#!/usr/bin/env python3
from pathlib import Path
import re
ROOT=Path(__file__).resolve().parents[1]
JAVA=ROOT/'BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player'
PACKAGE=JAVA/'PackageImporter.java'
PROFILE=JAVA/'ServerPlaybackProfile.java'
GRADLE=ROOT/'BLOFY-ANDROID-2026/app/build.gradle.kts'

# Guarantee cached catalog fast path even when earlier reconstruction layers omit it.
p=PACKAGE.read_text(encoding='utf-8')
if 'تم فتح النسخة المحفوظة على الجهاز بدون إعادة تحميل' not in p:
    anchor='''        int cachedSeries = database.count("series");\n'''
    if anchor not in p:
        raise SystemExit('r9: cached-series anchor missing')
    block='''        if (sourceIdentity.equals(activeSource) && (cachedLive + cachedMovies + cachedSeries) > 0
                && !"in_progress".equals(database.metadata("sync_state", ""))) {
            String profile = database.metadata("playback_profile", "Media3 مباشر");
            emit(100, "البيانات جاهزة", "تم فتح النسخة المحفوظة على الجهاز بدون إعادة تحميل");
            return new Result(cachedLive, cachedMovies, cachedSeries, profile);
        }

'''
    p=p.replace(anchor,anchor+block,1)
PACKAGE.write_text(p,encoding='utf-8')

# A single hard failure is diagnostic only. Never erase a verified family route because
# one movie/channel is dead; positive learning is replaced only by another proven success.
s=PROFILE.read_text(encoding='utf-8')
pattern=re.compile(r'''        if \(route\.equals\(prefs\.getString\(key \+ "\\.route", ""\)\)\) \{\n            editor\.putBoolean\(key \+ "\\.verified", false\)\.remove\(key \+ "\\.route"\);\n        \}\n''')
s,hits=pattern.subn('',s,count=1)
if hits==0:
    # tolerate already-safe generated source, but forbid the dangerous behavior below.
    pass
if '.putBoolean(key + ".verified", false).remove(key + ".route")' in s:
    raise SystemExit('r9: provider route invalidation still present')
PROFILE.write_text(s,encoding='utf-8')

# R9 identity
g=GRADLE.read_text(encoding='utf-8')
g=re.sub(r'versionCode\s*=\s*\d+','versionCode = 1000342',g,count=1)
g=re.sub(r'versionName\s*=\s*"[^"]*"','versionName = "v340-full-stability-r9"',g,count=1)
GRADLE.write_text(g,encoding='utf-8')

pf=PACKAGE.read_text(encoding='utf-8')
pr=PROFILE.read_text(encoding='utf-8')
assert 'sourceIdentity.equals(activeSource)' in pf
assert 'تم فتح النسخة المحفوظة على الجهاز بدون إعادة تحميل' in pf
assert 'ServerCompatibilityPreflight.run(' not in pf
assert '.putBoolean(key + ".verified", false).remove(key + ".route")' not in pr
assert 'rememberVerifiedSuccess' in pr
print('R9 deep logic fix: guaranteed local-cache fast path + positive-only route learning')
