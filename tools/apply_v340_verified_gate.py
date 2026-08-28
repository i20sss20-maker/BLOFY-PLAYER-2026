#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player"
PROFILE = JAVA / "ServerPlaybackProfile.java"
PREFLIGHT = JAVA / "ServerCompatibilityPreflight.java"
PACKAGE = JAVA / "PackageImporter.java"
PLAYER = JAVA / "PlayerActivity.java"
VOD = JAVA / "VodPlayerActivity.java"
PREVIEW = JAVA / "LivePreviewController.java"
POLICY = JAVA / "PlaybackPolicy.java"
SEVEN = JAVA / "SevenMaxActivity.java"

PROFILE.write_text(r'''package tv.blofy.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Verified playback profile scoped to the active playlist/login + content kind + device class.
 * A profile is NEVER considered usable merely because a URL resolved or a byte probe succeeded.
 */
final class ServerPlaybackProfile {
    private static final String PREFS = "blofy_server_playback_profiles_v2";
    private static final long STALE_MS = 30L * 24L * 60L * 60L * 1000L;

    static final class Profile {
        final String preferredLiveExtension;
        final String preferredRoute;
        final String preferredEngine;
        final String userAgent;
        final String referer;
        final long updatedAt;
        final boolean verified;
        final String rejectedRoutes;
        final long firstFrameMs;

        Profile(String liveExtension, String route, String engine, String userAgent,
                String referer, long updatedAt, boolean verified,
                String rejectedRoutes, long firstFrameMs) {
            this.preferredLiveExtension = safe(liveExtension);
            this.preferredRoute = safe(route);
            this.preferredEngine = safe(engine);
            this.userAgent = safe(userAgent);
            this.referer = safe(referer);
            this.updatedAt = updatedAt;
            this.verified = verified;
            this.rejectedRoutes = safe(rejectedRoutes);
            this.firstFrameMs = firstFrameMs;
        }

        boolean fresh() {
            return verified && updatedAt > 0 && System.currentTimeMillis() - updatedAt < STALE_MS;
        }

        boolean routeRejected(String route) {
            if (route == null || route.isEmpty()) return false;
            for (String value : rejectedRoutes.split(",")) {
                if (route.equals(value.trim())) return true;
            }
            return false;
        }
    }

    private ServerPlaybackProfile() {}

    /** Legacy overload intentionally returns an unverified profile so old host-only learning cannot steer playback. */
    static Profile load(Context context, String url) {
        return empty();
    }

    static Profile load(Context context, String url, String kind) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String key = key(context, url, kind);
        return new Profile(
                prefs.getString(key + ".live_ext", ""),
                prefs.getString(key + ".route", ""),
                prefs.getString(key + ".engine", ""),
                prefs.getString(key + ".ua", ""),
                prefs.getString(key + ".referer", ""),
                prefs.getLong(key + ".updated", 0L),
                prefs.getBoolean(key + ".verified", false),
                prefs.getString(key + ".rejected", ""),
                prefs.getLong(key + ".first_frame_ms", -1L));
    }

    /** Legacy overload retained for source compatibility; it does not mark the route verified. */
    static void rememberSuccess(Context context, String url, String extension, String route,
                                String engine, String userAgent, String referer) {
        rememberCandidate(context, url, "generic", extension, route, engine, userAgent, referer);
    }

    static void rememberVerifiedSuccess(Context context, String url, String kind,
                                        String extension, String route, String engine,
                                        String userAgent, String referer, long firstFrameMs) {
        String key = key(context, url, kind);
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        if (!safe(extension).isEmpty()) editor.putString(key + ".live_ext", extension);
        if (!safe(route).isEmpty()) editor.putString(key + ".route", route);
        if (!safe(engine).isEmpty()) editor.putString(key + ".engine", engine);
        if (!safe(userAgent).isEmpty()) editor.putString(key + ".ua", userAgent);
        if (!safe(referer).isEmpty()) editor.putString(key + ".referer", referer);
        editor.putBoolean(key + ".verified", true);
        editor.putLong(key + ".first_frame_ms", Math.max(-1L, firstFrameMs));
        editor.putLong(key + ".updated", System.currentTimeMillis()).apply();
    }

    static void rememberCandidate(Context context, String url, String kind,
                                  String extension, String route, String engine,
                                  String userAgent, String referer) {
        String key = key(context, url, kind);
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        if (!safe(extension).isEmpty()) editor.putString(key + ".live_ext", extension);
        if (!safe(route).isEmpty()) editor.putString(key + ".route", route);
        if (!safe(engine).isEmpty()) editor.putString(key + ".engine", engine);
        if (!safe(userAgent).isEmpty()) editor.putString(key + ".ua", userAgent);
        if (!safe(referer).isEmpty()) editor.putString(key + ".referer", referer);
        editor.putBoolean(key + ".verified", false);
        editor.putLong(key + ".updated", System.currentTimeMillis()).apply();
    }

    static void rejectRoute(Context context, String url, String kind, String route, String reason) {
        if (safe(route).isEmpty()) return;
        String key = key(context, url, kind);
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> routes = new LinkedHashSet<>();
        for (String value : prefs.getString(key + ".rejected", "").split(",")) {
            if (!value.trim().isEmpty()) routes.add(value.trim());
        }
        routes.add(route.trim());
        StringBuilder joined = new StringBuilder();
        for (String value : routes) {
            if (joined.length() > 0) joined.append(',');
            joined.append(value);
        }
        SharedPreferences.Editor editor = prefs.edit()
                .putString(key + ".rejected", joined.toString())
                .putString(key + ".reject_reason_" + route, safe(reason))
                .putLong(key + ".updated", System.currentTimeMillis());
        if (route.equals(prefs.getString(key + ".route", ""))) {
            editor.putBoolean(key + ".verified", false).remove(key + ".route");
        }
        editor.apply();
    }

    static void forget(Context context, String url) {
        clearKind(context, url, "live");
        clearKind(context, url, "movies");
        clearKind(context, url, "episode");
        clearKind(context, url, "generic");
    }

    static void clearKind(Context context, String url, String kind) {
        String prefix = key(context, url, kind) + ".";
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : prefs.getAll().keySet()) if (key.startsWith(prefix)) editor.remove(key);
        editor.apply();
    }

    private static Profile empty() {
        return new Profile("", "", "", "", "", 0L, false, "", -1L);
    }

    private static String key(Context context, String url, String kind) {
        String host = "unknown";
        try {
            Uri uri = Uri.parse(safe(url));
            if (uri.getHost() != null) host = uri.getHost().toLowerCase(Locale.US);
        } catch (Exception ignored) {}
        String source = safe(CatalogScope.active(context));
        if (source.isEmpty()) source = "legacy";
        DeviceCapabilityProfile capability = DeviceCapabilityProfile.detect(context);
        String deviceClass = Build.VERSION.SDK_INT + "|" + capability.memoryClassMb()
                + "|" + capability.usesReducedPerformance();
        return "p_" + digest(source + "|" + host + "|" + safe(kind).toLowerCase(Locale.US)
                + "|" + deviceClass);
    }

    private static String digest(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 12 && i < digest.length; i++) out.append(String.format(Locale.US, "%02x", digest[i]));
            return out.toString();
        } catch (Exception ignored) { return Integer.toHexString(raw.hashCode()); }
    }

    private static String safe(String value) { return value == null ? "" : value.trim(); }
}
''', encoding='utf-8')

