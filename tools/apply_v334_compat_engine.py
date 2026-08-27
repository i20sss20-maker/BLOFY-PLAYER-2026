#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player"
PLAYER = JAVA / "PlayerActivity.java"
PROFILE = JAVA / "PlaybackProfileManager.java"
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
# 1) Keep the full v331 transport chain, but make LIVE learning per-stream.
#    Movies/Series deliberately retain provider/content-family learning.
# -----------------------------------------------------------------------------
replace_once(PROFILE,
'''    private static final String KEY_FAIL_PREFIX = "fail:";\n    private static final String KEY_LIVE_EXT_PREFIX = "live_ext:";\n''',
'''    private static final String KEY_FAIL_PREFIX = "fail:";\n    private static final String KEY_LIVE_EXT_PREFIX = "live_ext:";\n    private static final String KEY_STREAM_MODE_PREFIX = "stream_mode:";\n    private static final String KEY_STREAM_FAIL_PREFIX = "stream_fail:";\n    private static final String KEY_STREAM_EXT_PREFIX = "stream_ext:";\n''', "per-stream preference keys")

replace_once(PROFILE,
'''    static String preferredMode(Context context, String kind, String extension) {\n        PlaylistStore store = new PlaylistStore(context);\n        String playlistId = activePlaylistId(store);\n        SharedPreferences prefs = prefs(context);\n        String key = modeKey(playlistId, kind, extension);\n        return prefs.getString(key, MODE_LEGACY);\n    }\n''',
'''    static String preferredMode(Context context, String kind, String extension) {\n        return preferredMode(context, kind, extension, "");\n    }\n\n    static String preferredMode(Context context, String kind, String extension, String streamId) {\n        PlaylistStore store = new PlaylistStore(context);\n        String playlistId = activePlaylistId(store);\n        SharedPreferences prefs = prefs(context);\n        if ("live".equals(normalizeKind(kind)) && streamId != null && !streamId.isEmpty()) {\n            String streamKey = streamModeKey(playlistId, streamId);\n            String learned = prefs.getString(streamKey, "");\n            if (learned != null && !learned.isEmpty()) return learned;\n        }\n        String key = modeKey(playlistId, kind, extension);\n        return prefs.getString(key, MODE_LEGACY);\n    }\n''', "per-stream preferred mode")

replace_once(PROFILE,
'''    static void recordSuccess(Context context, String kind, String extension, String mode) {\n        if (mode == null || mode.isEmpty()) return;\n        PlaylistStore store = new PlaylistStore(context);\n        String playlistId = activePlaylistId(store);\n        String key = modeKey(playlistId, kind, extension);\n        SharedPreferences.Editor editor = prefs(context).edit()\n                .putString(key, mode).putInt(KEY_FAIL_PREFIX + key, 0);\n        if ("live".equals(normalizeKind(kind))) {\n            String liveExt = normalizeLiveExtension(extension);\n            if (!liveExt.isEmpty()) editor.putString(KEY_LIVE_EXT_PREFIX + playlistId, liveExt);\n        }\n        editor.apply();\n    }\n''',
'''    static void recordSuccess(Context context, String kind, String extension, String mode) {\n        recordSuccess(context, kind, extension, "", mode);\n    }\n\n    static void recordSuccess(Context context, String kind, String extension, String streamId, String mode) {\n        if (mode == null || mode.isEmpty()) return;\n        PlaylistStore store = new PlaylistStore(context);\n        String playlistId = activePlaylistId(store);\n        SharedPreferences.Editor editor = prefs(context).edit();\n        if ("live".equals(normalizeKind(kind)) && streamId != null && !streamId.isEmpty()) {\n            String streamKey = streamModeKey(playlistId, streamId);\n            editor.putString(streamKey, mode).putInt(KEY_STREAM_FAIL_PREFIX + streamKey, 0);\n            String liveExt = normalizeLiveExtension(extension);\n            if (!liveExt.isEmpty()) editor.putString(streamExtKey(playlistId, streamId), liveExt);\n        } else {\n            String key = modeKey(playlistId, kind, extension);\n            editor.putString(key, mode).putInt(KEY_FAIL_PREFIX + key, 0);\n            if ("live".equals(normalizeKind(kind))) {\n                String liveExt = normalizeLiveExtension(extension);\n                if (!liveExt.isEmpty()) editor.putString(KEY_LIVE_EXT_PREFIX + playlistId, liveExt);\n            }\n        }\n        editor.apply();\n    }\n''', "per-stream success learning")

