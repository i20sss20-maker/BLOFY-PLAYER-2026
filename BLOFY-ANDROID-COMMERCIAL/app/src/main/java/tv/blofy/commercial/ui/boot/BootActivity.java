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
            binding.progress.setProgressCompat(progress, true);
            binding.status.setText(status);
        });
    }

    private void boot() {
        worker.execute(() -> {
            ApiClient api = new ApiClient(this);
            try {
                stage(12, "فحص الاتصال الآمن…");
                api.get("/api/health");
                stage(28, "تعريف الجهاز…");
                JSONObject registration = new JSONObject();
                registration.put("deviceId", api.deviceId());
                registration.put("deviceKey", DeviceIdentity.secret(this));
                try {
                    JSONObject result = api.post("/api/device/register", registration);
                    DeviceIdentity.pairToken(this, result.optString("pairToken", ""));
                } catch (Exception ignored) {}

                stage(46, "التحقق من التفعيل…");
                JSONObject license = api.get("/api/license?device_id=" + ApiClient.encode(api.deviceId()));
                boolean licensed = LicenseGate.isLicensed(license);
                if (licensed) {
                    try { api.get("/api/device/bootstrap?device_id=" + ApiClient.encode(api.deviceId())); }
                    catch (Exception ignored) {}
                }

                stage(68, "استعادة الجلسة والباقة…");
                JSONObject session = api.get("/api/session").optJSONObject("session");
                if (!licensed || !LicenseGate.isPackageUsable(session)) {
                    runOnUiThread(() -> {
                        Intent activation = new Intent(this, ActivationActivity.class);
                        if (licensed && session != null) activation.putExtra("boot_error", "انتهى اشتراك الباقة أو توقف. جدّد البيانات ثم سجّل الدخول من جديد.");
                        startActivity(activation);
                        finish();
                    });
                    return;
                }

                stage(86, "فحص الكتالوج المحلي…");
                boolean hasCatalog = getSharedPreferences("blofy_commercial_state", MODE_PRIVATE)
                        .getBoolean("catalog_ready", false)
                        && getSharedPreferences("blofy_commercial_state", MODE_PRIVATE)
                        .getInt("catalog_schema", 0) >= 3;
                stage(100, "جاهز");
                open(hasCatalog ? HomeActivity.class : SyncActivity.class);
            } catch (Exception error) {
                runOnUiThread(() -> {
                    Intent intent = new Intent(this, ActivationActivity.class);
                    intent.putExtra("boot_error", error.getMessage());
                    startActivity(intent);
                    finish();
                });
            }
        });
    }

    private void open(Class<?> target) {
        runOnUiThread(() -> {
            startActivity(new Intent(this, target));
            finish();
        });
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