PREFLIGHT.write_text(r'''package tv.blofy.player;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.SystemClock;

import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Strict acceptance gate. 100% means all three families were demuxed and produced media samples. */
final class ServerCompatibilityPreflight {
    private static final String PREFS = "blofy_compatibility_preflight_v2";
    private static final int SAMPLE_COUNT = 2;
    private static final long VERIFY_TIMEOUT_MS = 12_000L;

    static final class Result {
        final int liveScore, moviesScore, seriesScore, tested, playable, inconclusive;
        final String summary, report;
        Result(int liveScore, int moviesScore, int seriesScore, int tested, int playable,
               int inconclusive, String summary, String report) {
            this.liveScore = liveScore; this.moviesScore = moviesScore; this.seriesScore = seriesScore;
            this.tested = tested; this.playable = playable; this.inconclusive = inconclusive;
            this.summary = summary; this.report = report;
        }
        boolean accepted() {
            return liveScore == 100 && moviesScore == 100 && seriesScore == 100
                    && tested >= 3 && inconclusive == 0;
        }
        boolean completeFailure() { return tested > 0 && playable == 0 && inconclusive == 0; }
    }

    private static final class Family { int ok, tested, inconclusive; }
    private static final class Candidate {
        final String apiKind, id, extension;
        Candidate(String apiKind, String id, String extension) {
            this.apiKind = apiKind; this.id = id; this.extension = extension;
        }
    }
    private static final class Verification {
        final boolean ok, inconclusive; final String detail; final long elapsedMs;
        Verification(boolean ok, boolean inconclusive, String detail, long elapsedMs) {
            this.ok = ok; this.inconclusive = inconclusive; this.detail = detail; this.elapsedMs = elapsedMs;
        }
    }

    private ServerCompatibilityPreflight() {}

    static Result run(Context context, BlofyApi api, CatalogDatabase database, String playlistId) {
        StringBuilder report = new StringBuilder(8192);
        report.append("BLOFY VERIFIED PLAYBACK GATE\n")
                .append("playlist=").append(safe(playlistId)).append('\n')
                .append("rule=3-of-3 required: live + movies + episode\n");
        Family live = family(context, api, database, "live", report);
        Family movies = family(context, api, database, "movies", report);
        Family series = family(context, api, database, "series", report);
        int tested = live.tested + movies.tested + series.tested;
        int playable = live.ok + movies.ok + series.ok;
        int inconclusive = live.inconclusive + movies.inconclusive + series.inconclusive;
        int liveScore = score(live.ok, live.tested);
        int movieScore = score(movies.ok, movies.tested);
        int seriesScore = score(series.ok, series.tested);
        String summary = "Live " + liveScore + "% • Movies " + movieScore
                + "% • Series " + seriesScore + "%";
        boolean accepted = liveScore == 100 && movieScore == 100 && seriesScore == 100
                && tested >= 3 && inconclusive == 0;
        report.append("decision=").append(accepted ? "ACCEPTED" : "REJECTED").append('\n')
                .append("summary=").append(summary).append('\n')
                .append("tested=").append(tested).append(" playable=").append(playable)
                .append(" inconclusive=").append(inconclusive).append('\n');
        String key = key(playlistId);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(key + ":summary", summary)
                .putString(key + ":report", report.toString())
                .putBoolean(key + ":accepted", accepted)
                .putInt(key + ":live", liveScore)
                .putInt(key + ":movies", movieScore)
                .putInt(key + ":series", seriesScore)
                .putLong(key + ":at", System.currentTimeMillis()).apply();
        PlaybackDiagnostics.marker(context, "verified-gate", "server", safe(playlistId), "",
                accepted ? "accepted" : "rejected", summary);
        return new Result(liveScore, movieScore, seriesScore, tested, playable,
                inconclusive, summary, report.toString());
    }

    static boolean savedAccepted(Context context, String playlistId) {
        String key = key(playlistId);
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long at = prefs.getLong(key + ":at", 0L);
        return prefs.getBoolean(key + ":accepted", false)
                && at > 0 && System.currentTimeMillis() - at < 7L * 24L * 60L * 60L * 1000L;
    }
    static String savedSummary(Context context, String playlistId) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(key(playlistId) + ":summary", "لم يتم التحقق بعد");
    }
    static String savedReport(Context context, String playlistId) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(key(playlistId) + ":report", "لا يوجد تقرير تحقق محفوظ.");
    }

    private static Family family(Context context, BlofyApi api, CatalogDatabase database,
                                 String type, StringBuilder report) {
        Family family = new Family();
        List<BlofyModels.Media> samples = spread(database, type);
        if (samples.isEmpty()) {
            family.tested = 1;
            report.append(type).append(" FAIL no-samples\n");
            return family;
        }
        for (BlofyModels.Media item : samples) {
            Candidate candidate;
            try { candidate = candidate(api, type, item); }
            catch (Exception error) {
                family.tested++;
                family.inconclusive++;
                report.append(type).append('/').append(item.id).append(" INCONCLUSIVE candidate ")
                        .append(error.getClass().getSimpleName()).append('\n');
                continue;
            }
            family.tested++;
            Verification result = verifyCandidate(context, api, type, candidate, report);
            if (result.ok) family.ok++;
            else if (result.inconclusive) family.inconclusive++;
        }
        return family;
    }

    private static List<BlofyModels.Media> spread(CatalogDatabase db, String type) {
        List<BlofyModels.Media> out = new ArrayList<>();
        int total = Math.max(0, db.count(type));
        int[] offsets = total > 2 ? new int[]{0, Math.max(0, total / 2)} : new int[]{0};
        Set<String> ids = new LinkedHashSet<>();
        for (int offset : offsets) {
            List<BlofyModels.Media> page = db.media(type, "", "", false, false, 1, offset);
            if (page != null && !page.isEmpty() && ids.add(page.get(0).id)) out.add(page.get(0));
            if (out.size() >= SAMPLE_COUNT) break;
        }
        return out;
    }

    private static Candidate candidate(BlofyApi api, String type, BlofyModels.Media item) throws Exception {
        if (!"series".equals(type)) return new Candidate(type, item.id, item.extension);
        BlofyModels.Detail detail = new BlofyModels.Detail(
                api.get("/api/series/" + BlofyApi.encode(item.id)), "series");
        for (BlofyModels.Season season : detail.seasons) {
            for (BlofyModels.Episode episode : season.episodes) {
                if (!safe(episode.id).isEmpty()) return new Candidate("episode", episode.id,
                        safe(episode.extension).isEmpty() ? "mp4" : episode.extension);
            }
        }
        throw new IllegalStateException("series-has-no-playable-episode");
    }

    private static Verification verifyCandidate(Context context, BlofyApi api, String family,
                                                Candidate candidate, StringBuilder report) {
        String baseExt = PlaybackPolicy.normalizeExtension(candidate.extension,
                "live".equals(family) ? "ts" : "mp4");
        Set<String> exts = new LinkedHashSet<>();
        exts.add(baseExt);
        if ("live".equals(family)) exts.add(PlaybackPolicy.alternateLiveExtension(baseExt));
        String[] routes = {"canonical", "direct", "no-extension"};
        boolean sawInconclusive = false;
        String last = "no-route";
        for (String ext : exts) {
            for (String route : routes) {
                long started = SystemClock.elapsedRealtime();
                try {
                    JSONObject data = api.getPlayback("/api/native-link/" + BlofyApi.encode(candidate.apiKind)
                            + "/" + BlofyApi.encode(candidate.id) + "?ext=" + BlofyApi.encode(ext)
                            + "&variant=" + BlofyApi.encode(route), new BlofyApi.Cancellation());
                    String url = data.optString("url", "");
                    String resolvedExt = PlaybackPolicy.normalizeExtension(data.optString("extension", ext), ext);
                    if (!(url.startsWith("http://") || url.startsWith("https://"))) {
                        last = "invalid-url"; continue;
                    }
                    Verification verification = timedVerify(url, data, resolvedExt);
                    long elapsed = Math.max(0L, SystemClock.elapsedRealtime() - started);
                    report.append(family).append('/').append(candidate.id).append(' ')
                            .append(verification.ok ? "PASS" : verification.inconclusive ? "INCONCLUSIVE" : "FAIL")
                            .append(" route=").append(route).append(" ext=").append(resolvedExt)
                            .append(" elapsed_ms=").append(elapsed)
                            .append(" detail=").append(redact(verification.detail)).append('\n');
                    if (verification.ok) {
                        ServerPlaybackProfile.rememberVerifiedSuccess(context, url, candidate.apiKind,
                                resolvedExt, route, "verified-demux", suppliedHeader(data, "User-Agent"),
                                first(data.optString("referer", ""), suppliedHeader(data, "Referer")),
                                verification.elapsedMs);
                        return verification;
                    }
                    last = verification.detail;
                    if (verification.inconclusive) sawInconclusive = true;
                    if (PlaybackPolicy.isHardRouteFailure(verification.detail)) {
                        ServerPlaybackProfile.rejectRoute(context, url, candidate.apiKind, route, verification.detail);
                    }
                } catch (BlofyApi.ApiException error) {
                    last = "HTTP " + error.status;
                    if (PlaybackPolicy.isHardRouteFailure(last)) {
                        // no URL is available; continue without persisting a host-scoped rejection
                    } else if (error.status == 429 || error.status >= 500) sawInconclusive = true;
                    report.append(family).append('/').append(candidate.id).append(" FAIL route=")
                            .append(route).append(" ").append(last).append('\n');
                } catch (Exception error) {
                    last = error.getClass().getSimpleName() + ":" + safe(error.getMessage());
                    sawInconclusive = true;
                    report.append(family).append('/').append(candidate.id).append(" INCONCLUSIVE route=")
                            .append(route).append(' ').append(redact(last)).append('\n');
                }
            }
        }
        return new Verification(false, sawInconclusive, last, 0L);
    }

    private static Verification timedVerify(String url, JSONObject data, String extension) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        long started = SystemClock.elapsedRealtime();
        Future<Verification> future = executor.submit((Callable<Verification>) () -> verifyMedia(url, data, extension));
        try {
            Verification value = future.get(VERIFY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return new Verification(value.ok, value.inconclusive, value.detail,
                    Math.max(0L, SystemClock.elapsedRealtime() - started));
        } catch (TimeoutException error) {
            future.cancel(true);
            return new Verification(false, true, "verification-timeout", VERIFY_TIMEOUT_MS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return new Verification(false, true, "verification-interrupted", 0L);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            return new Verification(false, false,
                    cause == null ? "verification-failed" : cause.getClass().getSimpleName() + ":" + safe(cause.getMessage()), 0L);
        } finally {
            executor.shutdownNow();
        }
    }

    private static Verification verifyMedia(String url, JSONObject data, String extension) throws Exception {
        Map<String, String> headers = headers(data);
        // Fast status/range validation first; catches 404/551 before the extractor blocks.
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(3_500);
        connection.setReadTimeout(4_500);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Range", "bytes=0-65535");
        connection.setRequestProperty("Accept-Encoding", "identity");
        for (Map.Entry<String, String> header : headers.entrySet()) connection.setRequestProperty(header.getKey(), header.getValue());
        int status = connection.getResponseCode();
        String contentType = safe(connection.getContentType());
        connection.disconnect();
        if (status != 200 && status != 206) return new Verification(false, false, "HTTP " + status, 0L);

        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(url, headers);
            int tracks = extractor.getTrackCount();
            int videoTrack = -1;
            int audioTrack = -1;
            String videoMime = "";
            for (int index = 0; index < tracks; index++) {
                MediaFormat format = extractor.getTrackFormat(index);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/") && videoTrack < 0) {
                    videoTrack = index; videoMime = mime;
                } else if (mime != null && mime.startsWith("audio/") && audioTrack < 0) {
                    audioTrack = index;
                }
            }
            int chosen = videoTrack >= 0 ? videoTrack : audioTrack;
            if (chosen < 0) return new Verification(false, false,
                    "no-media-track type=" + contentType, 0L);
            extractor.selectTrack(chosen);
            ByteBuffer buffer = ByteBuffer.allocate(256 * 1024);
            int sample = extractor.readSampleData(buffer, 0);
            if (sample <= 0) return new Verification(false, false,
                    "no-readable-sample mime=" + videoMime + " type=" + contentType, 0L);
            return new Verification(true, false,
                    "tracks=" + tracks + " sample=" + sample + " mime=" + videoMime
                            + " type=" + contentType, 0L);
        } finally {
            try { extractor.release(); } catch (Exception ignored) {}
        }
    }

    private static Map<String, String> headers(JSONObject data) {
        Map<String, String> out = new HashMap<>();
        String ua = suppliedHeader(data, "User-Agent");
        out.put("User-Agent", ua.isEmpty() ? PlaybackTransportFactory.userAgent(0) : ua);
        out.put("Accept", "*/*");
        String referer = first(data.optString("referer", ""), suppliedHeader(data, "Referer"));
        if (!referer.isEmpty()) out.put("Referer", referer);
        JSONObject supplied = data.optJSONObject("headers");
        if (supplied != null) for (String key : new String[]{"Origin", "Cookie", "Authorization", "Accept-Language"}) {
            String value = supplied.optString(key, supplied.optString(key.toLowerCase(Locale.US), ""));
            if (!value.isEmpty()) out.put(key, value);
        }
        return out;
    }

    private static String suppliedHeader(JSONObject data, String name) {
        JSONObject headers = data == null ? null : data.optJSONObject("headers");
        if (headers == null) return "";
        return headers.optString(name, headers.optString(name.toLowerCase(Locale.US), ""));
    }
    private static String first(String a, String b) { return !safe(a).isEmpty() ? a : safe(b); }
    private static int score(int ok, int tested) { return tested <= 0 ? 0 : Math.round(ok * 100f / tested); }
    private static String key(String playlistId) { return Integer.toHexString(safe(playlistId).toLowerCase(Locale.US).hashCode()); }
    private static String redact(String value) { return safe(value).replaceAll("(?i)(token|password|pass|auth)=([^&\\s]+)", "$1=<redacted>"); }
    private static String safe(String value) { return value == null ? "" : value.trim(); }
}
''', encoding='utf-8')

