package tv.blofy.commercial.ui.activation;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import tv.blofy.commercial.R;
import tv.blofy.commercial.core.ApiClient;
import tv.blofy.commercial.core.DeviceIdentity;
import tv.blofy.commercial.core.LicenseGate;
import tv.blofy.commercial.databinding.ActivityActivationBinding;
import tv.blofy.commercial.provider.ProviderProfile;
import tv.blofy.commercial.provider.ProviderProfileStore;
import tv.blofy.commercial.provider.XtreamClient;
import tv.blofy.commercial.ui.home.HomeActivity;
import tv.blofy.commercial.ui.sync.SyncActivity;

public final class ActivationActivity extends AppCompatActivity {
    private ActivityActivationBinding binding;
    private ApiClient api;
    private boolean licensed;
    private volatile boolean refreshing;
    private volatile boolean navigating;
    private volatile boolean pairingReady;
    private boolean forceForm;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable poll = this::refreshLicense;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityActivationBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        api = new ApiClient(this);
        forceForm = getIntent().getBooleanExtra("force_form", false);
        binding.deviceId.setText(api.deviceId());
        binding.qr.setImageBitmap(qr(activationUrl(), 380));
        String error = getIntent().getStringExtra("boot_error");
        if (error != null && !error.isEmpty()) binding.formStatus.setText(error);

