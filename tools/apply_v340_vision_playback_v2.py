#!/usr/bin/env python3
from pathlib import Path
import re
ROOT=Path(__file__).resolve().parents[1]
APP=ROOT/'BLOFY-ANDROID-2026/app'; JAVA=APP/'src/main/java/tv/blofy/player'
P=JAVA/'PlayerActivity.java'; V=JAVA/'VodPlayerActivity.java'; G=APP/'build.gradle.kts'

# ---- Live/general full-screen playback: bounded spinner + same-URL steady-state recovery ----
p=P.read_text(encoding='utf-8')
if 'private boolean visionSameUrlRecoveryAttempted;' not in p:
    anchor='    private boolean lifecycleStarted;\n'
    if anchor not in p: raise SystemExit('vision-v2: player field anchor missing')
    p=p.replace(anchor,anchor+'    private boolean visionSameUrlRecoveryAttempted;\n',1)

if 'private final Runnable visionSteadyStateStall' not in p:
    anchor='    private final Runnable markPlaybackStable = () -> {\n'
    if anchor not in p: raise SystemExit('vision-v2: stable runnable anchor missing')
    helper='''    private final Runnable visionSteadyStateStall = () -> {\n        if (!firstFrameRendered || usingVlc || player == null) return;\n        if (player.getPlaybackState() != Player.STATE_BUFFERING) return;\n        Log.w(TAG, "vision-steady-stall sameUrlAttempted=" + visionSameUrlRecoveryAttempted\n                + " kind=" + kind + " ext=" + extension);\n        if (!visionSameUrlRecoveryAttempted && validUrl(url)) {\n            visionSameUrlRecoveryAttempted = true;\n            long keepPosition = isLive() ? 0 : Math.max(0, player.getCurrentPosition());\n            releaseMedia3Player();\n            resumePosition = keepPosition;\n            firstFrameRendered = false;\n            initializePlayer();\n            return;\n        }\n        recoverFromFailure("توقف تدفق الفيديو بعد بدء التشغيل");\n    };\n\n'''
    p=p.replace(anchor,helper+anchor,1)

# Startup timeout on Live should try provider-direct once before decoder fallback.
old='''        if (PlaybackPolicy.isNetworkFailure(reason)\n                && !PlaybackPolicy.isStartupTimeout(reason)\n                && "canonical".equals(sourceVariant) && !id.isEmpty()) {'''
new='''        if ((PlaybackPolicy.isNetworkFailure(reason)\n                || (isLive() && PlaybackPolicy.isStartupTimeout(reason)))\n                && "canonical".equals(sourceVariant) && !id.isEmpty()) {'''
if old in p: p=p.replace(old,new,1)
elif '(isLive() && PlaybackPolicy.isStartupTimeout(reason))' not in p:
    raise SystemExit('vision-v2: player recovery condition missing')

# State machine: short buffering after first frame is normal; sustained buffering is not.
old='''        if (playbackState == Player.STATE_BUFFERING) {\n            if (!firstFrameRendered) progress.setVisibility(View.VISIBLE);\n            return;\n        }'''
new='''        if (playbackState == Player.STATE_BUFFERING) {\n            if (!firstFrameRendered) progress.setVisibility(View.VISIBLE);\n            else {\n                playbackHandler.removeCallbacks(visionSteadyStateStall);\n                playbackHandler.postDelayed(visionSteadyStateStall, isLive() ? 6_500L : 12_000L);\n            }\n            return;\n        }'''
if old in p: p=p.replace(old,new,1)
elif 'postDelayed(visionSteadyStateStall' not in p: raise SystemExit('vision-v2: buffering anchor missing')

ready='''        if (playbackState == Player.STATE_READY) {\n'''
if ready in p and 'removeCallbacks(visionSteadyStateStall);\n            long readyMs' not in p:
    p=p.replace(ready,ready+'            playbackHandler.removeCallbacks(visionSteadyStateStall);\n',1)

ff='''        firstFrameRendered = true;\n        playbackHandler.removeCallbacks(playbackTimeout);'''
if ff in p and 'visionSameUrlRecoveryAttempted = false;' not in p[p.find(ff):p.find(ff)+250]:
    p=p.replace(ff,'''        firstFrameRendered = true;\n        visionSameUrlRecoveryAttempted = false;\n        playbackHandler.removeCallbacks(visionSteadyStateStall);\n        playbackHandler.removeCallbacks(playbackTimeout);''',1)

# Always clean watchdog on release/channel switch.
p=p.replace('        playbackHandler.removeCallbacks(markPlaybackStable);\n        playbackHandler.removeCallbacks(hideTitle);\n        warmLiveSwitchPending = false;',
'''        playbackHandler.removeCallbacks(markPlaybackStable);\n        playbackHandler.removeCallbacks(visionSteadyStateStall);\n        playbackHandler.removeCallbacks(hideTitle);\n        warmLiveSwitchPending = false;''',1)
P.write_text(p,encoding='utf-8')

# ---- VOD / episodes: never treat an empty/failed source as a completed episode ----
v=V.read_text(encoding='utf-8')
if 'private boolean visionSameUrlRecoveryAttempted;' not in v:
    anchor='    private boolean contentEnded;\n'
    if anchor not in v: raise SystemExit('vision-v2: vod field anchor missing')
    v=v.replace(anchor,anchor+'    private boolean visionSameUrlRecoveryAttempted;\n',1)

