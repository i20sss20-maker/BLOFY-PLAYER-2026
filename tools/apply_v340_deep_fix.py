#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player"
PACKAGE = JAVA / "PackageImporter.java"
PROFILE = JAVA / "ServerPlaybackProfile.java"
PLAYER = JAVA / "PlayerActivity.java"
VOD = JAVA / "VodPlayerActivity.java"
PREFLIGHT = JAVA / "ServerCompatibilityPreflight.java"
GRADLE = ROOT / "BLOFY-ANDROID-2026/app/build.gradle.kts"

# 1) Import must finish immediately after the complete catalog is committed.
# Compatibility becomes diagnostic/advisory instead of a blocking 3/3 gate.
text = PACKAGE.read_text(encoding="utf-8")
block_start = text.find('        if (sourceIdentity.equals(activeSource)')
block_end = text.find('        emit(12, "تحليل الخادم"', block_start) if block_start >= 0 else -1
if block_start >= 0 and block_end > block_start:
    cache_block = '''        if (sourceIdentity.equals(activeSource) && (cachedLive + cachedMovies + cachedSeries) > 0
                && !"in_progress".equals(database.metadata("sync_state", ""))) {
            String profile = database.metadata("playback_profile", "Media3 مباشر");
            emit(100, "البيانات جاهزة", "تم فتح النسخة المحفوظة على الجهاز بدون إعادة تحميل");
            return new Result(cachedLive, cachedMovies, cachedSeries, profile);
        }

'''
    text = text[:block_start] + cache_block + text[block_end:]

# Remove synchronous verified preflight blocks if an earlier patch inserted them.
text = re.sub(
    r'\n\s*emit\(96, "[^\n]*?(?:توافق|التحقق)[^\n]*?\);.*?ServerCompatibilityPreflight\.Result\s+\w+\s*=\s*ServerCompatibilityPreflight\.run\(.*?\);.*?(?=\n\s*emit\((?:99|100),)',
    '\n', text, flags=re.S)
text = re.sub(
    r'\n\s*ServerCompatibilityPreflight\.Result\s+\w+\s*=\s*ServerCompatibilityPreflight\.run\(.*?\);.*?if\s*\(!?\w+\.accepted\(\)\)\s*\{.*?\}\n',
    '\n', text, flags=re.S)
PACKAGE.write_text(text, encoding="utf-8")

# 2) Route failures are item-scoped. One dead movie/channel must never poison
# the route for all content on the same host.
text = PROFILE.read_text(encoding="utf-8")
# Profile-level rejected route state is intentionally ignored.
text = text.replace('                prefs.getString(key + ".rejected", ""),', '                "",')

# Replace rejectRoute implementation with item-specific storage.
start = text.find('    static void rejectRoute(Context context, String url, String kind, String route, String reason) {')
end = text.find('    static void forget(Context context, String url) {', start)
if start >= 0 and end > start:
    replacement = '''    static boolean routeAllowed(Context context, String url, String kind, String route) {
        if (safe(route).isEmpty()) return true;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return !prefs.getBoolean(routeKey(context, url, kind, route) + ".rejected", false);
    }

    static void rejectRoute(Context context, String url, String kind, String route, String reason) {
        if (safe(route).isEmpty() || safe(url).isEmpty()) return;
        String key = routeKey(context, url, kind, route);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(key + ".rejected", true)
                .putString(key + ".reason", safe(reason))
                .putLong(key + ".updated", System.currentTimeMillis())
                .apply();
    }

'''
    text = text[:start] + replacement + text[end:]

# Add item-specific route key before digest().
anchor = '    private static String digest(String raw) {'
if 'private static String routeKey(' not in text and anchor in text:
    helper = '''    private static String routeKey(Context context, String url, String kind, String route) {
        String source = safe(CatalogScope.active(context));
        if (source.isEmpty()) source = "legacy";
        String normalized = safe(url);
        try {
            Uri uri = Uri.parse(normalized);
            normalized = safe(uri.getScheme()) + "://" + safe(uri.getHost()) + safe(uri.getPath());
        } catch (Exception ignored) {}
        return "r_" + digest(source + "|" + safe(kind).toLowerCase(Locale.US)
                + "|" + normalized + "|" + safe(route).toLowerCase(Locale.US));
    }

'''
    text = text.replace(anchor, helper + anchor, 1)
PROFILE.write_text(text, encoding="utf-8")

# 3) Playback consults item-specific rejection while keeping successful
# server/kind learning global enough to speed future playback.
for path in (PLAYER, VOD):
    value = path.read_text(encoding="utf-8")
    value = value.replace('!learned.routeRejected(learned.preferredRoute)',
                          'ServerPlaybackProfile.routeAllowed(this, canonicalUrl, kind, learned.preferredRoute)')
    value = value.replace('return !profile.routeRejected(route);',
                          'return ServerPlaybackProfile.routeAllowed(this, reference, kind, route);')
    path.write_text(value, encoding="utf-8")

# 4) Preflight remains available for diagnostics, but its wording must not claim
# it is an entry gate or that 100% is required to open the application.
text = PREFLIGHT.read_text(encoding="utf-8")
text = text.replace('Strict acceptance gate. 100% means all three families were demuxed and produced media samples.',
                    'Advisory compatibility diagnostic. Results never block catalog entry.')
text = text.replace('BLOFY VERIFIED PLAYBACK GATE', 'BLOFY PLAYBACK DIAGNOSTIC')
text = text.replace('rule=3-of-3 required: live + movies + episode',
                    'mode=advisory; playback learns from real first-frame success')
# Keep accepted() for compatibility with older callers, but do not persist it as an entry decision.
text = text.replace('decision=', 'diagnostic_decision=')
PREFLIGHT.write_text(text, encoding="utf-8")

# 5) Final release identity + cleanup of old visible version labels generated by legacy stack.
for path in JAVA.glob('*.java'):
    value = path.read_text(encoding='utf-8')
    value = value.replace('BLOFY PLAYER v330', 'BLOFY PLAYER v340')
    value = value.replace('BLOFY v330', 'BLOFY v340')
    path.write_text(value, encoding='utf-8')

gradle = GRADLE.read_text(encoding='utf-8')
gradle = re.sub(r'versionCode\s*=\s*\d+', 'versionCode = 1000341', gradle, count=1)
gradle = re.sub(r'versionName\s*=\s*"[^"]*"', 'versionName = "v340-deep-fix-r8"', gradle, count=1)
GRADLE.write_text(gradle, encoding='utf-8')

# Invariants: cached data opens directly; hard failures are item scoped; successful
# learning still requires real first-frame code from the previous patch stack.
assert 'ServerCompatibilityPreflight.run' not in PACKAGE.read_text(encoding='utf-8')
profile = PROFILE.read_text(encoding='utf-8')
assert 'static boolean routeAllowed' in profile and 'routeKey(context, url, kind, route)' in profile
assert 'rememberVerifiedSuccess' in PLAYER.read_text(encoding='utf-8')
assert 'rememberVerifiedSuccess' in VOD.read_text(encoding='utf-8')
assert 'versionCode = 1000341' in GRADLE.read_text(encoding='utf-8')
print('v340 deep fix applied: non-blocking import + item-scoped route rejection + first-frame learning preserved + legacy labels cleaned')
