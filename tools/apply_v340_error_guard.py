#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PLAYER = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player/PlayerActivity.java"
text = PLAYER.read_text(encoding="utf-8")


def replace_once(old, new, label):
    global text
    if old not in text:
        raise SystemExit(f"v340 error guard patch mismatch: {label}")
    text = text.replace(old, new, 1)

# One fatal dialog maximum per active playback session. Old callbacks/recovery tails
# may still arrive after a switch, but they must never cover the new channel.
replace_once(
'''    private int startupTimeoutEpoch;\n''',
'''    private int startupTimeoutEpoch;\n    private int failureShownEpoch = -1;\n    private boolean recoveryInProgress;\n''',
"failure guard fields")

# Reset the fatal-dialog latch whenever a brand-new live source is selected.
replace_once(
'''        playbackSessionEpoch++;\n        playbackHandler.removeCallbacks(playbackTimeout);\n''',
'''        playbackSessionEpoch++;\n        failureShownEpoch = -1;\n        recoveryInProgress = false;\n        playbackHandler.removeCallbacks(playbackTimeout);\n''',
"live switch reset")

# Reset on manual retry too.
replace_once(
'''    private void manualRetry() {\n        playbackSessionEpoch++;\n        cancelResolve(true);\n''',
'''    private void manualRetry() {\n        playbackSessionEpoch++;\n        failureShownEpoch = -1;\n        recoveryInProgress = false;\n        cancelResolve(true);\n''',
"manual retry reset")

# Serialize recovery. A timeout + decoder callback can fire close together; only the
# first callback is allowed to advance the fallback ladder.
replace_once(
'''    private void recoverFromFailure(String reason) {\n        if (isFinishing() || isDestroyed()) return;\n        playbackHandler.removeCallbacks(playbackTimeout);\n        playbackHandler.removeCallbacks(markPlaybackStable);\n''',
'''    private void recoverFromFailure(String reason) {\n        if (isFinishing() || isDestroyed() || !lifecycleStarted) return;\n        if (failureShownEpoch == playbackSessionEpoch) return;\n        if (recoveryInProgress) {\n            PlaybackDiagnostics.marker(this, "duplicate-recovery-ignored", kind, id, extension,\n                    activeTransportName(), reason == null ? "unknown" : reason);\n            return;\n        }\n        recoveryInProgress = true;\n        playbackHandler.removeCallbacks(playbackTimeout);\n        playbackHandler.removeCallbacks(markPlaybackStable);\n''',
"serialize recovery")

# Every branch that launches the next recovery stage releases the lock first. The next
# engine gets its own callbacks; this prevents the current failure from recursively
# starting two routes at once.
text = text.replace(
'''            releasePlayer();\n            sourceVariant = "direct";\n''',
'''            recoveryInProgress = false;\n            releasePlayer();\n            sourceVariant = "direct";\n''',
1)
text = text.replace(
'''            recoveryStep = 2;\n            if ("direct".equals(sourceVariant)) restoreCanonicalSource();\n            openVlc(reason);\n''',
'''            recoveryInProgress = false;\n            recoveryStep = 2;\n            if ("direct".equals(sourceVariant)) restoreCanonicalSource();\n            openVlc(reason);\n''',
1)

# Final error panel is deduplicated per session. It can only be shown for the current,
# active source after every bounded fallback has really failed.
replace_once(
'''    private void showPlaybackFailure(String reason) {\n        if (!lifecycleStarted || isFinishing() || isDestroyed()) return;\n        releaseMedia3Player();\n''',
'''    private void showPlaybackFailure(String reason) {\n        if (!lifecycleStarted || isFinishing() || isDestroyed()) return;\n        if (failureShownEpoch == playbackSessionEpoch) return;\n        failureShownEpoch = playbackSessionEpoch;\n        recoveryInProgress = false;\n        PlaybackDiagnostics.marker(this, "final-playback-failure", kind, id, extension,\n                activeTransportName(), reason == null ? "unknown" : reason);\n        releaseMedia3Player();\n''',
"dedupe final failure")

# Once a frame is rendered, clear recovery state. This is also what stops a delayed
# secondary callback from converting a successful start into an error panel.
replace_once(
'''    @Override public void onRenderedFirstFrame() {\n        if (playbackStartedAtMs == 0) return;\n        firstFrameRendered = true;\n''',
'''    @Override public void onRenderedFirstFrame() {\n        if (playbackStartedAtMs == 0) return;\n        firstFrameRendered = true;\n        recoveryInProgress = false;\n        failureShownEpoch = -1;\n''',
"first frame clears guard")

# Lifecycle cleanup invalidates the latch, so a fresh onStart never inherits an old
# error state from a long-running session.
replace_once(
'''        firstFrameRendered = false;\n        playbackStartedAtMs = 0;\n        startupTimeoutEpoch = playbackSessionEpoch;\n''',
'''        firstFrameRendered = false;\n        playbackStartedAtMs = 0;\n        startupTimeoutEpoch = playbackSessionEpoch;\n        recoveryInProgress = false;\n''',
"release recovery reset")

PLAYER.write_text(text, encoding="utf-8")

for token in ["failureShownEpoch", "duplicate-recovery-ignored", "final-playback-failure", "recoveryInProgress"]:
    if token not in text:
        raise SystemExit("v340 error guard invariant missing: " + token)

print("v340 error guard applied: one recovery at a time + one final dialog per current session")
