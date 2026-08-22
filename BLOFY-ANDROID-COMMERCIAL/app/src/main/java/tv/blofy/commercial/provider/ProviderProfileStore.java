package tv.blofy.commercial.provider;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/** Device-local IPTV profile store. Provider data is used locally after secure pairing handoff. */
public final class ProviderProfileStore {
    private static final String PREFS = "blofy_provider_local";

    private ProviderProfileStore() { }

    public static void save(Context context, ProviderProfile profile) {
        if (profile == null || !profile.isValid()) return;
        SharedPreferences.Editor edit = prefs(context).edit();
        edit.putString("kind", profile.kind);
        edit.putString("name", profile.name);
        edit.putString("server_url", profile.serverUrl);
        edit.putString("username", profile.username);
        edit.putString("password", profile.password);
        edit.putString("playlist_url", profile.playlistUrl);
        edit.apply();
    }

    public static boolean importFromBootstrap(Context context, JSONObject bootstrap) {
        ProviderProfile profile = ProviderProfile.fromJson(
                bootstrap == null ? null : bootstrap.optJSONObject("profile"));
        if (profile == null) return false;
        save(context, profile);
        return true;
    }

    public static ProviderProfile load(Context context) {
        SharedPreferences p = prefs(context);
        ProviderProfile profile = new ProviderProfile(
                p.getString("kind", ""),
                p.getString("name", ""),
                p.getString("server_url", ""),
                p.getString("username", ""),
                p.getString("password", ""),
                p.getString("playlist_url", ""));
        return profile.isValid() ? profile : null;
    }

    public static boolean hasProfile(Context context) { return load(context) != null; }

    public static void clear(Context context) { prefs(context).edit().clear().apply(); }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
