package tv.blofy.player;

import java.util.Locale;

/**
 * BLOFY playback recovery policy.
 *
 * Recovery is deliberately bounded.  Older builds walked through every HTTP
 * transport, container and decoder in series, so one dead source could keep a
 * television on the spinner for more than twenty seconds.  The player now
 * gives Media3 one realistic window and then moves to the compatibility engine
 * (or a direct provider URL for an immediate HTTP failure).
 */
final class PlaybackPolicy {
    static final int INITIAL_STARTUP_TIMEOUT_MS = 4_500;
    static final int RETRY_STARTUP_TIMEOUT_MS = 3_500;
    static final int VOD_STARTUP_TIMEOUT_MS = 4_500;
    static final int UHD_VOD_STARTUP_TIMEOUT_MS = 5_500;
    static final int VLC_STARTUP_TIMEOUT_MS = 5_500;
    static final int UHD_VLC_STARTUP_TIMEOUT_MS = 6_500;
    static final int PREVIEW_STARTUP_TIMEOUT_MS = 4_000;

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

    static int vodStartupTimeoutMs(boolean ultraHd) {
        return ultraHd ? UHD_VOD_STARTUP_TIMEOUT_MS : VOD_STARTUP_TIMEOUT_MS;
    }

    static int vlcStartupTimeoutMs(boolean ultraHd) {
        return ultraHd ? UHD_VLC_STARTUP_TIMEOUT_MS : VLC_STARTUP_TIMEOUT_MS;
    }

    static boolean isStartupTimeout(String reason) {
        String value = value(reason);
        return value.contains("مهلة بدء") || value.contains("لم تظهر صورة");
    }

    static boolean isNetworkFailure(String reason) {
        String value = value(reason).toUpperCase(Locale.US);
        return value.contains("HTTP") || value.contains("IO_") || value.contains("NETWORK")
                || value.contains("CONNECTION") || value.contains("TIMEOUT")
                || value.contains("BAD_HTTP_STATUS");
    }

    static boolean isDecoderFailure(String reason) {
        String value = value(reason).toUpperCase(Locale.US);
        return value.contains("DECOD") || value.contains("CODEC")
                || value.contains("FORMAT_UNSUPPORTED") || value.contains("PARSING");
    }

    static String resolveErrorMessage(Throwable error) {
        String message = "";
        Throwable current = error;
        while (current != null) {
            String candidate = current.getMessage();
            if (candidate != null && !candidate.trim().isEmpty()) message = candidate.trim();
            current = current.getCause();
        }
        String upper = message.toUpperCase(Locale.US);
        if (upper.contains("PLAYBACK-LINK-TIMEOUT") || upper.contains("TIMED OUT")) {
            return "استغرق الخادم وقتًا أطول من مهلة تجهيز رابط التشغيل.";
        }
        if (upper.contains("PLAYBACK-LINK-CANCELLED") || upper.contains("INTERRUPTED")) {
            return "تم إلغاء تجهيز رابط التشغيل.";
        }
        return message.isEmpty() ? "تعذر تجهيز رابط التشغيل." : message;
    }

    private static String value(String reason) {
        return reason == null ? "" : reason.trim();
    }

    static String directPlaybackUrl(String signedNativeUrl) {
        return signedNativeUrl;
    }
}
