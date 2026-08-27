package tv.blofy.player;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/** Remembers the last proven route for one playlist + content item. */
final class PlaybackRouteMemory {
    private static final String PREFS = "blofy_playback_routes_v1";
    private static final String PREFIX_TRANSPORT = "transport:";
    private static final String PREFIX_LIVE_EXT = "live_ext:";

    private PlaybackRouteMemory() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String source(Context context) {
        String value = CatalogScope.active(context);
        return value == null || value.trim().isEmpty() ? "legacy" : value.trim();
    }

    private static String itemKey(Context context, String kind, String id) {
        return source(context) + ":" + clean(kind) + ":" + clean(id);
    }

    static String preferredMode(Context context, String kind, String id, String fallback) {
        if (id == null || id.isEmpty()) return fallback;
        String value = prefs(context).getString(PREFIX_TRANSPORT + itemKey(context, kind, id), "");
        return value == null || value.isEmpty() ? fallback : value;
    }

    static int preferredRecoveryStep(Context context, String kind, String id) {
        String transport = preferredMode(context, kind, id, "");
        return "cronet".equals(transport) ? 1 : 0;
    }

    static String preferredLiveExtension(Context context, String id, String fallback) {
        if (id == null || id.isEmpty()) return fallback;
        String value = prefs(context).getString(PREFIX_LIVE_EXT + itemKey(context, "live", id), "");
        return value == null || value.isEmpty() ? fallback : value;
    }

    static void recordSuccess(Context context, String kind, String id,
                              String extension, String transport) {
        if (id == null || id.isEmpty()) return;
        SharedPreferences.Editor editor = prefs(context).edit();
        String key = itemKey(context, kind, id);
        if (transport != null && !transport.trim().isEmpty()) {
            editor.putString(PREFIX_TRANSPORT + key, transport.trim());
        }
        if ("live".equals(clean(kind))) {
            String ext = normalizeLiveExtension(extension);
            if (!ext.isEmpty()) editor.putString(PREFIX_LIVE_EXT + key, ext);
        }
        editor.apply();
    }

    static void forgetItem(Context context, String kind, String id) {
        if (id == null || id.isEmpty()) return;
        String key = itemKey(context, kind, id);
        prefs(context).edit()
                .remove(PREFIX_TRANSPORT + key)
                .remove(PREFIX_LIVE_EXT + key)
                .apply();
    }

    static void clearSource(Context context) {
        String sourcePrefix = source(context) + ":";
        SharedPreferences preferences = prefs(context);
        SharedPreferences.Editor editor = preferences.edit();
        for (String key : preferences.getAll().keySet()) {
            if ((key.startsWith(PREFIX_TRANSPORT) || key.startsWith(PREFIX_LIVE_EXT))
                    && key.contains(sourcePrefix)) {
                editor.remove(key);
            }
        }
        editor.apply();
    }

    private static String normalizeLiveExtension(String extension) {
        String ext = clean(extension).replace(".", "");
        if (ext.contains("m3u8") || ext.contains("hls")) return "m3u8";
        if (ext.equals("ts") || ext.contains("mpegts")) return "ts";
        return "";
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.US);
    }
}
