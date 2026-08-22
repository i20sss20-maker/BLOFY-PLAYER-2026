package tv.blofy.commercial.provider;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okio.BufferedSource;
import tv.blofy.commercial.data.MediaRecord;

/** Streaming M3U parser. Playlist bytes go directly from provider to the Android device. */
public final class M3uClient {
    public interface EntryConsumer {
        void accept(String type, String categoryId, String categoryName, MediaRecord item) throws Exception;
    }

    private static final Pattern ATTR = Pattern.compile("([A-Za-z0-9_-]+)=\"([^\"]*)\"");
    private static final String[] USER_AGENTS = new String[] {
            "Mozilla/5.0 (Linux; Android 11; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36",
            "IPTVSmartersPlayer",
            "Dalvik/2.1.0 (Linux; U; Android 11; Android TV)",
            "VLC/3.0.20 LibVLC/3.0.20"
    };

    private final ProviderProfile profile;
    private final OkHttpClient http;
    private volatile String workingUserAgent = USER_AGENTS[0];

    public M3uClient(ProviderProfile profile) {
        if (profile == null || !profile.isM3u() || !profile.isValid()) {
            throw new IllegalArgumentException("رابط M3U المحلي غير صالح.");
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

    public int stream(EntryConsumer consumer) throws Exception {
        try (Response response = executeCompatible(profile.playlistUrl)) {
            if (!response.isSuccessful()) {
                int status = response.code();
                if (status == 403) {
                    throw new Exception("المزوّد رفض M3U (HTTP 403) بعد تجربة هويات Android TV وSmarters وDalvik وVLC. غالبًا يوجد تقييد على الحساب أو IP الجهاز أو رابط get.php نفسه.");
                }
                if (status == 401) throw new Exception("المزوّد رفض بيانات M3U (HTTP 401).");
                if (status == 456) throw new Exception("المزوّد رفض M3U (HTTP 456). غالبًا يوجد تقييد IP/اتصال على الحساب.");
                throw new Exception("المزوّد رفض M3U (HTTP " + status + ").");
            }
            if (response.body() == null) throw new Exception("قائمة M3U فارغة.");
            BufferedSource source = response.body().source();
            Pending pending = null;
            int count = 0;
            String line;
            while ((line = source.readUtf8Line()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("#EXTINF")) {
                    pending = parseInfo(line);
                    continue;
                }
                if (line.startsWith("#")) continue;
                if (!(line.startsWith("http://") || line.startsWith("https://"))) continue;
                Pending info = pending == null ? new Pending() : pending;
                pending = null;
                String type = classify(line, info.group);
                String category = info.group.isEmpty() ? "غير مصنف" : info.group;
                String name = info.name.isEmpty() ? line : info.name;
                MediaRecord item = new MediaRecord(
                        type, line, name, info.logo, "", category,
                        "", "", extension(line, type));
                consumer.accept(type, category, category, item);
                count++;
            }
            if (count == 0) throw new Exception("قائمة M3U تم فتحها لكن لم تحتوِ على روابط تشغيل صالحة.");
            return count;
        } catch (IOException error) {
            throw new Exception("تعذر قراءة M3U مباشرة: " + error.getMessage());
        }
    }

    public String playbackUrl(String id) { return id == null ? "" : id; }
    public String workingUserAgent() { return workingUserAgent; }

    private Response executeCompatible(String url) throws IOException {
        String preferred = workingUserAgent;
        Response last = http.newCall(request(url, preferred)).execute();
        if (last.code() != 403 && last.code() != 406) return last;

        for (String userAgent : USER_AGENTS) {
            if (userAgent.equals(preferred)) continue;
            last.close();
            last = http.newCall(request(url, userAgent)).execute();
            if (last.code() != 403 && last.code() != 406) {
                workingUserAgent = userAgent;
                return last;
            }
        }
        return last;
    }

    private Request request(String url, String userAgent) {
        Request.Builder builder = new Request.Builder().url(url)
                .header("User-Agent", userAgent)
                .header("Accept", "application/x-mpegURL,text/plain,*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache");

        if ("IPTVSmartersPlayer".equals(userAgent)) {
            builder.header("X-Requested-With", "com.nst.iptvsmarterstvbox");
        } else if (userAgent.startsWith("Mozilla/")) {
            HttpUrl target = HttpUrl.parse(url);
            if (target != null) {
                String origin = target.scheme() + "://" + target.host()
                        + ((target.port() == 80 && "http".equals(target.scheme()))
                        || (target.port() == 443 && "https".equals(target.scheme())) ? "" : ":" + target.port());
                builder.header("Origin", origin).header("Referer", origin + "/");
            }
        }
        return builder.build();
    }

    private static Pending parseInfo(String line) {
        Pending result = new Pending();
        Matcher matcher = ATTR.matcher(line);
        while (matcher.find()) {
            String key = matcher.group(1).toLowerCase(Locale.US);
            String value = matcher.group(2) == null ? "" : matcher.group(2).trim();
            if ("tvg-name".equals(key)) result.name = value;
            else if ("tvg-logo".equals(key)) result.logo = value;
            else if ("group-title".equals(key)) result.group = value;
        }
        int comma = line.indexOf(',');
        if (result.name.isEmpty() && comma >= 0 && comma + 1 < line.length()) {
            result.name = line.substring(comma + 1).trim();
        }
        return result;
    }

    private static String classify(String url, String group) {
        String value = (url + " " + group).toLowerCase(Locale.US);
        if (value.contains("/movie/") || value.contains("vod") || value.contains("افلام") || value.contains("movies")) return "movies";
        if (value.contains("/series/") || value.contains("مسلسل") || value.contains("series")) return "series";
        return "live";
    }

    private static String extension(String url, String type) {
        String clean = url;
        int query = clean.indexOf('?');
        if (query >= 0) clean = clean.substring(0, query);
        int slash = clean.lastIndexOf('/');
        int dot = clean.lastIndexOf('.');
        if (dot > slash && dot + 1 < clean.length()) {
            String ext = clean.substring(dot + 1).toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "");
            if (!ext.isEmpty() && ext.length() <= 6) return ext;
        }
        return "live".equals(type) ? "ts" : "mp4";
    }

    private static final class Pending {
        String name = "";
        String logo = "";
        String group = "";
    }
}
