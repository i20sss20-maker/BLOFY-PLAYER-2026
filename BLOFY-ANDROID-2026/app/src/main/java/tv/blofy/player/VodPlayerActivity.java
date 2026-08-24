package tv.blofy.player;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.ui.PlayerView;

import org.json.JSONObject;
import org.videolan.libvlc.interfaces.IVLCVout;
import org.videolan.libvlc.LibVLC;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Dedicated VOD engine: Media3 first, Cronet retry, then LibVLC video fallback. */
@UnstableApi
public final class VodPlayerActivity extends Activity implements Player.Listener {
    public static final String EXTRA_ID = "id";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_KIND = "kind";
    public static final String EXTRA_EXTENSION = "extension";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final ExecutorService cronetExecutor = Executors.newCachedThreadPool();

    private PlayerView playerView;
    private SurfaceView vlcSurface;
    private ExoPlayer player;
    private LibVLC libVLC;
    private org.videolan.libvlc.MediaPlayer vlcPlayer;
    private ProgressBar spinner;
    private LinearLayout controls;
    private LinearLayout errorPanel;
    private TextView titleView;
    private TextView engineView;
    private TextView timeView;
    private TextView errorText;
    private SeekBar seekBar;

    private String id;
    private String title;
    private String kind;
    private String extension;
    private String resolvedUrl;
    private long resumePosition;
    private boolean useCronet;
    private boolean usingVlc;
    private boolean firstFrame;
    private boolean resolving;
    private boolean stoppedByLifecycle;
    private int attempt;
    private int vlcAudioIndex = -1;
    private int vlcSubtitleIndex = -1;

    private final Runnable updateProgress = new Runnable() {
        @Override public void run() {
            long duration = durationMs();
            long position = positionMs();
            if (duration > 0) {
                seekBar.setProgress((int) Math.min(1000, position * 1000L / duration));
            } else {
                seekBar.setProgress(0);
            }
            timeView.setText(formatTime(position) + "  /  " + formatTime(duration));
            main.postDelayed(this, 500);
        }
    };

    private final Runnable hideControls = () -> {
        if (controls != null && errorPanel.getVisibility() != View.VISIBLE) {
            controls.setVisibility(View.GONE);
        }
    };

