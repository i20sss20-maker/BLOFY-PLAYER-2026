#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player"
PLAYER = JAVA / "PlayerActivity.java"
GRADLE = ROOT / "BLOFY-ANDROID-2026/app/build.gradle.kts"

p = PLAYER.read_text(encoding="utf-8")

# Stage4 goals:
# 1) Detect real post-first-frame stalls, not harmless short buffering.
# 2) First live stall gets one same-URL reconnect when the source already rendered video.
# 3) Repeated stalls escalate into the existing bounded route/engine recovery ladder.
# 4) Apply identical protection to Media3 and LibVLC without creating retry loops.

if "STEADY_STATE_LIVE_STALL_MS" not in p:
    anchor = "    private static final long LIVE_STABLE_WINDOW_MS = 4_000L;\n"
    if anchor not in p:
        raise SystemExit("stage4: stable-window anchor missing")
    p = p.replace(anchor, anchor +
        "    private static final long STEADY_STATE_LIVE_STALL_MS = 5_500L;\n"
        "    private static final long STEADY_STATE_VOD_STALL_MS = 10_000L;\n", 1)

if "private boolean playbackBuffering;" not in p:
    anchor = "    private boolean liveFirstFrameSeen;\n"
    if anchor not in p:
        raise SystemExit("stage4: liveFirstFrameSeen field missing")
    p = p.replace(anchor, anchor +
        "    private boolean playbackBuffering;\n"
        "    private long bufferingStartedAtMs;\n", 1)

if "private final Runnable steadyStateStallWatchdog" not in p:
    anchor = "    private final Runnable markPlaybackStable = () -> {\n"
    idx = p.find(anchor)
    if idx < 0:
        raise SystemExit("stage4: markPlaybackStable anchor missing")
    watchdog = '''    private final Runnable steadyStateStallWatchdog = () -> {\n        if (!firstFrameRendered || !playbackBuffering || isFinishing() || isDestroyed()) return;\n        long stalledFor = bufferingStartedAtMs <= 0 ? 0L\n                : SystemClock.elapsedRealtime() - bufferingStartedAtMs;\n        playbackBuffering = false;\n        bufferingStartedAtMs = 0L;\n        PlaybackDiagnostics.marker(this, "hotfix-steady-state-stall", kind, id, extension,\n                sourceVariant, "engine=" + activeTransportName() + " ms=" + stalledFor);\n        Log.w(TAG, "steady-state-stall kind=" + kind + " ext=" + extension\n                + " ms=" + stalledFor + " engine=" + activeTransportName());\n\n        // A live URL that has already rendered video is worth one exact reconnect.\n        // If that was already consumed, the existing bounded recovery ladder takes over.\n        if (isLive() && recoverProvenLiveSilently("steady-state-stall")) return;\n        recoverFromFailure(isLive() ? "توقف البث بعد بدء التشغيل" : "توقف تشغيل الفيديو");\n    };\n\n'''
    p = p[:idx] + watchdog + p[idx:]

if "private void noteSteadyStateBuffering()" not in p:
    anchor = "    private void schedulePlaybackTimeout() {\n"
    if anchor not in p:
        raise SystemExit("stage4: schedulePlaybackTimeout anchor missing")
    helpers = '''    private void noteSteadyStateBuffering() {\n        if (!firstFrameRendered) return;\n        if (!playbackBuffering) {\n            playbackBuffering = true;\n            bufferingStartedAtMs = SystemClock.elapsedRealtime();\n        }\n        playbackHandler.removeCallbacks(steadyStateStallWatchdog);\n        playbackHandler.postDelayed(steadyStateStallWatchdog,\n                isLive() ? STEADY_STATE_LIVE_STALL_MS : STEADY_STATE_VOD_STALL_MS);\n    }\n\n    private void clearSteadyStateBuffering() {\n        playbackBuffering = false;\n        bufferingStartedAtMs = 0L;\n        playbackHandler.removeCallbacks(steadyStateStallWatchdog);\n    }\n\n'''
    p = p.replace(anchor, helpers + anchor, 1)

state_start = p.find("    @Override public void onPlaybackStateChanged(int playbackState) {")
state_end = p.find("\n    @Override public void onRenderedFirstFrame()", state_start)
if state_start < 0 or state_end < 0:
    raise SystemExit("stage4: Media3 state callback missing")
state = p[state_start:state_end]
state = state.replace(
    "        if (playbackState == Player.STATE_BUFFERING) {\n            if (!firstFrameRendered) progress.setVisibility(View.VISIBLE);\n            return;\n        }",
    "        if (playbackState == Player.STATE_BUFFERING) {\n            if (!firstFrameRendered) progress.setVisibility(View.VISIBLE);\n            else noteSteadyStateBuffering();\n            return;\n        }",
    1,
)
ready_anchor = "        if (playbackState == Player.STATE_READY) {\n"
if ready_anchor not in state:
    raise SystemExit("stage4: Media3 READY anchor missing")
if "clearSteadyStateBuffering();" not in state:
    state = state.replace(ready_anchor, ready_anchor + "            clearSteadyStateBuffering();\n", 1)
ended_anchor = "        if (playbackState == Player.STATE_ENDED) {\n"
if ended_anchor in state and "clearSteadyStateBuffering();\n            progress" not in state:
    state = state.replace(ended_anchor, ended_anchor + "            clearSteadyStateBuffering();\n", 1)
p = p[:state_start] + state + p[state_end:]

