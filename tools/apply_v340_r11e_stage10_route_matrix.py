#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "BLOFY-ANDROID-2026/app"
JAVA = APP / "src/main/java/tv/blofy/player"
NEG = JAVA / "PlaybackNegotiator.java"
GRADLE = APP / "build.gradle.kts"
DB = JAVA / "CatalogDatabase.java"
IMPORTER = JAVA / "PackageImporter.java"

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

# Stage 11 hardens the one-time huge-package cache without changing the DB schema.
# A successful atomic import records exact per-type counts. Future launches may use the
# completed local package immediately, but if known counts no longer match we refuse the
# fast path and rebuild through staging while the old package remains readable.
d = DB.read_text(encoding="utf-8")

if "r11e11TypeCount(" not in d:
    anchor = "    /** Atomically replaces one source partition only after every page succeeded. */\n"
    if anchor not in d:
        raise SystemExit("R11E11: CatalogDatabase commit anchor missing")
    helper = '''    private static int r11e11TypeCount(SQLiteDatabase database, String sourceId, String type) {
        try (Cursor cursor = database.rawQuery(
                "SELECT COUNT(*) FROM media WHERE source_id=? AND type=?",
                new String[]{sourceId, type})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

'''
    d = d.replace(anchor, helper + anchor, 1)

if 'putMetadata(database, "package_fingerprint"' not in d:
    anchor = '''            putMetadata(database, "playback_profile", playbackProfile);
            putMetadata(database, "last_sync", String.valueOf(System.currentTimeMillis()));'''
    if anchor not in d:
        raise SystemExit("R11E11: CatalogDatabase metadata anchor missing")
    inject = '''            putMetadata(database, "playback_profile", playbackProfile);
            int r11e11Live = r11e11TypeCount(database, cleanSource, "live");
            int r11e11Movies = r11e11TypeCount(database, cleanSource, "movies");
            int r11e11Series = r11e11TypeCount(database, cleanSource, "series");
            putMetadata(database, "package_count_live", String.valueOf(r11e11Live));
            putMetadata(database, "package_count_movies", String.valueOf(r11e11Movies));
            putMetadata(database, "package_count_series", String.valueOf(r11e11Series));
            putMetadata(database, "package_fingerprint",
                    cleanSource + "|" + r11e11Live + "|" + r11e11Movies + "|" + r11e11Series);
            putMetadata(database, "last_sync", String.valueOf(System.currentTimeMillis()));'''
    d = d.replace(anchor, inject, 1)

DB.write_text(d, encoding="utf-8")

p = IMPORTER.read_text(encoding="utf-8")

# Main current-session cache gate installed by Stage 4.
if "boolean cacheIntegrityOk" not in p:
    anchor = '''        String syncState = database.metadata("sync_state", "");
        boolean cacheComplete = "complete".equals(syncState);'''
    if anchor not in p:
        raise SystemExit("R11E11: PackageImporter complete-cache anchor missing")
    inject = '''        String syncState = database.metadata("sync_state", "");
        boolean cacheComplete = "complete".equals(syncState);
        String expectedLive = database.metadata("package_count_live", "");
        String expectedMovies = database.metadata("package_count_movies", "");
        String expectedSeries = database.metadata("package_count_series", "");
        boolean packageFingerprintKnown = !expectedLive.isEmpty() && !expectedMovies.isEmpty()
                && !expectedSeries.isEmpty();
        boolean cacheIntegrityOk = !packageFingerprintKnown
                || (expectedLive.equals(String.valueOf(cachedLive))
                && expectedMovies.equals(String.valueOf(cachedMovies))
                && expectedSeries.equals(String.valueOf(cachedSeries)));'''
    p = p.replace(anchor, inject, 1)

    old = '''        if (sourceIdentity.equals(activeSource) && (cachedLive + cachedMovies + cachedSeries) > 0
                && cacheComplete) {'''
    new = '''        if (sourceIdentity.equals(activeSource) && (cachedLive + cachedMovies + cachedSeries) > 0
                && cacheComplete && cacheIntegrityOk) {'''
    if old not in p:
        raise SystemExit("R11E11: PackageImporter main fast-path anchor missing")
    p = p.replace(old, new, 1)

# Saved-playlist fast path from Stage 4 gets the same integrity test.
if "boolean savedCacheIntegrityOk" not in p:
    anchor = '''            int localLive = database.count("live");
            int localMovies = database.count("movies");
            int localSeries = database.count("series");'''
    if anchor not in p:
        raise SystemExit("R11E11: saved-playlist count anchor missing")
    inject = '''            int localLive = database.count("live");
            int localMovies = database.count("movies");
            int localSeries = database.count("series");
            String savedExpectedLive = database.metadata("package_count_live", "");
            String savedExpectedMovies = database.metadata("package_count_movies", "");
            String savedExpectedSeries = database.metadata("package_count_series", "");
            boolean savedFingerprintKnown = !savedExpectedLive.isEmpty() && !savedExpectedMovies.isEmpty()
                    && !savedExpectedSeries.isEmpty();
            boolean savedCacheIntegrityOk = !savedFingerprintKnown
                    || (savedExpectedLive.equals(String.valueOf(localLive))
                    && savedExpectedMovies.equals(String.valueOf(localMovies))
                    && savedExpectedSeries.equals(String.valueOf(localSeries)));'''
    p = p.replace(anchor, inject, 1)

    old = '''            if (scopedSource.equals(cachedSource) && (localLive + localMovies + localSeries) > 0
                    && "complete".equals(cachedState)) {'''
    new = '''            if (scopedSource.equals(cachedSource) && (localLive + localMovies + localSeries) > 0
                    && "complete".equals(cachedState) && savedCacheIntegrityOk) {'''
    if old not in p:
        raise SystemExit("R11E11: saved-playlist fast-path anchor missing")
    p = p.replace(old, new, 1)

IMPORTER.write_text(p, encoding="utf-8")

stage11_checks = {
    DB: ["r11e11TypeCount", "package_count_live", "package_count_movies", "package_count_series", "package_fingerprint"],
    IMPORTER: ["packageFingerprintKnown", "cacheIntegrityOk", "savedCacheIntegrityOk", "package_count_live"],
}
for path, markers in stage11_checks.items():
    text = path.read_text(encoding="utf-8")
    for marker in markers:
        if marker not in text:
            raise SystemExit(f"R11E11 invariant missing {path.name}: {marker}")

print("R11E stage10+11 applied: route matrix + atomic package count fingerprint/integrity fast-path guard")
