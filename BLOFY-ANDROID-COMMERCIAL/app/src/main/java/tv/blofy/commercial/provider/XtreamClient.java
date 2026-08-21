package tv.blofy.commercial.provider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Direct Xtream Codes client. Requests go from the Android device to the IPTV provider. */
public final class XtreamClient {
    private static final String USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20";

    private final ProviderProfile profile;
    private final OkHttpClient http;

    public XtreamClient(ProviderProfile profile) {
        if (profile == null || !profile.isXtream() || !profile.isValid()) {
            throw new IllegalArgumentException("بيانات Xtream المحلية غير مكتملة.");
        }
        this.profile = profile;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(35, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .build();
    }

    public JSONObject validate() throws Exception {
        JSONObject root = object(apiUrl(null, null));
        JSONObject user = root.optJSONObject("user_info");
        if (user == null) throw new Exception("المزوّد لم يرجع بيانات حساب Xtream.");
        String auth = user.optString("auth", "");
        String status = user.optString("status", "");
        if (!("1".equals(auth) || "Active".equalsIgnoreCase(status))) {
            throw new Exception("بيانات الباقة غير صحيحة أو الاشتراك غير نشط.");
        }
        return root;
    }

    public JSONArray categories(String type) throws Exception {
        String action = action(type, true);
        JSONArray raw = array(apiUrl("action", action));
        JSONArray out = new JSONArray();
        for (int i = 0; i < raw.length(); i++) {
            JSONObject row = raw.optJSONObject(i);
            if (row == null) continue;
            JSONObject item = new JSONObject();
            item.put("id", row.optString("category_id"));
            item.put("name", row.optString("category_name", "غير مصنف"));
            out.put(item);
        }
        return out;
    }

    public JSONArray catalog(String type) throws Exception {
        JSONArray raw = array(apiUrl("action", action(type, false)));
        JSONArray out = new JSONArray();
        for (int i = 0; i < raw.length(); i++) {
            JSONObject row = raw.optJSONObject(i);
            if (row == null) continue;
            JSONObject item = normalize(row, type);
            if (!item.optString("id").isEmpty()) out.put(item);
        }
        return out;
    }

    public JSONObject movieInfo(String id) throws Exception {
        return object(apiUrl("action", "get_vod_info", "vod_id", id));
    }

    public JSONObject seriesInfo(String id) throws Exception {
        return object(apiUrl("action", "get_series_info", "series_id", id));
    }

    public JSONObject epg(String streamId, int limit) throws Exception {
        return object(apiUrl("action", "get_short_epg", "stream_id", streamId,
                "limit", String.valueOf(Math.max(1, limit))));
    }

    public String playbackUrl(String type, String id, String extension) {
        String folder;
        if ("live".equals(type)) folder = "live";
        else if ("episode".equals(type) || "series".equals(type)) folder = "series";
        else folder = "movie";
        String ext = cleanExtension(extension, "live".equals(type) ? "ts" : "mp4");
        return profile.serverUrl + "/" + folder + "/" + encodePath(profile.username) + "/"
                + encodePath(profile.password) + "/" + encodePath(id) + "." + ext;
    }

    public String serverName() { return profile.name.isEmpty() ? profile.serverUrl : profile.name; }

    private JSONObject normalize(JSONObject row, String type) throws Exception {
        JSONObject item = new JSONObject();
        boolean series = "series".equals(type);
        String id = series ? row.optString("series_id") : row.optString("stream_id");
        String image = series ? row.optString("cover") : row.optString("stream_icon");
        String backdrop = "";
        JSONArray backdrops = row.optJSONArray("backdrop_path");
        if (backdrops != null && backdrops.length() > 0) backdrop = backdrops.optString(0, "");
        if (backdrop.isEmpty()) backdrop = row.optString("backdrop_path", "");
        String year = row.optString("year", "");
        if (year.isEmpty()) {
            String released = row.optString("releaseDate", row.optString("release_date", ""));
            if (released.length() >= 4) year = released.substring(0, 4);
        }
        item.put("type", type);
        item.put("id", id);
        item.put("name", row.optString("name"));
        item.put("image", image);
        item.put("backdrop", backdrop);
        item.put("categoryId", row.optString("category_id"));
        item.put("rating", row.optString("rating"));
        item.put("year", year);
        item.put("extension", series ? "" : cleanExtension(
                row.optString("container_extension"), "live".equals(type) ? "ts" : "mp4"));
        return item;
    }

    private String apiUrl(String... pairs) {
        HttpUrl base = HttpUrl.parse(profile.serverUrl + "/player_api.php");
        if (base == null) throw new IllegalArgumentException("رابط Xtream غير صالح.");
        HttpUrl.Builder builder = base.newBuilder()
                .addQueryParameter("username", profile.username)
                .addQueryParameter("password", profile.password);
        if (pairs != null) {
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                if (pairs[i] != null && pairs[i + 1] != null) builder.addQueryParameter(pairs[i], pairs[i + 1]);
            }
        }
        return builder.build().toString();
    }

    private JSONObject object(String url) throws Exception {
        String text = request(url);
        try { return new JSONObject(text); }
        catch (Exception error) { throw new Exception("المزوّد رجع استجابة Xtream غير صالحة."); }
    }

    private JSONArray array(String url) throws Exception {
        String text = request(url);
        try { return new JSONArray(text); }
        catch (Exception error) { throw new Exception("المزوّد رجع قائمة Xtream غير صالحة."); }
    }

    private String request(String url) throws Exception {
        Request request = new Request.Builder().url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json,*/*")
                .build();
        try (Response response = http.newCall(request).execute()) {
            int code = response.code();
            if (!response.isSuccessful()) throw new Exception("المزوّد رفض الطلب (HTTP " + code + ").");
            if (response.body() == null) throw new Exception("استجابة المزوّد فارغة.");
            return response.body().string();
        } catch (IOException error) {
            throw new Exception("تعذر الاتصال مباشرة بمزوّد IPTV: " + error.getMessage());
        }
    }

    private static String action(String type, boolean categories) {
        if ("live".equals(type)) return categories ? "get_live_categories" : "get_live_streams";
        if ("movies".equals(type)) return categories ? "get_vod_categories" : "get_vod_streams";
        if ("series".equals(type)) return categories ? "get_series_categories" : "get_series";
        throw new IllegalArgumentException("نوع مكتبة غير مدعوم: " + type);
    }

    private static String cleanExtension(String value, String fallback) {
        String ext = value == null ? "" : value.toLowerCase().replaceAll("[^a-z0-9]", "");
        return ext.isEmpty() ? fallback : ext;
    }

    private static String encodePath(String value) {
        return android.net.Uri.encode(value == null ? "" : value, null);
    }
}