        binding.packageType.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            boolean xtream = checkedId == R.id.xtream;
            binding.serverUrl.setVisibility(xtream ? View.VISIBLE : View.GONE);
            binding.username.setVisibility(xtream ? View.VISIBLE : View.GONE);
            binding.password.setVisibility(xtream ? View.VISIBLE : View.GONE);
            binding.playlistUrl.setVisibility(xtream ? View.GONE : View.VISIBLE);
        });
        binding.savePackage.setOnClickListener(view -> savePackage());
        restoreLocalProfile();
        refreshLicense();
    }

    private void restoreLocalProfile() {
        ProviderProfile profile = ProviderProfileStore.load(this);
        if (profile == null) return;
        binding.packageName.setText(profile.name);
        if (profile.isXtream()) {
            binding.packageType.check(R.id.xtream);
            binding.serverUrl.setText(profile.serverUrl);
            binding.username.setText(profile.username);
            binding.password.setText(profile.password);
        } else {
            binding.packageType.check(R.id.m3u);
            binding.playlistUrl.setText(profile.playlistUrl);
        }
    }

    private String activationUrl() {
        String value = api.baseUrl() + "/activate?device_id=" + ApiClient.encode(api.deviceId());
        String token = DeviceIdentity.pairToken(this);
        return token == null || token.isEmpty() ? value : value + "&pair_token=" + ApiClient.encode(token);
    }

    private void refreshLicense() {
        if (refreshing || navigating || isFinishing() || isDestroyed()) return;
        refreshing = true;
        worker.execute(() -> {
            try {
                ensureFreshPairing();
                JSONObject data = api.get("/api/license?device_id=" + ApiClient.encode(api.deviceId()));
                licensed = LicenseGate.isLicensed(data);
                int days = data.optInt("remainingDays", 0);
                boolean imported = licensed && pullPairedProfile();
                boolean configured = licensed && ProviderProfileStore.hasProfile(this);
                runOnUiThread(() -> {
                    if (binding == null) return;
                    binding.licenseStatus.setText(licensed ? "مفعّل • متبقي " + days + " أيام" : "بانتظار التجديد");
                    binding.licenseStatus.setTextColor(getColor(licensed ? R.color.blofy_success : R.color.blofy_error));
                    if (imported) restoreLocalProfile();
                    if (configured && !forceForm && !isFinishing()) {
                        navigating = true;
                        main.removeCallbacks(poll);
                        boolean ready = getSharedPreferences("blofy_commercial_state", MODE_PRIVATE)
                                .getBoolean("catalog_ready", false)
                                && getSharedPreferences("blofy_commercial_state", MODE_PRIVATE)
                                .getInt("catalog_schema", 0) >= 4;
                        startActivity(new Intent(this, ready ? HomeActivity.class : SyncActivity.class));
                        finish();
                    } else if (licensed && !configured) {
                        showStatus("امسح الباركود وأرسل بيانات الباقة من الموقع، أو أدخلها هنا مباشرة.", false);
                    }
                });
            } catch (Exception error) {
                showStatus(error.getMessage(), true);
            } finally {
                refreshing = false;
                main.removeCallbacks(poll);
                if (!navigating && !isFinishing() && !isDestroyed()) main.postDelayed(poll, 5_000);
            }
        });
    }

    private boolean pullPairedProfile() {
        try {
            JSONObject bootstrap = api.get("/api/device/bootstrap?device_id=" + ApiClient.encode(api.deviceId()));
            boolean imported = ProviderProfileStore.importFromBootstrap(this, bootstrap);
            if (imported) {
                getSharedPreferences("blofy_commercial_state", MODE_PRIVATE).edit()
                        .putBoolean("catalog_ready", false).apply();
            }
            return imported;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void ensureFreshPairing() {
        if (pairingReady) return;
        try {
            JSONObject body = new JSONObject();
            body.put("deviceId", api.deviceId());
            body.put("deviceKey", DeviceIdentity.secret(this));
            JSONObject result = api.post("/api/device/register", body);
            String token = result.optString("pairToken", "");
            if (token.isEmpty()) return;
            DeviceIdentity.pairToken(this, token);
            pairingReady = true;
            Bitmap image = qr(activationUrl(), 380);
            runOnUiThread(() -> {
                if (binding != null && image != null) binding.qr.setImageBitmap(image);
            });
        } catch (Exception error) {
            if (DeviceIdentity.pairToken(this) == null || DeviceIdentity.pairToken(this).isEmpty()) {
                showStatus("تعذر تحديث باركود الربط. تحقق من الاتصال ثم أعد المحاولة.", true);
            }
        }
    }

    private void savePackage() {
        if (!licensed) {
            showStatus("الجهاز منتهي. جدّد الاشتراك أولًا ثم أضف بيانات الباقة.", true);
            return;
        }
        boolean xtream = binding.packageType.getCheckedButtonId() == R.id.xtream;
        ProviderProfile profile = new ProviderProfile(
                xtream ? "xtream" : "m3u",
                text(binding.packageName),
                xtream ? text(binding.serverUrl) : "",
                xtream ? text(binding.username) : "",
                xtream ? text(binding.password) : "",
                xtream ? "" : text(binding.playlistUrl));
        if (!profile.isValid()) {
            showStatus(xtream ? "أدخل رابط السيرفر واسم المستخدم وكلمة المرور." : "أدخل رابط M3U صحيح.", true);
            return;
        }

        binding.savePackage.setEnabled(false);
        showStatus(xtream ? "فحص Xtream مباشرة من الجهاز…" : "حفظ قائمة M3U محليًا…", false);
        worker.execute(() -> {
            try {
                if (profile.isXtream()) new XtreamClient(profile).validate();
                ProviderProfileStore.save(this, profile);
                getSharedPreferences("blofy_commercial_state", MODE_PRIVATE).edit()
                        .putBoolean("catalog_ready", false).apply();
                runOnUiThread(() -> {
                    navigating = true;
                    main.removeCallbacks(poll);
                    startActivity(new Intent(this, SyncActivity.class));
                    finish();
                });
            } catch (Exception error) {
                runOnUiThread(() -> { if (binding != null) binding.savePackage.setEnabled(true); });
                showStatus(error.getMessage(), true);
            }
        });
    }

    private void showStatus(String message, boolean error) {
        runOnUiThread(() -> {
            if (binding == null) return;
            binding.formStatus.setText(message == null ? "حدث خطأ غير متوقع." : message);
            binding.formStatus.setTextColor(getColor(error ? R.color.blofy_error : R.color.blofy_muted));
        });
    }

    private static String text(android.widget.EditText input) {
        return input.getText() == null ? "" : input.getText().toString().trim();
    }

    private static Bitmap qr(String value, int size) {
        try {
            BitMatrix bits = new MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, size, size);
            int[] pixels = new int[size * size];
            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) pixels[y * size + x] = bits.get(x, y) ? Color.rgb(18, 0, 51) : Color.WHITE;
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
            bitmap.setPixels(pixels, 0, size, 0, 0, size, size);
            return bitmap;
        } catch (Exception error) {
            return null;
        }
    }

    @Override protected void onDestroy() {
        main.removeCallbacks(poll);
        worker.shutdownNow();
        binding = null;
        super.onDestroy();
    }
}
