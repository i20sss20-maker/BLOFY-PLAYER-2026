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

# Cronet existed in the app but PlayerActivity always passed preferCronet=false.
# Use it only on bounded compatibility attempts (recoveryStep == 1), preserving
# the proven default HTTP path for sources that already work.
patch(PLAYER,
'''    private DataSource.Factory createDataSourceFactory() {\n        // Avoid an identical queued retry while the Cronet provider is still\n        // installing. The compatibility fallback is a different decoder/stack.\n        return PlaybackTransportFactory.create(this, false, network,\n                15_000, 30_000, recoveryStep, playbackReferer);\n    }\n''',
'''    private DataSource.Factory createDataSourceFactory() {\n        // The canonical path stays on platform HTTP. A bounded compatibility\n        // attempt may use Cronet when the provider is ready; if unavailable the\n        // factory safely falls back to platform HTTP.\n        boolean preferCronet = recoveryStep == 1 && PlaybackTransportFactory.isCronetReady();\n        return PlaybackTransportFactory.create(this, preferCronet, network,\n                recoveryStep == 0 ? 8_000 : 4_000,\n                recoveryStep == 0 ? 20_000 : 10_000,\n                recoveryStep, playbackReferer);\n    }\n''', "activate Cronet on bounded retries")

patch(PLAYER,
'''    private String activeTransportName() {\n        return usingVlc ? "libvlc" : "default-http";\n    }\n''',
'''    private String activeTransportName() {\n        if (usingVlc) return "libvlc";\n        return recoveryStep == 1 && PlaybackTransportFactory.isCronetReady()\n                ? "cronet" : "default-http";\n    }\n''', "transport diagnostics")

# Treat DNS/hostname failures as fast network failures so the state machine can
# skip a dead direct/redirect host instead of waiting for the long watchdog.
patch(POLICY,
'''        return value.contains("HTTP") || value.contains("IO_") || value.contains("NETWORK")\n                || value.contains("CONNECTION") || value.contains("TIMEOUT")\n                || value.contains("BAD_HTTP_STATUS");\n''',
'''        return value.contains("HTTP") || value.contains("IO_") || value.contains("NETWORK")\n                || value.contains("CONNECTION") || value.contains("TIMEOUT")\n                || value.contains("BAD_HTTP_STATUS") || value.contains("EAI_NODATA")\n                || value.contains("DNS") || value.contains("UNKNOWNHOST")\n                || value.contains("UNKNOWN_HOST") || value.contains("NO ADDRESS ASSOCIATED")\n                || value.contains("UNABLE TO RESOLVE HOST");\n''', "DNS fast failure classification")

print("v334 adaptive compatibility transport applied: canonical default HTTP + bounded Cronet + fast DNS failure")
