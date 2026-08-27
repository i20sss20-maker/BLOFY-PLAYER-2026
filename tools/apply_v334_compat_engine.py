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

# Per-source bounded fallback state. Never change sources that already render.
patch(PLAYER,
'''    private boolean warmLiveSwitchPending;\n    private boolean vlcSubtitlePreferenceApplied;\n''',
'''    private boolean warmLiveSwitchPending;\n    private boolean vlcSubtitlePreferenceApplied;\n    private boolean alternateLiveAttempted;\n    private boolean containerSniffAttempted;\n''', "compatibility state flags")

# Cronet existed in the app but the player always selected platform HTTP.
patch(PLAYER,
'''    private DataSource.Factory createDataSourceFactory() {\n        // Avoid an identical queued retry while the Cronet provider is still\n        // installing. The compatibility fallback is a different decoder/stack.\n        return PlaybackTransportFactory.create(this, false, network,\n                15_000, 30_000, recoveryStep, playbackReferer);\n    }\n''',
'''    private DataSource.Factory createDataSourceFactory() {\n        boolean preferCronet = recoveryStep == 1 && PlaybackTransportFactory.isCronetReady();\n        return PlaybackTransportFactory.create(this, preferCronet, network,\n                recoveryStep == 0 ? 8_000 : 4_000,\n                recoveryStep == 0 ? 20_000 : 10_000,\n                recoveryStep, playbackReferer);\n    }\n''', "activate bounded Cronet")

patch(PLAYER,
'''    private String activeTransportName() {\n        return usingVlc ? "libvlc" : "default-http";\n    }\n''',
'''    private String activeTransportName() {\n        if (usingVlc) return "libvlc";\n        return recoveryStep == 1 && PlaybackTransportFactory.isCronetReady()\n                ? "cronet" : "default-http";\n    }\n''', "transport diagnostics")

# Optional resolve failures should never show a fatal dialog while a canonical
# source is still available.
patch(PLAYER,
'''                    warmLiveSwitchPending = false;\n                    if ("direct".equals(requestedVariant) && restoreCanonicalSource()) {\n                        if (lifecycleStarted) openVlc(PlaybackPolicy.resolveErrorMessage(error));\n                        return;\n                    }\n                    showResolveError(PlaybackPolicy.resolveErrorMessage(error));\n''',
'''                    warmLiveSwitchPending = false;\n                    if (("direct".equals(requestedVariant) || alternateLiveAttempted)\n                            && restoreCanonicalSource()) {\n                        if (lifecycleStarted) openVlc(PlaybackPolicy.resolveErrorMessage(error));\n                        return;\n                    }\n                    showResolveError(PlaybackPolicy.resolveErrorMessage(error));\n''', "silent optional resolve fallback")

