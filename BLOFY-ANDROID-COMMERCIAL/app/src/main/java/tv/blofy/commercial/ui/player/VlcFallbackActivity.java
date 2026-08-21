package tv.blofy.commercial.ui.player;

import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;

import java.util.ArrayList;

import tv.blofy.commercial.databinding.ActivityVlcFallbackBinding;

/** LibVLC lives in a separate Activity so native VLC code is loaded only when Media3 really needs fallback. */
public final class VlcFallbackActivity extends AppCompatActivity {
    private static final String USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20";
    private ActivityVlcFallbackBinding binding;
    private LibVLC libVlc;
    private MediaPlayer player;
    private String url;
    private boolean live;

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        binding = ActivityVlcFallbackBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        url = getIntent().getStringExtra("url");
        live = getIntent().getBooleanExtra("live", false);
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            finish();
            return;
        }
        startVlc();
    }

    private void startVlc() {
        try {
            ArrayList<String> options = new ArrayList<>();
            options.add("--no-drop-late-frames");
            options.add("--no-skip-frames");
            libVlc = new LibVLC(getApplicationContext(), options);
            player = new MediaPlayer(libVlc);
            player.attachViews(binding.vlcPlayer, null, true, false);
            player.setEventListener(event -> {
                if (event == null) return;
                runOnUiThread(() -> {
                    if (binding == null) return;
                    if (event.type == MediaPlayer.Event.Playing) {
                        binding.status.setVisibility(View.GONE);
                    } else if (event.type == MediaPlayer.Event.EncounteredError) {
                        binding.status.setText("تعذر تشغيل المصدر بالمحرك الاحتياطي.");
                        binding.status.setVisibility(View.VISIBLE);
                    } else if (event.type == MediaPlayer.Event.EndReached && !live) {
                        finish();
                    }
                });
            });
            Media media = new Media(libVlc, Uri.parse(url));
            media.setHWDecoderEnabled(true, false);
            media.addOption(":http-user-agent=" + USER_AGENT);
            media.addOption(":http-reconnect");
            media.addOption(":network-caching=" + (live ? "1000" : "2500"));
            if (live) media.addOption(":live-caching=1000");
            player.setMedia(media);
            media.release();
            player.play();
        } catch (Throwable error) {
            if (binding != null) {
                binding.status.setText("تعذر تحميل LibVLC على هذا الجهاز.");
                binding.status.setVisibility(View.VISIBLE);
            }
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK
                    || event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE
                    || event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_B) {
                finish();
                return true;
            }
            if (event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && player != null) {
                if (player.isPlaying()) player.pause(); else player.play();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override protected void onDestroy() {
        if (player != null) {
            try { player.stop(); } catch (Throwable ignored) { }
            try { player.detachViews(); } catch (Throwable ignored) { }
            try { player.release(); } catch (Throwable ignored) { }
            player = null;
        }
        if (libVlc != null) {
            try { libVlc.release(); } catch (Throwable ignored) { }
            libVlc = null;
        }
        binding = null;
        super.onDestroy();
    }
}
