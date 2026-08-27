#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PLAYER = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player/PlayerActivity.java"
text = PLAYER.read_text(encoding="utf-8")


def replace_once(old, new, label):
    global text
    if old not in text:
        raise SystemExit(f"v340 session guard patch mismatch: {label}")
    text = text.replace(old, new, 1)

# A monotonically increasing session epoch invalidates every timeout/resolve callback
# belonging to an older channel/content attempt.
replace_once(
'''    private int resolveGeneration;\n''',
'''    private int resolveGeneration;\n    private int playbackSessionEpoch;\n    private int startupTimeoutEpoch;\n''',
"session fields")

replace_once(
'''    private final Runnable playbackTimeout = () -> {\n        if ((!usingVlc && player == null) || (usingVlc && vlcPlayer == null)\n                || firstFrameRendered) return;\n''',
'''    private final Runnable playbackTimeout = () -> {\n        if (startupTimeoutEpoch != playbackSessionEpoch) {\n            PlaybackDiagnostics.marker(PlayerActivity.this, "stale-timeout-ignored", kind, id, extension,\n                    activeTransportName(), "epoch=" + startupTimeoutEpoch + " current=" + playbackSessionEpoch);\n            return;\n        }\n        if ((!usingVlc && player == null) || (usingVlc && vlcPlayer == null)\n                || firstFrameRendered || !lifecycleStarted) return;\n''',
"stale timeout guard")

replace_once(
'''    private void schedulePlaybackTimeout() {\n        playbackHandler.removeCallbacks(playbackTimeout);\n''',
'''    private void schedulePlaybackTimeout() {\n        playbackHandler.removeCallbacks(playbackTimeout);\n        startupTimeoutEpoch = playbackSessionEpoch;\n''',
"timeout epoch capture")

# Tie resolver completion to both its resolver generation and the playback session.
replace_once(
'''    private void resolvePlaybackLink() {\n        int token = ++resolveGeneration;\n''',
'''    private void resolvePlaybackLink() {\n        int token = ++resolveGeneration;\n        int sessionToken = playbackSessionEpoch;\n''',
"resolver session token")

text = text.replace(
'''                    if (token != resolveGeneration || isFinishing() || isDestroyed()) return;\n''',
'''                    if (token != resolveGeneration || sessionToken != playbackSessionEpoch\n                            || isFinishing() || isDestroyed()) {\n                        PlaybackDiagnostics.marker(PlayerActivity.this, "stale-resolve-ignored", kind, id, extension,\n                                activeTransportName(), "token=" + token + " session=" + sessionToken);\n                        return;\n                    }\n''',
2)

# Release strong references as soon as the resolver has delivered its result/error.
text = text.replace(
'''                    url = finalResolved.startsWith("http") ? finalResolved\n''',
'''                    resolveTask = null;\n                    resolveCancellation = null;\n                    url = finalResolved.startsWith("http") ? finalResolved\n''',
1)
text = text.replace(
'''                    warmLiveSwitchPending = false;\n                    if ("direct".equals(requestedVariant) && restoreCanonicalSource()) {\n''',
'''                    resolveTask = null;\n                    resolveCancellation = null;\n                    warmLiveSwitchPending = false;\n                    if ("direct".equals(requestedVariant) && restoreCanonicalSource()) {\n''',
1)

# live_guard has already rebuilt this method. Increment the playback session before
# cancelling old work so every queued callback becomes harmless immediately.
replace_once(
'''        int epoch = ++liveSwitchEpoch;\n        playbackHandler.removeCallbacks(playbackTimeout);\n''',
'''        int epoch = ++liveSwitchEpoch;\n        playbackSessionEpoch++;\n        playbackHandler.removeCallbacks(playbackTimeout);\n''',
"live switch session bump")

# A manual retry is a brand-new session too; invalidate old resolver/player work first.
replace_once(
'''    private void manualRetry() {\n        recoveryStep = preferredRecoveryStep();\n''',
'''    private void manualRetry() {\n        playbackSessionEpoch++;\n        cancelResolve(true);\n        recoveryStep = preferredRecoveryStep();\n''',
"manual retry cleanup")

# Leaving the player must invalidate all pending UI callbacks before releasing engines.
replace_once(
'''    @Override protected void onStop() {\n        lifecycleStarted = false;\n        cancelResolve(true);\n''',
'''    @Override protected void onStop() {\n        lifecycleStarted = false;\n        playbackSessionEpoch++;\n        cancelResolve(true);\n''',
"stop session cleanup")

# Do not let an old failure dialog cover a newly playing source.
replace_once(
'''    private void showPlaybackFailure(String reason) {\n        releaseMedia3Player();\n''',
'''    private void showPlaybackFailure(String reason) {\n        if (!lifecycleStarted || isFinishing() || isDestroyed()) return;\n        releaseMedia3Player();\n''',
"failure lifecycle guard")

# Give long-lived sessions a deterministic reset point whenever all engines are released.
replace_once(
'''        firstFrameRendered = false;\n        playbackStartedAtMs = 0;\n    }\n\n    private void cancelResolve(boolean invalidateGeneration) {\n''',
'''        firstFrameRendered = false;\n        playbackStartedAtMs = 0;\n        startupTimeoutEpoch = playbackSessionEpoch;\n    }\n\n    private void cancelResolve(boolean invalidateGeneration) {\n''',
"release state reset")

PLAYER.write_text(text, encoding="utf-8")

for token in ["playbackSessionEpoch", "stale-timeout-ignored", "stale-resolve-ignored",
              "resolveTask = null", "sessionToken != playbackSessionEpoch"]:
    if token not in text:
        raise SystemExit("v340 session guard invariant missing: " + token)

print("v340 session guard applied: stale timeout/resolve suppression + long-session cleanup")
