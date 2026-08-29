#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "BLOFY-ANDROID-2026/app"
JAVA = APP / "src/main/java/tv/blofy/player"
IMPORTER = JAVA / "PackageImporter.java"
GRADLE = APP / "build.gradle.kts"

s = IMPORTER.read_text(encoding="utf-8")

# Keep current-session identity stable. The display name is cosmetic and can
# change upstream without the underlying account/source changing.
s = re.sub(
    r'String\s+raw\s*=\s*session\.kind\s*\+\s*"\\\|"\s*\+\s*session\.serverName\s*\+\s*"\\\|"\s*\+\s*session\.name\s*\+\s*"\\\|"\s*\+\s*username\s*;',
    'String raw = session.kind + "|" + session.serverName + "|" + username;',
    s,
    count=1,
)
# Simple exact fallback for the normal reconstruction.
s = s.replace(
    'String raw = session.kind + "|" + session.serverName + "|" + session.name + "|" + username;',
    'String raw = session.kind + "|" + session.serverName + "|" + username;',
    1,
)

# Tighten the existing R9 fast path without relying on exact whitespace/text.
if 'boolean cacheComplete = "complete".equals(syncState)' not in s:
    gate = re.compile(
        r'(?P<indent>\s*)if\s*\(\s*sourceIdentity\.equals\(activeSource\)\s*&&\s*'
        r'\(cachedLive\s*\+\s*cachedMovies\s*\+\s*cachedSeries\)\s*>\s*0\s*&&\s*'
        r'!"in_progress"\.equals\(database\.metadata\("sync_state",\s*""\)\)\s*\)\s*\{'
        r'(?P<body>.*?)return\s+new\s+Result\(cachedLive,\s*cachedMovies,\s*cachedSeries,\s*profile\);\s*\}',
        re.S,
    )
    m = gate.search(s)
    if not m:
        raise SystemExit("R11E4: package fast-path anchor missing")
    indent = m.group('indent')
    replacement = f'''{indent}String syncState = database.metadata("sync_state", "");
{indent}boolean cacheComplete = "complete".equals(syncState) || "failed".equals(syncState);
{indent}if (sourceIdentity.equals(activeSource) && (cachedLive + cachedMovies + cachedSeries) > 0
{indent}        && cacheComplete) {{
{indent}    String profile = database.metadata("playback_profile", "Media3 مباشر");
{indent}    emit(100, "البيانات جاهزة", "تم فتح النسخة المحفوظة على الجهاز بدون إعادة تحميل");
{indent}    return new Result(cachedLive, cachedMovies, cachedSeries, profile);
{indent}}}'''
    s = s[:m.start()] + replacement + s[m.end():]

# For explicitly selected/saved playlists, skip the health/session round trip
# when the scoped package is already complete. This prevents reconnect/onResume
# from starting a huge catalog sync again.
if "فتح فوري من الحفظ المحلي" not in s:
    run_sig = re.search(r'(?m)^\s*Result\s+run\s*\(\s*\)\s+throws\s+Exception\s*\{', s)
    if not run_sig:
        raise SystemExit("R11E4: run anchor missing")
    insert_at = run_sig.end()
    inject = '''
        if (!playlistId.isEmpty() && !"current-session".equals(playlistId)) {
            String scopedSource = CatalogScope.forPlaylist(playlistId);
            String activeSource = database.metadata("active_source_id", "");
            String state = database.metadata("sync_state", "");
            int live = database.count("live");
            int movies = database.count("movies");
            int series = database.count("series");
            if (scopedSource.equals(activeSource) && (live + movies + series) > 0
                    && ("complete".equals(state) || "failed".equals(state))) {
                String profile = database.metadata("playback_profile", "Media3 مباشر");
                emit(100, "البيانات جاهزة", "فتح فوري من الحفظ المحلي");
                return new Result(live, movies, series, profile);
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
        'String raw = session.kind + "|" + session.serverName + "|" + username;',
        'boolean cacheComplete = "complete".equals(syncState)',
        'String scopedSource = CatalogScope.forPlaylist(playlistId);',
        'فتح فوري من الحفظ المحلي',
    ],
    GRADLE: ['versionCode = 1000349', 'v340-full-stability-r11e-stage4'],
}
for path, markers in checks.items():
    text = path.read_text(encoding="utf-8")
    for marker in markers:
        if marker not in text:
            raise SystemExit(f"R11E4 invariant missing {path.name}: {marker}")

print("R11E stage4 applied: stable source fingerprint + strict complete-cache gate + instant saved-playlist local open")
