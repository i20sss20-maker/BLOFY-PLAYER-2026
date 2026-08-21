package tv.blofy.commercial.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PlaybackRoutePolicyTest {
    @Test public void acceptsOnlyCompleteSignedRelayPaths() {
        assertTrue(PlaybackRoutePolicy.isSignedRelayPath("/api/proxy?u=sealed&e=123&s=signature"));
        assertFalse(PlaybackRoutePolicy.isSignedRelayPath("/api/proxy?u=sealed"));
        assertFalse(PlaybackRoutePolicy.isSignedRelayPath("https://provider.example/video.ts"));
    }

    @Test public void legacyCleartextIsDetectedBeforeMedia3() {
        assertTrue(PlaybackRoutePolicy.isCleartextHttp("http://provider.example/live.ts"));
        assertFalse(PlaybackRoutePolicy.isCleartextHttp("https://provider.example/live.ts"));
    }

    @Test public void relayFallbackRunsAtMostOnce() {
        assertTrue(PlaybackRoutePolicy.canTryRelay("https://blofy.example/api/proxy?x=1", false));
        assertFalse(PlaybackRoutePolicy.canTryRelay("https://blofy.example/api/proxy?x=1", true));
        assertFalse(PlaybackRoutePolicy.canTryRelay("", false));
    }

    @Test public void backendAuthorizationStatusesAreSeparatedFromProviderErrors() {
        assertTrue(PlaybackRoutePolicy.isBackendAuthorizationStatus(401, -1));
        assertTrue(PlaybackRoutePolicy.isBackendAuthorizationStatus(402, -1));
        assertFalse(PlaybackRoutePolicy.isBackendAuthorizationStatus(401, 401));
        assertFalse(PlaybackRoutePolicy.isBackendAuthorizationStatus(402, 402));
        assertFalse(PlaybackRoutePolicy.isBackendAuthorizationStatus(403, -1));
        assertFalse(PlaybackRoutePolicy.isBackendAuthorizationStatus(500, -1));
    }

    @Test public void backendOriginComparisonIsStrict() {
        assertTrue(PlaybackRoutePolicy.isBlofyUrl(
                "https://blofy.example", "https://blofy.example/api/proxy?x=1"));
        assertFalse(PlaybackRoutePolicy.isBlofyUrl(
                "https://blofy.example", "https://provider.example/video.ts"));
        assertFalse(PlaybackRoutePolicy.isBlofyUrl(
                "https://blofy.example", "http://blofy.example/api/proxy?x=1"));
    }
}