# PlaybackPolicy: add hard route classification.
policy = POLICY.read_text(encoding='utf-8')
if 'static boolean isHardRouteFailure' not in policy:
    anchor = '    static boolean isDecoderFailure(String reason) {'
    insert = '''    static boolean isHardRouteFailure(String reason) {\n        String value = value(reason).toUpperCase(Locale.US);\n        return value.contains("HTTP 400") || value.contains("HTTP400")\n                || value.contains("HTTP 404") || value.contains("HTTP404")\n                || value.contains("HTTP 405") || value.contains("HTTP405")\n                || value.contains("HTTP 410") || value.contains("HTTP410")\n                || value.contains("HTTP 415") || value.contains("HTTP415")\n                || value.contains("HTTP 451") || value.contains("HTTP451")\n                || value.contains("HTTP 551") || value.contains("HTTP551");\n    }\n\n'''
    if anchor not in policy: raise SystemExit('policy anchor missing')
    policy = policy.replace(anchor, insert + anchor, 1)
POLICY.write_text(policy, encoding='utf-8')

# Strict cached gate and strict post-import acceptance.
package = PACKAGE.read_text(encoding='utf-8')
package = package.replace(
'''        if (sourceIdentity.equals(activeSource) && (cachedLive + cachedMovies + cachedSeries) > 0\n                && !"in_progress".equals(database.metadata("sync_state", ""))) {\n            String profile = database.metadata("playback_profile", "Media3 مباشر");\n            emit(100, "البيانات جاهزة", "تم فتح النسخة المحفوظة على الجهاز بدون إعادة تحميل");\n            return new Result(cachedLive, cachedMovies, cachedSeries, profile);\n        }\n''',
'''        if (sourceIdentity.equals(activeSource) && (cachedLive + cachedMovies + cachedSeries) > 0\n                && !"in_progress".equals(database.metadata("sync_state", ""))) {\n            String profile = database.metadata("playback_profile", "Media3 مباشر");\n            if (ServerCompatibilityPreflight.savedAccepted(api.context(), playlistId)) {\n                emit(100, "جاهز", "تم التحقق من ملف تشغيل هذا السيرفر مسبقًا");\n                return new Result(cachedLive, cachedMovies, cachedSeries, profile);\n            }\n            emit(96, "إعادة التحقق من التشغيل", "البيانات محفوظة؛ يتم التحقق من Live و Movies و Series فقط");\n            ServerCompatibilityPreflight.Result cachedGate = ServerCompatibilityPreflight.run(\n                    api.context(), api, database, playlistId);\n            if (!cachedGate.accepted()) {\n                throw new Exception("السيرفر غير متوافق بالكامل. " + cachedGate.summary\n                        + " • افتح تقرير التشخيص لمعرفة المسار أو الصيغة الفاشلة.");\n            }\n            emit(100, "جاهز", cachedGate.summary);\n            return new Result(cachedLive, cachedMovies, cachedSeries, profile);\n        }\n''', 1)
package = package.replace(
'''            if (preflight.completeFailure() && (live + movies + series) > 0) {\n                throw new Exception("تم حفظ الباقة كاملة، لكن فشل اختبار التشغيل على جميع العينات والمسارات. "\n                        + preflight.summary + " • افتح تقرير التشخيص لمعرفة السبب.");\n            }\n''',
'''            if (!preflight.accepted()) {\n                throw new Exception("تم حفظ الباقة كاملة، لكن لم يتم اعتماد السيرفر لأن التشغيل ليس 3/3. "\n                        + preflight.summary + " • Live و Movies و Series يجب أن تنجح جميعًا قبل الدخول.");\n            }\n''', 1)
PACKAGE.write_text(package, encoding='utf-8')

