package tv.blofy.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Persists bounded compatibility hints per provider/host. */
final class ServerPlaybackProfile {
    private static final String PREFS = "blofy_server_playback_profiles";
    private static final long STALE_MS = 30L * 24L * 60L * 60L * 1000L;

    static final class Profile {
        final String preferredLiveExtension;
        final String preferredRoute;
        final String preferredEngine;
        final String userAgent;
        final String referer;
        final long updatedAt;

        Profile(String liveExtension, String route, String engine, String userAgent,
                String referer, long updatedAt) {
            this.preferredLiveExtension = liveExtension;
            this.preferredRoute = route;
            this.preferredEngine = engine;
            this.userAgent = userAgent;
            this.referer = referer;
            this.updatedAt = updatedAt;
        }

        boolean fresh() { return System.currentTimeMillis() - updatedAt < STALE_MS; }
    }

    private ServerPlaybackProfile() {}

    static Profile load(Context context, String url) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String key = key(url);
        return new Profile(
                prefs.getString(key + ".live_ext", ""),
                prefs.getString(key + ".route", ""),
                prefs.getString(key + ".engine", ""),
                prefs.getString(key + ".ua", ""),
                prefs.getString(key + ".referer", ""),
                prefs.getLong(key + ".updated", 0L));
    }

    static void rememberSuccess(Context context, String url, String extension, String route,
                                String engine, String userAgent, String referer) {
        String key = key(url);
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        if (extension != null && !extension.isEmpty()) editor.putString(key + ".live_ext", extension);
        if (route != null && !route.isEmpty()) editor.putString(key + ".route", route);
        if (engine != null && !engine.isEmpty()) editor.putString(key + ".engine", engine);
        if (userAgent != null && !userAgent.isEmpty()) editor.putString(key + ".ua", userAgent);
        if (referer != null && !referer.isEmpty()) editor.putString(key + ".referer", referer);
        editor.putLong(key + ".updated", System.currentTimeMillis()).apply();
    }

    static void forget(Context context, String url) {
        String key = key(url);
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        for (String suffix : new String[]{".live_ext", ".route", ".engine", ".ua", ".referer", ".updated"}) {
            editor.remove(key + suffix);
        }
        editor.apply();
    }

    private static String key(String url) {
        String host = "unknown";
        try {
            Uri uri = Uri.parse(url == null ? "" : url);
            if (uri.getHost() != null) host = uri.getHost().toLowerCase(Locale.US);
        } catch (Exception ignored) {}
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(host.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder("h_");
            for (int i = 0; i < 8 && i < digest.length; i++) out.append(String.format(Locale.US, "%02x", digest[i]));
            return out.toString();
        } catch (Exception ignored) { return "h_" + Math.abs(host.hashCode()); }
    }
}
