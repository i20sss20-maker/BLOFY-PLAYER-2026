package tv.blofy.player;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
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

import java.util.HashMap;
import java.util.Map;

@UnstableApi
public final class PlayerActivity extends Activity implements Player.Listener {
    public static final String EXTRA_URL = "url";
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
    private String url;
    private String title;
    private String kind;
    private String extension;
    private long resumePosition;
    private int connectionAttempt;
    private final Handler playbackHandler = new Handler(Looper.getMainLooper());
    private final Runnable playbackTimeout = () -> {
        if (player == null || player.getPlaybackState() == Player.STATE_READY) return;
        player.stop();
        progress.setVisibility(View.GONE);
        errorPanel.setVisibility(View.VISIBLE);
        errorText.setText("لم تصل بيانات فيديو قابلة للتشغيل خلال المهلة. أعد الاتصال مباشرة بالمصدر أو جرّب قناة أخرى. لا يمر الفيديو عبر Railway.");
        retryButton.setText("إعادة الاتصال مباشرة");
        retryButton.requestFocus();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        url = getIntent().getStringExtra(EXTRA_URL);
        title = valueOr(getIntent().getStringExtra(EXTRA_TITLE), "BLOFY PLAYER");
        kind = valueOr(getIntent().getStringExtra(EXTRA_KIND), "movies");
        extension = PlaybackPolicy.normalizeExtension(
                getIntent().getStringExtra(EXTRA_EXTENSION),
                "live".equals(kind) ? "ts" : "mp4");
        if (!validUrl(url)) {
            finish();
            return;
        }
        resumePosition = isLive() ? 0 : getSharedPreferences("blofy_positions", MODE_PRIVATE).getLong(positionKey(), 0);
        buildUi();
        hideSystemUi();
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static boolean validUrl(String value) {
        if (value == null) return false;
        Uri uri = Uri.parse(value);
        return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null;
    }

    private boolean isLive() {
        return "live".equals(kind);
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
        root.addView(playerView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(17);
        titleView.setTypeface(titleView.getTypeface(), android.graphics.Typeface.BOLD);
        titleView.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        titleView.setPadding(dp(24), dp(12), dp(24), dp(12));
        titleView.setBackgroundColor(Color.argb(175, 8, 7, 14));
        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58), Gravity.TOP);
        root.addView(titleView, titleParams);

        progress = new ProgressBar(this);
        progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Color.rgb(154, 88, 255)));
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER);
        root.addView(progress, progressParams);

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
        errorPanel.addView(errorText, new LinearLayout.LayoutParams(dp(520), ViewGroup.LayoutParams.WRAP_CONTENT));

        retryButton = new Button(this);
        retryButton.setText("إعادة الاتصال مباشرة");
        retryButton.setTextColor(Color.WHITE);
        retryButton.setTextSize(15);
        retryButton.setAllCaps(false);
        retryButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(124, 50, 255)));
        retryButton.setOnClickListener(view -> retryPlayback());
        errorPanel.addView(retryButton, new LinearLayout.LayoutParams(dp(240), dp(54)));

        FrameLayout.LayoutParams errorParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(errorPanel, errorParams);
        setContentView(root);
    }

    private DefaultLoadControl createLoadControl() {
        if (isLive()) {
            return new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(4_000, 18_000, 700, 1_400)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build();
        }
        return new DefaultLoadControl.Builder()
                .setBufferDurationsMs(10_000, 45_000, 900, 1_800)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();
    }

    private void schedulePlaybackTimeout() {
        playbackHandler.removeCallbacks(playbackTimeout);
        playbackHandler.postDelayed(playbackTimeout, PlaybackPolicy.startupTimeoutMs(connectionAttempt));
    }

    private void initializePlayer() {
        if (player != null) return;
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "VLC/3.0.20 LibVLC/3.0.20 BLOFY-Media3/1.11");
        headers.put("Accept", "*/*");

        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(30_000)
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(headers);
        DefaultDataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(this, httpFactory);
        int tsFlags = DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
                | DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES;
        DefaultExtractorsFactory extractorsFactory = new DefaultExtractorsFactory()
                .setTsExtractorFlags(tsFlags);
        DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory);
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this)
                .setEnableDecoderFallback(true);

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
        player.setMediaSource(mediaSource, Math.max(0, resumePosition));
        player.prepare();
        player.play();
        progress.setVisibility(View.VISIBLE);
        errorPanel.setVisibility(View.GONE);
        playerView.requestFocus();
        schedulePlaybackTimeout();
    }

    private void retryPlayback() {
        connectionAttempt += 1;
        releasePlayer();
        titleView.setVisibility(View.VISIBLE);
        initializePlayer();
    }

    @Override
    public void onPlaybackStateChanged(int playbackState) {
        if (playbackState == Player.STATE_BUFFERING) progress.setVisibility(View.VISIBLE);
        else if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) progress.setVisibility(View.GONE);
        if (playbackState == Player.STATE_READY) {
            playbackHandler.removeCallbacks(playbackTimeout);
            connectionAttempt = 0;
            titleView.postDelayed(() -> titleView.setVisibility(View.GONE), 2500);
        }
    }

    @Override
    public void onPlayerError(PlaybackException error) {
        playbackHandler.removeCallbacks(playbackTimeout);
        progress.setVisibility(View.GONE);
        errorPanel.setVisibility(View.VISIBLE);
        errorText.setText("تعذر الاتصال المباشر بالمصدر أو أن ترميز القناة غير مدعوم على هذا الجهاز. أعد الاتصال أو جرّب قناة أخرى.\n" + error.getErrorCodeName());
        retryButton.setText("إعادة الاتصال مباشرة");
        retryButton.requestFocus();
    }

    private String positionKey() {
        return "position_" + Integer.toHexString(url.hashCode());
    }

    private void savePosition() {
        if (player == null || isLive()) return;
        long position = player.getCurrentPosition();
        long duration = player.getDuration();
        if (duration > 0 && position > duration - 30_000) position = 0;
        getSharedPreferences("blofy_positions", MODE_PRIVATE).edit().putLong(positionKey(), Math.max(0, position)).apply();
        resumePosition = Math.max(0, position);
    }

    private void releasePlayer() {
        playbackHandler.removeCallbacks(playbackTimeout);
        if (player == null) return;
        savePosition();
        playerView.setPlayer(null);
        player.removeListener(this);
        player.release();
        player = null;
    }

    @Override
    protected void onStart() {
        super.onStart();
        initializePlayer();
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
                    finish();
                    return true;
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
                default: break;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    private void hideSystemUi() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                    View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }
}
