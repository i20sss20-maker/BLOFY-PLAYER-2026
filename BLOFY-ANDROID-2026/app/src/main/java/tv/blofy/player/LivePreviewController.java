package tv.blofy.player;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.ViewGroup;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import org.json.JSONObject;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@OptIn(markerClass = UnstableApi.class)
final class LivePreviewController implements Player.Listener {
    interface Listener {
        void loading();
        void firstFrame();
        void error();
    }

    private static final LruCache<String, Resolved> URL_CACHE = new LruCache<>(48);
    private static final LruCache<String, Resolved> URL_CACHE_BY_URL = new LruCache<>(48);
    private final Context context;
    private final PlayerView view;
    private final BlofyApi api;
    private final ExecutorService network = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
            new ThreadPoolExecutor.DiscardOldestPolicy());
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger generation = new AtomicInteger();
    private int openedGeneration = -1;
    private ExoPlayer player;
    private String playerReferer = "";
    private Runnable pending;
    private Listener listener;
    private Future<?> resolveTask;
    private BlofyApi.Cancellation resolveCancellation;
    private String openedCacheKey = "";
    private Resolved openedResolved;
    private final Runnable previewTimeout = () -> {
        if (openedGeneration != generation.get()) return;
        if (player != null) player.stop();
        evictOpened();
        if (listener != null) listener.error();
    };

    LivePreviewController(Context context) {
        this.context = context;
        this.api = new BlofyApi(context);
        view = new PlayerView(context);
        view.setUseController(false);
        view.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
        view.setShutterBackgroundColor(Color.TRANSPARENT);
        view.setKeepScreenOn(false);
        view.setFocusable(false);
        view.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    PlayerView view() { return view; }

    String resolvedUrl(BlofyModels.Media item) {
        if (item == null) return "";
        String key = cacheKey(item);
        Resolved value = URL_CACHE.get(key);
        if (value == null || value.expired()) {
            URL_CACHE.remove(key);
            return "";
        }
        return value.url;
    }

    String resolvedReferer(BlofyModels.Media item) {
        if (item == null) return "";
        Resolved value = URL_CACHE.get(cacheKey(item));
        return value == null || value.expired() ? "" : value.referer;
    }

    static String resolvedExtension(String url, String fallback) {
        if (url == null || url.isEmpty()) return fallback;
        Resolved value = URL_CACHE_BY_URL.get(url);
        if (value == null || value.expired()) {
            URL_CACHE_BY_URL.remove(url);
            return fallback;
        }
        return PlaybackPolicy.normalizeExtension(value.extension, fallback);
    }

    void setListener(Listener listener) { this.listener = listener; }

    void preview(BlofyModels.Media item) {
        if (item == null || item.id == null || item.id.isEmpty()) return;
        int token = generation.incrementAndGet();
        if (listener != null) listener.loading();
        if (pending != null) main.removeCallbacks(pending);
        cancelResolve();
        pending = () -> startPreview(item, token);
        main.postDelayed(pending, 110L);
    }

    private void startPreview(BlofyModels.Media item, int token) {
        if (item == null) return;
        String cacheKey = cacheKey(item);
        Resolved cached = URL_CACHE.get(cacheKey);
        if (cached != null && !cached.expired()) {
            if (token == generation.get()) open(cacheKey, cached, token);
            return;
        }
        if (cached != null) evict(cacheKey, cached);
        BlofyApi.Cancellation cancellation = new BlofyApi.Cancellation();
        resolveCancellation = cancellation;
        resolveTask = network.submit(() -> {
            try {
                String ext = PlaybackPolicy.normalizeExtension(item.extension, "ts");
                JSONObject result = api.getPlayback("/api/native-link/live/" + BlofyApi.encode(item.id)
                        + "?ext=" + BlofyApi.encode(ext), cancellation);
                String url = result.optString("url", "");
                if (url.startsWith("/")) {
                    url = BuildConfig.BLOFY_BASE_URL.replaceAll("/+$", "") + url;
                }
                String resolvedExt = PlaybackPolicy.normalizeExtension(result.optString("extension", ext), ext);
                String referer = result.optString("referer", "");
                if (!url.startsWith("http")) throw new IllegalStateException("invalid preview url");
                final String resolvedUrl = url;
                Resolved resolved = new Resolved(resolvedUrl, resolvedExt, referer);
                main.post(() -> {
                    if (token != generation.get()) return;
                    open(cacheKey, resolved, token);
                });
            } catch (Exception ignored) {
                main.post(() -> {
                    if (token == generation.get() && listener != null) listener.error();
                });
                // Preview is optional; never block channel navigation because of it.
            }
        });
    }

    private String cacheKey(BlofyModels.Media item) {
        return api.playbackSessionKey() + ":" + item.id + ":"
                + PlaybackPolicy.normalizeExtension(item.extension, "ts");
    }

    private void ensurePlayer(String referer) {
        String normalized = referer == null ? "" : referer;
        if (player != null && normalized.equals(playerReferer)) return;
        if (player != null) {
            view.setPlayer(null);
            player.release();
            player = null;
        }
        playerReferer = normalized;
        DataSource.Factory data = PlaybackTransportFactory.create(context, false, network,
                3_000, 7_000, 0, normalized);
        DefaultLoadControl load = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(800, 6_000, 250, 450)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(context)
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);
        player = new ExoPlayer.Builder(context, renderers)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(data))
                .setLoadControl(load)
                .build();
        player.addListener(this);
        player.setVolume(0f);
        view.setPlayer(player);
    }

    private void open(String cacheKey, Resolved resolved, int token) {
        if (token != generation.get()) return;
        try {
            ensurePlayer(resolved.referer);
            openedGeneration = token;
            openedCacheKey = cacheKey;
            openedResolved = resolved;
            main.removeCallbacks(previewTimeout);
            MediaItem.Builder item = new MediaItem.Builder().setUri(resolved.url);
            String mime = PlaybackPolicy.mimeType(resolved.extension);
            if (mime != null) item.setMimeType(mime);
            player.stop();
            player.clearMediaItems();
            player.setMediaItem(item.build());
            player.prepare();
            player.play();
            main.postDelayed(previewTimeout, PlaybackPolicy.PREVIEW_STARTUP_TIMEOUT_MS);
        } catch (Throwable ignored) {
            evict(cacheKey, resolved);
            if (listener != null) listener.error();
        }
    }

    @Override public void onRenderedFirstFrame() {
        if (openedGeneration == generation.get()) {
            main.removeCallbacks(previewTimeout);
            if (openedResolved != null && !openedCacheKey.isEmpty()) {
                URL_CACHE.put(openedCacheKey, openedResolved);
                URL_CACHE_BY_URL.put(openedResolved.url, openedResolved);
            }
            if (listener != null) listener.firstFrame();
        }
    }

    @Override public void onPlayerError(PlaybackException error) {
        if (openedGeneration == generation.get()) {
            main.removeCallbacks(previewTimeout);
            evictOpened();
            if (listener != null) listener.error();
        }
    }

    void release() {
        generation.incrementAndGet();
        openedGeneration = -1;
        if (pending != null) main.removeCallbacks(pending);
        cancelResolve();
        main.removeCallbacks(previewTimeout);
        if (player != null) {
            view.setPlayer(null);
            player.release();
            player = null;
        }
        network.shutdownNow();
    }

    private void cancelResolve() {
        BlofyApi.Cancellation cancellation = resolveCancellation;
        resolveCancellation = null;
        if (cancellation != null) cancellation.cancel();
        Future<?> task = resolveTask;
        resolveTask = null;
        if (task != null) task.cancel(true);
    }

    private void evictOpened() {
        if (openedResolved != null) evict(openedCacheKey, openedResolved);
        openedCacheKey = "";
        openedResolved = null;
    }

    private static void evict(String cacheKey, Resolved value) {
        if (cacheKey != null && !cacheKey.isEmpty()) URL_CACHE.remove(cacheKey);
        if (value != null && value.url != null) URL_CACHE_BY_URL.remove(value.url);
    }

    private static final class Resolved {
        final String url;
        final String extension;
        final String referer;
        final long createdAt = android.os.SystemClock.elapsedRealtime();
        Resolved(String url, String extension, String referer) {
            this.url = url;
            this.extension = extension;
            this.referer = referer == null ? "" : referer;
        }
        boolean expired() {
            return android.os.SystemClock.elapsedRealtime() - createdAt > 10 * 60_000L;
        }
    }
}
