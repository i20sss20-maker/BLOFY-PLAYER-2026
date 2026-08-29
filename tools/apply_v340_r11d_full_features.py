#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "BLOFY-ANDROID-2026/app"
JAVA = APP / "src/main/java/tv/blofy/player"
PLAYER = JAVA / "PlayerActivity.java"
GRADLE = APP / "build.gradle.kts"

# ---------------------------------------------------------------------------
# 1) Fast fallback preloader: pre-resolve the alternate Live candidate through
#    BLOFY only. It never opens provider media bytes in parallel, so it avoids
#    stealing a second IPTV stream/session while still removing a Railway round
#    trip when TS<->HLS recovery is needed.
# ---------------------------------------------------------------------------
PRELOAD = JAVA / "PlaybackPreloadManager.java"
PRELOAD.write_text(r'''package tv.blofy.player;

import android.content.Context;
import android.os.SystemClock;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small bounded cache of already-authorized native-link responses. */
final class PlaybackPreloadManager {
    private static final long TTL_MS = 20_000L;
    private static final int MAX = 6;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final LinkedHashMap<String, Entry> CACHE = new LinkedHashMap<String, Entry>(8, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
            return size() > MAX;
        }
    };

    private static final class Entry {
        final JSONObject value;
        final long at;
        Entry(JSONObject value) { this.value = value; this.at = SystemClock.elapsedRealtime(); }
    }

    private PlaybackPreloadManager() {}

    static void preload(Context context, String path) {
        if (path == null || path.isEmpty()) return;
        synchronized (CACHE) {
            Entry existing = CACHE.get(path);
            if (existing != null && SystemClock.elapsedRealtime() - existing.at < TTL_MS) return;
        }
        Context app = context.getApplicationContext();
        WORKER.execute(() -> {
            try {
                BlofyApi.Cancellation cancellation = new BlofyApi.Cancellation();
                JSONObject value = new BlofyApi(app).getPlayback(path, cancellation);
                synchronized (CACHE) { CACHE.put(path, new Entry(new JSONObject(value.toString()))); }
                PlaybackDiagnostics.marker(app, "r11d-preload-ready", "live", "", "", "native-link", path);
            } catch (Exception ignored) {
                // Preload is opportunistic. Normal bounded resolve remains authoritative.
            }
        });
    }

    static JSONObject take(String path) {
        synchronized (CACHE) {
            Entry entry = CACHE.remove(path);
            if (entry == null) return null;
            if (SystemClock.elapsedRealtime() - entry.at >= TTL_MS) return null;
            try { return new JSONObject(entry.value.toString()); }
            catch (Exception ignored) { return null; }
        }
    }

    static void clear() { synchronized (CACHE) { CACHE.clear(); } }
}
''', encoding="utf-8")

# ---------------------------------------------------------------------------
# 2) Smart VOD byte cache. Live is deliberately excluded: caching rolling IPTV
#    segments can replay stale windows. Movies/episodes use a bounded LRU cache
#    and fall back to upstream immediately on cache errors.
# ---------------------------------------------------------------------------
SMART = JAVA / "PlaybackSmartCache.java"
SMART.write_text(r'''package tv.blofy.player;

import android.content.Context;

import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;

import java.io.File;

final class PlaybackSmartCache {
    private static final long MAX_BYTES = 192L * 1024L * 1024L;
    private static SimpleCache cache;

    private PlaybackSmartCache() {}

    private static synchronized SimpleCache get(Context context) {
        if (cache == null) {
            Context app = context.getApplicationContext();
            File dir = new File(app.getCacheDir(), "blofy-media3-vod-cache");
            cache = new SimpleCache(dir, new LeastRecentlyUsedCacheEvictor(MAX_BYTES),
                    new StandaloneDatabaseProvider(app));
        }
        return cache;
    }

    static DataSource.Factory wrap(Context context, DataSource.Factory upstream, boolean live) {
        if (live) return upstream;
        return new CacheDataSource.Factory()
                .setCache(get(context))
                .setUpstreamDataSourceFactory(upstream)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    }
}
''', encoding="utf-8")

p = PLAYER.read_text(encoding="utf-8")

# CMCD import.
if "import androidx.media3.exoplayer.upstream.CmcdConfiguration;" not in p:
    anchor = "import androidx.media3.exoplayer.source.MediaSource;\n"
    if anchor not in p:
        raise SystemExit("R11D: MediaSource import anchor missing")
    p = p.replace(anchor, anchor + "import androidx.media3.exoplayer.upstream.CmcdConfiguration;\n", 1)

# Smart cache wraps only VOD/episodes.
old = '''        return PlaybackTransportFactory.create(this, false, network,
                15_000, 30_000, recoveryStep, playbackReferer);'''
new = '''        DataSource.Factory upstream = PlaybackTransportFactory.create(this, false, network,
                15_000, 30_000, recoveryStep, playbackReferer);
        return PlaybackSmartCache.wrap(this, upstream, isLive());'''
if old in p:
    p = p.replace(old, new, 1)
elif "PlaybackSmartCache.wrap(this, upstream, isLive())" not in p:
    raise SystemExit("R11D: data source factory anchor missing")

# CMCD on DefaultMediaSourceFactory instances.
p = p.replace(
    "DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory);",
    "DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)\n"
    "                .setCmcdConfigurationFactory(CmcdConfiguration.Factory.DEFAULT);"
)

# CMCD + chunkless preparation on explicit HLS factory. Media3 1.11 handles
# Apple LL-HLS (blocking reload/preload hints) and fMP4/CMAF natively.
old_hls = '''                ? new HlsMediaSource.Factory(dataSourceFactory)
                    .setExtractorFactory(new DefaultHlsExtractorFactory(tsFlags, true)).createMediaSource(item)'''
