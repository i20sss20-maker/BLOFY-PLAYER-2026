#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player"
PLAYER = JAVA / "PlayerActivity.java"
VOD = JAVA / "VodPlayerActivity.java"
TRANSPORT = JAVA / "PlaybackTransportFactory.java"
GRADLE = ROOT / "BLOFY-ANDROID-2026/app/build.gradle.kts"

# Stage5 goals:
# 1) Explicit IPTV-oriented Media3 buffer policy: fast first picture without a tiny
#    steady-state window that causes constant underruns.
# 2) Time-based buffering because IPTV provider bitrates vary widely.
# 3) Header/timeout parity between DefaultHttpDataSource and CronetDataSource.
# 4) Preserve all existing recovery ladders and UI behavior.

# ---- PlayerActivity: explicit LoadControl for live/preview and VOD fallback path.
p = PLAYER.read_text(encoding="utf-8")
if "private DefaultLoadControl iptvLoadControl()" not in p:
    anchor = "    private DefaultLoadErrorHandlingPolicy loadErrorPolicy() {\n"
    if anchor not in p:
        # Stage3 should have created this helper before Stage5 executes.
        raise SystemExit("stage5: Stage3 loadErrorPolicy anchor missing")
    helper = '''    private DefaultLoadControl iptvLoadControl() {\n        // IPTV profile: start quickly, but maintain enough runway to absorb jitter.\n        // Live: 15s minimum / 50s max / 1s start / 2s rebuffer.\n        // VOD: 25s minimum / 60s max / 1.2s start / 2.5s rebuffer.\n        int minBufferMs = isLive() ? 15_000 : 25_000;\n        int maxBufferMs = isLive() ? 50_000 : 60_000;\n        int startMs = isLive() ? 1_000 : 1_200;\n        int rebufferMs = isLive() ? 2_000 : 2_500;\n        return new DefaultLoadControl.Builder()\n                .setBufferDurationsMsForStreaming(minBufferMs, maxBufferMs, startMs, rebufferMs)\n                .setPrioritizeTimeOverSizeThresholdsForStreaming(true)\n                .build();\n    }\n\n'''
    p = p.replace(anchor, helper + anchor, 1)

# Attach the explicit load control to every PlayerActivity ExoPlayer.Builder that is
# not already configured. Reconstruction-safe across earlier stages.
def patch_builder(match):
    block = match.group(0)
    if ".setLoadControl(" in block:
        return block
    return block.replace("new ExoPlayer.Builder(this)",
                         "new ExoPlayer.Builder(this)\n                .setLoadControl(iptvLoadControl())", 1)

p, builder_count = re.subn(
    r'new ExoPlayer\.Builder\(this\)(?:(?!\.build\(\)).){0,1800}?\.build\(\)',
    patch_builder, p, flags=re.S)
if builder_count < 1 and ".setLoadControl(iptvLoadControl())" not in p:
    raise SystemExit("stage5: PlayerActivity ExoPlayer.Builder not found")

# Diagnostic breadcrumb makes field reports prove the policy is running.
if '"stage5-buffer-profile"' not in p:
    marker = "        PlaybackTransportFactory.warmUpCronet(this);\n"
    if marker not in p:
        raise SystemExit("stage5: onCreate transport warmup anchor missing")
    p = p.replace(marker, marker +
        '        PlaybackDiagnostics.marker(this, "stage5-buffer-profile", kind == null ? "" : kind, "", "", "",\n'
        '                "live=15/50/1/2 vod=25/60/1.2/2.5 time-priority=true");\n', 1)
PLAYER.write_text(p, encoding="utf-8")

# ---- VOD activity: explicit streaming buffer policy too, so standalone VOD paths
# don't silently fall back to Media3 defaults.
v = VOD.read_text(encoding="utf-8")
if "private DefaultLoadControl vodLoadControl()" not in v:
    # Ensure import exists.
    if "import androidx.media3.exoplayer.DefaultLoadControl;" not in v:
        anchor = "import androidx.media3.exoplayer.ExoPlayer;\n"
        if anchor not in v:
            raise SystemExit("stage5: VOD ExoPlayer import anchor missing")
        v = v.replace(anchor, "import androidx.media3.exoplayer.DefaultLoadControl;\n" + anchor, 1)
    anchor = "    private DataSource.Factory createDataSourceFactory() {\n"
    if anchor not in v:
        raise SystemExit("stage5: VOD data-source helper anchor missing")
    helper = '''    private DefaultLoadControl vodLoadControl() {\n        return new DefaultLoadControl.Builder()\n                .setBufferDurationsMsForStreaming(25_000, 60_000, 1_200, 2_500)\n                .setPrioritizeTimeOverSizeThresholdsForStreaming(true)\n                .build();\n    }\n\n'''
    v = v.replace(anchor, helper + anchor, 1)

def patch_vod_builder(match):
    block = match.group(0)
    if ".setLoadControl(" in block:
        return block
    return block.replace("new ExoPlayer.Builder(this)",
                         "new ExoPlayer.Builder(this)\n                .setLoadControl(vodLoadControl())", 1)

v, vod_builders = re.subn(
    r'new ExoPlayer\.Builder\(this\)(?:(?!\.build\(\)).){0,1800}?\.build\(\)',
    patch_vod_builder, v, flags=re.S)
