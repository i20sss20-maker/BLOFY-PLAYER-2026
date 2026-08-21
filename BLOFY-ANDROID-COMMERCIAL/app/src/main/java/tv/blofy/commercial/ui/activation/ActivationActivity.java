package tv.blofy.commercial.ui.activation;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Toast;

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
        binding.activate.setOnClickListener(view -> activate());
        binding.savePackage.setOnClickListener(view -> savePackage());
        refreshLicense();
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
                boolean configured = licensed && restoreRemoteSession();
                runOnUiThread(() -> {
                    binding.licenseStatus.setText(licensed ? "مفعّل • متبقي " + days + " أيام" : "الجهاز غير مفعّل");
                    binding.licenseStatus.setTextColor(getColor(licensed ? R.color.blofy_success : R.color.blofy_error));
                    if (configured && !forceForm && !isFinishing()) {
                        navigating = true;
                        main.removeCallbacks(poll);
                        boolean ready = getSharedPreferences("blofy_commercial_state", MODE_PRIVATE)
                                .getBoolean("catalog_ready", false)
                                && getSharedPreferences("blofy_commercial_state", MODE_PRIVATE)
                                .getInt("catalog_schema", 0) >= 3;
                        startActivity(new Intent(this, ready ? HomeActivity.class : SyncActivity.class));
                        finish();
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

    /** Refreshes the QR token whenever this screen is entered, so a token left
     * on a TV for more than 24 hours never leads to a dead mobile form. */
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

    private boolean restoreRemoteSession() {
        try {
            api.get("/api/device/bootstrap?device_id=" + ApiClient.encode(api.deviceId()));
            JSONObject session = api.get("/api/session").optJSONObject("session");
            if (!LicenseGate.isPackageUsable(session)) {
                showStatus("انتهى اشتراك الباقة. أدخل بيانات الباقة المجددة ثم احفظها.", true);
                return false;
            }
            return session != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void activate() {
        String code = text(binding.activationCode);
        binding.activate.setEnabled(false);
        showStatus("جاري التحقق من رمز التفعيل…", false);
        worker.execute(() -> {
            try {
                if (!code.isEmpty()) {
                    JSONObject body = new JSONObject();
                    body.put("deviceId", api.deviceId());
                    body.put("code", code);
                    api.post("/api/activate", body);
                }
                JSONObject data = api.get("/api/license?device_id=" + ApiClient.encode(api.deviceId()));
                licensed = LicenseGate.isLicensed(data);
                if (!licensed) throw new Exception("رمز التفعيل غير صحيح أو انتهت صلاحيته.");
                runOnUiThread(() -> {
                    binding.activate.setEnabled(true);
                    binding.licenseStatus.setText("تم تفعيل الجهاز");
                    binding.licenseStatus.setTextColor(getColor(R.color.blofy_success));
                    showStatus("تم التفعيل. أضف بيانات الباقة الآن.", false);
                    binding.savePackage.requestFocus();
                });
            } catch (Exception error) {
                runOnUiThread(() -> binding.activate.setEnabled(true));
                showStatus(error.getMessage(), true);
            }
        });
    }

    private void savePackage() {
        if (!licensed) {
            showStatus("فعّل الجهاز أولًا أو امسح الباركود لتفعيله من الجوال.", true);
            return;
        }
        boolean xtream = binding.packageType.getCheckedButtonId() == R.id.xtream;
        JSONObject body = new JSONObject();
        try {
            body.put("kind", xtream ? "xtream" : "m3u");
            body.put("name", text(binding.packageName));
            if (xtream) {
                body.put("serverUrl", text(binding.serverUrl));
                body.put("username", text(binding.username));
                body.put("password", text(binding.password));
            } else {
                body.put("url", text(binding.playlistUrl));
            }
        } catch (Exception ignored) {}
        binding.savePackage.setEnabled(false);
        showStatus("فحص الخادم وبيانات الباقة…", false);
        worker.execute(() -> {
            try {
                api.post("/api/session", body);
                JSONObject session = api.get("/api/session").optJSONObject("session");
                if (session == null) throw new Exception("لم يتم حفظ جلسة الباقة.");
                runOnUiThread(() -> {
                    navigating = true;
                    main.removeCallbacks(poll);
                    startActivity(new Intent(this, SyncActivity.class));
                    finish();
                });
            } catch (Exception error) {
                runOnUiThread(() -> binding.savePackage.setEnabled(true));
                showStatus(error.getMessage(), true);
            }
        });
    }

    private void showStatus(String message, boolean error) {
        runOnUiThread(() -> {
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
        super.onDestroy();
    }
}
