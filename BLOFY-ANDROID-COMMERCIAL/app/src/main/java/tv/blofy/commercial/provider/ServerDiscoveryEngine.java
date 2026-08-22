package tv.blofy.commercial.provider;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okio.BufferedSource;

/** Bounded per-playlist provider discovery. Full catalog/media payloads are never downloaded here. */
public final class ServerDiscoveryEngine {
    public interface Listener { void onProgress(int percent); }

    private static final String[] USER_AGENTS = new String[] {
            "Mozilla/5.0 (Linux; Android 11; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36",
            "IPTVSmartersPlayer",
            "Dalvik/2.1.0 (Linux; U; Android 11; Android TV)",
            "VLC/3.0.20 LibVLC/3.0.20"
    };
    private static final long MAX_PROBE_BYTES = 48 * 1024L;
    private static final Pattern STREAM_ID = Pattern.compile("\\\"stream_id\\\"\\s*:\\s*\\\"?([0-9]+)\\\"?");
    private static final Pattern EXTENSION = Pattern.compile("\\\"container_extension\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build();

    public CompatibilityProfile discover(PlaylistProfile playlist, Listener listener) throws Exception {
        if (playlist == null || playlist.provider == null || !playlist.provider.isValid()) {
            throw new IllegalArgumentException("Playlist is not configured.");
        }
        progress(listener, 3);
        return playlist.provider.isM3u()
                ? discoverM3u(playlist, playlist.provider.playlistUrl, listener)
                : discoverXtream(playlist, playlist.provider, listener);
    }

    private CompatibilityProfile discoverXtream(PlaylistProfile playlist, ProviderProfile provider,
                                                 Listener listener) throws Exception {
        progress(listener, 10);
        HttpUrl api = HttpUrl.parse(provider.serverUrl + "/player_api.php");
        if (api == null) throw new Exception("Invalid Xtream URL");
        api = api.newBuilder().addQueryParameter("username", provider.username)
                .addQueryParameter("password", provider.password).build();

        Probe auth = probe(api, "application/json,text/plain,*/*");
        progress(listener, 24);
        if (auth.ok && looksLikeXtream(auth.preview)) {
            Probe liveCategories = probe(api.newBuilder().addQueryParameter("action", "get_live_categories").build(), "application/json,text/plain,*/*");
            progress(listener, 38);
            Probe vod = probe(api.newBuilder().setQueryParameter("action", "get_vod_categories").build(), "application/json,text/plain,*/*");
            progress(listener, 50);
            Probe series = probe(api.newBuilder().setQueryParameter("action", "get_series_categories").build(), "application/json,text/plain,*/*");
            progress(listener, 60);

            Probe liveStreams = probe(api.newBuilder().setQueryParameter("action", "get_live_streams").build(), "application/json,text/plain,*/*");
            String streamId = sampleStreamId(liveStreams.preview);
            String extension = sampleExtension(liveStreams.preview, "ts");
            progress(listener, 70);

            Probe media = new Probe();
            if (!streamId.isEmpty()) {
                HttpUrl sample = HttpUrl.parse(provider.serverUrl + "/live/" + provider.username + "/" + provider.password
                        + "/" + streamId + "." + extension);
                if (sample != null) media = probeMedia(sample, firstNonEmpty(liveStreams.userAgent, auth.userAgent));
            }
            progress(listener, 82);

            boolean epgSupported = false;
            if (!streamId.isEmpty()) {
                HttpUrl epg = api.newBuilder()
                        .setQueryParameter("action", "get_short_epg")
                        .addQueryParameter("stream_id", streamId)
                        .addQueryParameter("limit", "1").build();
                Probe epgProbe = probe(epg, "application/json,text/plain,*/*");
                epgSupported = epgProbe.ok && (epgProbe.preview.contains("epg_listings") || epgProbe.preview.startsWith("{"));
            }
            progress(listener, 92);

            String ua = firstNonEmpty(media.userAgent, liveStreams.userAgent, liveCategories.userAgent, auth.userAgent);
            String finalHost = firstNonEmpty(media.finalHost, auth.finalHost, host(provider.serverUrl));
            String liveProtocol = media.ok ? inferMediaProtocol(media.finalUrl, media.contentType, extension) : normalizeProtocol(extension);
            boolean catalogueHealthy = liveCategories.ok || vod.ok || series.ok || liveStreams.ok;
            progress(listener, 100);
            return new CompatibilityProfile(playlist.id, "xtream", ua,
                    firstNonEmpty(media.origin, auth.origin), firstNonEmpty(media.referer, auth.referer),
                    finalHost, liveProtocol, "mp4", epgSupported || catalogueHealthy,
                    "media3", "libvlc", auth.status, System.currentTimeMillis());
        }

        progress(listener, 32);
        Probe m3u = probeXtreamPlaylistVariants(provider, listener);
        progress(listener, 68);
        if (!m3u.ok || !looksLikeM3u(m3u.preview)) {
            int status = m3u.status > 0 ? m3u.status : auth.status;
            throw new DiscoveryException(status, "Provider rejected Xtream API and playlist compatibility probes.");
        }

        HttpUrl sampleUrl = firstPlaylistUrl(m3u);
        Probe media = sampleUrl == null ? new Probe() : probeMedia(sampleUrl, m3u.userAgent);
        progress(listener, 86);
        String protocol = media.ok ? inferMediaProtocol(media.finalUrl, media.contentType, inferProtocol(m3u.preview))
                : inferProtocol(m3u.preview);
        progress(listener, 100);
        return new CompatibilityProfile(playlist.id, "m3u-fallback", firstNonEmpty(media.userAgent, m3u.userAgent),
                firstNonEmpty(media.origin, m3u.origin), firstNonEmpty(media.referer, m3u.referer),
                firstNonEmpty(media.finalHost, m3u.finalHost), protocol, "mp4", false,
                "media3", "libvlc", m3u.status, System.currentTimeMillis());
    }

    /** Try common Xtream playlist forms. No Range header: several panels/CDNs reject Range on get.php. */
    private Probe probeXtreamPlaylistVariants(ProviderProfile provider, Listener listener) {
        HttpUrl base = HttpUrl.parse(provider.serverUrl + "/get.php");
        if (base == null) return new Probe();
        String[][] variants = new String[][] {
                {"m3u_plus", "ts"}, {"m3u_plus", "m3u8"}, {"m3u", "ts"}, {"m3u", "m3u8"}
        };
        Probe best = new Probe();
        for (int i = 0; i < variants.length; i++) {
            HttpUrl url = base.newBuilder()
                    .addQueryParameter("username", provider.username)
                    .addQueryParameter("password", provider.password)
                    .addQueryParameter("type", variants[i][0])
                    .addQueryParameter("output", variants[i][1]).build();
            Probe current = probe(url, "application/x-mpegURL,text/plain,*/*");
            best = current;
            progress(listener, 38 + (i * 6));
            if (current.ok && looksLikeM3u(current.preview)) return current;
        }
        return best;
    }

    private CompatibilityProfile discoverM3u(PlaylistProfile playlist, String url, Listener listener) throws Exception {
        progress(listener, 12);
        HttpUrl target = HttpUrl.parse(url);
        if (target == null) throw new Exception("Invalid M3U URL");
        Probe m3u = probe(target, "application/x-mpegURL,text/plain,*/*");
        progress(listener, 54);
        if (!m3u.ok || !looksLikeM3u(m3u.preview)) {
            throw new DiscoveryException(m3u.status, "M3U compatibility probe failed.");
        }
        HttpUrl sample = firstPlaylistUrl(m3u);
        Probe media = sample == null ? new Probe() : probeMedia(sample, m3u.userAgent);
        progress(listener, 84);
        String protocol = media.ok ? inferMediaProtocol(media.finalUrl, media.contentType, inferProtocol(m3u.preview))
                : inferProtocol(m3u.preview);
        progress(listener, 100);
        return new CompatibilityProfile(playlist.id, "m3u", firstNonEmpty(media.userAgent, m3u.userAgent),
                firstNonEmpty(media.origin, m3u.origin), firstNonEmpty(media.referer, m3u.referer),
                firstNonEmpty(media.finalHost, m3u.finalHost), protocol, protocol, false,
                "media3", "libvlc", m3u.status, System.currentTimeMillis());
    }

    private Probe probe(HttpUrl url, String accept) {
        Probe last = new Probe();
        for (String ua : USER_AGENTS) {
            Probe current = execute(url, accept, ua, false);
            last = current;
            if (current.ok) return current;
            if (current.status != 403 && current.status != 406 && current.status != 416 && current.status != -1) return current;
        }
        return last;
    }

    private Probe probeMedia(HttpUrl url, String preferredUa) {
        List<String> agents = new ArrayList<>();
        if (preferredUa != null && !preferredUa.trim().isEmpty()) agents.add(preferredUa.trim());
        for (String ua : USER_AGENTS) if (!agents.contains(ua)) agents.add(ua);
        Probe last = new Probe();
        for (String ua : agents) {
            Probe current = execute(url, "*/*", ua, true);
            last = current;
            if (current.ok) return current;
            if (current.status != 403 && current.status != 406 && current.status != 416 && current.status != -1) return current;
        }
        return last;
    }

    private Probe execute(HttpUrl url, String accept, String ua, boolean media) {
        Request.Builder request = new Request.Builder().url(url)
                .header("User-Agent", ua).header("Accept", accept)
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cache-Control", "no-cache").header("Pragma", "no-cache");
        String origin = origin(url);
        String referer = origin.isEmpty() ? "" : origin + "/";
        if (ua.startsWith("Mozilla/") && !origin.isEmpty()) {
            request.header("Origin", origin).header("Referer", referer);
        } else if ("IPTVSmartersPlayer".equals(ua)) {
            request.header("X-Requested-With", "com.nst.iptvsmarterstvbox");
        }
        if (media) request.header("Range", "bytes=0-2047");

        Probe current = new Probe();
        current.userAgent = ua;
        current.origin = ua.startsWith("Mozilla/") ? origin : "";
        current.referer = ua.startsWith("Mozilla/") ? referer : "";
        try (Response response = http.newCall(request.build()).execute()) {
            current.status = response.code();
            current.finalHost = response.request().url().host();
            current.finalUrl = response.request().url().toString();
            current.contentType = response.header("Content-Type", "");
            current.ok = response.isSuccessful();
            if (!media && response.body() != null) current.preview = readPreview(response.body().source());
            return current;
        } catch (IOException networkError) {
            current.status = -1;
            current.ok = false;
            current.finalHost = url.host();
            current.finalUrl = url.toString();
            current.networkError = networkError.getClass().getSimpleName();
            return current;
        }
    }

    private static HttpUrl firstPlaylistUrl(Probe playlist) {
        if (playlist == null || playlist.preview == null) return null;
        HttpUrl base = HttpUrl.parse(playlist.finalUrl);
        String[] lines = playlist.preview.replace("\uFEFF", "").split("\\r?\\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            HttpUrl direct = HttpUrl.parse(line);
            if (direct != null) return direct;
            if (base != null) {
                HttpUrl resolved = base.resolve(line);
                if (resolved != null) return resolved;
            }
        }
        return null;
    }

    private static String sampleStreamId(String preview) {
        if (preview == null) return "";
        Matcher match = STREAM_ID.matcher(preview);
        return match.find() ? match.group(1) : "";
    }

    private static String sampleExtension(String preview, String fallback) {
        if (preview != null) {
            Matcher match = EXTENSION.matcher(preview);
            if (match.find()) return match.group(1).replaceAll("[^A-Za-z0-9]", "").toLowerCase();
        }
        return fallback;
    }

    private static String readPreview(BufferedSource source) throws IOException {
        source.request(MAX_PROBE_BYTES);
        long size = Math.min(source.buffer().size(), MAX_PROBE_BYTES);
        return size <= 0 ? "" : source.buffer().clone().readUtf8(size);
    }

    private static boolean looksLikeXtream(String value) {
        if (value == null || value.isEmpty()) return false;
        try {
            JSONObject root = new JSONObject(value);
            return root.has("user_info") || root.has("server_info");
        } catch (Exception ignored) {
            return value.contains("\"user_info\"") || value.contains("\"server_info\"");
        }
    }

    private static boolean looksLikeM3u(String value) {
        if (value == null) return false;
        String normalized = value.startsWith("\uFEFF") ? value.substring(1) : value;
        return normalized.startsWith("#EXTM3U") || normalized.contains("#EXTINF");
    }

    private static String inferProtocol(String preview) {
        String value = preview == null ? "" : preview.toLowerCase();
        if (value.contains(".m3u8")) return "hls";
        if (value.contains(".mpd")) return "dash";
        if (value.contains(".mp4")) return "mp4";
        return "ts";
    }

    private static String inferMediaProtocol(String finalUrl, String contentType, String fallback) {
        String url = finalUrl == null ? "" : finalUrl.toLowerCase();
        String mime = contentType == null ? "" : contentType.toLowerCase();
        if (url.contains(".m3u8") || mime.contains("mpegurl")) return "hls";
        if (url.contains(".mpd") || mime.contains("dash")) return "dash";
        if (url.contains(".mp4") || mime.contains("video/mp4")) return "mp4";
        if (url.contains(".ts") || mime.contains("mp2t") || mime.contains("mpegts")) return "ts";
        return normalizeProtocol(fallback);
    }

    private static String normalizeProtocol(String value) {
        String v = value == null ? "" : value.toLowerCase();
        if ("m3u8".equals(v) || "hls".equals(v)) return "hls";
        if ("mpd".equals(v) || "dash".equals(v)) return "dash";
        if ("mp4".equals(v)) return "mp4";
        return "ts";
    }

    private static String firstNonEmpty(String... values) {
        if (values != null) for (String value : values) if (value != null && !value.trim().isEmpty()) return value.trim();
        return "";
    }

    private static String host(String url) { HttpUrl parsed = HttpUrl.parse(url); return parsed == null ? "" : parsed.host(); }
    private static String origin(HttpUrl url) {
        if (url == null) return "";
        boolean defaultPort = ("http".equals(url.scheme()) && url.port() == 80)
                || ("https".equals(url.scheme()) && url.port() == 443);
        return url.scheme() + "://" + url.host() + (defaultPort ? "" : ":" + url.port());
    }
    private static void progress(Listener listener, int value) {
        if (listener != null) listener.onProgress(Math.max(0, Math.min(100, value)));
    }

    private static final class Probe {
        int status = -1; boolean ok; String userAgent = ""; String origin = "";
        String referer = ""; String finalHost = ""; String finalUrl = "";
        String contentType = ""; String preview = ""; String networkError = "";
    }

    public static final class DiscoveryException extends Exception {
        public final int httpStatus;
        DiscoveryException(int httpStatus, String message) { super(message); this.httpStatus = httpStatus; }
    }
}