# Player: never use legacy host-only learning; use verified per-kind profile only.
player = PLAYER.read_text(encoding='utf-8')
player = player.replace('ServerPlaybackProfile.load(this, canonicalUrl)',
                        'ServerPlaybackProfile.load(this, canonicalUrl, kind)')
player = player.replace('if (learned.fresh() && learnedAlternateVariant(learned.preferredRoute)) {',
                        'if (learned.fresh() && !learned.routeRejected(learned.preferredRoute)\n                                && learnedAlternateVariant(learned.preferredRoute)) {')
# Disable live drawer creation entirely.
player = re.sub(r'\n        if \(isLive\(\)\) \{\n            liveOverlay = new LiveChannelOverlay\(this, categoryId, this::switchLiveChannel\);\n            View overlayView = liveOverlay\.view\(\);\n            overlayView\.setElevation\(dp\(20\)\);\n            root\.addView\(overlayView, new FrameLayout\.LayoutParams\(ViewGroup\.LayoutParams\.MATCH_PARENT,\n                    ViewGroup\.LayoutParams\.MATCH_PARENT\)\);\n        \}\n', '\n', player, count=1)
# OK/Enter on live returns to the list, avoiding the broken overlay route.
player = player.replace(
'''                case KeyEvent.KEYCODE_DPAD_CENTER:\n                case KeyEvent.KEYCODE_ENTER:\n                    if (isLive() && liveOverlay != null && !liveOverlay.isVisible()) {\n                        liveOverlay.show(id); return true;\n                    }\n                    break;\n''',
'''                case KeyEvent.KEYCODE_DPAD_CENTER:\n                case KeyEvent.KEYCODE_ENTER:\n                    if (isLive()) { finish(); return true; }\n                    break;\n''', 1)
# Hard route failures invalidate that route immediately.
needle = '    private static String playbackErrorReason(PlaybackException error) {'
if needle in player and 'rejectCurrentRouteIfHard' not in player:
    helper = '''    private void rejectCurrentRouteIfHard(String reason) {\n        if (!PlaybackPolicy.isHardRouteFailure(reason) || !validUrl(url)) return;\n        ServerPlaybackProfile.rejectRoute(this, url, kind, sourceVariant, reason);\n    }\n\n'''
    player = player.replace(needle, helper + needle, 1)
