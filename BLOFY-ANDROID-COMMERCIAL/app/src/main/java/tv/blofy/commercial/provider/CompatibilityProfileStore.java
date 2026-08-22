package tv.blofy.commercial.provider;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/** Small per-playlist compatibility cache. Catalogue/media data remains in Room. */
public final class CompatibilityProfileStore {
    private static final String PREFS = "blofy_provider_compatibility";

    private CompatibilityProfileStore() { }

    public static void save(Context context, CompatibilityProfile profile) {
        if (profile == null || profile.playlistId.isEmpty()) return;
        prefs(context).edit().putString(profile.playlistId, profile.toJson().toString()).apply();
    }

    public static CompatibilityProfile load(Context context, String playlistId) {
        if (playlistId == null || playlistId.trim().isEmpty()) return null;
        String raw = prefs(context).getString(playlistId.trim(), "");
        if (raw == null || raw.isEmpty()) return null;
        try { return CompatibilityProfile.fromJson(new JSONObject(raw)); }
        catch (Exception ignored) { return null; }
    }

    public static void remove(Context context, String playlistId) {
        if (playlistId == null || playlistId.trim().isEmpty()) return;
        prefs(context).edit().remove(playlistId.trim()).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
