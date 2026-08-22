package tv.blofy.commercial.ui.sync;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import tv.blofy.commercial.data.CatalogStore;
import tv.blofy.commercial.data.MediaRecord;
import tv.blofy.commercial.databinding.ActivitySyncBinding;
import tv.blofy.commercial.provider.M3uClient;
import tv.blofy.commercial.provider.PlaylistProfile;
import tv.blofy.commercial.provider.PlaylistRepository;
import tv.blofy.commercial.provider.PlaylistStateStore;
import tv.blofy.commercial.provider.ProviderProfile;
import tv.blofy.commercial.provider.XtreamClient;
import tv.blofy.commercial.ui.activation.ActivationActivity;
import tv.blofy.commercial.ui.home.HomeActivity;

/** Provider sync runs directly on-device and is isolated per playlist. */
public final class SyncActivity extends AppCompatActivity {
    private static final String TAG = "BlofySync";
    private static final int DB_BATCH = 500;

    private ActivitySyncBinding binding;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean destroyed = new AtomicBoolean(false);
    private final AtomicInteger lastProgress = new AtomicInteger(0);
    private final Map<String, Integer> formats = new LinkedHashMap<>();
    private CatalogStore store;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySyncBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        store = new CatalogStore(this);
        binding.retry.setOnClickListener(view -> {
            binding.retry.setVisibility(View.GONE);
            binding.error.setVisibility(View.GONE);
            binding.error.setText("");
            lastProgress.set(0);
            binding.progress.setProgressCompat(0, false);
            binding.percent.setText("0%");
            worker.execute(this::sync);
        });
        worker.execute(this::sync);
    }

    private void sync() {
        PlaylistProfile playlist = PlaylistRepository.active(this);
        if (playlist == null) playlist = PlaylistRepository.importLegacySingleProfile(this);
        if (playlist == null || playlist.provider == null) {
            runOnUiThread(() -> {
                startActivity(new Intent(this, ActivationActivity.class).putExtra("force_form", true));
                finish();
            });
            return;
        }
        final PlaylistProfile activePlaylist = playlist;
        try {
            ensureActive();
            formats.clear();
            PlaylistStateStore.markSyncing(this, activePlaylist.id);
            runOnUiThread(() -> {
                if (binding == null) return;
                binding.error.setVisibility(View.GONE);
                binding.retry.setVisibility(View.GONE);
            });
            emit(4);

            ProviderProfile profile = activePlaylist.provider;
            store.clearCatalog();
            store.putMeta("playlist_id", activePlaylist.id);
            store.putMeta("kind", profile.kind);
            store.putMeta("server", profile.name.isEmpty()
                    ? (profile.isXtream() ? profile.serverUrl : "M3U") : profile.name);

            if (profile.isXtream()) syncXtream(profile);
            else syncM3u(profile);

            store.putMeta("profile", playbackProfile());
            store.putMeta("last_sync", String.valueOf(System.currentTimeMillis()));
            PlaylistStateStore.markReady(this, activePlaylist.id);
            emit(100);
            Thread.sleep(250);
            ensureActive();
            runOnUiThread(() -> {
                if (destroyed.get() || isFinishing() || isDestroyed()) return;
                startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
                finish();
            });
        } catch (Exception error) {
            if (destroyed.get() || Thread.currentThread().isInterrupted()) return;
            Log.e(TAG, "Direct provider sync failed", error);
            emit(lastProgress.get());
            String message = error.getMessage();
            if (message == null || message.trim().isEmpty()) message = "تعذر قراءة الباقة مباشرة من المزوّد.";
            final String visibleMessage = message;
            runOnUiThread(() -> {
                if (destroyed.get() || isFinishing() || isDestroyed() || binding == null) return;
                binding.error.setText(visibleMessage);
                binding.error.setVisibility(View.VISIBLE);
                binding.retry.setVisibility(View.VISIBLE);
                binding.retry.requestFocus();
            });
        }
    }

    private void syncXtream(ProviderProfile profile) throws Exception {
        XtreamClient client = new XtreamClient(profile);
        emit(8);
        try {
            client.validate();
            importXtreamType(client, "live", 12, 42);
            importXtreamType(client, "movies", 42, 70);
            importXtreamType(client, "series", 70, 94);
        } catch (Exception apiError) {
            String message = apiError.getMessage() == null ? "" : apiError.getMessage();
            if (!message.contains("HTTP 403")) throw apiError;
            Log.w(TAG, "Xtream API blocked with 403; switching to get.php/M3U fallback");
            store.clearCatalog();
            store.putMeta("kind", "m3u");
            store.putMeta("server", (profile.name.isEmpty() ? profile.serverUrl : profile.name) + " • M3U fallback");
            emit(10);
            syncM3u(client.playlistFallbackProfile());
        }
    }

    private void importXtreamType(XtreamClient client, String type, int start, int end) throws Exception {
        ensureActive();
        emit(start);
        JSONArray categories = client.categories(type);
        for (int i = 0; i < categories.length(); i++) {
            JSONObject row = categories.optJSONObject(i);
            if (row != null) store.saveCategory(type, row.optString("id"), row.optString("name"));
        }

        List<MediaRecord> batch = new ArrayList<>(DB_BATCH);
        int[] loaded = {0};
        client.streamCatalog(type, item -> {
            ensureActive();
            MediaRecord row = MediaRecord.from(item, type);
            batch.add(row);
            trackFormat(row);
            loaded[0]++;
            if (batch.size() >= DB_BATCH) {
                store.saveMedia(new ArrayList<>(batch));
                batch.clear();
                int estimated = Math.min(end - 1, start + Math.max(1, loaded[0] / 2500));
                emit(estimated);
            }
        });
        if (!batch.isEmpty()) store.saveMedia(batch);
        emit(end);
    }

    private void syncM3u(ProviderProfile profile) throws Exception {
        emit(10);
        M3uClient client = new M3uClient(profile);
        Map<String, List<MediaRecord>> batches = new LinkedHashMap<>();
        batches.put("live", new ArrayList<>());
        batches.put("movies", new ArrayList<>());
        batches.put("series", new ArrayList<>());
        Set<String> savedCategories = new HashSet<>();
        int[] loaded = {0};

        client.stream((type, categoryId, categoryName, item) -> {
            ensureActive();
            String categoryKey = type + "\n" + categoryId;
            if (savedCategories.add(categoryKey)) store.saveCategory(type, categoryId, categoryName);
            List<MediaRecord> batch = batches.get(type);
            if (batch == null) batch = batches.get("live");
            batch.add(item);
            trackFormat(item);
            loaded[0]++;
            if (batch.size() >= DB_BATCH) {
                store.saveMedia(new ArrayList<>(batch));
                batch.clear();
            }
            if (loaded[0] % 2000 == 0) emit(Math.min(94, 12 + loaded[0] / 3000));
        });
        for (List<MediaRecord> batch : batches.values()) if (!batch.isEmpty()) store.saveMedia(batch);
        emit(94);
    }

    private void trackFormat(MediaRecord item) {
        String ext = item.extension.isEmpty() ? "unknown" : item.extension.toLowerCase(Locale.US);
        formats.put(ext, formats.containsKey(ext) ? formats.get(ext) + 1 : 1);
    }

    private String playbackProfile() {
        int hls = formats.containsKey("m3u8") ? formats.get("m3u8") : 0;
        int ts = formats.containsKey("ts") ? formats.get("ts") : 0;
        int files = (formats.containsKey("mp4") ? formats.get("mp4") : 0)
                + (formats.containsKey("mkv") ? formats.get("mkv") : 0);
        if (ts >= hls && ts >= files) return "Media3 + LibVLC • TS مباشر من المزود";
        if (hls >= files) return "Media3 + LibVLC • HLS مباشر من المزود";
        return "Media3 + LibVLC • VOD مباشر من المزود";
    }

    private void emit(int percent) {
        if (destroyed.get()) return;
        int safePercent = Math.max(0, Math.min(100, percent));
        if (safePercent < lastProgress.get()) return;
        lastProgress.set(safePercent);
        runOnUiThread(() -> {
            if (destroyed.get() || isFinishing() || isDestroyed() || binding == null) return;
            binding.progress.setProgressCompat(safePercent, true);
            binding.percent.setText(safePercent + "%");
        });
    }

    private void ensureActive() throws InterruptedException {
        if (destroyed.get() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("تم إيقاف المزامنة.");
        }
    }

    @Override protected void onDestroy() {
        destroyed.set(true);
        worker.shutdownNow();
        binding = null;
        super.onDestroy();
    }
}
