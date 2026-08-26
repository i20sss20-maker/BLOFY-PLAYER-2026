from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def write(path, text):
    (ROOT / path).write_text(text, encoding="utf-8")


def replace_once(text, old, new, label):
    if old not in text:
        raise SystemExit(f"v331 patch mismatch: {label}")
    return text.replace(old, new, 1)

# -----------------------------------------------------------------------------
# 1) Analyze provider before catalog import and cache the profile.
# -----------------------------------------------------------------------------
main_path = "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player/MainActivity.java"
main = read(main_path)
old_import = '''            try {
                PackageImporter importer = new PackageImporter(api, database, playlistStore.activeId(),
                        (value, step, note) -> main.post(() -> {
'''
new_import = '''            try {
                main.post(() -> {
                    progress.setProgress(2);
                    percent.setText("2%");
                    title.setText("تحليل الخادم");
                    detail.setText("تحديد أفضل مسار تشغيل قبل تحميل الباقة");
                });
                PlaybackProfileManager.Analysis playbackAnalysis =
                        PlaybackProfileManager.analyzeProvider(this, playlistStore, session);
                database.setMetadata("playback_profile", playbackAnalysis.summary());
                main.post(() -> {
                    progress.setProgress(5);
                    percent.setText("5%");
                    title.setText("تم تحليل الخادم");
                    detail.setText(playbackAnalysis.summary());
                });
                PackageImporter importer = new PackageImporter(api, database, playlistStore.activeId(),
                        (value, step, note) -> main.post(() -> {
'''
main = replace_once(main, old_import, new_import, "provider analysis before import")
write(main_path, main)

# -----------------------------------------------------------------------------
# 2) Adaptive transport selection in PlayerActivity.
# -----------------------------------------------------------------------------
player_path = "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player/PlayerActivity.java"
player = read(player_path)
player = replace_once(player,
'''    private String sourceVariant = "canonical";
    private String canonicalUrl = "";
''',
'''    private String sourceVariant = "canonical";
    private String transportMode = PlaybackProfileManager.MODE_LEGACY;
    private boolean liveAlternateTried;
    private String canonicalUrl = "";
''', "transport state")

player = replace_once(player,
'''        playbackReferer = valueOr(getIntent().getStringExtra(EXTRA_REFERER), "");
        recoveryStep = preferredRecoveryStep();
''',
'''        playbackReferer = valueOr(getIntent().getStringExtra(EXTRA_REFERER), "");
        transportMode = PlaybackProfileManager.preferredMode(this, kind, extension);
        recoveryStep = preferredRecoveryStep();
''', "initial profile selection")

player = replace_once(player,
'''    private String activeTransportName() {
        return usingVlc ? "libvlc" : "default-http";
    }
''',
'''    private String activeTransportName() {
        return usingVlc ? PlaybackProfileManager.MODE_VLC : transportMode;
    }
''', "transport name")

player = replace_once(player,
'''    private void rememberSuccessfulTransport() {
        // Deliberately session-only. Persisting this choice by file extension
        // made one unusual host slow down every other host using that extension.
    }
''',
'''    private void rememberSuccessfulTransport() {
        PlaybackProfileManager.recordSuccess(this, kind, extension, activeTransportName());
    }
''', "remember success")

player = replace_once(player,
'''    private DataSource.Factory createDataSourceFactory() {
        // Avoid an identical queued retry while the Cronet provider is still
        // installing. The compatibility fallback is a different decoder/stack.
        return PlaybackTransportFactory.create(this, false, network,
                15_000, 30_000, recoveryStep, playbackReferer);
    }
''',
'''    private DataSource.Factory createDataSourceFactory() {
        if (PlaybackProfileManager.MODE_LEGACY.equals(transportMode)) {
            return PlaybackTransportFactory.createLegacy7Max(this);
        }
        if (PlaybackProfileManager.MODE_CRONET.equals(transportMode)) {
            return PlaybackTransportFactory.create(this, true, network,
                    3_500, 10_000, 0, playbackReferer);
        }
        int profile = "no-extension".equals(sourceVariant) ? 2 : 1;
        return PlaybackTransportFactory.create(this, false, network,
                3_500, 10_000, profile, playbackReferer);
    }
''', "profile data source")

player = replace_once(player,
'''    private void initializePlayer() {
        if (player != null || !validUrl(url)) return;
        releaseVlcPlayer();
''',
'''    private void initializePlayer() {
        if (player != null || !validUrl(url)) return;
        if (PlaybackProfileManager.MODE_VLC.equals(transportMode) && !vlcAttempted) {
            openVlc("provider-profile");
            return;
        }
        releaseVlcPlayer();
''', "profile direct VLC")

