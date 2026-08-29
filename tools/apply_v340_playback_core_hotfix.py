#!/usr/bin/env python3
from pathlib import Path
import re

ROOT=Path(__file__).resolve().parents[1]
JAVA=ROOT/'BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player'
GRADLE=ROOT/'BLOFY-ANDROID-2026/app/build.gradle.kts'

# 1) Give native-link authorization + redirect a realistic budget. Cancellation still
# disconnects immediately when the user changes content, so this does not make zapping wait 10s.
api=JAVA/'BlofyApi.java'
s=api.read_text()
s=s.replace('private static final int PLAYBACK_LINK_TIMEOUT_MS = 4_000;', 'private static final int PLAYBACK_LINK_TIMEOUT_MS = 10_000;')
api.write_text(s)

# 2) Soft/transient failures must never poison a provider route. Preserve negative memory
# only for proven hard HTTP source failures (403/404). 429/5xx remain transient retry signals.
fail=JAVA/'PlaybackFailureMemory.java'
s=fail.read_text()
start=s.index('    static void failed(Context c, String provider, String family, String route,')
end=s.index('\n    static boolean routeBlocked(', start) if '\n    static boolean routeBlocked(' in s[start:] else s.index('\n    static boolean blocked(', start)
new='''    static void failed(Context c, String provider, String family, String route,\n                       String extension, String engine, String reason) {\n        if (!(is403(reason) || is404(reason))) {\n            PlaybackDiagnostics.marker(c, "playback-soft-failure", family, "", extension,\n                    route, "engine=" + safe(engine) + " reason=" + safe(reason));\n            return;\n        }\n        String k = key(provider, family, route, extension, engine);\n        long now = System.currentTimeMillis();\n        long ttl = ttl(reason);\n        SharedPreferences.Editor edit = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()\n                .putLong(k + ".until", now + ttl)\n                .putString(k + ".reason", safe(reason));\n        String rk = routeKey(provider, family, route, extension);\n        edit.putLong(rk + ".until", now + ttl).putString(rk + ".reason", safe(reason)).apply();\n        PlaybackDiagnostics.marker(c, "playback-hard-route-failure", family, "", extension,\n                route, "engine=" + safe(engine) + " ttl_ms=" + ttl + " reason=" + safe(reason));\n    }\n'''
s=s[:start]+new+s[end:]
fail.write_text(s)

# 3) Startup watchdogs are a guard, not a route verdict. Give Media3 time to perform its
# own load retries. VOD/4K need longer first-keyframe windows than Live.
pol=JAVA/'PlaybackPolicy.java'
s=pol.read_text()
for old,newv in {
'INITIAL_STARTUP_TIMEOUT_MS = 7_000':'INITIAL_STARTUP_TIMEOUT_MS = 12_000',
'RETRY_STARTUP_TIMEOUT_MS = 5_000':'RETRY_STARTUP_TIMEOUT_MS = 9_000',
'VOD_STARTUP_TIMEOUT_MS = 8_000':'VOD_STARTUP_TIMEOUT_MS = 15_000',
'UHD_VOD_STARTUP_TIMEOUT_MS = 11_000':'UHD_VOD_STARTUP_TIMEOUT_MS = 22_000',
'VLC_STARTUP_TIMEOUT_MS = 7_000':'VLC_STARTUP_TIMEOUT_MS = 12_000',
'UHD_VLC_STARTUP_TIMEOUT_MS = 10_000':'UHD_VLC_STARTUP_TIMEOUT_MS = 20_000',
'PREVIEW_STARTUP_TIMEOUT_MS = 5_000':'PREVIEW_STARTUP_TIMEOUT_MS = 8_000',
}.items(): s=s.replace(old,newv)
pol.write_text(s)

# 4) Stamp an upgrade-safe hotfix build above COMPLETE.
g=GRADLE.read_text()
g=re.sub(r'versionCode\s*=\s*\d+', 'versionCode = 1000356', g, count=1)
g=re.sub(r'versionName\s*=\s*"[^"]*"', 'versionName = "v340-playback-core-hotfix"', g, count=1)
GRADLE.write_text(g)

for p,need in {
 api:['PLAYBACK_LINK_TIMEOUT_MS = 10_000'],
 fail:['playback-soft-failure','playback-hard-route-failure','if (!(is403(reason) || is404(reason)))'],
 pol:['INITIAL_STARTUP_TIMEOUT_MS = 12_000','VOD_STARTUP_TIMEOUT_MS = 15_000','UHD_VOD_STARTUP_TIMEOUT_MS = 22_000'],
 GRADLE:['versionCode = 1000356','v340-playback-core-hotfix'],
}.items():
 t=p.read_text()
 for n in need:
  if n not in t: raise SystemExit(f'hotfix invariant missing {p.name}: {n}')
print('v340 playback-core hotfix applied: resolver budget + soft timeout policy + realistic startup windows')
