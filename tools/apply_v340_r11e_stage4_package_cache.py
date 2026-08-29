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
# change upstream without the underlying Xtream/playlist account changing.
old_raw = 'String raw = session.kind + "|" + session.serverName + "|" + session.name + "|" + username;'
new_raw = 'String raw = session.kind + "|" + session.serverName + "|" + username;'
if old_raw in s:
    s = s.replace(old_raw, new_raw, 1)

# Tighten the fast-path gate: only a previously complete package (or the last
# complete package retained after a failed staged refresh) may bypass sync.
old_gate = '''        if (sourceIdentity.equals(activeSource) && (cachedLive + cachedMovies + cachedSeries) > 0
                && !"in_progress".equals(database.metadata("sync_state", ""))) {
            String profile = database.metadata("playback_profile", "Media3 مباشر");
            emit(100, "البيانات جاهزة", "تم فتح النسخة المحفوظة على الجهاز بدون إعادة تحميل");
            return new Result(cachedLive, cachedMovies, cachedSeries, profile);
        }'''
new_gate = '''        String syncState = database.metadata("sync_state", "");
        boolean cacheComplete = "complete".equals(syncState) || "failed".equals(syncState);
        if (sourceIdentity.equals(activeSource) && (cachedLive + cachedMovies + cachedSeries) > 0
                && cacheComplete) {
            String profile = database.metadata("playback_profile", "Media3 مباشر");
            emit(100, "البيانات جاهزة", "تم فتح النسخة المحفوظة على الجهاز بدون إعادة تحميل");
            return new Result(cachedLive, cachedMovies, cachedSeries, profile);
        }'''
if old_gate in s:
    s = s.replace(old_gate, new_gate, 1)
elif 'boolean cacheComplete = "complete".equals(syncState)' not in s:
    raise SystemExit("R11E4: package fast-path anchor missing")

# For explicitly selected/saved playlists, skip even the health/session round
# trip when the scoped package is already complete. This prevents reconnect,
# onResume, and portal hiccups from starting expensive catalog work.
if "r11e4-explicit-cache-hit" not in s:
    anchor = '''    Result run() throws Exception {
        emit(3, "الاتصال بخادم BLOFY", "فحص الاستضافة والاستجابة");'''
    if anchor not in s:
        raise SystemExit("R11E4: run anchor missing")
    inject = '''    Result run() throws Exception {
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
                PlaybackDiagnostics.marker(api.context(), "r11e4-explicit-cache-hit", "package",
                        playlistId, "", "local", "counts=" + live + "/" + movies + "/" + series);
                return new Result(live, movies, series, profile);
            }
        }
        emit(3, "الاتصال بخادم BLOFY", "فحص الاستضافة والاستجابة");'''
    # Avoid depending on a context accessor that may not exist: inject marker
    # only when the API exposes one. The following replacement removes it and
    # keeps the fast path pure/local.
    inject = inject.replace('                PlaybackDiagnostics.marker(api.context(), "r11e4-explicit-cache-hit", "package",\n                        playlistId, "", "local", "counts=" + live + "/" + movies + "/" + series);\n', '')
    s = s.replace(anchor, inject, 1)

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
