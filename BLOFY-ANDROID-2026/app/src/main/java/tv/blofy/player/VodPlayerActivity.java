package tv.blofy.player;

import android.app.Activity;
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
import java.util.List;
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
    private long resumePosition;
    private boolean useCronet;
    private boolean usingVlc;
    private boolean firstFrame;
    private boolean resolving;
    private boolean stoppedByLifecycle;
    private int attempt;
    private int vlcAudioIndex = -1;
    private int vlcSubtitleIndex = -1;
    private boolean stereoMode;

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
        if (controls != null && errorPanel.getVisibility() != View.VISIBLE && !optionFocused()) {
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
        root.setBackgroundColor(Color.rgb(3, 3, 8));

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
        LinearLayout.LayoutParams engineParams = new LinearLayout.LayoutParams(dp(220), dp(34));
        engineParams.leftMargin = dp(16);
        heading.addView(engineView, engineParams);
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

        TextView errorTitle = BlofyUi.title(this, "تعذر تشغيل المحتوى", 24);
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
        retryButton.setOnClickListener(v -> {
            attempt = 0;
            useCronet = false;
            usingVlc = false;
            releaseAllEngines();
            if (validUrl(resolvedUrl)) openMedia3(); else resolve();
        });
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
        if (stereoMode) {
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                    .setMaxAudioChannelCount(2)
                    .build());
        }

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
        if (stereoMode) options.add("--stereo-mode=1");
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
        if (stereoMode) media.addOption(":stereo-mode=1");
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
                updateVlcTrackButtons();
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
        if (retryButton != null) retryButton.requestFocus();
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
            int chosen = -1;
            for (int index = 0; index < group.length; index++) {
                if (!group.isTrackSupported(index)) continue;
                if (chosen < 0) chosen = index;
                if (group.isTrackSelected(index)) {
                    chosen = index;
                    break;
                }
            }
            if (chosen >= 0) result.add(new TrackChoice(
                    group, chosen, displayIndex++, group.getTrackFormat(chosen)));
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
        audioButton.setText(selectedAudio >= 0
                ? "🔊  الصوت: " + trackLabel(audio.get(selectedAudio).format, "تلقائي")
                : "🔊  الصوت: تلقائي");
        List<TrackChoice> text = trackChoices(C.TRACK_TYPE_TEXT);
        int selectedText = selectedChoice(text);
        subtitleButton.setText(selectedText >= 0
                ? "CC  الترجمة: " + trackLabel(text.get(selectedText).format, "مضمّنة")
                : "CC  الترجمة: إيقاف");
    }

    private void toggleStereo() {
        stereoMode = !stereoMode;
        stereoButton.setText(stereoMode ? "♫  المخرج: ستريو 2.0" : "♫  المخرج: تلقائي");
        resumePosition = positionMs();
        if (usingVlc) {
            openVlc(stereoMode ? "تم تفعيل إخراج ستريو 2.0" : "تمت إعادة إخراج الصوت إلى تلقائي");
        } else if (player != null) {
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
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
        int attempts = 0;
        do {
            vlcAudioIndex = (vlcAudioIndex + 1) % tracks.length;
            attempts++;
        } while (tracks[vlcAudioIndex].id < 0 && attempts < tracks.length);
        if (tracks[vlcAudioIndex].id < 0) return;
        vlcPlayer.setAudioTrack(tracks[vlcAudioIndex].id);
        engineView.setText("VLC • صوت: " + tracks[vlcAudioIndex].name);
        audioButton.setText("🔊  الصوت: " + tracks[vlcAudioIndex].name);
        showControls();
    }

    private void cycleVlcSubtitle() {
        if (!usingVlc || vlcPlayer == null) { showControls(); return; }
        org.videolan.libvlc.MediaPlayer.TrackDescription[] tracks = vlcPlayer.getSpuTracks();
        if (tracks == null || tracks.length == 0) return;
        vlcSubtitleIndex = (vlcSubtitleIndex + 1) % tracks.length;
        vlcPlayer.setSpuTrack(tracks[vlcSubtitleIndex].id);
        engineView.setText("VLC • ترجمة: " + tracks[vlcSubtitleIndex].name);
        subtitleButton.setText(tracks[vlcSubtitleIndex].id < 0
                ? "CC  الترجمة: إيقاف" : "CC  الترجمة: " + tracks[vlcSubtitleIndex].name);
        showControls();
    }

    private void updateVlcTrackButtons() {
        if (!usingVlc || vlcPlayer == null || audioButton == null) return;
        org.videolan.libvlc.MediaPlayer.TrackDescription[] audio = vlcPlayer.getAudioTracks();
        org.videolan.libvlc.MediaPlayer.TrackDescription[] text = vlcPlayer.getSpuTracks();
        audioButton.setText("🔊  الصوت" + (audio == null ? "" : " • " + audio.length + " مسار"));
        subtitleButton.setText("CC  الترجمة" + (text == null ? "" : " • " + text.length + " مسار"));
        stereoButton.setText(stereoMode ? "♫  المخرج: ستريو 2.0" : "♫  المخرج: تلقائي");
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
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                    case KeyEvent.KEYCODE_DPAD_UP:
                        return super.dispatchKeyEvent(event);
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
                    if (audioButton != null) audioButton.requestFocus();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    cycleSubtitle();
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
        PlaybackProgress.save(this, kind, id, position);
    }

    private void savePosition() {
        long p = positionMs();
        long d = durationMs();
        if (d > 0 && p > d - 30_000) p = 0;
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
        BlofyUi.attachScaleFocus(button, 1.025f);
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
