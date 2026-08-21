package tv.blofy.commercial.ui.player;

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
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
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import tv.blofy.commercial.BuildConfig;
import tv.blofy.commercial.core.ApiClient;
import tv.blofy.commercial.data.CatalogStore;
import tv.blofy.commercial.data.MediaRecord;
import tv.blofy.commercial.databinding.ActivityPlayerBinding;

@OptIn(markerClass = UnstableApi.class)
public final class PlayerActivity extends AppCompatActivity implements Player.Listener {
    private ActivityPlayerBinding binding;
    private ApiClient api;
    private CatalogStore store;
    private ExoPlayer player;
    private String id, name, type, extension, playbackUrl;
    private List<MediaRecord> liveChannels;
    private int liveIndex = -1;
    private int attempts;
    private boolean alternateTried;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeout = () -> {
        if (player != null && player.getPlaybackState() != Player.STATE_READY) {
            if (tryAlternateLive()) return;
            releasePlayer(); showError("لم تصل بيانات فيديو قابلة للتشغيل خلال المهلة. جرّب إعادة الاتصال أو قناة أخرى.");
        }
    };

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        binding = ActivityPlayerBinding.inflate(getLayoutInflater()); setContentView(binding.getRoot());
        api = new ApiClient(this); store = new CatalogStore(this);
        id = extra("id", ""); name = extra("name", "BLOFY PLAYER"); type = extra("type", "live"); extension = normalize(extra("extension", isLive() ? "ts" : "mp4"));
        binding.title.setText(name); binding.close.setOnClickListener(v -> finish()); binding.retry.setOnClickListener(v -> retry());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { finish(); }
        });
        if (isLive()) {
            liveChannels = store.media("live", "", "", 20_000);
            for (int i = 0; i < liveChannels.size(); i++) if (id.equals(liveChannels.get(i).id)) liveIndex = i;
        } else binding.epg.setVisibility(View.GONE);
        hideSystemUi();
        resolve();
        if (isLive()) loadEpg();
    }

    private boolean isLive() { return "live".equals(type); }
    private String extra(String key, String fallback) { String value = getIntent().getStringExtra(key); return value == null || value.isEmpty() ? fallback : value; }

    private void resolve() {
        binding.progress.setVisibility(View.VISIBLE); binding.errorPanel.setVisibility(View.GONE);
        worker.execute(() -> {
            try {
                String apiType = "series".equals(type) || "episode".equals(type) ? "episode" : type;
                JSONObject data = api.get("/api/native-link/" + ApiClient.encode(apiType) + "/" + ApiClient.encode(id) + "?ext=" + ApiClient.encode(extension));
                String path = data.optString("url");
                if (!path.startsWith("/api/native-play")) throw new Exception("تعذر إصدار رابط Media3 آمن.");
                playbackUrl = BuildConfig.BLOFY_BASE_URL.replaceAll("/+$", "") + path;
                extension = normalize(data.optString("extension", extension));
                runOnUiThread(this::preparePlayer);
            } catch (Exception error) { runOnUiThread(() -> showError(error.getMessage())); }
        });
    }

    private void preparePlayer() {
        releasePlayer();
        Map<String,String> headers = new HashMap<>(); headers.put("User-Agent", "VLC/3.0.20 BLOFY-Media3/1.11"); headers.put("Accept", "*/*");
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory().setConnectTimeoutMs(12_000).setReadTimeoutMs(30_000).setAllowCrossProtocolRedirects(true).setDefaultRequestProperties(headers);
        DefaultDataSource.Factory source = new DefaultDataSource.Factory(this, http);
        int flags = DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS | DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES;
        DefaultExtractorsFactory extractors = new DefaultExtractorsFactory().setTsExtractorFlags(flags);
        DefaultMediaSourceFactory mediaFactory = new DefaultMediaSourceFactory(source, extractors);
        boolean stableBuffer = "stable".equals(getSharedPreferences("blofy_player_settings", MODE_PRIVATE).getString("buffer", "fast"));
        int minimum = stableBuffer ? (isLive() ? 6_000 : 14_000) : (isLive() ? 2_500 : 8_000);
        int maximum = stableBuffer ? (isLive() ? 28_000 : 60_000) : (isLive() ? 14_000 : 40_000);
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder().setBufferDurationsMs(minimum, maximum, 600, 1_200).setPrioritizeTimeOverSizeThresholds(true).build();
        player = new ExoPlayer.Builder(this, new DefaultRenderersFactory(this).setEnableDecoderFallback(true)).setMediaSourceFactory(mediaFactory).setLoadControl(loadControl).build();
        player.addListener(this); player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true); player.setWakeMode(C.WAKE_MODE_NETWORK); binding.player.setPlayer(player);
        String quality = getSharedPreferences("blofy_player_settings", MODE_PRIVATE).getString("quality", "auto");
        TrackSelectionParameters.Builder tracks = player.getTrackSelectionParameters().buildUpon();
        if ("sd".equals(quality)) tracks.setMaxVideoSize(854, 480); else if ("hd".equals(quality)) tracks.setMaxVideoSize(1920, 1080);
        if (getSharedPreferences("blofy_player_settings", MODE_PRIVATE).getBoolean("subtitles", true)) tracks.setPreferredTextLanguage("ar").setSelectUndeterminedTextLanguage(true);
        player.setTrackSelectionParameters(tracks.build());
        MediaItem.Builder item = new MediaItem.Builder().setUri(playbackUrl).setMediaId(id);
        String mime = mime(extension); if (mime != null) item.setMimeType(mime);
        MediaSource media = "m3u8".equals(extension) ? new HlsMediaSource.Factory(source).setExtractorFactory(new DefaultHlsExtractorFactory(flags, true)).createMediaSource(item.build()) : mediaFactory.createMediaSource(item.build());
        long position = isLive() ? 0 : getSharedPreferences("blofy_positions", MODE_PRIVATE).getLong(positionKey(), 0);
        player.setMediaSource(media, Math.max(0, position)); player.prepare();
        if (getSharedPreferences("blofy_player_settings", MODE_PRIVATE).getBoolean("autoplay", true)) player.play();
        binding.player.requestFocus();
        handler.removeCallbacks(timeout); handler.postDelayed(timeout, Math.min(28_000, 10_000 + attempts * 4_000L));
    }

    private void loadEpg() {
        final String requested = id;
        worker.execute(() -> {
            try {
                JSONArray values = api.get("/api/epg/" + ApiClient.encode(requested)).optJSONArray("entries");
                JSONObject selected = null; long now = System.currentTimeMillis();
                if (values != null) for (int i=0;i<values.length();i++) { JSONObject row=values.optJSONObject(i); if(row==null)continue; if(selected==null)selected=row; if(row.optLong("start")<=now && now<row.optLong("end",Long.MAX_VALUE)){selected=row;break;} }
                JSONObject finalSelected = selected;
                runOnUiThread(() -> { if (!requested.equals(id)) return; if (finalSelected == null) binding.epg.setText("لا توجد بيانات برنامج حاليًا"); else binding.epg.setText("الآن: " + finalSelected.optString("title") + " • " + time(finalSelected.optLong("start")) + " – " + time(finalSelected.optLong("end"))); });
            } catch (Exception ignored) { runOnUiThread(() -> { if (requested.equals(id)) binding.epg.setText("البث المباشر"); }); }
        });
    }

    private void switchChannel(int direction) {
        if (!isLive() || liveChannels == null || liveChannels.size() < 2) return;
        if (liveIndex < 0) liveIndex = 0; liveIndex = (liveIndex + direction + liveChannels.size()) % liveChannels.size();
        MediaRecord item = liveChannels.get(liveIndex); id=item.id; name=item.name; extension=normalize(item.extension.isEmpty()?"ts":item.extension); playbackUrl=null; attempts=0; alternateTried=false;
        binding.title.setText(name + " • " + (liveIndex+1) + "/" + liveChannels.size());
        binding.epg.setText("جلب دليل البرنامج…");
        resolve();
        loadEpg();
    }

    private void retry() { attempts++; alternateTried=false; resolve(); }
    private boolean tryAlternateLive() {
        if (!isLive() || alternateTried) return false;
        alternateTried = true;
        extension = "m3u8".equals(extension) ? "ts" : "m3u8";
        releasePlayer();
        resolve();
        return true;
    }
    private void showError(String message) { binding.progress.setVisibility(View.GONE); binding.errorPanel.setVisibility(View.VISIBLE); binding.error.setText(message == null ? "حدث خطأ غير متوقع." : message); binding.retry.requestFocus(); }

    @Override public void onPlaybackStateChanged(int state) {
        binding.progress.setVisibility(state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
        if (state == Player.STATE_READY) { handler.removeCallbacks(timeout); attempts = 0; binding.topBar.postDelayed(() -> binding.topBar.setVisibility(View.GONE), 3500); binding.hint.postDelayed(() -> binding.hint.setVisibility(View.GONE), 6500); }
    }
    @Override public void onPlayerError(PlaybackException error) {
        handler.removeCallbacks(timeout);
        if (tryAlternateLive()) return;
        showError("المصدر لم يستجب أو الترميز غير مدعوم على هذا الجهاز.\n" + error.getErrorCodeName());
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_CHANNEL_UP: case KeyEvent.KEYCODE_DPAD_UP: if(isLive()){switchChannel(1);return true;} break;
            case KeyEvent.KEYCODE_CHANNEL_DOWN: case KeyEvent.KEYCODE_DPAD_DOWN: if(isLive()){switchChannel(-1);return true;} break;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: if(player!=null){if(player.isPlaying())player.pause();else player.play();} return true;
        }
        binding.topBar.setVisibility(View.VISIBLE); return super.dispatchKeyEvent(event);
    }

    private String positionKey(){return "p_"+Integer.toHexString((type+":"+id).hashCode());}
    private void releasePlayer(){handler.removeCallbacks(timeout);if(player==null)return;if(!isLive()){long p=player.getCurrentPosition(),d=player.getDuration();if(d>0&&p>d-30000)p=0;getSharedPreferences("blofy_positions",MODE_PRIVATE).edit().putLong(positionKey(),Math.max(0,p)).apply();}binding.player.setPlayer(null);player.removeListener(this);player.release();player=null;}
    private static String normalize(String ext){String value=ext==null?"":ext.toLowerCase(Locale.US).replaceAll("[^a-z0-9]","");return value.isEmpty()?"mp4":value;}
    private static String mime(String ext){
        if("m3u8".equals(ext))return MimeTypes.APPLICATION_M3U8;
        if("mpd".equals(ext))return MimeTypes.APPLICATION_MPD;
        if("ts".equals(ext)||"mts".equals(ext)||"m2ts".equals(ext))return MimeTypes.VIDEO_MP2T;
        if("mp4".equals(ext)||"m4v".equals(ext))return MimeTypes.VIDEO_MP4;
        if("mkv".equals(ext))return MimeTypes.VIDEO_MATROSKA;
        if("webm".equals(ext))return MimeTypes.VIDEO_WEBM;
        return null;
    }
    private static String time(long value){return value<=0?"":DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(value));}

    @Override protected void onStop(){releasePlayer();super.onStop();}
    @Override protected void onStart(){super.onStart();if(playbackUrl!=null&&player==null)preparePlayer();}
    @Override protected void onDestroy(){worker.shutdownNow();if(store!=null)store.close();super.onDestroy();}
    private void hideSystemUi(){if(android.os.Build.VERSION.SDK_INT>=30){WindowInsetsController c=getWindow().getInsetsController();if(c!=null){c.hide(WindowInsets.Type.statusBars()|WindowInsets.Type.navigationBars());c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);}}else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);}
    @Override public void onWindowFocusChanged(boolean hasFocus){super.onWindowFocusChanged(hasFocus);if(hasFocus)hideSystemUi();}
}
