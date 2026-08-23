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
    public void sevenMaxStyleStartupTimeoutsAreBounded() {
        assertEquals(15_000, PlaybackPolicy.startupTimeoutMs(0));
        assertEquals(20_000, PlaybackPolicy.startupTimeoutMs(1));
    }

    @Test
    public void liveFallbackSwitchesBetweenTsAndHls() {
        assertEquals("m3u8", PlaybackPolicy.alternateLiveExtension("ts"));
        assertEquals("ts", PlaybackPolicy.alternateLiveExtension("m3u8"));
    }

    @Test
    public void recoveryRetriesSameFormatBeforeFormatFallback() {
        assertTrue(PlaybackPolicy.shouldRetrySameFormat(1));
        assertTrue(PlaybackPolicy.shouldRetrySameFormat(2));
        assertFalse(PlaybackPolicy.shouldRetrySameFormat(3));
        assertTrue(PlaybackPolicy.shouldTryAlternateLiveFormat(3));
    }
}
