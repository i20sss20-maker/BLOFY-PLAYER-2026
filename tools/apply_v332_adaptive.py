#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player"


def replace_once(path: Path, old: str, new: str):
    text = path.read_text()
    if old not in text:
        raise SystemExit(f"missing expected block in {path}: {old[:100]!r}")
    path.write_text(text.replace(old, new, 1))


def ensure_once(path: Path, needle: str, anchor: str, addition: str):
    text = path.read_text()
    if needle in text:
        return
    if anchor not in text:
        raise SystemExit(f"missing anchor in {path}: {anchor[:100]!r}")
    path.write_text(text.replace(anchor, anchor + addition, 1))


player = JAVA / "PlayerActivity.java"
ensure_once(player, "private String playbackMode", "    private String playbackReferer = \"\";\n",
            "    private String playbackMode = AdaptivePlaybackController.LEGACY;\n"
            "    private String playbackPlaylistId = \"current-session\";\n")

replace_once(player,
'''        playbackReferer = valueOr(getIntent().getStringExtra(EXTRA_REFERER), "");
        recoveryStep = preferredRecoveryStep();
''',
'''        playbackReferer = valueOr(getIntent().getStringExtra(EXTRA_REFERER), "");
        playbackPlaylistId = valueOr(new PlaylistStore(this).activeId(), "current-session");
        AdaptivePlaybackController.Decision decision = AdaptivePlaybackController.decide(
                this, playbackPlaylistId, kind, extension);
        playbackMode = decision.mode;
        recoveryStep = preferredRecoveryStep();
''')

replace_once(player,
'''    private String activeTransportName() {
        return usingVlc ? "libvlc" : "default-http";
    }

    private int preferredRecoveryStep() {
        // Transport success must not leak from one playlist/provider to another.
        // Start every source with the platform HTTP stack and use Cronet only as
        // a bounded compatibility fallback for the current source.
        return 0;
    }

    private void rememberSuccessfulTransport() {
        // Deliberately session-only. Persisting this choice by file extension
        // made one unusual host slow down every other host using that extension.
    }
''',
'''    private String activeTransportName() {
        return usingVlc ? AdaptivePlaybackController.VLC : playbackMode;
    }

    private int preferredRecoveryStep() {
        if (AdaptivePlaybackController.COMPAT.equals(playbackMode)) return 2;
        if (AdaptivePlaybackController.CRONET.equals(playbackMode)) return 1;
        if (AdaptivePlaybackController.VLC.equals(playbackMode)) return 3;
        return 0;
    }

    private void refreshAdaptiveDecision() {
        AdaptivePlaybackController.Decision decision = AdaptivePlaybackController.decide(
                this, playbackPlaylistId, kind, extension);
        playbackMode = decision.mode;
        recoveryStep = preferredRecoveryStep();
    }

    private void rememberSuccessfulTransport() {
        String mode = usingVlc ? AdaptivePlaybackController.VLC : playbackMode;
        AdaptivePlaybackController.recordSuccess(this, playbackPlaylistId, kind, extension, mode);
        long elapsed = playbackStartedAtMs == 0 ? 0L : SystemClock.elapsedRealtime() - playbackStartedAtMs;
        PlaybackDiagnostics.record(this, kind, extension, mode, "stable", sourceVariant, elapsed);
    }
''')

# Deep probe happens on the existing resolver worker, never on the UI thread.
replace_once(player,
'''                String resolvedReferer = data.optString("referer", requestedReferer);
                String finalResolved = resolved;
                runOnUiThread(() -> {
''',
'''                String resolvedReferer = data.optString("referer", requestedReferer);
                String finalResolved = resolved.startsWith("http") ? resolved
                        : BuildConfig.BLOFY_BASE_URL.replaceAll("/+$", "") + resolved;
                AdaptivePlaybackController.Decision existingDecision = AdaptivePlaybackController.decide(
                        this, playbackPlaylistId, requestedKind, resolvedExtension);
                if (!existingDecision.learned) {
                    PlaybackCapabilityProbe.Result probe = PlaybackCapabilityProbe.probe(
                            finalResolved,
                            PlaybackTransportFactory.userAgent(0),
                            resolvedReferer,
                            !isLiveKind(requestedKind));
                    if (!probe.success) {
                        probe = PlaybackCapabilityProbe.probe(finalResolved,
                                PlaybackTransportFactory.userAgent(1), resolvedReferer,
                                !isLiveKind(requestedKind));
                    }
                    AdaptivePlaybackController.learnFromProbe(this, playbackPlaylistId,
                            requestedKind, resolvedExtension, probe, PlaybackTransportFactory.isCronetReady());
                    PlaybackDiagnostics.record(this, requestedKind, resolvedExtension,
                            "probe", probe.success ? "probe-ok" : "probe-fail",
                            "http=" + probe.statusCode + " range=" + probe.rangeSupported
                                    + " redirect=" + probe.redirected + " type=" + probe.contentType
                                    + " reason=" + probe.reason,
                            probe.elapsedMs);
                }
                runOnUiThread(() -> {
''')

