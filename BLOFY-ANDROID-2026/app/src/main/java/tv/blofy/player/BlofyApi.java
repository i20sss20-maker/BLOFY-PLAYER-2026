package tv.blofy.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class BlofyApi {
    private static final String PREFS = "blofy_native_http";
    private static final String KEY_COOKIES = "cookies";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 25_000;
    // One budget covers both BLOFY authorization and redirect resolution.  A
    // dead host must not consume most of the user's channel-switch window.
    private static final int PLAYBACK_LINK_TIMEOUT_MS = 4_000;

    static final class ApiException extends Exception {
        final int status;
        final String code;
        ApiException(int status, String message) { this(status, "", message); }
        ApiException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code == null ? "" : code;
        }
    }

    static boolean isDeviceRecoveryConflict(Throwable failure) {
        if (!(failure instanceof ApiException)) return false;
        ApiException apiFailure = (ApiException) failure;
        if (apiFailure.status == 409
                && "DEVICE_IDENTITY_CONFLICT".equals(apiFailure.code)) return true;
        // Compatibility with the already deployed v324 server, which returned
        // this exact conflict as a generic 500 before the structured 409 contract.
        // Do not rotate identity for network/local failures or unrelated 5xx errors.
        String message = apiFailure.getMessage() == null ? "" : apiFailure.getMessage().trim();
        return apiFailure.status == 500
                && apiFailure.code.isEmpty()
                && "تعذر استعادة الجهاز. تحقق من رقم الجهاز ورمز الربط.".equals(message);
    }

    /** Cancels the active native-link/redirect connection, not just its Future. */
    static final class Cancellation {
        private HttpURLConnection connection;
        private boolean cancelled;

        synchronized void attach(HttpURLConnection next) throws InterruptedIOException {
            if (cancelled) {
                next.disconnect();
                throw new InterruptedIOException("playback-link-cancelled");
            }
            connection = next;
        }

        synchronized void detach(HttpURLConnection current) {
            if (connection == current) connection = null;
        }

        synchronized void cancel() {
            cancelled = true;
            if (connection != null) connection.disconnect();
            connection = null;
        }

        synchronized boolean isCancelled() { return cancelled; }
    }

    private final Context context;
    private final SharedPreferences preferences;
    private final String baseUrl;
    private final String deviceId;
    private final String deviceSecret;
    private final Map<String, String> cookies = new LinkedHashMap<>();

    BlofyApi(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        baseUrl = BuildConfig.BLOFY_BASE_URL.replaceAll("/+$", "");
        deviceId = DeviceIdentity.id(this.context);
        deviceSecret = DeviceIdentity.secret(this.context);
        loadCookies();
    }

    String baseUrl() { return baseUrl; }
    String deviceId() { return deviceId; }
    String playbackSessionKey() {
        return Integer.toHexString(cookieHeader().hashCode());
    }
    String activationUrl(BlofyModels.License license) {
        String root = license != null && !license.activationUrl.isEmpty() ? license.activationUrl : baseUrl + "/activate";
        if (!DeviceIdentity.hasRegisteredPublicIdentity(context)) return root;
        // The QR must show exactly the same public credentials printed on TV.
        // Never expose the long private id, device key, or reusable six-digit code.
        // A freshly registered one-time token may auto-open the dashboard; after it
        // is consumed the short id + code printed on TV remain the login fallback.
        String url = root + (root.contains("?") ? "&" : "?")
                + "device_id=" + encode(DeviceIdentity.displayId(context));
        String pairToken = DeviceIdentity.pairToken(context);
        return pairToken == null || pairToken.isEmpty()
                ? url : url + "&pair_token=" + encode(pairToken);
    }

    JSONObject get(String path) throws Exception {
        return path.startsWith("/api/native-link/")
                ? getPlayback(path, new Cancellation())
                : request("GET", path, null);
    }

    JSONObject getPlayback(String path, Cancellation cancellation) throws Exception {
        if (!path.startsWith("/api/native-link/")) {
            throw new IllegalArgumentException("getPlayback requires a native-link path");
        }
        long deadlineMs = SystemClock.elapsedRealtime() + PLAYBACK_LINK_TIMEOUT_MS;
        JSONObject result = request("GET", path, null, deadlineMs, cancellation);
        // The native-link endpoint authorizes the device and returns a short-lived
        // BLOFY redirect. Resolve that redirect here with BLOFY cookies/identity,
        // but DO NOT follow it with HttpURLConnection. Media3 receives only the
        // provider URL, so BLOFY credentials are never forwarded to the provider
        // and media bytes never pass through Railway.
        String playbackPath = result.optString("url", "");
        if (playbackPath.startsWith("/api/native-play")) {
            result.put("url", resolveNativePlaybackRedirect(playbackPath, deadlineMs, cancellation));
            result.put("mode", "direct-provider");
        }
        return result;
    }

    JSONObject delete(String path) throws Exception { return request("DELETE", path, null); }
    JSONObject post(String path, JSONObject body) throws Exception { return request("POST", path, body); }
    JSONObject patch(String path, JSONObject body) throws Exception { return request("PATCH", path, body); }

    JSONObject request(String method, String path, JSONObject body) throws Exception {
        return request(method, path, body, 0L, null);
    }

    private JSONObject request(String method, String path, JSONObject body,
                               long deadlineMs, Cancellation cancellation) throws Exception {
        HttpURLConnection connection = open(path, method, deadlineMs);
        if (cancellation != null) cancellation.attach(connection);
        int status;
        String text;
        try {
            checkActive(deadlineMs, cancellation);
            if (body != null) {
                connection.setDoOutput(true);
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            status = connection.getResponseCode();
            checkActive(deadlineMs, cancellation);
            captureCookies(connection);
            connection.setReadTimeout(remainingTimeout(deadlineMs, READ_TIMEOUT_MS));
            InputStream stream = status >= 200 && status < 400
                    ? connection.getInputStream() : connection.getErrorStream();
            text = stream == null ? "" : readText(stream, 64 * 1024 * 1024, deadlineMs, cancellation);
        } finally {
            if (cancellation != null) cancellation.detach(connection);
            connection.disconnect();
        }
        JSONObject result;
        try { result = text.isEmpty() ? new JSONObject() : new JSONObject(text); }
        catch (Exception error) { throw new ApiException(status, "الخادم أعاد بيانات غير صالحة."); }
        if (status < 200 || status >= 300) {
            String message = result.optString("error", "تعذر إكمال الطلب (" + status + ").");
            throw new ApiException(status, result.optString("errorCode", ""), message);
        }
        return result;
    }

    private String resolveNativePlaybackRedirect(String path, long deadlineMs,
                                                 Cancellation cancellation) throws Exception {
        checkActive(deadlineMs, cancellation);
        HttpURLConnection connection = open(path, "GET", deadlineMs);
        cancellation.attach(connection);
        int status;
        String location;
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "*/*");
            status = connection.getResponseCode();
            checkActive(deadlineMs, cancellation);
            captureCookies(connection);
            location = connection.getHeaderField("Location");
        } finally {
            cancellation.detach(connection);
            connection.disconnect();
        }

        if (status < 300 || status >= 400 || location == null || location.isEmpty()) {
            throw new ApiException(status, "تعذر استخراج رابط المصدر المباشر من BLOFY.");
        }

        URL target = new URL(location);
        String scheme = target.getProtocol();
        if (target.getHost() == null || target.getHost().isEmpty()
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new ApiException(403, "رابط المصدر المباشر غير صالح.");
        }
        return target.toString();
    }

    byte[] image(String path) throws Exception {
        HttpURLConnection connection = open(path, "GET");
        connection.setRequestProperty("Accept", "image/*,*/*;q=0.5");
        int status = connection.getResponseCode();
        captureCookies(connection);
        if (status < 200 || status >= 300) {
            connection.disconnect();
            throw new ApiException(status, "تعذر تحميل الصورة.");
        }
        byte[] result;
        try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16_384];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
                if (output.size() > 8 * 1024 * 1024) throw new ApiException(413, "الصورة أكبر من الحد المسموح.");
            }
            result = output.toByteArray();
        } finally { connection.disconnect(); }
        return result;
    }

    private HttpURLConnection open(String path, String method) throws Exception {
        return open(path, method, 0L);
    }

    private HttpURLConnection open(String path, String method, long deadlineMs) throws Exception {
        String target = path.startsWith("http://") || path.startsWith("https://") ? path : baseUrl + (path.startsWith("/") ? path : "/" + path);
        URL url = new URL(target);
        if (!url.getHost().equalsIgnoreCase(new URL(baseUrl).getHost())) {
            throw new ApiException(403, "تم رفض رابط خارج خادم BLOFY.");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(remainingTimeout(deadlineMs, CONNECT_TIMEOUT_MS));
        connection.setReadTimeout(remainingTimeout(deadlineMs, READ_TIMEOUT_MS));
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("User-Agent", "BLOFY-ANDROID-NATIVE/" + BuildConfig.VERSION_NAME);
        connection.setRequestProperty("X-Blofy-Device-Id", deviceId);
        connection.setRequestProperty("X-Blofy-Device-Key", deviceSecret);
        String cookie = cookieHeader();
        if (!cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
        return connection;
    }

    private synchronized void loadCookies() {
        String value = preferences.getString(KEY_COOKIES, "");
        if (value == null || value.isEmpty()) return;
        for (String part : value.split(";")) {
            int at = part.indexOf('=');
            if (at > 0) cookies.put(part.substring(0, at).trim(), part.substring(at + 1).trim());
        }
    }

    private synchronized String cookieHeader() {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            if (result.length() > 0) result.append("; ");
            result.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return result.toString();
    }

    private synchronized void captureCookies(HttpURLConnection connection) {
        for (Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet()) {
            if (header.getKey() == null || !"set-cookie".equals(header.getKey().toLowerCase(Locale.US))) continue;
            for (String value : header.getValue()) {
                String pair = value.split(";", 2)[0];
                int at = pair.indexOf('=');
                if (at <= 0) continue;
                String name = pair.substring(0, at).trim();
                String content = pair.substring(at + 1).trim();
                if (content.isEmpty()) cookies.remove(name); else cookies.put(name, content);
            }
        }
        preferences.edit().putString(KEY_COOKIES, cookieHeader()).apply();
    }

    void clearSession() {
        synchronized (this) {
            cookies.remove("blofy_session");
            preferences.edit().putString(KEY_COOKIES, cookieHeader()).apply();
        }
    }

    void clearAllCookies() {
        synchronized (this) {
            cookies.clear();
            preferences.edit().remove(KEY_COOKIES).commit();
        }
    }

    private static String readText(InputStream input, int limit) throws Exception {
        return readText(input, limit, 0L, null);
    }

    private static String readText(InputStream input, int limit, long deadlineMs,
                                   Cancellation cancellation) throws Exception {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16_384];
            int read;
            while ((read = source.read(buffer)) >= 0) {
                checkActive(deadlineMs, cancellation);
                output.write(buffer, 0, read);
                if (output.size() > limit) throw new ApiException(413, "البيانات أكبر من الحد المسموح.");
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static int remainingTimeout(long deadlineMs, int normalTimeoutMs)
            throws InterruptedIOException {
        if (deadlineMs <= 0) return normalTimeoutMs;
        long remaining = deadlineMs - SystemClock.elapsedRealtime();
        if (remaining <= 0) throw new InterruptedIOException("playback-link-timeout");
        return (int) Math.max(1L, Math.min((long) normalTimeoutMs, remaining));
    }

    private static void checkActive(long deadlineMs, Cancellation cancellation)
            throws InterruptedIOException {
        if (cancellation != null && cancellation.isCancelled()) {
            throw new InterruptedIOException("playback-link-cancelled");
        }
        if (deadlineMs > 0 && SystemClock.elapsedRealtime() >= deadlineMs) {
            throw new InterruptedIOException("playback-link-timeout");
        }
    }

    static String encode(String value) {
        try { return java.net.URLEncoder.encode(String.valueOf(value), "UTF-8"); }
        catch (Exception ignored) { return ""; }
    }
}
