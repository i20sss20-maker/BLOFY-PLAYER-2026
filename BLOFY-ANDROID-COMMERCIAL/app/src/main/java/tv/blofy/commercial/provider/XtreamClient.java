package tv.blofy.commercial.provider;

import android.util.JsonReader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.Reader;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Direct Xtream Codes client. Requests go from the Android device to the IPTV provider. */
public final class XtreamClient {
    public interface CatalogConsumer { void accept(JSONObject item) throws Exception; }

    private static final String[] API_USER_AGENTS = new String[] {
            "Mozilla/5.0 (Linux; Android 11; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36",
            "IPTVSmartersPlayer",
            "Dalvik/2.1.0 (Linux; U; Android 11; Android TV)",
            "VLC/3.0.20 LibVLC/3.0.20"
    };

    private final ProviderProfile profile;
    private final OkHttpClient http;
    private volatile String workingUserAgent = API_USER_AGENTS[0];
    private volatile int lastApiStatus = -1;

    public XtreamClient(ProviderProfile profile) {
        if (profile == null || !profile.isXtream() || !profile.isValid()) {
            throw new IllegalArgumentException("بيانات Xtream المحلية غير مكتملة.");
        }
        this.profile = profile;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .build();
    }

    public JSONObject validate() throws Exception {
        JSONObject root = object(apiUrl());
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
        JSONArray raw = array(apiUrl("action", action(type, true)));
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

    /** Streaming parser for very large Xtream libraries. Only one item is materialized at a time. */
    public int streamCatalog(String type, CatalogConsumer consumer) throws Exception {
        String url = apiUrl("action", action(type, false));
        try (Response response = executeCompatible(url)) {
            if (!response.isSuccessful()) throw providerHttpError("المكتبة", response.code());
            if (response.body() == null) throw new Exception("مكتبة المزوّد فارغة.");
            try (Reader body = response.body().charStream(); JsonReader reader = new JsonReader(body)) {
                int count = 0;
                reader.beginArray();
                while (reader.hasNext()) {
                    JSONObject item = readCatalogItem(reader, type);
                    if (!item.optString("id").isEmpty()) {
                        consumer.accept(item);
                        count++;
                    }
                }
                reader.endArray();
                return count;
            }
        } catch (IOException error) {
            throw new Exception("تعذر قراءة مكتبة IPTV مباشرة: " + error.getMessage());
        }
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
        String folder = "live".equals(type) ? "live"
                : ("episode".equals(type) || "series".equals(type)) ? "series" : "movie";
        String ext = cleanExtension(extension, "live".equals(type) ? "ts" : "mp4");
        return profile.serverUrl + "/" + folder + "/" + encodePath(profile.username) + "/"
                + encodePath(profile.password) + "/" + encodePath(id) + "." + ext;
    }

    public String serverName() { return profile.name.isEmpty() ? profile.serverUrl : profile.name; }
    public String workingUserAgent() { return workingUserAgent; }
    public int lastApiStatus() { return lastApiStatus; }

    private JSONObject readCatalogItem(JsonReader reader, String type) throws Exception {
        String id = "", name = "", image = "", backdrop = "", category = "", rating = "", year = "", ext = "";
        reader.beginObject();
        while (reader.hasNext()) {
            String key = reader.nextName();
            switch (key) {
                case "stream_id": if (!"series".equals(type)) id = nextString(reader); else reader.skipValue(); break;
                case "series_id": if ("series".equals(type)) id = nextString(reader); else reader.skipValue(); break;
                case "name": name = nextString(reader); break;
                case "stream_icon": if (!"series".equals(type)) image = nextString(reader); else reader.skipValue(); break;
                case "cover": if ("series".equals(type)) image = nextString(reader); else reader.skipValue(); break;
                case "category_id": category = nextString(reader); break;
                case "rating": rating = nextString(reader); break;
                case "year": year = nextString(reader); break;
                case "releaseDate":
                case "release_date": {
                    String release = nextString(reader);
                    if (year.isEmpty() && release.length() >= 4) year = release.substring(0, 4);
                    break;
                }
                case "container_extension": ext = nextString(reader); break;
                case "backdrop_path": backdrop = readBackdrop(reader); break;
                default: reader.skipValue();
            }
        }
        reader.endObject();
        JSONObject item = new JSONObject();
        item.put("type", type);
        item.put("id", id);
        item.put("name", name);
        item.put("image", image);
        item.put("backdrop", backdrop);
        item.put("categoryId", category);
        item.put("rating", rating);
        item.put("year", year);
        item.put("extension", "series".equals(type) ? "" : cleanExtension(ext, "live".equals(type) ? "ts" : "mp4"));
        return item;
    }

    private static String readBackdrop(JsonReader reader) throws IOException {
        switch (reader.peek()) {
            case BEGIN_ARRAY:
                String first = "";
                reader.beginArray();
                if (reader.hasNext()) first = nextString(reader);
                while (reader.hasNext()) reader.skipValue();
                reader.endArray();
                return first;
            case STRING: return reader.nextString();
            case NULL: reader.nextNull(); return "";
            default: reader.skipValue(); return "";
        }
    }

    private static String nextString(JsonReader reader) throws IOException {
        switch (reader.peek()) {
            case STRING: return reader.nextString();
            case NUMBER: return reader.nextString();
            case BOOLEAN: return String.valueOf(reader.nextBoolean());
            case NULL: reader.nextNull(); return "";
            default: reader.skipValue(); return "";
        }
    }

    private String apiUrl(String... pairs) {
        HttpUrl base = HttpUrl.parse(profile.serverUrl + "/player_api.php");
        if (base == null) throw new IllegalArgumentException("رابط Xtream غير صالح.");
        HttpUrl.Builder builder = base.newBuilder()
                .addQueryParameter("username", profile.username)
                .addQueryParameter("password", profile.password);
        if (pairs != null) for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (pairs[i] != null && pairs[i + 1] != null) builder.addQueryParameter(pairs[i], pairs[i + 1]);
        }
        return builder.build().toString();
    }