replace_once(player,
'''                    url = finalResolved.startsWith("http") ? finalResolved
                            : BuildConfig.BLOFY_BASE_URL.replaceAll("/+$", "") + finalResolved;
                    extension = resolvedExtension;
                    playbackReferer = resolvedReferer;
''',
'''                    url = finalResolved;
                    extension = resolvedExtension;
                    playbackReferer = resolvedReferer;
                    refreshAdaptiveDecision();
''')

replace_once(player,
'''    private DataSource.Factory createDataSourceFactory() {
        // Avoid an identical queued retry while the Cronet provider is still
        // installing. The compatibility fallback is a different decoder/stack.
        return PlaybackTransportFactory.create(this, false, network,
                15_000, 30_000, recoveryStep, playbackReferer);
    }
''',
'''    private DataSource.Factory createDataSourceFactory() {
        if (AdaptivePlaybackController.LEGACY.equals(playbackMode)) {
            return PlaybackTransportFactory.createLegacy7Max(this);
        }
        boolean preferCronet = AdaptivePlaybackController.CRONET.equals(playbackMode);
        int compatibilityProfile = AdaptivePlaybackController.COMPAT.equals(playbackMode) ? 2
                : AdaptivePlaybackController.CRONET.equals(playbackMode) ? 1 : 0;
        return PlaybackTransportFactory.create(this, preferCronet, network,
                15_000, 30_000, compatibilityProfile, playbackReferer);
    }
''')

replace_once(player,
'''    private void initializePlayer() {
        if (player != null || !validUrl(url)) return;
        releaseVlcPlayer();
''',
'''    private void initializePlayer() {
        if (player != null || !validUrl(url)) return;
        if (AdaptivePlaybackController.VLC.equals(playbackMode)) {
            openVlc("adaptive per-type profile");
            return;
        }
        releaseVlcPlayer();
''')

# After any source restoration/retry, use only this type's learned profile.
replace_once(player,
'''    private void manualRetry() {
        recoveryStep = preferredRecoveryStep();
        vlcAttempted = false;
''',
'''    private void manualRetry() {
        refreshAdaptiveDecision();
        vlcAttempted = false;
''')

replace_once(player,
'''        if (usingVlc) {
            showPlaybackFailure(reason);
            return;
        }

        Log.w(TAG, "bounded-recovery reason=" + reason + " ext=" + extension
''',
'''        String failedMode = usingVlc ? AdaptivePlaybackController.VLC : playbackMode;
        String nextAdaptiveMode = AdaptivePlaybackController.recordFailureAndNext(
                this, playbackPlaylistId, kind, extension, failedMode);
        long elapsed = playbackStartedAtMs == 0 ? 0L : SystemClock.elapsedRealtime() - playbackStartedAtMs;
        PlaybackDiagnostics.record(this, kind, extension, failedMode, "failure", reason, elapsed);
        if (usingVlc) {
            // One bounded non-VLC attempt is allowed for difficult VOD before surfacing the error.
            if (!isLive() && AdaptivePlaybackController.PLATFORM.equals(nextAdaptiveMode)) {
                usingVlc = false;
                playbackMode = nextAdaptiveMode;
                releaseVlcPlayer();
                initializePlayer();
                return;
            }
            showPlaybackFailure(reason);
            return;
        }

        Log.w(TAG, "bounded-recovery reason=" + reason + " ext=" + extension
''')

