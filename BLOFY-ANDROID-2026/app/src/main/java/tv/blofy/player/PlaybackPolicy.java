package tv.blofy.player;

import java.util.Locale;

/**
 * BLOFY playback recovery policy.
 * Start with the platform HTTP stack (fastest on the tested TV/server), retry
 * with Cronet, then for Live only try the alternate TS/HLS container.
 */
final class PlaybackPolicy {
    static final int INITIAL_STARTUP_TIMEOUT_MS = 3_500;
    static final int RETRY_STARTUP_TIMEOUT_MS = 5_500;
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
            // Many IPTV panels return MKV/WebM with generic or incorrect HTTP
            // Content-Type. Let Media3 sniff EBML/container bytes instead of
            // forcing a type that can select the wrong extractor too early.
            case "mkv":
            case "webm": return null;
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

    /** step 0/2 use platform HTTP; step 1/3 use Cronet. */
    static boolean useCronet(int recoveryStep) {
        return recoveryStep == 1 || recoveryStep == 3;
    }

    /** One same-format fallback only: platform HTTP -> Cronet. */
    static boolean shouldRetrySameFormat(int recoveryStep) {
        return recoveryStep == 1;
    }

    /** After both transports on the original Live format, switch TS <-> HLS. */
    static boolean shouldTryAlternateLiveFormat(int recoveryStep) {
        return recoveryStep == 2;
    }

    /** Retry alternate Live format once through Cronet. */
    static boolean shouldRetryAlternateFormat(int recoveryStep) {
        return recoveryStep == 3;
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
