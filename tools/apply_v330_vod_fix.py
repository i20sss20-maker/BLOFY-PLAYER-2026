from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PATH = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player/VodPlayerActivity.java"
text = PATH.read_text(encoding="utf-8")


def once(old, new, label):
    global text
    if old not in text:
        raise SystemExit(f"v330 VOD patch mismatch: {label}")
    text = text.replace(old, new, 1)

# Dead VOD items must never hold the TV UI on 15-45 second socket windows.
once('''        DataSource.Factory source = PlaybackTransportFactory.create(
                this, false, network,
                ultraHd() ? 20_000 : 15_000,
                ultraHd() ? 45_000 : 30_000,
                attempt, playbackReferer);
''', '''        DataSource.Factory source = PlaybackTransportFactory.create(
                this, false, network,
                ultraHd() ? 5_000 : 3_500,
                ultraHd() ? 12_000 : 8_000,
                attempt, playbackReferer);
''', "short socket windows")

# Use the same bounded compatibility ladder as live playback. A timeout is a
# failed item, not a reason to freeze on the same source.
old_recover = '''        // An immediate HTTP/connection failure can be caused by the signed
        // relay route. Resolve the provider's direct variant once, without also
        // walking through an identical Cronet retry. A slow/unsupported decoder
        // goes straight to LibVLC so total startup stays bounded.
        if (PlaybackPolicy.isNetworkFailure(reason)
                && !PlaybackPolicy.isStartupTimeout(reason)
                && !alternateSourceAttempted && !id.isEmpty()) {
            alternateSourceAttempted = true;
            sourceVariant = "direct";
            attempt = 1;
            releaseAllEngines();
            resolvedUrl = "";
            resolving = false;
            resolve();
            return;
        }

        if ("direct".equals(sourceVariant)) restoreCanonicalSource();
        attempt = 2;
        openVlc(reason);
'''
new_recover = '''        // v330: every movie/episode gets a bounded per-item recovery chain.
        // canonical -> provider direct -> canonical with container sniffing -> VLC.
        // The fast path remains unchanged for items that start normally.
        if ("canonical".equals(sourceVariant) && !alternateSourceAttempted && !id.isEmpty()) {
            alternateSourceAttempted = true;
            sourceVariant = "direct";
            attempt = 1;
            releaseAllEngines();
            resolvedUrl = "";
            resolving = false;
            resolve();
            return;
        }

        if ("direct".equals(sourceVariant) && restoreCanonicalSource()) {
            releaseAllEngines();
            sourceVariant = "no-extension";
            attempt = 2;
            openMedia3();
            return;
        }

        if (!usingVlc) {
            if (!"no-extension".equals(sourceVariant)) restoreCanonicalSource();
            attempt = 2;
            openVlc(reason);
            return;
        }
        showFinalPlaybackError(reason);
'''
once(old_recover, new_recover, "bounded per-item recovery")

# Do not force a MIME type on the sniffing fallback.
once('''        String mime = PlaybackPolicy.mimeType(extension);
        if (mime != null && (PlaybackPolicy.isHls(extension) || "mpd".equalsIgnoreCase(extension))) {
            item.setMimeType(mime);
        }
''', '''        String mime = "no-extension".equals(sourceVariant)
                ? null : PlaybackPolicy.mimeType(extension);
        if (mime != null && (PlaybackPolicy.isHls(extension) || "mpd".equalsIgnoreCase(extension))) {
            item.setMimeType(mime);
        }
''', "container sniffing")

# If resolving the direct provider URL itself fails, fall back to the known
# canonical URL with sniffing instead of blocking or showing an error at once.
once('''                    if ("direct".equals(requestedVariant) && restoreCanonicalSource()) {
                        if (!lifecycleStopped) openVlc(PlaybackPolicy.resolveErrorMessage(error));
                        return;
                    }
                    showError(PlaybackPolicy.resolveErrorMessage(error));
''', '''                    if ("direct".equals(requestedVariant) && restoreCanonicalSource()) {
                        sourceVariant = "no-extension";
                        attempt = 2;
                        if (!lifecycleStopped) openMedia3();
                        return;
                    }
                    showError(PlaybackPolicy.resolveErrorMessage(error));
''', "direct resolve fallback")

PATH.write_text(text, encoding="utf-8")
print("v330 VOD fast-failure fix applied")
