package tv.blofy.player;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * v332 adaptive decision layer.
 *
 * This class intentionally sits above the proven v331 player core. It never changes
 * another content type when one type fails: live, movies and series learn separately.
 */
final class AdaptivePlaybackController {
    static final String LEGACY = "legacy7max";
    static final String PLATFORM = "platform";
    static final String CRONET = "cronet";
    static final String COMPAT = "compat";
    static final String VLC = "vlc";

    private static final String PREFS = "blofy_adaptive_playback_v332";
    private static final String MODE = "mode:";
    private static final String FAILS = "fails:";
    private static final String SUCCESSES = "successes:";
    private static final String FAMILY = "family:";

    private AdaptivePlaybackController() {}

    static Decision decide(Context context, String playlistId, String kind, String extension) {
        String key = key(playlistId, kind);
        SharedPreferences p = prefs(context);
        String learned = p.getString(MODE + key, "");
        String family = p.getString(FAMILY + key, family(extension));
        if (!learned.isEmpty()) return new Decision(learned, family, true);
        // Keep v331's successful route as the conservative default.
        return new Decision(LEGACY, family, false);
    }

    static void learnFromProbe(Context context, String playlistId, String kind,
                               String extension, PlaybackCapabilityProbe.Result result,
                               boolean cronetReady) {
        if (result == null || !result.success) return;
        String contentFamily = PlaybackCapabilityProbe.inferFamily(result, extension);
        String preferred;
        if ("mkv".equals(contentFamily)) preferred = VLC;
        else if (result.redirected && cronetReady) preferred = CRONET;
        else if (result.rangeSupported || "hls".equals(contentFamily) || "ts".equals(contentFamily)) preferred = LEGACY;
        else preferred = COMPAT;
        String key = key(playlistId, kind);
        prefs(context).edit()
                .putString(MODE + key, preferred)
                .putString(FAMILY + key, contentFamily)
                .putInt(FAILS + key, 0)
                .apply();
    }

    static void recordSuccess(Context context, String playlistId, String kind,
                              String extension, String mode) {
        String key = key(playlistId, kind);
        SharedPreferences p = prefs(context);
        int count = p.getInt(SUCCESSES + key, 0) + 1;
        p.edit().putString(MODE + key, normalizeMode(mode))
                .putString(FAMILY + key, family(extension))
                .putInt(SUCCESSES + key, count)
                .putInt(FAILS + key, 0)
                .apply();
    }

    static String recordFailureAndNext(Context context, String playlistId, String kind,
                                       String extension, String failedMode) {
        String key = key(playlistId, kind);
        SharedPreferences p = prefs(context);
        int failures = p.getInt(FAILS + key, 0) + 1;
        String next = nextMode(failedMode, extension);
        SharedPreferences.Editor e = p.edit().putInt(FAILS + key, failures);
        // One failure changes only this playback attempt. Two consecutive failures
        // are required before changing the learned per-type profile.
        if (failures >= 2) e.putString(MODE + key, next).putInt(FAILS + key, 0);
        e.apply();
        return next;
    }

    static String nextMode(String current, String extension) {
        String mode = normalizeMode(current);
        String f = family(extension);
        if (LEGACY.equals(mode)) return CRONET;
        if (CRONET.equals(mode)) return COMPAT;
        if (COMPAT.equals(mode)) return VLC;
        if (VLC.equals(mode)) return PLATFORM;
        // Platform is last only for difficult VOD. For live, return to the known-good legacy path.
        return ("ts".equals(f) || "hls".equals(f)) ? LEGACY : VLC;
    }

    static void resetType(Context context, String playlistId, String kind) {
        String key = key(playlistId, kind);
        prefs(context).edit().remove(MODE + key).remove(FAILS + key)
                .remove(SUCCESSES + key).remove(FAMILY + key).apply();
    }

    static String diagnostics(Context context, String playlistId, String kind) {
        String key = key(playlistId, kind);
        SharedPreferences p = prefs(context);
        return normalizeKind(kind) + "=" + p.getString(MODE + key, LEGACY)
                + " family=" + p.getString(FAMILY + key, "unknown")
                + " ok=" + p.getInt(SUCCESSES + key, 0)
                + " fail=" + p.getInt(FAILS + key, 0);
    }

    private static String key(String playlistId, String kind) {
        String playlist = playlistId == null || playlistId.isEmpty() ? "current-session" : playlistId;
        return playlist + ":" + normalizeKind(kind);
    }

    private static String normalizeKind(String kind) {
        String value = kind == null ? "" : kind.toLowerCase(Locale.US);
        if (value.equals("series") || value.equals("episode")) return "series";
        if (value.equals("movie") || value.equals("movies") || value.equals("vod")) return "movies";
        return "live";
    }

    private static String family(String extension) {
        String value = extension == null ? "" : extension.toLowerCase(Locale.US).replace(".", "");
        if (value.contains("m3u8") || value.contains("hls")) return "hls";
        if (value.equals("ts") || value.contains("mpegts")) return "ts";
        if (value.contains("mkv")) return "mkv";
        if (value.contains("mp4")) return "mp4";
        return "vod";
    }

    private static String normalizeMode(String mode) {
        if (CRONET.equals(mode) || COMPAT.equals(mode) || VLC.equals(mode)
                || PLATFORM.equals(mode) || LEGACY.equals(mode)) return mode;
        return LEGACY;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static final class Decision {
        final String mode;
        final String family;
        final boolean learned;
        Decision(String mode, String family, boolean learned) {
            this.mode = mode;
            this.family = family;
            this.learned = learned;
        }
    }
}
