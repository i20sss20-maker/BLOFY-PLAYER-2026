package tv.blofy.player;

import java.util.Locale;

/**
 * Playback recovery policy modeled on the stable behaviour observed in 7 Max:
 * direct TS first, retry, switch transport, then try HLS with the same two
 * transports before surfacing an error.
 */
final class PlaybackPolicy {
    static final int INITIAL_STARTUP_TIMEOUT_MS = 15_000;
    static final int RETRY_STARTUP_TIMEOUT_MS = 20_000;
    static final int MAX_RECOVERY_STEPS = 4;

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

    /** step 0/1/3 use Player1 HTTP; step 2/4 use Cronet Player2. */
    static boolean useCronet(int recoveryStep) {
        return recoveryStep == 2 || recoveryStep == 4;
    }

    static boolean shouldRetrySameFormat(int recoveryStep) {
        return recoveryStep == 1 || recoveryStep == 2;
    }

    /** After Default HTTP + retry + Cronet on TS, switch to HLS. */
    static boolean shouldTryAlternateLiveFormat(int recoveryStep) {
        return recoveryStep == 3;
    }

    /** After switching format, step 4 retries that format through Cronet. */
    static boolean shouldRetryAlternateFormat(int recoveryStep) {
        return recoveryStep == 4;
    }

    static boolean exhausted(int recoveryStep) {
        return recoveryStep > MAX_RECOVERY_STEPS;
    }

    static String transportName(int recoveryStep) {
        return useCronet(recoveryStep) ? "cronet" : "default-http";
    }

    static String directPlaybackUrl(String signedNativeUrl) {
        return signedNativeUrl;
    }
}
