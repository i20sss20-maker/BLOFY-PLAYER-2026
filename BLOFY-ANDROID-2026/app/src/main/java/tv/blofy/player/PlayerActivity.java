package tv.blofy.player;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
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
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;
import androidx.media3.ui.PlayerView;

import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** BLOFY native playback core. */
@UnstableApi
public final class PlayerActivity extends Activity implements Player.Listener {
    private static final String TAG = "BlofyPlayback";
    private static final long LIVE_STABLE_WINDOW_MS = 2_500L;

    public static final String EXTRA_URL = "url";
    public static final String EXTRA_ID = "id";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_KIND = "kind";
    public static final String EXTRA_EXTENSION = "extension";

    private PlayerView playerView;
    private ProgressBar progress;
    private LinearLayout errorPanel;
    private TextView errorText;
    private TextView titleView;
    private Button retryButton;
    private ExoPlayer player;
    private LiveChannelOverlay liveOverlay;

    private String id;
    private String url;
    private String title;
    private String kind;
    private String extension;
    private long resumePosition;
    private int recoveryStep;
    private boolean resolving;
    private boolean firstFrameRendered;
    private long playbackStartedAtMs;

    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final ExecutorService cronetExecutor = Executors.newCachedThreadPool();
    private final Handler playbackHandler = new Handler(Looper.getMainLooper());

    private final Runnable playbackTimeout = () -> {
        if (player == null || firstFrameRendered) return;
        Log.w(TAG, "startup-timeout kind=" + kind + " ext=" + extension
                + " step=" + recoveryStep + " state=" + player.getPlaybackState()
                + " transport=" + activeTransportName());
        recoverFromFailure(player.getPlaybackState() == Player.STATE_READY
                ? "لم تظهر صورة الفيديو"
                : "انتهت مهلة بدء التشغيل");
    };

    private final Runnable markPlaybackStable = () -> {
        if (player == null || !firstFrameRendered || !player.isPlaying()) return;
        rememberSuccessfulTransport();
        recoveryStep = preferredRecoveryStep();
        Log.i(TAG, "stable kind=" + kind + " ext=" + extension
                + " transport=" + activeTransportName());
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PlaybackTransportFactory.warmUpCronet(this);
        url = getIntent().getStringExtra(EXTRA_URL);
        id = valueOr(getIntent().getStringExtra(EXTRA_ID), "");
        title = valueOr(getIntent().getStringExtra(EXTRA_TITLE), "BLOFY PLAYER");
        kind = valueOr(getIntent().getStringExtra(EXTRA_KIND), "movies");
        extension = PlaybackPolicy.normalizeExtension(
                getIntent().getStringExtra(EXTRA_EXTENSION),
                isLiveKind(kind) ? "ts" : "mp4");
        recoveryStep = preferredRecoveryStep();

        buildUi();
        hideSystemUi();

        if (validUrl(url)) prepareResolvedUrl();
        else if (!id.isEmpty()) resolvePlaybackLink();
        else showResolveError("بيانات المحتوى غير مكتملة.");
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static boolean isLiveKind(String value) {
        return "live".equals(value);
    }

    private static boolean validUrl(String value) {
        if (value == null || value.isEmpty()) return false;
        try {
            Uri uri = Uri.parse(value);
            String scheme = uri.getScheme();
            return uri.getHost() != null
                    && ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme));
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isLive() {
        return isLiveKind(kind);
    }

    private boolean isUltraHd() {
        String value = title == null ? "" : title.toUpperCase(Locale.US);
        return value.contains("4K") || value.contains("UHD") || value.contains("2160");
    }

    private boolean useCronetNow() {
        return PlaybackPolicy.useCronet(recoveryStep);
    }

    private String activeTransportName() {
        return useCronetNow() ? "cronet" : "default-http";
    }

    private String transportPreferenceKey() {
        return "transport_" + kind + "_" + PlaybackPolicy.normalizeExtension(extension, "auto");
    }

    private int preferredRecoveryStep() {
        if (kind == null || extension == null) return 0;
        return getSharedPreferences("blofy_playback", MODE_PRIVATE)
                .getBoolean(transportPreferenceKey(), false) ? 1 : 0;
    }

    private void rememberSuccessfulTransport() {
        getSharedPreferences("blofy_playback", MODE_PRIVATE)
                .edit()
                .putBoolean(transportPreferenceKey(), useCronetNow())
                .apply();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        playerView = new PlayerView(this);
        playerView.setUseController(true);
        playerView.setControllerAutoShow(true);
        playerView.setControllerShowTimeoutMs(4500);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER);
        playerView.setKeepScreenOn(true);
        playerView.setFocusable(true);
        root.addView(playerView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(17);
        titleView.setTypeface(titleView.getTypeface(), android.graphics.Typeface.BOLD);
        titleView.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        titleView.setPadding(dp(24), dp(12), dp(24), dp(12));
        titleView.setBackgroundColor(Color.argb(175, 8, 7, 14));
        root.addView(titleView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58), Gravity.TOP));

