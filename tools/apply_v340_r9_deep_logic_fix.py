#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player"
PACKAGE = JAVA / "PackageImporter.java"
PROFILE = JAVA / "ServerPlaybackProfile.java"
PLAYER = JAVA / "PlayerActivity.java"
VOD = JAVA / "VodPlayerActivity.java"
DATABASE = JAVA / "CatalogDatabase.java"
GRADLE = ROOT / "BLOFY-ANDROID-2026/app/build.gradle.kts"

# 1) Never reload a complete local catalog for the same playlist/source.
p = PACKAGE.read_text(encoding="utf-8")
if "تم فتح النسخة المحفوظة على الجهاز بدون إعادة تحميل" not in p:
    anchor = '        int cachedSeries = database.count("series");\n'
    if anchor not in p:
        raise SystemExit("r9: cached-series anchor missing")
    block = '''        if (sourceIdentity.equals(activeSource) && (cachedLive + cachedMovies + cachedSeries) > 0
                && !"in_progress".equals(database.metadata("sync_state", ""))) {
            String profile = database.metadata("playback_profile", "Media3 مباشر");
            emit(100, "البيانات جاهزة", "تم فتح النسخة المحفوظة على الجهاز بدون إعادة تحميل");
            return new Result(cachedLive, cachedMovies, cachedSeries, profile);
        }

'''
    p = p.replace(anchor, anchor + block, 1)
PACKAGE.write_text(p, encoding="utf-8")

# 2) Positive-only route learning. One dead channel/movie must not erase a route
# that was previously proven on the same provider/content family.
s = PROFILE.read_text(encoding="utf-8")
dangerous_block = '''        if (route.equals(prefs.getString(key + ".route", ""))) {
            editor.putBoolean(key + ".verified", false).remove(key + ".route");
        }
'''
s = s.replace(dangerous_block, "")
if '.putBoolean(key + ".verified", false).remove(key + ".route")' in s:
    raise SystemExit("r9: provider route invalidation still present")
PROFILE.write_text(s, encoding="utf-8")

# 3) Final release identity.
g = GRADLE.read_text(encoding="utf-8")
g, c1 = re.subn(r'versionCode\s*=\s*\d+', 'versionCode = 1000342', g, count=1)
g, c2 = re.subn(r'versionName\s*=\s*"[^"]*"', 'versionName = "v340-full-stability-r9"', g, count=1)
if c1 != 1 or c2 != 1:
    raise SystemExit("r9: version stamp failed")
GRADLE.write_text(g, encoding="utf-8")

# 4) Deep invariants: fail the build rather than ship a regression.
pf = PACKAGE.read_text(encoding="utf-8")
pr = PROFILE.read_text(encoding="utf-8")
pl = PLAYER.read_text(encoding="utf-8")
vd = VOD.read_text(encoding="utf-8")
db = DATABASE.read_text(encoding="utf-8")

for marker in [
    "sourceIdentity.equals(activeSource)",
    "تم فتح النسخة المحفوظة على الجهاز بدون إعادة تحميل",
    '!"in_progress".equals(database.metadata("sync_state", ""))',
]:
    if marker not in pf:
        raise SystemExit("r9: local-cache invariant missing: " + marker)
if "ServerCompatibilityPreflight.run(" in pf or "!preflight.accepted()" in pf:
    raise SystemExit("r9: blocking preflight returned")

if '.putBoolean(key + ".verified", false).remove(key + ".route")' in pr:
    raise SystemExit("r9: destructive negative route learning returned")
if "rememberVerifiedSuccess" not in pr:
    raise SystemExit("r9: verified positive route learning missing")

# The final verified-gate reconstruction intentionally makes OK leave fullscreen Live
# back to the channel list; do not require the older in-player drawer/warm-switch marker.
for marker in [
    "if (isLive()) { finish(); return true; }",
    "rememberVerifiedSuccess",
]:
    if marker not in pl:
        raise SystemExit("r9: live player invariant missing: " + marker)

for marker in [
    'routeAllowed("direct")',
    'routeAllowed("no-extension")',
    "rejectVodRouteIfHard",
]:
    if marker not in vd:
        raise SystemExit("r9: vod recovery invariant missing: " + marker)

for marker in [
    "database.beginTransaction()",
    "media_staging",
    'putMetadata(database, "sync_state", "complete")',
    "committing the playable package must not wait for FTS",
]:
    if marker not in db:
        raise SystemExit("r9: catalog database invariant missing: " + marker)

print("R9 deep logic fix applied: cache fast path + positive-only route learning + bounded Live/VOD recovery + staged SQLite safeguards")
