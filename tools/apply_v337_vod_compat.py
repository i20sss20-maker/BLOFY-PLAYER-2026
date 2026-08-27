#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VOD = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player/VodPlayerActivity.java"
text = VOD.read_text(encoding="utf-8")


def patch(old, new, label):
    global text
    if old not in text:
        raise SystemExit(f"v337 VOD patch mismatch: {label}")
    text = text.replace(old, new, 1)


def optional_patch(old, new, label):
    global text
    if old not in text:
        print(f"v337 optional patch skipped: {label}")
        return
    text = text.replace(old, new, 1)


def replace_method(signature, replacement, label):
    global text
    start = text.find(signature)
    if start < 0:
        raise SystemExit(f"v337 VOD method missing: {label}")
    brace = text.find("{", start)
    depth = 0
    end = -1
    for i in range(brace, len(text)):
        if text[i] == "{": depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                end = i + 1
                break
    if end < 0:
        raise SystemExit(f"v337 VOD method end missing: {label}")
    text = text[:start] + replacement + text[end:]

patch(
'''    private boolean alternateSourceAttempted;\n    private int attempt;\n''',
'''    private boolean alternateSourceAttempted;\n    private boolean containerRouteAttempted;\n    private int attempt;\n''', "VOD compatibility flags")

patch(
'''        PlaybackTransportFactory.warmUpCronet(this);\n        buildUi();\n''',
'''        PlaybackTransportFactory.warmUpCronet(this);\n        attempt = PlaybackRouteMemory.preferredRecoveryStep(this, kind, id);\n        buildUi();\n''', "learned VOD transport")

# v330 has already shortened these socket windows. Preserve the fast canonical
# path and make only compatibility/learned attempt 1 prefer Cronet.
patch(
'''        DataSource.Factory source = PlaybackTransportFactory.create(\n                this, false, network,\n                ultraHd() ? 5_000 : 3_500,\n                ultraHd() ? 12_000 : 8_000,\n                attempt, playbackReferer);\n''',
'''        boolean preferCronet = attempt == 1 && PlaybackTransportFactory.isCronetReady();\n        DataSource.Factory source = PlaybackTransportFactory.create(\n                this, preferCronet, network,\n                attempt == 0 ? (ultraHd() ? 5_000 : 3_500) : 3_500,\n                attempt == 0 ? (ultraHd() ? 12_000 : 8_000) : 8_000,\n                attempt, playbackReferer);\n''', "activate bounded VOD Cronet")

patch(
'''        player.setMediaItem(item.build(), Math.max(0, resumePosition));\n        player.prepare();\n''',
'''        engineView.setText(attempt == 1 && PlaybackTransportFactory.isCronetReady()\n                ? "Media3 • Cronet" : "Media3");\n        player.setMediaItem(item.build(), Math.max(0, resumePosition));\n        player.prepare();\n''', "VOD transport diagnostics")

# v330 already handles a direct-link resolve failure by restoring canonical and
# opening the no-extension path. Keep that known-good behavior rather than
# replacing it with another competing resolve-error ladder.
optional_patch(
'''                    if ("direct".equals(requestedVariant) && restoreCanonicalSource()) {\n                        if (!lifecycleStopped) openVlc(PlaybackPolicy.resolveErrorMessage(error));\n                        return;\n                    }\n                    showError(PlaybackPolicy.resolveErrorMessage(error));\n''',
'''                    if (("direct".equals(requestedVariant) || "no-extension".equals(requestedVariant))\n                            && restoreCanonicalSource()) {\n                        if (!lifecycleStopped) openVlc(PlaybackPolicy.resolveErrorMessage(error));\n                        return;\n                    }\n                    showError(PlaybackPolicy.resolveErrorMessage(error));\n''', "optional VOD resolve failure")

replace_method(
"    private void recover(String reason)",
'''    private void recover(String reason) {\n        main.removeCallbacks(startupTimeout);\n        savePosition();\n        if (usingVlc) {\n            showFinalPlaybackError(reason);\n            return;\n        }\n\n        boolean networkFailure = PlaybackPolicy.isNetworkFailure(reason)\n                && !PlaybackPolicy.isStartupTimeout(reason);\n\n        // DNS/HTTP/relay failure: provider direct route once. Attempt 1 uses\n        // Cronet when available, but safely falls back to platform HTTP.\n        if (networkFailure && !alternateSourceAttempted && !id.isEmpty()) {\n            alternateSourceAttempted = true;\n            sourceVariant = "direct";\n            attempt = 1;\n            releaseAllEngines();\n            resolvedUrl = "";\n            resolving = false;\n            resolve();\n            return;\n        }\n\n        // Startup, parser, codec or a failed direct source gets one neutral\n        // container route. Backend resolves no-extension and Media3 sniffs it.\n        if (!containerRouteAttempted && !id.isEmpty()) {\n            containerRouteAttempted = true;\n            if ("direct".equals(sourceVariant)) restoreCanonicalSource();\n            sourceVariant = "no-extension";\n            attempt = 1;\n            releaseAllEngines();\n            resolvedUrl = "";\n            resolving = false;\n            resolve();\n            return;\n        }\n\n        if ("direct".equals(sourceVariant) || "no-extension".equals(sourceVariant)) {\n            restoreCanonicalSource();\n        }\n        attempt = 2;\n        openVlc(reason);\n    }''', "adaptive VOD recovery")

patch(
'''    @Override public void onRenderedFirstFrame() {\n        if (usingVlc) return;\n        firstFrame = true;\n        spinner.setVisibility(View.GONE);\n        main.removeCallbacks(startupTimeout);\n        showControls();\n    }\n''',
'''    @Override public void onRenderedFirstFrame() {\n        if (usingVlc) return;\n        firstFrame = true;\n        spinner.setVisibility(View.GONE);\n        main.removeCallbacks(startupTimeout);\n        PlaybackRouteMemory.recordSuccess(this, kind, id, extension,\n                attempt == 1 && PlaybackTransportFactory.isCronetReady() ? "cronet" : "default-http");\n        PlaybackProfileManager.recordSuccess(this, kind, extension,\n                attempt == 1 && PlaybackTransportFactory.isCronetReady() ? "cronet" : "legacy7max");\n        showControls();\n    }\n''', "learn successful VOD route")

patch(
'''                if (!firstFrame) {\n                    firstFrame = true;\n                    spinner.setVisibility(View.GONE);\n                    main.removeCallbacks(startupTimeout);\n                    showControls();\n                }\n''',
'''                if (!firstFrame) {\n                    firstFrame = true;\n                    spinner.setVisibility(View.GONE);\n                    main.removeCallbacks(startupTimeout);\n                    PlaybackProfileManager.recordSuccess(this, kind, extension, "vlc");\n                    showControls();\n                }\n''', "learn LibVLC VOD compatibility")

patch(
'''        alternateSourceAttempted = false;\n        playbackReferer = "";\n''',
'''        alternateSourceAttempted = false;\n        containerRouteAttempted = false;\n        playbackReferer = "";\n''', "reset VOD compatibility")

VOD.write_text(text, encoding="utf-8")
print("v337 VOD compatibility applied: direct + no-extension + Cronet + first-frame learning + LibVLC")
