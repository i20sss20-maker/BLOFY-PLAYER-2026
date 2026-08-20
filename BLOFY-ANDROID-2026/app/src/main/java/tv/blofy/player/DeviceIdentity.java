package tv.blofy.player;

import android.content.Context;
import android.provider.Settings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

final class DeviceIdentity {
    private static final String PREFS = "blofy_native_identity";
    private static final String KEY_SECRET = "device_secret";
    private static final String KEY_PAIR_TOKEN = "pair_token";

    private DeviceIdentity() {}

    static String id(Context context) {
        try {
            String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(("tv.blofy.player:" + androidId).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int index = 0; index < 8; index++) hex.append(String.format(Locale.US, "%02X", hash[index]));
            String value = hex.toString();
            return "BLOFY-" + value.substring(0, 4) + "-" + value.substring(4, 8)
                    + "-" + value.substring(8, 12) + "-" + value.substring(12, 16);
        } catch (Exception ignored) {
            return "BLOFY-ANDROID-DEVICE";
        }
    }

    static String secret(Context context) {
        android.content.SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String saved = preferences.getString(KEY_SECRET, "");
        if (saved != null && saved.matches("[A-F0-9]{64}")) return saved;
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        StringBuilder value = new StringBuilder(64);
        for (byte part : random) value.append(String.format(Locale.US, "%02X", part));
        String created = value.toString();
        preferences.edit().putString(KEY_SECRET, created).apply();
        return created;
    }

    static void pairToken(Context context, String value) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PAIR_TOKEN, value == null ? "" : value).apply();
    }

    static String pairToken(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PAIR_TOKEN, "");
    }
}
