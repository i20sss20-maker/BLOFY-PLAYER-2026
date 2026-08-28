#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player"
PLAYER = JAVA / "PlayerActivity.java"
VOD = JAVA / "VodPlayerActivity.java"


def patch(path: Path, old: str, new: str, label: str):
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"v340 route-learning patch mismatch in {path.name}: {label}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


# -----------------------------------------------------------------------------
# Full-screen/live player: after canonical resolves once, reuse the last proven
# provider route (direct or no-extension). If that learned route is no longer valid,
# immediately fall back to the cached canonical URL instead of burning another long
# startup timeout. Successful first-frame reporting from v340_full already persists
# sourceVariant through ServerPlaybackProfile.rememberSuccess(...).
# -----------------------------------------------------------------------------
patch(
    PLAYER,
    '''    private boolean isLive() { return isLiveKind(kind); }\n''',
    '''    private boolean isLive() { return isLiveKind(kind); }\n\n'''
    '''    private static boolean learnedAlternateVariant(String value) {\n'''
    '''        return "direct".equals(value) || "no-extension".equals(value);\n'''
    '''    }\n''',
    "alternate variant helper")

patch(
    PLAYER,
    '''                    if ("canonical".equals(requestedVariant)) {\n'''
    '''                        canonicalUrl = url;\n'''
    '''                        canonicalExtension = extension;\n'''
    '''                        canonicalReferer = playbackReferer;\n'''
    '''                    }\n'''
    '''                    prepareResolvedUrl();\n''',
    '''                    if ("canonical".equals(requestedVariant)) {\n'''
    '''                        canonicalUrl = url;\n'''
    '''                        canonicalExtension = extension;\n'''
    '''                        canonicalReferer = playbackReferer;\n'''
    '''                        ServerPlaybackProfile.Profile learned = ServerPlaybackProfile.load(this, canonicalUrl);\n'''
    '''                        if (learned.fresh() && learnedAlternateVariant(learned.preferredRoute)) {\n'''
    '''                            sourceVariant = learned.preferredRoute;\n'''
    '''                            PlaybackDiagnostics.marker(this, "learned-route-retry", kind, id, extension,\n'''
    '''                                    sourceVariant, "canonical-ready");\n'''
    '''                            url = null;\n'''
    '''                            resolvePlaybackLink();\n'''
    '''                            return;\n'''
    '''                        }\n'''
    '''                    }\n'''
    '''                    prepareResolvedUrl();\n''',
    "apply learned player route")

patch(
    PLAYER,
    '''                    if ("direct".equals(requestedVariant) && restoreCanonicalSource()) {\n''',
    '''                    if (learnedAlternateVariant(requestedVariant) && restoreCanonicalSource()) {\n''',
    "player resolve canonical fallback")

patch(
    PLAYER,
    '''            recoveryStep = 2;\n'''
    '''            if ("direct".equals(sourceVariant)) restoreCanonicalSource();\n'''
    '''            openVlc(reason);\n''',
    '''            recoveryStep = 2;\n'''
    '''            if (learnedAlternateVariant(sourceVariant)) restoreCanonicalSource();\n'''
    '''            openVlc(reason);\n''',
    "player playback canonical fallback")


# -----------------------------------------------------------------------------
# Dedicated VOD player: v337 already has direct/no-extension recovery, but it only
# remembered transport. Persist the source route per provider host too, replay it
# after the cheap canonical resolve, and fall back to canonical Media3 immediately
# when the learned alternate route stops working.
# -----------------------------------------------------------------------------
patch(
    VOD,
    '''    private boolean restoreCanonicalSource() {\n''',
    '''    private static boolean learnedAlternateVariant(String value) {\n'''
    '''        return "direct".equals(value) || "no-extension".equals(value);\n'''
    '''    }\n\n'''
    '''    private boolean restoreCanonicalSource() {\n''',
    "VOD alternate variant helper")

patch(
    VOD,
    '''                    if ("canonical".equals(requestedVariant)) {\n'''
    '''                        canonicalUrl = resolvedUrl;\n'''
    '''                        canonicalExtension = extension;\n'''
    '''                        canonicalReferer = playbackReferer;\n'''
    '''                    }\n'''
    '''                    resolving = false;\n'''
    '''                    if (!lifecycleStopped) openMedia3();\n''',
    '''                    if ("canonical".equals(requestedVariant)) {\n'''
    '''                        canonicalUrl = resolvedUrl;\n'''
    '''                        canonicalExtension = extension;\n'''
    '''                        canonicalReferer = playbackReferer;\n'''
    '''                        ServerPlaybackProfile.Profile learned = ServerPlaybackProfile.load(this, canonicalUrl);\n'''
    '''                        if (learned.fresh() && learnedAlternateVariant(learned.preferredRoute)) {\n'''
    '''                            sourceVariant = learned.preferredRoute;\n'''
    '''                            alternateSourceAttempted = "direct".equals(sourceVariant);\n'''
    '''                            containerRouteAttempted = "no-extension".equals(sourceVariant);\n'''
    '''                            attempt = 1;\n'''
    '''                            resolvedUrl = "";\n'''
    '''                            resolving = false;\n'''
    '''                            PlaybackDiagnostics.marker(this, "learned-vod-route-retry", kind, id, extension,\n'''
    '''                                    sourceVariant, "canonical-ready");\n'''
    '''                            resolve();\n'''
    '''                            return;\n'''
    '''                        }\n'''
    '''                    }\n'''
    '''                    resolving = false;\n'''
    '''                    if (!lifecycleStopped) openMedia3();\n''',
    "apply learned VOD route")