new_hls = '''                ? new HlsMediaSource.Factory(dataSourceFactory)
                    .setAllowChunklessPreparation(true)
                    .setCmcdConfigurationFactory(CmcdConfiguration.Factory.DEFAULT)
                    .setExtractorFactory(new DefaultHlsExtractorFactory(tsFlags, true)).createMediaSource(item)'''
if old_hls in p:
    p = p.replace(old_hls, new_hls, 1)
elif "setAllowChunklessPreparation(true)" not in p:
    raise SystemExit("R11D: HLS factory anchor missing")

# Build a stable requestPath once, then consume an already pre-resolved fallback
# link if available before asking Railway again.
if "String requestPath = \"/api/native-link/\"" not in p:
    anchor = '''        String requestedReferer = playbackReferer;
        resolveTask = network.submit(() -> {
            try {
                String apiType = "series".equals(requestedKind) ? "episode" : requestedKind;
                JSONObject data = new BlofyApi(this).getPlayback("/api/native-link/" + BlofyApi.encode(apiType) + "/"
                        + BlofyApi.encode(requestedId) + "?ext=" + BlofyApi.encode(requestedExtension)
                        + "&variant=" + BlofyApi.encode(requestedVariant), cancellation);'''
    repl = '''        String requestedReferer = playbackReferer;
        String apiType = "series".equals(requestedKind) ? "episode" : requestedKind;
        String requestPath = "/api/native-link/" + BlofyApi.encode(apiType) + "/"
                + BlofyApi.encode(requestedId) + "?ext=" + BlofyApi.encode(requestedExtension)
                + "&variant=" + BlofyApi.encode(requestedVariant);
        resolveTask = network.submit(() -> {
            try {
                JSONObject data = PlaybackPreloadManager.take(requestPath);
                if (data == null) data = new BlofyApi(this).getPlayback(requestPath, cancellation);'''
    if anchor not in p:
        raise SystemExit("R11D: resolve path anchor missing")
    p = p.replace(anchor, repl, 1)

# Once Live proves a first frame, pre-resolve only the alternate stream family.
# This is the candidate R11 will use on a hard/stall recovery.
if "r11d-preload-scheduled" not in p:
    ff = '''        if (isLive()) {
            livePlaybackProven = true;
            liveSilentRecoveryCount = 0;
            attemptedLiveCandidates.clear();
        }'''
    add = ff + '''
        if (isLive() && !id.isEmpty()) {
            String alternate = PlaybackPolicy.alternateLiveExtension(extension);
            String preloadPath = "/api/native-link/live/" + BlofyApi.encode(id)
                    + "?ext=" + BlofyApi.encode(alternate) + "&variant=canonical";
            PlaybackPreloadManager.preload(this, preloadPath);
            PlaybackDiagnostics.marker(this, "r11d-preload-scheduled", "live", id, extension,
                    sourceVariant, "next=" + alternate);
        }'''
    if ff not in p:
        raise SystemExit("R11D: proven-live first-frame anchor missing")
    p = p.replace(ff, add, 1)

# Clear short-lived preload state on explicit channel change/manual retry so old
# signed links can never leak between sessions.
p = p.replace(
    "        attemptedLiveCandidates.clear();\n        id = media.id;",
    "        attemptedLiveCandidates.clear();\n        PlaybackPreloadManager.clear();\n        id = media.id;",
    1
)
manual = "    private void manualRetry() {\n"
if manual in p:
    pos = p.find(manual) + len(manual)
    section = p[pos:pos+500]
    if "PlaybackPreloadManager.clear();" not in section:
        p = p[:pos] + "        PlaybackPreloadManager.clear();\n" + p[pos:]

PLAYER.write_text(p, encoding="utf-8")

# Dependencies + release stamp.
g = GRADLE.read_text(encoding="utf-8")
if 'media3-datasource:$media3Version' not in g:
    needle = '    implementation("androidx.media3:media3-exoplayer:$media3Version")\n'
    g = g.replace(needle, needle +
        '    implementation("androidx.media3:media3-datasource:$media3Version")\n'
        '    implementation("androidx.media3:media3-database:$media3Version")\n', 1)
g, _ = re.subn(r'versionCode\s*=\s*\d+', 'versionCode = 1000345', g, count=1)
g, _ = re.subn(r'versionName\s*=\s*"[^"]*"', 'versionName = "v340-full-stability-r11d-full"', g, count=1)
GRADLE.write_text(g, encoding="utf-8")

checks = {
    PRELOAD: ["r11d-preload-ready", "TTL_MS = 20_000L", "MAX = 6"],
    SMART: ["MAX_BYTES = 192L", "CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR", "if (live) return upstream"],
    PLAYER: ["CmcdConfiguration.Factory.DEFAULT", "PlaybackSmartCache.wrap", "PlaybackPreloadManager.take",
             "r11d-preload-scheduled", "setAllowChunklessPreparation(true)"],
    GRADLE: ["media3-datasource:$media3Version", "media3-database:$media3Version",
             "versionCode = 1000345", "v340-full-stability-r11d-full"],
}
for path, markers in checks.items():
    text = path.read_text(encoding="utf-8")
    for marker in markers:
        if marker not in text:
            raise SystemExit(f"R11D invariant missing {path.name}: {marker}")

print("R11D full feature bundle applied: fallback preloader + smart VOD cache + CMCD + LL-HLS/CMAF-ready HLS + release stamp")
