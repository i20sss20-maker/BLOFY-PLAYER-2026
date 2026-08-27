#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PLAYER = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player/PlayerActivity.java"
text = PLAYER.read_text(encoding="utf-8")


def replace_once(old, new, label):
    global text
    if old not in text:
        raise SystemExit(f"v340 live guard patch mismatch: {label}")
    text = text.replace(old, new, 1)


def replace_method(signature, replacement, label):
    global text
    start = text.find(signature)
    if start < 0:
        raise SystemExit(f"v340 live guard method missing: {label}")
    brace = text.find("{", start)
    depth = 0
    end = -1
    for i in range(brace, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                end = i + 1
                break
    if end < 0:
        raise SystemExit(f"v340 live guard method end missing: {label}")
    text = text[:start] + replacement + text[end:]

# Track post-first-frame stalls separately from startup timeout.
replace_once(
'''    private long playbackStartedAtMs;\n''',
'''    private long playbackStartedAtMs;\n    private long liveWatchdogPositionMs = Long.MIN_VALUE;\n    private int liveWatchdogStalls;\n    private int liveSwitchEpoch;\n''',
"watchdog fields")

replace_once(
'''    private final Runnable markPlaybackStable = () -> {\n''',
'''    private final Runnable liveStallWatchdog = new Runnable() {\n        @Override public void run() {\n            if (!isLive() || usingVlc || player == null || !firstFrameRendered\n                    || isFinishing() || isDestroyed()) return;\n            if (player.getPlaybackState() != Player.STATE_READY || !player.getPlayWhenReady()) {\n                liveWatchdogPositionMs = Long.MIN_VALUE;\n                liveWatchdogStalls = 0;\n                playbackHandler.postDelayed(this, 3_000L);\n                return;\n            }\n            long position = player.getCurrentPosition();\n            if (position >= 0 && liveWatchdogPositionMs != Long.MIN_VALUE\n                    && Math.abs(position - liveWatchdogPositionMs) < 250L) {\n                liveWatchdogStalls++;\n            } else {\n                liveWatchdogStalls = 0;\n            }\n            liveWatchdogPositionMs = position;\n            if (liveWatchdogStalls >= 2) {\n                PlaybackDiagnostics.marker(PlayerActivity.this, "live-stall", kind, id, extension,\n                        activeTransportName(), "position-not-advancing");\n                liveWatchdogStalls = 0;\n                recoverFromFailure("توقف تقدم البث بعد بدء الصورة");\n                return;\n            }\n            playbackHandler.postDelayed(this, 3_000L);\n        }\n    };\n\n    private final Runnable markPlaybackStable = () -> {\n''',
"stall watchdog")

# On first frame, arm the stall watchdog only for Live.
replace_once(
'''        playbackHandler.removeCallbacks(markPlaybackStable);\n        playbackHandler.postDelayed(markPlaybackStable, isLive() ? LIVE_STABLE_WINDOW_MS : 500L);\n''',
'''        playbackHandler.removeCallbacks(markPlaybackStable);\n        playbackHandler.postDelayed(markPlaybackStable, isLive() ? LIVE_STABLE_WINDOW_MS : 500L);\n        if (isLive()) {\n            liveWatchdogPositionMs = Long.MIN_VALUE;\n            liveWatchdogStalls = 0;\n            playbackHandler.removeCallbacks(liveStallWatchdog);\n            playbackHandler.postDelayed(liveStallWatchdog, 3_000L);\n        }\n''',
"arm watchdog")

# Harden channel switching: cancel every previous resolver/callback and rebuild the
# player instead of reusing a possibly wedged decoder/network stack.
replace_method(
"    private void switchLiveChannel(BlofyModels.Media media)",
'''    private void switchLiveChannel(BlofyModels.Media media) {\n        if (!isLive() || media == null || media.id.equals(id)) return;\n\n        int epoch = ++liveSwitchEpoch;\n        playbackHandler.removeCallbacks(playbackTimeout);\n        playbackHandler.removeCallbacks(markPlaybackStable);\n        playbackHandler.removeCallbacks(hideTitle);\n        playbackHandler.removeCallbacks(liveStallWatchdog);\n        cancelResolve(true);\n\n        // Never leave the previous channel consuming network/decoder resources while\n        // the next link resolves. This intentionally favors stability over warm reuse.\n        releasePlayer();\n        warmLiveSwitchPending = false;\n        PlaybackDiagnostics.marker(this, "live-switch-cancel", kind, id, extension,\n                activeTransportName(), "epoch=" + epoch);\n\n        id = media.id;\n        title = media.name;\n        extension = configuredExtension(PlaybackPolicy.normalizeExtension(media.extension, "ts"));\n        sourceVariant = "canonical";\n        canonicalUrl = "";\n        canonicalExtension = "";\n        canonicalReferer = "";\n        playbackReferer = "";\n        url = null;\n        resumePosition = 0;\n        recoveryStep = preferredRecoveryStep();\n        vlcAttempted = false;\n        liveWatchdogPositionMs = Long.MIN_VALUE;\n        liveWatchdogStalls = 0;\n\n        titleView.setText(title);\n        titleView.setVisibility(View.VISIBLE);\n        progress.setVisibility(View.VISIBLE);\n        errorPanel.setVisibility(View.GONE);\n        resolvePlaybackLink();\n    }''',
"hard-cancel live switch")

# Any release must disarm watchdog so stale callbacks can never surface an error
# after the user has already moved to another channel or screen.
replace_once(
'''        playbackHandler.removeCallbacks(hideTitle);\n        warmLiveSwitchPending = false;\n''',
'''        playbackHandler.removeCallbacks(hideTitle);\n        playbackHandler.removeCallbacks(liveStallWatchdog);\n        liveWatchdogPositionMs = Long.MIN_VALUE;\n        liveWatchdogStalls = 0;\n        warmLiveSwitchPending = false;\n''',
"release watchdog cleanup")

PLAYER.write_text(text, encoding="utf-8")

for token in ["liveStallWatchdog", "live-switch-cancel", "cancelResolve(true)",
              "position-not-advancing"]:
    if token not in text:
        raise SystemExit("v340 live guard invariant missing: " + token)

print("v340 live guard applied: hard channel-switch cancellation + stall watchdog")