# This block exists after apply_v330_hotfix.py.
old_recovery = '''        // v330 bounded compatibility chain. The first canonical attempt is
        // untouched, so providers that already start instantly stay instant.
        // Only a failed source advances to another transport/profile.
        if ("canonical".equals(sourceVariant) && !id.isEmpty()) {
            releasePlayer();
            sourceVariant = "direct";
            recoveryStep = 1; // ExoPlayer-compatible UA + direct source
            url = null;
            resolvePlaybackLink();
            return;
        }

        if ("direct".equals(sourceVariant) && restoreCanonicalSource()) {
            releasePlayer();
            sourceVariant = "no-extension";
            recoveryStep = 2; // VLC-compatible UA, Media3 container sniffing
            initializePlayer();
            return;
        }

        if (!vlcAttempted) {
            if (!"no-extension".equals(sourceVariant)) restoreCanonicalSource();
            recoveryStep = 2;
            openVlc(reason);
            return;
        }
'''
new_recovery = '''        // v331 provider-aware transport manager. Move across independent
        // transports first; only then change the provider-link variant/container.
        PlaybackProfileManager.recordFailure(this, kind, extension, transportMode);

        if (PlaybackProfileManager.MODE_LEGACY.equals(transportMode)) {
            releasePlayer();
            transportMode = PlaybackProfileManager.MODE_CRONET;
            recoveryStep = 1;
            initializePlayer();
            return;
        }
        if (PlaybackProfileManager.MODE_CRONET.equals(transportMode)) {
            releasePlayer();
            transportMode = PlaybackProfileManager.MODE_COMPAT;
            recoveryStep = 1;
            initializePlayer();
            return;
        }

        // Keep the v330 link compatibility path for providers that need it.
        if (PlaybackProfileManager.MODE_COMPAT.equals(transportMode)
                && "canonical".equals(sourceVariant) && !id.isEmpty()) {
            releasePlayer();
            sourceVariant = "direct";
            recoveryStep = 1;
            url = null;
            resolvePlaybackLink();
            return;
        }
        if (PlaybackProfileManager.MODE_COMPAT.equals(transportMode)
                && "direct".equals(sourceVariant) && restoreCanonicalSource()) {
            releasePlayer();
            transportMode = PlaybackProfileManager.MODE_COMPAT;
            sourceVariant = "no-extension";
            recoveryStep = 2;
            initializePlayer();
            return;
        }

        // Old 7 Max behaviour: live TS/HLS alternate only after transport
        // compatibility is exhausted, never for every successful channel.
        if (isLive() && !liveAlternateTried && !id.isEmpty()) {
            releasePlayer();
            liveAlternateTried = true;
            extension = PlaybackPolicy.alternateLiveExtension(extension);
            transportMode = PlaybackProfileManager.MODE_LEGACY;
            sourceVariant = "canonical";
            canonicalUrl = "";
            canonicalExtension = "";
            canonicalReferer = "";
            playbackReferer = "";
            recoveryStep = 1;
            url = null;
            resolvePlaybackLink();
            return;
        }

        if (!vlcAttempted) {
            if (!"no-extension".equals(sourceVariant)) restoreCanonicalSource();
            transportMode = PlaybackProfileManager.MODE_VLC;
            recoveryStep = 2;
            openVlc(reason);
            return;
        }
'''
player = replace_once(player, old_recovery, new_recovery, "adaptive recovery")

player = replace_once(player,
'''        recoveryStep = preferredRecoveryStep();
        vlcAttempted = false;
        sourceVariant = "canonical";
''',
'''        transportMode = PlaybackProfileManager.preferredMode(this, kind, extension);
        recoveryStep = preferredRecoveryStep();
        liveAlternateTried = false;
        vlcAttempted = false;
        sourceVariant = "canonical";
''', "manual retry profile")

# Live channel switch should use the learned strategy for the new stream.
player = replace_once(player,
'''        resumePosition = 0;
        recoveryStep = preferredRecoveryStep();
        vlcAttempted = false;
''',
'''        resumePosition = 0;
        transportMode = PlaybackProfileManager.preferredMode(this, kind, extension);
        recoveryStep = preferredRecoveryStep();
        liveAlternateTried = false;
        vlcAttempted = false;
''', "live switch profile")

# LibVLC success also teaches the provider profile.
player = replace_once(player,
'''        Log.i(TAG, "compat-first-frame ext=" + extension + " ms=" + firstFrameMs);
    }
''',
'''        Log.i(TAG, "compat-first-frame ext=" + extension + " ms=" + firstFrameMs);
        PlaybackProfileManager.recordSuccess(this, kind, extension, PlaybackProfileManager.MODE_VLC);
    }
''', "vlc learning")

write(player_path, player)

# -----------------------------------------------------------------------------
# 3) Faster bounded retry windows after the pre-analysis decision.
# -----------------------------------------------------------------------------
policy_path = "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player/PlaybackPolicy.java"
policy = read(policy_path)
policy = policy.replace("static final int INITIAL_STARTUP_TIMEOUT_MS = 7_000;",
                        "static final int INITIAL_STARTUP_TIMEOUT_MS = 5_500;", 1)
policy = policy.replace("static final int RETRY_STARTUP_TIMEOUT_MS = 5_000;",
                        "static final int RETRY_STARTUP_TIMEOUT_MS = 3_000;", 1)
write(policy_path, policy)

# -----------------------------------------------------------------------------
# 4) v331 package version.
# -----------------------------------------------------------------------------
gradle_path = "BLOFY-ANDROID-2026/app/build.gradle.kts"
gradle = read(gradle_path)
gradle = gradle.replace("versionCode = 330", "versionCode = 331")
gradle = gradle.replace('versionName = "v330"', 'versionName = "v331"')
write(gradle_path, gradle)
