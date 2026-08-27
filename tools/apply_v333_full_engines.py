#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player"


def read(path):
    return path.read_text(encoding="utf-8")


def write(path, text):
    path.write_text(text, encoding="utf-8")


def replace_once(text, old, new, label):
    if old not in text:
        raise SystemExit(f"v333 patch mismatch: {label}")
    return text.replace(old, new, 1)

# 1) Provider memory: remember successful LIVE container per playlist.
profile_path = JAVA / "PlaybackProfileManager.java"
profile = read(profile_path)
profile = replace_once(profile,
'''    private static final String KEY_FAIL_PREFIX = "fail:";\n''',
'''    private static final String KEY_FAIL_PREFIX = "fail:";\n    private static final String KEY_LIVE_EXT_PREFIX = "live_ext:";\n''', "live extension key")

profile = replace_once(profile,
'''    static void recordSuccess(Context context, String kind, String extension, String mode) {\n        if (mode == null || mode.isEmpty()) return;\n        PlaylistStore store = new PlaylistStore(context);\n        String playlistId = activePlaylistId(store);\n        String key = modeKey(playlistId, kind, extension);\n        prefs(context).edit().putString(key, mode).putInt(KEY_FAIL_PREFIX + key, 0).apply();\n    }\n''',
'''    static void recordSuccess(Context context, String kind, String extension, String mode) {\n        if (mode == null || mode.isEmpty()) return;\n        PlaylistStore store = new PlaylistStore(context);\n        String playlistId = activePlaylistId(store);\n        String key = modeKey(playlistId, kind, extension);\n        SharedPreferences.Editor editor = prefs(context).edit()\n                .putString(key, mode).putInt(KEY_FAIL_PREFIX + key, 0);\n        if ("live".equals(normalizeKind(kind))) {\n            String liveExt = normalizeLiveExtension(extension);\n            if (!liveExt.isEmpty()) editor.putString(KEY_LIVE_EXT_PREFIX + playlistId, liveExt);\n        }\n        editor.apply();\n    }\n\n    static String preferredLiveExtension(Context context, String fallback) {\n        PlaylistStore store = new PlaylistStore(context);\n        String playlistId = activePlaylistId(store);\n        String learned = prefs(context).getString(KEY_LIVE_EXT_PREFIX + playlistId, "");\n        return learned == null || learned.isEmpty() ? fallback : learned;\n    }\n\n    private static String normalizeLiveExtension(String extension) {\n        String ext = extension == null ? "" : extension.toLowerCase(Locale.US).replace(".", "");\n        if (ext.contains("m3u8") || ext.contains("hls")) return "m3u8";\n        if (ext.contains("ts") || ext.contains("mpegts")) return "ts";\n        return "";\n    }\n''', "record success and live extension memory")

profile = replace_once(profile,
'''            if (key.startsWith(KEY_MODE_PREFIX + playlistId + ":")\n                    || key.startsWith(KEY_FAIL_PREFIX + KEY_MODE_PREFIX + playlistId + ":")) {\n                editor.remove(key);\n            }\n''',
'''            if (key.startsWith(KEY_MODE_PREFIX + playlistId + ":")\n                    || key.startsWith(KEY_FAIL_PREFIX + KEY_MODE_PREFIX + playlistId + ":")\n                    || key.equals(KEY_LIVE_EXT_PREFIX + playlistId)) {\n                editor.remove(key);\n            }\n''', "clear learned live extension")
write(profile_path, profile)

# 2) LIVE engine: preserve explicit user TS/HLS setting, otherwise use learned provider extension.
player_path = JAVA / "PlayerActivity.java"
player = read(player_path)
player = replace_once(player,
'''    private String configuredExtension(String candidate) {\n        if (!isLiveKind(kind)) return candidate;\n        String mode = playerSetting(SettingsActivity.KEY_STREAM, "auto");\n        if ("ts".equals(mode)) return "ts";\n        if ("hls".equals(mode)) return "m3u8";\n        return candidate;\n    }\n''',
'''    private String configuredExtension(String candidate) {\n        if (!isLiveKind(kind)) return candidate;\n        String mode = playerSetting(SettingsActivity.KEY_STREAM, "auto");\n        if ("ts".equals(mode)) return "ts";\n        if ("hls".equals(mode)) return "m3u8";\n        return PlaybackProfileManager.preferredLiveExtension(this, candidate);\n    }\n''', "learned live extension")
write(player_path, player)

# 3) Full preload: keep all three sections complete before home.
importer_path = JAVA / "PackageImporter.java"
imp = read(importer_path)
imp = imp.replace("private static final long LEGACY_MIN_REQUEST_GAP_MS = 450L;",
                  "private static final long LEGACY_MIN_REQUEST_GAP_MS = 320L;", 1)
