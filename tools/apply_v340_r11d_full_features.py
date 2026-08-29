#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "BLOFY-ANDROID-2026/app"
JAVA = APP / "src/main/java/tv/blofy/player"
PLAYER = JAVA / "PlayerActivity.java"
GRADLE = APP / "build.gradle.kts"

PRELOAD = JAVA / "PlaybackPreloadManager.java"
PRELOAD.write_text(r'''package tv.blofy.player;

import android.content.Context;
import android.os.SystemClock;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class PlaybackPreloadManager {
    private static final long TTL_MS = 20_000L;
    private static final int MAX = 6;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final LinkedHashMap<String, Entry> CACHE = new LinkedHashMap<String, Entry>(8, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) { return size() > MAX; }
    };
    private static final class Entry {
        final JSONObject value; final long at;
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
            } catch (Exception ignored) {}
        });
    }
    static JSONObject take(String path) {
        synchronized (CACHE) {
            Entry entry = CACHE.remove(path);
            if (entry == null || SystemClock.elapsedRealtime() - entry.at >= TTL_MS) return null;
            try { return new JSONObject(entry.value.toString()); } catch (Exception ignored) { return null; }
        }
    }
    static void clear() { synchronized (CACHE) { CACHE.clear(); } }
}
''', encoding="utf-8")

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
            cache = new SimpleCache(dir, new LeastRecentlyUsedCacheEvictor(MAX_BYTES), new StandaloneDatabaseProvider(app));
        }
        return cache;
    }
    static DataSource.Factory wrap(Context context, DataSource.Factory upstream, boolean live) {
        if (live) return upstream;
        return new CacheDataSource.Factory().setCache(get(context))
                .setUpstreamDataSourceFactory(upstream)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    }
}
''', encoding="utf-8")

p = PLAYER.read_text(encoding="utf-8")

if "import androidx.media3.exoplayer.upstream.CmcdConfiguration;" not in p:
    anchor = "import androidx.media3.exoplayer.source.MediaSource;\n"
    if anchor not in p: raise SystemExit("R11D: MediaSource import anchor missing")
    p = p.replace(anchor, anchor + "import androidx.media3.exoplayer.upstream.CmcdConfiguration;\n", 1)

# Replace createDataSourceFactory body structurally instead of relying on one old line shape.
if "PlaybackSmartCache.wrap(this, upstream, isLive())" not in p:
    pattern = re.compile(r'''    private DataSource\.Factory createDataSourceFactory\(\) \{.*?\n    \}\n''', re.S)
    match = pattern.search(p)
    if not match: raise SystemExit("R11D: createDataSourceFactory method missing")
    body = '''    private DataSource.Factory createDataSourceFactory() {\n        DataSource.Factory upstream = PlaybackTransportFactory.create(this, false, network,\n                15_000, 30_000, recoveryStep, playbackReferer);\n        return PlaybackSmartCache.wrap(this, upstream, isLive());\n    }\n'''
    p = p[:match.start()] + body + p[match.end():]

# CMCD all DefaultMediaSourceFactory constructions, idempotently.
needle = "DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory);"
replacement = "DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)\n                .setCmcdConfigurationFactory(CmcdConfiguration.Factory.DEFAULT);"
p = p.replace(needle, replacement)

old_hls = '''                ? new HlsMediaSource.Factory(dataSourceFactory)\n                    .setExtractorFactory(new DefaultHlsExtractorFactory(tsFlags, true)).createMediaSource(item)'''
new_hls = '''                ? new HlsMediaSource.Factory(dataSourceFactory)\n                    .setAllowChunklessPreparation(true)\n                    .setCmcdConfigurationFactory(CmcdConfiguration.Factory.DEFAULT)\n                    .setExtractorFactory(new DefaultHlsExtractorFactory(tsFlags, true)).createMediaSource(item)'''
if old_hls in p:
    p = p.replace(old_hls, new_hls)
elif "setAllowChunklessPreparation(true)" not in p:
    # tolerate spacing differences around the explicit HLS factory
    p, n = re.subn(r'(new HlsMediaSource\.Factory\(dataSourceFactory\)\s*\n\s*)\.setExtractorFactory\(',
                   r'\1.setAllowChunklessPreparation(true)\n                    .setCmcdConfigurationFactory(CmcdConfiguration.Factory.DEFAULT)\n                    .setExtractorFactory(', p, count=1)
    if n != 1: raise SystemExit("R11D: HLS factory anchor missing")

if "String requestPath = \"/api/native-link/\"" not in p:
    # Rewrite the getPlayback expression while preserving the surrounding resolve method.
    start = p.find("        String requestedReferer = playbackReferer;\n")
    if start < 0: raise SystemExit("R11D: requestedReferer anchor missing")
    submit = p.find("        resolveTask = network.submit(() -> {", start)
    if submit < 0: raise SystemExit("R11D: resolve submit anchor missing")
    try_pos = p.find("            try {\n", submit)
    call_start = p.find("                JSONObject data = new BlofyApi(this).getPlayback(", try_pos)
    if call_start < 0: raise SystemExit("R11D: resolve getPlayback call missing")
    call_end = p.find(");", call_start)
    if call_end < 0: raise SystemExit("R11D: resolve getPlayback terminator missing")
    call_end += 2
    prefix = '''        String requestedReferer = playbackReferer;\n        String apiType = "series".equals(requestedKind) ? "episode" : requestedKind;\n        String requestPath = "/api/native-link/" + BlofyApi.encode(apiType) + "/"\n                + BlofyApi.encode(requestedId) + "?ext=" + BlofyApi.encode(requestedExtension)\n                + "&variant=" + BlofyApi.encode(requestedVariant);\n'''
    p = p[:start] + prefix + p[submit:call_start] + '''                JSONObject data = PlaybackPreloadManager.take(requestPath);\n                if (data == null) data = new BlofyApi(this).getPlayback(requestPath, cancellation);''' + p[call_end:]
    # Remove now-duplicated apiType declaration inside try if R11B had one.
    p = p.replace('                String apiType = "series".equals(requestedKind) ? "episode" : requestedKind;\n', '', 1)

if "r11d-preload-scheduled" not in p:
    ff = '''        if (isLive()) {\n            livePlaybackProven = true;\n            liveSilentRecoveryCount = 0;\n            attemptedLiveCandidates.clear();\n        }'''
    add = ff + '''\n        if (isLive() && !id.isEmpty()) {\n            String alternate = PlaybackPolicy.alternateLiveExtension(extension);\n            String preloadPath = "/api/native-link/live/" + BlofyApi.encode(id)\n                    + "?ext=" + BlofyApi.encode(alternate) + "&variant=canonical";\n            PlaybackPreloadManager.preload(this, preloadPath);\n            PlaybackDiagnostics.marker(this, "r11d-preload-scheduled", "live", id, extension,\n                    sourceVariant, "next=" + alternate);\n        }'''
    if ff not in p: raise SystemExit("R11D: proven-live first-frame anchor missing")
    p = p.replace(ff, add, 1)

p = p.replace("        attemptedLiveCandidates.clear();\n        id = media.id;",
              "        attemptedLiveCandidates.clear();\n        PlaybackPreloadManager.clear();\n        id = media.id;", 1)
manual = "    private void manualRetry() {\n"
if manual in p:
    pos = p.find(manual) + len(manual)
    if "PlaybackPreloadManager.clear();" not in p[pos:pos+500]:
        p = p[:pos] + "        PlaybackPreloadManager.clear();\n" + p[pos:]

PLAYER.write_text(p, encoding="utf-8")

g = GRADLE.read_text(encoding="utf-8")
if 'media3-datasource:$media3Version' not in g:
    needle = '    implementation("androidx.media3:media3-exoplayer:$media3Version")\n'
    g = g.replace(needle, needle + '    implementation("androidx.media3:media3-datasource:$media3Version")\n    implementation("androidx.media3:media3-database:$media3Version")\n', 1)
g = re.sub(r'versionCode\s*=\s*\d+', 'versionCode = 1000345', g, count=1)
g = re.sub(r'versionName\s*=\s*"[^"]*"', 'versionName = "v340-full-stability-r11d-full"', g, count=1)
GRADLE.write_text(g, encoding="utf-8")

checks = {
    PRELOAD: ["r11d-preload-ready", "TTL_MS = 20_000L", "MAX = 6"],
    SMART: ["MAX_BYTES = 192L", "CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR", "if (live) return upstream"],
    PLAYER: ["CmcdConfiguration.Factory.DEFAULT", "PlaybackSmartCache.wrap", "PlaybackPreloadManager.take", "r11d-preload-scheduled", "setAllowChunklessPreparation(true)"],
    GRADLE: ["media3-datasource:$media3Version", "media3-database:$media3Version", "versionCode = 1000345", "v340-full-stability-r11d-full"],
}
for path, markers in checks.items():
    text = path.read_text(encoding="utf-8")
    for marker in markers:
        if marker not in text: raise SystemExit(f"R11D invariant missing {path.name}: {marker}")

print("R11D full feature bundle applied: fallback preloader + smart VOD cache + CMCD + LL-HLS/CMAF-ready HLS + release stamp")
