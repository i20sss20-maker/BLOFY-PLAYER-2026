#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player"
PLAYER = JAVA / "PlayerActivity.java"
VOD = JAVA / "VodPlayerActivity.java"
TRANSPORT = JAVA / "PlaybackTransportFactory.java"
GRADLE = ROOT / "BLOFY-ANDROID-2026/app/build.gradle.kts"

# Stage5: IPTV buffer policy + Cronet/default-HTTP parity.
# Reconstruction-safe: VOD already has an adaptive DefaultLoadControl in current R11D/R11E.

p = PLAYER.read_text(encoding="utf-8")
if "private DefaultLoadControl iptvLoadControl()" not in p:
    anchor = "    private DefaultLoadErrorHandlingPolicy loadErrorPolicy() {\n"
    if anchor not in p:
        raise SystemExit("stage5: Stage3 loadErrorPolicy anchor missing")
    helper = '''    private DefaultLoadControl iptvLoadControl() {\n        int minBufferMs = isLive() ? 15_000 : 25_000;\n        int maxBufferMs = isLive() ? 50_000 : 60_000;\n        int startMs = isLive() ? 1_000 : 1_200;\n        int rebufferMs = isLive() ? 2_000 : 2_500;\n        return new DefaultLoadControl.Builder()\n                .setBufferDurationsMs(minBufferMs, maxBufferMs, startMs, rebufferMs)\n                .setPrioritizeTimeOverSizeThresholds(true)\n                .build();\n    }\n\n'''
    p = p.replace(anchor, helper + anchor, 1)

# Attach to any ExoPlayer.Builder form: Builder(this) or Builder(this, renderers).
if ".setLoadControl(iptvLoadControl())" not in p:
    pattern = re.compile(r'(new ExoPlayer\.Builder\(this(?:,\s*[^\)]*)?\))')
    p, n = pattern.subn(r'\1\n                .setLoadControl(iptvLoadControl())', p, count=1)
    if n == 0:
        raise SystemExit("stage5: PlayerActivity ExoPlayer.Builder not found")

if '"stage5-buffer-profile"' not in p:
    marker = "        PlaybackTransportFactory.warmUpCronet(this);\n"
    if marker not in p:
        raise SystemExit("stage5: onCreate transport warmup anchor missing")
    p = p.replace(marker, marker +
        '        PlaybackDiagnostics.marker(this, "stage5-buffer-profile", kind == null ? "" : kind, "", "", "",\n'
        '                "live=15/50/1/2 vod=adaptive-existing time-priority=true");\n', 1)
PLAYER.write_text(p, encoding="utf-8")

# VOD already owns an adaptive fast/auto/stable LoadControl selected from settings/device profile.
# Do not replace it with a second fixed policy. Assert it remains wired.
v = VOD.read_text(encoding="utf-8")
if "DefaultLoadControl.Builder()" not in v or ".setLoadControl(load)" not in v:
    raise SystemExit("stage5: existing adaptive VOD LoadControl not found")

# Transport parity: share headers between DefaultHttpDataSource and Cronet.
t = TRANSPORT.read_text(encoding="utf-8")
if "private static Map<String, String> requestHeaders(String referer)" not in t:
    anchor = "    static DataSource.Factory create(Context context, boolean preferCronet, Executor executor,\n"
    if anchor not in t:
        raise SystemExit("stage5: transport create anchor missing")
    helper = '''    private static Map<String, String> requestHeaders(String referer) {\n        Map<String, String> headers = new HashMap<>();\n        headers.put("Accept", "*/*");\n        headers.put("Accept-Encoding", "identity");\n        headers.put("Connection", "keep-alive");\n        headers.put("Icy-MetaData", "1");\n        if (referer != null && !referer.isEmpty()) {\n            headers.put("Referer", referer);\n            try {\n                android.net.Uri uri = android.net.Uri.parse(referer);\n                if (uri.getScheme() != null && uri.getHost() != null) {\n                    String origin = uri.getScheme() + "://" + uri.getHost()\n                            + (uri.getPort() > 0 ? ":" + uri.getPort() : "");\n                    headers.put("Origin", origin);\n                }\n            } catch (Exception ignored) {}\n        }\n        return headers;\n    }\n\n'''
    t = t.replace(anchor, helper + anchor, 1)

# Replace duplicated default HTTP header block if still present.
create_pos = t.find("int compatibilityProfile, String referer")
start = t.find("        Map<String, String> headers = new HashMap<>();", create_pos)
if start >= 0:
    end_marker = "        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()"
    end = t.find(end_marker, start)
    if end < 0:
        raise SystemExit("stage5: default HTTP header block end missing")
    t = t[:start] + "        Map<String, String> headers = requestHeaders(referer);\n" + t[end:]

old = """                return new DefaultDataSource.Factory(\n                        context,\n                        new CronetDataSource.Factory(engine, executor).setUserAgent(userAgent));"""
new = """                Map<String, String> headers = requestHeaders(referer);\n                CronetDataSource.Factory cronet = new CronetDataSource.Factory(engine, executor)\n                        .setUserAgent(userAgent)\n                        .setConnectionTimeoutMs(connectTimeoutMs)\n                        .setReadTimeoutMs(readTimeoutMs)\n                        .setResetTimeoutOnRedirects(true)\n                        .setDefaultRequestProperties(headers);\n                Log.i(TAG, "transport=cronet-gms headers=parity connect=" + connectTimeoutMs\n                        + " read=" + readTimeoutMs + " profile=" + compatibilityProfile);\n                return new DefaultDataSource.Factory(context, cronet);"""
if old in t:
    t = t.replace(old, new, 1)
elif "transport=cronet-gms headers=parity" not in t:
    raise SystemExit("stage5: Cronet factory anchor missing")
TRANSPORT.write_text(t, encoding="utf-8")

g = GRADLE.read_text(encoding="utf-8")
g = re.sub(r'versionCode\s*=\s*\d+', 'versionCode = 1000358', g, count=1)
g = re.sub(r'versionName\s*=\s*"[^"]*"', 'versionName = "v340-playback-hotfix-stage5"', g, count=1)
GRADLE.write_text(g, encoding="utf-8")

checks = {
    PLAYER: ["private DefaultLoadControl iptvLoadControl()", ".setLoadControl(iptvLoadControl())", "stage5-buffer-profile"],
    VOD: ["DefaultLoadControl.Builder()", ".setLoadControl(load)"],
    TRANSPORT: ["requestHeaders(String referer)", ".setConnectionTimeoutMs(connectTimeoutMs)", ".setReadTimeoutMs(readTimeoutMs)", ".setResetTimeoutOnRedirects(true)", ".setDefaultRequestProperties(headers)", "transport=cronet-gms headers=parity"],
    GRADLE: ["versionCode = 1000358", 'versionName = "v340-playback-hotfix-stage5"'],
}
for path, markers in checks.items():
    text = path.read_text(encoding="utf-8")
    for marker in markers:
        if marker not in text:
            raise SystemExit(f"stage5 invariant missing {path.name}: {marker}")
print("v340 stage5 applied: live IPTV LoadControl + existing adaptive VOD buffer preserved + Cronet/header parity")
