#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PLAYER = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player/PlayerActivity.java"

text = PLAYER.read_text()


def replace_once(old, new):
    global text
    if old not in text:
        raise SystemExit("missing expected PlayerActivity block: " + old[:120])
    text = text.replace(old, new, 1)

replace_once(
'''    private boolean warmLiveSwitchPending;\n    private boolean vlcSubtitlePreferenceApplied;\n''',
'''    private boolean warmLiveSwitchPending;\n    private boolean vlcSubtitlePreferenceApplied;\n    // v333 compatibility is deliberately bounded and per content type.\n    // Never change the proven v331 path unless that exact source fails.\n    private boolean alternateLiveAttempted;\n    private boolean containerSniffAttempted;\n''')

replace_once(
'''        sourceVariant = "canonical";\n        canonicalUrl = "";\n        canonicalExtension = "";\n        canonicalReferer = "";\n        playbackReferer = "";\n        url = null;\n        resumePosition = 0;\n        recoveryStep = preferredRecoveryStep();\n        vlcAttempted = false;\n''',
'''        sourceVariant = "canonical";\n        canonicalUrl = "";\n        canonicalExtension = "";\n        canonicalReferer = "";\n        playbackReferer = "";\n        url = null;\n        resumePosition = 0;\n        recoveryStep = preferredRecoveryStep();\n        vlcAttempted = false;\n        alternateLiveAttempted = false;\n        containerSniffAttempted = false;\n''')

replace_once(
'''                runOnUiThread(() -> {\n                    if (token != resolveGeneration || isFinishing() || isDestroyed()) return;\n                    warmLiveSwitchPending = false;\n                    if ("direct".equals(requestedVariant) && restoreCanonicalSource()) {\n                        if (lifecycleStarted) openVlc(PlaybackPolicy.resolveErrorMessage(error));\n                        return;\n                    }\n                    showResolveError(PlaybackPolicy.resolveErrorMessage(error));\n                });\n''',
'''                runOnUiThread(() -> {\n                    if (token != resolveGeneration || isFinishing() || isDestroyed()) return;\n                    warmLiveSwitchPending = false;\n                    // If an optional compatibility resolve fails, fall back to the\n                    // exact canonical v331 source before using LibVLC.\n                    if (("direct".equals(requestedVariant) || alternateLiveAttempted)\n                            && restoreCanonicalSource()) {\n                        if (lifecycleStarted) openVlc(PlaybackPolicy.resolveErrorMessage(error));\n                        return;\n                    }\n                    showResolveError(PlaybackPolicy.resolveErrorMessage(error));\n                });\n''')

old_recovery = '''        // A fast HTTP/connection error can be specific to the signed relay.\n        // Resolve the direct source once; do not add TS/HLS and Cronet retries\n        // behind it. Slow startup and decoder failures go straight to LibVLC.\n        if (PlaybackPolicy.isNetworkFailure(reason)\n                && !PlaybackPolicy.isStartupTimeout(reason)\n                && "canonical".equals(sourceVariant) && !id.isEmpty()) {\n            releasePlayer();\n            sourceVariant = "direct";\n            recoveryStep = 1;\n            url = null;\n            resolvePlaybackLink();\n            return;\n        }\n\n        if (!vlcAttempted) {\n            recoveryStep = 2;\n            if ("direct".equals(sourceVariant)) restoreCanonicalSource();\n            openVlc(reason);\n            return;\n        }\n'''

new_recovery = '''        // Preserve the v331 canonical/direct path first. Compatibility is added\n        // only after that exact source fails, and is bounded to one extra attempt.\n        if (PlaybackPolicy.isNetworkFailure(reason)\n                && !PlaybackPolicy.isStartupTimeout(reason)\n                && "canonical".equals(sourceVariant) && !id.isEmpty()) {\n            releasePlayer();\n            sourceVariant = "direct";\n            recoveryStep = 1;\n            url = null;\n            resolvePlaybackLink();\n            return;\n        }\n\n        // Live providers differ on whether the playable endpoint is MPEG-TS or HLS.\n        // Try the alternate extension once, only after the proven v331 path fails.\n        if (isLive() && !alternateLiveAttempted && !id.isEmpty()) {\n            if ("direct".equals(sourceVariant)) restoreCanonicalSource();\n            alternateLiveAttempted = true;\n            releasePlayer();\n            extension = PlaybackPolicy.alternateLiveExtension(extension);\n            sourceVariant = "canonical";\n            recoveryStep = 1;\n            url = null;\n            resolvePlaybackLink();\n            return;\n        }\n\n        // VOD/episodes often report mkv/mp4 incorrectly. Re-open the SAME resolved\n        // source once without forcing a MIME type so Media3 can sniff the container.\n        if (!isLive() && !containerSniffAttempted && validUrl(url)) {\n            if ("direct".equals(sourceVariant)) restoreCanonicalSource();\n            containerSniffAttempted = true;\n            sourceVariant = "no-extension";\n            recoveryStep = 1;\n            releaseMedia3Player();\n            firstFrameRendered = false;\n            initializePlayer();\n            return;\n        }\n\n        if (!vlcAttempted) {\n            recoveryStep = 2;\n            if (isLive() && alternateLiveAttempted) restoreCanonicalSource();\n            else if ("direct".equals(sourceVariant)) restoreCanonicalSource();\n            openVlc(reason);\n            return;\n        }\n'''
replace_once(old_recovery, new_recovery)

replace_once(
'''        vlcAttempted = false;\n        sourceVariant = "canonical";\n        canonicalUrl = "";\n''',
'''        vlcAttempted = false;\n        alternateLiveAttempted = false;\n        containerSniffAttempted = false;\n        sourceVariant = "canonical";\n        canonicalUrl = "";\n''')

PLAYER.write_text(text)
print("v333 bounded provider compatibility applied; UI untouched")
