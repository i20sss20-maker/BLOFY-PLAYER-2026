#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player"
PLAYER = JAVA / "PlayerActivity.java"
POLICY = JAVA / "PlaybackPolicy.java"


def patch(path, old, new, label):
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"v334 compatibility patch mismatch: {label}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def optional_patch(path, old, new, label):
    text = path.read_text(encoding="utf-8")
    if old not in text:
        print(f"v334 optional patch skipped: {label}")
        return
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def replace_method(path, signature, replacement, label):
    text = path.read_text(encoding="utf-8")
    start = text.find(signature)
    if start < 0:
        raise SystemExit(f"v334 compatibility method missing: {label}")
    brace = text.find("{", start)
    if brace < 0:
        raise SystemExit(f"v334 compatibility method brace missing: {label}")
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
        raise SystemExit(f"v334 compatibility method end missing: {label}")
    path.write_text(text[:start] + replacement + text[end:], encoding="utf-8")

patch(PLAYER,
'''    private boolean warmLiveSwitchPending;\n    private boolean vlcSubtitlePreferenceApplied;\n''',
'''    private boolean warmLiveSwitchPending;\n    private boolean vlcSubtitlePreferenceApplied;\n    private boolean alternateLiveAttempted;\n    private boolean containerSniffAttempted;\n''', "compatibility state flags")

replace_method(PLAYER,
"    private String configuredExtension(String candidate)",
'''    private String configuredExtension(String candidate) {\n        if (!isLiveKind(kind)) return candidate;\n        String mode = playerSetting(SettingsActivity.KEY_STREAM, "auto");\n        if ("ts".equals(mode)) return "ts";\n        if ("hls".equals(mode)) return "m3u8";\n        String itemRoute = PlaybackRouteMemory.preferredLiveExtension(this, id, "");\n        if (!itemRoute.isEmpty()) return itemRoute;\n        return PlaybackProfileManager.preferredLiveExtension(this, candidate);\n    }''',
"per-stream live extension memory")

replace_method(PLAYER,
"    private int preferredRecoveryStep()",
'''    private int preferredRecoveryStep() {\n        return PlaybackRouteMemory.preferredRecoveryStep(this, kind, id);\n    }''',
"per-stream transport memory")

replace_method(PLAYER,
"    private void rememberSuccessfulTransport()",
'''    private void rememberSuccessfulTransport() {\n        PlaybackRouteMemory.recordSuccess(this, kind, id, extension, activeTransportName());\n        PlaybackProfileManager.recordSuccess(this, kind, extension, activeTransportName());\n    }''',
"persist successful route")

replace_method(PLAYER,
"    private DataSource.Factory createDataSourceFactory()",
'''    private DataSource.Factory createDataSourceFactory() {\n        boolean preferCronet = recoveryStep == 1 && PlaybackTransportFactory.isCronetReady();\n        return PlaybackTransportFactory.create(this, preferCronet, network,\n                recoveryStep == 0 ? 8_000 : 4_000,\n                recoveryStep == 0 ? 20_000 : 10_000,\n                recoveryStep, playbackReferer);\n    }''',
"activate bounded Cronet")

replace_method(PLAYER,
"    private String activeTransportName()",
'''    private String activeTransportName() {\n        if (usingVlc) return "libvlc";\n        return recoveryStep == 1 && PlaybackTransportFactory.isCronetReady()\n                ? "cronet" : "default-http";\n    }''',
"transport diagnostics")

optional_patch(PLAYER,
'''                    warmLiveSwitchPending = false;\n                    if ("direct".equals(requestedVariant) && restoreCanonicalSource()) {\n                        if (lifecycleStarted) openVlc(PlaybackPolicy.resolveErrorMessage(error));\n                        return;\n                    }\n                    showResolveError(PlaybackPolicy.resolveErrorMessage(error));\n''',
'''                    warmLiveSwitchPending = false;\n                    if (("direct".equals(requestedVariant) || alternateLiveAttempted)\n                            && restoreCanonicalSource()) {\n                        if (lifecycleStarted) openVlc(PlaybackPolicy.resolveErrorMessage(error));\n                        return;\n                    }\n                    showResolveError(PlaybackPolicy.resolveErrorMessage(error));\n''', "silent optional resolve fallback")

