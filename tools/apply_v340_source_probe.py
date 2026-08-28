#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player"
PREFLIGHT = JAVA / "ServerCompatibilityPreflight.java"

PREFLIGHT.write_text(r'''package tv.blofy.player;

import android.content.Context;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Provider-scoped compatibility gate executed only after the complete catalog is
 * committed locally. It resolves the same canonical/direct/no-extension routes
 * used by the player and performs a bounded byte-level source probe. No full
 * media file is downloaded and no BLOFY credentials are forwarded to providers.
 */
final class ServerCompatibilityPreflight {
    private static final String PREFS = "blofy_compatibility_preflight";
    private static final int SAMPLE_COUNT = 2;
    private static final int MAX_PROBE_BYTES = 64 * 1024;
    private static final int MAX_REDIRECTS = 5;
    private static final long ITEM_BUDGET_MS = 22_000L;

    static final class Result {
        final int liveScore, moviesScore, seriesScore, tested, playable, inconclusive;
        final String summary, report;

        Result(int liveScore, int moviesScore, int seriesScore, int tested,
               int playable, int inconclusive, String summary, String report) {
            this.liveScore = liveScore;
            this.moviesScore = moviesScore;
            this.seriesScore = seriesScore;
            this.tested = tested;
            this.playable = playable;
            this.inconclusive = inconclusive;
            this.summary = summary;
            this.report = report;
        }

        /** Reject only when every tested family produced a definitive hard failure. */
        boolean completeFailure() {
            return tested > 0 && playable == 0 && inconclusive == 0;
        }
    }

    private static final class FamilyResult {
        int playable;
        int tested;
        int inconclusive;
    }

    private static final class Candidate {
        final String kind;
        final String id;
        final String extension;

        Candidate(String kind, String id, String extension) {
            this.kind = kind;
            this.id = id;
            this.extension = extension;
        }
    }

    private static final class Probe {
        final boolean ok;
        final boolean hardFailure;
        final int status;
        final String contentType;
        final int bytes;
        final int redirects;
        final String userAgent;
        final String referer;
        final String reason;
        final String finalHost;

        Probe(boolean ok, boolean hardFailure, int status, String contentType,
              int bytes, int redirects, String userAgent, String referer,
              String reason, String finalHost) {
            this.ok = ok;
            this.hardFailure = hardFailure;
            this.status = status;
            this.contentType = safe(contentType);
            this.bytes = bytes;
            this.redirects = redirects;
            this.userAgent = safe(userAgent);
            this.referer = safe(referer);
            this.reason = safe(reason);
            this.finalHost = safe(finalHost);
        }
    }

    private ServerCompatibilityPreflight() {}

    static Result run(Context context, BlofyApi api, CatalogDatabase database,
                      String playlistId) {
        StringBuilder report = new StringBuilder(8192);
        report.append("BLOFY Server Compatibility Preflight\n");
        report.append("playlist=").append(safe(playlistId)).append('\n');
        report.append("mode=full-catalog-then-bounded-source-probe\n");

        FamilyResult live = testFamily(context, api, database, "live", report);
        FamilyResult movies = testFamily(context, api, database, "movies", report);
        FamilyResult series = testFamily(context, api, database, "series", report);

        int tested = live.tested + movies.tested + series.tested;
        int playable = live.playable + movies.playable + series.playable;
        int inconclusive = live.inconclusive + movies.inconclusive + series.inconclusive;
        int liveScore = score(live.playable, live.tested);
        int moviesScore = score(movies.playable, movies.tested);
        int seriesScore = score(series.playable, series.tested);
        String summary = "Live " + liveScore + "% • Movies " + moviesScore
                + "% • Series " + seriesScore + "%"
                + (inconclusive > 0 ? " • غير مؤكد " + inconclusive : "");
        report.append("summary=").append(summary).append('\n');
        report.append("tested=").append(tested)
                .append(" playable=").append(playable)
                .append(" inconclusive=").append(inconclusive).append('\n');

        String key = key(playlistId);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(key + ":summary", summary)
                .putString(key + ":report", report.toString())
                .putInt(key + ":live", liveScore)
                .putInt(key + ":movies", moviesScore)
                .putInt(key + ":series", seriesScore)
                .putInt(key + ":inconclusive", inconclusive)
                .putLong(key + ":at", System.currentTimeMillis())
                .apply();
        PlaybackDiagnostics.marker(context, "compatibility-preflight", "server",
                safe(playlistId), "", "source-probe",
                summary + " tested=" + tested + " playable=" + playable
                        + " inconclusive=" + inconclusive);
        return new Result(liveScore, moviesScore, seriesScore, tested, playable,
                inconclusive, summary, report.toString());
    }

    static String savedSummary(Context context, String playlistId) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(key(playlistId) + ":summary", "لم يتم الفحص بعد");
    }

    static String savedReport(Context context, String playlistId) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(key(playlistId) + ":report",
                        "لا يوجد تقرير توافق محفوظ بعد.");
    }

    private static FamilyResult testFamily(Context context, BlofyApi api,
                                           CatalogDatabase database, String type,
                                           StringBuilder report) {
        FamilyResult result = new FamilyResult();
        List<BlofyModels.Media> items = spreadSamples(database, type);
        if (items.isEmpty()) {
            report.append(type).append(": no-samples\n");
            return result;
        }
        for (BlofyModels.Media item : items) {
            result.tested++;
            int outcome = testItem(context, api, type, item, report);
            if (outcome > 0) result.playable++;
            else if (outcome == 0) result.inconclusive++;
        }
        return result;
    }

    /** Samples the beginning and a distant part of large packages. */
    private static List<BlofyModels.Media> spreadSamples(CatalogDatabase database,
                                                         String type) {
        Map<String, BlofyModels.Media> unique = new LinkedHashMap<>();
        int total = Math.max(0, database.count(type));
        int[] offsets = total <= 1
                ? new int[]{0}
                : new int[]{0, Math.max(0, total / 2)};
        for (int offset : offsets) {
            List<BlofyModels.Media> page = database.media(
                    type, "", "", false, false, 1, offset);
            if (page != null && !page.isEmpty()) {
                BlofyModels.Media item = page.get(0);
                if (item != null && !safe(item.id).isEmpty()) unique.put(item.id, item);
            }
            if (unique.size() >= SAMPLE_COUNT) break;
        }
        return new ArrayList<>(unique.values());
    }

    /** Returns 1 playable, 0 inconclusive/timeout, -1 definitive failure. */
    private static int testItem(Context context, BlofyApi api, String type,
                                BlofyModels.Media item, StringBuilder report) {
        long itemStarted = SystemClock.elapsedRealtime();
        long deadline = itemStarted + ITEM_BUDGET_MS;
        Candidate candidate;
        try {
            candidate = candidate(api, type, item);
        } catch (Exception error) {
            String reason = error.getClass().getSimpleName() + ":" + safe(error.getMessage());
            report.append(type).append('/').append(safe(item.id))
                    .append(" INCONCLUSIVE candidate=").append(redact(reason)).append('\n');
            PlaybackDiagnostics.failure(context, "preflight-" + type, type,
                    safe(item.id), safe(item.extension), "candidate", itemStarted, 0, error);
            return 0;
        }
        if (candidate == null || safe(candidate.id).isEmpty()) {
            report.append(type).append('/').append(safe(item.id))
                    .append(" INCONCLUSIVE no-playable-episode\n");
            return 0;
        }

        String baseExt = PlaybackPolicy.normalizeExtension(candidate.extension,
                "live".equals(type) ? "ts" : "mp4");
        Set<String> extensions = new LinkedHashSet<>();
        extensions.add(baseExt);
        if ("live".equals(type)) {
            extensions.add(PlaybackPolicy.alternateLiveExtension(baseExt));
        }
        String[] variants = {"canonical", "direct", "no-extension"};
        boolean sawInconclusive = false;
        String lastReason = "no-route";

        for (String extension : extensions) {
            for (String variant : variants) {
                if (SystemClock.elapsedRealtime() >= deadline) {
                    sawInconclusive = true;
                    lastReason = "item-budget-exhausted";
                    break;
                }
                long routeStarted = PlaybackDiagnostics.start(context,
                        "preflight-" + type, candidate.kind, candidate.id,
                        extension, variant);
                try {
                    JSONObject data = api.getPlayback("/api/native-link/"
                            + BlofyApi.encode(candidate.kind) + "/"
                            + BlofyApi.encode(candidate.id) + "?ext="
                            + BlofyApi.encode(extension) + "&variant="
                            + BlofyApi.encode(variant), new BlofyApi.Cancellation());
                    String url = data.optString("url", "");
                    String resolvedExt = PlaybackPolicy.normalizeExtension(
                            data.optString("extension", extension), extension);
                    if (!(url.startsWith("http://") || url.startsWith("https://"))) {
                        lastReason = "invalid-provider-url";
                        continue;
                    }

                    Probe probe = probeWithCompatibilityProfiles(url, data,
                            resolvedExt, deadline);
                    long elapsed = Math.max(0L,
                            SystemClock.elapsedRealtime() - routeStarted);
                    report.append(type).append('/').append(candidate.id)
                            .append(probe.ok ? " OK" : (probe.hardFailure ? " FAIL" : " INCONCLUSIVE"))
                            .append(" route=").append(variant)
                            .append(" ext=").append(resolvedExt)
                            .append(" http=").append(probe.status)
                            .append(" type=").append(cleanType(probe.contentType))
                            .append(" bytes=").append(probe.bytes)
                            .append(" redirects=").append(probe.redirects)
                            .append(" host=").append(probe.finalHost)
                            .append(" elapsed_ms=").append(elapsed)
                            .append(" reason=").append(redact(probe.reason)).append('\n');

                    if (probe.ok) {
                        PlaybackDiagnostics.success(context, "preflight-" + type,
                                candidate.kind, candidate.id, resolvedExt, variant,
                                routeStarted, "HTTP " + probe.status + " • "
                                        + cleanType(probe.contentType) + " • bytes="
                                        + probe.bytes + " • redirects=" + probe.redirects);
                        ServerPlaybackProfile.rememberSuccess(context, url,
                                "live".equals(type) ? resolvedExt : "", variant,
                                "source-probe", probe.userAgent, probe.referer);
                        return 1;
                    }
                    lastReason = probe.reason;
                    if (!probe.hardFailure) sawInconclusive = true;
                    PlaybackDiagnostics.failure(context, "preflight-" + type,
                            candidate.kind, candidate.id, resolvedExt, variant,
                            routeStarted, probe.status,
                            new Exception(probe.reason + " type=" + probe.contentType));
                } catch (SocketTimeoutException timeout) {
                    sawInconclusive = true;
                    lastReason = "source-timeout";
                    PlaybackDiagnostics.failure(context, "preflight-" + type,
                            candidate.kind, candidate.id, extension, variant,
                            routeStarted, 0, timeout);
                } catch (Exception error) {
                    lastReason = error.getClass().getSimpleName() + ":"
                            + safe(error.getMessage());
                    if (isInconclusive(error)) sawInconclusive = true;
                    PlaybackDiagnostics.failure(context, "preflight-" + type,
                            candidate.kind, candidate.id, extension, variant,
                            routeStarted, error instanceof BlofyApi.ApiException
                                    ? ((BlofyApi.ApiException) error).status : 0,
                            error);
                }
            }
            if (SystemClock.elapsedRealtime() >= deadline) break;
        }

        long elapsed = Math.max(0L, SystemClock.elapsedRealtime() - itemStarted);
        report.append(type).append('/').append(candidate.id)
                .append(sawInconclusive ? " INCONCLUSIVE " : " FAIL ")
                .append(redact(lastReason)).append(" elapsed_ms=")
                .append(elapsed).append('\n');
        return sawInconclusive ? 0 : -1;
    }

    private static Candidate candidate(BlofyApi api, String type,
                                       BlofyModels.Media item) throws Exception {
        if (!"series".equals(type)) {
            return new Candidate(type, item.id, item.extension);
        }
        BlofyModels.Detail detail = new BlofyModels.Detail(
                api.get("/api/series/" + BlofyApi.encode(item.id)), "series");
        for (BlofyModels.Season season : detail.seasons) {
            if (season == null) continue;
            for (BlofyModels.Episode episode : season.episodes) {
                if (episode != null && !safe(episode.id).isEmpty()) {
                    return new Candidate("episode", episode.id,
                            safe(episode.extension).isEmpty() ? "mp4" : episode.extension);
                }
            }
        }
        return null;
    }

    private static Probe probeWithCompatibilityProfiles(String url, JSONObject data,
                                                         String extension,
                                                         long deadline) {
        Probe last = null;
        int[] profiles = {0, 1, 2};
        for (int profile : profiles) {
            if (SystemClock.elapsedRealtime() >= deadline) {
                return last == null
                        ? new Probe(false, false, 0, "", 0, 0, "", "",
                        "item-budget-exhausted", host(url))
                        : last;
            }
            Probe current = probeSource(url, data, extension, profile, deadline);
            last = current;
            if (current.ok) return current;
            if (!(current.status == 401 || current.status == 403
                    || current.status == 406 || current.status == 429
                    || "html-or-json-error".equals(current.reason))) {
                break;
            }
        }
        return last == null
                ? new Probe(false, false, 0, "", 0, 0, "", "",
                "probe-not-run", host(url))
                : last;
    }

    private static Probe probeSource(String originalUrl, JSONObject data,
                                     String extension, int compatibilityProfile,
                                     long deadline) {
        String current = originalUrl;
        int redirects = 0;
        String userAgent = suppliedHeader(data, "User-Agent");
        if (userAgent.isEmpty()) {
            userAgent = PlaybackTransportFactory.userAgent(compatibilityProfile);
        }
        String referer = firstNonEmpty(data.optString("referer", ""),
                suppliedHeader(data, "Referer"));

        while (redirects <= MAX_REDIRECTS) {
            HttpURLConnection connection = null;
            try {
                int remaining = remaining(deadline);
                if (remaining <= 0) {
                    return new Probe(false, false, 0, "", 0, redirects,
                            userAgent, referer, "item-budget-exhausted", host(current));
                }
                connection = (HttpURLConnection) new URL(current).openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(Math.max(500, Math.min(3_500, remaining)));
                connection.setReadTimeout(Math.max(700, Math.min(4_500, remaining)));
                connection.setUseCaches(false);
                connection.setRequestProperty("User-Agent", userAgent);
                connection.setRequestProperty("Accept", "*/*");
                connection.setRequestProperty("Accept-Encoding", "identity");
                connection.setRequestProperty("Connection", "close");
                if (!referer.isEmpty()) {
                    connection.setRequestProperty("Referer", referer);
                    String origin = origin(referer);
                    if (!origin.isEmpty()) connection.setRequestProperty("Origin", origin);
                }
                applySuppliedHeaders(connection, data);
                if (!PlaybackPolicy.isHls(extension)) {
                    connection.setRequestProperty("Range", "bytes=0-65535");
                }

                int status = connection.getResponseCode();
                String contentType = safe(connection.getContentType());
                if (status >= 300 && status < 400) {
                    String location = safe(connection.getHeaderField("Location"));
                    if (location.isEmpty()) {
                        return new Probe(false, true, status, contentType, 0,
                                redirects, userAgent, referer,
                                "redirect-without-location", host(current));
                    }
                    current = new URL(new URL(current), location).toString();
                    redirects++;
                    continue;
                }
                if (status < 200 || status >= 300) {
                    return new Probe(false, isHardHttp(status), status,
                            contentType, 0, redirects, userAgent, referer,
                            "http-" + status, host(current));
                }

                InputStream input = connection.getInputStream();
                byte[] bytes = readBounded(input, MAX_PROBE_BYTES, deadline);
                String reason = validateBytes(bytes, contentType, extension);
                boolean ok = reason.isEmpty();
                boolean hard = !ok && "html-or-json-error".equals(reason);
                return new Probe(ok, hard, status, contentType, bytes.length,
                        redirects, userAgent, referer,
                        ok ? "source-readable" : reason, host(current));
            } catch (SocketTimeoutException timeout) {
                return new Probe(false, false, 0, "", 0, redirects,
                        userAgent, referer, "source-timeout", host(current));
            } catch (Exception error) {
                return new Probe(false, false, 0, "", 0, redirects,
                        userAgent, referer,
                        error.getClass().getSimpleName() + ":" + safe(error.getMessage()),
                        host(current));
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        return new Probe(false, true, 310, "", 0, redirects,
                userAgent, referer, "too-many-redirects", host(current));
    }

    private static byte[] readBounded(InputStream input, int limit,
                                      long deadline) throws Exception {
        try (InputStream source = input;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8_192];
            while (output.size() < limit) {
                if (remaining(deadline) <= 0) {
                    throw new SocketTimeoutException("source-probe-budget");
                }
                int read = source.read(buffer, 0,
                        Math.min(buffer.length, limit - output.size()));
                if (read < 0) break;
                if (read > 0) output.write(buffer, 0, read);
                if (output.size() >= 1_024) break;
            }
            return output.toByteArray();
        }
    }

    private static String validateBytes(byte[] bytes, String contentType,
                                        String extension) {
        if (bytes == null || bytes.length == 0) return "empty-source";
        String type = safe(contentType).toLowerCase(Locale.US);
        String prefix = new String(bytes, 0, Math.min(bytes.length, 512),
                StandardCharsets.ISO_8859_1).trim().toLowerCase(Locale.US);
        boolean hls = PlaybackPolicy.isHls(extension)
                || type.contains("mpegurl") || prefix.contains("#extm3u");
        if (hls) return prefix.contains("#extm3u") ? "" : "invalid-hls-manifest";
        if (type.contains("text/html") || type.contains("application/json")
                || prefix.startsWith("<html") || prefix.startsWith("<!doctype")
                || prefix.startsWith("{") || prefix.startsWith("[")) {
            return "html-or-json-error";
        }
        if (PlaybackPolicy.isTransportStream(extension)) {
            if (hasTsSync(bytes) || type.contains("mp2t")
                    || type.contains("octet-stream")) return "";
            return "unrecognized-transport-stream";
        }
        String ext = safe(extension).toLowerCase(Locale.US).replace(".", "");
        if ("mp4".equals(ext) || "m4v".equals(ext) || "mov".equals(ext)) {
            if (containsAscii(bytes, "ftyp") || type.contains("video/mp4")
                    || type.contains("octet-stream")) return "";
            return "unrecognized-mp4";
        }
        if ("mkv".equals(ext) || "webm".equals(ext)) {
            if (hasEbml(bytes) || type.startsWith("video/")
                    || type.contains("octet-stream")) return "";
            return "unrecognized-ebml";
        }
        if (type.startsWith("video/") || type.startsWith("audio/")
                || type.contains("octet-stream") || bytes.length >= 188) return "";
        return "unrecognized-source";
    }

    private static boolean hasTsSync(byte[] bytes) {
        for (int offset : new int[]{0, 188, 376}) {
            if (bytes.length > offset && (bytes[offset] & 0xff) == 0x47) return true;
        }
        return false;
    }

    private static boolean hasEbml(byte[] bytes) {
        return bytes.length >= 4
                && (bytes[0] & 0xff) == 0x1a
                && (bytes[1] & 0xff) == 0x45
                && (bytes[2] & 0xff) == 0xdf
                && (bytes[3] & 0xff) == 0xa3;
    }

    private static boolean containsAscii(byte[] bytes, String value) {
        byte[] target = value.getBytes(StandardCharsets.US_ASCII);
        int limit = Math.min(bytes.length, 64);
        for (int start = 0; start + target.length <= limit; start++) {
            boolean match = true;
            for (int index = 0; index < target.length; index++) {
                if (bytes[start + index] != target[index]) { match = false; break; }
            }
            if (match) return true;
        }
        return false;
    }

    private static void applySuppliedHeaders(HttpURLConnection connection,
                                             JSONObject data) {
        JSONObject headers = data == null ? null : data.optJSONObject("headers");
        if (headers == null) return;
        String[] allowed = {"Referer", "Origin", "Cookie", "Authorization",
                "Accept-Language", "Icy-MetaData"};
        for (String name : allowed) {
            String value = headers.optString(name, "");
            if (value.isEmpty()) value = headers.optString(name.toLowerCase(Locale.US), "");
            if (!value.isEmpty()) connection.setRequestProperty(name, value);
        }
    }

    private static String suppliedHeader(JSONObject data, String name) {
        JSONObject headers = data == null ? null : data.optJSONObject("headers");
        if (headers == null) return "";
        String value = headers.optString(name, "");
        return value.isEmpty()
                ? headers.optString(name.toLowerCase(Locale.US), "") : value;
    }

    private static int remaining(long deadline) {
        return (int) Math.max(0L,
                Math.min(Integer.MAX_VALUE, deadline - SystemClock.elapsedRealtime()));
    }

    private static boolean isHardHttp(int status) {
        return status == 400 || status == 401 || status == 403
                || status == 404 || status == 405 || status == 410
                || status == 415 || status == 451;
    }

    private static boolean isInconclusive(Throwable error) {
        if (error == null) return true;
        if (error instanceof SocketTimeoutException
                || error instanceof java.io.InterruptedIOException
                || error instanceof java.net.ConnectException
                || error instanceof java.net.UnknownHostException
                || error instanceof java.net.SocketException) return true;
        if (error instanceof BlofyApi.ApiException) {
            int status = ((BlofyApi.ApiException) error).status;
            return status == 0 || status == 408 || status == 429 || status >= 500;
        }
        return true;
    }

    private static int score(int ok, int tested) {
        return tested <= 0 ? 0 : Math.round((ok * 100f) / tested);
    }

    private static String key(String playlistId) {
        return Integer.toHexString(safe(playlistId).trim()
                .toLowerCase(Locale.US).hashCode());
    }

    private static String origin(String value) {
        try {
            URL url = new URL(value);
            int port = url.getPort();
            return url.getProtocol() + "://" + url.getHost()
                    + (port > 0 ? ":" + port : "");
        } catch (Exception ignored) { return ""; }
    }

    private static String host(String value) {
        try { return safe(new URL(value).getHost()); }
        catch (Exception ignored) { return "unknown"; }
    }

    private static String cleanType(String value) {
        String type = safe(value);
        int semicolon = type.indexOf(';');
        return semicolon > 0 ? type.substring(0, semicolon) : type;
    }

    private static String firstNonEmpty(String first, String second) {
        return !safe(first).isEmpty() ? first : safe(second);
    }

    private static String redact(String value) {
        return safe(value)
                .replaceAll("(?i)(username|user|password|pass|token|auth)=([^&\\s]+)",
                        "$1=<redacted>")
                .replaceAll("(?i)(Bearer\\s+)[A-Za-z0-9._~-]+",
                        "$1<redacted>");
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
''', encoding="utf-8")

text = PREFLIGHT.read_text(encoding="utf-8")
for token in [
    "probeWithCompatibilityProfiles", "probeSource", "MAX_PROBE_BYTES",
    "source-readable", "html-or-json-error", "completeFailure()",
    "PlaybackTransportFactory.userAgent", "ServerPlaybackProfile.rememberSuccess",
    "inconclusive"
]:
    if token not in text:
        raise SystemExit("v340 source probe invariant missing: " + token)

print("v340 source probe applied: byte-level provider validation + safe hard-failure gate")
