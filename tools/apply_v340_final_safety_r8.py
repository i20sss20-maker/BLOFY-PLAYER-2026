#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player"
PACKAGE = JAVA / "PackageImporter.java"
PROFILE = JAVA / "ServerPlaybackProfile.java"
PREFLIGHT = JAVA / "ServerCompatibilityPreflight.java"
PLAYER = JAVA / "PlayerActivity.java"
VOD = JAVA / "VodPlayerActivity.java"
GRADLE = ROOT / "BLOFY-ANDROID-2026/app/build.gradle.kts"

text = PACKAGE.read_text(encoding="utf-8")
cache_start = text.find('        if (sourceIdentity.equals(activeSource)')
cache_end = text.find('        emit(12, "تحليل الخادم"', cache_start) if cache_start >= 0 else -1
if cache_start >= 0 and cache_end > cache_start:
    cache_block = '''        if (sourceIdentity.equals(activeSource) && (cachedLive + cachedMovies + cachedSeries) > 0
                && !"in_progress".equals(database.metadata("sync_state", ""))) {
            String profile = database.metadata("playback_profile", "Media3 مباشر");
            emit(100, "البيانات جاهزة", "تم فتح النسخة المحفوظة على الجهاز بدون إعادة تحميل");
            return new Result(cachedLive, cachedMovies, cachedSeries, profile);
        }

'''
    text = text[:cache_start] + cache_block + text[cache_end:]
else:
    print('r8: reconstructed importer has no cache shortcut; leaving that path unchanged')

text = re.sub(
    r'\n\s*emit\(96,.*?ServerCompatibilityPreflight\.Result\s+preflight\s*=\s*ServerCompatibilityPreflight\.run\(.*?\);\s*'
    r'(?:if\s*\(!preflight\.accepted\(\)\)\s*\{.*?\}\s*)?',
    '\n', text, count=1, flags=re.S)
text = re.sub(
    r'\n\s*ServerCompatibilityPreflight\.Result\s+preflight\s*=\s*ServerCompatibilityPreflight\.run\(.*?\);\s*'
    r'(?:if\s*\(!preflight\.accepted\(\)\)\s*\{.*?\}\s*)?',
    '\n', text, count=1, flags=re.S)
text = re.sub(r'\n\s*if\s*\(!preflight\.accepted\(\)\)\s*\{.*?\}\s*', '\n', text, count=1, flags=re.S)
text = re.sub(r'\n\s*if\s*\(preflight\.completeFailure\(\).*?\)\s*\{.*?\}\s*', '\n', text, count=1, flags=re.S)
# Remove any orphan UI/report lines left behind by older generated gate layers.
text = re.sub(r'\n\s*emit\(99,\s*"نتيجة التوافق",\s*preflight\.summary\);\s*', '\n', text)
text = text.replace(' + " • " + preflight.summary', '')
text = text.replace('+ " • " + preflight.summary', '')
text = text.replace('preflight.summary', '"تم حفظ البيانات بنجاح"')
text = text.replace('emit(99, "فتح BLOFY PLAYER", "تم الحفظ بنجاح");',
                    'emit(99, "فتح BLOFY PLAYER", "تم حفظ البيانات؛ تحسين التشغيل يتم أثناء الاستخدام");')
PACKAGE.write_text(text, encoding="utf-8")

text = PROFILE.read_text(encoding="utf-8")
text = re.sub(
    r'        boolean routeRejected\(String route\) \{.*?\n        \}',
    '''        boolean routeRejected(String route) {
            // R8: negative route decisions are intentionally not persisted at provider scope.
            // A single dead movie/channel must never disable a route for unrelated content.
            return false;
        }''', text, count=1, flags=re.S)
reject_pattern = re.compile(r'    static void rejectRoute\(Context context, String url, String kind, String route, String reason\) \{.*?\n    \}', re.S)
reject_replacement = '''    static void rejectRoute(Context context, String url, String kind, String route, String reason) {
        if (safe(route).isEmpty()) return;
        String key = key(context, url, kind);
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit()
                .putString(key + ".last_failure_route", route.trim())
                .putString(key + ".last_failure_reason", safe(reason))
                .putLong(key + ".last_failure_at", System.currentTimeMillis());
        if (route.equals(prefs.getString(key + ".route", ""))) {
            editor.putBoolean(key + ".verified", false).remove(key + ".route");
        }
        editor.apply();
    }'''
