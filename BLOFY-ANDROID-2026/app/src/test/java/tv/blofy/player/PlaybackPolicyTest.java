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
        assertFalse(PlaybackPolicy.isHls("ts"));
        assertTrue(PlaybackPolicy.isHls("m3u8"));
    }

    @Test
    public void unknownExtensionsAreLeftForMedia3Sniffing() {
        assertNull(PlaybackPolicy.mimeType("unknown"));
    }

    @Test
    public void retryAllowsAProviderMoreStartupTime() {
        assertEquals(60_000, PlaybackPolicy.startupTimeoutMs(0));
        assertEquals(90_000, PlaybackPolicy.startupTimeoutMs(1));
    }
}
