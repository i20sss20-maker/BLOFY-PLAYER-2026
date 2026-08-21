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

import tv.blofy.commercial.BuildConfig;

public final class ApiClient {
    private static final int CONNECT_TIMEOUT = 8_000;
    private static final int READ_TIMEOUT = 20_000;
    private static final int CATALOG_READ_TIMEOUT = 150_000;
    private final Context context;
    private final SharedPreferences preferences;
    private final String baseUrl = BuildConfig.BLOFY_BASE_URL.replaceAll("/+$", "");
    private final Map<String, String> cookies = new LinkedHashMap<>();

    public ApiClient(Context context) {
        this.context = context.getApplicationContext();
        // Reuse the native app cookie jar so an upgrade keeps its session.
        this.preferences = this.context.getSharedPreferences("blofy_native_http", Context.MODE_PRIVATE);
        loadCookies();
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
        synchronized (this) {
            cookies.remove("blofy_session");
            cookies.remove("blofy_license");
            preferences.edit().putString("cookies", cookieHeader()).apply();
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
        HttpURLConnection connection = open(path, "GET", READ_TIMEOUT);
        connection.setInstanceFollowRedirects(false);
        try {
            int status = connection.getResponseCode();
            captureCookies(connection);
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
        HttpURLConnection connection = open(path, method, readTimeout);
        try {
            if (body != null) {
                connection.setDoOutput(true);
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            int status = connection.getResponseCode();
            captureCookies(connection);
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

    private synchronized void loadCookies() {
        String saved = preferences.getString("cookies", "");
        if (saved == null) return;
        for (String part : saved.split(";")) {
            int at = part.indexOf('=');
            if (at > 0) cookies.put(part.substring(0, at).trim(), part.substring(at + 1).trim());
        }
    }

    private synchronized String cookieHeader() {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> item : cookies.entrySet()) {
            if (result.length() > 0) result.append("; ");
            result.append(item.getKey()).append('=').append(item.getValue());
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
                String key = pair.substring(0, at).trim();
                String content = pair.substring(at + 1).trim();
                if (content.isEmpty()) cookies.remove(key); else cookies.put(key, content);
            }
        }
        preferences.edit().putString("cookies", cookieHeader()).apply();
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
