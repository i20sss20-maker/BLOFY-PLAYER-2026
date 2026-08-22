package tv.blofy.commercial.provider;

import android.content.Context;

import java.util.LinkedHashMap;
import java.util.Map;

/** Resolves saved discovery results into playback request policy for the active playlist. */
public final class PlaybackCompatibility {
    public final String userAgent;
    public final String origin;
    public final String referer;
    public final String preferredEngine;
    public final String fallbackEngine;

    private PlaybackCompatibility(String userAgent, String origin, String referer,
                                  String preferredEngine, String fallbackEngine) {
        this.userAgent = safe(userAgent).isEmpty() ? "VLC/3.0.20 LibVLC/3.0.20" : safe(userAgent);
        this.origin = safe(origin);
        this.referer = safe(referer);
        this.preferredEngine = safe(preferredEngine).isEmpty() ? "media3" : safe(preferredEngine);
        this.fallbackEngine = safe(fallbackEngine).isEmpty() ? "libvlc" : safe(fallbackEngine);
    }

    public static PlaybackCompatibility resolve(Context context) {
        PlaylistProfile playlist = PlaylistRepository.active(context);
        CompatibilityProfile profile = playlist == null ? null
                : CompatibilityProfileStore.load(context, playlist.id);
        if (profile == null) {
            return new PlaybackCompatibility("VLC/3.0.20 LibVLC/3.0.20", "", "", "media3", "libvlc");
        }
        return new PlaybackCompatibility(profile.userAgent, profile.origin, profile.referer,
                profile.preferredEngine, profile.fallbackEngine);
    }

    public String selectedEngine(Context context) {
        String manual = context.getSharedPreferences("blofy_player_settings", Context.MODE_PRIVATE)
                .getString("player_engine", "auto");
        return manual == null || manual.isEmpty() || "auto".equals(manual) ? preferredEngine : manual;
    }

    public Map<String, String> requestHeaders() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("Accept", "*/*");
        out.put("Accept-Encoding", "identity");
        out.put("Cache-Control", "no-cache");
        out.put("Icy-MetaData", "1");
        if (!origin.isEmpty()) out.put("Origin", origin);
        if (!referer.isEmpty()) out.put("Referer", referer);
        return out;
    }

    private static String safe(String value) { return value == null ? "" : value.trim(); }
}
