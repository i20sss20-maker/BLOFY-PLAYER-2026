package tv.blofy.player;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
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
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.AspectRatioFrameLayout;

import org.json.JSONObject;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.interfaces.IVLCVout;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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
    public static final String EXTRA_CATEGORY_ID = "category_id";
    public static final String EXTRA_REFERER = "referer";

    private PlayerView playerView;
    private SurfaceView vlcSurface;
    private ProgressBar progress;
    private LinearLayout errorPanel;
    private TextView errorText;
    private TextView titleView;
    private Button retryButton;
    private ExoPlayer player;
    private LibVLC libVLC;
    private org.videolan.libvlc.MediaPlayer vlcPlayer;
    private LiveChannelOverlay liveOverlay;

    private String id;
    private String url;
    private String title;
    private String kind;
    private String extension;
    private String categoryId;
    private String playbackReferer = "";
    private String sourceVariant = "canonical";
    private String canonicalUrl = "";
    private String canonicalExtension = "";
    private String canonicalReferer = "";
    private long resumePosition;
    private int recoveryStep;
    private boolean firstFrameRendered;
    private boolean usingVlc;
    private boolean vlcAttempted;
    private boolean warmLiveSwitchPending;
    private boolean vlcSubtitlePreferenceApplied;
    private long playbackStartedAtMs;
    private int vlcGeneration;
    private int resolveGeneration;
    private Future<?> resolveTask;
    private BlofyApi.Cancellation resolveCancellation;
    private boolean lifecycleStarted;

    private final ExecutorService network = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
            new ThreadPoolExecutor.DiscardOldestPolicy());
    private final Handler playbackHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideTitle = () -> {
        if (titleView != null) titleView.setVisibility(View.GONE);
    };

    private final Runnable playbackTimeout = () -> {
        if ((!usingVlc && player == null) || (usingVlc && vlcPlayer == null)
                || firstFrameRendered) return;
        Log.w(TAG, "startup-timeout kind=" + kind + " ext=" + extension
                + " step=" + recoveryStep + " state="
                + (player == null ? "vlc" : player.getPlaybackState())
                + " transport=" + activeTransportName());
        recoverFromFailure(!usingVlc && player != null && player.getPlaybackState() == Player.STATE_READY
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
        extension = configuredExtension(PlaybackPolicy.normalizeExtension(
                getIntent().getStringExtra(EXTRA_EXTENSION),
                isLiveKind(kind) ? "ts" : "mp4"));
        if (validUrl(url)) extension = LivePreviewController.resolvedExtension(url, extension);
        categoryId = valueOr(getIntent().getStringExtra(EXTRA_CATEGORY_ID), "");
        playbackReferer = valueOr(getIntent().getStringExtra(EXTRA_REFERER), "");
        recoveryStep = preferredRecoveryStep();

        buildUi();
        hideSystemUi();

        if (validUrl(url)) {
            canonicalUrl = url;
            canonicalExtension = extension;
            canonicalReferer = playbackReferer;
            prepareResolvedUrl();
        }
        else if (!id.isEmpty()) resolvePlaybackLink();
        else showResolveError("بيانات المحتوى غير مكتملة.");
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static boolean isLiveKind(String value) { return "live".equals(value); }

    private static boolean validUrl(String value) {
        if (value == null || value.isEmpty()) return false;
        try {
            Uri uri = Uri.parse(value);
            String scheme = uri.getScheme();
            return uri.getHost() != null
                    && ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme));
        } catch (Exception ignored) { return false; }
    }

    private boolean isLive() { return isLiveKind(kind); }

    private boolean isUltraHd() {
        String value = ((title == null ? "" : title) + " " + extension).toUpperCase(Locale.US);
        return value.contains("4K") || value.contains("UHD") || value.contains("2160")
                || value.contains("HEVC") || value.contains("H265") || value.contains("H.265");
    }

    private String playerSetting(String key, String fallback) {
        return getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE).getString(key, fallback);
    }

    private int media3ResizeMode() {
        String mode = playerSetting(SettingsActivity.KEY_ASPECT, "fit");
        if ("zoom".equals(mode)) return AspectRatioFrameLayout.RESIZE_MODE_ZOOM;
        if ("fill".equals(mode)) return AspectRatioFrameLayout.RESIZE_MODE_FILL;
        return AspectRatioFrameLayout.RESIZE_MODE_FIT;
    }

    private void applySubtitleStyle() {
        if (playerView == null || playerView.getSubtitleView() == null) return;
        String size = playerSetting(SettingsActivity.KEY_SUBTITLE_SIZE, "medium");
        float fraction = "small".equals(size) ? 0.043f : "large".equals(size) ? 0.062f : 0.053f;
        playerView.getSubtitleView().setFractionalTextSize(fraction);
    }

    private int vlcSubtitleRelativeSize() {
        String size = playerSetting(SettingsActivity.KEY_SUBTITLE_SIZE, "medium");
        return "small".equals(size) ? 18 : "large".equals(size) ? 26 : 22;
    }

    private int vlcCacheMs() {
        String mode = playerSetting(SettingsActivity.KEY_BUFFER, "auto");
        if ("fast".equals(mode)) return isUltraHd() ? 500 : 300;
        if ("stable".equals(mode)) return isUltraHd() ? 900 : 700;
        return isUltraHd() ? 650 : 420;
    }

    private void applyVlcAspect(org.videolan.libvlc.MediaPlayer target) {
        if (target == null) return;
        String mode = playerSetting(SettingsActivity.KEY_ASPECT, "fit");
        try {
            if ("fill".equals(mode)) {
                int width = Math.max(1, getResources().getDisplayMetrics().widthPixels);
                int height = Math.max(1, getResources().getDisplayMetrics().heightPixels);
                target.getVLCVout().setWindowSize(width, height);
                target.setAspectRatio(width + ":" + height);
                target.setScale(0f);
            } else if ("zoom".equals(mode)) {
                target.setAspectRatio(null);
                target.setScale(1.12f);
            } else {
                target.setAspectRatio(null);
                target.setScale(0f);
            }
        } catch (Throwable error) {
            Log.w(TAG, "vlc-aspect-not-supported mode=" + mode, error);
        }
    }

    private String configuredExtension(String candidate) {
        if (!isLiveKind(kind)) return candidate;
        String mode = playerSetting(SettingsActivity.KEY_STREAM, "auto");
        if ("ts".equals(mode)) return "ts";
        if ("hls".equals(mode)) return "m3u8";
        return candidate;
    }

    private String activeTransportName() {
        return usingVlc ? "libvlc" : "default-http";
    }

    private int preferredRecoveryStep() {
        // Transport success must not leak from one playlist/provider to another.
        // Start every source with the platform HTTP stack and use Cronet only as
        // a bounded compatibility fallback for the current source.
        return 0;
    }

    private void rememberSuccessfulTransport() {
        // Deliberately session-only. Persisting this choice by file extension
        // made one unusual host slow down every other host using that extension.
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        vlcSurface = new SurfaceView(this);
        vlcSurface.setVisibility(View.GONE);
        vlcSurface.setKeepScreenOn(true);
        vlcSurface.setFocusable(true);
        root.addView(vlcSurface, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        playerView = new PlayerView(this);
        boolean useMedia3Controller = !isLive();
        playerView.setUseController(useMedia3Controller);
        playerView.setControllerAutoShow(useMedia3Controller);
        playerView.setControllerShowTimeoutMs(4500);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER);
        playerView.setKeepScreenOn(true);
        playerView.setFocusable(true);
        playerView.setResizeMode(media3ResizeMode());
        applySubtitleStyle();
        root.addView(playerView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        titleView = BlofyUi.title(this, title, 20);
        titleView.setText(title);
        titleView.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        titleView.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        titleView.setPadding(dp(32), dp(10), dp(32), dp(24));
        titleView.setShadowLayer(dp(5), 0, dp(2), Color.BLACK);
        GradientDrawable titleGradient = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{
                        Color.argb(235, 6, 3, 12),
                        Color.argb(190, 25, 12, 45),
                        Color.argb(85, 34, 15, 57),
                        Color.TRANSPARENT
                });
        titleView.setBackground(titleGradient);
        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(96), Gravity.TOP);
        root.addView(titleView, titleParams);

        progress = new ProgressBar(this);
        progress.setIndeterminateTintList(BlofyUi.progressColors());
        root.addView(progress, new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER));

        if (isLive()) {
            liveOverlay = new LiveChannelOverlay(this, categoryId, this::switchLiveChannel);
            View overlayView = liveOverlay.view();
            overlayView.setElevation(dp(20));
            root.addView(overlayView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }

        // This full-screen layer is deliberately added last so a fatal error always
        // wins the focus and z-order over the player chrome and the channel drawer.
        errorPanel = new LinearLayout(this);
        errorPanel.setOrientation(LinearLayout.VERTICAL);
        errorPanel.setGravity(Gravity.CENTER);
        errorPanel.setPadding(dp(28), dp(28), dp(28), dp(28));
        errorPanel.setBackgroundColor(Color.argb(205, 3, 2, 8));
        errorPanel.setClickable(true);
        errorPanel.setElevation(dp(40));
        errorPanel.setVisibility(View.GONE);

        LinearLayout modal = new LinearLayout(this);
        modal.setOrientation(LinearLayout.VERTICAL);
        modal.setGravity(Gravity.CENTER);
        modal.setPadding(dp(34), dp(28), dp(34), dp(30));
        modal.setBackground(BlofyUi.panel(this, Color.rgb(22, 13, 35), 12, Color.rgb(132, 87, 194)));

        TextView errorEyebrow = BlofyUi.text(this, "BLOFY PLAYER", 11, BlofyUi.PURPLE_LIGHT);
        errorEyebrow.setGravity(Gravity.CENTER);
        errorEyebrow.setTextDirection(View.TEXT_DIRECTION_LTR);
        modal.addView(errorEyebrow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(30)));

        TextView errorTitle = BlofyUi.title(this, "تعذر تشغيل المصدر", 23);
        errorTitle.setGravity(Gravity.CENTER);
        modal.addView(errorTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        errorText = BlofyUi.text(this, "", 14, BlofyUi.MUTED);
        errorText.setGravity(Gravity.CENTER);
        errorText.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        errorText.setPadding(dp(8), dp(8), dp(8), dp(18));
        modal.addView(errorText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        retryButton = BlofyUi.button(this, "إعادة الاتصال", true);
        retryButton.setOnClickListener(view -> manualRetry());
        LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(dp(250), dp(58));
        retryParams.topMargin = dp(4);
        modal.addView(retryButton, retryParams);

        errorPanel.addView(modal, new LinearLayout.LayoutParams(dp(650), ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(errorPanel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    private void schedulePlaybackTimeout() {
        playbackHandler.removeCallbacks(playbackTimeout);
        int timeout = usingVlc
                ? PlaybackPolicy.vlcStartupTimeoutMs(isUltraHd())
                : PlaybackPolicy.startupTimeoutMs(recoveryStep);
        playbackHandler.postDelayed(playbackTimeout, timeout);
    }

    private void resolvePlaybackLink() {
        int token = ++resolveGeneration;
        BlofyApi.Cancellation previousCancellation = resolveCancellation;
        if (previousCancellation != null) previousCancellation.cancel();
        Future<?> previous = resolveTask;
        if (previous != null) previous.cancel(true);
        BlofyApi.Cancellation cancellation = new BlofyApi.Cancellation();
        resolveCancellation = cancellation;
        progress.setVisibility(View.VISIBLE);
        errorPanel.setVisibility(View.GONE);
        String requestedId = id;
        String requestedKind = kind;
        String requestedExtension = extension;
        String requestedVariant = sourceVariant;
        String requestedReferer = playbackReferer;
        resolveTask = network.submit(() -> {
            try {
                String apiType = "series".equals(requestedKind) ? "episode" : requestedKind;
                JSONObject data = new BlofyApi(this).getPlayback("/api/native-link/" + BlofyApi.encode(apiType) + "/"
                        + BlofyApi.encode(requestedId) + "?ext=" + BlofyApi.encode(requestedExtension)
                        + "&variant=" + BlofyApi.encode(requestedVariant), cancellation);
                String resolved = data.optString("url", "");
                if (!resolved.startsWith("/api/native-play") && !resolved.startsWith("http")) {
                    throw new Exception("الخادم لم يُصدر رابط تشغيل مباشر صحيحًا.");
                }
                String resolvedExtension = configuredExtension(PlaybackPolicy.normalizeExtension(
                        data.optString("extension", requestedExtension), requestedExtension));
                String resolvedReferer = data.optString("referer", requestedReferer);
                String finalResolved = resolved;
                runOnUiThread(() -> {
                    if (token != resolveGeneration || isFinishing() || isDestroyed()) return;
                    url = finalResolved.startsWith("http") ? finalResolved
                            : BuildConfig.BLOFY_BASE_URL.replaceAll("/+$", "") + finalResolved;
                    extension = resolvedExtension;
                    playbackReferer = resolvedReferer;
                    if ("canonical".equals(requestedVariant)) {
                        canonicalUrl = url;
                        canonicalExtension = extension;
                        canonicalReferer = playbackReferer;
                    }
                    prepareResolvedUrl();
                    if (!lifecycleStarted) return;
                    if (warmLiveSwitchPending && player != null && isLive()) {
                        warmLiveSwitchPending = false;
                        replaceLiveSourceOnWarmPlayer();
                    } else {
                        warmLiveSwitchPending = false;
                        initializePlayer();
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (token != resolveGeneration || isFinishing() || isDestroyed()) return;
                    warmLiveSwitchPending = false;
                    if ("direct".equals(requestedVariant) && restoreCanonicalSource()) {
                        if (lifecycleStarted) openVlc(PlaybackPolicy.resolveErrorMessage(error));
                        return;
                    }
                    showResolveError(PlaybackPolicy.resolveErrorMessage(error));
                });
            }
        });
    }

    private void prepareResolvedUrl() {
        if (!validUrl(url)) return;
        resumePosition = isLive() ? 0 : PlaybackProgress.get(this, kind, id);
    }

    private boolean restoreCanonicalSource() {
        if (!validUrl(canonicalUrl)) return false;
        url = canonicalUrl;
        extension = PlaybackPolicy.normalizeExtension(canonicalExtension, extension);
        playbackReferer = canonicalReferer;
        sourceVariant = "canonical";
        recoveryStep = 2;
        return true;
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
        // Avoid an identical queued retry while the Cronet provider is still
        // installing. The compatibility fallback is a different decoder/stack.
        return PlaybackTransportFactory.create(this, false, network,
                15_000, 30_000, recoveryStep, playbackReferer);
    }

    private DefaultLoadControl createLoadControl() {
        String mode = playerSetting(SettingsActivity.KEY_BUFFER, "auto");
        if ("auto".equals(mode) && DeviceCapabilityProfile.detect(this).usesReducedPerformance()) {
            mode = "fast";
        }
        int minBuffer, maxBuffer, playbackBuffer, rebuffer;
        if ("fast".equals(mode) && isLive() && !isUltraHd()) {
            minBuffer = 1_500; maxBuffer = 10_000; playbackBuffer = 350; rebuffer = 1_000;
        } else if ("stable".equals(mode) || (isLive() && isUltraHd())) {
            minBuffer = isLive() ? 5_000 : 12_000;
            maxBuffer = isLive() ? 35_000 : 75_000;
            playbackBuffer = isLive() ? 700 : 1_200;
            rebuffer = isLive() ? 2_500 : 3_000;
        } else if (isLive()) {
            minBuffer = 2_500; maxBuffer = 18_000; playbackBuffer = 600; rebuffer = 1_800;
        } else {
            minBuffer = 12_000; maxBuffer = 60_000; playbackBuffer = 900; rebuffer = 2_500;
        }
        return new DefaultLoadControl.Builder().setBufferDurationsMs(minBuffer, maxBuffer, playbackBuffer, rebuffer)
                .setPrioritizeTimeOverSizeThresholds(true).build();
    }

    private MediaSource createCurrentMediaSource() {
        DataSource.Factory dataSourceFactory = createDataSourceFactory();
        int tsFlags = DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
                | DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES;
        DefaultExtractorsFactory extractorsFactory = new DefaultExtractorsFactory().setTsExtractorFlags(tsFlags);
        DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory);
        MediaItem.Builder itemBuilder = new MediaItem.Builder()
                .setUri(PlaybackPolicy.directPlaybackUrl(url)).setMediaId(title);
        // The no-extension variant is explicitly a container-sniffing fallback;
        // forcing the old extension here defeated that fallback.
        String mimeType = "no-extension".equals(sourceVariant)
                ? null : PlaybackPolicy.mimeType(extension);
        if (mimeType != null) itemBuilder.setMimeType(mimeType);
        MediaItem item = itemBuilder.build();
        return PlaybackPolicy.isHls(extension) && !"no-extension".equals(sourceVariant)
                ? new HlsMediaSource.Factory(dataSourceFactory)
                    .setExtractorFactory(new DefaultHlsExtractorFactory(tsFlags, true)).createMediaSource(item)
                : mediaSourceFactory.createMediaSource(item);
    }

    private void initializePlayer() {
        if (player != null || !validUrl(url)) return;
        releaseVlcPlayer();
        usingVlc = false;
        firstFrameRendered = false;
        playbackHandler.removeCallbacks(markPlaybackStable);
        vlcSurface.setVisibility(View.GONE);
        playerView.setVisibility(View.VISIBLE);
        DataSource.Factory dataSourceFactory = createDataSourceFactory();
        int tsFlags = DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
                | DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES;
        DefaultExtractorsFactory extractorsFactory = new DefaultExtractorsFactory().setTsExtractorFlags(tsFlags);
        DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory);
        String decoderMode = playerSetting(SettingsActivity.KEY_DECODER, "auto");
        boolean strictHardware = "hardware".equals(decoderMode);
        int extensionMode = "software".equals(decoderMode)
                ? DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                : DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON;
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this)
                .setEnableDecoderFallback(!strictHardware).setExtensionRendererMode(extensionMode);
        player = new ExoPlayer.Builder(this, renderers).setMediaSourceFactory(mediaSourceFactory)
                .setLoadControl(createLoadControl()).build();
        player.addListener(this);
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        player.setHandleAudioBecomingNoisy(true);
        player.setWakeMode(C.WAKE_MODE_NETWORK);
        if ("stereo".equals(playerSetting(SettingsActivity.KEY_AUDIO_OUTPUT, "auto"))) {
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .setMaxAudioChannelCount(2).build());
        }
        String subtitlePreference = playerSetting(SettingsActivity.KEY_SUBTITLE_LANGUAGE, "ar");
        if ("off".equals(subtitlePreference)) {
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
        } else if ("ar".equals(subtitlePreference)) {
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .setPreferredTextLanguage("ar").build());
        }
        playerView.setPlayer(player);
        playbackStartedAtMs = SystemClock.elapsedRealtime();
        Log.i(TAG, "open kind=" + kind + " ext=" + extension + " step=" + recoveryStep
                + " uhd=" + isUltraHd() + " transport=" + activeTransportName()
                + " stream=" + playerSetting(SettingsActivity.KEY_STREAM, "auto")
                + " buffer=" + playerSetting(SettingsActivity.KEY_BUFFER, "auto") + " decoder=" + decoderMode);
        player.setMediaSource(createCurrentMediaSource(), Math.max(0, resumePosition));
        player.prepare();
        player.play();
        progress.setVisibility(View.VISIBLE);
        errorPanel.setVisibility(View.GONE);
        playerView.requestFocus();
        schedulePlaybackTimeout();
    }

    private void openVlc(String media3Reason) {
        if (!validUrl(url)) {
            showPlaybackFailure("تعذر تجهيز رابط البث");
            return;
        }
        releaseMedia3Player();
        releaseVlcPlayer();
        usingVlc = true;
        vlcAttempted = true;
        firstFrameRendered = false;
        playbackStartedAtMs = SystemClock.elapsedRealtime();
        playerView.setVisibility(View.GONE);
        vlcSurface.setVisibility(View.VISIBLE);
        progress.setVisibility(View.VISIBLE);
        errorPanel.setVisibility(View.GONE);

        try {
            int cacheMs = vlcCacheMs();
            ArrayList<String> options = new ArrayList<>();
            options.add("--audio-time-stretch");
            options.add("--http-reconnect");
            options.add("--network-caching=" + cacheMs);
            options.add("--file-caching=" + cacheMs);
            if ("stereo".equals(playerSetting(SettingsActivity.KEY_AUDIO_OUTPUT, "auto"))) {
                options.add("--stereo-mode=1");
            }
            options.add("--freetype-rel-fontsize=" + vlcSubtitleRelativeSize());
            libVLC = new LibVLC(this, options);
            vlcPlayer = new org.videolan.libvlc.MediaPlayer(libVLC);
            vlcSubtitlePreferenceApplied = false;
            org.videolan.libvlc.MediaPlayer opened = vlcPlayer;
            int token = ++vlcGeneration;
            opened.setEventListener(event -> playbackHandler.post(() -> {
                if (token != vlcGeneration || opened != vlcPlayer || isFinishing()) return;
                onVlcEvent(event);
            }));
            IVLCVout output = opened.getVLCVout();
            output.setVideoView(vlcSurface);
            output.attachViews();

            org.videolan.libvlc.Media media = new org.videolan.libvlc.Media(libVLC, Uri.parse(url));
            String decoderMode = playerSetting(SettingsActivity.KEY_DECODER, "auto");
            media.setHWDecoderEnabled(!"software".equals(decoderMode), "hardware".equals(decoderMode));
            media.addOption(":http-user-agent=" + PlaybackTransportFactory.userAgent(2));
            if (!playbackReferer.isEmpty()) media.addOption(":http-referrer=" + playbackReferer);
            media.addOption(":network-caching=" + cacheMs);
            if ("stereo".equals(playerSetting(SettingsActivity.KEY_AUDIO_OUTPUT, "auto"))) {
                media.addOption(":stereo-mode=1");
            }
            media.addOption(":freetype-rel-fontsize=" + vlcSubtitleRelativeSize());
            opened.setMedia(media);
            media.release();
            applyVlcAspect(opened);
            opened.play();
            vlcSurface.requestFocus();
            schedulePlaybackTimeout();
            Log.i(TAG, "compat-open ext=" + extension + " uhd=" + isUltraHd()
                    + " media3Reason=" + media3Reason);
        } catch (Throwable error) {
            Log.w(TAG, "compat-engine-init-failed", error);
            showPlaybackFailure("مشغل التوافق غير متاح على هذا الجهاز");
        }
    }

    private void onVlcEvent(org.videolan.libvlc.MediaPlayer.Event event) {
        if (event == null || !usingVlc) return;
        switch (event.type) {
            case org.videolan.libvlc.MediaPlayer.Event.Vout:
            case org.videolan.libvlc.MediaPlayer.Event.TimeChanged:
            case org.videolan.libvlc.MediaPlayer.Event.PositionChanged:
                applyVlcSubtitlePreference();
                markVlcFirstFrame();
                break;
            case org.videolan.libvlc.MediaPlayer.Event.Buffering:
                if (!firstFrameRendered) progress.setVisibility(View.VISIBLE);
                break;
            case org.videolan.libvlc.MediaPlayer.Event.EndReached:
                recoverFromFailure("انتهى اتصال البث المباشر");
                break;
            case org.videolan.libvlc.MediaPlayer.Event.EncounteredError:
                recoverFromFailure("تعذر فتح المصدر بمشغل التوافق");
                break;
            default:
                break;
        }
    }

    private void markVlcFirstFrame() {
        if (firstFrameRendered || !usingVlc) return;
        firstFrameRendered = true;
        playbackHandler.removeCallbacks(playbackTimeout);
        progress.setVisibility(View.GONE);
        playbackHandler.removeCallbacks(hideTitle);
        playbackHandler.postDelayed(hideTitle, 2_500L);
        long firstFrameMs = playbackStartedAtMs == 0
                ? -1 : SystemClock.elapsedRealtime() - playbackStartedAtMs;
        Log.i(TAG, "compat-first-frame ext=" + extension + " ms=" + firstFrameMs);
    }

    private void applyVlcSubtitlePreference() {
        if (vlcSubtitlePreferenceApplied || vlcPlayer == null) return;
        String preference = playerSetting(SettingsActivity.KEY_SUBTITLE_LANGUAGE, "ar");
        if ("off".equals(preference)) {
            vlcPlayer.setSpuTrack(-1);
            vlcSubtitlePreferenceApplied = true;
            return;
        }
        if (!"ar".equals(preference)) {
            vlcSubtitlePreferenceApplied = true;
            return;
        }
        org.videolan.libvlc.MediaPlayer.TrackDescription[] tracks = vlcPlayer.getSpuTracks();
        if (tracks == null || tracks.length == 0) return;
        for (org.videolan.libvlc.MediaPlayer.TrackDescription track : tracks) {
            String name = track == null || track.name == null ? "" : track.name.toLowerCase(Locale.US);
            if (track != null && track.id >= 0
                    && (name.contains("arab") || name.contains("عرب") || "ar".equals(name.trim()))) {
                vlcPlayer.setSpuTrack(track.id);
                break;
            }
        }
        vlcSubtitlePreferenceApplied = true;
    }

    private void replaceLiveSourceOnWarmPlayer() {
        if (player == null || !validUrl(url)) { initializePlayer(); return; }
        try {
            firstFrameRendered = false;
            playbackHandler.removeCallbacks(playbackTimeout);
            playbackHandler.removeCallbacks(markPlaybackStable);
            playbackStartedAtMs = SystemClock.elapsedRealtime();
            player.setMediaSource(createCurrentMediaSource(), true);
            player.prepare();
            player.play();
            progress.setVisibility(View.VISIBLE);
            errorPanel.setVisibility(View.GONE);
            playerView.requestFocus();
            schedulePlaybackTimeout();
            Log.i(TAG, "warm-live-switch id=" + id + " ext=" + extension + " transport=" + activeTransportName());
        } catch (Throwable error) {
            Log.w(TAG, "warm-live-switch-failed fallback=rebuild", error);
            releasePlayer();
            initializePlayer();
        }
    }

    private void switchLiveChannel(BlofyModels.Media media) {
        if (!isLive() || media == null || media.id.equals(id)) return;
        playbackHandler.removeCallbacks(playbackTimeout);
        playbackHandler.removeCallbacks(markPlaybackStable);
        playbackHandler.removeCallbacks(hideTitle);
        id = media.id;
        title = media.name;
        extension = configuredExtension(PlaybackPolicy.normalizeExtension(media.extension, "ts"));
        sourceVariant = "canonical";
        canonicalUrl = "";
        canonicalExtension = "";
        canonicalReferer = "";
        playbackReferer = "";
        url = null;
        resumePosition = 0;
        recoveryStep = preferredRecoveryStep();
        vlcAttempted = false;
        if (usingVlc) {
            releaseVlcPlayer();
            usingVlc = false;
        }
        warmLiveSwitchPending = player != null;
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

        if (usingVlc) {
            showPlaybackFailure(reason);
            return;
        }

        Log.w(TAG, "bounded-recovery reason=" + reason + " ext=" + extension
                + " variant=" + sourceVariant + " uhd=" + isUltraHd());

        // A fast HTTP/connection error can be specific to the signed relay.
        // Resolve the direct source once; do not add TS/HLS and Cronet retries
        // behind it. Slow startup and decoder failures go straight to LibVLC.
        if (PlaybackPolicy.isNetworkFailure(reason)
                && !PlaybackPolicy.isStartupTimeout(reason)
                && "canonical".equals(sourceVariant) && !id.isEmpty()) {
            releasePlayer();
            sourceVariant = "direct";
            recoveryStep = 1;
            url = null;
            resolvePlaybackLink();
            return;
        }

        if (!vlcAttempted) {
            recoveryStep = 2;
            if ("direct".equals(sourceVariant)) restoreCanonicalSource();
            openVlc(reason);
            return;
        }
        showPlaybackFailure(reason);
    }

    private void showPlaybackFailure(String reason) {
        releaseMedia3Player();
        releaseVlcPlayer();
        usingVlc = false;
        progress.setVisibility(View.GONE);
        errorPanel.setVisibility(View.VISIBLE);
        String detail = reason == null || reason.trim().isEmpty()
                ? "المصدر لا يرسل فيديو قابلاً للتشغيل" : reason.trim();
        errorText.setText("تعذر تشغيل القناة بعد المحاولة بالمشغل الأساسي والمتوافق."
                + "\n" + detail + "\nالصيغة: " + extension);
        retryButton.setText("إعادة المحاولة من البداية");
        retryButton.requestFocus();
    }

    private void reopenResolvedSource(boolean forceResolve) {
        if (!forceResolve && validUrl(url)) { initializePlayer(); return; }
        if (!id.isEmpty()) { url = null; resolvePlaybackLink(); } else { initializePlayer(); }
    }

    private void manualRetry() {
        recoveryStep = preferredRecoveryStep();
        vlcAttempted = false;
        sourceVariant = "canonical";
        canonicalUrl = "";
        canonicalExtension = "";
        canonicalReferer = "";
        playbackReferer = "";
        warmLiveSwitchPending = false;
        releasePlayer();
        url = null;
        reopenResolvedSource(true);
    }

    @Override public void onPlaybackStateChanged(int playbackState) {
        if (playbackState == Player.STATE_BUFFERING) {
            if (!firstFrameRendered) progress.setVisibility(View.VISIBLE);
            return;
        }
        if (playbackState == Player.STATE_READY) {
            long readyMs = playbackStartedAtMs == 0 ? -1 : SystemClock.elapsedRealtime() - playbackStartedAtMs;
            Log.i(TAG, "ready kind=" + kind + " ext=" + extension + " ms=" + readyMs
                    + " firstFrame=" + firstFrameRendered + " transport=" + activeTransportName());
            if (firstFrameRendered) progress.setVisibility(View.GONE);
            playbackHandler.removeCallbacks(hideTitle);
            playbackHandler.postDelayed(hideTitle, 2500);
            return;
        }
        if (playbackState == Player.STATE_ENDED) {
            progress.setVisibility(View.GONE);
            if (isLive()) recoverFromFailure("انتهى اتصال البث المباشر");
        }
    }

    @Override public void onRenderedFirstFrame() {
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

    @Override public void onPlayerError(PlaybackException error) {
        playbackHandler.removeCallbacks(playbackTimeout);
        playbackHandler.removeCallbacks(markPlaybackStable);
        Log.w(TAG, "player-error code=" + error.errorCode + " name=" + error.getErrorCodeName()
                + " ext=" + extension + " transport=" + activeTransportName(), error);
        if (isLive() && error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && player != null) {
            try {
                player.seekToDefaultPosition();
                player.prepare();
                player.play();
                firstFrameRendered = false;
                schedulePlaybackTimeout();
                return;
            } catch (Exception ignored) {}
        }
        recoverFromFailure(playbackErrorReason(error));
    }

    private static String playbackErrorReason(PlaybackException error) {
        if (error == null) return "Media3 error";
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof HttpDataSource.InvalidResponseCodeException) {
                int status = ((HttpDataSource.InvalidResponseCodeException) cause).responseCode;
                return "HTTP " + status;
            }
            cause = cause.getCause();
        }
        return error.getErrorCodeName();
    }

    private void savePosition() {
        if (player == null || isLive()) return;
        long position = player.getCurrentPosition();
        long duration = player.getDuration();
        if (duration > 0 && position > duration - 30_000) position = 0;
        position = Math.max(0, position);
        PlaybackProgress.save(this, kind, id, position);
        resumePosition = position;
    }

    private void releaseMedia3Player() {
        if (player == null) return;
        savePosition();
        playerView.setPlayer(null);
        player.removeListener(this);
        player.release();
        player = null;
    }

    private void releaseVlcPlayer() {
        vlcGeneration++;
        org.videolan.libvlc.MediaPlayer current = vlcPlayer;
        vlcPlayer = null;
        if (current != null) {
            try { current.setEventListener(null); } catch (Exception ignored) {}
            try { current.stop(); } catch (Exception ignored) {}
            try { current.getVLCVout().detachViews(); } catch (Exception ignored) {}
            try { current.release(); } catch (Exception ignored) {}
        }
        if (libVLC != null) {
            try { libVLC.release(); } catch (Exception ignored) {}
            libVLC = null;
        }
    }

    private void releasePlayer() {
        playbackHandler.removeCallbacks(playbackTimeout);
        playbackHandler.removeCallbacks(markPlaybackStable);
        playbackHandler.removeCallbacks(hideTitle);
        warmLiveSwitchPending = false;
        releaseMedia3Player();
        releaseVlcPlayer();
        usingVlc = false;
        firstFrameRendered = false;
        playbackStartedAtMs = 0;
    }

    private void cancelResolve(boolean invalidateGeneration) {
        if (invalidateGeneration) resolveGeneration++;
        BlofyApi.Cancellation cancellation = resolveCancellation;
        resolveCancellation = null;
        if (cancellation != null) cancellation.cancel();
        Future<?> task = resolveTask;
        resolveTask = null;
        if (task != null) task.cancel(true);
    }

    private void requestPlaybackFocus() {
        if (usingVlc && vlcSurface.getVisibility() == View.VISIBLE) vlcSurface.requestFocus();
        else playerView.requestFocus();
    }

    @Override protected void onStart() {
        super.onStart();
        lifecycleStarted = true;
        if (validUrl(url) && errorPanel.getVisibility() != View.VISIBLE) {
            vlcAttempted = false;
            recoveryStep = preferredRecoveryStep();
            initializePlayer();
        } else if (!id.isEmpty() && errorPanel.getVisibility() != View.VISIBLE
                && (resolveTask == null || resolveTask.isDone())) {
            resolvePlaybackLink();
        }
    }

    @Override protected void onStop() {
        lifecycleStarted = false;
        cancelResolve(true);
        releasePlayer();
        super.onStop();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        // Modal actions must receive DPAD_CENTER before the live-player shortcut.
        if (errorPanel != null && errorPanel.getVisibility() == View.VISIBLE) {
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                finish();
                return true;
            }
            return super.dispatchKeyEvent(event);
        }

        // While the drawer is visible, its RecyclerView owns DPAD/OK. Only Back is
        // handled here so focus can never leak to PlayerView behind the scrim.
        if (liveOverlay != null && liveOverlay.isVisible()) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    liveOverlay.hide();
                    requestPlaybackFocus();
                    return true;
                }
                if (event.getKeyCode() == KeyEvent.KEYCODE_CHANNEL_UP) {
                    liveOverlay.selectRelative(id, -1);
                    return true;
                }
                if (event.getKeyCode() == KeyEvent.KEYCODE_CHANNEL_DOWN) {
                    liveOverlay.selectRelative(id, 1);
                    return true;
                }
            }
            return super.dispatchKeyEvent(event);
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_BACK:
                    finish(); return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (isLive() && liveOverlay != null && !liveOverlay.isVisible()) {
                        liveOverlay.show(id); return true;
                    }
                    break;
                case KeyEvent.KEYCODE_CHANNEL_UP:
                    if (isLive() && liveOverlay != null) { liveOverlay.selectRelative(id, -1); return true; }
                    break;
                case KeyEvent.KEYCODE_CHANNEL_DOWN:
                    if (isLive() && liveOverlay != null) { liveOverlay.selectRelative(id, 1); return true; }
                    break;
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    if (usingVlc && vlcPlayer != null) {
                        if (vlcPlayer.isPlaying()) vlcPlayer.pause(); else vlcPlayer.play();
                    } else if (player != null) {
                        if (player.isPlaying()) player.pause(); else player.play();
                    }
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                    if (usingVlc && vlcPlayer != null) vlcPlayer.play();
                    else if (player != null) player.play();
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    if (usingVlc && vlcPlayer != null) vlcPlayer.pause();
                    else if (player != null) player.pause();
                    return true;
                default: break;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public void onBackPressed() {
        if (liveOverlay != null && liveOverlay.isVisible()) {
            liveOverlay.hide(); requestPlaybackFocus(); return;
        }
        finish();
    }

    @Override protected void onDestroy() {
        lifecycleStarted = false;
        cancelResolve(true);
        playbackHandler.removeCallbacksAndMessages(null);
        releasePlayer();
        if (liveOverlay != null) liveOverlay.close();
        network.shutdownNow();
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