imp = replace_once(imp,
'''            importType("live", "القنوات المباشرة", 14, 42);\n            importType("movies", "الأفلام", 42, 69);\n            importType("series", "المسلسلات", 69, 94);\n''',
'''            importType("live", "القنوات المباشرة", 14, 40);\n            importType("movies", "الأفلام", 40, 67);\n            importType("series", "المسلسلات", 67, 94);\n''', "separate preload stages")

old_first = '''        JSONObject first = getWithRetry("/api/catalog?type=" + BlofyApi.encode(type)\n                + "&page=1&page_size=" + REQUESTED_PAGE_SIZE, true);\n        int total = Math.max(0, first.optInt("total", 0));\n        int pageSize = Math.max(1, first.optInt("pageSize", 60));\n\n        if (total == 0 && !"live".equals(type) && !categories.isEmpty()) {\n            importByCategories(type, label, categories, start, end);\n            return;\n        }\n'''
new_first = '''        JSONObject first;\n        try {\n            first = getWithRetry("/api/catalog?type=" + BlofyApi.encode(type)\n                    + "&page=1&page_size=" + REQUESTED_PAGE_SIZE, true);\n        } catch (Exception globalFailure) {\n            if (categories.isEmpty()) throw globalFailure;\n            emit(start + 1, "قراءة " + label,\n                    "المسار العام غير متوافق؛ الانتقال لقراءة التصنيفات كاملة");\n            importByCategories(type, label, categories, start + 1, end);\n            return;\n        }\n        int total = Math.max(0, first.optInt("total", 0));\n        int pageSize = Math.max(1, first.optInt("pageSize", 60));\n\n        if (total == 0 && !categories.isEmpty()) {\n            importByCategories(type, label, categories, start, end);\n            return;\n        }\n'''
imp = replace_once(imp, old_first, new_first, "global catalog fallback")

old_cat = '''            String base = "/api/catalog?type=" + BlofyApi.encode(type)\n                    + "&category=" + BlofyApi.encode(category.id)\n                    + "&page_size=" + REQUESTED_PAGE_SIZE;\n            JSONObject first = getWithRetry(base + "&page=1", true);\n            int total = Math.max(0, first.optInt("total", 0));\n            int pageSize = Math.max(1, first.optInt("pageSize", 60));\n            int pages = Math.max(1, (int) Math.ceil(total / (double) pageSize));\n            save(BlofyModels.Media.list(first, type));\n            for (int page = 2; page <= pages; page++) {\n                JSONObject response = getWithRetry(base + "&page=" + page, true);\n                save(BlofyModels.Media.list(response, type));\n            }\n'''
new_cat = '''            String base = "/api/catalog?type=" + BlofyApi.encode(type)\n                    + "&category=" + BlofyApi.encode(category.id)\n                    + "&page_size=" + REQUESTED_PAGE_SIZE;\n            try {\n                JSONObject first = getWithRetry(base + "&page=1", true);\n                int total = Math.max(0, first.optInt("total", 0));\n                int pageSize = Math.max(1, first.optInt("pageSize", 60));\n                int pages = Math.max(1, (int) Math.ceil(total / (double) pageSize));\n                save(BlofyModels.Media.list(first, type));\n                for (int page = 2; page <= pages; page++) {\n                    JSONObject response = getWithRetry(base + "&page=" + page, true);\n                    save(BlofyModels.Media.list(response, type));\n                }\n            } catch (Exception categoryFailure) {\n                emit(progress, "قراءة " + label,\n                        "تم تجاوز تصنيف غير متوافق " + (index + 1) + " من " + categories.size());\n            }\n'''
imp = replace_once(imp, old_cat, new_cat, "category tolerant preload")
write(importer_path, imp)

# 4) High monotonic version code so Android never treats v333 as a downgrade from v332.
gradle_path = ROOT / "BLOFY-ANDROID-2026/app/build.gradle.kts"
gradle = read(gradle_path)
gradle = gradle.replace("versionCode = 331", "versionCode = 1000333", 1)
if 'versionName = "v331"' in gradle:
    gradle = gradle.replace('versionName = "v331"', 'versionName = "v333-golden-r2"', 1)
else:
    import re
    gradle = re.sub(r'versionName\s*=\s*"[^"]+"', 'versionName = "v333-golden-r2"', gradle, count=1)
write(gradle_path, gradle)

print("v333 r2 applied: same engines, versionCode 1000333 for guaranteed upgrade compatibility")
