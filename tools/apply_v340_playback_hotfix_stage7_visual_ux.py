#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "BLOFY-ANDROID-2026/app"
JAVA = APP / "src/main/java/tv/blofy/player"
UI = JAVA / "BlofyUi.java"
PLAYER = JAVA / "PlayerActivity.java"
DETAILS = JAVA / "DetailsActivity.java"
GRADLE = APP / "build.gradle.kts"

# Stage7: premium metadata UX only. Keep fullscreen player clean: no LIVE/VOD status badge.

u = UI.read_text(encoding="utf-8")
old = '''        chip.setBackground(gradientPanel(context, Color.argb(220, 43, 28, 72), Color.argb(210, 20, 17, 38), 10, Color.rgb(91, 61, 133)));'''
if old in u:
    new = '''        String upper = value == null ? "" : value.toUpperCase(java.util.Locale.US);\n        boolean premium = upper.contains("4K") || upper.contains("UHD") || upper.contains("HDR")\n                || upper.contains("HEVC") || upper.contains("H265") || upper.contains("H.265");\n        chip.setBackground(premium\n                ? gradientPanel(context, Color.argb(240, 104, 39, 184), Color.argb(225, 41, 19, 86), 10, Color.rgb(203, 150, 255))\n                : gradientPanel(context, Color.argb(220, 43, 28, 72), Color.argb(210, 20, 17, 38), 10, Color.rgb(91, 61, 133)));'''
    u = u.replace(old, new, 1)
UI.write_text(u, encoding="utf-8")

# Remove any Stage7 player status chip if this script is applied over a tree that already contains it.
p = PLAYER.read_text(encoding="utf-8")
p = p.replace("    private TextView playbackStatusChip;\n", "")
p = re.sub(
    r'\n\s*String statusLabel = isLive\(\) \? "● LIVE" : "VOD";.*?PlaybackDiagnostics\.marker\(this, "stage7-visible-playback-status", kind, id, extension,\s*sourceVariant, "label=" \+ statusLabel\);\s*\n',
    '\n', p, count=1, flags=re.S)
p = re.sub(
    r'\s*if \(playbackStatusChip != null\) \{ playbackStatusChip\.setAlpha\(1f\); playbackStatusChip\.setText\(\(isLive\(\) \? "● LIVE" : "VOD"\) \+ \(isUltraHd\(\) \? "   4K" : ""\)\); \}\s*',
    '\n', p, count=1)
PLAYER.write_text(p, encoding="utf-8")

d = DETAILS.read_text(encoding="utf-8")
needle = "        addMetaChip(chips, detail.duration);\n"
if needle in d and "stage7QualityLabel" not in d:
    replacement = needle + '''        String stage7QualityLabel = ((detail.name == null ? "" : detail.name) + " "\n                + (item.name == null ? "" : item.name) + " " + (detail.extension == null ? "" : detail.extension)).toUpperCase();\n        if (stage7QualityLabel.contains("4K") || stage7QualityLabel.contains("UHD") || stage7QualityLabel.contains("2160"))\n            addMetaChip(chips, "4K UHD");\n        else if (stage7QualityLabel.contains("HEVC") || stage7QualityLabel.contains("H265") || stage7QualityLabel.contains("H.265"))\n            addMetaChip(chips, "HEVC");\n'''
    d = d.replace(needle, replacement, 1)
DETAILS.write_text(d, encoding="utf-8")

g = GRADLE.read_text(encoding="utf-8")
g = re.sub(r'versionCode\s*=\s*\d+', 'versionCode = 1000360', g, count=1)
g = re.sub(r'versionName\s*=\s*"[^"]*"', 'versionName = "v340-playback-hotfix-stage7-premium"', g, count=1)
GRADLE.write_text(g, encoding="utf-8")

checks = {
    UI: ["boolean premium", "Color.rgb(203, 150, 255)"],
    DETAILS: ["stage7QualityLabel", 'addMetaChip(chips, "4K UHD")'],
    GRADLE: ["versionCode = 1000360", 'versionName = "v340-playback-hotfix-stage7-premium"'],
}
for path, markers in checks.items():
    text = path.read_text(encoding="utf-8")
    for marker in markers:
        if marker not in text:
            raise SystemExit(f"stage7 invariant missing {path.name}: {marker}")

p = PLAYER.read_text(encoding="utf-8")
for forbidden in ("playbackStatusChip", "stage7-visible-playback-status", "● LIVE"):
    if forbidden in p:
        raise SystemExit(f"stage7 player badge cleanup failed: {forbidden}")

print("v340 stage7 premium applied: smart quality metadata; fullscreen LIVE/VOD badge removed")
