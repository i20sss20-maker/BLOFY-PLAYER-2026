package tv.blofy.player;

import java.util.Locale;

final class PlaybackPolicy {
    static final int INITIAL_STARTUP_TIMEOUT_MS = 60_000;
    static final int RETRY_STARTUP_TIMEOUT_MS = 90_000;

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

    static int startupTimeoutMs(int connectionAttempt) {
        return connectionAttempt > 0 ? RETRY_STARTUP_TIMEOUT_MS : INITIAL_STARTUP_TIMEOUT_MS;
    }

    static String directPlaybackUrl(String signedNativeUrl) {
        return signedNativeUrl;
    }
}
