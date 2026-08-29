#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "BLOFY-ANDROID-2026/app"
JAVA = APP / "src/main/java/tv/blofy/player"
PROFILE = JAVA / "DeviceCapabilityProfile.java"
PLAYER = JAVA / "PlayerActivity.java"
SMART = JAVA / "PlaybackSmartCache.java"
GRADLE = APP / "build.gradle.kts"

# --- Device-adaptive playback/cache budget ---
p = PROFILE.read_text(encoding="utf-8")
if "long vodCacheBytes()" not in p:
    insert_at = p.rfind("\n}")
    if insert_at < 0:
        raise SystemExit("R11E6: profile class end missing")
    helper = r'''

    long vodCacheBytes() {
        int mb;
        if (constrained) mb = Math.max(96, Math.min(192, memoryClassMb));
        else if (requestedMode == PerformanceMode.QUALITY) mb = Math.max(384, Math.min(768, memoryClassMb * 2));
        else mb = Math.max(192, Math.min(512, memoryClassMb));
        return (long) mb * 1024L * 1024L;
    }

    int adaptiveVlcCacheMs(boolean live, boolean ultraHd) {
        if (constrained) return live ? (ultraHd ? 700 : 420) : (ultraHd ? 950 : 650);
        if (requestedMode == PerformanceMode.QUALITY) return live ? (ultraHd ? 900 : 600) : (ultraHd ? 1200 : 800);
        return live ? (ultraHd ? 780 : 500) : (ultraHd ? 1050 : 720);
    }

    boolean prefersSeamlessFrameRateMatching() {
        return !constrained;
    }
'''
    p = p[:insert_at] + helper + p[insert_at:]
PROFILE.write_text(p, encoding="utf-8")

# --- Adaptive Media3/VLC runtime behavior ---
s = PLAYER.read_text(encoding="utf-8")
if "r11e6-adaptive-vlc-cache" not in s:
    pattern = re.compile(r'''    private int vlcCacheMs\(\) \{.*?\n    \}\n''', re.S)
    m = pattern.search(s)
    if not m:
        raise SystemExit("R11E6: vlcCacheMs anchor missing")
    body = '''    private int vlcCacheMs() {\n        String mode = playerSetting(SettingsActivity.KEY_BUFFER, "auto");\n        if ("fast".equals(mode)) return isUltraHd() ? 500 : 300;\n        if ("stable".equals(mode)) return isUltraHd() ? 900 : 700;\n        int value = DeviceCapabilityProfile.detect(this).adaptiveVlcCacheMs(isLive(), isUltraHd());\n        PlaybackDiagnostics.marker(this, "r11e6-adaptive-vlc-cache", kind, id, extension,\n                sourceVariant, "ms=" + value);\n        return value;\n    }\n'''
    s = s[:m.start()] + body + s[m.end():]

if "r11e6-frame-rate-matching" not in s:
    anchor = "        player = new ExoPlayer.Builder(this, renderers).setMediaSourceFactory(mediaSourceFactory)\n                .setLoadControl(createLoadControl()).build();\n"
    if anchor not in s:
        raise SystemExit("R11E6: ExoPlayer build anchor missing")
    inject = anchor + '''        DeviceCapabilityProfile capability = DeviceCapabilityProfile.detect(this);\n        if (capability.prefersSeamlessFrameRateMatching()) {\n            player.setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS);\n            PlaybackDiagnostics.marker(this, "r11e6-frame-rate-matching", kind, id, extension,\n                    sourceVariant, "strategy=seamless");\n        }\n'''
    s = s.replace(anchor, inject, 1)

PLAYER.write_text(s, encoding="utf-8")

# --- Smart VOD cache sized to device, not a single 192 MB constant ---
c = SMART.read_text(encoding="utf-8")
if "DeviceCapabilityProfile.detect(app).vodCacheBytes()" not in c:
    c = c.replace("    private static final long MAX_BYTES = 192L * 1024L * 1024L;\n", "")
    old = '            cache = new SimpleCache(dir, new LeastRecentlyUsedCacheEvictor(MAX_BYTES), new StandaloneDatabaseProvider(app));'
    new = '''            long maxBytes = DeviceCapabilityProfile.detect(app).vodCacheBytes();\n            cache = new SimpleCache(dir, new LeastRecentlyUsedCacheEvictor(maxBytes), new StandaloneDatabaseProvider(app));\n            PlaybackDiagnostics.marker(app, "r11e6-adaptive-vod-cache", "vod", "", "", "cache",\n                    "bytes=" + maxBytes);'''
    if old not in c:
        raise SystemExit("R11E6: smart cache allocation anchor missing")
    c = c.replace(old, new, 1)
SMART.write_text(c, encoding="utf-8")

# Release stamp
g = GRADLE.read_text(encoding="utf-8")
g = re.sub(r'versionCode\s*=\s*\d+', 'versionCode = 1000351', g, count=1)
g = re.sub(r'versionName\s*=\s*"[^"]*"', 'versionName = "v340-full-stability-r11e-stage6"', g, count=1)
GRADLE.write_text(g, encoding="utf-8")

checks = {
    PROFILE: ["long vodCacheBytes()", "adaptiveVlcCacheMs", "prefersSeamlessFrameRateMatching"],
    PLAYER: ["r11e6-adaptive-vlc-cache", "r11e6-frame-rate-matching", "VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS"],
    SMART: ["r11e6-adaptive-vod-cache", "DeviceCapabilityProfile.detect(app).vodCacheBytes()"],
    GRADLE: ["versionCode = 1000351", "v340-full-stability-r11e-stage6"],
}
for path, markers in checks.items():
    text = path.read_text(encoding="utf-8")
    for marker in markers:
        if marker not in text:
            raise SystemExit(f"R11E6 invariant missing {path.name}: {marker}")

print("R11E stage6 applied: device-adaptive VOD cache + VLC buffer + seamless frame-rate matching")
