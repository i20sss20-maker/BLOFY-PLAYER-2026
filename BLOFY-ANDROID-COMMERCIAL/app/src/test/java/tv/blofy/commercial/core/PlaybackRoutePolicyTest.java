package tv.blofy.commercial.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
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
        assertTrue(PlaybackRoutePolicy.isHttpUrl("https://provider.example/live.ts"));
        assertFalse(PlaybackRoutePolicy.isHttpUrl("file:///video.ts"));
    }

    @Test public void signedProxyCanBeResolvedDeviceDirectFirst() {
        assertEquals("/api/native-play?u=sealed&e=123&s=signature",
                PlaybackRoutePolicy.directRedirectPath(
                        "/api/proxy?u=sealed&e=123&s=signature"));
        assertEquals("/api/native-play?u=sealed&e=123&s=signature",
                PlaybackRoutePolicy.directRedirectPath(
                        "/api/native-play?u=sealed&e=123&s=signature"));
        assertEquals("", PlaybackRoutePolicy.directRedirectPath("/api/proxy?u=sealed"));
    }

    @Test public void relayFallbackRunsAtMostOnce() {
        assertTrue(PlaybackRoutePolicy.canTryRelay("https://blofy.example/api/proxy?x=1", false));
        assertFalse(PlaybackRoutePolicy.canTryRelay("https://blofy.example/api/proxy?x=1", true));
        assertFalse(PlaybackRoutePolicy.canTryRelay("", false));
        assertFalse(PlaybackRoutePolicy.canTryRelay(
                "https://blofy.example/api/proxy?x=1", false,
                "https://blofy.example/api/proxy?x=1"));
    }

    @Test public void onlyRouteDependentHttpFailuresTryRelay() {
        assertTrue(PlaybackRoutePolicy.isRelayEligibleHttpStatus(403));
        assertTrue(PlaybackRoutePolicy.isRelayEligibleHttpStatus(429));
        assertTrue(PlaybackRoutePolicy.isRelayEligibleHttpStatus(456));
        assertFalse(PlaybackRoutePolicy.isRelayEligibleHttpStatus(401));
        assertFalse(PlaybackRoutePolicy.isRelayEligibleHttpStatus(402));
        assertFalse(PlaybackRoutePolicy.isRelayEligibleHttpStatus(404));
        assertFalse(PlaybackRoutePolicy.isRelayEligibleHttpStatus(500));
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
        assertTrue(PlaybackRoutePolicy.isBlofyRelayUrl(
                "https://blofy.example", "https://blofy.example/api/proxy?x=1"));
        assertFalse(PlaybackRoutePolicy.isBlofyRelayUrl(
                "https://blofy.example", "https://provider.example/video.ts"));
    }
}
