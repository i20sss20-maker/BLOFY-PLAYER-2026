#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "BLOFY-ANDROID-2026/app"
JAVA = APP / "src/main/java/tv/blofy/player"
IMPORTER = JAVA / "PackageImporter.java"
GRADLE = APP / "build.gradle.kts"

s = IMPORTER.read_text(encoding="utf-8")

# Keep source identity stable when the reconstructed importer still exposes a
# raw session identity assignment. Newer reconstruction layers may already use
# a scoped/hashed identity directly; in that case do not force an older shape.
stable_raw = 'String raw = session.kind + "|" + session.serverName + "|" + username;'
method = re.search(
    r'private\s+static\s+String\s+sourceIdentity\s*\([^)]*\)\s*\{(?P<body>.*?)\n\s*\}',
    s,
    re.S,
)
if method and stable_raw not in method.group(0):
    body = method.group("body")
    raw = re.search(r'String\s+raw\s*=\s*[^;]+;', body, re.S)
    if raw:
        abs_start = method.start("body") + raw.start()
        abs_end = method.start("body") + raw.end()
        s = s[:abs_start] + stable_raw + s[abs_end:]

# Strict complete-cache gate. Never reopen a partially imported package, but a
# completed local package for the same source must open immediately.
if 'boolean cacheComplete = "complete".equals(syncState)' not in s:
    replacement = '''        String syncState = database.metadata("sync_state", "");
        boolean cacheComplete = "complete".equals(syncState);
        if (sourceIdentity.equals(activeSource) && (cachedLive + cachedMovies + cachedSeries) > 0
                && cacheComplete) {
            String profile = database.metadata("playback_profile", "Media3 مباشر");
            emit(100, "البيانات جاهزة", "تم فتح النسخة المحفوظة على الجهاز بدون إعادة تحميل");
            return new Result(cachedLive, cachedMovies, cachedSeries, profile);
        }

'''
    start = s.find('        if (sourceIdentity.equals(activeSource) && (cachedLive + cachedMovies + cachedSeries) > 0')
    if start >= 0:
        end_marker = '            return new Result(cachedLive, cachedMovies, cachedSeries, profile);\n        }'
        end = s.find(end_marker, start)
        if end < 0:
            raise SystemExit("R11E4: package fast-path end missing")
        end += len(end_marker)
        s = s[:start] + replacement.rstrip() + s[end:]
    else:
        match = re.search(r'(?m)^\s*int\s+cachedSeries\s*=\s*database\.count\("series"\)\s*;\s*$', s)
        if not match:
            raise SystemExit("R11E4: cached-series insertion anchor missing")
        s = s[:match.end()] + "\n" + replacement + s[match.end():]

# Saved playlists get the same instant-local path before health/session calls.
# Only atomic imports whose sync_state reached complete are eligible.
if "فتح فوري من الحفظ المحلي" not in s:
    run_sig = re.search(r'(?m)^\s*Result\s+run\s*\(\s*\)\s+throws\s+Exception\s*\{', s)
    if not run_sig:
        raise SystemExit("R11E4: run anchor missing")
    insert_at = run_sig.end()
    inject = '''
        if (!playlistId.isEmpty() && !"current-session".equals(playlistId)) {
            String scopedSource = CatalogScope.forPlaylist(playlistId);
            String cachedSource = database.metadata("active_source_id", "");
            String cachedState = database.metadata("sync_state", "");
            int localLive = database.count("live");
            int localMovies = database.count("movies");
            int localSeries = database.count("series");
            if (scopedSource.equals(cachedSource) && (localLive + localMovies + localSeries) > 0
                    && "complete".equals(cachedState)) {
                String cachedProfile = database.metadata("playback_profile", "Media3 مباشر");
                emit(100, "البيانات جاهزة", "فتح فوري من الحفظ المحلي");
                return new Result(localLive, localMovies, localSeries, cachedProfile);
            }
        }'''
    s = s[:insert_at] + inject + s[insert_at:]

IMPORTER.write_text(s, encoding="utf-8")

g = GRADLE.read_text(encoding="utf-8")
g = re.sub(r'versionCode\s*=\s*\d+', 'versionCode = 1000349', g, count=1)
g = re.sub(r'versionName\s*=\s*"[^"]*"', 'versionName = "v340-full-stability-r11e-stage4"', g, count=1)
GRADLE.write_text(g, encoding="utf-8")

checks = {
    IMPORTER: [
        'boolean cacheComplete = "complete".equals(syncState)',
        'String scopedSource = CatalogScope.forPlaylist(playlistId);',
        'فتح فوري من الحفظ المحلي',
        '&& "complete".equals(cachedState)',
        'تم فتح النسخة المحفوظة على الجهاز بدون إعادة تحميل',
    ],
    GRADLE: ['versionCode = 1000349', 'v340-full-stability-r11e-stage4'],
}
for path, markers in checks.items():
    text = path.read_text(encoding="utf-8")
    for marker in markers:
        if marker not in text:
            raise SystemExit(f"R11E4 invariant missing {path.name}: {marker}")

print("R11E stage4 applied: strict complete-cache gate + instant saved-playlist local open + compatible source identity")