    private Request request(String url, String userAgent) {
        Request.Builder builder = new Request.Builder().url(url)
                .header("User-Agent", userAgent)
                .header("Accept", "application/json,text/plain,*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache");

        if ("IPTVSmartersPlayer".equals(userAgent)) {
            builder.header("X-Requested-With", "com.nst.iptvsmarterstvbox");
        } else if (userAgent.startsWith("Mozilla/")) {
            HttpUrl target = HttpUrl.parse(profile.serverUrl);
            if (target != null) {
                String origin = target.scheme() + "://" + target.host()
                        + ((target.port() == 80 && "http".equals(target.scheme()))
                        || (target.port() == 443 && "https".equals(target.scheme())) ? "" : ":" + target.port());
                builder.header("Origin", origin).header("Referer", origin + "/");
            }
        }
        return builder.build();
    }

    private Response executeCompatible(String url) throws Exception {
        String preferred = workingUserAgent;
        Response last = null;

        for (int pass = 0; pass < API_USER_AGENTS.length; pass++) {
            String userAgent = pass == 0 ? preferred : API_USER_AGENTS[pass - (API_USER_AGENTS[0].equals(preferred) ? 0 : 1)];
            if (pass > 0 && userAgent.equals(preferred)) continue;
            Response candidate;
            try {
                candidate = http.newCall(request(url, userAgent)).execute();
            } catch (IOException error) {
                if (last != null) last.close();
                throw error;
            }
            lastApiStatus = candidate.code();
            if (candidate.code() != 403 && candidate.code() != 406) {
                if (last != null) last.close();
                workingUserAgent = userAgent;
                return candidate;
            }
            if (last != null) last.close();
            last = candidate;
        }
        if (last != null) return last;
        return http.newCall(request(url, preferred)).execute();
    }

    private JSONObject object(String url) throws Exception {
        String text = requestText(url);
        try { return new JSONObject(text); }
        catch (Exception error) { throw new Exception("المزوّد رجع استجابة Xtream غير صالحة."); }
    }

    private JSONArray array(String url) throws Exception {
        String text = requestText(url);
        try { return new JSONArray(text); }
        catch (Exception error) { throw new Exception("المزوّد رجع قائمة Xtream غير صالحة."); }
    }

    private String requestText(String url) throws Exception {
        try (Response response = executeCompatible(url)) {
            if (!response.isSuccessful()) throw providerHttpError("الطلب", response.code());
            if (response.body() == null) throw new Exception("استجابة المزوّد فارغة.");
            return response.body().string();
        } catch (IOException error) {
            throw new Exception("تعذر الاتصال مباشرة بمزوّد IPTV: " + error.getMessage());
        }
    }

    private Exception providerHttpError(String area, int status) {
        if (status == 403) {
            return new Exception("المزوّد رفض " + area + " (HTTP 403) بعد تجربة هويات API متعددة. غالبًا الحساب أو IP الجهاز أو سياسة مزوّد الخدمة تمنع player_api.php.");
        }
        if (status == 401) return new Exception("المزوّد رفض بيانات الدخول (HTTP 401). تحقق من اسم المستخدم وكلمة المرور.");
        if (status == 456) return new Exception("المزوّد رفض الطلب (HTTP 456). غالبًا يوجد تقييد IP/اتصال على الحساب.");
        return new Exception("المزوّد رفض " + area + " (HTTP " + status + ").");
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
