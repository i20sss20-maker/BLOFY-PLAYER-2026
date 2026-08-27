#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player"
PLAYER = JAVA / "PlayerActivity.java"
POLICY = JAVA / "PlaybackPolicy.java"


def read(path):
    return path.read_text(encoding="utf-8")


def write(path, text):
    path.write_text(text, encoding="utf-8")


def replace_once(path, old, new, label):
    text = read(path)
    if old not in text:
        raise SystemExit(f"v334 patch mismatch: {label}")
    write(path, text.replace(old, new, 1))

# -----------------------------------------------------------------------------
# Preserve the complete v331 chain exactly:
# legacy7max -> Cronet -> compat -> direct/no-extension -> TS/HLS -> LibVLC.
# Add only per-item memory so mixed channels do not poison a whole provider.
# -----------------------------------------------------------------------------
replace_once(PLAYER,
'''        transportMode = PlaybackProfileManager.preferredMode(this, kind, extension);\n        recoveryStep = preferredRecoveryStep();\n''',
'''        transportMode = PlaybackRouteMemory.preferredMode(this, kind, id,\n                PlaybackProfileManager.preferredMode(this, kind, extension));\n        recoveryStep = preferredRecoveryStep();\n''', "initial item route")

# The same provider selection appears on channel switch/manual retry.
player = read(PLAYER)
player = player.replace(
'''        transportMode = PlaybackProfileManager.preferredMode(this, kind, extension);\n        recoveryStep = preferredRecoveryStep();''',
'''        transportMode = PlaybackRouteMemory.preferredMode(this, kind, id,\n                PlaybackProfileManager.preferredMode(this, kind, extension));\n        recoveryStep = preferredRecoveryStep();''')
write(PLAYER, player)

replace_once(PLAYER,
'''    private String configuredExtension(String candidate) {\n        if (!isLiveKind(kind)) return candidate;\n        String mode = playerSetting(SettingsActivity.KEY_STREAM, "auto");\n        if ("ts".equals(mode)) return "ts";\n        if ("hls".equals(mode)) return "m3u8";\n        return PlaybackProfileManager.preferredLiveExtension(this, candidate);\n    }\n''',
'''    private String configuredExtension(String candidate) {\n        if (!isLiveKind(kind)) return candidate;\n        String mode = playerSetting(SettingsActivity.KEY_STREAM, "auto");\n        if ("ts".equals(mode)) return "ts";\n        if ("hls".equals(mode)) return "m3u8";\n        String provider = PlaybackProfileManager.preferredLiveExtension(this, candidate);\n        return PlaybackRouteMemory.preferredLiveExtension(this, id, provider);\n    }\n''', "per-channel TS/HLS memory")

replace_once(PLAYER,
'''    private void rememberSuccessfulTransport() {\n        PlaybackProfileManager.recordSuccess(this, kind, extension, activeTransportName());\n    }\n''',
'''    private void rememberSuccessfulTransport() {\n        PlaybackRouteMemory.recordSuccess(this, kind, id, extension, activeTransportName());\n        // Provider-wide learning stays useful for VOD/Series. Live is deliberately\n        // per-channel because one playlist can mix transports and codecs.\n        if (!isLive()) {\n            PlaybackProfileManager.recordSuccess(this, kind, extension, activeTransportName());\n        }\n    }\n''', "per-item success memory")

replace_once(PLAYER,
'''        PlaybackProfileManager.recordFailure(this, kind, extension, transportMode);\n\n        if (PlaybackProfileManager.MODE_LEGACY.equals(transportMode)) {\n''',
'''        if (!isLive()) {\n            PlaybackProfileManager.recordFailure(this, kind, extension, transportMode);\n        }\n\n        if (PlaybackProfileManager.MODE_LEGACY.equals(transportMode)) {\n''', "do not poison provider from one live failure")

replace_once(PLAYER,
'''        Log.i(TAG, "compat-first-frame ext=" + extension + " ms=" + firstFrameMs);\n        PlaybackProfileManager.recordSuccess(this, kind, extension, PlaybackProfileManager.MODE_VLC);\n    }\n''',
'''        Log.i(TAG, "compat-first-frame ext=" + extension + " ms=" + firstFrameMs);\n        PlaybackRouteMemory.recordSuccess(this, kind, id, extension, PlaybackProfileManager.MODE_VLC);\n        if (!isLive()) {\n            PlaybackProfileManager.recordSuccess(this, kind, extension, PlaybackProfileManager.MODE_VLC);\n        }\n    }\n''', "remember per-item VLC success")

# DNS/hostname failures should move to the next existing v331 route immediately.
replace_once(POLICY,
'''        return value.contains("HTTP") || value.contains("IO_") || value.contains("NETWORK")\n                || value.contains("CONNECTION") || value.contains("TIMEOUT")\n                || value.contains("BAD_HTTP_STATUS");\n''',
'''        return value.contains("HTTP") || value.contains("IO_") || value.contains("NETWORK")\n                || value.contains("CONNECTION") || value.contains("TIMEOUT")\n                || value.contains("BAD_HTTP_STATUS") || value.contains("EAI_NODATA")\n                || value.contains("DNS") || value.contains("UNKNOWNHOST")\n                || value.contains("UNKNOWN_HOST") || value.contains("NO ADDRESS ASSOCIATED")\n                || value.contains("UNABLE TO RESOLVE HOST");\n''', "DNS fast failure")

print("v334 applied: v331 transport chain preserved + per-item route memory + fast DNS failure")