        progress = new ProgressBar(this);
        progress.setIndeterminateTintList(
                android.content.res.ColorStateList.valueOf(Color.rgb(154, 88, 255)));
        root.addView(progress, new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER));

        errorPanel = new LinearLayout(this);
        errorPanel.setOrientation(LinearLayout.VERTICAL);
        errorPanel.setGravity(Gravity.CENTER);
        errorPanel.setPadding(dp(24), dp(20), dp(24), dp(20));
        errorPanel.setBackgroundColor(Color.argb(225, 10, 9, 18));
        errorPanel.setVisibility(View.GONE);

        TextView errorTitle = new TextView(this);
        errorTitle.setText("تعذر تشغيل المصدر");
        errorTitle.setTextColor(Color.WHITE);
        errorTitle.setTextSize(21);
        errorTitle.setTypeface(errorTitle.getTypeface(), android.graphics.Typeface.BOLD);
        errorTitle.setGravity(Gravity.CENTER);
        errorPanel.addView(errorTitle);

        errorText = new TextView(this);
        errorText.setTextColor(Color.rgb(184, 181, 197));
        errorText.setTextSize(14);
        errorText.setGravity(Gravity.CENTER);
        errorText.setPadding(0, dp(10), 0, dp(14));
        errorPanel.addView(errorText,
                new LinearLayout.LayoutParams(dp(560), ViewGroup.LayoutParams.WRAP_CONTENT));

        retryButton = new Button(this);
        retryButton.setText("إعادة الاتصال");
        retryButton.setTextColor(Color.WHITE);
        retryButton.setTextSize(15);
        retryButton.setAllCaps(false);
        retryButton.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(Color.rgb(124, 50, 255)));
        retryButton.setOnClickListener(view -> manualRetry());
        errorPanel.addView(retryButton, new LinearLayout.LayoutParams(dp(240), dp(54)));

        root.addView(errorPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        if (isLive()) {
            liveOverlay = new LiveChannelOverlay(this, this::switchLiveChannel);
            root.addView(liveOverlay.view(), new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
        setContentView(root);
    }

    private void schedulePlaybackTimeout() {
        playbackHandler.removeCallbacks(playbackTimeout);
        int timeout = PlaybackPolicy.startupTimeoutMs(recoveryStep);
        if (isUltraHd()) timeout += 2_500;
        playbackHandler.postDelayed(playbackTimeout, timeout);
    }

    private void resolvePlaybackLink() {
        if (resolving) return;
        resolving = true;
        progress.setVisibility(View.VISIBLE);
        errorPanel.setVisibility(View.GONE);

        network.execute(() -> {
            try {
                String apiType = "series".equals(kind) ? "episode" : kind;
                JSONObject data = new BlofyApi(this).get(
                        "/api/native-link/" + BlofyApi.encode(apiType) + "/" + BlofyApi.encode(id)
                                + "?ext=" + BlofyApi.encode(extension));
                String resolved = data.optString("url", "");
                if (!resolved.startsWith("/api/native-play") && !resolved.startsWith("http")) {
                    throw new Exception("الخادم لم يُصدر رابط تشغيل مباشر صحيحًا.");
                }

                url = resolved.startsWith("http")
                        ? resolved
                        : BuildConfig.BLOFY_BASE_URL.replaceAll("/+$", "") + resolved;
                extension = PlaybackPolicy.normalizeExtension(
                        data.optString("extension", extension), extension);

                runOnUiThread(() -> {
                    resolving = false;
                    prepareResolvedUrl();
                    initializePlayer();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    resolving = false;
                    showResolveError(error.getMessage());
                });
            }
        });
    }

    private void prepareResolvedUrl() {
        if (!validUrl(url)) return;
        resumePosition = isLive()
                ? 0
                : getSharedPreferences("blofy_positions", MODE_PRIVATE)
                    .getLong(positionKey(), 0);
    }

    private void showResolveError(String message) {
        playbackHandler.removeCallbacks(playbackTimeout);
        progress.setVisibility(View.GONE);
        errorPanel.setVisibility(View.VISIBLE);
        errorText.setText(message == null ? "تعذر تجهيز رابط التشغيل." : message);
        retryButton.setText("إعادة المحاولة");
        retryButton.requestFocus();
    }

    private DataSource.Factory createDataSourceFactory() {
        return PlaybackTransportFactory.create(this, useCronetNow(), cronetExecutor);
    }

    private DefaultLoadControl createLoadControl() {
        int minBuffer;
        int maxBuffer;
        int playbackBuffer;
        int rebuffer;
        if (isLive() && isUltraHd()) {
            minBuffer = 10_000;
            maxBuffer = 50_000;
            playbackBuffer = 1_200;
            rebuffer = 5_000;
        } else if (isLive()) {
            minBuffer = 2_500;
            maxBuffer = 18_000;
            playbackBuffer = 600;
            rebuffer = 1_800;
        } else {
            minBuffer = 12_000;
            maxBuffer = 60_000;
            playbackBuffer = 900;
            rebuffer = 2_500;
        }
        return new DefaultLoadControl.Builder()
                .setBufferDurationsMs(minBuffer, maxBuffer, playbackBuffer, rebuffer)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();
    }

    private void initializePlayer() {
        if (player != null || !validUrl(url)) return;

        firstFrameRendered = false;
        playbackHandler.removeCallbacks(markPlaybackStable);
        DataSource.Factory dataSourceFactory = createDataSourceFactory();
        int tsFlags = DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
                | DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES;

        DefaultExtractorsFactory extractorsFactory = new DefaultExtractorsFactory()
                .setTsExtractorFlags(tsFlags);
        DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(
                dataSourceFactory, extractorsFactory);

        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this)
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER);

        player = new ExoPlayer.Builder(this, renderers)
                .setMediaSourceFactory(mediaSourceFactory)
                .setLoadControl(createLoadControl())
                .build();
        player.addListener(this);
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(), true);
        player.setHandleAudioBecomingNoisy(true);
        player.setWakeMode(C.WAKE_MODE_NETWORK);
        playerView.setPlayer(player);

        MediaItem.Builder itemBuilder = new MediaItem.Builder()
                .setUri(PlaybackPolicy.directPlaybackUrl(url))
                .setMediaId(title);
        String mimeType = PlaybackPolicy.mimeType(extension);
        if (mimeType != null) itemBuilder.setMimeType(mimeType);
        MediaItem item = itemBuilder.build();

        MediaSource mediaSource = PlaybackPolicy.isHls(extension)
                ? new HlsMediaSource.Factory(dataSourceFactory)
                    .setExtractorFactory(new DefaultHlsExtractorFactory(tsFlags, true))
                    .createMediaSource(item)
                : mediaSourceFactory.createMediaSource(item);

        playbackStartedAtMs = SystemClock.elapsedRealtime();
        Log.i(TAG, "open kind=" + kind + " ext=" + extension + " step=" + recoveryStep
                + " uhd=" + isUltraHd() + " transport=" + activeTransportName());
        player.setMediaSource(mediaSource, Math.max(0, resumePosition));
        player.prepare();
        player.play();
        progress.setVisibility(View.VISIBLE);
        errorPanel.setVisibility(View.GONE);
        playerView.requestFocus();
        schedulePlaybackTimeout();
    }

    private void switchLiveChannel(BlofyModels.Media media) {
        if (!isLive() || media == null || media.id.equals(id)) return;
        playbackHandler.removeCallbacks(playbackTimeout);
        playbackHandler.removeCallbacks(markPlaybackStable);
        releasePlayer();
        id = media.id;
        title = media.name;
        extension = PlaybackPolicy.normalizeExtension(media.extension, "ts");
        url = null;
        resumePosition = 0;
        recoveryStep = preferredRecoveryStep();
        titleView.setText(title);
        titleView.setVisibility(View.VISIBLE);
        progress.setVisibility(View.VISIBLE);
        errorPanel.setVisibility(View.GONE);
        resolvePlaybackLink();
    }

    private void recoverFromFailure(String reason) {
        if (isFinishing() || isDestroyed()) return;
        playbackHandler.removeCallbacks(playbackTimeout);
        playbackHandler.removeCallbacks(markPlaybackStable);
        recoveryStep += 1;
        Log.w(TAG, "recover reason=" + reason + " step=" + recoveryStep + " ext=" + extension
                + " nextTransport=" + PlaybackPolicy.transportName(recoveryStep));
        releasePlayer();

        if (PlaybackPolicy.shouldRetrySameFormat(recoveryStep)) {
            reopenResolvedSource(false);
            return;
        }

        if (isLive() && PlaybackPolicy.shouldTryAlternateLiveFormat(recoveryStep) && !id.isEmpty()) {
            extension = PlaybackPolicy.alternateLiveExtension(extension);
            url = null;
            Log.i(TAG, "live-format-fallback ext=" + extension + " transport=" + activeTransportName());
            resolvePlaybackLink();
            return;
        }

        if (isLive() && PlaybackPolicy.shouldRetryAlternateFormat(recoveryStep)) {
            reopenResolvedSource(false);
            return;
        }

        progress.setVisibility(View.GONE);
        errorPanel.setVisibility(View.VISIBLE);
        errorText.setText("تعذر تشغيل المصدر. آخر سبب: " + reason
                + "\nالصيغة: " + extension + "\nالنقل: " + activeTransportName());
        retryButton.setText("إعادة المحاولة من البداية");
        retryButton.requestFocus();
    }

    private void reopenResolvedSource(boolean forceResolve) {
        if (!forceResolve && validUrl(url)) {
            initializePlayer();
            return;
        }
        if (!id.isEmpty()) {
            url = null;
            resolvePlaybackLink();
        } else {
            initializePlayer();
        }
    }

    private void manualRetry() {
        recoveryStep = preferredRecoveryStep();
        releasePlayer();
        reopenResolvedSource(!validUrl(url));
    }

    @Override
    public void onPlaybackStateChanged(int playbackState) {
        if (playbackState == Player.STATE_BUFFERING) {
            if (!firstFrameRendered) progress.setVisibility(View.VISIBLE);
            return;
        }

        if (playbackState == Player.STATE_READY) {
            long readyMs = playbackStartedAtMs == 0
                    ? -1
                    : SystemClock.elapsedRealtime() - playbackStartedAtMs;
            Log.i(TAG, "ready kind=" + kind + " ext=" + extension + " ms=" + readyMs
                    + " firstFrame=" + firstFrameRendered
                    + " transport=" + activeTransportName());
            if (firstFrameRendered) progress.setVisibility(View.GONE);
            titleView.postDelayed(() -> titleView.setVisibility(View.GONE), 2500);
            return;
        }

        if (playbackState == Player.STATE_ENDED) {
            progress.setVisibility(View.GONE);
            if (isLive()) recoverFromFailure("انتهى اتصال البث المباشر");
        }
    }

    @Override
    public void onRenderedFirstFrame() {
        if (playbackStartedAtMs == 0) return;
        firstFrameRendered = true;
        playbackHandler.removeCallbacks(playbackTimeout);
        progress.setVisibility(View.GONE);
        long firstFrameMs = SystemClock.elapsedRealtime() - playbackStartedAtMs;
        Log.i(TAG, "first-frame kind=" + kind + " ext=" + extension + " ms=" + firstFrameMs
                + " transport=" + activeTransportName());
        playbackHandler.removeCallbacks(markPlaybackStable);
        playbackHandler.postDelayed(markPlaybackStable, isLive() ? LIVE_STABLE_WINDOW_MS : 500L);
    }

    @Override
    public void onPlayerError(PlaybackException error) {
        playbackHandler.removeCallbacks(playbackTimeout);
        playbackHandler.removeCallbacks(markPlaybackStable);
        Log.w(TAG, "player-error code=" + error.errorCode + " name=" + error.getErrorCodeName()
                + " ext=" + extension + " transport=" + activeTransportName(), error);

        if (isLive()
                && error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW
                && player != null) {
            try {
                player.seekToDefaultPosition();
                player.prepare();
                player.play();
                firstFrameRendered = false;
                schedulePlaybackTimeout();
                return;
            } catch (Exception ignored) {
                // Fall through to normal reconnect.
            }
        }

        recoverFromFailure(error.getErrorCodeName());
    }

    private String positionKey() {
        String key = id.isEmpty() ? String.valueOf(url) : kind + ":" + id;
        return "position_" + Integer.toHexString(key.hashCode());
    }

    private void savePosition() {
        if (player == null || isLive()) return;
        long position = player.getCurrentPosition();
        long duration = player.getDuration();
        if (duration > 0 && position > duration - 30_000) position = 0;
        position = Math.max(0, position);
        getSharedPreferences("blofy_positions", MODE_PRIVATE)
                .edit()
                .putLong(positionKey(), position)
                .apply();
        resumePosition = position;
    }

    private void releasePlayer() {
        playbackHandler.removeCallbacks(playbackTimeout);
        playbackHandler.removeCallbacks(markPlaybackStable);
        if (player == null) return;
        savePosition();
        playerView.setPlayer(null);
        player.removeListener(this);
        player.release();
        player = null;
        firstFrameRendered = false;
        playbackStartedAtMs = 0;
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (validUrl(url)) initializePlayer();
    }

    @Override
    protected void onStop() {
        releasePlayer();
        super.onStop();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_BACK:
                    if (liveOverlay != null && liveOverlay.isVisible()) {
                        liveOverlay.hide();
                        playerView.requestFocus();
                        return true;
                    }
                    finish();
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (isLive() && liveOverlay != null && !liveOverlay.isVisible()) {
                        liveOverlay.show(id);
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    if (player != null) {
                        if (player.isPlaying()) player.pause(); else player.play();
                    }
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                    if (player != null) player.play();
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    if (player != null) player.pause();
                    return true;
                default:
                    break;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (liveOverlay != null && liveOverlay.isVisible()) {
            liveOverlay.hide();
            playerView.requestFocus();
            return;
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        playbackHandler.removeCallbacksAndMessages(null);
        if (liveOverlay != null) liveOverlay.close();
        network.shutdownNow();
        cronetExecutor.shutdownNow();
        super.onDestroy();
    }

    private void hideSystemUi() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
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

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }
}
