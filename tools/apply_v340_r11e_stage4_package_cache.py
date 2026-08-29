#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "BLOFY-ANDROID-2026/app"
JAVA = APP / "src/main/java/tv/blofy/player"
IMPORTER = JAVA / "PackageImporter.java"
GRADLE = APP / "build.gradle.kts"

s = IMPORTER.read_text(encoding="utf-8")

# Stable source identity: cosmetic playlist/session name changes must not force
# a complete catalog download when server/account identity is unchanged.
s = s.replace(
    'String raw = session.kind + "|" + session.serverName + "|" + session.name + "|" + username;',
    'String raw = session.kind + "|" + session.serverName + "|" + username;',
    1,
)

# Replace the R9 cache gate using a structural boundary instead of a fragile
# whitespace regex. This preserves the full-download-first rule while opening
# a previously committed catalog instantly on later launches.
if 'boolean cacheComplete = "complete".equals(syncState)' not in s:
    start = s.find('        if (sourceIdentity.equals(activeSource) && (cachedLive + cachedMovies + cachedSeries) > 0')
    if start < 0:
        raise SystemExit("R11E4: package fast-path start missing")
    end_marker = '            return new Result(cachedLive, cachedMovies, cachedSeries, profile);\n        }'
    end = s.find(end_marker, start)
    if end < 0:
        raise SystemExit("R11E4: package fast-path end missing")
    end += len(end_marker)
    replacement = '''        String syncState = database.metadata("sync_state", "");
        boolean cacheComplete = "complete".equals(syncState);
        if (sourceIdentity.equals(activeSource) && (cachedLive + cachedMovies + cachedSeries) > 0
                && cacheComplete) {
            String profile = database.metadata("playback_profile", "Media3 مباشر");
            emit(100, "البيانات جاهزة", "تم فتح النسخة المحفوظة على الجهاز بدون إعادة تحميل");
            return new Result(cachedLive, cachedMovies, cachedSeries, profile);
        }'''
    s = s[:start] + replacement + s[end:]

# Saved playlists get the same instant-local path before any health/session
# request. Only a catalog whose atomic import reached sync_state=complete is
# eligible; interrupted/failed imports keep the previous committed package.
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
                    && "complete".equals(state)) {
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
        '&& "complete".equals(state)',
    ],
    GRADLE: ['versionCode = 1000349', 'v340-full-stability-r11e-stage4'],
}
for path, markers in checks.items():
    text = path.read_text(encoding="utf-8")
    for marker in markers:
        if marker not in text:
            raise SystemExit(f"R11E4 invariant missing {path.name}: {marker}")

print("R11E stage4 applied: stable source fingerprint + strict complete-cache gate + instant saved-playlist local open")
