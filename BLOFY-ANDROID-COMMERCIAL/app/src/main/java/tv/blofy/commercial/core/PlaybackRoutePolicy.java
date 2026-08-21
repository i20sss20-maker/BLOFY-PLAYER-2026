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

    public static boolean canTryRelay(String relayUrl, boolean alreadyAttempted) {
        return !alreadyAttempted && relayUrl != null && !relayUrl.trim().isEmpty();
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

    private static int defaultPort(String scheme) {
        return "https".equalsIgnoreCase(scheme) ? 443 : "http".equalsIgnoreCase(scheme) ? 80 : -1;
    }
}
