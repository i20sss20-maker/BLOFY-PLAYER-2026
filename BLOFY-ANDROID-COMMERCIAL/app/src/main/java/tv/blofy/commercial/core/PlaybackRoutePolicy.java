package tv.blofy.commercial.core;

import java.net.URI;

/** Small, side-effect-free routing rules shared by the Media3 player and tests. */
public final class PlaybackRoutePolicy {
    private PlaybackRoutePolicy() {}

    public static boolean isSignedRelayPath(String value) {
        if (value == null) return false;
        String path = value.trim();
        return path.startsWith("/api/proxy?")
                && path.contains("u=") && path.contains("e=") && path.contains("s=");
    }

    public static boolean isCleartextHttp(String value) {
        return value != null && value.trim().toLowerCase(java.util.Locale.US).startsWith("http://");
    }

    public static boolean isHttpUrl(String value) {
        if (value == null) return false;
        String url = value.trim().toLowerCase(java.util.Locale.US);
        return url.startsWith("http://") || url.startsWith("https://");
    }

    /**
     * The resource signature is independent from the serving endpoint. This
     * lets the native app resolve the same signed resource through
     * /api/native-play and open it on the TV first, while retaining /api/proxy
     * as the one-shot relay fallback.
     */
    public static String directRedirectPath(String signedPath) {
        if (signedPath == null) return "";
        String value = signedPath.trim();
        if (!hasCompleteSignature(value)) return "";
        if (value.startsWith("/api/native-play?")) return value;
        if (value.startsWith("/api/proxy?")) {
            return "/api/native-play?" + value.substring("/api/proxy?".length());
        }
        return "";
    }

    public static boolean canTryRelay(String relayUrl, boolean alreadyAttempted) {
        return !alreadyAttempted && relayUrl != null && !relayUrl.trim().isEmpty();
    }

    public static boolean canTryRelay(String relayUrl, boolean alreadyAttempted, String currentUrl) {
        return canTryRelay(relayUrl, alreadyAttempted)
                && (currentUrl == null || !relayUrl.trim().equals(currentUrl.trim()));
    }

    public static boolean isRelayEligibleHttpStatus(int status) {
        // These statuses can depend on request headers, rate limits, region or
        // source IP, so changing from device-direct to the server relay can be
        // useful. Authentication/not-found statuses are deliberately absent:
        // changing the decoder or transport cannot repair stale credentials.
        return status == 403 || status == 406 || status == 408 || status == 421
                || status == 425 || status == 429 || status == 451 || status == 456;
    }

    public static boolean isHttpError(int status) {
        return status >= 400 && status <= 599;
    }

    public static boolean isBackendAuthorizationStatus(int status, int providerStatus) {
        // /api/proxy intentionally preserves an upstream 401/402 and marks it
        // with providerStatus. That is a media-source failure, not a reason to
        // erase the BLOFY login or force the activation screen.
        return (status == 401 || status == 402) && providerStatus <= 0;
    }

    public static boolean isBlofyUrl(String baseUrl, String mediaUrl) {
        if (baseUrl == null || mediaUrl == null) return false;
        try {
            URI base = URI.create(baseUrl);
            URI media = URI.create(mediaUrl);
            int basePort = base.getPort() >= 0 ? base.getPort() : defaultPort(base.getScheme());
            int mediaPort = media.getPort() >= 0 ? media.getPort() : defaultPort(media.getScheme());
            return base.getScheme() != null && base.getScheme().equalsIgnoreCase(media.getScheme())
                    && base.getHost() != null && base.getHost().equalsIgnoreCase(media.getHost())
                    && basePort == mediaPort;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public static boolean isBlofyRelayUrl(String baseUrl, String mediaUrl) {
        if (!isBlofyUrl(baseUrl, mediaUrl)) return false;
        try {
            String path = URI.create(mediaUrl).getPath();
            return "/api/proxy".equals(path)
                    || "/api/transcode/index.m3u8".equals(path)
                    || "/api/play/live".equals(path)
                    || (path != null && path.startsWith("/api/play/"));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean hasCompleteSignature(String value) {
        return value.contains("u=") && value.contains("e=") && value.contains("s=");
    }

    private static int defaultPort(String scheme) {
        return "https".equalsIgnoreCase(scheme) ? 443 : "http".equalsIgnoreCase(scheme) ? 80 : -1;
    }
}
