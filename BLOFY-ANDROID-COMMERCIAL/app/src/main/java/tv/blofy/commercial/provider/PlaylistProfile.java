package tv.blofy.commercial.provider;

import org.json.JSONObject;

import java.util.UUID;

/** One saved IPTV playlist/account. Credentials never belong to the media backend. */
public final class PlaylistProfile {
    public final String id;
    public final ProviderProfile provider;
    public final long createdAt;
    public final long updatedAt;

    public PlaylistProfile(String id, ProviderProfile provider, long createdAt, long updatedAt) {
        this.id = id == null || id.trim().isEmpty() ? UUID.randomUUID().toString() : id.trim();
        this.provider = provider;
        this.createdAt = createdAt <= 0 ? System.currentTimeMillis() : createdAt;
        this.updatedAt = updatedAt <= 0 ? System.currentTimeMillis() : updatedAt;
    }

    public static PlaylistProfile create(ProviderProfile provider) {
        long now = System.currentTimeMillis();
        return new PlaylistProfile(UUID.randomUUID().toString(), provider, now, now);
    }

    public JSONObject toJson() {
        JSONObject out = new JSONObject();
        try {
            out.put("id", id);
            out.put("createdAt", createdAt);
            out.put("updatedAt", updatedAt);
            JSONObject p = new JSONObject();
            p.put("kind", provider == null ? "" : provider.kind);
            p.put("name", provider == null ? "" : provider.name);
            p.put("serverUrl", provider == null ? "" : provider.serverUrl);
            p.put("username", provider == null ? "" : provider.username);
            p.put("password", provider == null ? "" : provider.password);
            p.put("playlistUrl", provider == null ? "" : provider.playlistUrl);
            out.put("provider", p);
        } catch (Exception ignored) { }
        return out;
    }

    public static PlaylistProfile fromJson(JSONObject value) {
        if (value == null) return null;
        ProviderProfile provider = ProviderProfile.fromJson(value.optJSONObject("provider"));
        if (provider == null) return null;
        return new PlaylistProfile(value.optString("id"), provider,
                value.optLong("createdAt"), value.optLong("updatedAt"));
    }
}