if vod_builders < 1 and ".setLoadControl(vodLoadControl())" not in v:
    raise SystemExit("stage5: VOD ExoPlayer.Builder not found")
VOD.write_text(v, encoding="utf-8")

# ---- Transport parity: Cronet previously got only User-Agent while default HTTP got
# Referer/Origin/Accept/identity/keep-alive. Some IPTV providers gate on those headers.
t = TRANSPORT.read_text(encoding="utf-8")
if "private static Map<String, String> requestHeaders(String referer)" not in t:
    anchor = "    static DataSource.Factory create(Context context, boolean preferCronet, Executor executor,\n"
    if anchor not in t:
        raise SystemExit("stage5: transport create anchor missing")
    helper = '''    private static Map<String, String> requestHeaders(String referer) {\n        Map<String, String> headers = new HashMap<>();\n        headers.put("Accept", "*/*");\n        headers.put("Accept-Encoding", "identity");\n        headers.put("Connection", "keep-alive");\n        headers.put("Icy-MetaData", "1");\n        if (referer != null && !referer.isEmpty()) {\n            headers.put("Referer", referer);\n            try {\n                android.net.Uri uri = android.net.Uri.parse(referer);\n                if (uri.getScheme() != null && uri.getHost() != null) {\n                    String origin = uri.getScheme() + "://" + uri.getHost()\n                            + (uri.getPort() > 0 ? ":" + uri.getPort() : "");\n                    headers.put("Origin", origin);\n                }\n            } catch (Exception ignored) {}\n        }\n        return headers;\n    }\n\n'''
    t = t.replace(anchor, helper + anchor, 1)

# Replace duplicate header construction in create() with shared parity map.
start = t.find("        Map<String, String> headers = new HashMap<>();", t.find("int compatibilityProfile"))
if start >= 0:
    end_marker = "        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()"
    end = t.find(end_marker, start)
    if end < 0:
        raise SystemExit("stage5: default HTTP header block end missing")
    t = t[:start] + "        Map<String, String> headers = requestHeaders(referer);\n" + t[end:]

# Cronet parity: same headers + explicit connect/read budgets + reset timeout across redirects.
old = """                return new DefaultDataSource.Factory(\n                        context,\n                        new CronetDataSource.Factory(engine, executor).setUserAgent(userAgent));"""
new = """                Map<String, String> headers = requestHeaders(referer);\n                CronetDataSource.Factory cronet = new CronetDataSource.Factory(engine, executor)\n                        .setUserAgent(userAgent)\n                        .setConnectionTimeoutMs(connectTimeoutMs)\n                        .setReadTimeoutMs(readTimeoutMs)\n                        .setResetTimeoutOnRedirects(true)\n                        .setDefaultRequestProperties(headers);\n                Log.i(TAG, "transport=cronet-gms headers=parity connect=" + connectTimeoutMs\n                        + " read=" + readTimeoutMs + " profile=" + compatibilityProfile);\n                return new DefaultDataSource.Factory(context, cronet);"""
if old in t:
    t = t.replace(old, new, 1)
elif ".setDefaultRequestProperties(headers)" not in t or ".setResetTimeoutOnRedirects(true)" not in t:
    raise SystemExit("stage5: Cronet factory anchor missing")
TRANSPORT.write_text(t, encoding="utf-8")

# ---- Version stamp.
g = GRADLE.read_text(encoding="utf-8")
g = re.sub(r'versionCode\s*=\s*\d+', 'versionCode = 1000358', g, count=1)
g = re.sub(r'versionName\s*=\s*"[^"]*"', 'versionName = "v340-playback-hotfix-stage5"', g, count=1)
GRADLE.write_text(g, encoding="utf-8")

checks = {
    PLAYER: [
        "private DefaultLoadControl iptvLoadControl()",
        "setBufferDurationsMsForStreaming(minBufferMs, maxBufferMs, startMs, rebufferMs)",
        "setPrioritizeTimeOverSizeThresholdsForStreaming(true)",
        ".setLoadControl(iptvLoadControl())",
        "stage5-buffer-profile",
    ],
    VOD: [
        "private DefaultLoadControl vodLoadControl()",
        "setBufferDurationsMsForStreaming(25_000, 60_000, 1_200, 2_500)",
        ".setLoadControl(vodLoadControl())",
    ],
    TRANSPORT: [
        "private static Map<String, String> requestHeaders(String referer)",
        ".setConnectionTimeoutMs(connectTimeoutMs)",
        ".setReadTimeoutMs(readTimeoutMs)",
        ".setResetTimeoutOnRedirects(true)",
        ".setDefaultRequestProperties(headers)",
        "transport=cronet-gms headers=parity",
    ],
    GRADLE: [
        "versionCode = 1000358",
        'versionName = "v340-playback-hotfix-stage5"',
    ],
}
for path, markers in checks.items():
    text = path.read_text(encoding="utf-8")
    for marker in markers:
        if marker not in text:
            raise SystemExit(f"stage5 invariant missing {path.name}: {marker}")

print("v340 playback-core hotfix stage5 applied: explicit IPTV LoadControl + Cronet/header parity + redirect-safe transport budgets")
