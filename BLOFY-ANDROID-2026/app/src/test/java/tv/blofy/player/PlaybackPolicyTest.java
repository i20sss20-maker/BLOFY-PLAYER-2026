package tv.blofy.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PlaybackPolicyTest {
    @Test
    public void signedNativeUrlStaysDirectAndUnmodified() {
        String signed = "https://blofy.example/api/native-play?u=sealed&e=123&s=signed";
        String result = PlaybackPolicy.directPlaybackUrl(signed);
        assertEquals(signed, result);
        assertFalse(result.contains("compat="));
    }

    @Test
    public void transportStreamsUseProgressiveMpegTsMimeType() {
        assertEquals("video/mp2t", PlaybackPolicy.mimeType(".TS"));
        assertTrue(PlaybackPolicy.isTransportStream("ts"));
        assertFalse(PlaybackPolicy.isHls("ts"));
        assertTrue(PlaybackPolicy.isHls("m3u8"));
    }

    @Test
    public void unknownExtensionsAreLeftForMedia3Sniffing() {
        assertNull(PlaybackPolicy.mimeType("unknown"));
    }

    @Test
    public void fastStartupTimeoutsAreBounded() {
        assertEquals(6_000, PlaybackPolicy.startupTimeoutMs(0));
        assertEquals(8_000, PlaybackPolicy.startupTimeoutMs(1));
    }

    @Test
    public void liveFallbackSwitchesBetweenTsAndHls() {
        assertEquals("m3u8", PlaybackPolicy.alternateLiveExtension("ts"));
        assertEquals("ts", PlaybackPolicy.alternateLiveExtension("m3u8"));
    }

    @Test
    public void recoveryUsesHttpFirstThenCronetThenAlternateFormat() {
        // Fast/default path: platform HTTP first, then Cronet, then alternate TS/HLS.
        assertFalse(PlaybackPolicy.useCronet(0));
        assertTrue(PlaybackPolicy.useCronet(1));
        assertFalse(PlaybackPolicy.useCronet(2));
        assertTrue(PlaybackPolicy.useCronet(3));

        assertTrue(PlaybackPolicy.shouldRetrySameFormat(1));
        assertFalse(PlaybackPolicy.shouldRetrySameFormat(2));
        assertTrue(PlaybackPolicy.shouldTryAlternateLiveFormat(2));
        assertTrue(PlaybackPolicy.shouldRetryAlternateFormat(3));
        assertTrue(PlaybackPolicy.exhausted(4));
    }
}
