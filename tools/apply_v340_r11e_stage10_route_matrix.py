#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "BLOFY-ANDROID-2026/app"
JAVA = APP / "src/main/java/tv/blofy/player"
NEG = JAVA / "PlaybackNegotiator.java"
GRADLE = APP / "build.gradle.kts"

n = NEG.read_text(encoding="utf-8")

# Stage 10 expands only to genuinely different URL shapes/extensions. It does not
# repeat a dead URL through another engine after Stage 9 has circuit-broken it.
# This specifically covers providers whose API advertises the wrong VOD container
# or whose live endpoint works only with the alternate extension on direct/no-extension routes.

if "r11e10-live-route-matrix" not in n:
    old = '''        add(out, seen, "canonical", base, "media3");
        add(out, seen, "canonical", alternate, "media3");
        add(out, seen, "direct", base, "media3");
        add(out, seen, "no-extension", base, "media3");
        // VLC is a bounded compatibility engine, not the default path.'''
    if old not in n:
        raise SystemExit("R11E10: live candidate anchor missing")
    new = '''        add(out, seen, "canonical", base, "media3");
        add(out, seen, "canonical", alternate, "media3");
        add(out, seen, "direct", base, "media3");
        add(out, seen, "direct", alternate, "media3");
        add(out, seen, "no-extension", base, "media3");
        add(out, seen, "no-extension", alternate, "media3");
        PlaybackDiagnostics.marker(context, "r11e10-live-route-matrix", "live", "", base,
                "matrix", "base=" + base + " alternate=" + alternate);
        // VLC is a bounded compatibility engine, not the default path.'''
    n = n.replace(old, new, 1)

if "r11e10-vod-route-matrix" not in n:
    old = '''        String base = normalize(itemExtension, "mp4");
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        ArrayList<Candidate> out = new ArrayList<>();
        if (profile.fresh()) add(out, seen, profile.route, base, profile.engine);
        // Canonical is intentionally first. R10 field tests proved that learned
        // direct/no-extension retries could add 10-15 seconds before canonical.
        add(out, seen, "canonical", base, ultraHd ? "vlc" : "media3");
        add(out, seen, "canonical", base, ultraHd ? "media3" : "vlc");
        add(out, seen, "direct", base, "media3");
        add(out, seen, "no-extension", base, "media3");
        return out;'''
    if old not in n:
        raise SystemExit("R11E10: VOD candidate anchor missing")
    new = '''        String base = normalize(itemExtension, "mp4");
        String alternate = "mkv".equals(base) ? "mp4" : "mkv";
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        ArrayList<Candidate> out = new ArrayList<>();
        if (profile.fresh()) add(out, seen, profile.route, profile.extension, profile.engine);
        // Keep the provider-declared canonical shape first, then explore only distinct
        // URL/container shapes. Stage 9 prevents engine-only retries on a dead 403/404 URL.
        add(out, seen, "canonical", base, ultraHd ? "vlc" : "media3");
        add(out, seen, "canonical", base, ultraHd ? "media3" : "vlc");
        add(out, seen, "direct", base, "media3");
        add(out, seen, "no-extension", base, "media3");
        add(out, seen, "canonical", alternate, "media3");
        add(out, seen, "direct", alternate, "media3");
        add(out, seen, "no-extension", alternate, "media3");
        PlaybackDiagnostics.marker(context, "r11e10-vod-route-matrix", "vod", "", base,
                "matrix", "base=" + base + " alternate=" + alternate + " uhd=" + ultraHd);
        return out;'''
    n = n.replace(old, new, 1)

NEG.write_text(n, encoding="utf-8")

g = GRADLE.read_text(encoding="utf-8")
g = re.sub(r'versionCode\s*=\s*\d+', 'versionCode = 1000355', g, count=1)
g = re.sub(r'versionName\s*=\s*"[^"]*"', 'versionName = "v340-full-stability-r11e-stage10"', g, count=1)
GRADLE.write_text(g, encoding="utf-8")

checks = {
    NEG: [
        "r11e10-live-route-matrix",
        'add(out, seen, "direct", alternate, "media3")',
        'add(out, seen, "no-extension", alternate, "media3")',
        "r11e10-vod-route-matrix",
        'String alternate = "mkv".equals(base) ? "mp4" : "mkv";',
        'profile.route, profile.extension, profile.engine',
    ],
    GRADLE: ["versionCode = 1000355", "v340-full-stability-r11e-stage10"],
}
for path, markers in checks.items():
    text = path.read_text(encoding="utf-8")
    for marker in markers:
        if marker not in text:
            raise SystemExit(f"R11E10 invariant missing {path.name}: {marker}")

print("R11E stage10 applied: expanded distinct Live route matrix + VOD mp4/mkv/no-extension matrix without engine-only dead-URL loops")
