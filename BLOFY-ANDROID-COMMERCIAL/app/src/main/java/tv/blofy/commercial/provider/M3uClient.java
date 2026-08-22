package tv.blofy.commercial.provider;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final String USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20";
    private final ProviderProfile profile;
    private final OkHttpClient http;

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
        Request request = new Request.Builder().url(profile.playlistUrl)
                .header("User-Agent", USER_AGENT).header("Accept", "*/*").build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new Exception("المزوّد رفض M3U (HTTP " + response.code() + ").");
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
            return count;
        } catch (IOException error) {
            throw new Exception("تعذر قراءة M3U مباشرة: " + error.getMessage());
        }
    }

    public String playbackUrl(String id) { return id == null ? "" : id; }

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