replace_once(player,
'''        if (!vlcAttempted) {
            recoveryStep = 2;
            if ("direct".equals(sourceVariant)) restoreCanonicalSource();
            openVlc(reason);
            return;
        }
''',
'''        if (!vlcAttempted) {
            playbackMode = nextAdaptiveMode;
            recoveryStep = preferredRecoveryStep();
            if ("direct".equals(sourceVariant)) restoreCanonicalSource();
            releaseMedia3Player();
            firstFrameRendered = false;
            if (AdaptivePlaybackController.VLC.equals(playbackMode)) openVlc(reason);
            else initializePlayer();
            return;
        }
''')

replace_once(player,
'''        Log.i(TAG, "first-frame kind=" + kind + " ext=" + extension + " ms=" + firstFrameMs
                + " transport=" + activeTransportName());
''',
'''        Log.i(TAG, "first-frame kind=" + kind + " ext=" + extension + " ms=" + firstFrameMs
                + " transport=" + activeTransportName());
        PlaybackDiagnostics.record(this, kind, extension, activeTransportName(),
                "first-frame", sourceVariant, firstFrameMs);
''')

replace_once(player,
'''        Log.w(TAG, "player-error code=" + error.errorCode + " name=" + error.getErrorCodeName()
                + " ext=" + extension + " transport=" + activeTransportName(), error);
''',
'''        Log.w(TAG, "player-error code=" + error.errorCode + " name=" + error.getErrorCodeName()
                + " ext=" + extension + " transport=" + activeTransportName(), error);
        PlaybackDiagnostics.record(this, kind, extension, activeTransportName(),
                "media3-error", playbackErrorReason(error),
                playbackStartedAtMs == 0 ? 0L : SystemClock.elapsedRealtime() - playbackStartedAtMs);
''')

settings = JAVA / "SettingsActivity.java"
if "تشخيص التشغيل المتقدم" not in settings.read_text():
    replace_once(settings,
'''        addGridSetting(grid, gridAction("✓  فحص التشغيل", "Media3 + VLC + FFmpeg", () ->
                ToastBridge.show(this, "المحركات جاهزة • Media3 + VLC + FFmpeg")));
''',
'''        addGridSetting(grid, gridAction("✓  فحص التشغيل", "Media3 + VLC + FFmpeg", () ->
                ToastBridge.show(this, "المحركات جاهزة • Media3 + VLC + FFmpeg")));
        addGridSetting(grid, gridAction("⌁  تشخيص التشغيل المتقدم", "Live / Movies / Series", () ->
                startActivity(new Intent(this, PlaybackDiagnosticsActivity.class))));
''')
text = settings.read_text().replace("BLOFY PLAYER v328", "BLOFY PLAYER v332")
settings.write_text(text)

manifest = ROOT / "BLOFY-ANDROID-2026/app/src/main/AndroidManifest.xml"
if 'android:name=".PlaybackDiagnosticsActivity"' not in manifest.read_text():
    replace_once(manifest,
'''        <activity
            android:name=".SettingsActivity"
            android:configChanges="keyboard|keyboardHidden|orientation|screenLayout|screenSize|smallestScreenSize|uiMode"
            android:exported="false"
            android:screenOrientation="sensorLandscape" />
''',
'''        <activity
            android:name=".SettingsActivity"
            android:configChanges="keyboard|keyboardHidden|orientation|screenLayout|screenSize|smallestScreenSize|uiMode"
            android:exported="false"
            android:screenOrientation="sensorLandscape" />

        <activity
            android:name=".PlaybackDiagnosticsActivity"
            android:configChanges="keyboard|keyboardHidden|orientation|screenLayout|screenSize|smallestScreenSize|uiMode"
            android:exported="false"
            android:screenOrientation="sensorLandscape" />
''')

gradle = ROOT / "BLOFY-ANDROID-2026/app/build.gradle.kts"
g = gradle.read_text()
g = re.sub(r'versionCode\s*=\s*\d+', 'versionCode = 332', g, count=1)
g = re.sub(r'versionName\s*=\s*"[^"]+"', 'versionName = "3.3.2"', g, count=1)
gradle.write_text(g)

print("v332 adaptive per-type playback + diagnostics patch applied")
