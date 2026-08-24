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
import android.view.View;
import android.view.ViewGroup;
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

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Dedicated movie/episode player. VOD intentionally does not share Live fallback policy. */
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
    private ExoPlayer player;
    private ProgressBar spinner;
    private LinearLayout controls;
    private LinearLayout errorPanel;
    private TextView titleView;
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
    private boolean firstFrame;
    private boolean resolving;
    private int attempt;

    private final Runnable updateProgress = new Runnable() {
        @Override public void run() {
            if (player != null) {
                long duration = Math.max(0, player.getDuration());
                long position = Math.max(0, player.getCurrentPosition());
                if (duration > 0) seekBar.setProgress((int) Math.min(1000, position * 1000L / duration));
                timeView.setText(formatTime(position) + "  /  " + formatTime(duration));
            }
            main.postDelayed(this, 500);
        }
    };

    private final Runnable hideControls = () -> {
        if (controls != null) controls.setVisibility(View.GONE);
    };

    private final Runnable startupTimeout = () -> {
        if (!firstFrame && player != null) recover("انتهت مهلة بدء الفيديو");
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
        return upper.contains("4K") || upper.contains("UHD") || upper.contains("2160") || upper.contains("HEVC") || upper.contains("H265");
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

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
        controls.setPadding(dp(34), dp(20), dp(34), dp(26));
        controls.setBackgroundColor(Color.argb(210, 8, 10, 15));

        titleView = BlofyUi.title(this, title, 18);
        titleView.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        titleView.setTextDirection(View.TEXT_DIRECTION_LTR);
        controls.addView(titleView, new LinearLayout.LayoutParams(-1, dp(44)));

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

        TextView hints = BlofyUi.text(this, "◀▶ تقديم/تأخير 10 ثوانٍ     OK تشغيل/إيقاف     رجوع للخروج", 13, BlofyUi.MUTED);
        hints.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        controls.addView(hints, new LinearLayout.LayoutParams(-1, dp(36)));

        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-1, dp(180), Gravity.BOTTOM);
        root.addView(controls, cp);

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
        errorPanel.addView(errorText, new LinearLayout.LayoutParams(dp(620), -2));
        TextView retry = BlofyUi.navChip(this, "إعادة المحاولة");
        retry.setGravity(Gravity.CENTER);
        retry.setOnClickListener(v -> {
            attempt = 0;
            useCronet = false;
            releasePlayer();
            resolve();
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
                JSONObject data = new BlofyApi(this).get("/api/native-link/" + BlofyApi.encode(apiType) + "/" + BlofyApi.encode(id)
                        + "?ext=" + BlofyApi.encode(extension));
                String result = data.optString("url", "");
                if (result.startsWith("/")) result = BuildConfig.BLOFY_BASE_URL.replaceAll("/+$", "") + result;
                if (!validUrl(result)) throw new Exception("رابط الفيديو غير صالح");
                resolvedUrl = result;
                extension = PlaybackPolicy.normalizeExtension(data.optString("extension", extension), extension);
                runOnUiThread(() -> {
                    resolving = false;
                    openPlayer();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    resolving = false;
                    showError(error.getMessage());
                });
            }
        });
    }

    private void openPlayer() {
        if (!validUrl(resolvedUrl)) { showError("تعذر تجهيز رابط الفيديو"); return; }
        releasePlayer();
        firstFrame = false;

        DataSource.Factory source = PlaybackTransportFactory.create(
                this, useCronet, cronetExecutor,
                ultraHd() ? 6_000 : 4_000,
                ultraHd() ? 45_000 : 30_000);

        DefaultExtractorsFactory extractors = new DefaultExtractorsFactory();
        DefaultMediaSourceFactory mediaSources = new DefaultMediaSourceFactory(source, extractors);
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this)
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);

        int min = ultraHd() ? 20_000 : 8_000;
        int max = ultraHd() ? 120_000 : 60_000;
        int start = ultraHd() ? 1_800 : 800;
        int rebuffer = ultraHd() ? 5_000 : 2_000;
        DefaultLoadControl load = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(min, max, start, rebuffer)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();

        player = new ExoPlayer.Builder(this, renderers)
                .setMediaSourceFactory(mediaSources)
                .setLoadControl(load)
                .build();
        player.addListener(this);
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        player.setHandleAudioBecomingNoisy(true);
        player.setWakeMode(C.WAKE_MODE_NETWORK);
        playerView.setPlayer(player);

        MediaItem.Builder item = new MediaItem.Builder().setUri(PlaybackPolicy.directPlaybackUrl(resolvedUrl)).setMediaId(title);
        String mime = PlaybackPolicy.mimeType(extension);
        if (mime != null && !"mkv".equalsIgnoreCase(extension) && !"webm".equalsIgnoreCase(extension)) item.setMimeType(mime);
        player.setMediaItem(item.build(), Math.max(0, resumePosition));
        player.prepare();
        player.play();
        spinner.setVisibility(View.VISIBLE);
        errorPanel.setVisibility(View.GONE);
        main.removeCallbacks(startupTimeout);
        main.postDelayed(startupTimeout, ultraHd() ? 22_000 : 14_000);
    }

    private void recover(String reason) {
        main.removeCallbacks(startupTimeout);
        savePosition();
        if (attempt == 0) {
            attempt++;
            useCronet = true;
            openPlayer();
            return;
        }
        showError(reason + "\nالصيغة: " + extension + (ultraHd() ? "\nالمحتوى عالي الدقة/HEVC" : ""));
    }

    private void showError(String message) {
        spinner.setVisibility(View.GONE);
        errorPanel.setVisibility(View.VISIBLE);
        errorText.setText(message == null ? "حدث خطأ أثناء التشغيل" : message);
        errorPanel.requestFocus();
    }

    @Override public void onPlaybackStateChanged(int state) {
        if (state == Player.STATE_BUFFERING && !firstFrame) spinner.setVisibility(View.VISIBLE);
        if (state == Player.STATE_READY && firstFrame) spinner.setVisibility(View.GONE);
        if (state == Player.STATE_ENDED) {
            spinner.setVisibility(View.GONE);
            resumePosition = 0;
            savePosition();
        }
    }

    @Override public void onRenderedFirstFrame() {
        firstFrame = true;
        spinner.setVisibility(View.GONE);
        main.removeCallbacks(startupTimeout);
        showControls();
    }

    @Override public void onPlayerError(PlaybackException error) {
        recover(error.getErrorCodeName());
    }

    private void seekBy(long deltaMs) {
        if (player == null) return;
        long duration = player.getDuration();
        long next = Math.max(0, player.getCurrentPosition() + deltaMs);
        if (duration > 0) next = Math.min(duration - 500, next);
        player.seekTo(next);
        showControls();
    }

    private void togglePlay() {
        if (player == null) return;
        if (player.isPlaying()) player.pause(); else player.play();
        showControls();
    }

    private void showControls() {
        controls.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideControls);
        main.postDelayed(hideControls, 5_000);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT: seekBy(-10_000); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: seekBy(10_000); return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: togglePlay(); return true;
                case KeyEvent.KEYCODE_MEDIA_PLAY: if (player != null) player.play(); showControls(); return true;
                case KeyEvent.KEYCODE_MEDIA_PAUSE: if (player != null) player.pause(); showControls(); return true;
                case KeyEvent.KEYCODE_BACK: finish(); return true;
                default: showControls(); break;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void savePosition() {
        if (player == null) return;
        long p = Math.max(0, player.getCurrentPosition());
        long d = player.getDuration();
        if (d > 0 && p > d - 30_000) p = 0;
        getSharedPreferences("blofy_positions", MODE_PRIVATE).edit().putLong(positionKey(), p).apply();
        resumePosition = p;
    }

    private String positionKey() { return "position_" + Integer.toHexString((kind + ":" + id).hashCode()); }

    private void releasePlayer() {
        main.removeCallbacks(startupTimeout);
        if (player == null) return;
        savePosition();
        playerView.setPlayer(null);
        player.removeListener(this);
        player.release();
        player = null;
    }

    private static boolean validUrl(String value) {
        try {
            Uri uri = Uri.parse(value);
            return uri.getHost() != null && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()));
        } catch (Exception ignored) { return false; }
    }

    private static String formatTime(long ms) {
        if (ms <= 0) return "00:00";
        return DateUtils.formatElapsedTime(ms / 1000L);
    }

    private int dp(int value) { return BlofyUi.dp(this, value); }

    @Override protected void onStop() {
        releasePlayer();
        super.onStop();
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
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
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }
}
