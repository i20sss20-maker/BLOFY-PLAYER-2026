package tv.blofy.player;

import android.net.Uri;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * Deep, bounded HTTP capability probe used by v332.
 * It inspects redirects, range support, content type and first bytes without downloading media.
 */
final class PlaybackCapabilityProbe {
    private static final int CONNECT_TIMEOUT_MS = 1800;
    private static final int READ_TIMEOUT_MS = 1800;
    private static final int SAMPLE_BYTES = 768;

    private PlaybackCapabilityProbe() {}

    static Result probe(String url, String userAgent, String referer, boolean useRange) {
        if (!validUrl(url)) return Result.failure("invalid-url");
        HttpURLConnection connection = null;
        long started = System.currentTimeMillis();
        try {
            URL start = new URL(url);
            connection = (HttpURLConnection) start.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "*/*");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("Connection", "keep-alive");
            if (userAgent != null && !userAgent.isEmpty()) connection.setRequestProperty("User-Agent", userAgent);
            if (referer != null && !referer.isEmpty()) {
                connection.setRequestProperty("Referer", referer);
                try {
                    Uri uri = Uri.parse(referer);
                    if (uri.getScheme() != null && uri.getHost() != null) {
                        String origin = uri.getScheme() + "://" + uri.getHost()
                                + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
                        connection.setRequestProperty("Origin", origin);
                    }
                } catch (Exception ignored) {}
            }
            if (useRange) connection.setRequestProperty("Range", "bytes=0-" + (SAMPLE_BYTES - 1));

            int status = connection.getResponseCode();
            URL end = connection.getURL();
            boolean redirected = end != null && !sameHost(start, end);
            String type = lower(connection.getContentType());
            String acceptRanges = lower(connection.getHeaderField("Accept-Ranges"));
            String contentRange = lower(connection.getHeaderField("Content-Range"));
            boolean range = status == 206 || acceptRanges.contains("bytes") || contentRange.startsWith("bytes");
            String signature = "";
            if (status >= 200 && status < 400) {
                try (InputStream in = connection.getInputStream()) {
                    byte[] buffer = new byte[SAMPLE_BYTES];
                    int read = in.read(buffer);
                    signature = signature(buffer, Math.max(0, read));
                }
            }
            long elapsed = System.currentTimeMillis() - started;
            boolean success = status >= 200 && status < 400;
            return new Result(success, status, redirected, range, type, signature,
                    end == null ? url : end.toString(), elapsed, success ? "ok" : "http-" + status);
        } catch (Exception error) {
            return new Result(false, -1, false, false, "", "", url,
                    System.currentTimeMillis() - started, error.getClass().getSimpleName());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    static String inferFamily(Result result, String fallbackExtension) {
        String type = result == null ? "" : lower(result.contentType);
        String sig = result == null ? "" : lower(result.signature);
        String ext = lower(fallbackExtension).replace(".", "");
        if (type.contains("mpegurl") || type.contains("m3u8") || sig.contains("#extm3u")) return "hls";
        if (type.contains("mp2t") || "ts".equals(ext) || "mpegts".equals(ext)) return "ts";
        if (type.contains("matroska") || "mkv".equals(ext) || sig.contains("matroska")) return "mkv";
        if (type.contains("mp4") || "mp4".equals(ext) || sig.contains("ftyp")) return "mp4";
        return ext.isEmpty() ? "vod" : ext;
    }

    private static boolean validUrl(String value) {
        try {
            Uri uri = Uri.parse(value == null ? "" : value);
            return uri.getHost() != null && ("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()));
        } catch (Exception ignored) { return false; }
    }

    private static boolean sameHost(URL a, URL b) {
        return a.getHost().equalsIgnoreCase(b.getHost()) && a.getProtocol().equalsIgnoreCase(b.getProtocol());
    }

    private static String signature(byte[] bytes, int length) {
        if (length <= 0) return "";
        StringBuilder ascii = new StringBuilder();
        int max = Math.min(length, 96);
        for (int i = 0; i < max; i++) {
            int value = bytes[i] & 0xff;
            ascii.append(value >= 32 && value <= 126 ? (char) value : '.');
        }
        return ascii.toString();
    }

    private static String lower(String value) { return value == null ? "" : value.toLowerCase(Locale.US); }

    static final class Result {
        final boolean success;
        final int statusCode;
        final boolean redirected;
        final boolean rangeSupported;
        final String contentType;
        final String signature;
        final String finalUrl;
        final long elapsedMs;
        final String reason;

        Result(boolean success, int statusCode, boolean redirected, boolean rangeSupported,
               String contentType, String signature, String finalUrl, long elapsedMs, String reason) {
            this.success = success;
            this.statusCode = statusCode;
            this.redirected = redirected;
            this.rangeSupported = rangeSupported;
            this.contentType = contentType == null ? "" : contentType;
            this.signature = signature == null ? "" : signature;
            this.finalUrl = finalUrl == null ? "" : finalUrl;
            this.elapsedMs = elapsedMs;
            this.reason = reason == null ? "" : reason;
        }

        static Result failure(String reason) {
            return new Result(false, -1, false, false, "", "", "", 0L, reason);
        }
    }
}