if 'private final Runnable visionVodSteadyStall' not in v:
    anchor='    private final Runnable startupTimeout = () -> {\n'
    if anchor not in v: raise SystemExit('vision-v2: vod watchdog anchor missing')
    helper='''    private final Runnable visionVodSteadyStall = () -> {\n        if (!firstFrame || usingVlc || player == null) return;\n        if (player.getPlaybackState() != Player.STATE_BUFFERING) return;\n        if (!visionSameUrlRecoveryAttempted && validUrl(resolvedUrl)) {\n            visionSameUrlRecoveryAttempted = true;\n            resumePosition = Math.max(0, player.getCurrentPosition());\n            releaseAllEngines();\n            firstFrame = false;\n            openMedia3();\n            return;\n        }\n        recover("توقف تدفق الفيديو بعد بدء التشغيل");\n    };\n\n'''
    v=v.replace(anchor,helper+anchor,1)

# Canonical startup timeout gets one direct-source attempt before VLC. This is especially useful for MKV/episodes.
old='''        if (PlaybackPolicy.isNetworkFailure(reason)\n                && !PlaybackPolicy.isStartupTimeout(reason)\n                && !alternateSourceAttempted && !id.isEmpty()) {'''
new='''        if ((PlaybackPolicy.isNetworkFailure(reason) || PlaybackPolicy.isStartupTimeout(reason))\n                && !alternateSourceAttempted && !id.isEmpty()) {'''
if old in v: v=v.replace(old,new,1)
elif 'PlaybackPolicy.isNetworkFailure(reason) || PlaybackPolicy.isStartupTimeout(reason)' not in v:
    raise SystemExit('vision-v2: vod recovery condition missing')

# VLC must not announce EndReached as a real episode completion before any frame/time progress.
old='''            case org.videolan.libvlc.MediaPlayer.Event.EndReached:\n                onContentEnded();\n                break;'''
new='''            case org.videolan.libvlc.MediaPlayer.Event.EndReached:\n                if (!firstFrame) recover("انتهى المصدر قبل بدء الفيديو");\n                else onContentEnded();\n                break;'''
if old in v: v=v.replace(old,new,1)
elif 'if (!firstFrame) recover("انتهى المصدر قبل بدء الفيديو")' not in v:
    raise SystemExit('vision-v2: vlc ended guard missing')

# Media3: same guard, plus steady-state stall watchdog.
old='''        if (state == Player.STATE_BUFFERING && !firstFrame) spinner.setVisibility(View.VISIBLE);\n        if (state == Player.STATE_READY && firstFrame) spinner.setVisibility(View.GONE);\n        if (state == Player.STATE_ENDED) {\n            onContentEnded();\n        }'''
new='''        if (state == Player.STATE_BUFFERING) {\n            if (!firstFrame) spinner.setVisibility(View.VISIBLE);\n            else { main.removeCallbacks(visionVodSteadyStall); main.postDelayed(visionVodSteadyStall, 12_000L); }\n        }\n        if (state == Player.STATE_READY) {\n            main.removeCallbacks(visionVodSteadyStall);\n            if (firstFrame) spinner.setVisibility(View.GONE);\n        }\n        if (state == Player.STATE_ENDED) {\n            main.removeCallbacks(visionVodSteadyStall);\n            if (!firstFrame || durationMs() < 1_000) recover("انتهى المصدر قبل بدء الفيديو");\n            else onContentEnded();\n        }'''
if old in v: v=v.replace(old,new,1)
elif 'durationMs() < 1_000' not in v: raise SystemExit('vision-v2: media3 ended guard missing')

ff='''        firstFrame = true;\n        spinner.setVisibility(View.GONE);'''
if ff in v and 'visionSameUrlRecoveryAttempted = false;' not in v[v.find(ff):v.find(ff)+220]:
    v=v.replace(ff,'''        firstFrame = true;\n        visionSameUrlRecoveryAttempted = false;\n        main.removeCallbacks(visionVodSteadyStall);\n        spinner.setVisibility(View.GONE);''',1)

# Retry resets compatibility state but never auto-finishes the Activity.
anchor='''        alternateSourceAttempted = false;\n'''
if anchor in v and 'visionSameUrlRecoveryAttempted = false;' not in v[v.find(anchor):v.find(anchor)+180]:
    v=v.replace(anchor,anchor+'        visionSameUrlRecoveryAttempted = false;\n',1)

# Ensure release removes long-session callback if a matching cleanup block exists.
if 'main.removeCallbacks(visionVodSteadyStall);' not in v[v.rfind('private void releaseAllEngines'):]:
    idx=v.find('    private void releaseAllEngines()')
    if idx>=0:
        brace=v.find('{',idx)+1
        v=v[:brace]+'\n        main.removeCallbacks(visionVodSteadyStall);'+v[brace:]
V.write_text(v,encoding='utf-8')

g=G.read_text(encoding='utf-8'); g=re.sub(r'versionCode\s*=\s*\d+','versionCode = 1000365',g,count=1); g=re.sub(r'versionName\s*=\s*"[^"]*"','versionName = "v340-vision-stable-playback-v2"',g,count=1); G.write_text(g,encoding='utf-8')

checks={
P:['visionSteadyStateStall','visionSameUrlRecoveryAttempted','isLive() && PlaybackPolicy.isStartupTimeout(reason)','6_500L'],
V:['visionVodSteadyStall','durationMs() < 1_000','انتهى المصدر قبل بدء الفيديو','PlaybackPolicy.isNetworkFailure(reason) || PlaybackPolicy.isStartupTimeout(reason)'],
G:['versionCode = 1000365','v340-vision-stable-playback-v2']}
for path,marks in checks.items():
    s=path.read_text(encoding='utf-8')
    for m in marks:
        if m not in s: raise SystemExit(f'vision-v2 invariant missing {path.name}: {m}')
print('BLOFY Vision Playback v2 applied: bounded Live stall recovery + direct startup fallback + safe episode Ended handling + VOD same-URL recovery')
