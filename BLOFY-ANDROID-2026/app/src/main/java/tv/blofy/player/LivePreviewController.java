package tv.blofy.player;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@OptIn(markerClass = UnstableApi.class)
final class LivePreviewController {
    private final Context context;
    private final PlayerView view;
    private final BlofyApi api;
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger generation = new AtomicInteger();
    private ExoPlayer player;
    private Runnable pending;
    private BlofyModels.Media pendingItem;

    LivePreviewController(Context context) {
        this.context = context;
        this.api = new BlofyApi(context);
        view = new PlayerView(context);
        view.setUseController(false);
        view.setKeepScreenOn(false);
        view.setFocusable(false);
        view.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    PlayerView view() { return view; }

    void preview(BlofyModels.Media item) {
        if (item == null || item.id == null || item.id.isEmpty()) return;
        pendingItem = item;
        if (pending != null) main.removeCallbacks(pending);
        pending = () -> startPreview(pendingItem);
        main.postDelayed(pending, 320L);
    }

    private void startPreview(BlofyModels.Media item) {
        if (item == null) return;
        int token = generation.incrementAndGet();
        network.execute(() -> {
            try {
                String ext = PlaybackPolicy.normalizeExtension(item.extension, "ts");
                JSONObject result = api.get("/api/native-link/live/" + BlofyApi.encode(item.id)
                        + "?ext=" + BlofyApi.encode(ext));
                String url = result.optString("url", "");
                if (!url.startsWith("http")) return;
                main.post(() -> {
                    if (token != generation.get()) return;
                    open(url);
                });
            } catch (Exception ignored) {
                // Preview is optional; never block channel navigation because of it.
            }
        });
    }

    private void open(String url) {
        releasePlayer();
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(4_500)
                .setReadTimeoutMs(8_000);
        DefaultDataSource.Factory data = new DefaultDataSource.Factory(context, http);
        DefaultLoadControl load = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(1_000, 9_000, 250, 750)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();
        player = new ExoPlayer.Builder(context)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(data))
                .setLoadControl(load)
                .build();
        player.setVolume(0f);
        view.setPlayer(player);
        player.setMediaItem(MediaItem.fromUri(url));
        player.prepare();
        player.play();
    }

    private void releasePlayer() {
        if (player == null) return;
        view.setPlayer(null);
        player.release();
        player = null;
    }

    void release() {
        generation.incrementAndGet();
        if (pending != null) main.removeCallbacks(pending);
        releasePlayer();
        network.shutdownNow();
    }
}