old_recovery = '''        // A fast HTTP/connection error can be specific to the signed relay.\n        // Resolve the direct source once; do not add TS/HLS and Cronet retries\n        // behind it. Slow startup and decoder failures go straight to LibVLC.\n        if (PlaybackPolicy.isNetworkFailure(reason)\n                && !PlaybackPolicy.isStartupTimeout(reason)\n                && "canonical".equals(sourceVariant) && !id.isEmpty()) {\n            releasePlayer();\n            sourceVariant = "direct";\n            recoveryStep = 1;\n            url = null;\n            resolvePlaybackLink();\n            return;\n        }\n\n        if (!vlcAttempted) {\n            recoveryStep = 2;\n            if ("direct".equals(sourceVariant)) restoreCanonicalSource();\n            openVlc(reason);\n            return;\n        }\n'''
new_recovery = '''        // 1) Hard network/DNS failures: try the provider direct source once using\n        // the compatibility network stack. Dead hosts fail fast and continue.\n        if (PlaybackPolicy.isNetworkFailure(reason)\n                && !PlaybackPolicy.isStartupTimeout(reason)\n                && "canonical".equals(sourceVariant) && !id.isEmpty()) {\n            releasePlayer();\n            sourceVariant = "direct";\n            recoveryStep = 1;\n            url = null;\n            resolvePlaybackLink();\n            return;\n        }\n\n        // 2) LIVE: a source is successful only after a real video frame. If it\n        // stays black, try the opposite Xtream transport exactly once.\n        if (isLive() && !alternateLiveAttempted && !id.isEmpty()) {\n            if ("direct".equals(sourceVariant)) restoreCanonicalSource();\n            alternateLiveAttempted = true;\n            releasePlayer();\n            extension = PlaybackPolicy.alternateLiveExtension(extension);\n            sourceVariant = "canonical";\n            recoveryStep = 1;\n            url = null;\n            resolvePlaybackLink();\n            return;\n        }\n\n        // 3) Movies/episodes: retry the same URL without a forced MIME/container\n        // so Media3 can sniff MP4/MKV and odd provider responses.\n        if (!isLive() && !containerSniffAttempted && validUrl(url)) {\n            if ("direct".equals(sourceVariant)) restoreCanonicalSource();\n            containerSniffAttempted = true;\n            sourceVariant = "no-extension";\n            recoveryStep = 1;\n            releaseMedia3Player();\n            firstFrameRendered = false;\n            initializePlayer();\n            return;\n        }\n\n        // 4) Final decoder/protocol fallback. Restore the canonical URL so a bad\n        // optional direct source cannot poison LibVLC.\n        if (!vlcAttempted) {\n            recoveryStep = 2;\n            if (isLive() && alternateLiveAttempted) restoreCanonicalSource();\n            else if ("direct".equals(sourceVariant)) restoreCanonicalSource();\n            openVlc(reason);\n            return;\n        }\n'''
patch(PLAYER, old_recovery, new_recovery, "four-stage recovery state machine")

# Reset per-stream attempts whenever the user changes channel.
patch(PLAYER,
'''    private void switchLiveChannel(BlofyModels.Media media) {\n        if (!isLive() || media == null || media.id.equals(id)) return;\n''',
'''    private void switchLiveChannel(BlofyModels.Media media) {\n        if (!isLive() || media == null || media.id.equals(id)) return;\n        alternateLiveAttempted = false;\n        containerSniffAttempted = false;\n''', "reset on channel switch")

# Manual retry must start a completely fresh compatibility pass.
patch(PLAYER,
'''    private void manualRetry() {\n        recoveryStep = preferredRecoveryStep();\n        vlcAttempted = false;\n        sourceVariant = "canonical";\n''',
'''    private void manualRetry() {\n        recoveryStep = preferredRecoveryStep();\n        vlcAttempted = false;\n        alternateLiveAttempted = false;\n        containerSniffAttempted = false;\n        sourceVariant = "canonical";\n''', "manual retry reset")

# DNS/hostname errors from direct_source or redirects are network failures and
# should immediately advance the state machine instead of waiting a minute.
patch(POLICY,
'''        return value.contains("HTTP") || value.contains("IO_") || value.contains("NETWORK")\n                || value.contains("CONNECTION") || value.contains("TIMEOUT")\n                || value.contains("BAD_HTTP_STATUS");\n''',
'''        return value.contains("HTTP") || value.contains("IO_") || value.contains("NETWORK")\n                || value.contains("CONNECTION") || value.contains("TIMEOUT")\n                || value.contains("BAD_HTTP_STATUS") || value.contains("EAI_NODATA")\n                || value.contains("DNS") || value.contains("UNKNOWNHOST")\n                || value.contains("UNKNOWN_HOST") || value.contains("NO ADDRESS ASSOCIATED")\n                || value.contains("UNABLE TO RESOLVE HOST");\n''', "DNS fast failure")

print("v334 compatibility engine applied: Live first-frame TS/HLS + VOD sniff + Cronet + DNS fast-failure + LibVLC")