player = player.replace('        recoverFromFailure(playbackErrorReason(error));',
                        '        String reason = playbackErrorReason(error);\n        rejectCurrentRouteIfHard(reason);\n        recoverFromFailure(reason);', 1)
# Persist only after stable real playback.
old = '''    private void rememberSuccessfulTransport() {\n        // Deliberately session-only. Persisting this choice by file extension\n        // made one unusual host slow down every other host using that extension.\n    }\n'''
new = '''    private void rememberSuccessfulTransport() {\n        if (!validUrl(url) || !firstFrameRendered) return;\n        long firstFrameMs = playbackStartedAtMs == 0 ? -1L\n                : Math.max(0L, SystemClock.elapsedRealtime() - playbackStartedAtMs);\n        ServerPlaybackProfile.rememberVerifiedSuccess(this, url, kind, extension, sourceVariant,\n                usingVlc ? "libvlc" : "media3", PlaybackTransportFactory.userAgent(usingVlc ? 2 : 0),\n                playbackReferer, firstFrameMs);\n    }\n'''
if old in player: player = player.replace(old, new, 1)
PLAYER.write_text(player, encoding='utf-8')

# VOD: kind-aware verified profiles, canonical-first and reject 404/551 immediately.
vod = VOD.read_text(encoding='utf-8')
vod = vod.replace('ServerPlaybackProfile.load(this, canonicalUrl)',
                  'ServerPlaybackProfile.load(this, canonicalUrl, kind)')