    private final Runnable startupTimeout = () -> {
        if (!firstFrame && hasActiveEngine()) recover("انتهت مهلة بدء الفيديو");
    };

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        id = value(getIntent().getStringExtra(EXTRA_ID));
        title = value(getIntent().getStringExtra(EXTRA_TITLE));
        kind = value(getIntent().getStringExtra(EXTRA_KIND));
        extension = PlaybackPolicy.normalizeExtension(getIntent().getStringExtra(EXTRA_EXTENSION), "mp4");
        resumePosition = getSharedPreferences("blofy_positions", MODE_PRIVATE).getLong(positionKey(), 0);
        PlaybackTransportFactory.warmUpCronet(this);
        buildUi();
        hideSystemUi();
        resolve();
    }

    private static String value(String value) { return value == null ? "" : value; }

    private boolean ultraHd() {
        String upper = (title + " " + extension).toUpperCase(Locale.US);
        return upper.contains("4K") || upper.contains("UHD") || upper.contains("2160")
                || upper.contains("HEVC") || upper.contains("H265") || upper.contains("H.265");
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        vlcSurface = new SurfaceView(this);
        vlcSurface.setVisibility(View.GONE);
        vlcSurface.setKeepScreenOn(true);
        root.addView(vlcSurface, new FrameLayout.LayoutParams(-1, -1));

        playerView = new PlayerView(this);
        playerView.setUseController(false);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER);
        playerView.setKeepScreenOn(true);
        playerView.setFocusable(true);
        root.addView(playerView, new FrameLayout.LayoutParams(-1, -1));

        spinner = new ProgressBar(this);
        spinner.setIndeterminateTintList(BlofyUi.progressColors());
        root.addView(spinner, new FrameLayout.LayoutParams(dp(54), dp(54), Gravity.CENTER));

        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setGravity(Gravity.BOTTOM);
        controls.setPadding(dp(34), dp(16), dp(34), dp(22));
        controls.setBackgroundColor(Color.argb(215, 8, 10, 15));

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        titleView = BlofyUi.title(this, title, 18);
        titleView.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        titleView.setTextDirection(View.TEXT_DIRECTION_LTR);
        heading.addView(titleView, new LinearLayout.LayoutParams(0, dp(42), 1));
        engineView = BlofyUi.text(this, "Media3", 12, BlofyUi.MUTED);
        engineView.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        engineView.setTextDirection(View.TEXT_DIRECTION_LTR);
        heading.addView(engineView, new LinearLayout.LayoutParams(dp(160), dp(42)));
        controls.addView(heading, new LinearLayout.LayoutParams(-1, dp(42)));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        seekBar = new SeekBar(this);
        seekBar.setMax(1000);
        seekBar.setFocusable(false);
        row.addView(seekBar, new LinearLayout.LayoutParams(0, dp(44), 1));
        timeView = BlofyUi.text(this, "00:00 / 00:00", 13, Color.WHITE);
        timeView.setGravity(Gravity.CENTER);
        timeView.setTextDirection(View.TEXT_DIRECTION_LTR);
        row.addView(timeView, new LinearLayout.LayoutParams(dp(190), dp(44)));
        controls.addView(row, new LinearLayout.LayoutParams(-1, dp(52)));

        TextView hints = BlofyUi.text(this,
                "◀▶ ±10ث   •   ضغط مطول ±60ث   •   OK تشغيل/إيقاف   •   ↑ صوت   •   ↓ ترجمة",
                13, BlofyUi.MUTED);
        hints.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        controls.addView(hints, new LinearLayout.LayoutParams(-1, dp(36)));

        root.addView(controls, new FrameLayout.LayoutParams(-1, dp(170), Gravity.BOTTOM));

        errorPanel = new LinearLayout(this);
        errorPanel.setOrientation(LinearLayout.VERTICAL);
        errorPanel.setGravity(Gravity.CENTER);
        errorPanel.setPadding(dp(28), dp(22), dp(28), dp(22));
        errorPanel.setBackground(BlofyUi.panel(this, Color.argb(245, 20, 23, 30), 4, Color.WHITE));
        errorPanel.setVisibility(View.GONE);
        TextView errorTitle = BlofyUi.title(this, "تعذر تشغيل الفيديو", 22);
        errorTitle.setGravity(Gravity.CENTER);
        errorPanel.addView(errorTitle);
        errorText = BlofyUi.text(this, "", 14, BlofyUi.MUTED);
        errorText.setGravity(Gravity.CENTER);
        errorPanel.addView(errorText, new LinearLayout.LayoutParams(dp(650), -2));
        TextView retry = BlofyUi.navChip(this, "إعادة المحاولة");
        retry.setGravity(Gravity.CENTER);
        retry.setOnClickListener(v -> {
            attempt = 0;
            useCronet = false;
            usingVlc = false;
            releaseAllEngines();
            if (validUrl(resolvedUrl)) openMedia3(); else resolve();
        });
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(dp(240), dp(54));
        rp.topMargin = dp(18);
        errorPanel.addView(retry, rp);
        root.addView(errorPanel, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        setContentView(root);
        showControls();
        main.post(updateProgress);
    }

    private void resolve() {
        if (resolving || id.isEmpty()) return;
        resolving = true;
        spinner.setVisibility(View.VISIBLE);
        errorPanel.setVisibility(View.GONE);
        network.execute(() -> {
            try {
                String apiType = "episode".equals(kind) || "series".equals(kind) ? "episode" : "movies";
                JSONObject data = new BlofyApi(this).get(
                        "/api/native-link/" + BlofyApi.encode(apiType) + "/" + BlofyApi.encode(id)
                                + "?ext=" + BlofyApi.encode(extension));
                String result = data.optString("url", "");
                if (result.startsWith("/")) {
                    result = BuildConfig.BLOFY_BASE_URL.replaceAll("/+$", "") + result;
                }
                if (!validUrl(result)) throw new Exception("رابط الفيديو غير صالح");
                resolvedUrl = result;
                extension = PlaybackPolicy.normalizeExtension(data.optString("extension", extension), extension);
                runOnUiThread(() -> {
                    resolving = false;
                    openMedia3();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    resolving = false;
                    showError(error.getMessage());
                });
            }
        });
    }

    private void openMedia3() {
        if (!validUrl(resolvedUrl)) { showError("تعذر تجهيز رابط الفيديو"); return; }
        releaseAllEngines();
        usingVlc = false;
        firstFrame = false;
        playerView.setVisibility(View.VISIBLE);
        vlcSurface.setVisibility(View.GONE);
        engineView.setText(useCronet ? "Media3 • Cronet" : "Media3 • HTTP");

        DataSource.Factory source = PlaybackTransportFactory.create(
                this, useCronet, cronetExecutor,
                ultraHd() ? 6_000 : 4_000,
                ultraHd() ? 45_000 : 30_000);
        DefaultExtractorsFactory extractors = new DefaultExtractorsFactory();
        DefaultMediaSourceFactory mediaSources = new DefaultMediaSourceFactory(source, extractors);
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this)
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);

        int min = ultraHd() ? 18_000 : 8_000;
        int max = ultraHd() ? 120_000 : 60_000;
        int start = ultraHd() ? 1_600 : 700;
        int rebuffer = ultraHd() ? 4_000 : 1_800;
        DefaultLoadControl load = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(min, max, start, rebuffer)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();

        player = new ExoPlayer.Builder(this, renderers)
                .setMediaSourceFactory(mediaSources)
                .setLoadControl(load)
                .build();
        player.addListener(this);
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(), true);
        player.setHandleAudioBecomingNoisy(true);
        player.setWakeMode(C.WAKE_MODE_NETWORK);
        playerView.setPlayer(player);

        MediaItem.Builder item = new MediaItem.Builder()
                .setUri(PlaybackPolicy.directPlaybackUrl(resolvedUrl))
                .setMediaId(title);
        String mime = PlaybackPolicy.mimeType(extension);
        if (mime != null && !"mkv".equalsIgnoreCase(extension) && !"webm".equalsIgnoreCase(extension)) {
            item.setMimeType(mime);
        }
        player.setMediaItem(item.build(), Math.max(0, resumePosition));
        player.prepare();
        player.play();
        startWatchdog(ultraHd() ? 20_000 : 13_000);
    }

    private void openVlc(String reason) {
        if (!validUrl(resolvedUrl)) { showError("تعذر تجهيز رابط الفيديو"); return; }
        releaseAllEngines();
        usingVlc = true;
        firstFrame = false;
        playerView.setVisibility(View.GONE);
        vlcSurface.setVisibility(View.VISIBLE);
        spinner.setVisibility(View.VISIBLE);
        errorPanel.setVisibility(View.GONE);
        engineView.setText("VLC • HEVC/4K fallback");

        ArrayList<String> options = new ArrayList<>();
        options.add("--audio-time-stretch");
        options.add("--network-caching=" + (ultraHd() ? "2500" : "1500"));
        options.add("--file-caching=" + (ultraHd() ? "2500" : "1500"));
        options.add("--http-reconnect");
        options.add("--no-drop-late-frames");
        options.add("--no-skip-frames");
        libVLC = new LibVLC(this, options);
        vlcPlayer = new org.videolan.libvlc.MediaPlayer(libVLC);
        vlcPlayer.setEventListener(event -> runOnUiThread(() -> onVlcEvent(event)));

        IVLCVout vout = vlcPlayer.getVLCVout();
        vout.setVideoView(vlcSurface);
        vout.attachViews();

        org.videolan.libvlc.Media media = new org.videolan.libvlc.Media(libVLC, Uri.parse(resolvedUrl));
        media.setHWDecoderEnabled(true, false);
        media.addOption(":http-user-agent=BLOFY-PLAYER/2026 AndroidTV");
        media.addOption(":network-caching=" + (ultraHd() ? "2500" : "1500"));
        vlcPlayer.setMedia(media);
        media.release();
        vlcPlayer.play();
        if (resumePosition > 0) {
            main.postDelayed(() -> {
                if (vlcPlayer != null && resumePosition > 0) vlcPlayer.setTime(resumePosition);
            }, 900);
        }
        startWatchdog(ultraHd() ? 28_000 : 18_000);
        errorText.setText(reason == null ? "" : reason);
    }

    private void onVlcEvent(org.videolan.libvlc.MediaPlayer.Event event) {
        if (event == null) return;
        switch (event.type) {
            case org.videolan.libvlc.MediaPlayer.Event.Playing:
            case org.videolan.libvlc.MediaPlayer.Event.Vout:
                firstFrame = true;
                spinner.setVisibility(View.GONE);
                main.removeCallbacks(startupTimeout);
                showControls();
                break;
            case org.videolan.libvlc.MediaPlayer.Event.Buffering:
                if (!firstFrame) spinner.setVisibility(View.VISIBLE);
                break;
            case org.videolan.libvlc.MediaPlayer.Event.EndReached:
                spinner.setVisibility(View.GONE);
                resumePosition = 0;
                persistPosition(0);
                break;
            case org.videolan.libvlc.MediaPlayer.Event.EncounteredError:
                recover("VLC لم يتمكن من فك ترميز هذا المصدر");
                break;
            default:
                break;
        }
    }

    private void startWatchdog(long timeoutMs) {
        spinner.setVisibility(View.VISIBLE);
        errorPanel.setVisibility(View.GONE);
        main.removeCallbacks(startupTimeout);
        main.postDelayed(startupTimeout, timeoutMs);
    }

    private void recover(String reason) {
        main.removeCallbacks(startupTimeout);
        savePosition();
        if (usingVlc) {
            showError(reason + "\nالصيغة: " + extension + "\nتمت تجربة Media3 وVLC");
            return;
        }
        String upperReason = reason == null ? "" : reason.toUpperCase(Locale.US);
        boolean decoderFailure = upperReason.contains("DECOD") || upperReason.contains("CODEC")
                || upperReason.contains("FORMAT_UNSUPPORTED") || ultraHd();
        if (decoderFailure) {
            attempt = 2;
            openVlc(reason);
            return;
        }
        if (attempt == 0) {
            attempt = 1;
            useCronet = true;
            openMedia3();
            return;
        }
        attempt = 2;
        openVlc(reason);
    }

    private void showError(String message) {
        releaseAllEngines();
        spinner.setVisibility(View.GONE);
        controls.setVisibility(View.VISIBLE);
        errorPanel.setVisibility(View.VISIBLE);
        errorText.setText(message == null ? "حدث خطأ أثناء التشغيل" : message);
        errorPanel.requestFocus();
    }

    @Override public void onPlaybackStateChanged(int state) {
        if (usingVlc) return;
        if (state == Player.STATE_BUFFERING && !firstFrame) spinner.setVisibility(View.VISIBLE);
        if (state == Player.STATE_READY && firstFrame) spinner.setVisibility(View.GONE);
        if (state == Player.STATE_ENDED) {
            spinner.setVisibility(View.GONE);
            resumePosition = 0;
            persistPosition(0);
        }
    }

    @Override public void onRenderedFirstFrame() {
        if (usingVlc) return;
        firstFrame = true;
        spinner.setVisibility(View.GONE);
        main.removeCallbacks(startupTimeout);
        showControls();
    }

    @Override public void onPlayerError(PlaybackException error) {
        if (usingVlc) return;
        recover(error == null ? "Media3 error" : error.getErrorCodeName());
    }

    private long positionMs() {
        if (usingVlc && vlcPlayer != null) return Math.max(0, vlcPlayer.getTime());
        if (player != null) return Math.max(0, player.getCurrentPosition());
        return Math.max(0, resumePosition);
    }

    private long durationMs() {
        if (usingVlc && vlcPlayer != null) return Math.max(0, vlcPlayer.getLength());
        if (player != null) return Math.max(0, player.getDuration());
        return 0;
    }

    private boolean hasActiveEngine() {
        return player != null || vlcPlayer != null;
    }

    private void seekBy(long deltaMs) {
        long duration = durationMs();
        long next = Math.max(0, positionMs() + deltaMs);
        if (duration > 0) next = Math.min(Math.max(0, duration - 500), next);
        if (usingVlc && vlcPlayer != null) vlcPlayer.setTime(next);
        else if (player != null) player.seekTo(next);
        resumePosition = next;
        showControls();
    }

    private void togglePlay() {
        if (usingVlc && vlcPlayer != null) {
            if (vlcPlayer.isPlaying()) vlcPlayer.pause(); else vlcPlayer.play();
        } else if (player != null) {
            if (player.isPlaying()) player.pause(); else player.play();
        }
        showControls();
    }

    private void cycleVlcAudio() {
        if (!usingVlc || vlcPlayer == null) { showControls(); return; }
        org.videolan.libvlc.MediaPlayer.TrackDescription[] tracks = vlcPlayer.getAudioTracks();
        if (tracks == null || tracks.length == 0) return;
        vlcAudioIndex = (vlcAudioIndex + 1) % tracks.length;
        vlcPlayer.setAudioTrack(tracks[vlcAudioIndex].id);
        engineView.setText("VLC • صوت: " + tracks[vlcAudioIndex].name);
        showControls();
    }

    private void cycleVlcSubtitle() {
        if (!usingVlc || vlcPlayer == null) { showControls(); return; }
        org.videolan.libvlc.MediaPlayer.TrackDescription[] tracks = vlcPlayer.getSpuTracks();
        if (tracks == null || tracks.length == 0) return;
        vlcSubtitleIndex = (vlcSubtitleIndex + 1) % tracks.length;
        vlcPlayer.setSpuTrack(tracks[vlcSubtitleIndex].id);
        engineView.setText("VLC • ترجمة: " + tracks[vlcSubtitleIndex].name);
        showControls();
    }

    private void showControls() {
        controls.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideControls);
        main.postDelayed(hideControls, 5_000);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            boolean longPress = event.getRepeatCount() >= 2;
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    seekBy(longPress ? -60_000 : -10_000);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    seekBy(longPress ? 60_000 : 10_000);
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    cycleVlcAudio();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    cycleVlcSubtitle();
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    togglePlay();
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                    if (usingVlc && vlcPlayer != null) vlcPlayer.play();
                    else if (player != null) player.play();
                    showControls();
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    if (usingVlc && vlcPlayer != null) vlcPlayer.pause();
                    else if (player != null) player.pause();
                    showControls();
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    finish();
                    return true;
                default:
                    showControls();
                    break;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void persistPosition(long position) {
        getSharedPreferences("blofy_positions", MODE_PRIVATE)
                .edit().putLong(positionKey(), Math.max(0, position)).apply();
    }

    private void savePosition() {
        long p = positionMs();
        long d = durationMs();
        if (d > 0 && p > d - 30_000) p = 0;
        resumePosition = Math.max(0, p);
        persistPosition(resumePosition);
    }

    private String positionKey() {
        return "position_" + Integer.toHexString((kind + ":" + id).hashCode());
    }

    private void releaseMedia3() {
        if (player == null) return;
        playerView.setPlayer(null);
        player.removeListener(this);
        player.release();
        player = null;
    }

    private void releaseVlc() {
        if (vlcPlayer != null) {
            try { vlcPlayer.stop(); } catch (Exception ignored) {}
            try { vlcPlayer.getVLCVout().detachViews(); } catch (Exception ignored) {}
            try { vlcPlayer.release(); } catch (Exception ignored) {}
            vlcPlayer = null;
        }
        if (libVLC != null) {
            try { libVLC.release(); } catch (Exception ignored) {}
            libVLC = null;
        }
    }

    private void releaseAllEngines() {
        main.removeCallbacks(startupTimeout);
        releaseMedia3();
        releaseVlc();
    }

    private static boolean validUrl(String value) {
        try {
            Uri uri = Uri.parse(value);
            return uri.getHost() != null && ("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String formatTime(long ms) {
        if (ms <= 0) return "00:00";
        return DateUtils.formatElapsedTime(ms / 1000L);
    }

    private int dp(int value) { return BlofyUi.dp(this, value); }

    @Override protected void onStart() {
        super.onStart();
        if (stoppedByLifecycle && !hasActiveEngine() && validUrl(resolvedUrl) && errorPanel.getVisibility() != View.VISIBLE) {
            stoppedByLifecycle = false;
            if (usingVlc || attempt >= 2) openVlc("استئناف المشاهدة");
            else openMedia3();
        }
    }

    @Override protected void onStop() {
        savePosition();
        stoppedByLifecycle = !isFinishing();
        releaseAllEngines();
        super.onStop();
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        releaseAllEngines();
        network.shutdownNow();
        cronetExecutor.shutdownNow();
        super.onDestroy();
    }

    private void hideSystemUi() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }
}
