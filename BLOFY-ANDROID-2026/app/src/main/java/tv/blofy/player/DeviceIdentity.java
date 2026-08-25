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
    private static final String KEY_PUBLIC_REGISTERED = "public_identity_registered";

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
        if (!hasRegisteredPublicIdentity(context)) return "";
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_DISPLAY_ID, "");
    }

    /** Candidate sent during registration; it is never shown until the server confirms it. */
    static String proposedDisplayId(Context context) {
        String saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DISPLAY_ID, "");
        if (saved != null && saved.matches("BLOFY-[A-Z0-9]{4}-[A-Z0-9]{4}")) return saved;
        String code = stableCode(context, "display", 8);
        return "BLOFY-" + code.substring(0, 4) + "-" + code.substring(4, 8);
    }

    /** Six-digit pairing code. The server-issued value wins after registration. */
    static String activationCode(Context context) {
        if (!hasRegisteredPublicIdentity(context)) return "";
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PAIRING_CODE, "");
    }

    /** Candidate sent during registration; it is never shown until the server confirms it. */
    static String proposedActivationCode(Context context) {
        String saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PAIRING_CODE, "");
        if (saved != null && saved.matches("[0-9]{6}")) return saved;
        return stableDigits(context, "activation", 6);
    }

    /** Atomically accepts only a complete server registration response. */
    static boolean updatePublicIdentity(Context context, JSONObject response) {
        if (response == null) return false;
        String displayId = response.optString("displayId", "").trim().toUpperCase(Locale.US);
        String pairingCode = response.optString("pairingCode", "").trim();
        if (!displayId.matches("BLOFY-[A-Z0-9]{4}-[A-Z0-9]{4}")
                || !pairingCode.matches("[0-9]{6}")) return false;
        android.content.SharedPreferences.Editor edit = context.getSharedPreferences(PREFS,
                Context.MODE_PRIVATE).edit();
        edit.putString(KEY_DISPLAY_ID, displayId);
        edit.putString(KEY_PAIRING_CODE, pairingCode);
        edit.putBoolean(KEY_PUBLIC_REGISTERED, true);
        edit.apply();
        return true;
    }

    static boolean hasRegisteredPublicIdentity(Context context) {
        android.content.SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!preferences.getBoolean(KEY_PUBLIC_REGISTERED, false)) return false;
        String displayId = preferences.getString(KEY_DISPLAY_ID, "");
        String pairingCode = preferences.getString(KEY_PAIRING_CODE, "");
        return displayId != null && displayId.matches("BLOFY-[A-Z0-9]{4}-[A-Z0-9]{4}")
                && pairingCode != null && pairingCode.matches("[0-9]{6}");
    }

    private static String stableCode(Context context, String purpose, int length) {
        final char[] alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
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
            return length == 8 ? "00000000" : "BLOFY000";
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