vod = vod.replace('if (learned.fresh() && learnedAlternateVariant(learned.preferredRoute)) {',
                  'if (learned.fresh() && !learned.routeRejected(learned.preferredRoute)\n                                && learnedAlternateVariant(learned.preferredRoute)) {')
# Any old success persistence is converted to verified success; firstFrame events below reinforce it.
vod = re.sub(r'ServerPlaybackProfile\.rememberSuccess\(this, resolvedUrl, extension, sourceVariant,\n\s*([^;]+)\);',
             r'ServerPlaybackProfile.rememberVerifiedSuccess(this, resolvedUrl, kind, extension, sourceVariant,\n                \1, firstFrame ? 0L : -1L);', vod)
# Inject helpers before recover.
if 'private void rejectVodRouteIfHard' not in vod:
    anchor = '    private void recover(String reason) {'
    helpers = '''    private void rejectVodRouteIfHard(String reason) {\n        if (!PlaybackPolicy.isHardRouteFailure(reason) || !validUrl(resolvedUrl)) return;\n        ServerPlaybackProfile.rejectRoute(this, resolvedUrl, kind, sourceVariant, reason);\n    }\n\n    private boolean routeAllowed(String route) {\n        String reference = validUrl(canonicalUrl) ? canonicalUrl : resolvedUrl;\n        ServerPlaybackProfile.Profile profile = ServerPlaybackProfile.load(this, reference, kind);\n        return !profile.routeRejected(route);\n    }\n\n'''
    if anchor not in vod: raise SystemExit('VOD recover anchor missing')
    vod = vod.replace(anchor, helpers + anchor, 1)
