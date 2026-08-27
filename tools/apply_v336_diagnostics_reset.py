#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player"
PROFILE = JAVA / "PlaybackProfileManager.java"
ROUTES = JAVA / "PlaybackRouteMemory.java"
SETTINGS = JAVA / "SettingsActivity.java"

profile = PROFILE.read_text(encoding="utf-8")
if "static void clearCurrent(Context context)" not in profile:
    marker = '''    private static void putDefaultMode(SharedPreferences.Editor editor, SharedPreferences prefs,\n'''
    if marker not in profile:
        raise SystemExit("v336 diagnostics patch mismatch: profile insertion marker")
    method = '''    static void clearCurrent(Context context) {\n        PlaylistStore store = new PlaylistStore(context);\n        String playlistId = activePlaylistId(store);\n        SharedPreferences preferences = prefs(context);\n        clearPlaylistModes(preferences, playlistId);\n        preferences.edit().remove(KEY_HOST_PREFIX + playlistId).apply();\n    }\n\n'''
    profile = profile.replace(marker, method + marker, 1)
    PROFILE.write_text(profile, encoding="utf-8")

routes = ROUTES.read_text(encoding="utf-8")
if "static void clearSource(Context context)" not in routes:
    marker = '''    static void recordSuccess(Context context, String kind, String streamId,\n'''
    if marker not in routes:
        raise SystemExit("v336 diagnostics patch mismatch: route insertion marker")
    method = '''    static void clearSource(Context context) {\n        String prefix = playlist(context) + ":";\n        SharedPreferences preferences = prefs(context);\n        SharedPreferences.Editor editor = preferences.edit();\n        for (String key : preferences.getAll().keySet()) {\n            if ((key.startsWith(EXT) || key.startsWith(STEP)) && key.contains(prefix)) {\n                editor.remove(key);\n            }\n        }\n        editor.apply();\n    }\n\n'''
    routes = routes.replace(marker, method + marker, 1)
    ROUTES.write_text(routes, encoding="utf-8")

settings = SETTINGS.read_text(encoding="utf-8")
old_check = '''        addGridSetting(grid, gridAction("✓  فحص التشغيل", "Media3 + VLC + FFmpeg", () ->\n                ToastBridge.show(this, "المحركات جاهزة • Media3 + VLC + FFmpeg")));\n'''
new_check = '''        addGridSetting(grid, gridAction("✓  فحص التشغيل", "Media3 + VLC + FFmpeg", () -> {\n            String liveMode = PlaybackProfileManager.preferredMode(this, "live", "ts");\n            String vodMode = PlaybackProfileManager.preferredMode(this, "movies", "mp4");\n            String cronet = PlaybackTransportFactory.isCronetReady() ? "Cronet جاهز" : "Cronet احتياطي";\n            ToastBridge.show(this, "Media3 + VLC + FFmpeg • " + cronet\n                    + " • Live " + liveMode + " • VOD " + vodMode);\n        }));\n'''
if old_check in settings:
    settings = settings.replace(old_check, new_check, 1)
elif "Live \" + liveMode" not in settings:
    raise SystemExit("v336 diagnostics patch mismatch: playback check")

old_reset = '''        addGridSetting(grid, gridAction("⟲  استعادة التلقائي", "إلغاء التعديلات", () -> {\n            prefs.edit().clear().apply();\n            buildGrid();\n        }));\n'''
new_reset = '''        addGridSetting(grid, gridAction("⟲  استعادة التلقائي", "إلغاء التعديلات", () -> {\n            prefs.edit().clear().apply();\n            PlaybackRouteMemory.clearSource(this);\n            PlaybackProfileManager.clearCurrent(this);\n            ToastBridge.show(this, "تمت استعادة الوضع التلقائي ومسح مسارات التوافق المتعلمة");\n            buildGrid();\n        }));\n'''
if old_reset in settings:
    settings = settings.replace(old_reset, new_reset, 1)
elif "PlaybackRouteMemory.clearSource(this)" not in settings:
    raise SystemExit("v336 diagnostics patch mismatch: auto reset")

SETTINGS.write_text(settings, encoding="utf-8")
print("v336 diagnostics/reset applied: learned route reset + transport diagnostics")
