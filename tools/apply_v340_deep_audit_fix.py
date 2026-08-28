#!/usr/bin/env python3
from pathlib import Path
import re

ROOT=Path(__file__).resolve().parents[1]
JAVA=ROOT/'BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player'
PACKAGE=JAVA/'PackageImporter.java'
PROFILE=JAVA/'ServerPlaybackProfile.java'
PLAYER=JAVA/'PlayerActivity.java'
VOD=JAVA/'VodPlayerActivity.java'

# 1) Import/catalog readiness must not be blocked by a demux probe.
p=PACKAGE.read_text(encoding='utf-8')
# Cached catalog: always enter immediately when cache is complete/current.
start=p.find('        if (sourceIdentity.equals(activeSource)')
end=p.find('        emit(12, "تحليل الخادم"', start) if start>=0 else -1
if start>=0 and end>start:
    p=p[:start]+'''        if (sourceIdentity.equals(activeSource) && (cachedLive + cachedMovies + cachedSeries) > 0
                && !"in_progress".equals(database.metadata("sync_state", ""))) {
            String profile = database.metadata("playback_profile", "Media3 مباشر");
            emit(100, "البيانات جاهزة", "تم فتح النسخة المحفوظة بدون إعادة تحميل أو فحص مانع للدخول");
            return new Result(cachedLive, cachedMovies, cachedSeries, profile);
        }

'''+p[end:]
# Post-import: keep compatibility result diagnostic only, never a hard gate.
p=re.sub(r'\s*if \(!preflight\.accepted\(\)\) \{.*?\n\s*\}\n\s*emit\(100, "جاهز", preflight\.summary\);',
'''            if (!preflight.accepted()) {
                PlaybackDiagnostics.marker(api.context(), "compatibility-advisory", "server", playlistId, "",
                        "not-blocking", preflight.summary);
            }
            emit(100, "جاهز", "تم حفظ الباقة كاملة • " + preflight.summary);''',p,count=1,flags=re.S)
PACKAGE.write_text(p,encoding='utf-8')

# 2) Route rejection is item-scoped, never host/family-wide.
s=PROFILE.read_text(encoding='utf-8')
s=s.replace('static void rejectRoute(Context context, String url, String kind, String route, String reason) {',
'''static void rejectRoute(Context context, String url, String kind, String route, String reason) {
        // Compatibility shim: broad route poisoning is intentionally disabled.
        // A single dead channel/movie must never blacklist a route for the whole provider.
        return;
    }

    static void rejectItemRoute(Context context, String url, String kind, String itemId, String route, String reason) {''')
# item key includes id; retain same persistence body below new method
s=s.replace('String key = key(context, url, kind);\n        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);\n        Set<String> routes = new LinkedHashSet<>();',
'''String key = key(context, url, kind) + ".item_" + digest(safe(itemId));
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> routes = new LinkedHashSet<>();''',1)
# Add item lookup without changing verified family profile.
insert='''
    static boolean itemRouteRejected(Context context, String url, String kind, String itemId, String route) {
        if (safe(route).isEmpty() || safe(itemId).isEmpty()) return false;
        String key = key(context, url, kind) + ".item_" + digest(safe(itemId));
        String rejected = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(key + ".rejected", "");
        for (String value : safe(rejected).split(",")) if (route.equals(value.trim())) return true;
        return false;
    }
'''
pos=s.find('    static void forget(')
s=s[:pos]+insert+s[pos:]
PROFILE.write_text(s,encoding='utf-8')

# 3) Runtime players reject only the current item route.
for path in (PLAYER,VOD):
    t=path.read_text(encoding='utf-8')
    t=t.replace('ServerPlaybackProfile.rejectRoute(this, url, kind, sourceVariant, reason);',
                'ServerPlaybackProfile.rejectItemRoute(this, url, kind, id, sourceVariant, reason);')
    t=t.replace('ServerPlaybackProfile.rejectRoute(this, resolvedUrl, kind, sourceVariant, reason);',
                'ServerPlaybackProfile.rejectItemRoute(this, resolvedUrl, kind, id, sourceVariant, reason);')
    if path==VOD:
        t=t.replace('return !profile.routeRejected(route);',
                    'return !ServerPlaybackProfile.itemRouteRejected(this, reference, kind, id, route);')
    path.write_text(t,encoding='utf-8')

# 4) Release identity: R8 after deep-audit fixes.
gradle=ROOT/'BLOFY-ANDROID-2026/app/build.gradle.kts'
g=gradle.read_text(encoding='utf-8')
g=re.sub(r'versionCode\s*=\s*\d+','versionCode = 1000341',g,count=1)
g=re.sub(r'versionName\s*=\s*"[^"]*"','versionName = "v340-full-stability-r8"',g,count=1)
gradle.write_text(g,encoding='utf-8')

# Invariants
assert 'not-blocking' in PACKAGE.read_text(encoding='utf-8')
assert 'rejectItemRoute' in PROFILE.read_text(encoding='utf-8')
assert 'itemRouteRejected' in VOD.read_text(encoding='utf-8')
print('deep audit fix applied: nonblocking compatibility + item-scoped rejection + R8 identity')
