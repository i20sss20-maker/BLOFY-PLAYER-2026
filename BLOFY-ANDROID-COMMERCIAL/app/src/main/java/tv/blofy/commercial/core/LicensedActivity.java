package tv.blofy.commercial.core;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Lifecycle;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Protected BLOFY screen gate.
 *
 * Railway is intentionally used for the BLOFY device license only. IPTV account/session
 * validation is not performed here, so opening Live/Movies/Series/Player can never bounce
 * back to Home because of a Railway provider-session check.
 */
public abstract class LicensedActivity extends AppCompatActivity {
    private final ExecutorService licenseWorker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean checkingLicense = new AtomicBoolean(false);
    private volatile boolean leavingForActivation;

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override protected void onResume() {
        super.onResume();
        verifyEntitlement();
    }

    protected final void verifyEntitlement() {
        if (leavingForActivation || isFinishing() || isDestroyed()
                || !checkingLicense.compareAndSet(false, true)) return;
        licenseWorker.execute(() -> {
            String reason = null;
            try {
                ApiClient api = new ApiClient(this);
                JSONObject license = api.get("/api/license?device_id=" + ApiClient.encode(api.deviceId()));
                if (!LicenseGate.isLicensed(license)) {
                    reason = "انتهى تفعيل الجهاز. جدّد التفعيل ثم أعد فتح التطبيق.";
                }
            } catch (Exception error) {
                if (LicenseGate.isAuthorizationError(error)) {
                    reason = "انتهت صلاحية تفعيل الجهاز. جدّد التفعيل ثم أعد فتح التطبيق.";
                }
                // Network/offline failures are intentionally ignored. Railway is not allowed
                // to block access to already-synced IPTV content because of a temporary outage.
            } finally {
                checkingLicense.set(false);
            }
            if (reason == null) return;
            String message = reason;
            runOnUiThread(() -> {
                if (leavingForActivation || isFinishing() || isDestroyed()
                        || !getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) return;
                leavingForActivation = true;
                LicenseGate.openActivation(this, message);
            });
        });
    }

    @Override protected void onDestroy() {
        licenseWorker.shutdownNow();
        super.onDestroy();
    }
}
