package tv.blofy.commercial.provider;

import org.json.JSONObject;

/** Persisted result of the bounded provider discovery pipeline for one playlist. */
public final class CompatibilityProfile {
    public final String playlistId;
    public final String apiMode;
    public final String userAgent;
    public final String origin;
    public final String referer;
    public final String finalHost;
    public final String liveProtocol;
    public final String vodProtocol;
    public final boolean epgSupported;
    public final String preferredEngine;
    public final String fallbackEngine;
    public final int lastHttpStatus;
    public final long discoveredAt;

    public CompatibilityProfile(String playlistId, String apiMode, String userAgent,
                                String origin, String referer, String finalHost,
                                String liveProtocol, String vodProtocol,
                                boolean epgSupported, String preferredEngine,
                                String fallbackEngine, int lastHttpStatus,
                                long discoveredAt) {
        this.playlistId = safe(playlistId);
        this.apiMode = safe(apiMode);
        this.userAgent = safe(userAgent);
        this.origin = safe(origin);
        this.referer = safe(referer);
        this.finalHost = safe(finalHost);
        this.liveProtocol = safe(liveProtocol);
        this.vodProtocol = safe(vodProtocol);
        this.epgSupported = epgSupported;
        this.preferredEngine = safe(preferredEngine);
        this.fallbackEngine = safe(fallbackEngine);
        this.lastHttpStatus = lastHttpStatus;
        this.discoveredAt = discoveredAt;
    }

    public JSONObject toJson() {
        JSONObject out = new JSONObject();
        try {
            out.put("playlistId", playlistId);
            out.put("apiMode", apiMode);
            out.put("userAgent", userAgent);
            out.put("origin", origin);
            out.put("referer", referer);
            out.put("finalHost", finalHost);
            out.put("liveProtocol", liveProtocol);
            out.put("vodProtocol", vodProtocol);
            out.put("epgSupported", epgSupported);
            out.put("preferredEngine", preferredEngine);
            out.put("fallbackEngine", fallbackEngine);
            out.put("lastHttpStatus", lastHttpStatus);
            out.put("discoveredAt", discoveredAt);
        } catch (Exception ignored) { }
        return out;
    }

    public static CompatibilityProfile fromJson(JSONObject value) {
        if (value == null) return null;
        return new CompatibilityProfile(
                value.optString("playlistId"), value.optString("apiMode"),
                value.optString("userAgent"), value.optString("origin"),
                value.optString("referer"), value.optString("finalHost"),
                value.optString("liveProtocol"), value.optString("vodProtocol"),
                value.optBoolean("epgSupported", false),
                value.optString("preferredEngine", "auto"),
                value.optString("fallbackEngine", "libvlc"),
                value.optInt("lastHttpStatus", -1),
                value.optLong("discoveredAt", 0L));
    }

    private static String safe(String value) { return value == null ? "" : value.trim(); }
}
