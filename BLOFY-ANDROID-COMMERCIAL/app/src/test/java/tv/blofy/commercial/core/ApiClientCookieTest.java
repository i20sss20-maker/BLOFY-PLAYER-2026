package tv.blofy.commercial.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ApiClientCookieTest {
    @Test public void mergePreservesCookiesNotPresentInResponse() {
        Map<String, String> cookies = ApiClient.parseCookieHeader(
                "blofy_session=new-session; blofy_license=old-license");
        Map<String, Long> versions = new LinkedHashMap<>();

        assertTrue(ApiClient.mergeSetCookie(cookies, versions, 2L,
                "blofy_license=new-license; Path=/; HttpOnly"));

        assertEquals("new-session", cookies.get("blofy_session"));
        assertEquals("new-license", cookies.get("blofy_license"));
    }

    @Test public void olderResponseCannotOverwriteNewerCookie() {
        Map<String, String> cookies = ApiClient.parseCookieHeader("blofy_session=initial");
        Map<String, Long> versions = new LinkedHashMap<>();

        assertTrue(ApiClient.mergeSetCookie(cookies, versions, 20L,
                "blofy_session=fresh; Path=/"));
        assertFalse(ApiClient.mergeSetCookie(cookies, versions, 10L,
                "blofy_session=stale; Path=/"));

        assertEquals("fresh", cookies.get("blofy_session"));
    }

    @Test public void clearingCookieDoesNotDropOtherCookies() {
        Map<String, String> cookies = ApiClient.parseCookieHeader(
                "blofy_session=session; blofy_license=license; preference=dark");
        Map<String, Long> versions = new LinkedHashMap<>();

        assertTrue(ApiClient.mergeSetCookie(cookies, versions, 5L,
                "blofy_session=; Max-Age=0; Path=/"));

        assertFalse(cookies.containsKey("blofy_session"));
        assertEquals("license", cookies.get("blofy_license"));
        assertEquals("dark", cookies.get("preference"));
    }

    @Test public void headerRoundTripIsStable() {
        Map<String, String> cookies = ApiClient.parseCookieHeader(
                "blofy_session=abc==; blofy_license=xyz");
        assertEquals("blofy_session=abc==; blofy_license=xyz",
                ApiClient.formatCookieHeader(cookies));
    }
}
