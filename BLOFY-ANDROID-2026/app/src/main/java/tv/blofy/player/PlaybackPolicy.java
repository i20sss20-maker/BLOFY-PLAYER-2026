package tv.blofy.player;

import java.util.Locale;

/**
 * Playback policy intentionally mirrors the simple/stable IPTV behaviour we
 * observed in 7 Max: direct playback first, TS for Xtream live by default,
 * bounded reconnects, then a TS/HLS format fallback.
 */
final class PlaybackPolicy {
    static final int INITIAL_STARTUP_TIMEOUT_MS = 15_000;
    static final int RETRY_STARTUP_TIMEOUT_MS = 20_000;
    static final int SAME_FORMAT_RETRIES = 2;
    static final int MAX_RECOVERY_STEPS = 3;

    private PlaybackPolicy() {}

    static String normalizeExtension(String value, String fallback) {
        String clean = String.valueOf(value == null ? "" : value)
                .trim()
                .replaceFirst("^\\.+", "")
                .replaceAll("[^a-zA-Z0-9]", "")
                .toLowerCase(Locale.US);
        return clean.isEmpty() ? fallback : clean;
    }

    static boolean isHls(String extension) {
        return "m3u8".equals(normalizeExtension(extension, ""));
    }

    static boolean isTransportStream(String extension) {
        String ext = normalizeExtension(extension, "");
        return "ts".equals(ext) || "mts".equals(ext) || "m2ts".equals(ext);
    }

    static String alternateLiveExtension(String extension) {
        return isHls(extension) ? "ts" : "m3u8";
    }

    static String mimeType(String extension) {
        switch (normalizeExtension(extension, "")) {
            case "m3u8": return "application/x-mpegURL";
            case "mpd": return "application/dash+xml";
            case "mp4":
            case "m4v":
            case "mov": return "video/mp4";
            case "mkv": return "video/x-matroska";
            case "webm": return "video/webm";
            case "ts":
            case "mts":
            case "m2ts": return "video/mp2t";
            case "mp3": return "audio/mpeg";
            case "aac": return "audio/mp4a-latm";
            default: return null;
        }
    }

    static int startupTimeoutMs(int recoveryStep) {
        return recoveryStep > 0 ? RETRY_STARTUP_TIMEOUT_MS : INITIAL_STARTUP_TIMEOUT_MS;
    }

    static boolean shouldRetrySameFormat(int recoveryStep) {
        return recoveryStep <= SAME_FORMAT_RETRIES;
    }

    static boolean shouldTryAlternateLiveFormat(int recoveryStep) {
        return recoveryStep == MAX_RECOVERY_STEPS;
    }

    static String directPlaybackUrl(String signedNativeUrl) {
        return signedNativeUrl;
    }
}
