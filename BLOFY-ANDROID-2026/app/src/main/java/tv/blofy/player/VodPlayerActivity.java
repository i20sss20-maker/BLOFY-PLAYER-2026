package tv.blofy.player;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
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
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.AspectRatioFrameLayout;

import org.json.JSONObject;
import org.videolan.libvlc.interfaces.IVLCVout;
import org.videolan.libvlc.LibVLC;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Dedicated VOD engine: one Media3 attempt, then a bounded LibVLC fallback. */
@UnstableApi
public final class VodPlayerActivity extends Activity implements Player.Listener {
    public static final String EXTRA_ID = "id";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_KIND = "kind";
    public static final String EXTRA_EXTENSION = "extension";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
            new ThreadPoolExecutor.DiscardOldestPolicy());

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
    private TextView errorTitle;
    private TextView retryButton;
    private TextView audioButton;
    private TextView subtitleButton;
    private TextView stereoButton;
    private SeekBar seekBar;

    private String id;
    private String title;
    private String kind;
    private String extension;
    private String resolvedUrl;
    private String playbackReferer = "";
    private String canonicalUrl = "";
    private String canonicalExtension = "";
    private String canonicalReferer = "";
    private String sourceVariant = "canonical";
    private long resumePosition;
    private boolean usingVlc;
    private boolean firstFrame;
    private boolean resolving;
    private boolean alternateSourceAttempted;
    private int attempt;
    private int vlcAudioIndex = -1;
    private int vlcSubtitleIndex = -1;
    private boolean vlcSubtitlePreferenceApplied;
    private boolean stereoMode;
    private boolean lifecycleStopped = true;
    private boolean destroyed;
    private boolean contentEnded;
    private int resolveGeneration;
    private int vlcGeneration;
    private Future<?> resolveTask;
    private BlofyApi.Cancellation resolveCancellation;

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
        if (controls != null && errorPanel.getVisibility() != View.VISIBLE && !controls.hasFocus()) {
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
        resumePosition = PlaybackProgress.get(this, kind, id);
        stereoMode = "stereo".equals(getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE)
                .getString(SettingsActivity.KEY_AUDIO_OUTPUT, "auto"));
        PlaybackTransportFactory.warmUpCronet(this);
        buildUi();
        hideSystemUi();
        resolve();
    }

    private static String value(String value) { return value == null ? "" : value; }

    private String setting(String key, String fallback) {
        return getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE)
                .getString(key, fallback);
    }

    private void applySubtitleStyle() {
        if (playerView == null || playerView.getSubtitleView() == null) return;
        String size = setting(SettingsActivity.KEY_SUBTITLE_SIZE, "medium");
        float fraction = "small".equals(size) ? 0.043f : "large".equals(size) ? 0.062f : 0.053f;
        playerView.getSubtitleView().setFractionalTextSize(fraction);
    }

    private int vlcSubtitleRelativeSize() {
        String size = setting(SettingsActivity.KEY_SUBTITLE_SIZE, "medium");
        return "small".equals(size) ? 18 : "large".equals(size) ? 26 : 22;
    }

    private void applyVlcAspect(org.videolan.libvlc.MediaPlayer target) {
        if (target == null) return;
        String aspect = setting(SettingsActivity.KEY_ASPECT, "fit");
        try {
            if ("fill".equals(aspect)) {
                android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
                target.getVLCVout().setWindowSize(metrics.widthPixels, metrics.heightPixels);
                target.setAspectRatio(metrics.widthPixels + ":" + metrics.heightPixels);
                target.setScale(0f);
            } else if ("zoom".equals(aspect)) {
                target.setAspectRatio(null);
                target.setScale(1.12f);
            } else {
                target.setAspectRatio(null);
                target.setScale(0f);
            }
        } catch (Throwable ignored) {
            // Older LibVLC builds may not expose every sizing operation.
        }
    }

    private boolean ultraHd() {
        String upper = (title + " " + extension).toUpperCase(Locale.US);
        return upper.contains("4K") || upper.contains("UHD") || upper.contains("2160")
                || upper.contains("HEVC") || upper.contains("H265") || upper.contains("H.265");
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(3, 3, 8));

        vlcSurface = new SurfaceView(this);
        vlcSurface.setVisibility(View.GONE);
        vlcSurface.setKeepScreenOn(true);
        root.addView(vlcSurface, new FrameLayout.LayoutParams(-1, -1));

        playerView = new PlayerView(this);
        playerView.setId(View.generateViewId());
        playerView.setUseController(false);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER);
        playerView.setKeepScreenOn(true);
        playerView.setFocusable(true);
        String aspect = getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE)
                .getString(SettingsActivity.KEY_ASPECT, "fit");
        playerView.setResizeMode("zoom".equals(aspect)
                ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                : "fill".equals(aspect) ? AspectRatioFrameLayout.RESIZE_MODE_FILL
                : AspectRatioFrameLayout.RESIZE_MODE_FIT);
        applySubtitleStyle();
        root.addView(playerView, new FrameLayout.LayoutParams(-1, -1));

        spinner = new ProgressBar(this);
        spinner.setIndeterminateTintList(BlofyUi.progressColors());
        spinner.setElevation(dp(8));
        root.addView(spinner, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));

        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setGravity(Gravity.BOTTOM);
        controls.setPadding(dp(40), dp(18), dp(40), dp(16));
        controls.setBackground(playerControlsGradient());

        View accent = new View(this);
        accent.setBackgroundColor(BlofyUi.PURPLE);
        LinearLayout.LayoutParams accentParams = new LinearLayout.LayoutParams(dp(88), dp(3));
        accentParams.bottomMargin = dp(8);
        controls.addView(accent, accentParams);

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        titleView = BlofyUi.title(this, title, 20);
        titleView.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        titleView.setTextDirection(View.TEXT_DIRECTION_LTR);
        titleView.setMaxLines(1);
        heading.addView(titleView, new LinearLayout.LayoutParams(0, dp(44), 1));
        engineView = BlofyUi.text(this, "Media3", 11, BlofyUi.PURPLE_LIGHT);
        engineView.setGravity(Gravity.CENTER);
        engineView.setTextDirection(View.TEXT_DIRECTION_LTR);
        engineView.setMaxLines(1);
        engineView.setPadding(dp(14), 0, dp(14), 0);
        engineView.setBackground(cinemaPanel(Color.argb(220, 24, 17, 43), 18, 1, Color.rgb(102, 49, 190)));
        engineView.setVisibility(View.GONE);
        controls.addView(heading, new LinearLayout.LayoutParams(-1, dp(44)));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView rewind = transportBadge("−10");
        row.addView(rewind, new LinearLayout.LayoutParams(dp(46), dp(34)));

        seekBar = new SeekBar(this);
        seekBar.setMax(1000);
        seekBar.setFocusable(false);
        seekBar.setProgressTintList(ColorStateList.valueOf(BlofyUi.PURPLE));
        seekBar.setProgressBackgroundTintList(ColorStateList.valueOf(Color.rgb(69, 67, 78)));
        seekBar.setThumbTintList(ColorStateList.valueOf(BlofyUi.PURPLE_LIGHT));
        LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(0, dp(46), 1);
        seekParams.leftMargin = dp(8);
        seekParams.rightMargin = dp(8);
        row.addView(seekBar, seekParams);

        TextView forward = transportBadge("+10");
        row.addView(forward, new LinearLayout.LayoutParams(dp(46), dp(34)));

        timeView = BlofyUi.text(this, "00:00  /  00:00", 12, Color.WHITE);
        timeView.setGravity(Gravity.CENTER);
        timeView.setTextDirection(View.TEXT_DIRECTION_LTR);
        timeView.setBackground(cinemaPanel(Color.argb(170, 12, 12, 19), 14, 1, Color.rgb(58, 56, 68)));
        LinearLayout.LayoutParams timeParams = new LinearLayout.LayoutParams(dp(174), dp(34));
        timeParams.leftMargin = dp(12);
        row.addView(timeView, timeParams);
        controls.addView(row, new LinearLayout.LayoutParams(-1, dp(50)));

        LinearLayout trackRow = new LinearLayout(this);
        trackRow.setOrientation(LinearLayout.HORIZONTAL);
        trackRow.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        trackRow.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        audioButton = playerOptionButton("🔊  الصوت: تلقائي");
        audioButton.setOnClickListener(v -> cycleAudio());
        trackRow.addView(audioButton, new LinearLayout.LayoutParams(dp(250), dp(42)));
        subtitleButton = playerOptionButton("CC  الترجمة: تلقائي");
        subtitleButton.setOnClickListener(v -> cycleSubtitle());
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(dp(250), dp(42));
        subtitleParams.leftMargin = dp(10);
        trackRow.addView(subtitleButton, subtitleParams);
        stereoButton = playerOptionButton("♫  المخرج: تلقائي");
        stereoButton.setOnClickListener(v -> toggleStereo());
        LinearLayout.LayoutParams stereoParams = new LinearLayout.LayoutParams(dp(220), dp(42));
        stereoParams.leftMargin = dp(10);
        trackRow.addView(stereoButton, stereoParams);
        audioButton.setId(View.generateViewId());
        subtitleButton.setId(View.generateViewId());
        stereoButton.setId(View.generateViewId());
        audioButton.setNextFocusRightId(subtitleButton.getId());
        subtitleButton.setNextFocusLeftId(audioButton.getId());
        subtitleButton.setNextFocusRightId(stereoButton.getId());
        stereoButton.setNextFocusLeftId(subtitleButton.getId());
        audioButton.setNextFocusLeftId(audioButton.getId());
        stereoButton.setNextFocusRightId(stereoButton.getId());
        audioButton.setNextFocusUpId(audioButton.getId());
        subtitleButton.setNextFocusUpId(subtitleButton.getId());
        stereoButton.setNextFocusUpId(stereoButton.getId());
        audioButton.setNextFocusDownId(playerView.getId());
        subtitleButton.setNextFocusDownId(playerView.getId());
        stereoButton.setNextFocusDownId(playerView.getId());
        View.OnFocusChangeListener fixedControlFocus = (view, focused) -> {
            view.animate().cancel();
            view.setScaleX(1f);
            view.setScaleY(1f);
            if (focused) {
                controls.setVisibility(View.VISIBLE);
                main.removeCallbacks(hideControls);
            } else {
                main.post(() -> {
                    if (controls != null && !controls.hasFocus()) showControls();
                });
            }
        };
        audioButton.setOnFocusChangeListener(fixedControlFocus);
        subtitleButton.setOnFocusChangeListener(fixedControlFocus);
        stereoButton.setOnFocusChangeListener(fixedControlFocus);
        controls.addView(trackRow, new LinearLayout.LayoutParams(-1, dp(46)));

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        TextView hints = BlofyUi.text(this,
                "◀ ▶ تقديم وتأخير  •  OK تشغيل/إيقاف  •  ↑ خيارات الصوت والترجمة  •  MENU خيارات",
                12, BlofyUi.MUTED);
        hints.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        footer.addView(hints, new LinearLayout.LayoutParams(0, dp(34), 1));
        TextView signature = BlofyUi.text(this, "BLOFY  •  CINEMA", 10, BlofyUi.PURPLE_LIGHT);
        signature.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        signature.setTextDirection(View.TEXT_DIRECTION_LTR);
        footer.addView(signature, new LinearLayout.LayoutParams(dp(170), dp(34)));
        controls.addView(footer, new LinearLayout.LayoutParams(-1, dp(34)));

        root.addView(controls, new FrameLayout.LayoutParams(-1, dp(248), Gravity.BOTTOM));

        errorPanel = new LinearLayout(this);
        errorPanel.setOrientation(LinearLayout.VERTICAL);
        errorPanel.setGravity(Gravity.CENTER);
        errorPanel.setPadding(dp(38), dp(30), dp(38), dp(30));
        errorPanel.setBackground(cinemaPanel(Color.argb(250, 12, 11, 20), 22, 2, Color.rgb(112, 50, 214)));
        errorPanel.setElevation(dp(20));
        errorPanel.setVisibility(View.GONE);

        TextView errorMark = BlofyUi.title(this, "!", 22);
        errorMark.setGravity(Gravity.CENTER);
        errorMark.setTextColor(BlofyUi.PURPLE_LIGHT);
        errorMark.setBackground(cinemaPanel(Color.rgb(37, 20, 62), 22, 1, BlofyUi.PURPLE));
        errorPanel.addView(errorMark, new LinearLayout.LayoutParams(dp(44), dp(44)));

        errorTitle = BlofyUi.title(this, "تعذر تشغيل المحتوى", 24);
        errorTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams errorTitleParams = new LinearLayout.LayoutParams(-1, dp(52));
        errorTitleParams.topMargin = dp(8);
        errorPanel.addView(errorTitle, errorTitleParams);
        errorText = BlofyUi.text(this, "", 14, BlofyUi.MUTED);
        errorText.setGravity(Gravity.CENTER);
        errorText.setLineSpacing(0, 1.18f);
        errorPanel.addView(errorText, new LinearLayout.LayoutParams(dp(620), -2));

        retryButton = BlofyUi.title(this, "↻   إعادة المحاولة", 15);
        retryButton.setGravity(Gravity.CENTER);
        retryButton.setFocusable(true);
        retryButton.setClickable(true);
        retryButton.setBackground(retryButtonBackground());
        retryButton.setOnClickListener(v -> retryFromBeginning());
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(dp(260), dp(56));
        rp.topMargin = dp(22);
        errorPanel.addView(retryButton, rp);
        root.addView(errorPanel, new FrameLayout.LayoutParams(dp(720), -2, Gravity.CENTER));

        setContentView(root);
        showControls();
        playerView.requestFocus();
        main.post(updateProgress);
    }

    private void resolve() {
        if (resolving || id.isEmpty()) return;
        cancelResolve(false);
        resolving = true;
        int token = ++resolveGeneration;
        BlofyApi.Cancellation cancellation = new BlofyApi.Cancellation();
        resolveCancellation = cancellation;
        spinner.setVisibility(View.VISIBLE);
        errorPanel.setVisibility(View.GONE);
        String requestedExtension = extension;
        String requestedVariant = sourceVariant;
        String requestedReferer = playbackReferer;
        resolveTask = network.submit(() -> {
            try {
                String apiType = "episode".equals(kind) || "series".equals(kind) ? "episode" : "movies";
                JSONObject data = new BlofyApi(this).getPlayback(
                        "/api/native-link/" + BlofyApi.encode(apiType) + "/" + BlofyApi.encode(id)
                                + "?ext=" + BlofyApi.encode(requestedExtension)
                                + "&variant=" + BlofyApi.encode(requestedVariant), cancellation);
                String result = data.optString("url", "");
                if (result.startsWith("/")) {
                    result = BuildConfig.BLOFY_BASE_URL.replaceAll("/+$", "") + result;
                }
                if (!validUrl(result)) throw new Exception("رابط الفيديو غير صالح");
                String finalUrl = result;
                String finalExtension = PlaybackPolicy.normalizeExtension(
                        data.optString("extension", requestedExtension), requestedExtension);
                String finalReferer = data.optString("referer", requestedReferer);
                main.post(() -> {
                    if (destroyed || token != resolveGeneration || isFinishing()) return;
                    resolvedUrl = finalUrl;
                    extension = finalExtension;
                    playbackReferer = finalReferer;
                    if ("canonical".equals(requestedVariant)) {
                        canonicalUrl = resolvedUrl;
                        canonicalExtension = extension;
                        canonicalReferer = playbackReferer;
                    }
                    resolving = false;
                    if (!lifecycleStopped) openMedia3();
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (destroyed || token != resolveGeneration || isFinishing()) return;
                    resolving = false;
                    if ("direct".equals(requestedVariant) && restoreCanonicalSource()) {
                        if (!lifecycleStopped) openVlc(PlaybackPolicy.resolveErrorMessage(error));
                        return;
                    }
                    showError(PlaybackPolicy.resolveErrorMessage(error));
                });
            }
        });
    }

    private boolean restoreCanonicalSource() {
        if (!validUrl(canonicalUrl)) return false;
        resolvedUrl = canonicalUrl;
        extension = PlaybackPolicy.normalizeExtension(canonicalExtension, extension);
        playbackReferer = canonicalReferer;
        sourceVariant = "canonical";
        attempt = 2;
        return true;
    }

    private void cancelResolve(boolean invalidateGeneration) {
        if (invalidateGeneration) resolveGeneration++;
        BlofyApi.Cancellation cancellation = resolveCancellation;
        resolveCancellation = null;
        if (cancellation != null) cancellation.cancel();
        Future<?> task = resolveTask;
        resolveTask = null;
        if (task != null) task.cancel(true);
        resolving = false;
    }

    private void openMedia3() {
        if (!validUrl(resolvedUrl)) { showError("تعذر تجهيز رابط الفيديو"); return; }
        releaseAllEngines();
        usingVlc = false;
        firstFrame = false;
        playerView.setVisibility(View.VISIBLE);
        vlcSurface.setVisibility(View.GONE);

        DataSource.Factory source = PlaybackTransportFactory.create(
                this, false, network,
                ultraHd() ? 20_000 : 15_000,
                ultraHd() ? 45_000 : 30_000,
                attempt, playbackReferer);
        DefaultExtractorsFactory extractors = new DefaultExtractorsFactory();
        DefaultMediaSourceFactory mediaSources = new DefaultMediaSourceFactory(source, extractors);
        String decoderMode = setting(SettingsActivity.KEY_DECODER, "auto");
        boolean strictHardware = "hardware".equals(decoderMode);
        int extensionMode = "software".equals(decoderMode)
                ? DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                : DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON;
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this)
                .setEnableDecoderFallback(!strictHardware)
                .setExtensionRendererMode(extensionMode);

        String bufferMode = setting(SettingsActivity.KEY_BUFFER, "auto");
        if ("auto".equals(bufferMode) && DeviceCapabilityProfile.detect(this).usesReducedPerformance()) {
            bufferMode = "fast";
        }
        int min;
        int max;
        int start;
        int rebuffer;
        if ("fast".equals(bufferMode)) {
            min = 2_000; max = ultraHd() ? 20_000 : 14_000; start = 250; rebuffer = 750;
        } else if ("stable".equals(bufferMode) || ultraHd()) {
            min = 6_000; max = ultraHd() ? 36_000 : 28_000; start = 700; rebuffer = 1_800;
        } else {
            min = 3_000; max = 24_000; start = 350; rebuffer = 900;
        }
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
        if (stereoMode) {
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                    .setMaxAudioChannelCount(2)
                    .build());
        }
        String subtitlePreference = getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE)
                .getString(SettingsActivity.KEY_SUBTITLE_LANGUAGE, "ar");
        if ("off".equals(subtitlePreference)) {
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
        } else if ("ar".equals(subtitlePreference)) {
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .setPreferredTextLanguage("ar").build());
        }

        MediaItem.Builder item = new MediaItem.Builder()
                .setUri(PlaybackPolicy.directPlaybackUrl(resolvedUrl))
                .setMediaId(title);
        String mime = PlaybackPolicy.mimeType(extension);
        if (mime != null && (PlaybackPolicy.isHls(extension) || "mpd".equalsIgnoreCase(extension))) {
            item.setMimeType(mime);
        }
        player.setMediaItem(item.build(), Math.max(0, resumePosition));
        player.prepare();
        player.play();
        startWatchdog(PlaybackPolicy.vodStartupTimeoutMs(ultraHd()));
    }

    private void openVlc(String reason) {
        if (!validUrl(resolvedUrl)) { showError("تعذر تجهيز رابط الفيديو"); return; }
        try {
            releaseAllEngines();
            usingVlc = true;
            firstFrame = false;
            playerView.setVisibility(View.GONE);
            vlcSurface.setVisibility(View.VISIBLE);
            spinner.setVisibility(View.VISIBLE);
            errorPanel.setVisibility(View.GONE);

            int cacheMs = vlcCacheMs();
            ArrayList<String> options = new ArrayList<>();
            options.add("--audio-time-stretch");
            options.add("--network-caching=" + cacheMs);
            options.add("--file-caching=" + cacheMs);
            options.add("--http-reconnect");
            options.add("--freetype-rel-fontsize=" + vlcSubtitleRelativeSize());
            if (stereoMode) options.add("--stereo-mode=1");
            libVLC = new LibVLC(this, options);
            vlcPlayer = new org.videolan.libvlc.MediaPlayer(libVLC);
            vlcSubtitlePreferenceApplied = false;
            org.videolan.libvlc.MediaPlayer openedPlayer = vlcPlayer;
            int token = ++vlcGeneration;
            openedPlayer.setEventListener(event -> main.post(() -> {
                if (destroyed || token != vlcGeneration || vlcPlayer != openedPlayer) return;
                onVlcEvent(event);
            }));

            IVLCVout vout = openedPlayer.getVLCVout();
            vout.setVideoView(vlcSurface);
            vout.attachViews();

            org.videolan.libvlc.Media media = new org.videolan.libvlc.Media(libVLC, Uri.parse(resolvedUrl));
            String decoderMode = setting(SettingsActivity.KEY_DECODER, "auto");
            media.setHWDecoderEnabled(!"software".equals(decoderMode), "hardware".equals(decoderMode));
            media.addOption(":http-user-agent=" + PlaybackTransportFactory.userAgent(2));
            if (!playbackReferer.isEmpty()) media.addOption(":http-referrer=" + playbackReferer);
            media.addOption(":network-caching=" + cacheMs);
            media.addOption(":freetype-rel-fontsize=" + vlcSubtitleRelativeSize());
            if (stereoMode) media.addOption(":stereo-mode=1");
            openedPlayer.setMedia(media);
            media.release();
            applyVlcAspect(openedPlayer);
            openedPlayer.play();
            if (resumePosition > 0) {
                long resumeAtOpen = resumePosition;
                main.postDelayed(() -> {
                    if (!destroyed && token == vlcGeneration && vlcPlayer == openedPlayer) {
                        openedPlayer.setTime(resumeAtOpen);
                    }
                }, 700);
            }
            startWatchdog(PlaybackPolicy.vlcStartupTimeoutMs(ultraHd()));
            errorText.setText(reason == null ? "" : reason);
        } catch (Throwable error) {
            releaseAllEngines();
            showFinalPlaybackError("مشغل التوافق غير متاح على هذا الجهاز");
        }
    }

    private int vlcCacheMs() {
        String mode = setting(SettingsActivity.KEY_BUFFER, "auto");
        if ("fast".equals(mode)) return ultraHd() ? 500 : 320;
        if ("stable".equals(mode)) return ultraHd() ? 900 : 700;
        return ultraHd() ? 650 : 450;
    }

    private void onVlcEvent(org.videolan.libvlc.MediaPlayer.Event event) {
        if (event == null) return;
        switch (event.type) {
            case org.videolan.libvlc.MediaPlayer.Event.Playing:
            case org.videolan.libvlc.MediaPlayer.Event.Vout:
                updateVlcTrackButtons();
                break;
            case org.videolan.libvlc.MediaPlayer.Event.TimeChanged:
            case org.videolan.libvlc.MediaPlayer.Event.PositionChanged:
                updateVlcTrackButtons();
                if (!firstFrame) {
                    firstFrame = true;
                    spinner.setVisibility(View.GONE);
                    main.removeCallbacks(startupTimeout);
                    showControls();
                }
                break;
            case org.videolan.libvlc.MediaPlayer.Event.Buffering:
                if (!firstFrame) spinner.setVisibility(View.VISIBLE);
                break;
            case org.videolan.libvlc.MediaPlayer.Event.EndReached:
                onContentEnded();
                break;
            case org.videolan.libvlc.MediaPlayer.Event.EncounteredError:
                recover("تعذر فتح المصدر بمشغل التوافق");
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
            showFinalPlaybackError(reason);
            return;
        }

        // An immediate HTTP/connection failure can be caused by the signed
        // relay route. Resolve the provider's direct variant once, without also
        // walking through an identical Cronet retry. A slow/unsupported decoder
        // goes straight to LibVLC so total startup stays bounded.
        if (PlaybackPolicy.isNetworkFailure(reason)
                && !PlaybackPolicy.isStartupTimeout(reason)
                && !alternateSourceAttempted && !id.isEmpty()) {
            alternateSourceAttempted = true;
            sourceVariant = "direct";
            attempt = 1;
            releaseAllEngines();
            resolvedUrl = "";
            resolving = false;
            resolve();
            return;
        }

        if ("direct".equals(sourceVariant)) restoreCanonicalSource();
        attempt = 2;
        openVlc(reason);
    }

    private void showFinalPlaybackError(String reason) {
        String detail = reason == null || reason.trim().isEmpty()
                ? "المصدر لا يرسل فيديو قابلاً للتشغيل"
                : reason.trim();
        showError("تعذر تشغيل هذا المصدر بعد المحاولة بمشغل التوافق."
                + "\n" + detail + "\nالصيغة: " + extension);
    }

    private void showError(String message) {
        releaseAllEngines();
        spinner.setVisibility(View.GONE);
        controls.setVisibility(View.VISIBLE);
        errorPanel.setVisibility(View.VISIBLE);
        errorTitle.setText("تعذر تشغيل المحتوى");
        errorText.setText(message == null ? "حدث خطأ أثناء التشغيل" : message);
        if (retryButton != null) {
            retryButton.setText("↻   إعادة المحاولة");
            retryButton.setOnClickListener(v -> retryFromBeginning());
            retryButton.requestFocus();
        }
    }

    private void retryFromBeginning() {
        contentEnded = false;
        attempt = 0;
        usingVlc = false;
        sourceVariant = "canonical";
        alternateSourceAttempted = false;
        playbackReferer = "";
        canonicalUrl = "";
        canonicalExtension = "";
        canonicalReferer = "";
        releaseAllEngines();
        resolvedUrl = "";
        resolving = false;
        resolve();
    }

    private void onContentEnded() {
        if (contentEnded) return;
        contentEnded = true;
        spinner.setVisibility(View.GONE);
        resumePosition = 0;
        persistPosition(0);
        if (!"episode".equals(kind)) return;

        PlaybackProgress.NextEpisode next = PlaybackProgress.nextEpisode(this, id);
        if (next == null) return;
        String mode = setting(SettingsActivity.KEY_AUTO_NEXT, "ask");
        if ("on".equals(mode)) {
            startNextEpisode(next);
        } else if ("ask".equals(mode)) {
            showNextEpisodePrompt(next);
        }
    }

    private void showNextEpisodePrompt(PlaybackProgress.NextEpisode next) {
        releaseAllEngines();
        controls.setVisibility(View.VISIBLE);
        errorPanel.setVisibility(View.VISIBLE);
        errorTitle.setText("الحلقة التالية جاهزة");
        errorText.setText(next.title.isEmpty()
                ? "انتهت الحلقة. هل تريد تشغيل الحلقة التالية؟"
                : "انتهت الحلقة الحالية\n" + next.title);
        retryButton.setText("▶   تشغيل الحلقة التالية");
        retryButton.setOnClickListener(v -> startNextEpisode(next));
        retryButton.requestFocus();
    }

    private void startNextEpisode(PlaybackProgress.NextEpisode next) {
        if (next == null || next.id.isEmpty() || isFinishing()) return;
        PlaybackProgress.rememberEpisode(this, next.seriesId, next.id, next.title, next.extension);
        Intent intent = new Intent(this, VodPlayerActivity.class);
        intent.putExtra(EXTRA_ID, next.id);
        intent.putExtra(EXTRA_TITLE, next.title);
        intent.putExtra(EXTRA_KIND, "episode");
        intent.putExtra(EXTRA_EXTENSION, next.extension);
        startActivity(intent);
        finish();
    }

    @Override public void onPlaybackStateChanged(int state) {
        if (usingVlc) return;
        if (state == Player.STATE_BUFFERING && !firstFrame) spinner.setVisibility(View.VISIBLE);
        if (state == Player.STATE_READY && firstFrame) spinner.setVisibility(View.GONE);
        if (state == Player.STATE_ENDED) {
            onContentEnded();
        }
    }

    @Override public void onTracksChanged(Tracks tracks) {
        if (!usingVlc) updateMedia3TrackButtons();
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
        recover(playbackErrorReason(error));
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

    private void cycleAudio() {
        if (usingVlc) cycleVlcAudio(); else cycleMedia3Audio();
    }

    private void cycleSubtitle() {
        if (usingVlc) cycleVlcSubtitle(); else cycleMedia3Subtitle();
    }

    private void cycleMedia3Audio() {
        if (player == null) { showControls(); return; }
        List<TrackChoice> choices = trackChoices(C.TRACK_TYPE_AUDIO);
        if (choices.isEmpty()) {
            ToastBridge.show(this, "لا توجد مسارات صوت إضافية في هذا الملف");
            return;
        }
        int selected = selectedChoice(choices);
        TrackChoice choice = choices.get((selected + 1) % choices.size());
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .setOverrideForType(new TrackSelectionOverride(
                        choice.group.getMediaTrackGroup(), choice.trackIndex))
                .build());
        audioButton.setText("🔊  الصوت: " + trackLabel(choice.format, "مسار " + (choice.displayIndex + 1)));
        showControls();
    }

    private void cycleMedia3Subtitle() {
        if (player == null) { showControls(); return; }
        List<TrackChoice> choices = trackChoices(C.TRACK_TYPE_TEXT);
        if (choices.isEmpty()) {
            subtitleButton.setText("CC  الترجمة: غير متاحة");
            ToastBridge.show(this, "لا توجد ترجمة مضمّنة في هذا الملف");
            return;
        }
        int selected = selectedChoice(choices);
        if (selected == choices.size() - 1) {
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build());
            subtitleButton.setText("CC  الترجمة: إيقاف");
        } else {
            TrackChoice choice = choices.get(selected < 0 ? 0 : selected + 1);
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(new TrackSelectionOverride(
                            choice.group.getMediaTrackGroup(), choice.trackIndex))
                    .build());
            subtitleButton.setText("CC  الترجمة: " + trackLabel(choice.format,
                    "مسار " + (choice.displayIndex + 1)));
        }
        showControls();
    }

    private List<TrackChoice> trackChoices(@C.TrackType int trackType) {
        List<TrackChoice> result = new ArrayList<>();
        if (player == null) return result;
        int displayIndex = 0;
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() != trackType) continue;
            for (int index = 0; index < group.length; index++) {
                if (!group.isTrackSupported(index)) continue;
                result.add(new TrackChoice(
                        group, index, displayIndex++, group.getTrackFormat(index)));
            }
        }
        return result;
    }

    private int selectedChoice(List<TrackChoice> choices) {
        for (int index = 0; index < choices.size(); index++) {
            TrackChoice choice = choices.get(index);
            if (choice.group.isTrackSelected(choice.trackIndex)) return index;
        }
        return -1;
    }

    private String trackLabel(Format format, String fallback) {
        if (format == null) return fallback;
        String label = format.label == null ? "" : format.label.trim();
        String language = format.language == null ? "" : format.language.trim();
        String base = !label.isEmpty() ? label : (!language.isEmpty() ? language : fallback);
        if (format.channelCount > 0) base += " • " + format.channelCount + "ch";
        return base;
    }

    private void updateMedia3TrackButtons() {
        if (usingVlc || player == null || audioButton == null) return;
        List<TrackChoice> audio = trackChoices(C.TRACK_TYPE_AUDIO);
        int selectedAudio = selectedChoice(audio);
        setTrackButtonAvailable(audioButton, !audio.isEmpty());
        audioButton.setText(selectedAudio >= 0
                ? "🔊  الصوت: " + trackLabel(audio.get(selectedAudio).format, "تلقائي")
                : audio.isEmpty() ? "🔊  الصوت: غير متاح" : "🔊  الصوت: تلقائي");
        List<TrackChoice> text = trackChoices(C.TRACK_TYPE_TEXT);
        int selectedText = selectedChoice(text);
        setTrackButtonAvailable(subtitleButton, !text.isEmpty());
        subtitleButton.setText(selectedText >= 0
                ? "CC  الترجمة: " + trackLabel(text.get(selectedText).format, "مضمّنة")
                : text.isEmpty() ? "CC  الترجمة: غير متاحة" : "CC  الترجمة: إيقاف");
    }

    private void toggleStereo() {
        stereoMode = !stereoMode;
        stereoButton.setText(stereoMode ? "♫  المخرج: ستريو 2.0" : "♫  المخرج: تلقائي");
        getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE).edit()
                .putString(SettingsActivity.KEY_AUDIO_OUTPUT, stereoMode ? "stereo" : "auto")
                .apply();
        resumePosition = positionMs();
        if (usingVlc) {
            openVlc(stereoMode ? "تم تفعيل إخراج ستريو 2.0" : "تمت إعادة إخراج الصوت إلى تلقائي");
        } else if (player != null) {
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .setMaxAudioChannelCount(stereoMode ? 2 : Integer.MAX_VALUE)
                    .build());
            updateMedia3TrackButtons();
        }
        showControls();
    }

    private void cycleVlcAudio() {
        if (!usingVlc || vlcPlayer == null) { showControls(); return; }
        org.videolan.libvlc.MediaPlayer.TrackDescription[] tracks = vlcPlayer.getAudioTracks();
        if (tracks == null || tracks.length == 0) return;
        int current = indexOfTrack(tracks, vlcPlayer.getAudioTrack());
        int selected = -1;
        for (int step = 1; step <= tracks.length; step++) {
            int candidate = (current + step + tracks.length) % tracks.length;
            if (tracks[candidate].id >= 0 && vlcPlayer.setAudioTrack(tracks[candidate].id)) {
                selected = candidate;
                break;
            }
        }
        if (selected < 0) return;
        vlcAudioIndex = selected;
        audioButton.setText("🔊  الصوت: " + tracks[selected].name);
        showControls();
    }

    private void cycleVlcSubtitle() {
        if (!usingVlc || vlcPlayer == null) { showControls(); return; }
        org.videolan.libvlc.MediaPlayer.TrackDescription[] tracks = vlcPlayer.getSpuTracks();
        if (tracks == null || tracks.length == 0) return;
        int current = indexOfTrack(tracks, vlcPlayer.getSpuTrack());
        int selected = -1;
        for (int step = 1; step <= tracks.length; step++) {
            int candidate = (current + step + tracks.length) % tracks.length;
            if (vlcPlayer.setSpuTrack(tracks[candidate].id)) {
                selected = candidate;
                break;
            }
        }
        if (selected < 0) return;
        vlcSubtitleIndex = selected;
        subtitleButton.setText(tracks[selected].id < 0
                ? "CC  الترجمة: إيقاف" : "CC  الترجمة: " + tracks[selected].name);
        showControls();
    }

    private void updateVlcTrackButtons() {
        if (!usingVlc || vlcPlayer == null || audioButton == null) return;
        org.videolan.libvlc.MediaPlayer.TrackDescription[] audio = vlcPlayer.getAudioTracks();
        org.videolan.libvlc.MediaPlayer.TrackDescription[] text = vlcPlayer.getSpuTracks();
        applyVlcSubtitlePreference(text);
        vlcAudioIndex = indexOfTrack(audio, vlcPlayer.getAudioTrack());
        vlcSubtitleIndex = indexOfTrack(text, vlcPlayer.getSpuTrack());
        boolean audioAvailable = countSelectableTracks(audio) > 0;
        boolean subtitleAvailable = countSelectableTracks(text) > 0;
        setTrackButtonAvailable(audioButton, audioAvailable);
        setTrackButtonAvailable(subtitleButton, subtitleAvailable);
        audioButton.setText(vlcAudioIndex >= 0
                ? "🔊  الصوت: " + audio[vlcAudioIndex].name
                : audioAvailable ? "🔊  الصوت" : "🔊  الصوت: غير متاح");
        subtitleButton.setText(vlcSubtitleIndex >= 0 && text[vlcSubtitleIndex].id >= 0
                ? "CC  الترجمة: " + text[vlcSubtitleIndex].name
                : subtitleAvailable ? "CC  الترجمة: إيقاف"
                : "CC  الترجمة: غير متاحة");
        stereoButton.setText(stereoMode ? "♫  المخرج: ستريو 2.0" : "♫  المخرج: تلقائي");
    }

    private void applyVlcSubtitlePreference(
            org.videolan.libvlc.MediaPlayer.TrackDescription[] tracks) {
        if (vlcSubtitlePreferenceApplied || vlcPlayer == null) return;
        String preference = setting(SettingsActivity.KEY_SUBTITLE_LANGUAGE, "ar");
        if ("off".equals(preference)) {
            vlcPlayer.setSpuTrack(-1);
            vlcSubtitlePreferenceApplied = true;
            return;
        }
        if (!"ar".equals(preference)) {
            vlcSubtitlePreferenceApplied = true;
            return;
        }
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

    private static int countSelectableTracks(
            org.videolan.libvlc.MediaPlayer.TrackDescription[] tracks) {
        if (tracks == null) return 0;
        int count = 0;
        for (org.videolan.libvlc.MediaPlayer.TrackDescription track : tracks) {
            if (track != null && track.id >= 0) count++;
        }
        return count;
    }

    private void setTrackButtonAvailable(TextView button, boolean available) {
        if (button == null) return;
        button.setEnabled(available);
        button.setFocusable(available);
        button.setAlpha(available ? 1f : 0.58f);
        if (!available && button.hasFocus()) playerView.requestFocus();
    }

    private static int indexOfTrack(org.videolan.libvlc.MediaPlayer.TrackDescription[] tracks,
                                    int trackId) {
        if (tracks == null) return -1;
        for (int index = 0; index < tracks.length; index++) {
            if (tracks[index].id == trackId) return index;
        }
        return -1;
    }

    private void showControls() {
        controls.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideControls);
        main.postDelayed(hideControls, 5_000);
    }

    private boolean optionFocused() {
        View focused = getCurrentFocus();
        return focused == audioButton || focused == subtitleButton || focused == stereoButton;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            boolean longPress = event.getRepeatCount() >= 2;
            if (errorPanel != null && errorPanel.getVisibility() == View.VISIBLE
                    && (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER
                    || event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                if (event.getRepeatCount() == 0 && retryButton != null) {
                    retryButton.requestFocus();
                    retryButton.performClick();
                }
                return true;
            }
            if (optionFocused()) {
                switch (event.getKeyCode()) {
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER:
                        if (event.getRepeatCount() == 0 && getCurrentFocus() != null) {
                            getCurrentFocus().performClick();
                        }
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        playerView.requestFocus();
                        showControls();
                        return true;
                    case KeyEvent.KEYCODE_BACK:
                        playerView.requestFocus();
                        showControls();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        moveOptionFocus(false);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        moveOptionFocus(true);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_UP:
                        showControls();
                        return true;
                    default:
                        break;
                }
            }
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    seekBy(longPress ? -60_000 : -10_000);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    seekBy(longPress ? 60_000 : 10_000);
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_MENU:
                    showControls();
                    focusTrackControls();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    showControls();
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

    private void focusTrackControls() {
        if (audioButton != null && audioButton.isFocusable()) audioButton.requestFocus();
        else if (subtitleButton != null && subtitleButton.isFocusable()) subtitleButton.requestFocus();
        else if (stereoButton != null) stereoButton.requestFocus();
    }

    private void moveOptionFocus(boolean right) {
        TextView[] options = new TextView[]{audioButton, subtitleButton, stereoButton};
        View focused = getCurrentFocus();
        int current = 0;
        for (int index = 0; index < options.length; index++) {
            if (options[index] == focused) { current = index; break; }
        }
        int direction = right ? 1 : -1;
        for (int step = 1; step < options.length; step++) {
            int candidate = current + direction * step;
            if (candidate < 0 || candidate >= options.length) break;
            if (options[candidate] != null && options[candidate].isFocusable()) {
                options[candidate].requestFocus();
                return;
            }
        }
        if (options[current] != null) options[current].requestFocus();
    }

    private void persistPosition(long position) {
        PlaybackProgress.save(this, kind, id, position);
    }

    private void savePosition() {
        long p = positionMs();
        long d = durationMs();
        if (d > 0 && p * 10L >= d * 9L) p = 0;
        resumePosition = Math.max(0, p);
        persistPosition(resumePosition);
    }

    private void releaseMedia3() {
        if (player == null) return;
        playerView.setPlayer(null);
        player.removeListener(this);
        player.release();
        player = null;
    }

    private void releaseVlc() {
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

    private GradientDrawable playerControlsGradient() {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{
                Color.argb(0, 4, 4, 10),
                Color.argb(218, 8, 7, 16),
                Color.argb(252, 5, 5, 11)
        });
    }

    private GradientDrawable cinemaPanel(int fill, int radiusDp, int strokeDp, int stroke) {
        GradientDrawable panel = new GradientDrawable();
        panel.setColor(fill);
        panel.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) panel.setStroke(dp(strokeDp), stroke);
        return panel;
    }

    private StateListDrawable retryButtonBackground() {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed},
                cinemaPanel(Color.rgb(102, 31, 207), 14, 2, Color.WHITE));
        states.addState(new int[]{android.R.attr.state_focused},
                cinemaPanel(Color.rgb(126, 45, 238), 14, 3, Color.rgb(208, 177, 255)));
        states.addState(new int[]{},
                cinemaPanel(Color.rgb(91, 28, 184), 14, 1, Color.rgb(151, 90, 244)));
        return states;
    }

    private TextView transportBadge(String label) {
        TextView badge = BlofyUi.text(this, label, 11, Color.WHITE);
        badge.setGravity(Gravity.CENTER);
        badge.setTextDirection(View.TEXT_DIRECTION_LTR);
        badge.setFocusable(false);
        badge.setBackground(cinemaPanel(Color.argb(185, 20, 19, 29), 17, 1, Color.rgb(72, 68, 86)));
        return badge;
    }

    private TextView playerOptionButton(String label) {
        TextView button = BlofyUi.title(this, label, 12);
        button.setGravity(Gravity.CENTER);
        button.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        button.setSingleLine(true);
        button.setFocusable(true);
        button.setClickable(true);
        button.setBackground(BlofyUi.focusDrawable(this,
                Color.argb(205, 20, 18, 31), Color.rgb(73, 29, 132), BlofyUi.PURPLE_LIGHT));
        return button;
    }

    private static final class TrackChoice {
        final Tracks.Group group;
        final int trackIndex;
        final int displayIndex;
        final Format format;

        TrackChoice(Tracks.Group group, int trackIndex, int displayIndex, Format format) {
            this.group = group;
            this.trackIndex = trackIndex;
            this.displayIndex = displayIndex;
            this.format = format;
        }
    }

    private int dp(int value) { return BlofyUi.dp(this, value); }

    @Override protected void onStart() {
        super.onStart();
        lifecycleStopped = false;
        if (!hasActiveEngine() && validUrl(resolvedUrl) && errorPanel.getVisibility() != View.VISIBLE) {
            if (usingVlc || attempt >= 2) openVlc("استئناف المشاهدة");
            else openMedia3();
        } else if (!hasActiveEngine() && !validUrl(resolvedUrl) && !resolving
                && !id.isEmpty() && errorPanel.getVisibility() != View.VISIBLE) {
            resolve();
        }
    }

    @Override protected void onStop() {
        savePosition();
        lifecycleStopped = true;
        cancelResolve(true);
        releaseAllEngines();
        super.onStop();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        cancelResolve(true);
        main.removeCallbacksAndMessages(null);
        releaseAllEngines();
        network.shutdownNow();
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
