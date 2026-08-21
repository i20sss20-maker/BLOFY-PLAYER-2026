package tv.blofy.commercial.core;

import android.content.Context;
import android.provider.Settings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

public final class DeviceIdentity {
    // Keep the native v2026 identity so upgrades retain activation and pairing.
    private static final String PREFS = "blofy_native_identity";
    private static final String KEY_SECRET = "device_secret";
    private static final String KEY_PAIR_TOKEN = "pair_token";
    private DeviceIdentity() {}

    public static String id(Context context) {
        try {
            String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(("tv.blofy.player:" + androidId).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int index = 0; index < 8; index++) hex.append(String.format(Locale.US, "%02X", hash[index]));
            String value = hex.toString();
            return "BLOFY-" + value.substring(0, 4) + "-" + value.substring(4, 8)
                    + "-" + value.substring(8, 12) + "-" + value.substring(12, 16);
        } catch (Exception error) {
            return "BLOFY-TV-0000-0000";
        }
    }

    public static String secret(Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String saved = prefs.getString(KEY_SECRET, "");
        if (saved != null && saved.matches("[A-F0-9]{64}")) return saved;
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder value = new StringBuilder();
        for (byte part : bytes) value.append(String.format(Locale.US, "%02X", part));
        prefs.edit().putString(KEY_SECRET, value.toString()).apply();
        return value.toString();
    }

    public static void pairToken(Context context, String value) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PAIR_TOKEN, value == null ? "" : value).apply();
    }

    public static String pairToken(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PAIR_TOKEN, "");
    }
}
