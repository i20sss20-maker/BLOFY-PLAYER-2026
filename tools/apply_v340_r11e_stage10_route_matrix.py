#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "BLOFY-ANDROID-2026/app"
JAVA = APP / "src/main/java/tv/blofy/player"
NEG = JAVA / "PlaybackNegotiator.java"
GRADLE = APP / "build.gradle.kts"

n = NEG.read_text(encoding="utf-8")

# Stage 10 expands only to genuinely different URL/container shapes.
# Stage 9 already blocks hard 403/404 routes across engines, so this matrix
# explores new URLs instead of pointlessly retrying the same dead URL in VLC.

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
    start = n.find("    static List<Candidate> vodCandidates(")
    if start < 0:
        raise SystemExit("R11E10: vodCandidates missing")
    next_method = n.find("\n    static ", start + 20)
    end = next_method if next_method > 0 else len(n)
    block = n[start:end]

    base_line = '        String base = normalize(itemExtension, "mp4");\n'
    if base_line not in block:
        raise SystemExit("R11E10: VOD base anchor missing")
    block = block.replace(base_line,
            base_line + '        String alternate = "mkv".equals(base) ? "mp4" : "mkv";\n', 1)

    # Preserve the exact proven extension, not merely the current item's extension.
    block = block.replace(
        'if (profile.fresh()) add(out, seen, profile.route, base, profile.engine);',
        'if (profile.fresh()) add(out, seen, profile.route, profile.extension, profile.engine);', 1)

    # Insert alternate-container candidates immediately before whichever return shape
    # previous stages installed (raw return or filterFailures return).
    return_candidates = [
        '        return filterFailures(context, providerUrl, "vod", out);',
        '        return out;'
    ]
    ret = next((r for r in return_candidates if r in block), None)
    if ret is None:
        raise SystemExit("R11E10: VOD return anchor missing")

    inject = '''        add(out, seen, "canonical", alternate, "media3");
        add(out, seen, "direct", alternate, "media3");
        add(out, seen, "no-extension", alternate, "media3");
        PlaybackDiagnostics.marker(context, "r11e10-vod-route-matrix", "vod", "", base,
                "matrix", "base=" + base + " alternate=" + alternate + " uhd=" + ultraHd);
'''
    block = block.replace(ret, inject + ret, 1)
    n = n[:start] + block + n[end:]

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
