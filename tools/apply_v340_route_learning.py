#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player"
PLAYER = JAVA / "PlayerActivity.java"
VOD = JAVA / "VodPlayerActivity.java"


def patch(path: Path, old: str, new: str, label: str):
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"v340 route-learning patch mismatch in {path.name}: {label}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def optional_patch(path: Path, old: str, new: str, label: str):
    text = path.read_text(encoding="utf-8")
    if old not in text:
        print(f"v340 route-learning optional patch skipped in {path.name}: {label}")
        return
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


patch(PLAYER,
'''    private boolean isLive() { return isLiveKind(kind); }\n''',
'''    private boolean isLive() { return isLiveKind(kind); }\n\n    private static boolean learnedAlternateVariant(String value) {\n        return "direct".equals(value) || "no-extension".equals(value);\n    }\n''',
"alternate variant helper")

patch(PLAYER,
'''                    if ("canonical".equals(requestedVariant)) {\n                        canonicalUrl = url;\n                        canonicalExtension = extension;\n                        canonicalReferer = playbackReferer;\n                    }\n                    prepareResolvedUrl();\n''',
'''                    if ("canonical".equals(requestedVariant)) {\n                        canonicalUrl = url;\n                        canonicalExtension = extension;\n                        canonicalReferer = playbackReferer;\n                        ServerPlaybackProfile.Profile learned = ServerPlaybackProfile.load(this, canonicalUrl);\n                        if (learned.fresh() && learnedAlternateVariant(learned.preferredRoute)) {\n                            sourceVariant = learned.preferredRoute;\n                            PlaybackDiagnostics.marker(this, "learned-route-retry", kind, id, extension,\n                                    sourceVariant, "canonical-ready");\n                            url = null;\n                            resolvePlaybackLink();\n                            return;\n                        }\n                    }\n                    prepareResolvedUrl();\n''',
"apply learned player route")

patch(PLAYER,
'''                    if ("direct".equals(requestedVariant) && restoreCanonicalSource()) {\n''',
'''                    if (learnedAlternateVariant(requestedVariant) && restoreCanonicalSource()) {\n''',
"player resolve canonical fallback")

optional_patch(PLAYER,
'''            if ("direct".equals(sourceVariant)) restoreCanonicalSource();\n''',
'''            if (learnedAlternateVariant(sourceVariant)) restoreCanonicalSource();\n''',
"player playback canonical fallback")


patch(VOD,
'''    private boolean restoreCanonicalSource() {\n''',
'''    private static boolean learnedAlternateVariant(String value) {\n        return "direct".equals(value) || "no-extension".equals(value);\n    }\n\n    private boolean restoreCanonicalSource() {\n''',
"VOD alternate variant helper")

patch(VOD,
'''                    if ("canonical".equals(requestedVariant)) {\n                        canonicalUrl = resolvedUrl;\n                        canonicalExtension = extension;\n                        canonicalReferer = playbackReferer;\n                    }\n                    resolving = false;\n                    if (!lifecycleStopped) openMedia3();\n''',
'''                    if ("canonical".equals(requestedVariant)) {\n                        canonicalUrl = resolvedUrl;\n                        canonicalExtension = extension;\n                        canonicalReferer = playbackReferer;\n                        ServerPlaybackProfile.Profile learned = ServerPlaybackProfile.load(this, canonicalUrl);\n                        if (learned.fresh() && learnedAlternateVariant(learned.preferredRoute)) {\n                            sourceVariant = learned.preferredRoute;\n                            alternateSourceAttempted = "direct".equals(sourceVariant);\n                            containerRouteAttempted = "no-extension".equals(sourceVariant);\n                            attempt = 1;\n                            resolvedUrl = "";\n                            resolving = false;\n                            PlaybackDiagnostics.marker(this, "learned-vod-route-retry", kind, id, extension,\n                                    sourceVariant, "canonical-ready");\n                            resolve();\n                            return;\n                        }\n                    }\n                    resolving = false;\n                    if (!lifecycleStopped) openMedia3();\n''',
"apply learned VOD route")

# Normalize the resolver-error branch regardless of whether v337 left the direct-only
# form, the direct/no-extension form, or another whitespace-equivalent layout.
vod_text = VOD.read_text(encoding="utf-8")
resolver_pattern = re.compile(
    r'(?P<indent>\s*)if\s*\(\s*(?:\(\s*)?"direct"\.equals\(requestedVariant\)'
    r'(?:\s*\|\|\s*"no-extension"\.equals\(requestedVariant\)\s*\))?\s*'
    r'&&\s*restoreCanonicalSource\(\)\s*\)\s*\{\s*'
    r'if\s*\(!lifecycleStopped\)\s*openVlc\(PlaybackPolicy\.resolveErrorMessage\(error\)\);\s*'
    r'return;\s*\}',
    re.MULTILINE)
match = resolver_pattern.search(vod_text)
if not match:
    raise SystemExit("v340 route-learning patch mismatch in VodPlayerActivity.java: VOD resolve canonical fallback")
indent = match.group('indent')
replacement = (
    indent + 'if (learnedAlternateVariant(requestedVariant) && restoreCanonicalSource()) {\n'
    + indent + '    attempt = 0;\n'
    + indent + '    if (!lifecycleStopped) openMedia3();\n'
    + indent + '    return;\n'
    + indent + '}'
)
vod_text = vod_text[:match.start()] + replacement + vod_text[match.end():]
VOD.write_text(vod_text, encoding="utf-8")

patch(VOD,
'''        boolean networkFailure = PlaybackPolicy.isNetworkFailure(reason)\n                && !PlaybackPolicy.isStartupTimeout(reason);\n\n''',
'''        boolean networkFailure = PlaybackPolicy.isNetworkFailure(reason)\n                && !PlaybackPolicy.isStartupTimeout(reason);\n\n        if (learnedAlternateVariant(sourceVariant) && validUrl(canonicalUrl)) {\n            String failedVariant = sourceVariant;\n            if (restoreCanonicalSource()) {\n                attempt = 0;\n                releaseAllEngines();\n                PlaybackDiagnostics.marker(this, "learned-vod-route-fallback", kind, id, extension,\n                        failedVariant, reason == null ? "playback-failure" : reason);\n                openMedia3();\n                return;\n            }\n        }\n\n''',
"VOD playback canonical fallback")

patch(VOD,
'''        PlaybackProfileManager.recordSuccess(this, kind, extension,\n                attempt == 1 && PlaybackTransportFactory.isCronetReady() ? "cronet" : "legacy7max");\n        showControls();\n''',
'''        PlaybackProfileManager.recordSuccess(this, kind, extension,\n                attempt == 1 && PlaybackTransportFactory.isCronetReady() ? "cronet" : "legacy7max");\n        ServerPlaybackProfile.rememberSuccess(this, resolvedUrl, extension, sourceVariant,\n                attempt == 1 && PlaybackTransportFactory.isCronetReady() ? "cronet" : "media3",\n                "", playbackReferer);\n        showControls();\n''',
"persist VOD Media3 source route")

patch(VOD,
'''                    PlaybackProfileManager.recordSuccess(this, kind, extension, "vlc");\n                    showControls();\n''',
'''                    PlaybackProfileManager.recordSuccess(this, kind, extension, "vlc");\n                    ServerPlaybackProfile.rememberSuccess(this, resolvedUrl, extension, sourceVariant,\n                            "libvlc", "", playbackReferer);\n                    showControls();\n''',
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
