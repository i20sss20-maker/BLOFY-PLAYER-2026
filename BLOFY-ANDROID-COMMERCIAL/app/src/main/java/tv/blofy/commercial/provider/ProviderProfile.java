package tv.blofy.commercial.provider;

import org.json.JSONObject;

/** Local IPTV account configuration used by the native player after pairing. */
public final class ProviderProfile {
    public final String kind;
    public final String name;
    public final String serverUrl;
    public final String username;
    public final String password;
    public final String playlistUrl;

    public ProviderProfile(String kind, String name, String serverUrl,
                           String username, String password, String playlistUrl) {
        this.kind = safe(kind).toLowerCase();
        this.name = safe(name);
        this.serverUrl = trimServer(serverUrl);
        this.username = safe(username);
        this.password = safe(password);
        this.playlistUrl = safe(playlistUrl);
    }

    public static ProviderProfile fromJson(JSONObject value) {
        if (value == null) return null;
        ProviderProfile profile = new ProviderProfile(
                value.optString("kind"),
                value.optString("name"),
                value.optString("serverUrl"),
                value.optString("username"),
                value.optString("password"),
                value.optString("url", value.optString("playlistUrl")));
        return profile.isValid() ? profile : null;
    }

    public boolean isXtream() { return "xtream".equals(kind); }
    public boolean isM3u() { return "m3u".equals(kind); }

    public boolean isValid() {
        if (isXtream()) {
            return (serverUrl.startsWith("http://") || serverUrl.startsWith("https://"))
                    && !username.isEmpty() && !password.isEmpty();
        }
        return isM3u() && (playlistUrl.startsWith("http://") || playlistUrl.startsWith("https://"));
    }

    private static String trimServer(String value) {
        String text = safe(value).trim();
        while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
        return text;
    }

    private static String safe(String value) { return value == null ? "" : value.trim(); }
}
