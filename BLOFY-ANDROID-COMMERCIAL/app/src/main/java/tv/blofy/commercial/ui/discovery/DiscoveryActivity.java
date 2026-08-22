package tv.blofy.commercial.ui.discovery;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import tv.blofy.commercial.databinding.ActivityDiscoveryBinding;
import tv.blofy.commercial.provider.CompatibilityProfile;
import tv.blofy.commercial.provider.CompatibilityProfileStore;
import tv.blofy.commercial.provider.PlaylistProfile;
import tv.blofy.commercial.provider.PlaylistRepository;
import tv.blofy.commercial.provider.ServerDiscoveryEngine;
import tv.blofy.commercial.ui.activation.ActivationActivity;
import tv.blofy.commercial.ui.sync.SyncActivity;

/** Clean 0-100 discovery screen. User-facing diagnostics are intentionally hidden here. */
public final class DiscoveryActivity extends AppCompatActivity {
    private ActivityDiscoveryBinding binding;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean destroyed = new AtomicBoolean(false);
    private int shownProgress;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityDiscoveryBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        worker.execute(this::discover);
    }

    private void discover() {
        PlaylistProfile selected = PlaylistRepository.active(this);
        if (selected == null) selected = PlaylistRepository.importLegacySingleProfile(this);
        if (selected == null) {
            openActivation();
            return;
        }
        final PlaylistProfile playlist = selected;

        try {
            ServerDiscoveryEngine engine = new ServerDiscoveryEngine();
            CompatibilityProfile profile = engine.discover(playlist, this::showProgress);
            CompatibilityProfileStore.save(this, profile);
            showProgress(100);
            if (destroyed.get()) return;
            runOnUiThread(() -> {
                if (destroyed.get() || isFinishing() || isDestroyed()) return;
                startActivity(new Intent(this, SyncActivity.class)
                        .putExtra("playlist_id", playlist.id)
                        .putExtra("discovery_ready", true));
                finish();
            });
        } catch (Exception error) {
            if (destroyed.get()) return;
            runOnUiThread(() -> {
                if (destroyed.get() || isFinishing() || isDestroyed()) return;
                startActivity(new Intent(this, ActivationActivity.class)
                        .putExtra("force_form", true)
                        .putExtra("boot_error", "تعذر تجهيز قائمة التشغيل. تحقق من بيانات الباقة أو اتصال المزود."));
                finish();
            });
        }
    }

    private void showProgress(int percent) {
        int safe = Math.max(0, Math.min(100, percent));
        if (safe < shownProgress || destroyed.get()) return;
        shownProgress = safe;
        runOnUiThread(() -> {
            if (binding == null || destroyed.get()) return;
            binding.progress.setProgressCompat(safe, true);
            binding.percent.setText(safe + "%");
        });
    }

    private void openActivation() {
        runOnUiThread(() -> {
            if (destroyed.get() || isFinishing() || isDestroyed()) return;
            startActivity(new Intent(this, ActivationActivity.class).putExtra("force_form", true));
            finish();
        });
    }

    @Override protected void onDestroy() {
        destroyed.set(true);
        worker.shutdownNow();
        binding = null;
        super.onDestroy();
    }
}
