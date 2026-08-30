#!/usr/bin/env python3
from pathlib import Path
import ast, re, sys

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'BLOFY-ANDROID-2026/app'
JAVA = APP / 'src/main/java/tv/blofy/player'
GRADLE = APP / 'build.gradle.kts'
WORKFLOW = ROOT / '.github/workflows/v340-playback-core-hotfix.yml'
TOOLS = ROOT / 'tools'

files = {p.name: p.read_text(encoding='utf-8', errors='ignore') for p in JAVA.glob('*.java')}
all_java = '\n'.join(files.values())
gradle = GRADLE.read_text(encoding='utf-8', errors='ignore')
workflow = WORKFLOW.read_text(encoding='utf-8', errors='ignore')
checks=[]
def add(name, ok): checks.append((name, bool(ok)))
def count(token): return all_java.count(token)

add('single versionCode declaration', len(re.findall(r'\bversionCode\s*=\s*\d+', gradle)) == 1)
add('premium versionCode current', 'versionCode = 1000362' in gradle)
add('single versionName declaration', len(re.findall(r'\bversionName\s*=\s*"[^"]+"', gradle)) == 1)
add('premium versionName current', 'v340-premium-app-stage9' in gradle)
add('no merge conflict markers in Gradle', not any(x in gradle for x in ('<<<<<<<','=======','>>>>>>>')))

for name,text in files.items():
    add(f'{name}: no merge conflict markers', not any(x in text for x in ('<<<<<<<','=======','>>>>>>>')))
    imports=[ln.strip() for ln in text.splitlines() if ln.strip().startswith('import ')]
    add(f'{name}: no duplicate imports', len(imports)==len(set(imports)))

critical={
 'SevenMaxActivity.java':[r'\bprivate\s+void\s+showPremiumHomeStage9\s*\(',r'\bprivate\s+void\s+showHome\s*\('],
 'VodPlayerActivity.java':[r'\bprivate\s+void\s+cycleQuality\s*\(',r'\bprivate\s+TextView\s+qualityButton\s*;'],
 'PlayerActivity.java':[r'\bclass\s+PlayerActivity\b'],
 'PlaybackV2.java':[r'\bclass\s+PlaybackV2\b'],
 'EpgNowNextCache.java':[r'\bclass\s+EpgNowNextCache\b'],
}
for fname,patterns in critical.items():
    text=files.get(fname,'')
    for pattern in patterns:
        add(f'{fname}: single {pattern}', len(re.findall(pattern,text))==1)

add('premium home helper single', count('private void showPremiumHomeStage9()')==1)
add('premium home call single', count('showPremiumHomeStage9();')==1)
add('quality button declaration single', count('private TextView qualityButton;')==1)
add('quality cycle method single', count('private void cycleQuality()')==1)
add('EPG cache class single', count('final class EpgNowNextCache')==1)
add('quick diagnostics marker single', count('stage9-quick-diagnostics')==1)
home=files.get('SevenMaxActivity.java','')
add('premium home runtime guard present', 'if (!isFinishing()) return;' in home)
add('no unconditional premium-home return', 'showPremiumHomeStage9();\n        return;' not in home)
add('no duplicate premium-home invocation block', home.count('showPremiumHomeStage9();')==1)

add('soft failure classification retained','playback-soft-failure' in all_java)
add('same-url recovery retained','hotfix-proven-live-same-url' in all_java)
add('stall recovery retained','hotfix-steady-state-stall' in all_java)
add('PlaybackV2 single class',count('class PlaybackV2')==1)
add('Cronet header parity retained','transport=cronet-gms headers=parity' in all_java)
add('track manager retained','stage9-track-manager' in all_java)
add('optional EPG retained','EpgNowNextCache.lookup' in all_java)

stage_scripts=[
 'apply_v340_playback_core_hotfix.py','apply_v340_playback_core_hotfix_stage2.py','apply_v340_playback_core_hotfix_stage3.py','apply_v340_playback_core_hotfix_stage4.py','apply_v340_playback_core_hotfix_stage5.py','apply_v340_playback_hotfix_stage6_visual.py','apply_v340_playback_hotfix_stage7_visual_ux.py','apply_v340_playback_v2_stage8.py','apply_v340_premium_app_stage9.py','apply_v340_premium_app_stage9_replacement.py','verify_package64.py','verify_playback_v2_12.py','verify_v340_cleanliness.py']
for script in stage_scripts:
    p=TOOLS/script
    ok=p.exists()
    if ok:
        try: ast.parse(p.read_text(encoding='utf-8'))
        except SyntaxError: ok=False
    add(f'{script}: python syntax valid',ok)

for script in stage_scripts[:9]:
    cmd='python3 tools/'+script
    add('workflow single: '+script, workflow.count(cmd)==1)
add('workflow cleanliness gate single', workflow.count('python3 tools/verify_v340_cleanliness.py')==1)
add('workflow package64 gate single', workflow.count('python3 tools/verify_package64.py')==1)
add('workflow playback12 gate single', workflow.count('python3 tools/verify_playback_v2_12.py')==1)
add('workflow concurrency enabled','cancel-in-progress: true' in workflow)
add('workflow branch scoped','branches: [v340-playback-core-hotfix]' in workflow)
add('workflow no merge conflict markers',not any(x in workflow for x in ('<<<<<<<','=======','>>>>>>>')))

stale=['BLOFY PLAYER  •  v328','versionCode = 1000355','versionCode = 1000356','versionCode = 1000357','versionCode = 1000358','versionCode = 1000359','versionCode = 1000360','versionCode = 1000361']
for token in stale:
    add('no stale generated literal: '+token, token not in all_java and token not in gradle)

failed=[]
for i,(name,ok) in enumerate(checks,1):
    print(f'{i:03d} {"PASS" if ok else "FAIL"} {name}')
    if not ok: failed.append(name)
print(f'CLEANLINESS_STRONG: {len(checks)-len(failed)}/{len(checks)} passed')
if failed:
    print('FAILED:', '; '.join(failed)); sys.exit(1)
