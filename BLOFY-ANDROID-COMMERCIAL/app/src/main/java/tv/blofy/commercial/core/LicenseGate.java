package tv.blofy.commercial.core;

import android.app.Activity;
import android.content.Intent;

import org.json.JSONObject;

import tv.blofy.commercial.ui.activation.ActivationActivity;

/** Central license/session gate used by every native screen. */
public final class LicenseGate {
    private LicenseGate() {}

    public static boolean isLicensed(JSONObject license) {
        String plan = license == null ? "" : license.optString("plan");
        long expiresAt = license == null ? 0L : license.optLong("expiresAt", 0L);
        return ("trial".equals(plan) || "active".equals(plan))
                && (expiresAt <= 0L || expiresAt > System.currentTimeMillis());
    }

    public static boolean isPackageUsable(JSONObject session) {
        if (session == null) return false;
        JSONObject account = session.optJSONObject("account");
        if (account == null) return true;
        String status = account.optString("status", "Active");
        long expiresAt = account.optLong("expiresAt", 0L);
        return !"expired".equalsIgnoreCase(status)
                && !"disabled".equalsIgnoreCase(status)
                && !"banned".equalsIgnoreCase(status)
                && (expiresAt <= 0L || expiresAt > System.currentTimeMillis());
    }

    public static boolean isAuthorizationError(Throwable error) {
        return error instanceof ApiClient.ApiException
                && (((ApiClient.ApiException) error).status == 401
                || ((ApiClient.ApiException) error).status == 402);
    }

    public static void openActivation(Activity activity, String message) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        Intent intent = new Intent(activity, ActivationActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                // An expired entitlement must never be able to auto-skip the
                // renewal/login form because an old provider cookie remains.
                .putExtra("force_form", true);
        if (message != null && !message.trim().isEmpty()) intent.putExtra("boot_error", message);
        activity.startActivity(intent);
        activity.finish();
    }
}
