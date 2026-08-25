package tv.blofy.player;

import android.content.Context;
import android.provider.Settings;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

final class DeviceIdentity {
    private static final String PREFS = "blofy_native_identity";
    private static final String KEY_SECRET = "device_secret";
    private static final String KEY_PAIR_TOKEN = "pair_token";
    private static final String KEY_DISPLAY_ID = "display_id";
    private static final String KEY_PAIRING_CODE = "pairing_code";

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

    /** Short, TV-friendly identifier. The long id remains the private API identity. */
    static String displayId(Context context) {
        String saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_DISPLAY_ID, "");
        if (saved != null && saved.matches("BLOFY-[A-Z0-9]{2}")) return saved;
        return "BLOFY-" + stableCode(context, "display", 2);
    }

    /** Six-digit pairing code. The server-issued value wins after registration. */
    static String activationCode(Context context) {
        String saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PAIRING_CODE, "");
        if (saved != null && saved.matches("[0-9]{6}")) return saved;
        return stableDigits(context, "activation", 6);
    }

    static void updatePublicIdentity(Context context, JSONObject response) {
        if (response == null) return;
        String displayId = response.optString("displayId", "").trim().toUpperCase(Locale.US);
        String pairingCode = response.optString("pairingCode", "").trim();
        android.content.SharedPreferences.Editor edit = context.getSharedPreferences(PREFS,
                Context.MODE_PRIVATE).edit();
        if (displayId.matches("BLOFY-[A-Z0-9]{2}")) edit.putString(KEY_DISPLAY_ID, displayId);
        if (pairingCode.matches("[0-9]{6}")) edit.putString(KEY_PAIRING_CODE, pairingCode);
        edit.apply();
    }

    private static String stableCode(Context context, String purpose, int length) {
        final char[] alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
        try {
            String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(("tv.blofy.player:" + purpose + ":" + androidId)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder(length);
            for (int index = 0; index < length; index++) {
                value.append(alphabet[(hash[index] & 0xff) % alphabet.length]);
            }
            return value.toString();
        } catch (Exception ignored) {
            return length == 2 ? "TV" : "BLOFY6";
        }
    }

    private static String stableDigits(Context context, String purpose, int length) {
        try {
            String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(("tv.blofy.player:" + purpose + ":" + androidId)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder(length);
            for (int index = 0; index < length; index++) value.append((hash[index] & 0xff) % 10);
            return value.toString();
        } catch (Exception ignored) {
            return "000000";
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
