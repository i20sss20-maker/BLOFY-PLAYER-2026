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
    public void vodEbmlContainersAreLeftForMedia3Sniffing() {
        assertNull(PlaybackPolicy.mimeType("mkv"));
        assertNull(PlaybackPolicy.mimeType("webm"));
        assertNull(PlaybackPolicy.mimeType("unknown"));
        assertEquals("video/mp4", PlaybackPolicy.mimeType("mp4"));
    }

    @Test
    public void startupTimeoutsAreBoundedForFastFallback() {
        // v340 final compatibility core: faster first decision, short learned-route
        // retry, and separate bounded windows for normal/UHD VOD and LibVLC.
        assertEquals(5_000, PlaybackPolicy.startupTimeoutMs(0));
        assertEquals(2_500, PlaybackPolicy.startupTimeoutMs(1));
        assertEquals(6_500, PlaybackPolicy.vodStartupTimeoutMs(false));
        assertEquals(9_000, PlaybackPolicy.vodStartupTimeoutMs(true));
        assertEquals(5_500, PlaybackPolicy.vlcStartupTimeoutMs(false));
        assertEquals(8_000, PlaybackPolicy.vlcStartupTimeoutMs(true));
        assertEquals(3_500, PlaybackPolicy.PREVIEW_STARTUP_TIMEOUT_MS);
    }

    @Test
    public void failuresAreClassifiedWithoutGuessingTrackSupport() {
        assertTrue(PlaybackPolicy.isNetworkFailure("HTTP 400"));
        assertTrue(PlaybackPolicy.isNetworkFailure("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED"));
        assertTrue(PlaybackPolicy.isDecoderFailure("ERROR_CODE_DECODING_FORMAT_UNSUPPORTED"));
        assertTrue(PlaybackPolicy.isStartupTimeout("انتهت مهلة بدء التشغيل"));
        assertFalse(PlaybackPolicy.isNetworkFailure("ERROR_CODE_DECODER_INIT_FAILED"));
    }

    @Test
    public void liveFallbackSwitchesBetweenTsAndHls() {
        assertEquals("m3u8", PlaybackPolicy.alternateLiveExtension("ts"));
        assertEquals("ts", PlaybackPolicy.alternateLiveExtension("m3u8"));
    }

    @Test
    public void playbackLinkTimeoutAndCancellationHaveStableUserMessages() {
        assertEquals("استغرق الخادم وقتًا أطول من مهلة تجهيز رابط التشغيل.",
                PlaybackPolicy.resolveErrorMessage(
                        new java.io.InterruptedIOException("playback-link-timeout")));
        assertEquals("تم إلغاء تجهيز رابط التشغيل.",
                PlaybackPolicy.resolveErrorMessage(
                        new java.io.InterruptedIOException("playback-link-cancelled")));
        assertEquals("رسالة الخادم",
                PlaybackPolicy.resolveErrorMessage(new Exception("رسالة الخادم")));
    }

}