replace_once(PROFILE,
'''    static void recordFailure(Context context, String kind, String extension, String mode) {\n        PlaylistStore store = new PlaylistStore(context);\n        String playlistId = activePlaylistId(store);\n        String key = modeKey(playlistId, kind, extension);\n        SharedPreferences prefs = prefs(context);\n        int failures = prefs.getInt(KEY_FAIL_PREFIX + key, 0) + 1;\n        SharedPreferences.Editor editor = prefs.edit().putInt(KEY_FAIL_PREFIX + key, failures);\n        if (failures >= 2 && mode != null && mode.equals(prefs.getString(key, MODE_LEGACY))) {\n            editor.remove(key).putInt(KEY_FAIL_PREFIX + key, 0);\n        }\n        editor.apply();\n    }\n''',
'''    static void recordFailure(Context context, String kind, String extension, String mode) {\n        recordFailure(context, kind, extension, "", mode);\n    }\n\n    static void recordFailure(Context context, String kind, String extension, String streamId, String mode) {\n        PlaylistStore store = new PlaylistStore(context);\n        String playlistId = activePlaylistId(store);\n        SharedPreferences prefs = prefs(context);\n        if ("live".equals(normalizeKind(kind)) && streamId != null && !streamId.isEmpty()) {\n            String key = streamModeKey(playlistId, streamId);\n            int failures = prefs.getInt(KEY_STREAM_FAIL_PREFIX + key, 0) + 1;\n            SharedPreferences.Editor editor = prefs.edit().putInt(KEY_STREAM_FAIL_PREFIX + key, failures);\n            if (failures >= 2 && mode != null && mode.equals(prefs.getString(key, ""))) {\n                editor.remove(key).remove(streamExtKey(playlistId, streamId))\n                        .putInt(KEY_STREAM_FAIL_PREFIX + key, 0);\n            }\n            editor.apply();\n            return;\n        }\n        String key = modeKey(playlistId, kind, extension);\n        int failures = prefs.getInt(KEY_FAIL_PREFIX + key, 0) + 1;\n        SharedPreferences.Editor editor = prefs.edit().putInt(KEY_FAIL_PREFIX + key, failures);\n        if (failures >= 2 && mode != null && mode.equals(prefs.getString(key, MODE_LEGACY))) {\n            editor.remove(key).putInt(KEY_FAIL_PREFIX + key, 0);\n        }\n        editor.apply();\n    }\n''', "per-stream failure learning")

replace_once(PROFILE,
'''    static String preferredLiveExtension(Context context, String fallback) {\n        PlaylistStore store = new PlaylistStore(context);\n        String playlistId = activePlaylistId(store);\n        String learned = prefs(context).getString(KEY_LIVE_EXT_PREFIX + playlistId, "");\n        return learned == null || learned.isEmpty() ? fallback : learned;\n    }\n''',
'''    static String preferredLiveExtension(Context context, String fallback) {\n        return preferredLiveExtension(context, "", fallback);\n    }\n\n    static String preferredLiveExtension(Context context, String streamId, String fallback) {\n        PlaylistStore store = new PlaylistStore(context);\n        String playlistId = activePlaylistId(store);\n        SharedPreferences prefs = prefs(context);\n        if (streamId != null && !streamId.isEmpty()) {\n            String streamExt = prefs.getString(streamExtKey(playlistId, streamId), "");\n            if (streamExt != null && !streamExt.isEmpty()) return streamExt;\n        }\n        String learned = prefs.getString(KEY_LIVE_EXT_PREFIX + playlistId, "");\n        return learned == null || learned.isEmpty() ? fallback : learned;\n    }\n''', "per-stream live extension")

