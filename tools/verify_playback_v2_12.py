#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player'
files = {p.name: p.read_text(encoding='utf-8', errors='ignore') for p in SRC.glob('*.java')}
ALL = '\n'.join(files.values())

def has(*needles):
    return all(needle in ALL for needle in needles)

checks = []
checks.append(['Playback engine/provider policy', has('class PlaybackV2', 'preferVlc', 'engine(Session')])
checks.append(['Provider-scoped playback profiles', has('providerKey', 'blofy_playback_v2')])
checks.append(['TTFF/rebuffer analytics learning', has('v2-first-frame', 'rebuffers', 'ttff_ms')])
checks.append(['Adaptive buffer recommendations', has('recommendedBufferMode', 'stage5-buffer-profile')])
checks.append(['Live edge target control', has('liveTargetOffsetMs', 'setLiveConfiguration', 'setTargetOffsetMs')])
checks.append(['Decoder capability matrix', has('supportsMime', 'MediaCodecList', 'recommendedDecoderMode')])
checks.append(['Audio/subtitle/quality track manager', has('cycleAudio', 'cycleSubtitle', 'cycleQuality', 'setMaxVideoSize')])
checks.append(['Preview warm source handoff', has('v2-preview-handoff', 'URL_CACHE_BY_URL', '> 30 * 60_000L')])
checks.append(['Local source cache / reduced resolver hot path', has('resolvedUrl(BlofyModels.Media', 'playbackSessionKey', 'v2-preview-handoff')])
checks.append(['Premium hero + quality badges', has('stage9-premium-home', 'addHero(page)', 'stage7QualityLabel')])
checks.append(['Continue watching + latest rails + optional EPG Now/Next', has('متابعة المشاهدة', 'وصل حديثاً', 'EpgNowNextCache.lookup')])
checks.append(['Quick diagnostics overlay', has('stage9-quick-diagnostics', 'quickSummary')])

failed = []
for index, check in enumerate(checks, 1):
    name, ok = check
    print(f'{index:02d} {"PASS" if ok else "FAIL"} {name}')
    if not ok:
        failed.append(name)

print(f'PLAYBACK_V2_12: {12 - len(failed)}/12 passed')
if failed:
    sys.exit(1)
