package tv.blofy.commercial.ui.player;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
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
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
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

import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import tv.blofy.commercial.core.LicensedActivity;
import tv.blofy.commercial.data.CatalogStore;
import tv.blofy.commercial.data.MediaRecord;
import tv.blofy.commercial.databinding.ActivityPlayerBinding;
import tv.blofy.commercial.provider.ProviderProfile;
import tv.blofy.commercial.provider.ProviderProfileStore;
import tv.blofy.commercial.provider.XtreamClient;

/** Media3 first, direct provider traffic only. LibVLC remains an isolated lazy fallback. */
@OptIn(markerClass = UnstableApi.class)
public final class PlayerActivity extends LicensedActivity implements Player.Listener {
    private static final String PROVIDER_USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20";

    private ActivityPlayerBinding binding;
    private CatalogStore store;
    private ExoPlayer player;

    private String id, name, type, extension, originalExtension, playbackUrl, playbackExtension;
    private boolean historyRecorded;
    private boolean initialized;
    private boolean vlcAttempted;
    private volatile boolean resolving;

    private final ExecutorService worker = Executors.newFixedThreadPool(2);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicInteger resolveGeneration = new AtomicInteger();
    private final AtomicInteger providerHttpStatus = new AtomicInteger(-1);

    private final Runnable hideChrome = () -> {
        if (binding != null) {
            binding.topBar.setVisibility(View.GONE);
            binding.hint.setVisibility(View.GONE);
        }
    };

    private final Runnable timeout = () -> {
        if (player == null || player.getPlaybackState() == Player.STATE_READY) return;
        int observed = providerHttpStatus.get();
        releasePlayer(true);
        if (observed == 401 || observed == 403 || observed == 456) showProviderRejected(observed);
        else showError("المزوّد لم يرسل فيديو خلال المهلة. التشغيل مباشر من الجهاز.");
    };

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        binding = ActivityPlayerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        store = new CatalogStore(this);
        type = extra("type", "live");
        id = extra("id", "");
        name = extra("name", "BLOFY PLAYER");
        extension = normalize(extra("extension", isLive() ? "ts" : "mp4"));
        originalExtension = extension;

        binding.title.setText(name);
        binding.close.setOnClickListener(v -> finish());
        binding.retry.setOnClickListener(v -> retryDirect());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { finish(); }
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

    /** Builds the provider URL locally. No BLOFY/Railway media endpoint is involved. */
    private void resolveDirect() {
        resolving = true;
        final int generation = resolveGeneration.incrementAndGet();
        final String requestedType = type;
        final String requestedId = id;
        final String requestedExtension = extension;
        showLoading();

        worker.execute(() -> {
            try {
                ProviderProfile profile = ProviderProfileStore.load(this);
                if (profile == null) throw new Exception("بيانات الباقة غير موجودة على الجهاز.");
                final String directUrl;
                if (requestedId.startsWith("http://") || requestedId.startsWith("https://")) {
                    directUrl = requestedId;
                } else if (profile.isXtream()) {
                    directUrl = new XtreamClient(profile).playbackUrl(requestedType, requestedId, requestedExtension);
                } else {
                    directUrl = requestedId;
                }
                if (!(directUrl.startsWith("http://") || directUrl.startsWith("https://"))) {
                    throw new Exception("رابط التشغيل المحلي غير صالح.");
                }
                if (!isCurrent(generation, requestedId)) return;
                playbackUrl = directUrl;
                playbackExtension = requestedExtension;
                resolving = false;
                runOnUiThread(() -> {
                    if (!isCurrent(generation, requestedId)) return;
                    if (getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
                        routeSelectedPlayer(directUrl, requestedExtension);
                    }
                });
            } catch (Exception error) {
                if (!isCurrent(generation, requestedId)) return;
                resolving = false;
                runOnUiThread(() -> {
                    if (isCurrent(generation, requestedId)) showError(error.getMessage());
                });
            }
        });
    }

    private void routeSelectedPlayer(String url, String mediaExtension) {
        String selected = getSharedPreferences("blofy_player_settings", MODE_PRIVATE)
                .getString("player_engine", "auto");
        String contentMime = mime(mediaExtension);
        if ("libvlc".equals(selected)) {
            playbackUrl = url;
            playbackExtension = mediaExtension;
            launchVlcFallback();
            return;
        }
        if ("mx_free".equals(selected)) {
            if (ExternalPlayerLauncher.launch(this, ExternalPlayerLauncher.MX_FREE, url, contentMime, name)) return;
        } else if ("mx_pro".equals(selected)) {
            if (ExternalPlayerLauncher.launch(this, ExternalPlayerLauncher.MX_PRO, url, contentMime, name)) return;
        } else if ("vlc_external".equals(selected)) {
            if (ExternalPlayerLauncher.launch(this, ExternalPlayerLauncher.VLC, url, contentMime, name)) return;
        } else if ("external".equals(selected)) {
            if (ExternalPlayerLauncher.launchChooser(this, url, contentMime, name)) return;
        }
        prepareMedia3Direct(url, mediaExtension);
    }

