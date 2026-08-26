#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player"


def replace_once(path: Path, old: str, new: str):
    text = path.read_text()
    if old not in text:
        raise SystemExit(f"missing expected block in {path}: {old[:80]!r}")
    text = text.replace(old, new, 1)
    path.write_text(text)


def ensure_once(path: Path, needle: str, insert_after: str, addition: str):
    text = path.read_text()
    if needle in text:
        return
    if insert_after not in text:
        raise SystemExit(f"missing anchor in {path}: {insert_after[:80]!r}")
    path.write_text(text.replace(insert_after, insert_after + addition, 1))


player = JAVA / "PlayerActivity.java"
ensure_once(player, "private String playbackMode", "    private String playbackReferer = \"\";\n",
            "    private String playbackMode = PlaybackProfileManager.MODE_LEGACY;\n")

replace_once(player,
'''        playbackReferer = valueOr(getIntent().getStringExtra(EXTRA_REFERER), "");
        recoveryStep = preferredRecoveryStep();
''',
'''        playbackReferer = valueOr(getIntent().getStringExtra(EXTRA_REFERER), "");
        playbackMode = PlaybackProfileManager.preferredMode(this, kind, extension);
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
        return usingVlc ? PlaybackProfileManager.MODE_VLC : playbackMode;
    }

    private int preferredRecoveryStep() {
        playbackMode = PlaybackProfileManager.preferredMode(this, kind, extension);
        if (PlaybackProfileManager.MODE_COMPAT.equals(playbackMode)) return 2;
        if (PlaybackProfileManager.MODE_CRONET.equals(playbackMode)) return 1;
        if (PlaybackProfileManager.MODE_VLC.equals(playbackMode)) return 3;
        return 0;
    }

    private void rememberSuccessfulTransport() {
        String mode = usingVlc ? PlaybackProfileManager.MODE_VLC : playbackMode;
        PlaybackProfileManager.recordSuccess(this, kind, extension, mode);
        long elapsed = playbackStartedAtMs == 0 ? 0L : SystemClock.elapsedRealtime() - playbackStartedAtMs;
        PlaybackDiagnostics.record(this, kind, extension, mode, "stable", sourceVariant, elapsed);
    }
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
        boolean preferCronet = PlaybackProfileManager.MODE_CRONET.equals(playbackMode);
        int compatibilityProfile = PlaybackProfileManager.MODE_COMPAT.equals(playbackMode) ? 2
                : PlaybackProfileManager.MODE_CRONET.equals(playbackMode) ? 1 : 0;
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
        if (PlaybackProfileManager.MODE_VLC.equals(playbackMode)) {
            openVlc("saved per-type profile");
            return;
        }
        releaseVlcPlayer();
''')

replace_once(player,
'''        recoveryStep = preferredRecoveryStep();
        vlcAttempted = false;
''',
'''        playbackMode = PlaybackProfileManager.preferredMode(this, kind, extension);
        recoveryStep = preferredRecoveryStep();
        vlcAttempted = false;
''')

replace_once(player,
'''        if (usingVlc) {
            showPlaybackFailure(reason);
            return;
        }

        Log.w(TAG, "bounded-recovery reason=" + reason + " ext=" + extension
''',
'''        String failedMode = usingVlc ? PlaybackProfileManager.MODE_VLC : playbackMode;
        PlaybackProfileManager.recordFailure(this, kind, extension, failedMode);
        long elapsed = playbackStartedAtMs == 0 ? 0L : SystemClock.elapsedRealtime() - playbackStartedAtMs;
        PlaybackDiagnostics.record(this, kind, extension, failedMode, "failure", reason, elapsed);
        if (usingVlc) {
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
            playbackMode = PlaybackProfileManager.nextMode(playbackMode);
            if (!PlaybackProfileManager.MODE_VLC.equals(playbackMode)) {
                releaseMedia3Player();
                firstFrameRendered = false;
                initializePlayer();
                return;
            }
            recoveryStep = 2;
            if ("direct".equals(sourceVariant)) restoreCanonicalSource();
            openVlc(reason);
            return;
        }
''')

replace_once(player,
'''    private void manualRetry() {
        recoveryStep = preferredRecoveryStep();
        vlcAttempted = false;
''',
'''    private void manualRetry() {
        playbackMode = PlaybackProfileManager.preferredMode(this, kind, extension);
        recoveryStep = preferredRecoveryStep();
        vlcAttempted = false;
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

print("v332 adaptive playback + diagnostics patch applied")