replace_once(PROFILE,
'''    private static String modeKey(String playlistId, String kind, String extension) {\n        return KEY_MODE_PREFIX + playlistId + ":" + normalizeKind(kind) + ":" + family(extension);\n    }\n''',
'''    private static String modeKey(String playlistId, String kind, String extension) {\n        return KEY_MODE_PREFIX + playlistId + ":" + normalizeKind(kind) + ":" + family(extension);\n    }\n\n    private static String streamModeKey(String playlistId, String streamId) {\n        return KEY_STREAM_MODE_PREFIX + playlistId + ":live:" + streamId;\n    }\n\n    private static String streamExtKey(String playlistId, String streamId) {\n        return KEY_STREAM_EXT_PREFIX + playlistId + ":live:" + streamId;\n    }\n''', "per-stream key helpers")

replace_once(PROFILE,
'''            if (key.startsWith(KEY_MODE_PREFIX + playlistId + ":")\n                    || key.startsWith(KEY_FAIL_PREFIX + KEY_MODE_PREFIX + playlistId + ":")\n                    || key.equals(KEY_LIVE_EXT_PREFIX + playlistId)) {\n                editor.remove(key);\n            }\n''',
'''            if (key.startsWith(KEY_MODE_PREFIX + playlistId + ":")\n                    || key.startsWith(KEY_FAIL_PREFIX + KEY_MODE_PREFIX + playlistId + ":")\n                    || key.startsWith(KEY_STREAM_MODE_PREFIX + playlistId + ":")\n                    || key.startsWith(KEY_STREAM_FAIL_PREFIX + KEY_STREAM_MODE_PREFIX + playlistId + ":")\n                    || key.startsWith(KEY_STREAM_EXT_PREFIX + playlistId + ":")\n                    || key.equals(KEY_LIVE_EXT_PREFIX + playlistId)) {\n                editor.remove(key);\n            }\n''', "clear per-stream profile")

# PlayerActivity: preserve v331's transportMode state machine, only scope LIVE
# learning to the current stream id.
replace_once(PLAYER,
'''        transportMode = PlaybackProfileManager.preferredMode(this, kind, extension);\n        recoveryStep = preferredRecoveryStep();\n''',
'''        transportMode = PlaybackProfileManager.preferredMode(this, kind, extension, id);\n        recoveryStep = preferredRecoveryStep();\n''', "initial per-stream transport")

# Occurs on live switch and manual retry. Replace every remaining exact call.
player = read(PLAYER)
player = player.replace(
    'transportMode = PlaybackProfileManager.preferredMode(this, kind, extension);',
    'transportMode = PlaybackProfileManager.preferredMode(this, kind, extension, id);')
player = player.replace(
    'PlaybackProfileManager.recordSuccess(this, kind, extension, activeTransportName());',
    'PlaybackProfileManager.recordSuccess(this, kind, extension, id, activeTransportName());')
player = player.replace(
    'PlaybackProfileManager.recordFailure(this, kind, extension, transportMode);',
    'PlaybackProfileManager.recordFailure(this, kind, extension, id, transportMode);')
player = player.replace(
    'PlaybackProfileManager.recordSuccess(this, kind, extension, PlaybackProfileManager.MODE_VLC);',
    'PlaybackProfileManager.recordSuccess(this, kind, extension, id, PlaybackProfileManager.MODE_VLC);')
player = player.replace(
    'return PlaybackProfileManager.preferredLiveExtension(this, candidate);',
    'return PlaybackProfileManager.preferredLiveExtension(this, id, candidate);')
write(PLAYER, player)

# -----------------------------------------------------------------------------
# 2) DNS/hostname failures are hard network failures: move immediately to the
#    next route instead of waiting for the startup watchdog.
# -----------------------------------------------------------------------------
replace_once(POLICY,
'''        return value.contains("HTTP") || value.contains("IO_") || value.contains("NETWORK")\n                || value.contains("CONNECTION") || value.contains("TIMEOUT")\n                || value.contains("BAD_HTTP_STATUS");\n''',
'''        return value.contains("HTTP") || value.contains("IO_") || value.contains("NETWORK")\n                || value.contains("CONNECTION") || value.contains("TIMEOUT")\n                || value.contains("BAD_HTTP_STATUS") || value.contains("EAI_NODATA")\n                || value.contains("DNS") || value.contains("UNKNOWNHOST")\n                || value.contains("UNKNOWN_HOST") || value.contains("NO ADDRESS ASSOCIATED")\n                || value.contains("UNABLE TO RESOLVE HOST");\n''', "DNS fast failure")

print("v334 applied: full v331 transport chain preserved + per-stream Live learning + DNS fast-failure")