# Replace recover method as one bounded state machine.
pattern = re.compile(r'    private void recover\(String reason\) \{.*?\n    \}\n\n    private void showFinalPlaybackError', re.S)
replacement = '''    private void recover(String reason) {\n        main.removeCallbacks(startupTimeout);\n        savePosition();\n        rejectVodRouteIfHard(reason);\n\n        if (usingVlc) {\n            showFinalPlaybackError(reason);\n            return;\n        }\n\n        // Canonical is always tried first. Direct/no-extension are bounded fallbacks only.\n        if ("canonical".equals(sourceVariant) && !id.isEmpty()) {\n            if (!alternateSourceAttempted && routeAllowed("direct")) {\n                alternateSourceAttempted = true;\n                sourceVariant = "direct";\n                attempt = 1;\n                releaseAllEngines();\n                resolvedUrl = ""; resolving = false; resolve();\n                return;\n            }\n            if (!containerRouteAttempted && routeAllowed("no-extension")) {\n                containerRouteAttempted = true;\n                sourceVariant = "no-extension";\n                attempt = 1;\n                releaseAllEngines();\n                resolvedUrl = ""; resolving = false; resolve();\n                return;\n            }\n            attempt = 2;\n            openVlc(reason);\n            return;\n        }\n\n        // An alternate route failed: return to canonical and let VLC try the proven URL.\n        if (restoreCanonicalSource()) {\n            attempt = 2;\n            openVlc(reason);\n            return;\n        }\n        showFinalPlaybackError(reason);\n    }\n\n    private void showFinalPlaybackError'''
vod, hits = pattern.subn(replacement, vod, count=1)
if hits != 1: raise SystemExit('VOD recover replacement failed')
# When Media3 first frame arrives, persist verified route. Find onRenderedFirstFrame.
vod = vod.replace(
'''        firstFrame = true;\n        spinner.setVisibility(View.GONE);\n        main.removeCallbacks(startupTimeout);\n''',
'''        firstFrame = true;\n        spinner.setVisibility(View.GONE);\n        main.removeCallbacks(startupTimeout);\n        if (validUrl(resolvedUrl)) {\n            ServerPlaybackProfile.rememberVerifiedSuccess(this, resolvedUrl, kind, extension, sourceVariant,\n                    usingVlc ? "libvlc" : "media3", PlaybackTransportFactory.userAgent(usingVlc ? 2 : 0),\n                    playbackReferer, 0L);\n        }\n''', 1)
# Retry resets all route state.
vod = vod.replace('        alternateSourceAttempted = false;\n',
                  '        alternateSourceAttempted = false;\n        containerRouteAttempted = false;\n', 1)
