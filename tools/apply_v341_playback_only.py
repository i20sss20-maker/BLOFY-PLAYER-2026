#!/usr/bin/env python3
"""Apply the v341 playback-only layer on top of the generated v340 R6 tree."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player"
TESTS = ROOT / "BLOFY-ANDROID-2026/app/src/test/java/tv/blofy/player"


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if text.count(old) != 1:
        raise SystemExit(f"v341 patch mismatch ({label}): expected one match, got {text.count(old)}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def write_new(path: Path, content: str) -> None:
    if path.exists():
        raise SystemExit(f"v341 patch refuses to overwrite existing file: {path}")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


build = ROOT / "BLOFY-ANDROID-2026/app/build.gradle.kts"
replace_once(build,
'''        versionCode = 1000333
        versionName = "v333-golden-r2"
''',
'''        versionCode = 1000341
        versionName = "v341-playback-only"
''', "upgrade-compatible version")

api = JAVA / "BlofyApi.java"
replace_once(api,
'''    private static final int PLAYBACK_LINK_TIMEOUT_MS = 4_000;
''',
'''    private static final int PLAYBACK_LINK_TIMEOUT_MS = 7_500;
''', "cold native-link budget")

policy = JAVA / "PlaybackPolicy.java"
replace_once(policy,
'''    static final int PREVIEW_STARTUP_TIMEOUT_MS = 5_000;

    private PlaybackPolicy() {}
''',
'''    static final int PREVIEW_STARTUP_TIMEOUT_MS = 5_000;

    enum RecoveryClass {
        STARTUP_TIMEOUT,
        DNS,
        HTTP_AUTH,
        HTTP_FORMAT,
        HTTP,
        FORMAT,
        DECODER,
        NETWORK,
        OTHER
    }

    private PlaybackPolicy() {}
''', "recovery classes")
replace_once(policy,
'''    static boolean isStartupTimeout(String reason) {
        String value = value(reason);
        return value.contains("مهلة بدء") || value.contains("لم تظهر صورة");
    }

    static boolean isNetworkFailure(String reason) {
        String value = value(reason).toUpperCase(Locale.US);
        return value.contains("HTTP") || value.contains("IO_") || value.contains("NETWORK")
                || value.contains("CONNECTION") || value.contains("TIMEOUT")
                || value.contains("BAD_HTTP_STATUS") || value.contains("EAI_NODATA")
                || value.contains("DNS") || value.contains("UNKNOWNHOST")
                || value.contains("NO ADDRESS ASSOCIATED") || value.contains("UNABLE TO RESOLVE HOST");
    }

    static boolean isDecoderFailure(String reason) {
        String value = value(reason).toUpperCase(Locale.US);
        return value.contains("DECOD") || value.contains("CODEC")
                || value.contains("FORMAT_UNSUPPORTED") || value.contains("PARSING");
    }
''',
'''    static boolean isStartupTimeout(String reason) {
        return classifyRecovery(reason) == RecoveryClass.STARTUP_TIMEOUT;
    }

    static boolean isNetworkFailure(String reason) {
        RecoveryClass type = classifyRecovery(reason);
        return type == RecoveryClass.DNS || type == RecoveryClass.NETWORK
                || type == RecoveryClass.HTTP || type == RecoveryClass.HTTP_AUTH
                || type == RecoveryClass.HTTP_FORMAT;
    }

    static boolean isDecoderFailure(String reason) {
        return classifyRecovery(reason) == RecoveryClass.DECODER;
    }

    static boolean isFormatFailure(String reason) {
        RecoveryClass type = classifyRecovery(reason);
        return type == RecoveryClass.FORMAT || type == RecoveryClass.HTTP_FORMAT;
    }

    static RecoveryClass classifyRecovery(String reason) {
        String raw = value(reason);
        String upper = raw.toUpperCase(Locale.US).replace('-', '_');
        if (raw.contains("مهلة بدء") || raw.contains("لم تظهر صورة")
                || upper.contains("FIRST_FRAME_TIMEOUT") || upper.contains("STARTUP_TIMEOUT")) {
            return RecoveryClass.STARTUP_TIMEOUT;
        }
        if (upper.contains("EAI_NODATA") || upper.contains("DNS")
                || upper.contains("UNKNOWNHOST") || upper.contains("NO ADDRESS ASSOCIATED")
                || upper.contains("UNABLE TO RESOLVE HOST")) {
            return RecoveryClass.DNS;
        }
        if (upper.contains("HTTP 401") || upper.contains("HTTP 403")) {
            return RecoveryClass.HTTP_AUTH;
        }
        if (upper.contains("HTTP 400") || upper.contains("HTTP 404")
                || upper.contains("HTTP 410") || upper.contains("HTTP 415")) {
            return RecoveryClass.HTTP_FORMAT;
        }
        if (upper.contains("HTTP") || upper.contains("BAD_HTTP_STATUS")) {
            return RecoveryClass.HTTP;
        }
        if (upper.contains("PARSING") || upper.contains("UNRECOGNIZED")
                || upper.contains("CONTAINER") || upper.contains("MANIFEST")
                || upper.contains("CONTENT_TYPE") || upper.contains("M3U8")
                || upper.contains("MPEGTS") || upper.contains("MPEG_TS")
                || upper.contains("SOURCE ERROR")) {
            return RecoveryClass.FORMAT;
        }
        if (upper.contains("DECOD") || upper.contains("CODEC")
                || upper.contains("FORMAT_UNSUPPORTED")) {
            return RecoveryClass.DECODER;
        }
        if (upper.contains("IO_") || upper.contains("NETWORK")
                || upper.contains("CONNECTION") || upper.contains("CONNECT")
                || upper.contains("SOCKET") || upper.contains("TLS")
                || upper.contains("SSL") || upper.contains("TIMEOUT")) {
            return RecoveryClass.NETWORK;
        }
        return RecoveryClass.OTHER;
    }
''', "deterministic recovery classification")

routes = JAVA / "PlaybackRouteMemory.java"
replace_once(routes,
'''        return "cronet".equals(transport) ? 1 : 0;
''',
'''        return recoveryStepForMode(transport);
''', "remember VLC startup")
replace_once(routes,
'''        if (transport != null && !transport.trim().isEmpty()) {
            editor.putString(PREFIX_TRANSPORT + key, transport.trim());
        }
''',
'''        String storedRoute = normalizeStoredRoute(transport);
        if (!storedRoute.isEmpty()) {
            editor.putString(PREFIX_TRANSPORT + key, storedRoute);
        }
''', "normalize stored route")
replace_once(routes,
'''    private static String clean(String value) {
''',
'''    static int recoveryStepForMode(String route) {
        String normalized = normalizeStoredRoute(route);
        if (PlaybackProfileManager.MODE_VLC.equals(normalized)) return 2;
        if (PlaybackProfileManager.MODE_CRONET.equals(normalized)
                || PlaybackProfileManager.MODE_COMPAT.equals(normalized)) return 1;
        return 0;
    }

    static String normalizeStoredRoute(String route) {
        String normalized = clean(route);
        if (PlaybackProfileManager.MODE_VLC.equals(normalized)
                || PlaybackProfileManager.MODE_CRONET.equals(normalized)
                || PlaybackProfileManager.MODE_COMPAT.equals(normalized)
                || PlaybackProfileManager.MODE_LEGACY.equals(normalized)) return normalized;
        if ("default-http".equals(normalized)) return PlaybackProfileManager.MODE_LEGACY;
        return "";
    }

    static String itemKey(String source, String kind, String id) {
        String normalizedSource = clean(source);
        return (normalizedSource.isEmpty() ? "legacy" : normalizedSource)
                + ":" + clean(kind) + ":" + clean(id);
    }

    private static String clean(String value) {
''', "pure route policy")

transport = JAVA / "PlaybackTransportFactory.java"
replace_once(transport,
'''    static DataSource.Factory createLegacy7Max(Context context) {
        Log.i(TAG, "transport=legacy7max-default-http");
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true);
        return new DefaultDataSource.Factory(context, http);
    }
''',
'''    static DataSource.Factory createLegacy7Max(Context context) {
        return createLegacy7Max(context, "");
    }

    /** Legacy timing with the same provider headers used by every fallback. */
    static DataSource.Factory createLegacy7Max(Context context, String referer) {
        Log.i(TAG, "transport=legacy7max-default-http");
        Map<String, String> headers = requestHeaders(referer);
        headers.put("Connection", "keep-alive");
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(USER_AGENT)
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(headers);
        return new DefaultDataSource.Factory(context, http);
    }
''', "legacy headers")
replace_once(transport,
'''                        new CronetDataSource.Factory(engine, executor).setUserAgent(userAgent));
''',
'''                        new CronetDataSource.Factory(engine, executor)
                                .setUserAgent(userAgent)
                                .setDefaultRequestProperties(requestHeaders(referer)));
''', "Cronet headers")
replace_once(transport,
'''        Log.i(TAG, "transport=default-http connect=" + connectTimeoutMs + " read=" + readTimeoutMs);
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "*/*");
        headers.put("Accept-Encoding", "identity");
        headers.put("Connection", "keep-alive");
        headers.put("Icy-MetaData", "1");
''',
'''        Log.i(TAG, "transport=default-http connect=" + connectTimeoutMs + " read=" + readTimeoutMs);
        Map<String, String> headers = requestHeaders(referer);
        headers.put("Connection", "keep-alive");
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setConnectTimeoutMs(connectTimeoutMs)
                .setReadTimeoutMs(readTimeoutMs)
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(headers);
        return new DefaultDataSource.Factory(context, http);
    }

    private static Map<String, String> requestHeaders(String referer) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "*/*");
        headers.put("Accept-Encoding", "identity");
        headers.put("Icy-MetaData", "1");
''', "shared header map")
replace_once(transport,
'''        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setConnectTimeoutMs(connectTimeoutMs)
                .setReadTimeoutMs(readTimeoutMs)
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(headers);
        return new DefaultDataSource.Factory(context, http);
    }

    static String userAgent(int compatibilityProfile) {
''',
'''        return headers;
    }

    static String userAgent(int compatibilityProfile) {
''', "finish shared header map")

player = JAVA / "PlayerActivity.java"
replace_once(player,
'''                    if (isLive() && "auto".equals(playerSetting(SettingsActivity.KEY_STREAM, "auto"))) {
                        ServerPlaybackProfile.Profile profile = ServerPlaybackProfile.load(this, url);
                        if (profile.fresh() && !profile.preferredLiveExtension.isEmpty()) {
                            extension = PlaybackPolicy.normalizeExtension(profile.preferredLiveExtension, extension);
                        }
                    }
''', "", "never force cached format onto another URL")
replace_once(player,
'''            return PlaybackTransportFactory.createLegacy7Max(this);
''',
'''            return PlaybackTransportFactory.createLegacy7Max(this, playbackReferer);
''', "legacy provider headers")
replace_once(player,
'''        releaseMedia3Player();
        releaseVlcPlayer();
        usingVlc = true;
''',
'''        releaseMedia3Player();
        releaseVlcPlayer();
        recoveryInProgress = false;
        usingVlc = true;
''', "unlock VLC recovery")
replace_once(player,
'''        firstFrameRendered = true;
        playbackHandler.removeCallbacks(playbackTimeout);
''',
'''        firstFrameRendered = true;
        recoveryInProgress = false;
        failureShownEpoch = -1;
        playbackHandler.removeCallbacks(playbackTimeout);
''', "VLC first-frame success")
replace_once(player,
'''        extension = configuredExtension(PlaybackPolicy.normalizeExtension(media.extension, "ts"));
        sourceVariant = "canonical";
''',
'''        extension = configuredExtension(PlaybackPolicy.normalizeExtension(media.extension, "ts"));
        liveAlternateTried = false;
        transportMode = PlaybackRouteMemory.preferredMode(this, kind, id,
                PlaybackProfileManager.preferredMode(this, kind, extension));
        sourceVariant = "canonical";
''', "new channel has isolated route state")
replace_once(player,
'''        // v331 provider-aware transport manager. Move across independent
        // transports first; only then change the provider-link variant/container.
        if (isLive() && formatSpecificLiveFailure(reason) && !liveAlternateTried && !id.isEmpty()) {
            Log.w(TAG, "format-fast-fallback ext=" + extension + " reason=" + reason);
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
''',
'''        if (isLive()) {
            PlaybackPolicy.RecoveryClass failureType = PlaybackPolicy.classifyRecovery(reason);

            // A parser/container mismatch retries the exact same URL without a
            // forced MIME type. HTTP format statuses go directly to the alternate
            // TS/HLS URL because changing a local MIME hint cannot fix an HTTP 4xx.
            if (PlaybackPolicy.isFormatFailure(reason)) {
                if (failureType == PlaybackPolicy.RecoveryClass.FORMAT
                        && !liveAlternateTried
                        && !"no-extension".equals(sourceVariant)
                        && restoreCanonicalSource()) {
                    releasePlayer();
                    transportMode = PlaybackProfileManager.MODE_COMPAT;
                    sourceVariant = "no-extension";
                    recoveryStep = 1;
                    initializePlayer();
                    return;
                }
                if (!liveAlternateTried && !id.isEmpty()) {
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
                openVlcFallback(reason);
                return;
            }

            // A first-frame or decoder failure will not improve by repeating the
            // same bytes through several HTTP factories. Move straight to the
            // independent compatibility engine and keep channel switching bounded.
            if (failureType == PlaybackPolicy.RecoveryClass.STARTUP_TIMEOUT
                    || failureType == PlaybackPolicy.RecoveryClass.DECODER
                    || failureType == PlaybackPolicy.RecoveryClass.HTTP_AUTH) {
                openVlcFallback(reason);
                return;
            }

            // DNS/TLS/socket failures get exactly one independent network stack.
            // A second network failure goes to LibVLC instead of a 30-second loop.
            if (failureType == PlaybackPolicy.RecoveryClass.DNS
                    || failureType == PlaybackPolicy.RecoveryClass.NETWORK) {
                if (PlaybackProfileManager.MODE_LEGACY.equals(transportMode)) {
                    releasePlayer();
                    transportMode = PlaybackTransportFactory.isCronetReady()
                            ? PlaybackProfileManager.MODE_CRONET
                            : PlaybackProfileManager.MODE_COMPAT;
                    recoveryStep = 1;
                    initializePlayer();
                    return;
                }
                openVlcFallback(reason);
                return;
            }

            // Other HTTP failures are provider responses, not container guesses.
            if (failureType == PlaybackPolicy.RecoveryClass.HTTP) {
                openVlcFallback(reason);
                return;
            }

            // Unknown Media3 failures receive one compatibility-header attempt.
            if (PlaybackProfileManager.MODE_LEGACY.equals(transportMode)) {
                releasePlayer();
                transportMode = PlaybackProfileManager.MODE_COMPAT;
                recoveryStep = 1;
                initializePlayer();
                return;
            }
            openVlcFallback(reason);
            return;
        }
''', "bounded live recovery")
replace_once(player,
'''        if (!vlcAttempted) {
            if (!"no-extension".equals(sourceVariant)) restoreCanonicalSource();
            transportMode = PlaybackProfileManager.MODE_VLC;
            recoveryStep = 2;
            openVlc(reason);
            return;
        }
        showPlaybackFailure(reason);
    }

    private void showPlaybackFailure(String reason) {
''',
'''        if (!vlcAttempted) {
            openVlcFallback(reason);
            return;
        }
        showPlaybackFailure(reason);
    }

    private void openVlcFallback(String reason) {
        if (vlcAttempted) {
            showPlaybackFailure(reason);
            return;
        }
        if (!"no-extension".equals(sourceVariant)) restoreCanonicalSource();
        transportMode = PlaybackProfileManager.MODE_VLC;
        recoveryStep = 2;
        recoveryInProgress = false;
        openVlc(reason);
    }

    private void showPlaybackFailure(String reason) {
''', "safe VLC fallback")
replace_once(player,
'''        switch (event.type) {
            case org.videolan.libvlc.MediaPlayer.Event.Vout:
            case org.videolan.libvlc.MediaPlayer.Event.TimeChanged:
            case org.videolan.libvlc.MediaPlayer.Event.PositionChanged:
                applyVlcSubtitlePreference();
                markVlcFirstFrame();
                break;
''',
'''        switch (event.type) {
            case org.videolan.libvlc.MediaPlayer.Event.Vout:
                applyVlcSubtitlePreference();
                markVlcFirstFrame();
                break;
            case org.videolan.libvlc.MediaPlayer.Event.TimeChanged:
            case org.videolan.libvlc.MediaPlayer.Event.PositionChanged:
                applyVlcSubtitlePreference();
                break;
''', "VLC success requires video output")

vod = JAVA / "VodPlayerActivity.java"
replace_once(vod,
'''                    resolving = false;
                    if (!lifecycleStopped) openMedia3();
''',
'''                    resolving = false;
                    if (!lifecycleStopped) {
                        if (attempt >= 2) openVlc("المسار المتوافق المحفوظ لهذا المحتوى");
                        else openMedia3();
                    }
''', "reuse learned VOD engine")
replace_once(vod,
'''            case org.videolan.libvlc.MediaPlayer.Event.Playing:
            case org.videolan.libvlc.MediaPlayer.Event.Vout:
                updateVlcTrackButtons();
                break;
            case org.videolan.libvlc.MediaPlayer.Event.TimeChanged:
            case org.videolan.libvlc.MediaPlayer.Event.PositionChanged:
                updateVlcTrackButtons();
                if (!firstFrame) {
                    firstFrame = true;
                    spinner.setVisibility(View.GONE);
                    main.removeCallbacks(startupTimeout);
                    PlaybackProfileManager.recordSuccess(this, kind, extension, "vlc");
                    showControls();
                }
                break;
''',
'''            case org.videolan.libvlc.MediaPlayer.Event.Playing:
                updateVlcTrackButtons();
                break;
            case org.videolan.libvlc.MediaPlayer.Event.Vout:
                updateVlcTrackButtons();
                markVlcFirstFrame();
                break;
            case org.videolan.libvlc.MediaPlayer.Event.TimeChanged:
            case org.videolan.libvlc.MediaPlayer.Event.PositionChanged:
                updateVlcTrackButtons();
                markVlcFirstFrame();
                break;
''', "real VLC first frame")
replace_once(vod,
'''    private void startWatchdog(long timeoutMs) {
''',
'''    private void markVlcFirstFrame() {
        if (firstFrame || !usingVlc) return;
        firstFrame = true;
        spinner.setVisibility(View.GONE);
        main.removeCallbacks(startupTimeout);
        PlaybackRouteMemory.recordSuccess(this, kind, id, extension,
                PlaybackProfileManager.MODE_VLC);
        PlaybackProfileManager.recordSuccess(this, kind, extension,
                PlaybackProfileManager.MODE_VLC);
        showControls();
    }

    private void startWatchdog(long timeoutMs) {
''', "store learned VLC route")
replace_once(vod,
'''        boolean networkFailure = PlaybackPolicy.isNetworkFailure(reason)
                && !PlaybackPolicy.isStartupTimeout(reason);
''',
'''        PlaybackPolicy.RecoveryClass failureType = PlaybackPolicy.classifyRecovery(reason);

        // Repeating a source that reached Ready without a frame, or one rejected
        // by the device decoder, only adds another long watchdog. LibVLC is the
        // independent decoder route and is intentionally next for these failures.
        if (failureType == PlaybackPolicy.RecoveryClass.STARTUP_TIMEOUT
                || failureType == PlaybackPolicy.RecoveryClass.DECODER
                || failureType == PlaybackPolicy.RecoveryClass.HTTP_AUTH) {
            if ("direct".equals(sourceVariant) || "no-extension".equals(sourceVariant)) {
                restoreCanonicalSource();
            }
            attempt = 2;
            openVlc(reason);
            return;
        }

        boolean networkFailure = failureType == PlaybackPolicy.RecoveryClass.DNS
                || failureType == PlaybackPolicy.RecoveryClass.NETWORK
                || failureType == PlaybackPolicy.RecoveryClass.HTTP;
''', "bounded VOD recovery")
replace_once(vod,
'''        // Startup, parser, codec or a failed direct source gets one neutral
''',
'''        if (networkFailure) {
            if ("direct".equals(sourceVariant) || "no-extension".equals(sourceVariant)) {
                restoreCanonicalSource();
            }
            attempt = 2;
            openVlc(reason);
            return;
        }

        // Startup, parser, codec or a failed direct source gets one neutral
''', "network ladder cap")

# Keep the exact learned VOD transport.  The old integer attempt value could
# not distinguish a proven platform-HTTP compatibility route from Cronet.
replace_once(vod,
'''    private String sourceVariant = "canonical";
    private long resumePosition;
''',
'''    private String sourceVariant = "canonical";
    private String transportMode = PlaybackProfileManager.MODE_LEGACY;
    private long resumePosition;
''', "VOD transport field")
replace_once(vod,
'''        attempt = PlaybackRouteMemory.preferredRecoveryStep(this, kind, id);
''',
'''        transportMode = PlaybackRouteMemory.normalizeStoredRoute(
                PlaybackRouteMemory.preferredMode(this, kind, id, ""));
        if (transportMode.isEmpty()) transportMode = PlaybackProfileManager.MODE_LEGACY;
        attempt = PlaybackRouteMemory.recoveryStepForMode(transportMode);
''', "load exact VOD transport")
replace_once(vod,
'''                        if (attempt >= 2) openVlc("المسار المتوافق المحفوظ لهذا المحتوى");
                        else openMedia3();
''',
'''                        if (PlaybackProfileManager.MODE_VLC.equals(transportMode)) {
                            openVlc("المسار المتوافق المحفوظ لهذا المحتوى");
                        }
                        else openMedia3();
''', "open learned VLC route")
replace_once(vod,
'''                        sourceVariant = "no-extension";
                        attempt = 2;
                        if (!lifecycleStopped) openMedia3();
''',
'''                        sourceVariant = "no-extension";
                        transportMode = PlaybackProfileManager.MODE_COMPAT;
                        attempt = 1;
                        if (!lifecycleStopped) openMedia3();
''', "direct resolve fallback stays Media3")
replace_once(vod,
'''        sourceVariant = "canonical";
        attempt = 2;
        return true;
''',
'''        sourceVariant = "canonical";
        return true;
''', "canonical restore does not choose engine")
replace_once(vod,
'''        boolean preferCronet = attempt == 1 && PlaybackTransportFactory.isCronetReady();
        DataSource.Factory source = PlaybackTransportFactory.create(
                this, preferCronet, network,
                attempt == 0 ? (ultraHd() ? 5_000 : 3_500) : 3_500,
                attempt == 0 ? (ultraHd() ? 12_000 : 8_000) : 8_000,
                attempt, playbackReferer);
''',
'''        boolean preferCronet = PlaybackProfileManager.MODE_CRONET.equals(transportMode)
                && PlaybackTransportFactory.isCronetReady();
        if (PlaybackProfileManager.MODE_CRONET.equals(transportMode) && !preferCronet) {
            transportMode = PlaybackProfileManager.MODE_COMPAT;
        }
        int compatibilityProfile = PlaybackProfileManager.MODE_LEGACY.equals(transportMode) ? 0 : 1;
        DataSource.Factory source = PlaybackTransportFactory.create(
                this, preferCronet, network,
                compatibilityProfile == 0 ? (ultraHd() ? 5_000 : 3_500) : 3_500,
                compatibilityProfile == 0 ? (ultraHd() ? 12_000 : 8_000) : 8_000,
                compatibilityProfile, playbackReferer);
''', "honor exact Media3 transport")
replace_once(vod,
'''        engineView.setText(attempt == 1 && PlaybackTransportFactory.isCronetReady()
                ? "Media3 • Cronet" : "Media3");
''',
'''        engineView.setText(preferCronet
                ? "Media3 • Cronet" : "Media3");
''', "accurate engine label")
replace_once(vod,
'''            releaseAllEngines();
            usingVlc = true;
''',
'''            releaseAllEngines();
            transportMode = PlaybackProfileManager.MODE_VLC;
            attempt = 2;
            usingVlc = true;
''', "VLC transport state")
replace_once(vod,
'''            case org.videolan.libvlc.MediaPlayer.Event.TimeChanged:
            case org.videolan.libvlc.MediaPlayer.Event.PositionChanged:
                updateVlcTrackButtons();
                markVlcFirstFrame();
                break;
''',
'''            case org.videolan.libvlc.MediaPlayer.Event.TimeChanged:
            case org.videolan.libvlc.MediaPlayer.Event.PositionChanged:
                updateVlcTrackButtons();
                break;
''', "VOD VLC success requires Vout")
replace_once(vod,
'''            attempt = 2;
            openVlc(reason);
            return;
        }

        boolean networkFailure = failureType == PlaybackPolicy.RecoveryClass.DNS
''',
'''            attempt = 2;
            transportMode = PlaybackProfileManager.MODE_VLC;
            openVlc(reason);
            return;
        }

        boolean networkFailure = failureType == PlaybackPolicy.RecoveryClass.DNS
''', "decoder route to VLC")
replace_once(vod,
'''            alternateSourceAttempted = true;
            sourceVariant = "direct";
            attempt = 1;
''',
'''            alternateSourceAttempted = true;
            sourceVariant = "direct";
            transportMode = PlaybackTransportFactory.isCronetReady()
                    ? PlaybackProfileManager.MODE_CRONET
                    : PlaybackProfileManager.MODE_COMPAT;
            attempt = 1;
''', "network transport selection")
replace_once(vod,
'''            attempt = 2;
            openVlc(reason);
            return;
        }

        // Startup, parser, codec or a failed direct source gets one neutral
''',
'''            attempt = 2;
            transportMode = PlaybackProfileManager.MODE_VLC;
            openVlc(reason);
            return;
        }

        // Startup, parser, codec or a failed direct source gets one neutral
''', "second network failure to VLC")
replace_once(vod,
'''            sourceVariant = "no-extension";
            attempt = 1;
''',
'''            sourceVariant = "no-extension";
            transportMode = PlaybackProfileManager.MODE_COMPAT;
            attempt = 1;
''', "neutral container transport")
replace_once(vod,
'''        attempt = 2;
        openVlc(reason);
    }

    private void showFinalPlaybackError(String reason) {
''',
'''        attempt = 2;
        transportMode = PlaybackProfileManager.MODE_VLC;
        openVlc(reason);
    }

    private void showFinalPlaybackError(String reason) {
''', "final VOD fallback route")
replace_once(vod,
'''        attempt = 0;
        usingVlc = false;
''',
'''        attempt = 0;
        transportMode = PlaybackProfileManager.MODE_LEGACY;
        usingVlc = false;
''', "manual VOD reset")
replace_once(vod,
'''        PlaybackRouteMemory.recordSuccess(this, kind, id, extension,
                attempt == 1 && PlaybackTransportFactory.isCronetReady() ? "cronet" : "default-http");
        PlaybackProfileManager.recordSuccess(this, kind, extension,
                attempt == 1 && PlaybackTransportFactory.isCronetReady() ? "cronet" : "legacy7max");
''',
'''        PlaybackRouteMemory.recordSuccess(this, kind, id, extension,
                transportMode);
        PlaybackProfileManager.recordSuccess(this, kind, extension,
                transportMode);
''', "record exact VOD route")
replace_once(vod,
'''            if (usingVlc || attempt >= 2) openVlc("استئناف المشاهدة");
            else openMedia3();
''',
'''            if (usingVlc || PlaybackProfileManager.MODE_VLC.equals(transportMode)) {
                openVlc("استئناف المشاهدة");
            }
            else openMedia3();
''', "resume exact VOD route")

server_profile = JAVA / "ServerPlaybackProfile.java"
replace_once(server_profile,
'''        if (extension != null && !extension.isEmpty()) editor.putString(key + ".live_ext", extension);
''',
'''        String liveExtension = normalizeLiveExtension(extension);
        if (!liveExtension.isEmpty()) editor.putString(key + ".live_ext", liveExtension);
''', "only remember real live formats")
replace_once(server_profile,
'''    }
}
''',
'''    }

    private static String normalizeLiveExtension(String extension) {
        String value = extension == null ? "" : extension.trim().toLowerCase(Locale.US).replace(".", "");
        if (value.contains("m3u8") || value.contains("hls")) return "m3u8";
        if (value.equals("ts") || value.contains("mpegts")) return "ts";
        return "";
    }
}
''', "server format normalization")

preview = JAVA / "LivePreviewController.java"
replace_once(preview,
'''    private Resolved openedResolved;
    private final Runnable previewTimeout = () -> {
        if (openedGeneration != generation.get()) return;
        if (player != null) player.stop();
        evictOpened();
        if (listener != null) listener.error();
    };
''',
'''    private Resolved openedResolved;
    private int previewRecoveryStep;
    private final Runnable previewTimeout = () -> {
        if (openedGeneration != generation.get()) return;
        recoverPreview();
    };
''', "preview watchdog recovery")
replace_once(preview,
'''        int token = generation.incrementAndGet();
        if (listener != null) listener.loading();
''',
'''        int token = generation.incrementAndGet();
        previewRecoveryStep = 0;
        if (listener != null) listener.loading();
''', "preview route reset")
replace_once(preview,
'''        String normalized = referer == null ? "" : referer;
        if (player != null && normalized.equals(playerReferer)) return;
''',
'''        String normalized = referer == null ? "" : referer;
        String transportKey = normalized + "#" + previewRecoveryStep;
        if (player != null && transportKey.equals(playerReferer)) return;
''', "preview transport key")
replace_once(preview,
'''        playerReferer = normalized;
        DataSource.Factory data = PlaybackTransportFactory.create(context, false, network,
                2_000, 4_500, 0, normalized);
''',
'''        playerReferer = transportKey;
        DataSource.Factory data = PlaybackTransportFactory.create(context, false, network,
                2_000, 4_500, previewRecoveryStep == 0 ? 0 : 1, normalized);
''', "preview compatibility headers")
replace_once(preview,
'''    @Override public void onPlayerError(PlaybackException error) {
        if (openedGeneration == generation.get()) {
            main.removeCallbacks(previewTimeout);
            evictOpened();
            if (listener != null) listener.error();
        }
    }

    void release() {
''',
'''    @Override public void onPlayerError(PlaybackException error) {
        if (openedGeneration == generation.get()) {
            main.removeCallbacks(previewTimeout);
            recoverPreview();
        }
    }

    private void recoverPreview() {
        main.removeCallbacks(previewTimeout);
        if (openedGeneration != generation.get() || openedResolved == null) {
            evictOpened();
            if (listener != null) listener.error();
            return;
        }
        if (previewRecoveryStep == 0) {
            previewRecoveryStep = 1;
            Resolved retry = new Resolved(openedResolved.url,
                    PlaybackPolicy.alternateLiveExtension(openedResolved.extension),
                    openedResolved.referer);
            open(openedCacheKey, retry, openedGeneration);
            return;
        }
        if (player != null) player.stop();
        evictOpened();
        if (listener != null) listener.error();
    }

    void release() {
''', "preview alternate MIME retry")

write_new(TESTS / "PlaybackRecoveryClassificationTest.java", '''package tv.blofy.player;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class PlaybackRecoveryClassificationTest {
    @Test public void firstFrameTimeoutIsStartupTimeoutNotNetwork() {
        assertEquals(PlaybackPolicy.RecoveryClass.STARTUP_TIMEOUT,
                PlaybackPolicy.classifyRecovery("FIRST-FRAME-TIMEOUT"));
        assertEquals(PlaybackPolicy.RecoveryClass.STARTUP_TIMEOUT,
                PlaybackPolicy.classifyRecovery("لم تظهر صورة الفيديو"));
    }

    @Test public void parserContainerErrorIsFormatBeforeDecoder() {
        assertEquals(PlaybackPolicy.RecoveryClass.FORMAT,
                PlaybackPolicy.classifyRecovery("ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED"));
    }

    @Test public void httpStatusesSeparateAuthorizationAndFormat() {
        assertEquals(PlaybackPolicy.RecoveryClass.HTTP_AUTH,
                PlaybackPolicy.classifyRecovery("HTTP 403"));
        assertEquals(PlaybackPolicy.RecoveryClass.HTTP_FORMAT,
                PlaybackPolicy.classifyRecovery("HTTP 415"));
        assertEquals(PlaybackPolicy.RecoveryClass.HTTP,
                PlaybackPolicy.classifyRecovery("HTTP 503"));
    }

    @Test public void dnsAliasesAreDns() {
        assertEquals(PlaybackPolicy.RecoveryClass.DNS,
                PlaybackPolicy.classifyRecovery("DNS EAI_NODATA"));
        assertEquals(PlaybackPolicy.RecoveryClass.DNS,
                PlaybackPolicy.classifyRecovery("Unable to resolve host"));
    }

    @Test public void ioConnectionTimeoutIsNetworkNotStartupTimeout() {
        assertEquals(PlaybackPolicy.RecoveryClass.NETWORK,
                PlaybackPolicy.classifyRecovery("ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT"));
    }
}
''')

write_new(TESTS / "PlaybackRouteMemoryPolicyTest.java", '''package tv.blofy.player;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class PlaybackRouteMemoryPolicyTest {
    @Test public void vlcRouteRoundTripsToRecoveryStepTwo() {
        assertEquals(PlaybackProfileManager.MODE_VLC,
                PlaybackRouteMemory.normalizeStoredRoute(" VLC "));
        assertEquals(2, PlaybackRouteMemory.recoveryStepForMode("vlc"));
    }

    @Test public void routeKeyIsScopedBySourceKindAndItem() {
        assertEquals("provider-a:movies:42",
                PlaybackRouteMemory.itemKey("Provider-A", "Movies", "42"));
        assertEquals("provider-b:movies:42",
                PlaybackRouteMemory.itemKey("Provider-B", "Movies", "42"));
    }

    @Test public void unknownRouteFallsBackToInitialStep() {
        assertEquals("", PlaybackRouteMemory.normalizeStoredRoute("mystery"));
        assertEquals(0, PlaybackRouteMemory.recoveryStepForMode("mystery"));
        assertEquals(0, PlaybackRouteMemory.recoveryStepForMode("default-http"));
    }
}
''')

for required in [
        "versionName = \"v341-playback-only\"",
        "RecoveryClass",
        "createLegacy7Max(this, playbackReferer)",
        "openVlcFallback",
        "PlaybackRouteMemory.recordSuccess(this, kind, id, extension",
        "recoverPreview()"]:
    found = any(required in path.read_text(encoding="utf-8") for path in [build, policy, player, vod, preview])
    if not found:
        raise SystemExit("v341 invariant missing: " + required)

print("v341 playback-only applied: bounded recovery, provider headers, isolated channels, learned VLC")
