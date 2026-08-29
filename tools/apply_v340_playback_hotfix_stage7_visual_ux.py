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

# Stage7: visible premium UX + useful playback-state surface. No screen restructuring.

u = UI.read_text(encoding="utf-8")
# Smart metadata chips: 4K/HDR/HEVC become visually distinct while regular chips stay subtle.
old = '''        chip.setBackground(gradientPanel(context, Color.argb(220, 43, 28, 72), Color.argb(210, 20, 17, 38), 10, Color.rgb(91, 61, 133)));'''
if old in u:
    new = '''        String upper = value == null ? "" : value.toUpperCase(java.util.Locale.US);\n        boolean premium = upper.contains("4K") || upper.contains("UHD") || upper.contains("HDR")\n                || upper.contains("HEVC") || upper.contains("H265") || upper.contains("H.265");\n        chip.setBackground(premium\n                ? gradientPanel(context, Color.argb(240, 104, 39, 184), Color.argb(225, 41, 19, 86), 10, Color.rgb(203, 150, 255))\n                : gradientPanel(context, Color.argb(220, 43, 28, 72), Color.argb(210, 20, 17, 38), 10, Color.rgb(91, 61, 133)));'''
    u = u.replace(old, new, 1)

# Reusable status chip for player overlays.
if "static TextView statusChip(Context context, String value, boolean accent)" not in u:
    anchor = "    static EditText input(Context context, String hint, boolean numeric) {\n"
    helper = '''    static TextView statusChip(Context context, String value, boolean accent) {\n        TextView view = title(context, value, 11);\n        view.setGravity(Gravity.CENTER);\n        view.setSingleLine(true);\n        view.setTextDirection(View.TEXT_DIRECTION_LTR);\n        view.setPadding(dp(context, 12), 0, dp(context, 12), 0);\n        view.setBackground(accent\n                ? gradientPanel(context, Color.argb(232, 116, 43, 214), Color.argb(220, 54, 22, 113), 12, Color.rgb(214, 170, 255))\n                : gradientPanel(context, Color.argb(212, 21, 20, 35), Color.argb(205, 11, 11, 22), 12, STROKE));\n        return view;\n    }\n\n'''
    if anchor not in u:
        raise SystemExit("stage7: BlofyUi input anchor missing")
    u = u.replace(anchor, helper + anchor, 1)
UI.write_text(u, encoding="utf-8")

p = PLAYER.read_text(encoding="utf-8")
if "private TextView playbackStatusChip;" not in p:
    anchor = "    private TextView titleView;\n"
    if anchor not in p:
        raise SystemExit("stage7: PlayerActivity title field anchor missing")
    p = p.replace(anchor, anchor + "    private TextView playbackStatusChip;\n", 1)

if "stage7-visible-playback-status" not in p:
    anchor = "        root.addView(titleView, titleParams);\n"
    if anchor not in p:
        raise SystemExit("stage7: player title overlay anchor missing")
    overlay = '''\n        String statusLabel = isLive() ? "● LIVE" : "VOD";\n        if (isUltraHd()) statusLabel += "   4K";\n        playbackStatusChip = BlofyUi.statusChip(this, statusLabel, isLive() || isUltraHd());\n        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(\n                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34), Gravity.TOP | Gravity.LEFT);\n        statusParams.leftMargin = dp(26);\n        statusParams.topMargin = dp(22);\n        root.addView(playbackStatusChip, statusParams);\n        PlaybackDiagnostics.marker(this, "stage7-visible-playback-status", kind, id, extension,\n                sourceVariant, "label=" + statusLabel);\n'''
    p = p.replace(anchor, anchor + overlay, 1)

# Update status chip once a frame renders to provide a visible success indication.
ff = "    @Override public void onRenderedFirstFrame() {\n"
if ff in p and "playbackStatusChip.setAlpha(1f);" not in p:
    p = p.replace(ff, ff +
        "        if (playbackStatusChip != null) { playbackStatusChip.setAlpha(1f); playbackStatusChip.setText((isLive() ? \"● LIVE\" : \"VOD\") + (isUltraHd() ? \"   4K\" : \"\")); }\n", 1)
PLAYER.write_text(p, encoding="utf-8")

d = DETAILS.read_text(encoding="utf-8")
# Add a visible premium quality chip when the title/extension strongly signals UHD/HEVC.
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
    UI: ["statusChip(Context context", "boolean premium", "Color.rgb(203, 150, 255)"],
    PLAYER: ["private TextView playbackStatusChip;", "stage7-visible-playback-status", "● LIVE"],
    DETAILS: ["stage7QualityLabel", 'addMetaChip(chips, "4K UHD")'],
    GRADLE: ["versionCode = 1000360", 'versionName = "v340-playback-hotfix-stage7-premium"'],
}
for path, markers in checks.items():
    text = path.read_text(encoding="utf-8")
    for marker in markers:
        if marker not in text:
            raise SystemExit(f"stage7 invariant missing {path.name}: {marker}")

print("v340 stage7 premium applied: smart quality chips + visible LIVE/VOD/4K status + premium details metadata")
