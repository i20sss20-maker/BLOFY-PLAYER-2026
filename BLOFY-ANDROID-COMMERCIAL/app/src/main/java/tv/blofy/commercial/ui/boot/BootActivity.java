package tv.blofy.commercial.ui.boot;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import tv.blofy.commercial.core.ApiClient;
import tv.blofy.commercial.core.DeviceIdentity;
import tv.blofy.commercial.core.LicenseGate;
import tv.blofy.commercial.databinding.ActivityBootBinding;
import tv.blofy.commercial.provider.ProviderProfileStore;
import tv.blofy.commercial.ui.activation.ActivationActivity;
import tv.blofy.commercial.ui.home.HomeActivity;
import tv.blofy.commercial.ui.sync.SyncActivity;

public final class BootActivity extends AppCompatActivity {
    private ActivityBootBinding binding;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityBootBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        boot();
    }

    private void stage(int progress, String status) {
        runOnUiThread(() -> {
            if (binding == null) return;
            binding.progress.setProgressCompat(progress, true);
            binding.status.setText(status);
        });
    }

    private void boot() {
        worker.execute(() -> {
            ApiClient api = new ApiClient(this);
            boolean hasCatalog = hasLocalCatalog();
            try {
                stage(14, "فحص خدمة التفعيل…");
                api.get("/api/health");

                stage(30, "تعريف الجهاز…");
                JSONObject registration = new JSONObject();
                registration.put("deviceId", api.deviceId());
                registration.put("deviceKey", DeviceIdentity.secret(this));
                try {
                    JSONObject result = api.post("/api/device/register", registration);
                    DeviceIdentity.pairToken(this, result.optString("pairToken", ""));
                } catch (Exception ignored) { }

                stage(50, "التحقق من التفعيل…");
                JSONObject license = api.get("/api/license?device_id=" + ApiClient.encode(api.deviceId()));
                if (!LicenseGate.isLicensed(license)) {
                    openActivation("انتهت مدة التفعيل. جدّد الاشتراك ثم افتح التطبيق من جديد.");
                    return;
                }

                stage(68, "استلام بيانات الباقة المرتبطة…");
                try {
                    JSONObject bootstrap = api.get("/api/device/bootstrap?device_id=" + ApiClient.encode(api.deviceId()));
                    if (ProviderProfileStore.importFromBootstrap(this, bootstrap)) {
                        getSharedPreferences("blofy_commercial_state", MODE_PRIVATE).edit()
                                .putBoolean("catalog_ready", false).apply();
                        hasCatalog = false;
                    }
                } catch (Exception ignored) {
                    // Keep an existing local profile during temporary activation-service outages.
                }

                stage(80, "فحص بيانات الباقة…");
                if (!ProviderProfileStore.hasProfile(this)) {
                    openActivation("امسح الباركود وأرسل بيانات Xtream أو M3U من صفحة الربط.");
                    return;
                }

                stage(94, "فحص الكتالوج المحلي…");
                stage(100, "جاهز");
                open(hasCatalog ? HomeActivity.class : SyncActivity.class);
            } catch (Exception error) {
                if (hasCatalog && ProviderProfileStore.hasProfile(this)) {
                    stage(100, "خدمة التفعيل غير متاحة مؤقتًا — فتح المكتبة المحلية");
                    open(HomeActivity.class);
                    return;
                }
                openActivation(error.getMessage());
            }
        });
    }

    private boolean hasLocalCatalog() {
        return getSharedPreferences("blofy_commercial_state", MODE_PRIVATE)
                .getBoolean("catalog_ready", false)
                && getSharedPreferences("blofy_commercial_state", MODE_PRIVATE)
                .getInt("catalog_schema", 0) >= 4;
    }

    private void openActivation(String message) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            Intent intent = new Intent(this, ActivationActivity.class).putExtra("force_form", true);
            if (message != null && !message.isEmpty()) intent.putExtra("boot_error", message);
            startActivity(intent);
            finish();
        });
    }

    private void open(Class<?> target) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            startActivity(new Intent(this, target));
            finish();
        });
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        binding = null;
        super.onDestroy();
    }
}