replace_method(PLAYER,
"    private void recoverFromFailure(String reason)",
'''    private void recoverFromFailure(String reason) {\n        if (isFinishing() || isDestroyed()) return;\n        playbackHandler.removeCallbacks(playbackTimeout);\n        playbackHandler.removeCallbacks(markPlaybackStable);\n\n        if (usingVlc) {\n            showPlaybackFailure(reason);\n            return;\n        }\n\n        Log.w(TAG, "adaptive-recovery reason=" + reason + " ext=" + extension\n                + " variant=" + sourceVariant + " uhd=" + isUltraHd()\n                + " transport=" + activeTransportName());\n\n        // A hard network/HTTP/DNS failure gets one direct-source attempt first.\n        if (PlaybackPolicy.isNetworkFailure(reason)\n                && !PlaybackPolicy.isStartupTimeout(reason)\n                && "canonical".equals(sourceVariant) && !id.isEmpty()) {\n            releasePlayer();\n            sourceVariant = "direct";\n            recoveryStep = 1;\n            url = null;\n            resolvePlaybackLink();\n            return;\n        }\n\n        // LIVE is successful only after a real first frame. If canonical/direct\n        // stalls or renders black, try the alternate Xtream TS/HLS route once.\n        if (isLive() && !alternateLiveAttempted && !id.isEmpty()) {\n            if ("direct".equals(sourceVariant)) restoreCanonicalSource();\n            alternateLiveAttempted = true;\n            releasePlayer();\n            extension = PlaybackPolicy.alternateLiveExtension(extension);\n            sourceVariant = "canonical";\n            recoveryStep = 1;\n            url = null;\n            resolvePlaybackLink();\n            return;\n        }\n\n        // Movies/episodes may advertise mp4/mkv incorrectly. Reopen the same\n        // source without MIME forcing so Media3 can sniff the real container.\n        if (!isLive() && !containerSniffAttempted && validUrl(url)) {\n            if ("direct".equals(sourceVariant)) restoreCanonicalSource();\n            containerSniffAttempted = true;\n            sourceVariant = "no-extension";\n            recoveryStep = 1;\n            releaseMedia3Player();\n            firstFrameRendered = false;\n            initializePlayer();\n            return;\n        }\n\n        // Final broad codec/protocol fallback.\n        if (!vlcAttempted) {\n            recoveryStep = 2;\n            if (isLive() && alternateLiveAttempted) restoreCanonicalSource();\n            else if ("direct".equals(sourceVariant)) restoreCanonicalSource();\n            openVlc(reason);\n            return;\n        }\n\n        showPlaybackFailure(reason);\n    }''',
"four-stage recovery state machine")

patch(PLAYER,
'''    private void switchLiveChannel(BlofyModels.Media media) {\n        if (!isLive() || media == null || media.id.equals(id)) return;\n''',
'''    private void switchLiveChannel(BlofyModels.Media media) {\n        if (!isLive() || media == null || media.id.equals(id)) return;\n        alternateLiveAttempted = false;\n        containerSniffAttempted = false;\n''', "reset on channel switch")

optional_patch(PLAYER,
'''    private void manualRetry() {\n        recoveryStep = preferredRecoveryStep();\n        vlcAttempted = false;\n        sourceVariant = "canonical";\n''',
'''    private void manualRetry() {\n        recoveryStep = preferredRecoveryStep();\n        vlcAttempted = false;\n        alternateLiveAttempted = false;\n        containerSniffAttempted = false;\n        sourceVariant = "canonical";\n''', "manual retry reset")

patch(POLICY,
'''        return value.contains("HTTP") || value.contains("IO_") || value.contains("NETWORK")\n                || value.contains("CONNECTION") || value.contains("TIMEOUT")\n                || value.contains("BAD_HTTP_STATUS");\n''',
'''        return value.contains("HTTP") || value.contains("IO_") || value.contains("NETWORK")\n                || value.contains("CONNECTION") || value.contains("TIMEOUT")\n                || value.contains("BAD_HTTP_STATUS") || value.contains("EAI_NODATA")\n                || value.contains("DNS") || value.contains("UNKNOWNHOST")\n                || value.contains("UNKNOWN_HOST") || value.contains("NO ADDRESS ASSOCIATED")\n                || value.contains("UNABLE TO RESOLVE HOST");\n''', "DNS fast failure")

print("v334 compatibility engine applied: per-stream memory + first-frame Live + TS/HLS + VOD sniff + Cronet + DNS fast-failure + LibVLC")
