package tv.blofy.commercial.provider;

import android.content.Context;
import android.content.SharedPreferences;

/** Per-playlist catalogue readiness and sync metadata. */
public final class PlaylistStateStore {
    private static final String PREFS = "blofy_playlist_state";
    private PlaylistStateStore() { }

    public static boolean isReady(Context context, String playlistId) {
        String key = key(playlistId);
        SharedPreferences prefs = prefs(context);
        return prefs.getBoolean(key + ".ready", false)
                && prefs.getInt(key + ".schema", 0) >= 5;
    }

    public static void markSyncing(Context context, String playlistId) {
        prefs(context).edit().putBoolean(key(playlistId) + ".ready", false).apply();
    }

    public static void markReady(Context context, String playlistId) {
        String key = key(playlistId);
        prefs(context).edit()
                .putBoolean(key + ".ready", true)
                .putInt(key + ".schema", 5)
                .putLong(key + ".last_sync", System.currentTimeMillis())
                .apply();
    }

    public static long lastSync(Context context, String playlistId) {
        return prefs(context).getLong(key(playlistId) + ".last_sync", 0L);
    }

    public static void clear(Context context, String playlistId) {
        String key = key(playlistId);
        prefs(context).edit()
                .remove(key + ".ready")
                .remove(key + ".schema")
                .remove(key + ".last_sync")
                .apply();
    }

    private static String key(String value) {
        String raw = value == null ? "legacy" : value.trim();
        return raw.isEmpty() ? "legacy" : raw;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
