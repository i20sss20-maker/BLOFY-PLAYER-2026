package tv.blofy.commercial.ui.sync;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import tv.blofy.commercial.core.ApiClient;
import tv.blofy.commercial.data.CatalogStore;
import tv.blofy.commercial.data.MediaRecord;
import tv.blofy.commercial.databinding.ActivitySyncBinding;
import tv.blofy.commercial.ui.home.HomeActivity;

public final class SyncActivity extends AppCompatActivity {
    private static final String TAG = "BlofySync";
    private static final int NATIVE_PAGE_SIZE = 2_000;
    private ActivitySyncBinding binding;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean destroyed = new AtomicBoolean(false);
    private final AtomicInteger lastProgress = new AtomicInteger(0);
    private final Map<String, Integer> formats = new LinkedHashMap<>();
    private CatalogStore store;
    private ApiClient api;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySyncBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        api = new ApiClient(this);
        store = new CatalogStore(this);
        binding.retry.setOnClickListener(view -> {
            binding.retry.setVisibility(android.view.View.GONE);
            worker.execute(this::sync);
        });
        worker.execute(this::sync);
    }

    private void sync() {
        try {
            ensureActive();
            formats.clear();
            getSharedPreferences("blofy_commercial_state", MODE_PRIVATE).edit().putBoolean("catalog_ready", false).apply();
            emit(4);
            if (!api.get("/api/health").optBoolean("ok")) throw new Exception("خدمة BLOFY غير جاهزة.");
            ensureActive();
            emit(10);
            JSONObject session = api.get("/api/session").optJSONObject("session");
            if (session == null) throw new Exception("لم يتم العثور على جلسة الباقة.");
            ensureActive();
            store.clearCatalog();
            store.putMeta("kind", session.optString("kind"));
            store.putMeta("server", session.optString("serverName"));
            importType("live", "القنوات المباشرة", 12, 42);
            importType("movies", "الأفلام", 42, 70);
            importType("series", "المسلسلات", 70, 94);
            String profile = profile();
            store.putMeta("profile", profile);
            store.putMeta("last_sync", String.valueOf(System.currentTimeMillis()));
            getSharedPreferences("blofy_commercial_state", MODE_PRIVATE).edit().putBoolean("catalog_ready", true).apply();
            emit(100);
            Thread.sleep(650);
            ensureActive();
            store.close();
            runOnUiThread(() -> {
                if (destroyed.get() || isFinishing() || isDestroyed()) return;
                startActivity(new Intent(this, HomeActivity.class));
                finish();
            });
        } catch (Exception error) {
            if (destroyed.get() || Thread.currentThread().isInterrupted()) return;
            Log.e(TAG, "Catalog sync failed", error);
            emit(lastProgress.get());
            runOnUiThread(() -> {
                if (destroyed.get() || isFinishing() || isDestroyed()) return;
                binding.retry.setVisibility(android.view.View.VISIBLE);
                binding.retry.requestFocus();
            });
        } finally {
            if (destroyed.get() && store != null) store.close();
        }
    }

    private void importType(String type, String label, int start, int end) throws Exception {
        emit(start);
        JSONArray categories = api.getCatalog("/api/categories?type=" + ApiClient.encode(type)).optJSONArray("categories");
        ensureActive();
        if (categories != null) for (int i = 0; i < categories.length(); i++) {
            JSONObject row = categories.optJSONObject(i);
            if (row != null) store.saveCategory(type, row.optString("id"), row.optString("name"));
        }
        String endpoint = "/api/catalog?native=1&type=" + ApiClient.encode(type) + "&page_size=" + NATIVE_PAGE_SIZE + "&page=";
        JSONObject first = api.getCatalog(endpoint + "1");
        ensureActive();
        if (!first.has("total")) throw new Exception("لم يرسل الخادم إجمالي " + label + ".");
        int total = Math.max(0, first.optInt("total"));
        int pageSize = Math.max(1, first.optInt("pageSize", NATIVE_PAGE_SIZE));
        int pages = Math.max(1, (int) Math.ceil(total / (double) pageSize));
        int loaded = save(first.optJSONArray("items"), type);
        emit(progress(start, end, loaded, total));
        for (int page = 2; page <= pages; page++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("تم إيقاف المزامنة.");
            JSONArray batch = api.getCatalog(endpoint + page).optJSONArray("items");
            ensureActive();
            int received = save(batch, type);
            if (received == 0) break;
            loaded = Math.min(total, loaded + received);
            emit(progress(start, end, loaded, total));
        }
        if (loaded < total) {
            throw new Exception("توقفت قراءة " + label + " عند " + loaded + " من " + total + ". اضغط إعادة المحاولة.");
        }
        emit(end);
    }

    private int save(JSONArray values, String type) {
        List<MediaRecord> rows = new ArrayList<>();
        if (values != null) for (int i = 0; i < values.length(); i++) {
            JSONObject row = values.optJSONObject(i);
            if (row == null) continue;
            MediaRecord item = MediaRecord.from(row, type); rows.add(item);
            String ext = item.extension.isEmpty() ? "unknown" : item.extension.toLowerCase(Locale.US);
            formats.put(ext, formats.containsKey(ext) ? formats.get(ext) + 1 : 1);
        }
        store.saveMedia(rows);
        return rows.size();
    }

    private static int progress(int start, int end, int loaded, int total) {
        if (total <= 0) return end;
        return Math.min(end, start + Math.round((end - start) * (loaded / (float) total)));
    }

    private String profile() {
        int hls = formats.containsKey("m3u8") ? formats.get("m3u8") : 0;
        int ts = formats.containsKey("ts") ? formats.get("ts") : 0;
        int files = (formats.containsKey("mp4") ? formats.get("mp4") : 0) + (formats.containsKey("mkv") ? formats.get("mkv") : 0);
        if (ts >= hls && ts >= files) return "Media3 • بث TS مباشر منخفض التأخير";
        if (hls >= files) return "Media3 • بث HLS متكيف";
        return "Media3 • فيديو مباشر مع استكمال المشاهدة";
    }

    private void emit(int percent) {
        if (destroyed.get()) return;
        int safePercent = Math.max(0, Math.min(100, percent));
        lastProgress.set(safePercent);
        runOnUiThread(() -> {
            if (destroyed.get() || isFinishing() || isDestroyed()) return;
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
        super.onDestroy();
    }
}