# v337 has already broadened this to direct OR no-extension. Replace that final form.
patch(
    VOD,
    '''                    if (("direct".equals(requestedVariant) || "no-extension".equals(requestedVariant))\n'''
    '''                            && restoreCanonicalSource()) {\n'''
    '''                        if (!lifecycleStopped) openVlc(PlaybackPolicy.resolveErrorMessage(error));\n'''
    '''                        return;\n'''
    '''                    }\n''',
    '''                    if (learnedAlternateVariant(requestedVariant) && restoreCanonicalSource()) {\n'''
    '''                        attempt = 0;\n'''
    '''                        if (!lifecycleStopped) openMedia3();\n'''
    '''                        return;\n'''
    '''                    }\n''',
    "VOD resolve canonical Media3 fallback")

# When an already-resolved learned route fails during playback, retry canonical once
# before continuing into the existing direct/no-extension/LibVLC recovery ladder.
patch(
    VOD,
    '''        boolean networkFailure = PlaybackPolicy.isNetworkFailure(reason)\n'''
    '''                && !PlaybackPolicy.isStartupTimeout(reason);\n\n''',
    '''        boolean networkFailure = PlaybackPolicy.isNetworkFailure(reason)\n'''
    '''                && !PlaybackPolicy.isStartupTimeout(reason);\n\n'''
    '''        if (learnedAlternateVariant(sourceVariant) && validUrl(canonicalUrl)) {\n'''
    '''            String failedVariant = sourceVariant;\n'''
    '''            if (restoreCanonicalSource()) {\n'''
    '''                attempt = 0;\n'''
    '''                releaseAllEngines();\n'''
    '''                PlaybackDiagnostics.marker(this, "learned-vod-route-fallback", kind, id, extension,\n'''
    '''                        failedVariant, reason == null ? "playback-failure" : reason);\n'''
    '''                openMedia3();\n'''
    '''                return;\n'''
    '''            }\n'''
    '''        }\n\n''',
    "VOD playback canonical fallback")

# v337 first-frame block already records PlaybackRouteMemory and PlaybackProfileManager.
patch(
    VOD,
    '''        PlaybackProfileManager.recordSuccess(this, kind, extension,\n'''
    '''                attempt == 1 && PlaybackTransportFactory.isCronetReady() ? "cronet" : "legacy7max");\n'''
    '''        showControls();\n''',
    '''        PlaybackProfileManager.recordSuccess(this, kind, extension,\n'''
    '''                attempt == 1 && PlaybackTransportFactory.isCronetReady() ? "cronet" : "legacy7max");\n'''
    '''        ServerPlaybackProfile.rememberSuccess(this, resolvedUrl, extension, sourceVariant,\n'''
    '''                attempt == 1 && PlaybackTransportFactory.isCronetReady() ? "cronet" : "media3",\n'''
    '''                "", playbackReferer);\n'''
    '''        showControls();\n''',
    "persist VOD Media3 source route")

patch(
    VOD,
    '''                    PlaybackProfileManager.recordSuccess(this, kind, extension, "vlc");\n'''
    '''                    showControls();\n''',
    '''                    PlaybackProfileManager.recordSuccess(this, kind, extension, "vlc");\n'''
    '''                    ServerPlaybackProfile.rememberSuccess(this, resolvedUrl, extension, sourceVariant,\n'''
    '''                            "libvlc", "", playbackReferer);\n'''
    '''                    showControls();\n''',
    "persist VOD VLC source route")

for path, tokens in [
    (PLAYER, ["learned-route-retry", "learnedAlternateVariant(requestedVariant)",
              "ServerPlaybackProfile.load(this, canonicalUrl)"]),
    (VOD, ["learned-vod-route-retry", "learned-vod-route-fallback",
            "ServerPlaybackProfile.rememberSuccess(this, resolvedUrl, extension, sourceVariant"]),
]:
    final = path.read_text(encoding="utf-8")
    for token in tokens:
        if token not in final:
            raise SystemExit(f"v340 route-learning invariant missing in {path.name}: {token}")

print("v340 route learning applied: direct/no-extension memory + immediate canonical fallback")
