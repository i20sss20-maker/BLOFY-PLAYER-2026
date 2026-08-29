#!/usr/bin/env python3
from pathlib import Path
import sys
ROOT=Path(__file__).resolve().parents[1]
SRC=ROOT/'BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player'
files={p.name:p.read_text(encoding='utf-8',errors='ignore') for p in SRC.glob('*.java')}
ALL='\n'.join(files.values())
def has(*xs): return all(x in ALL for x in xs)
checks=[
    ('Playback engine/provider policy', has('class PlaybackV2','preferVlc','engine(Session'))),
    ('Provider-scoped playback profiles', has('providerKey','blofy_playback_v2')),
    ('TTFF/rebuffer analytics learning', has('v2-first-frame','rebuffers','ttff_ms')),
    ('Adaptive buffer recommendations', has('recommendedBufferMode','stage5-buffer-profile')),
    ('Live edge target control', has('liveTargetOffsetMs','setLiveConfiguration','setTargetOffsetMs')),
    ('Decoder capability matrix', has('supportsMime','MediaCodecList','recommendedDecoderMode')),
    ('Audio/subtitle/quality track manager', has('cycleAudio','cycleSubtitle','cycleQuality','setMaxVideoSize')),
    ('Preview warm source handoff', has('v2-preview-handoff','URL_CACHE_BY_URL','> 30 * 60_000L')),
    ('Local source cache / reduced resolver hot path', has('resolvedUrl(BlofyModels.Media','playbackSessionKey','v2-preview-handoff')),
    ('Premium hero + quality badges', has('stage9-premium-home','addHero(page)','stage7QualityLabel')),
    ('Continue watching + latest rails + optional EPG Now/Next', has('متابعة المشاهدة','وصل حديثاً','EpgNowNextCache.lookup')),
    ('Quick diagnostics overlay', has('stage9-quick-diagnostics','quickSummary')),
]
failed=[]
for i,(name,ok) in enumerate(checks,1):
    print(f'{i:02d} {"PASS" if ok else "FAIL"} {name}')
    if not ok: failed.append(name)
print(f'PLAYBACK_V2_12: {12-len(failed)}/12 passed')
if failed: sys.exit(1)
