package tv.blofy.player;

import java.util.Locale;

/**
 * Fast IPTV recovery policy: start Live through Cronet, fall back to the
 * platform HTTP stack, then try the alternate Live container. This keeps the
 * proven Media3/direct-provider path while avoiding long duplicate retries.
 */
final class PlaybackPolicy {
    static final int INITIAL_STARTUP_TIMEOUT_MS = 6_000;
    static final int RETRY_STARTUP_TIMEOUT_MS = 8_000;
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

    /** step 0/2 use Cronet; step 1/3 use platform HTTP. */
    static boolean useCronet(int recoveryStep) {
        return recoveryStep == 0 || recoveryStep == 2;
    }

    /** One same-format fallback only: Cronet -> platform HTTP. */
    static boolean shouldRetrySameFormat(int recoveryStep) {
        return recoveryStep == 1;
    }

    /** After both transports on the original format, switch TS <-> HLS. */
    static boolean shouldTryAlternateLiveFormat(int recoveryStep) {
        return recoveryStep == 2;
    }

    /** Retry the alternate format once through platform HTTP. */
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
