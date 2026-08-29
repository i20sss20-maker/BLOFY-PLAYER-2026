#!/usr/bin/env python3
from pathlib import Path
import re

ROOT=Path(__file__).resolve().parents[1]
APP=ROOT/'BLOFY-ANDROID-2026/app'
JAVA=APP/'src/main/java/tv/blofy/player'
TEST=APP/'src/test/java/tv/blofy/player/PlaybackPolicyTest.java'
GRADLE=APP/'build.gradle.kts'

# 1) Give native-link authorization + redirect a realistic budget. Cancellation still
# disconnects immediately when the user changes content, so this does not make zapping wait 10s.
api=JAVA/'BlofyApi.java'
s=api.read_text()
s=re.sub(r'private static final int PLAYBACK_LINK_TIMEOUT_MS\s*=\s*[0-9_]+;',
         'private static final int PLAYBACK_LINK_TIMEOUT_MS = 10_000;', s, count=1)
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

# 3) Startup watchdogs are guards, not route verdicts. Media3 gets a realistic chance to
# complete its own load retry policy before BLOFY changes route/engine.
pol=JAVA/'PlaybackPolicy.java'
s=pol.read_text()
values={
 'INITIAL_STARTUP_TIMEOUT_MS':'12_000',
 'RETRY_STARTUP_TIMEOUT_MS':'9_000',
 'VOD_STARTUP_TIMEOUT_MS':'15_000',
 'UHD_VOD_STARTUP_TIMEOUT_MS':'22_000',
 'VLC_STARTUP_TIMEOUT_MS':'12_000',
 'UHD_VLC_STARTUP_TIMEOUT_MS':'20_000',
 'PREVIEW_STARTUP_TIMEOUT_MS':'8_000',
}
for name,value in values.items():
    s,n=re.subn(rf'({name}\s*=\s*)[0-9_]+', rf'\g<1>{value}', s, count=1)
    if n != 1: raise SystemExit(f'hotfix constant missing PlaybackPolicy.java: {name}')
pol.write_text(s)

# 4) Keep the regression test strict, but assert the new deliberately-bounded policy instead
# of the obsolete 2.5-6.5 second values that reproduced the field failures.
if TEST.exists():
    t=TEST.read_text()
    method=re.compile(r'    @Test\n    public void startupTimeoutsAreBoundedForFastFallback\(\) \{.*?\n    \}', re.S)
    replacement='''    @Test\n    public void startupTimeoutsAreBoundedForResilientFallback() {\n        assertEquals(12_000, PlaybackPolicy.startupTimeoutMs(0));\n        assertEquals(9_000, PlaybackPolicy.startupTimeoutMs(1));\n        assertEquals(15_000, PlaybackPolicy.vodStartupTimeoutMs(false));\n        assertEquals(22_000, PlaybackPolicy.vodStartupTimeoutMs(true));\n        assertEquals(12_000, PlaybackPolicy.vlcStartupTimeoutMs(false));\n        assertEquals(20_000, PlaybackPolicy.vlcStartupTimeoutMs(true));\n        assertEquals(8_000, PlaybackPolicy.PREVIEW_STARTUP_TIMEOUT_MS);\n        assertTrue(PlaybackPolicy.startupTimeoutMs(0) <= 12_000);\n        assertTrue(PlaybackPolicy.vodStartupTimeoutMs(true) <= 22_000);\n    }'''
    t,n=method.subn(replacement,t,count=1)
    if n != 1: raise SystemExit('hotfix timeout regression test anchor missing')
    TEST.write_text(t)

# 5) Stamp an upgrade-safe hotfix build above COMPLETE.
g=GRADLE.read_text()
g=re.sub(r'versionCode\s*=\s*\d+', 'versionCode = 1000356', g, count=1)
g=re.sub(r'versionName\s*=\s*"[^"]*"', 'versionName = "v340-playback-core-hotfix"', g, count=1)
GRADLE.write_text(g)

for p,need in {
 api:['PLAYBACK_LINK_TIMEOUT_MS = 10_000'],
 fail:['playback-soft-failure','playback-hard-route-failure','if (!(is403(reason) || is404(reason)))'],
 pol:['INITIAL_STARTUP_TIMEOUT_MS = 12_000','RETRY_STARTUP_TIMEOUT_MS = 9_000','VOD_STARTUP_TIMEOUT_MS = 15_000','UHD_VOD_STARTUP_TIMEOUT_MS = 22_000'],
 GRADLE:['versionCode = 1000356','v340-playback-core-hotfix'],
}.items():
 t=p.read_text()
 for n in need:
  if n not in t: raise SystemExit(f'hotfix invariant missing {p.name}: {n}')

# Exact field-failure guard: generic timeout, 429 and 5xx must not write persistent
# negative-route evidence. Only 403/404 reach the preference write path.
f=fail.read_text()
failed_body=f[f.index('static void failed('):f.index('static boolean routeBlocked(')]
if 'if (!(is403(reason) || is404(reason)))' not in failed_body:
    raise SystemExit('hard-only route failure gate missing')

print('v340 playback-core hotfix applied: resolver budget + hard-only route memory + resilient bounded startup windows')
