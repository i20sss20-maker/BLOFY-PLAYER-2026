package tv.blofy.commercial.core;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import tv.blofy.commercial.BuildConfig;

public final class ApiClient {
    private static final int CONNECT_TIMEOUT = 8_000;
    private static final int READ_TIMEOUT = 20_000;
    private static final int CATALOG_READ_TIMEOUT = 150_000;
    private static final String COOKIE_PREFERENCES = "blofy_native_http";
    private static final String COOKIE_KEY = "cookies";
    private static final Object COOKIE_LOCK = new Object();
    private static final AtomicLong COOKIE_REQUEST_IDS = new AtomicLong();
    private static final Map<String, Long> COOKIE_VERSIONS = new LinkedHashMap<>();
    private final Context context;
    private final SharedPreferences preferences;
    private final String baseUrl = BuildConfig.BLOFY_BASE_URL.replaceAll("/+$", "");

    public ApiClient(Context context) {
        this.context = context.getApplicationContext();
        // Reuse the native app cookie jar so an upgrade keeps its session.
        this.preferences = this.context.getSharedPreferences(COOKIE_PREFERENCES, Context.MODE_PRIVATE);
    }

    public String baseUrl() { return baseUrl; }
    public String deviceId() { return DeviceIdentity.id(context); }
    public String absoluteUrl(String value) {
        String raw = value == null ? "" : value.trim();
        if (raw.isEmpty()) return "";
        return raw.startsWith("http://") || raw.startsWith("https://")
                ? raw : baseUrl + (raw.startsWith("/") ? raw : "/" + raw);
    }
    public Map<String, String> authenticatedHeaders() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("User-Agent", "BLOFY-COMMERCIAL/" + BuildConfig.VERSION_NAME);
        values.put("X-Blofy-Device-Id", deviceId());
        values.put("X-Blofy-Device-Key", DeviceIdentity.secret(context));
        String cookie = cookieHeader();
        if (!cookie.isEmpty()) values.put("Cookie", cookie);
        return values;
    }
    public void clearSession() {
        long mutationId = COOKIE_REQUEST_IDS.incrementAndGet();
        synchronized (COOKIE_LOCK) {
            Map<String, String> cookies = readCookiesLocked();
            cookies.remove("blofy_session");
            cookies.remove("blofy_license");
            COOKIE_VERSIONS.put("blofy_session", mutationId);
            COOKIE_VERSIONS.put("blofy_license", mutationId);
            persistCookiesLocked(cookies);
        }
    }
    public boolean hasSessionCookie() {
        synchronized (COOKIE_LOCK) {
            String value = readCookiesLocked().get("blofy_session");
            return value != null && !value.trim().isEmpty();
        }
    }
    public JSONObject get(String path) throws Exception { return request("GET", path, null, READ_TIMEOUT); }
    public JSONObject getCatalog(String path) throws Exception { return request("GET", path, null, CATALOG_READ_TIMEOUT); }
    public JSONObject post(String path, JSONObject body) throws Exception { return request("POST", path, body, READ_TIMEOUT); }
    public JSONObject delete(String path) throws Exception { return request("DELETE", path, null, READ_TIMEOUT); }

    /**
     * Resolves BLOFY's short-lived native-play redirect without forwarding the
     * device key or session cookie to the external IPTV provider.
     */
    public String resolveMediaRedirect(String path) throws Exception {
        long cookieRequestId = COOKIE_REQUEST_IDS.incrementAndGet();
        HttpURLConnection connection = open(path, "GET", READ_TIMEOUT);
        connection.setInstanceFollowRedirects(false);
        try {
            int status = connection.getResponseCode();
            captureCookies(connection, cookieRequestId);
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location");
                if (location == null || location.trim().isEmpty()) {
                    throw new ApiException(status, "لم يرسل الخادم رابط التشغيل المباشر.");
                }
                URL resolved = new URL(new URL(absoluteUrl(path)), location);
                String protocol = resolved.getProtocol();
                if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
                    throw new ApiException(403, "تم رفض بروتوكول تشغيل غير آمن.");
                }
                return resolved.toString();
            }
            InputStream stream = status >= 200 && status < 400
                    ? connection.getInputStream() : connection.getErrorStream();
            String text = stream == null ? "" : read(stream);
            String message = "تعذر إصدار رابط التشغيل (" + status + ").";
            try { message = new JSONObject(text).optString("error", message); }
            catch (Exception ignored) {}
            throw new ApiException(status, message);
        } finally {
            connection.disconnect();
        }
    }

    public JSONObject request(String method, String path, JSONObject body) throws Exception {
        return request(method, path, body, READ_TIMEOUT);
    }

    private JSONObject request(String method, String path, JSONObject body, int readTimeout) throws Exception {
        long cookieRequestId = COOKIE_REQUEST_IDS.incrementAndGet();
        HttpURLConnection connection = open(path, method, readTimeout);
        try {
            if (body != null) {
                connection.setDoOutput(true);
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            int status = connection.getResponseCode();
            captureCookies(connection, cookieRequestId);
            InputStream stream = status >= 200 && status < 400 ? connection.getInputStream() : connection.getErrorStream();
            String text = stream == null ? "" : read(stream);
            JSONObject json;
            try { json = text.isEmpty() ? new JSONObject() : new JSONObject(text); }
            catch (Exception error) { throw new ApiException(status, "الخادم أعاد استجابة غير مفهومة."); }
            if (status < 200 || status >= 300) throw new ApiException(status, json.optString("error", "فشل الطلب (" + status + ")."));
            return json;
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection open(String path, String method, int readTimeout) throws Exception {
        URL base = new URL(baseUrl);
        URL target = new URL(path.startsWith("http") ? path : baseUrl + (path.startsWith("/") ? path : "/" + path));
        if (!base.getHost().equalsIgnoreCase(target.getHost())) throw new ApiException(403, "تم رفض رابط خارج خادم BLOFY.");
        HttpURLConnection connection = (HttpURLConnection) target.openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_TIMEOUT);
        connection.setReadTimeout(readTimeout);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("User-Agent", "BLOFY-COMMERCIAL/" + BuildConfig.VERSION_NAME);
        connection.setRequestProperty("X-Blofy-Device-Id", deviceId());
        connection.setRequestProperty("X-Blofy-Device-Key", DeviceIdentity.secret(context));
        String cookie = cookieHeader();
        if (!cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
        return connection;
    }

    private static String read(InputStream input) throws Exception {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16_384];
            int count;
            while ((count = source.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
                if (output.size() > 64 * 1024 * 1024) throw new ApiException(413, "البيانات أكبر من الحد الآمن.");
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private String cookieHeader() {
        synchronized (COOKIE_LOCK) {
            return formatCookieHeader(readCookiesLocked());
        }
    }

    private Map<String, String> readCookiesLocked() {
        return parseCookieHeader(preferences.getString(COOKIE_KEY, ""));
    }

    private void persistCookiesLocked(Map<String, String> cookies) {
        // apply() updates SharedPreferences' in-memory value before returning.
        // COOKIE_LOCK therefore makes it an atomic process-wide cookie store
        // without blocking the UI thread on a disk fsync.
        preferences.edit().putString(COOKIE_KEY, formatCookieHeader(cookies)).apply();
    }

    private void captureCookies(HttpURLConnection connection, long requestId) {
        synchronized (COOKIE_LOCK) {
            Map<String, String> cookies = readCookiesLocked();
            boolean changed = false;
            boolean receivedCookie = false;
            for (Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet()) {
                if (header.getKey() == null
                        || !"set-cookie".equals(header.getKey().toLowerCase(Locale.US))) continue;
                if (header.getValue() == null) continue;
                for (String value : header.getValue()) {
                    receivedCookie = true;
                    changed |= mergeSetCookie(cookies, COOKIE_VERSIONS, requestId, value);
                }
            }
            // A response without Set-Cookie must never rewrite the jar. This is
            // what previously allowed an old ApiClient instance to erase a new
            // session or license cookie.
            if (receivedCookie && changed) persistCookiesLocked(cookies);
        }
    }

    static Map<String, String> parseCookieHeader(String saved) {
        Map<String, String> cookies = new LinkedHashMap<>();
        if (saved == null || saved.trim().isEmpty()) return cookies;
        for (String part : saved.split(";")) {
            int at = part.indexOf('=');
            if (at <= 0) continue;
            String key = part.substring(0, at).trim();
            String value = part.substring(at + 1).trim();
            if (!key.isEmpty() && !value.isEmpty()) cookies.put(key, value);
        }
        return cookies;
    }

    static String formatCookieHeader(Map<String, String> cookies) {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> item : cookies.entrySet()) {
            if (item.getKey() == null || item.getKey().trim().isEmpty()
                    || item.getValue() == null || item.getValue().trim().isEmpty()) continue;
            if (result.length() > 0) result.append("; ");
            result.append(item.getKey()).append('=').append(item.getValue());
        }
        return result.toString();
    }

    static boolean mergeSetCookie(Map<String, String> cookies, Map<String, Long> versions,
                                  long requestId, String setCookie) {
        if (setCookie == null) return false;
        String pair = setCookie.split(";", 2)[0];
        int at = pair.indexOf('=');
        if (at <= 0) return false;
        String key = pair.substring(0, at).trim();
        String content = pair.substring(at + 1).trim();
        if (key.isEmpty()) return false;

        long currentVersion = versions.containsKey(key) ? versions.get(key) : Long.MIN_VALUE;
        if (requestId < currentVersion) return false;
        versions.put(key, requestId);

        String lower = setCookie.toLowerCase(Locale.US);
        boolean delete = content.isEmpty()
                || lower.matches("(?s).*;\\s*max-age\\s*=\\s*0(?:\\s*;.*|\\s*)$");
        if (delete) return cookies.remove(key) != null;
        String previous = cookies.put(key, content);
        return !content.equals(previous);
    }

    public static String encode(String value) {
        try { return java.net.URLEncoder.encode(String.valueOf(value), "UTF-8"); }
        catch (Exception error) { return ""; }
    }

    public static final class ApiException extends Exception {
        public final int status;
        public ApiException(int status, String message) { super(message); this.status = status; }
    }
}
