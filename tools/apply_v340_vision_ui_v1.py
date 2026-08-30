#!/usr/bin/env python3
from pathlib import Path
import re
ROOT=Path(__file__).resolve().parents[1]
APP=ROOT/'BLOFY-ANDROID-2026/app'; JAVA=APP/'src/main/java/tv/blofy/player'
UI=JAVA/'BlofyUi.java'; GRADLE=APP/'build.gradle.kts'
u=UI.read_text(encoding='utf-8')
# Vision UI: black-first premium palette, restrained purple accents, stronger TV focus.
repls={
'Color.rgb(5, 5, 12)':'Color.rgb(3, 3, 8)',
'Color.rgb(9, 9, 20)':'Color.rgb(7, 7, 14)',
'Color.rgb(17, 16, 30)':'Color.rgb(14, 13, 24)',
'Color.rgb(24, 20, 42)':'Color.rgb(20, 17, 34)',
'Color.rgb(38, 25, 68)':'Color.rgb(43, 25, 72)',
'Color.rgb(124, 43, 255)':'Color.rgb(126, 44, 255)',
'Color.rgb(188, 132, 255)':'Color.rgb(196, 151, 255)',
'Color.rgb(48, 39, 76)':'Color.rgb(57, 47, 82)'}
for a,b in repls.items(): u=u.replace(a,b)
# Slightly stronger premium focus while remaining TV-safe.
u=u.replace('attachScaleFocus(view, 1.008f);','attachScaleFocus(view, 1.012f);')
u=u.replace('Math.min(scale, 1.008f)','Math.min(scale, 1.012f)')
u=u.replace('.setDuration("reduced".equals(motion) ? 55 : 90)', '.setDuration("reduced".equals(motion) ? 55 : 115)')
u=u.replace('v.setElevation(focused ? dp(v.getContext(), 8) : 0);','v.setElevation(focused ? dp(v.getContext(), 12) : 0);')
# Blacker cinematic background, purple ambience only at edge.
u=re.sub(r'new int\[\]\{Color\.rgb\(4, 4, 10\), Color\.rgb\(7, 6, 15\), Color\.rgb\(11, 8, 25\), Color\.rgb\(3, 3, 8\)\}',
'''new int[]{Color.rgb(2, 2, 6), Color.rgb(5, 5, 11), Color.rgb(13, 8, 25), Color.rgb(3, 3, 8)}''',u)
UI.write_text(u,encoding='utf-8')
g=GRADLE.read_text(encoding='utf-8'); g=re.sub(r'versionCode\s*=\s*\d+','versionCode = 1000363',g,count=1); g=re.sub(r'versionName\s*=\s*"[^"]*"','versionName = "v340-vision-stable-v1"',g,count=1); GRADLE.write_text(g,encoding='utf-8')
# Guard: never add Stage7 LIVE/VOD overlay badge to stable Vision line.
for f in JAVA.glob('*.java'):
 t=f.read_text(encoding='utf-8')
 if 'stage7-visible-playback-status' in t or 'playbackStatusChip' in t: raise SystemExit('vision: forbidden LIVE status overlay present')
print('BLOFY Vision UI v1 applied: black-first premium palette + stronger safe TV focus; playback screens structurally untouched')
