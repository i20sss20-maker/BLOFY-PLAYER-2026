package tv.blofy.player;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class AdaptivePlaybackControllerTest {
    @Test
    public void fallbackOrderKeepsLegacyFirst() {
        assertEquals(AdaptivePlaybackController.CRONET,
                AdaptivePlaybackController.nextMode(AdaptivePlaybackController.LEGACY, "ts"));
        assertEquals(AdaptivePlaybackController.COMPAT,
                AdaptivePlaybackController.nextMode(AdaptivePlaybackController.CRONET, "ts"));
        assertEquals(AdaptivePlaybackController.VLC,
                AdaptivePlaybackController.nextMode(AdaptivePlaybackController.COMPAT, "mp4"));
    }

    @Test
    public void difficultVodCanReachPlatformAfterVlc() {
        assertEquals(AdaptivePlaybackController.PLATFORM,
                AdaptivePlaybackController.nextMode(AdaptivePlaybackController.VLC, "mkv"));
        assertEquals(AdaptivePlaybackController.VLC,
                AdaptivePlaybackController.nextMode(AdaptivePlaybackController.PLATFORM, "mp4"));
    }

    @Test
    public void livePlatformFallbackReturnsToKnownGoodLegacy() {
        assertEquals(AdaptivePlaybackController.LEGACY,
                AdaptivePlaybackController.nextMode(AdaptivePlaybackController.PLATFORM, "m3u8"));
        assertEquals(AdaptivePlaybackController.LEGACY,
                AdaptivePlaybackController.nextMode(AdaptivePlaybackController.PLATFORM, "ts"));
    }
}