    private boolean isCurrent(int generation, String requestedId) {
        return generation == resolveGeneration.get() && requestedId.equals(id) && !isFinishing() && !isDestroyed();
    }

    private void prepareMedia3Direct(String url, String mediaExtension) {
        if (isFinishing() || isDestroyed()
                || !getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) return;
        releasePlayer(false);
        providerHttpStatus.set(-1);

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(isLive() ? 35 : 60, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .addNetworkInterceptor(chain -> {
                    okhttp3.Response response = chain.proceed(chain.request());
                    providerHttpStatus.set(response.code());
                    return response;
                })
                .build();

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "*/*");
        headers.put("Accept-Encoding", "identity");
        headers.put("Cache-Control", "no-cache");
        headers.put("Icy-MetaData", "1");

        OkHttpDataSource.Factory http = new OkHttpDataSource.Factory(client)
                .setUserAgent(PROVIDER_USER_AGENT)
                .setDefaultRequestProperties(headers);
        DefaultDataSource.Factory source = new DefaultDataSource.Factory(this, http);

        int flags = DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
                | DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES;
        DefaultExtractorsFactory extractors = new DefaultExtractorsFactory().setTsExtractorFlags(flags);
        DefaultMediaSourceFactory mediaFactory = new DefaultMediaSourceFactory(source, extractors);

        boolean stable = "stable".equals(getSharedPreferences("blofy_player_settings", MODE_PRIVATE)
                .getString("buffer", "fast"));
        int minimum = stable ? (isLive() ? 4_000 : 10_000) : (isLive() ? 1_200 : 3_000);
        int maximum = stable ? (isLive() ? 20_000 : 45_000) : (isLive() ? 8_000 : 24_000);
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(minimum, maximum, isLive() ? 400 : 800, 1_000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();

        player = new ExoPlayer.Builder(this,
                new DefaultRenderersFactory(this).setEnableDecoderFallback(true))
                .setMediaSourceFactory(mediaFactory)
                .setLoadControl(loadControl)
                .build();
        player.addListener(this);
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
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

        if (isLive()) player.setMediaSource(media);
        else {
            long position = getSharedPreferences("blofy_positions", MODE_PRIVATE).getLong(positionKey(), 0L);
            player.setMediaSource(media, Math.max(0L, position));
        }
        player.prepare();
        if (getSharedPreferences("blofy_player_settings", MODE_PRIVATE).getBoolean("autoplay", true)) player.play();
        binding.player.requestFocus();
        handler.removeCallbacks(timeout);
        handler.postDelayed(timeout, isLive() ? 15_000L : 25_000L);
    }

    private void launchVlcFallback() {
        if (vlcAttempted || playbackUrl == null || playbackUrl.isEmpty()) return;
        vlcAttempted = true;
        startActivity(new Intent(this, VlcFallbackActivity.class)
                .putExtra("url", playbackUrl)
                .putExtra("live", isLive())
                .putExtra("name", name));
    }

    private void retryDirect() {
        releasePlayer(true);
        vlcAttempted = false;
        providerHttpStatus.set(-1);
        extension = originalExtension;
        playbackUrl = null;
        playbackExtension = null;
        resolveDirect();
    }

    private void loadEpg(String requestedId) {
        worker.execute(() -> {
            try {
                ProviderProfile profile = ProviderProfileStore.load(this);
                if (profile == null || !profile.isXtream()) throw new Exception("No Xtream EPG");
                JSONObject payload = new XtreamClient(profile).epg(requestedId, 8);
                JSONArray values = payload.optJSONArray("epg_listings");
                JSONObject selected = null;
                long now = System.currentTimeMillis();
                if (values != null) for (int i = 0; i < values.length(); i++) {
                    JSONObject row = values.optJSONObject(i);
                    if (row == null) continue;
                    long start = row.optLong("start_timestamp", 0L) * 1000L;
                    long end = row.optLong("stop_timestamp", 0L) * 1000L;
                    if (selected == null) selected = row;
                    if (start <= now && now < end) { selected = row; break; }
                }
                JSONObject current = selected;
                runOnUiThread(() -> {
                    if (!requestedId.equals(id) || binding == null) return;
                    if (current == null) {
                        binding.epg.setText("لا توجد بيانات برنامج حاليًا");
                        return;
                    }
                    long start = current.optLong("start_timestamp", 0L) * 1000L;
                    long end = current.optLong("stop_timestamp", 0L) * 1000L;
                    binding.epg.setText("الآن: " + decodeEpg(current.optString("title")) + " • "
                            + time(start) + " – " + time(end));
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
        resolving = true;
        releasePlayer(true);
        playbackUrl = null;
        playbackExtension = null;
        vlcAttempted = false;
        providerHttpStatus.set(-1);
        showLoading();

        worker.execute(() -> {
            MediaRecord item;
            try { item = store.adjacentLive(currentId, direction); }
            catch (Exception error) {
                if (generation != resolveGeneration.get()) return;
                resolving = false;
                runOnUiThread(() -> showError("تعذر قراءة القناة التالية من الكتالوج المحلي."));
                return;
            }
            if (generation != resolveGeneration.get()) return;
            if (item == null) {
                resolving = false;
                runOnUiThread(() -> showError("لا توجد قناة أخرى في هذه القائمة."));
                return;
            }
            MediaRecord selected = item;
            runOnUiThread(() -> {
                if (generation != resolveGeneration.get() || isFinishing() || isDestroyed()) return;
                id = selected.id;
                name = selected.name;
                extension = normalize(selected.extension.isEmpty() ? "ts" : selected.extension);
                originalExtension = extension;
                historyRecorded = false;
                binding.title.setText(name);
                binding.epg.setText("جلب دليل البرنامج…");
                resolveDirect();
                loadEpg(id);
            });
        });
    }

    private void showLoading() {
        if (binding == null) return;
        binding.progress.setVisibility(View.VISIBLE);
        binding.errorPanel.setVisibility(View.GONE);
        binding.topBar.setVisibility(View.VISIBLE);
    }

    private void showProviderRejected(int status) {
        showError("المزوّد رفض رابط التشغيل المباشر (HTTP " + status + "). راجع الحساب أو IP أو Headers.");
    }

    private void showError(String message) {
        if (binding == null || isFinishing() || isDestroyed()) return;
        binding.progress.setVisibility(View.GONE);
        binding.errorPanel.setVisibility(View.VISIBLE);
        binding.error.setText(message == null || message.trim().isEmpty() ? "حدث خطأ غير متوقع." : message);
        binding.retry.requestFocus();
    }

    private void onEngineReady() {
        handler.removeCallbacks(timeout);
        if (binding != null) binding.progress.setVisibility(View.GONE);
        handler.removeCallbacks(hideChrome);
        handler.postDelayed(hideChrome, 4_500L);
        if (!historyRecorded) {
            historyRecorded = true;
            String readyType = type;
            String readyId = id;
            worker.execute(() -> store.addHistory(readyType, readyId));
        }
    }

    @Override public void onPlaybackStateChanged(int state) {
        if (binding == null || player == null) return;
        binding.progress.setVisibility(state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
        if (state == Player.STATE_READY) onEngineReady();
    }

    @Override public void onPlayerError(PlaybackException error) {
        handler.removeCallbacks(timeout);
        int status = httpStatus(error);
        if (status > 0) providerHttpStatus.set(status);
        int observed = providerHttpStatus.get();
        releasePlayer(true);
        if (status == 401 || status == 403 || status == 456
                || observed == 401 || observed == 403 || observed == 456) {
            showProviderRejected(status > 0 ? status : observed);
            return;
        }
        if (status >= 400) {
            showError("المزوّد أعاد HTTP " + status + " أثناء التشغيل المباشر.");
            return;
        }
        if (observed >= 200 && observed < 300 && media3FormatFailure(error)) {
            launchVlcFallback();
            return;
        }
        showError("تعذر تشغيل المصدر مباشرة: " + error.getErrorCodeName());
    }

    private static boolean media3FormatFailure(PlaybackException error) {
        int code = error.errorCode;
        return code >= 3000 && code < 6000;
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

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE || event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_B) {
                finish(); return true;
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

    private void releasePlayer(boolean savePosition) {
        handler.removeCallbacks(timeout);
        if (player == null) return;
        if (savePosition && !isLive()) {
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

    private static String decodeEpg(String value) {
        if (value == null || value.isEmpty()) return "برنامج مباشر";
        try {
            byte[] raw = Base64.decode(value, Base64.DEFAULT);
            String decoded = new String(raw, StandardCharsets.UTF_8).trim();
            return decoded.isEmpty() ? value : decoded;
        } catch (Exception ignored) { return value; }
    }

    private static String time(long value) {
        return value <= 0 ? "" : DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(value));
    }

    @Override protected void onStart() {
        super.onStart();
        if (!initialized || player != null) return;
        if (playbackUrl != null) routeSelectedPlayer(playbackUrl, playbackExtension == null ? extension : playbackExtension);
        else if (!resolving) resolveDirect();
    }

    @Override protected void onStop() {
        resolveGeneration.incrementAndGet();
        resolving = false;
        releasePlayer(true);
        playbackUrl = null;
        playbackExtension = null;
        vlcAttempted = false;
        providerHttpStatus.set(-1);
        extension = originalExtension;
        super.onStop();
    }

    @Override protected void onDestroy() {
        resolveGeneration.incrementAndGet();
        releasePlayer(false);
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