VOD.write_text(vod, encoding='utf-8')

# Preview: debounce focus movement and use verified live profile; canonical remains default.
preview = PREVIEW.read_text(encoding='utf-8')
preview = preview.replace('        main.post(pending);', '        main.postDelayed(pending, 380L);', 1)
old = '''                String ext = PlaybackPolicy.normalizeExtension(item.extension, "ts");\n                JSONObject result = api.getPlayback("/api/native-link/live/" + BlofyApi.encode(item.id)\n                        + "?ext=" + BlofyApi.encode(ext), cancellation);\n'''
new = '''                String ext = PlaybackPolicy.normalizeExtension(item.extension, "ts");\n                String variant = "canonical";\n                ServerPlaybackProfile.Profile learned = ServerPlaybackProfile.load(context, api.baseUrl(), "live");\n                if (learned.fresh()) {\n                    if (!learned.preferredLiveExtension.isEmpty()) ext = learned.preferredLiveExtension;\n                    if (!learned.preferredRoute.isEmpty() && !learned.routeRejected(learned.preferredRoute))\n                        variant = learned.preferredRoute;\n                }\n                JSONObject result = api.getPlayback("/api/native-link/live/" + BlofyApi.encode(item.id)\n                        + "?ext=" + BlofyApi.encode(ext) + "&variant=" + BlofyApi.encode(variant), cancellation);\n'''
if old not in preview: raise SystemExit('preview resolver anchor missing')
preview = preview.replace(old, new, 1)
PREVIEW.write_text(preview, encoding='utf-8')

# SevenMax already has a real mini-player. Keep logo only as loading/error fallback and ensure first channel autoplays.
seven = SEVEN.read_text(encoding='utf-8')
seven = seven.replace('TextView channelName = BlofyUi.title(this, "اختر قناة للمعاينة", 20);',
                      'TextView channelName = BlofyUi.title(this, "جاري تشغيل أول قناة…", 20);', 1)
SEVEN.write_text(seven, encoding='utf-8')

# Invariants after all patches.
checks = {
    PROFILE: ['blofy_server_playback_profiles_v2', 'rememberVerifiedSuccess', 'rejectRoute', 'CatalogScope.active'],
    PREFLIGHT: ['rule=3-of-3 required', 'MediaExtractor', 'accepted()', 'no-readable-sample'],
    PACKAGE: ['cachedGate.accepted()', '!preflight.accepted()'],
    PLAYER: ['rememberVerifiedSuccess', 'if (isLive()) { finish(); return true; }'],
    VOD: ['routeAllowed("direct")', 'routeAllowed("no-extension")', 'rejectVodRouteIfHard'],
    PREVIEW: ['postDelayed(pending, 380L)', 'ServerPlaybackProfile.load(context, api.baseUrl(), "live")'],
    POLICY: ['isHardRouteFailure'],
}
for path, tokens in checks.items():
    text = path.read_text(encoding='utf-8')
    for token in tokens:
        if token not in text: raise SystemExit(f'missing invariant {path.name}: {token}')

print('v340 verified gate applied: strict 3/3 acceptance + per-login/per-kind verified profiles + canonical-first routes + mini-player debounce + no live drawer')
