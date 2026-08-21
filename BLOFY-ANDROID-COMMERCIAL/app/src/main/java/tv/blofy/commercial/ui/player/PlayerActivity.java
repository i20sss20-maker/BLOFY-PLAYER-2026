package tv.blofy.commercial.ui.player;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.lifecycle.Lifecycle;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Locale;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import tv.blofy.commercial.core.ApiClient;
import tv.blofy.commercial.core.LicenseGate;
import tv.blofy.commercial.core.LicensedActivity;
import tv.blofy.commercial.core.PlaybackRoutePolicy;
import tv.blofy.commercial.data.CatalogStore;
import tv.blofy.commercial.data.MediaRecord;
import tv.blofy.commercial.databinding.ActivityPlayerBinding;

@OptIn(markerClass = UnstableApi.class)
public final class PlayerActivity extends LicensedActivity implements Player.Listener {
    private ActivityPlayerBinding binding;
    private ApiClient api;
    private CatalogStore store;
    private ExoPlayer player;
    private String id, name, type, extension, originalExtension, playbackUrl, playbackExtension;
    private String relayUrl;
    private int fallbackStage;
    private boolean relayAttempted;
    private boolean historyRecorded;
    private boolean initialized;
    private volatile boolean resolving;
    private final ExecutorService worker = Executors.newFixedThreadPool(2);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicInteger resolveGeneration = new AtomicInteger();
    private final Runnable hideChrome = () -> {
        if (binding != null) {
            binding.topBar.setVisibility(View.GONE);
            binding.hint.setVisibility(View.GONE);
        }
    };
    private final Runnable timeout = () -> {
        if (player == null || player.getPlaybackState() == Player.STATE_READY) return;
        releasePlayer();
        if (!advanceAfterFailure()) {
            showError("المصدر لم يرسل فيديو خلال المهلة. جرّب إعادة الاتصال أو محتوى آخر.");
        }
    };

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        binding = ActivityPlayerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        api = new ApiClient(this);
        store = new CatalogStore(this);
        type = extra("type", "live");
        id = extra("id", "");
        name = extra("name", "BLOFY PLAYER");
        extension = normalize(extra("extension", isLive() ? "ts" : "mp4"));
        originalExtension = extension;
        binding.title.setText(name);
        binding.close.setOnClickListener(v -> finish());
        binding.retry.setOnClickListener(v -> retryFromStart());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                finish();
            }
        });
        if (!isLive()) binding.epg.setVisibility(View.GONE);
        hideSystemUi();
        initialized = true;
        resolveDirect();
        if (isLive()) loadEpg(id);
    }

    private boolean isLive() { return "live".equals(type); }
    private String extra(String key, String fallback) {
        String value = getIntent().getStringExtra(key);
        return value == null || value.isEmpty() ? fallback : value;
    }

    private void resolveDirect() {
        resolving = true;
        final int generation = resolveGeneration.incrementAndGet();
        final String requestedType = type;
        final String requestedId = id;
        final String requestedExtension = extension;
        showLoading();
        worker.execute(() -> {
            try {
                String apiType = "series".equals(requestedType) || "episode".equals(requestedType) ? "episode" : requestedType;
                JSONObject data = api.get("/api/native-link/" + ApiClient.encode(apiType) + "/" + ApiClient.encode(requestedId)
                        + "?ext=" + ApiClient.encode(requestedExtension));
                String path = data.optString("url");
                String relayPath = data.optString("relayUrl");
                final String signedRelay = PlaybackRoutePolicy.isSignedRelayPath(relayPath)
                        ? api.absoluteUrl(relayPath) : "";
                final String resolvedUrl;
                if (path.startsWith("/api/native-play")) {
                    resolvedUrl = api.resolveMediaRedirect(path);
                } else if (path.startsWith("/api/proxy")) {
                    resolvedUrl = api.absoluteUrl(path);
                } else {
                    throw new Exception("تعذر إصدار رابط Media3 آمن.");
                }
                final String mediaExtension = normalize(data.optString("extension", requestedExtension));
                // Never hand a provider's cleartext URL to Media3. New servers
                // always return a signed /api/proxy relayUrl; the legacy raw
                // route keeps v10 deployments usable during a rolling update.
                final boolean legacyCleartext = PlaybackRoutePolicy.isCleartextHttp(resolvedUrl);
                final String effectiveRelay = !signedRelay.isEmpty()
                        ? signedRelay : secureRelayUrl(requestedType, requestedId, requestedExtension);
                final String mediaUrl = legacyCleartext ? effectiveRelay : resolvedUrl;
                if (!isCurrent(generation, requestedId)) return;
                relayUrl = effectiveRelay;
                relayAttempted = legacyCleartext || path.startsWith("/api/proxy");
                playbackUrl = mediaUrl;
                playbackExtension = mediaExtension;
                resolving = false;
                runOnUiThread(() -> {
                    if (!isCurrent(generation, requestedId)) return;
                    if (getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) preparePlayer(mediaUrl, mediaExtension);
                });
            } catch (Exception error) {
                if (!isCurrent(generation, requestedId)) return;
                resolving = false;
                runOnUiThread(() -> {
                    if (!isCurrent(generation, requestedId)) return;
                    if (LicenseGate.isAuthorizationError(error)) {
                        LicenseGate.openActivation(this, "انتهى الاشتراك. جدّد التفعيل أو بيانات الباقة ثم سجّل الدخول.");
                    } else if (!advanceFallback()) {
                        showError(error.getMessage());
                    }
                });
            }
        });
    }

    private String secureRelayUrl(String requestedType, String requestedId, String requestedExtension) {
        String apiType = "series".equals(requestedType) || "episode".equals(requestedType)
                ? "episode" : requestedType;
        return api.absoluteUrl("/api/play/" + ApiClient.encode(apiType) + "/" + ApiClient.encode(requestedId)
                + "?ext=" + ApiClient.encode(requestedExtension) + "&raw=1");
    }

    private boolean isCurrent(int generation, String requestedId) {
        return generation == resolveGeneration.get() && requestedId.equals(id) && !isFinishing() && !isDestroyed();
    }

    private void prepareCompatibility(boolean transcodeVideo) {
        String apiType = "series".equals(type) || "episode".equals(type) ? "episode" : type;
        String url = api.absoluteUrl("/api/play/" + ApiClient.encode(apiType) + "/" + ApiClient.encode(id)
                + "?ext=" + ApiClient.encode(extension) + "&compat=" + (transcodeVideo ? "2" : "1"));
        playbackUrl = url;
        playbackExtension = "m3u8";
        preparePlayer(url, "m3u8");
    }

    private boolean advanceAfterFailure() {
        if (PlaybackRoutePolicy.canTryRelay(relayUrl, relayAttempted)) {
            relayAttempted = true;
            extension = originalExtension;
            playbackUrl = relayUrl;
            playbackExtension = originalExtension;
            showLoading();
            preparePlayer(relayUrl, originalExtension);
            return true;
        }
        return advanceFallback();
    }

    private void preparePlayer(String url, String mediaExtension) {
        if (isFinishing() || isDestroyed() || !getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) return;
        releasePlayer();
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(12_000)
                .setReadTimeoutMs(isLive() ? 35_000 : 55_000)
                .setAllowCrossProtocolRedirects(true)
                // Widely accepted by Xtream/CDN sources that reject generic
                // app user agents while still keeping BLOFY credentials away
                // from third-party hosts.
                .setUserAgent("VLC/3.0.20 LibVLC/3.0.20");
        // Compatibility streams remain on Railway and need the protected
        // session. Direct provider playback must never receive those secrets.
        if (url.startsWith(api.baseUrl() + "/")) http.setDefaultRequestProperties(api.authenticatedHeaders());
        else http.setDefaultRequestProperties(Collections.emptyMap());
        DefaultDataSource.Factory source = new DefaultDataSource.Factory(this, http);
        int flags = DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
                | DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES;
        DefaultExtractorsFactory extractors = new DefaultExtractorsFactory().setTsExtractorFlags(flags);
        DefaultMediaSourceFactory mediaFactory = new DefaultMediaSourceFactory(source, extractors);
        boolean stable = "stable".equals(getSharedPreferences("blofy_player_settings", MODE_PRIVATE).getString("buffer", "fast"));
        int minimum = stable ? (isLive() ? 5_000 : 12_000) : (isLive() ? 1_500 : 4_000);
        int maximum = stable ? (isLive() ? 24_000 : 55_000) : (isLive() ? 10_000 : 28_000);
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(minimum, maximum, isLive() ? 500 : 900, 1_100)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();
        player = new ExoPlayer.Builder(this, new DefaultRenderersFactory(this).setEnableDecoderFallback(true))
                .setMediaSourceFactory(mediaFactory)
                .setLoadControl(loadControl)
                .build();
        player.addListener(this);
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        player.setWakeMode(C.WAKE_MODE_NETWORK);
        binding.player.setPlayer(player);

        String quality = getSharedPreferences("blofy_player_settings", MODE_PRIVATE).getString("quality", "auto");
        TrackSelectionParameters.Builder tracks = player.getTrackSelectionParameters().buildUpon();
        if ("sd".equals(quality)) tracks.setMaxVideoSize(854, 480);
        else if ("hd".equals(quality)) tracks.setMaxVideoSize(1920, 1080);
        boolean subtitles = getSharedPreferences("blofy_player_settings", MODE_PRIVATE).getBoolean("subtitles", true);
        tracks.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitles);
        if (subtitles) tracks.setPreferredTextLanguage("ar").setSelectUndeterminedTextLanguage(true);
        player.setTrackSelectionParameters(tracks.build());

        MediaItem.Builder item = new MediaItem.Builder().setUri(url).setMediaId(id);
        String declaredMime = mime(mediaExtension);
        if (declaredMime != null) item.setMimeType(declaredMime);
        MediaSource media = "m3u8".equals(mediaExtension)
                ? new HlsMediaSource.Factory(source)
                    .setExtractorFactory(new DefaultHlsExtractorFactory(flags, true))
                    .createMediaSource(item.build())
                : mediaFactory.createMediaSource(item.build());
        if (isLive()) {
            // Let Media3 select the live edge. Passing position zero starts at
            // the beginning of a DVR window and can request expired segments.
            player.setMediaSource(media);
        } else {
            long position = getSharedPreferences("blofy_positions", MODE_PRIVATE).getLong(positionKey(), 0);
            player.setMediaSource(media, Math.max(0, position));
        }
        player.prepare();
        if (getSharedPreferences("blofy_player_settings", MODE_PRIVATE).getBoolean("autoplay", true)) player.play();
        binding.player.requestFocus();
        handler.removeCallbacks(timeout);
        handler.postDelayed(timeout, isLive() ? 18_000L : 32_000L);
    }

    private boolean advanceFallback() {
        if (isFinishing() || isDestroyed()) return false;
        if (isLive() && fallbackStage == 0) {
            fallbackStage = 1;
            extension = "m3u8".equals(extension) ? "ts" : "m3u8";
            resolveDirect();
            return true;
        }
        int maximum = isLive() ? 3 : 2;
        if (fallbackStage >= maximum) return false;
        fallbackStage++;
        // The alternate extension above is only a direct-play probe. Relay
        // and transcoding must start from the provider's original extension;
        // otherwise a valid TS source can accidentally be requested as m3u8.
        extension = originalExtension;
        showLoading();
        prepareCompatibility(fallbackStage == maximum);
        return true;
    }

    private void showLoading() {
        if (binding == null) return;
        binding.progress.setVisibility(View.VISIBLE);
        binding.errorPanel.setVisibility(View.GONE);
        binding.topBar.setVisibility(View.VISIBLE);
    }

    private void retryFromStart() {
        fallbackStage = 0;
        extension = originalExtension;
        playbackUrl = null;
        playbackExtension = null;
        relayUrl = null;
        relayAttempted = false;
        resolveDirect();
    }

    private void loadEpg(String requestedId) {
        worker.execute(() -> {
            try {
                JSONArray values = api.get("/api/epg/" + ApiClient.encode(requestedId)).optJSONArray("entries");
                JSONObject selected = null;
                long now = System.currentTimeMillis();
                if (values != null) for (int i = 0; i < values.length(); i++) {
                    JSONObject row = values.optJSONObject(i);
                    if (row == null) continue;
                    if (selected == null) selected = row;
                    if (row.optLong("start") <= now && now < row.optLong("end", Long.MAX_VALUE)) { selected = row; break; }
                }
                JSONObject current = selected;
                runOnUiThread(() -> {
                    if (!requestedId.equals(id) || binding == null) return;
                    binding.epg.setText(current == null ? "لا توجد بيانات برنامج حاليًا"
                            : "الآن: " + current.optString("title") + " • " + time(current.optLong("start")) + " – " + time(current.optLong("end")));
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> { if (requestedId.equals(id) && binding != null) binding.epg.setText("البث المباشر"); });
            }
        });
    }

    private void switchChannel(int direction) {
        if (!isLive()) return;
        final int generation = resolveGeneration.incrementAndGet();
        final String currentId = id;
        // Stop the previous channel before looking up the next one. Otherwise
        // its timeout/error callback can start a fallback and invalidate the
        // pending channel switch.
        resolving = true;
        releasePlayer();
        playbackUrl = null;
        playbackExtension = null;
        showLoading();
        worker.execute(() -> {
            final MediaRecord item;
            try {
                item = store.adjacentLive(currentId, direction);
            } catch (Exception error) {
                if (generation != resolveGeneration.get()) return;
                resolving = false;
                runOnUiThread(() -> {
                    if (generation != resolveGeneration.get() || isFinishing() || isDestroyed()) return;
                    showError(error.getMessage() == null
                            ? "تعذر قراءة القناة التالية من الكتالوج المحلي."
                            : error.getMessage());
                });
                return;
            }
            if (generation != resolveGeneration.get()) return;
            if (item == null) {
                resolving = false;
                runOnUiThread(() -> {
                    if (generation != resolveGeneration.get() || isFinishing() || isDestroyed()) return;
                    showError("لا توجد قناة أخرى في هذه القائمة.");
                });
                return;
            }
            runOnUiThread(() -> {
                if (generation != resolveGeneration.get() || isFinishing() || isDestroyed()) return;
                id = item.id;
                name = item.name;
                extension = normalize(item.extension.isEmpty() ? "ts" : item.extension);
                originalExtension = extension;
                fallbackStage = 0;
                historyRecorded = false;
                playbackUrl = null;
                playbackExtension = null;
                relayUrl = null;
                relayAttempted = false;
                binding.title.setText(name);
                binding.epg.setText("جلب دليل البرنامج…");
                resolveDirect();
                loadEpg(id);
            });
        });
    }

    private void showError(String message) {
        if (binding == null || isFinishing() || isDestroyed()) return;
        binding.progress.setVisibility(View.GONE);
        binding.errorPanel.setVisibility(View.VISIBLE);
        binding.error.setText(message == null || message.trim().isEmpty() ? "حدث خطأ غير متوقع." : message);
        binding.retry.requestFocus();
    }

    @Override public void onPlaybackStateChanged(int state) {
        if (binding == null) return;
        binding.progress.setVisibility(state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
        if (state == Player.STATE_READY) {
            handler.removeCallbacks(timeout);
            handler.removeCallbacks(hideChrome);
            handler.postDelayed(hideChrome, 4_500L);
            if (!historyRecorded) {
                historyRecorded = true;
                final String readyType = type;
                final String readyId = id;
                worker.execute(() -> store.addHistory(readyType, readyId));
            }
        }
    }

    @Override public void onPlayerError(PlaybackException error) {
        handler.removeCallbacks(timeout);
        final String failedUrl = playbackUrl;
        final int httpStatus = httpStatus(error);
        final int providerStatus = providerStatus(error);
        releasePlayer();
        if (PlaybackRoutePolicy.isBlofyUrl(api.baseUrl(), failedUrl)
                && PlaybackRoutePolicy.isBackendAuthorizationStatus(httpStatus, providerStatus)) {
            LicenseGate.openActivation(this, "انتهت جلسة التشغيل أو الاشتراك. حدّث التفعيل ثم سجّل الدخول.");
            return;
        }
        if (!advanceAfterFailure()) {
            String status = httpStatus > 0 ? " (HTTP " + httpStatus + ")" : "";
            showError("تعذر تشغيل المصدر بعد تجربة الوضع المباشر والوسيط والتوافق.\n"
                    + error.getErrorCodeName() + status);
        }
    }

    private static int httpStatus(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 8; depth++, current = current.getCause()) {
            if (current instanceof HttpDataSource.InvalidResponseCodeException) {
                return ((HttpDataSource.InvalidResponseCodeException) current).responseCode;
            }
        }
        return -1;
    }

    private static int providerStatus(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 8; depth++, current = current.getCause()) {
            if (!(current instanceof HttpDataSource.InvalidResponseCodeException)) continue;
            byte[] body = ((HttpDataSource.InvalidResponseCodeException) current).responseBody;
            if (body == null || body.length == 0) return -1;
            try {
                return new JSONObject(new String(body, StandardCharsets.UTF_8)).optInt("providerStatus", -1);
            } catch (Exception ignored) {
                return -1;
            }
        }
        return -1;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE
                    || event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_B) {
                finish();
                return true;
            }
            if (event.getKeyCode() == KeyEvent.KEYCODE_CHANNEL_UP) { switchChannel(1); return true; }
            if (event.getKeyCode() == KeyEvent.KEYCODE_CHANNEL_DOWN) { switchChannel(-1); return true; }
            if (event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && player != null) {
                if (player.isPlaying()) player.pause(); else player.play();
                return true;
            }
        }
        if (binding != null) binding.topBar.setVisibility(View.VISIBLE);
        return super.dispatchKeyEvent(event);
    }

    private String positionKey() { return "position_" + type + "_" + id; }

    private void releasePlayer() {
        handler.removeCallbacks(timeout);
        handler.removeCallbacks(hideChrome);
        if (player == null) return;
        if (!isLive()) {
            long position = player.getCurrentPosition();
            long duration = player.getDuration();
            if (duration > 0 && position > duration - 30_000) position = 0;
            getSharedPreferences("blofy_positions", MODE_PRIVATE).edit().putLong(positionKey(), Math.max(0, position)).apply();
        }
        if (binding != null) binding.player.setPlayer(null);
        player.removeListener(this);
        player.release();
        player = null;
    }

    private static String normalize(String ext) {
        String value = ext == null ? "" : ext.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "");
        return value.isEmpty() ? "mp4" : value;
    }

    private static String mime(String ext) {
        if ("m3u8".equals(ext)) return MimeTypes.APPLICATION_M3U8;
        if ("mpd".equals(ext)) return MimeTypes.APPLICATION_MPD;
        if ("ts".equals(ext) || "mts".equals(ext) || "m2ts".equals(ext)) return MimeTypes.VIDEO_MP2T;
        if ("mp4".equals(ext) || "m4v".equals(ext)) return MimeTypes.VIDEO_MP4;
        if ("mkv".equals(ext)) return MimeTypes.VIDEO_MATROSKA;
        if ("webm".equals(ext)) return MimeTypes.VIDEO_WEBM;
        return null;
    }

    private static String time(long value) {
        return value <= 0 ? "" : DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(value));
    }

    @Override protected void onStart() {
        super.onStart();
        if (!initialized || player != null) return;
        if (playbackUrl != null) preparePlayer(playbackUrl, playbackExtension == null ? extension : playbackExtension);
        else if (!resolving) resolveDirect();
    }

    @Override protected void onStop() {
        resolveGeneration.incrementAndGet();
        resolving = false;
        releasePlayer();
        // Provider URLs can expire or be tied to one connection. Always ask
        // BLOFY for a fresh link when this screen returns to the foreground.
        playbackUrl = null;
        playbackExtension = null;
        relayUrl = null;
        relayAttempted = false;
        extension = originalExtension;
        fallbackStage = 0;
        super.onStop();
    }

    @Override protected void onDestroy() {
        resolveGeneration.incrementAndGet();
        worker.shutdownNow();
        if (store != null) store.close();
        binding = null;
        super.onDestroy();
    }

    private void hideSystemUi() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }
}
