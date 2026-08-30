#!/usr/bin/env python3
from pathlib import Path
import re, sys

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'BLOFY-ANDROID-2026/app'
JAVA = APP / 'src/main/java/tv/blofy/player'
GRADLE = APP / 'build.gradle.kts'
WORKFLOW = ROOT / '.github/workflows/v340-playback-core-hotfix.yml'

files = {p.name: p.read_text(encoding='utf-8', errors='ignore') for p in JAVA.glob('*.java')}
all_java = '\n'.join(files.values())
gradle = GRADLE.read_text(encoding='utf-8', errors='ignore')
workflow = WORKFLOW.read_text(encoding='utf-8', errors='ignore')

checks = []
def add(name, ok): checks.append((name, bool(ok)))

def count(token): return all_java.count(token)

# Release identity must be single and current.
add('single versionCode declaration', len(re.findall(r'\bversionCode\s*=\s*\d+', gradle)) == 1)
add('premium versionCode current', 'versionCode = 1000362' in gradle)
add('single versionName declaration', len(re.findall(r'\bversionName\s*=\s*"[^"]+"', gradle)) == 1)
add('premium versionName current', 'v340-premium-app-stage9' in gradle)

# Stage9 must be inserted once only; duplicate declarations are a common reconstruction failure.
add('premium home helper single', count('private void showPremiumHomeStage9()') == 1)
add('premium home call single', count('showPremiumHomeStage9();') == 1)
add('quality button declaration single', count('private TextView qualityButton;') == 1)
add('quality cycle method single', count('private void cycleQuality()') == 1)
add('EPG cache class single', count('final class EpgNowNextCache') == 1)
add('quick diagnostics marker single', count('stage9-quick-diagnostics') == 1)

# Guard must remain runtime-dependent so javac does not mark legacy body unreachable.
home = files.get('SevenMaxActivity.java','')
add('premium home runtime guard present', 'if (!isFinishing()) return;' in home)
add('no unconditional premium-home return', 'showPremiumHomeStage9();\n        return;' not in home)

# Playback recovery invariants that must not regress during cleanup.
add('soft failure classification retained', 'playback-soft-failure' in all_java)
add('same-url recovery retained', 'hotfix-proven-live-same-url' in all_java)
add('stall recovery retained', 'hotfix-steady-state-stall' in all_java)
add('PlaybackV2 single class', count('class PlaybackV2') == 1)
add('Cronet header parity retained', 'transport=cronet-gms headers=parity' in all_java)

# CI pipeline should apply each premium stage exactly once.
stage_cmds = [
 'python3 tools/apply_v340_playback_core_hotfix.py',
 'python3 tools/apply_v340_playback_core_hotfix_stage2.py',
 'python3 tools/apply_v340_playback_core_hotfix_stage3.py',
 'python3 tools/apply_v340_playback_core_hotfix_stage4.py',
 'python3 tools/apply_v340_playback_core_hotfix_stage5.py',
 'python3 tools/apply_v340_playback_hotfix_stage6_visual.py',
 'python3 tools/apply_v340_playback_hotfix_stage7_visual_ux.py',
 'python3 tools/apply_v340_playback_v2_stage8.py',
 'python3 tools/apply_v340_premium_app_stage9.py',
]
for cmd in stage_cmds:
    add('workflow single: ' + cmd.split('/')[-1], workflow.count(cmd) == 1)

failed=[]
for i,(name,ok) in enumerate(checks,1):
    print(f'{i:02d} {"PASS" if ok else "FAIL"} {name}')
    if not ok: failed.append(name)
print(f'CLEANLINESS: {len(checks)-len(failed)}/{len(checks)} passed')
if failed:
    print('FAILED:', '; '.join(failed))
    sys.exit(1)
