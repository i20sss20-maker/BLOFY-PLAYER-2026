package tv.blofy.player;

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

final class BlofyApi {
    private static final String PREFS = "blofy_native_http";
    private static final String KEY_COOKIES = "cookies";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 25_000;

    static final class ApiException extends Exception {
        final int status;
        ApiException(int status, String message) { super(message); this.status = status; }
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
        String url = root + (root.contains("?") ? "&" : "?") + "device_id=" + encode(deviceId);
        String pairToken = DeviceIdentity.pairToken(context);
        return pairToken == null || pairToken.isEmpty() ? url : url + "&pair_token=" + encode(pairToken);
    }

    JSONObject get(String path) throws Exception {
        JSONObject result = request("GET", path, null);
        // The native-link endpoint authorizes the device and returns a short-lived
        // BLOFY redirect. Resolve that redirect here with BLOFY cookies/identity,
        // but DO NOT follow it with HttpURLConnection. Media3 receives only the
        // provider URL, so BLOFY credentials are never forwarded to the provider
        // and media bytes never pass through Railway.
        if (path.startsWith("/api/native-link/")) {
            String playbackPath = result.optString("url", "");
            if (playbackPath.startsWith("/api/native-play")) {
                result.put("url", resolveNativePlaybackRedirect(playbackPath));
                result.put("mode", "direct-provider");
            }
        }
        return result;
    }

    JSONObject delete(String path) throws Exception { return request("DELETE", path, null); }
    JSONObject post(String path, JSONObject body) throws Exception { return request("POST", path, body); }
    JSONObject patch(String path, JSONObject body) throws Exception { return request("PATCH", path, body); }

    JSONObject request(String method, String path, JSONObject body) throws Exception {
        HttpURLConnection connection = open(path, method);
        if (body != null) {
            connection.setDoOutput(true);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
        }
        int status = connection.getResponseCode();
        captureCookies(connection);
        InputStream stream = status >= 200 && status < 400 ? connection.getInputStream() : connection.getErrorStream();
        String text = stream == null ? "" : readText(stream, 64 * 1024 * 1024);
        connection.disconnect();
        JSONObject result;
        try { result = text.isEmpty() ? new JSONObject() : new JSONObject(text); }
        catch (Exception error) { throw new ApiException(status, "الخادم أعاد بيانات غير صالحة."); }
        if (status < 200 || status >= 300) {
            String message = result.optString("error", "تعذر إكمال الطلب (" + status + ").");
            throw new ApiException(status, message);
        }
        return result;
    }

    private String resolveNativePlaybackRedirect(String path) throws Exception {
        HttpURLConnection connection = open(path, "GET");
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "*/*");
        int status = connection.getResponseCode();
        captureCookies(connection);
        String location = connection.getHeaderField("Location");
        connection.disconnect();

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
        String target = path.startsWith("http://") || path.startsWith("https://") ? path : baseUrl + (path.startsWith("/") ? path : "/" + path);
        URL url = new URL(target);
        if (!url.getHost().equalsIgnoreCase(new URL(baseUrl).getHost())) {
            throw new ApiException(403, "تم رفض رابط خارج خادم BLOFY.");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
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

    private static String readText(InputStream input, int limit) throws Exception {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16_384];
            int read;
            while ((read = source.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
                if (output.size() > limit) throw new ApiException(413, "البيانات أكبر من الحد المسموح.");
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    static String encode(String value) {
        try { return java.net.URLEncoder.encode(String.valueOf(value), "UTF-8"); }
        catch (Exception ignored) { return ""; }
    }
}
