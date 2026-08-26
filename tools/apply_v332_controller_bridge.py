#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PLAYER = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player/PlayerActivity.java"


def replace_once(text: str, old: str, new: str) -> str:
    if old not in text:
        raise SystemExit(f"missing anchor: {old[:120]!r}")
    return text.replace(old, new, 1)

text = PLAYER.read_text()

# Bridge v332 controller without disturbing the proven v331 transport implementation.
text = text.replace('private String playbackMode = PlaybackProfileManager.MODE_LEGACY;',
                    'private String playbackMode = AdaptivePlaybackController.LEGACY;')

text = text.replace('playbackMode = PlaybackProfileManager.preferredMode(this, kind, extension);',
                    'playbackMode = adaptiveDecision().mode;')

text = text.replace('return usingVlc ? PlaybackProfileManager.MODE_VLC : playbackMode;',
                    'return usingVlc ? AdaptivePlaybackController.VLC : playbackMode;')

text = text.replace('if (PlaybackProfileManager.MODE_COMPAT.equals(playbackMode)) return 2;',
                    'if (AdaptivePlaybackController.COMPAT.equals(playbackMode)) return 2;')
text = text.replace('if (PlaybackProfileManager.MODE_CRONET.equals(playbackMode)) return 1;',
                    'if (AdaptivePlaybackController.CRONET.equals(playbackMode)) return 1;')
text = text.replace('if (PlaybackProfileManager.MODE_VLC.equals(playbackMode)) return 3;',
                    'if (AdaptivePlaybackController.VLC.equals(playbackMode)) return 3;')

text = text.replace('String mode = usingVlc ? PlaybackProfileManager.MODE_VLC : playbackMode;\n        PlaybackProfileManager.recordSuccess(this, kind, extension, mode);',
'''String mode = usingVlc ? AdaptivePlaybackController.VLC : playbackMode;
        AdaptivePlaybackController.recordSuccess(this, adaptivePlaylistId(), kind, extension, mode);''')

text = text.replace('boolean preferCronet = PlaybackProfileManager.MODE_CRONET.equals(playbackMode);',
                    'boolean preferCronet = AdaptivePlaybackController.CRONET.equals(playbackMode);')
text = text.replace('int compatibilityProfile = PlaybackProfileManager.MODE_COMPAT.equals(playbackMode) ? 2\n                : PlaybackProfileManager.MODE_CRONET.equals(playbackMode) ? 1 : 0;',
'''int compatibilityProfile = AdaptivePlaybackController.COMPAT.equals(playbackMode) ? 2
                : AdaptivePlaybackController.CRONET.equals(playbackMode) ? 1 : 0;''')

text = text.replace('if (PlaybackProfileManager.MODE_VLC.equals(playbackMode)) {',
                    'if (AdaptivePlaybackController.VLC.equals(playbackMode)) {')

text = text.replace('String failedMode = usingVlc ? PlaybackProfileManager.MODE_VLC : playbackMode;\n        PlaybackProfileManager.recordFailure(this, kind, extension, failedMode);',
'''String failedMode = usingVlc ? AdaptivePlaybackController.VLC : playbackMode;
        String nextAdaptiveMode = AdaptivePlaybackController.recordFailureAndNext(
                this, adaptivePlaylistId(), kind, extension, failedMode);''')

text = text.replace('playbackMode = PlaybackProfileManager.nextMode(playbackMode);\n            if (!PlaybackProfileManager.MODE_VLC.equals(playbackMode)) {',
'''playbackMode = nextAdaptiveMode;
            if (!AdaptivePlaybackController.VLC.equals(playbackMode)) {''')

anchor = '''    private String activeTransportName() {
        return usingVlc ? AdaptivePlaybackController.VLC : playbackMode;
    }
'''
addition = '''
    private String adaptivePlaylistId() {
        try {
            String active = new PlaylistStore(this).activeId();
            return active == null || active.isEmpty() ? "current-session" : active;
        } catch (Exception ignored) {
            return "current-session";
        }
    }

    private AdaptivePlaybackController.Decision adaptiveDecision() {
        return AdaptivePlaybackController.decide(this, adaptivePlaylistId(), kind, extension);
    }

    private void runCapabilityProbeAsync() {
        if (!validUrl(url)) return;
        final String probeUrl = url;
        final String probeKind = kind;
        final String probeExtension = extension;
        final String probeReferer = playbackReferer;
        final String playlistId = adaptivePlaylistId();
        network.execute(() -> {
            try {
                PlaybackCapabilityProbe.Result result = PlaybackCapabilityProbe.probe(
                        probeUrl,
                        PlaybackTransportFactory.userAgent(0),
                        probeReferer,
                        !isLiveKind(probeKind));
                if (result.success) {
                    AdaptivePlaybackController.learnFromProbe(
                            this, playlistId, probeKind, probeExtension, result,
                            PlaybackTransportFactory.isCronetReady());
                }
                PlaybackDiagnostics.record(this, probeKind, probeExtension, "probe",
                        result.success ? "probe-ok" : "probe-fail",
                        "status=" + result.statusCode
                                + " range=" + result.rangeSupported
                                + " redirect=" + result.redirected
                                + " type=" + result.contentType
                                + " family=" + PlaybackCapabilityProbe.inferFamily(result, probeExtension),
                        result.elapsedMs);
            } catch (Throwable ignored) {
                // Probe is advisory only; playback must never wait for it.
            }
        });
    }
'''
if 'private AdaptivePlaybackController.Decision adaptiveDecision()' not in text:
    text = replace_once(text, anchor, anchor + addition)

# Probe only after a real media URL has been resolved. It is asynchronous and never blocks startup.
old = '''    private void prepareResolvedUrl() {
        if (!validUrl(url)) return;
        resumePosition = isLive() ? 0 : PlaybackProgress.get(this, kind, id);
    }
'''
new = '''    private void prepareResolvedUrl() {
        if (!validUrl(url)) return;
        resumePosition = isLive() ? 0 : PlaybackProgress.get(this, kind, id);
        runCapabilityProbeAsync();
    }
'''
if old in text:
    text = text.replace(old, new, 1)

# Make diagnostics show the adaptive decision chosen before the player opens.
old_log = '''        Log.i(TAG, "open kind=" + kind + " ext=" + extension + " step=" + recoveryStep
                + " uhd=" + isUltraHd() + " transport=" + activeTransportName()
'''
new_log = '''        PlaybackDiagnostics.record(this, kind, extension, activeTransportName(),
                "open", "learned=" + adaptiveDecision().learned + " family=" + adaptiveDecision().family, 0L);
        Log.i(TAG, "open kind=" + kind + " ext=" + extension + " step=" + recoveryStep
                + " uhd=" + isUltraHd() + " transport=" + activeTransportName()
'''
if old_log in text:
    text = text.replace(old_log, new_log, 1)

PLAYER.write_text(text)
print("v332 adaptive controller bridge applied")