vlc_start = p.find("    private void onVlcEvent(org.videolan.libvlc.MediaPlayer.Event event) {")
vlc_end = p.find("\n    private void markVlcFirstFrame()", vlc_start)
if vlc_start < 0 or vlc_end < 0:
    raise SystemExit("stage4: VLC event block missing")
vlc = p[vlc_start:vlc_end]
progress_block = '''            case org.videolan.libvlc.MediaPlayer.Event.Vout:\n            case org.videolan.libvlc.MediaPlayer.Event.TimeChanged:\n            case org.videolan.libvlc.MediaPlayer.Event.PositionChanged:\n                applyVlcSubtitlePreference();\n                markVlcFirstFrame();\n                break;'''
progress_new = '''            case org.videolan.libvlc.MediaPlayer.Event.Vout:\n            case org.videolan.libvlc.MediaPlayer.Event.TimeChanged:\n            case org.videolan.libvlc.MediaPlayer.Event.PositionChanged:\n                clearSteadyStateBuffering();\n                applyVlcSubtitlePreference();\n                markVlcFirstFrame();\n                break;'''
if progress_block not in vlc and progress_new not in vlc:
    raise SystemExit("stage4: VLC forward-progress anchor missing")
vlc = vlc.replace(progress_block, progress_new, 1)
vlc = vlc.replace(
    "            case org.videolan.libvlc.MediaPlayer.Event.Buffering:\n                if (!firstFrameRendered) progress.setVisibility(View.VISIBLE);\n                break;",
    "            case org.videolan.libvlc.MediaPlayer.Event.Buffering:\n                if (!firstFrameRendered) progress.setVisibility(View.VISIBLE);\n                else noteSteadyStateBuffering();\n                break;",
    1,
)
p = p[:vlc_start] + vlc + p[vlc_end:]

for marker in [
    "    @Override public void onRenderedFirstFrame() {\n",
    "    private void markVlcFirstFrame() {\n",
]:
    start = p.find(marker)
    if start < 0:
        raise SystemExit(f"stage4: first-frame marker missing: {marker.strip()}")
    block_end = p.find("\n    }", start)
    block = p[start:block_end]
    if "clearSteadyStateBuffering();" not in block:
        if marker.startswith("    @Override"):
            guard = "        if (firstFrameRendered) return;\n"
            guard_at = p.find(guard, start, block_end)
            insert_at = guard_at + len(guard) if guard_at >= 0 else start + len(marker)
        else:
            guard = "        if (firstFrameRendered || !usingVlc) return;\n"
            guard_at = p.find(guard, start, block_end)
            insert_at = guard_at + len(guard) if guard_at >= 0 else start + len(marker)
        p = p[:insert_at] + "        clearSteadyStateBuffering();\n" + p[insert_at:]

for signature in [
    "    private void switchLiveChannel(BlofyModels.Media media) {\n",
    "    private void recoverFromFailure(String reason) {\n",
    "    private void manualRetry() {\n",
    "    private void releasePlayer() {\n",
]:
    start = p.find(signature)
    if start < 0:
        raise SystemExit(f"stage4: cleanup method missing: {signature.strip()}")
    end = p.find("\n    }", start)
    block = p[start:end]
    if "clearSteadyStateBuffering();" not in block:
        insert_at = start + len(signature)
        if "switchLiveChannel" in signature:
            guard = "        if (!isLive() || media == null || media.id.equals(id)) return;\n"
            guard_at = p.find(guard, start, end)
            if guard_at >= 0:
                insert_at = guard_at + len(guard)
        p = p[:insert_at] + "        clearSteadyStateBuffering();\n" + p[insert_at:]

PLAYER.write_text(p, encoding="utf-8")

g = GRADLE.read_text(encoding="utf-8")
g = re.sub(r'versionCode\s*=\s*\d+', 'versionCode = 1000357', g, count=1)
g = re.sub(r'versionName\s*=\s*"[^"]*"', 'versionName = "v340-playback-hotfix-stage4"', g, count=1)
GRADLE.write_text(g, encoding="utf-8")

checks = {
    PLAYER: [
        "STEADY_STATE_LIVE_STALL_MS = 5_500L",
        "STEADY_STATE_VOD_STALL_MS = 10_000L",
        "steadyStateStallWatchdog",
        "hotfix-steady-state-stall",
        "noteSteadyStateBuffering();",
        "clearSteadyStateBuffering();",
        'recoverProvenLiveSilently("steady-state-stall")',
        '"توقف البث بعد بدء التشغيل"',
    ],
    GRADLE: [
        "versionCode = 1000357",
        'versionName = "v340-playback-hotfix-stage4"',
    ],
}
for path, markers in checks.items():
    text = path.read_text(encoding="utf-8")
    for marker in markers:
        if marker not in text:
            raise SystemExit(f"stage4 invariant missing {path.name}: {marker}")

p = PLAYER.read_text(encoding="utf-8")
wd_start = p.find("    private final Runnable steadyStateStallWatchdog")
wd_end = p.find("\n    };", wd_start)
if wd_start < 0 or wd_end < 0 or "!firstFrameRendered" not in p[wd_start:wd_end]:
    raise SystemExit("stage4: steady-state watchdog lost first-frame safety gate")
if "postDelayed(steadyStateStallWatchdog" in p[wd_start:wd_end]:
    raise SystemExit("stage4: watchdog must remain one-shot and non-recursive")

print("v340 playback-core hotfix stage4 applied: post-first-frame stall hysteresis + same-URL self-heal + bounded engine escalation")
