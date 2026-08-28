#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player"
PACKAGE = JAVA / "PackageImporter.java"
PROFILE = JAVA / "ServerPlaybackProfile.java"
GRADLE = ROOT / "BLOFY-ANDROID-2026/app/build.gradle.kts"

# 1) Import must finish from the local catalog and enter immediately.
# Compatibility stays diagnostic only; it must never trap the user at 95-96%.
text = PACKAGE.read_text(encoding="utf-8")

# Cached catalogs: if a generated layer inserted the verified gate, collapse that
# whole cache block back to a direct local-cache return.
cache_start = text.find('        if (sourceIdentity.equals(activeSource)')
cache_end = text.find('        emit(12, "تحليل الخادم"', cache_start) if cache_start >= 0 else -1
if cache_start >= 0 and cache_end > cache_start:
    cache_block = text[cache_start:cache_end]
    if 'ServerCompatibilityPreflight' in cache_block or 'cachedGate' in cache_block:
        replacement = '''        if (sourceIdentity.equals(activeSource) && (cachedLive + cachedMovies + cachedSeries) > 0
                && !"in_progress".equals(database.metadata("sync_state", ""))) {
            String profile = database.metadata("playback_profile", "Media3 مباشر");
            emit(100, "جاهز", "تم فتح النسخة المحفوظة على الجهاز بدون إعادة فحص ثقيل");
            return new Result(cachedLive, cachedMovies, cachedSeries, profile);
        }

'''
        text = text[:cache_start] + replacement + text[cache_end:]

# Post-import gate: remove from declaration through the final ready emit. The
# historical stack changed wording several times, so locate it by stable anchors.
start = text.find('            ServerCompatibilityPreflight.Result preflight = ServerCompatibilityPreflight.run(')
if start < 0:
    start = text.find('ServerCompatibilityPreflight.Result preflight = ServerCompatibilityPreflight.run(')
if start >= 0:
    line_start = text.rfind('\n', 0, start) + 1
    ready = text.find('emit(100, "جاهز"', start)
    if ready < 0:
        raise SystemExit('R8 safety: final ready emit after preflight not found')
    ready_end = text.find('\n', ready)
    if ready_end < 0:
        ready_end = len(text)
    indent_match = re.match(r'\s*', text[line_start:start])
    indent = indent_match.group(0) if indent_match else '            '
    replacement = indent + 'emit(100, "جاهز", "تم حفظ البيانات كاملة؛ فحص التشغيل التشخيصي متاح من الإعدادات");'
    text = text[:line_start] + replacement + text[ready_end:]

# Defensive cleanup for fragments left by historical multiline exception text.
text = re.sub(r'(?m)^\s*\+\s*"\s*•\s*"\s*\+\s*preflight\.summary\);\s*\n?', '', text)
text = re.sub(r'(?m)^\s*\+\s*preflight\.summary\s*\+?.*?;\s*\n?', '', text)
text = re.sub(r'(?m)^\s*preflight\.summary\s*\+?.*?;\s*\n?', '', text)

# There must be no automatic gate reference left in PackageImporter. Settings can
# still invoke ServerCompatibilityPreflight explicitly for diagnostics.
if 'ServerCompatibilityPreflight.run(' in text:
    raise SystemExit('R8 safety: automatic compatibility preflight still present in PackageImporter')
if 'preflight.' in text or 'cachedGate' in text:
    raise SystemExit('R8 safety: stale preflight fragment remains in PackageImporter')
PACKAGE.write_text(text, encoding="utf-8")

# 2) Positive learning remains server/kind scoped, but negative route failures are
# never persisted globally. One dead movie/channel must not poison every item.
profile = PROFILE.read_text(encoding="utf-8")
profile = profile.replace('blofy_server_playback_profiles_v2', 'blofy_server_playback_profiles_v3')
profile = re.sub(
    r'        boolean routeRejected\(String route\) \{.*?\n        \}',
    '''        boolean routeRejected(String route) {
            // R8: negative evidence is playback-attempt local only. A single 404/551
            // must never blacklist this route for the whole provider/content family.
            return false;
        }''', profile, count=1, flags=re.S)
profile, reject_hits = re.subn(
    r'    static void rejectRoute\(Context context, String url, String kind, String route, String reason\) \{.*?\n    \}',
    '''    static void rejectRoute(Context context, String url, String kind, String route, String reason) {
        // Diagnostic only. Runtime state machines already avoid repeating the same
        // route inside one playback attempt; do not persist a provider-wide ban.
        PlaybackDiagnostics.marker(context, "route-rejected-local", safe(kind), "", "",
                safe(route), safe(reason));
    }''', profile, count=1, flags=re.S)
if reject_hits != 1:
    raise SystemExit('R8 safety: rejectRoute patch not applied')
PROFILE.write_text(profile, encoding="utf-8")

# 3) Remove stale user-visible v330 branding left by reconstructed historical layers.
for path in (ROOT / "BLOFY-ANDROID-2026/app/src/main").rglob('*'):
    if not path.is_file() or path.suffix.lower() not in {'.java', '.kt', '.xml'}:
        continue
    try:
        value = path.read_text(encoding='utf-8')
    except Exception:
        continue
    cleaned = value.replace('BLOFY PLAYER v330', 'BLOFY PLAYER v340')
    cleaned = cleaned.replace('BLOFY-PLAYER/v330', 'BLOFY-PLAYER/v340')
    cleaned = cleaned.replace('BlofyPlayer/330', 'BlofyPlayer/340')
    if cleaned != value:
        path.write_text(cleaned, encoding='utf-8')

# 4) R8 identity must be a real upgrade over R7.
gradle = GRADLE.read_text(encoding='utf-8')
gradle, code_hits = re.subn(r'versionCode\s*=\s*\d+', 'versionCode = 1000341', gradle, count=1)
gradle, name_hits = re.subn(r'versionName\s*=\s*"[^"]*"', 'versionName = "v340-full-stability-r8"', gradle, count=1)
if code_hits != 1 or name_hits != 1:
    raise SystemExit('R8 safety: version metadata patch failed')
GRADLE.write_text(gradle, encoding='utf-8')

# Final invariants.
package_final = PACKAGE.read_text(encoding='utf-8')
profile_final = PROFILE.read_text(encoding='utf-8')
assert 'ServerCompatibilityPreflight.run(' not in package_final
assert 'preflight.' not in package_final
assert 'cachedGate' not in package_final
assert 'blofy_server_playback_profiles_v3' in profile_final
assert 'return false;' in profile_final
assert 'route-rejected-local' in profile_final
assert 'versionCode = 1000341' in GRADLE.read_text(encoding='utf-8')
assert 'versionName = "v340-full-stability-r8"' in GRADLE.read_text(encoding='utf-8')
print('v340 R8 safety applied: no blocking 95/96% preflight + no global negative route poisoning + stale branding cleaned')
