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
 * Revalidates both the BLOFY device license and the provider subscription when
 * a protected screen returns to the foreground. Network outages do not sign a
 * user out; only an explicit inactive/expired response does.
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
                    reason = "انتهى تفعيل الجهاز. جدّد التفعيل ثم سجّل الدخول من هذه الشاشة.";
                } else {
                    // Revalidate the provider account (server-side cached for
                    // one minute) so an expired package returns to the renewal
                    // form instead of failing only after the user presses Play.
                    JSONObject session = api.get("/api/session?refresh=1").optJSONObject("session");
                    if (!LicenseGate.isPackageUsable(session)) {
                        reason = "انتهى اشتراك الباقة أو توقف. أدخل بيانات الاشتراك المجددة ثم سجّل الدخول.";
                    }
                }
            } catch (Exception error) {
                if (LicenseGate.isAuthorizationError(error)) {
                    reason = "انتهت صلاحية الجلسة. جدّد التفعيل أو بيانات الباقة ثم سجّل الدخول.";
                }
                // A timeout/offline error is intentionally ignored here so a
                // temporary network interruption never traps the user.
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