text, hits = reject_pattern.subn(reject_replacement, text, count=1)
if hits != 1:
    raise SystemExit('r8: rejectRoute replacement failed')
load_anchor = 'SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);\n        String key = key(context, url, kind);'
if load_anchor in text:
    text = text.replace(load_anchor,
'''SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String key = key(context, url, kind);
        if (!prefs.getString(key + ".rejected", "").isEmpty()) {
            prefs.edit().remove(key + ".rejected").apply();
        }''', 1)
PROFILE.write_text(text, encoding="utf-8")

text = PREFLIGHT.read_text(encoding="utf-8")
text = text.replace('private static final int SAMPLE_COUNT = 2;', 'private static final int SAMPLE_COUNT = 1;')
text = text.replace('private static final long VERIFY_TIMEOUT_MS = 12_000L;', 'private static final long VERIFY_TIMEOUT_MS = 6_000L;')
text = text.replace('BLOFY VERIFIED PLAYBACK GATE', 'BLOFY PLAYBACK DIAGNOSTIC')
text = text.replace('rule=3-of-3 required: live + movies + episode',
                    'mode=advisory-diagnostic; real playback is authoritative')
text = text.replace('.putBoolean(key + ":accepted", accepted)\n', '')
text = re.sub(r'    static boolean savedAccepted\(Context context, String playlistId\) \{.*?\n    \}',
'''    static boolean savedAccepted(Context context, String playlistId) {
        return false;
    }''', text, count=1, flags=re.S)
PREFLIGHT.write_text(text, encoding="utf-8")

for path in (PLAYER, VOD):
    value = path.read_text(encoding="utf-8")
    value = value.replace('&& !learned.routeRejected(learned.preferredRoute)\n                                && learnedAlternateVariant(learned.preferredRoute)',
                          '&& learnedAlternateVariant(learned.preferredRoute)')
    value = value.replace('return !profile.routeRejected(route);', 'return true;')
    path.write_text(value, encoding="utf-8")

for path in JAVA.glob('*.java'):
    value = path.read_text(encoding='utf-8')
    value = value.replace('BLOFY PLAYER v330', 'BLOFY PLAYER v340')
    value = value.replace('BLOFY/v330', 'BLOFY/v340')
    value = value.replace('BLOFY-PLAYER/v330', 'BLOFY-PLAYER/v340')
    path.write_text(value, encoding='utf-8')

gradle = GRADLE.read_text(encoding='utf-8')
gradle, c1 = re.subn(r'versionCode\s*=\s*\d+', 'versionCode = 1000341', gradle, count=1)
gradle, c2 = re.subn(r'versionName\s*=\s*"[^"]*"', 'versionName = "v340-full-stability-r8"', gradle, count=1)
if c1 != 1 or c2 != 1:
    raise SystemExit('r8: version stamp failed')
GRADLE.write_text(gradle, encoding='utf-8')

package_final = PACKAGE.read_text(encoding='utf-8')
profile_final = PROFILE.read_text(encoding='utf-8')
preflight_final = PREFLIGHT.read_text(encoding='utf-8')
if 'ServerCompatibilityPreflight.run(' in package_final or '!preflight.accepted()' in package_final or 'preflight.' in package_final:
    raise SystemExit('r8: blocking/orphan preflight code still present in PackageImporter')
if 'negative route decisions are intentionally not persisted' not in profile_final or 'rememberVerifiedSuccess' not in profile_final:
    raise SystemExit('r8: profile positive-learning invariant failed')
if 'SAMPLE_COUNT = 1' not in preflight_final or 'VERIFY_TIMEOUT_MS = 6_000L' not in preflight_final:
    raise SystemExit('r8: diagnostic bounds missing')
if 'versionCode = 1000341' not in GRADLE.read_text(encoding='utf-8'):
    raise SystemExit('r8: final version code missing')
print('v340 R8 safety applied: non-blocking import + positive-only learning + bounded diagnostics + cleaned version strings')
