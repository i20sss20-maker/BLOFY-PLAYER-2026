#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "BLOFY-ANDROID-2026/app"
JAVA = APP / "src/main/java/tv/blofy/player"
DB = JAVA / "CatalogDatabase.java"
GRADLE = APP / "build.gradle.kts"

s = DB.read_text(encoding="utf-8")

if "private final java.util.Map<String, Integer> r11e8CountCache" not in s:
    anchor = "    private String importSource;\n"
    if anchor not in s:
        raise SystemExit("R11E8: DB field anchor missing")
    fields = '''    private final java.util.Map<String, Integer> r11e8CountCache = new java.util.HashMap<>();\n    private final java.util.Map<String, java.util.List<BlofyModels.Category>> r11e8CategoryCache = new java.util.HashMap<>();\n'''
    s = s.replace(anchor, anchor + fields, 1)

# Replace categories() with a source-scoped immutable-ish copy cache.
if "r11e8CategoryCache.get(cacheKey)" not in s:
    pattern = re.compile(r'''    List<BlofyModels\.Category> categories\(String type\) \{.*?\n    \}\n\n    List<BlofyModels\.Media> media''', re.S)
    m = pattern.search(s)
    if not m:
        raise SystemExit("R11E8: categories method anchor missing")
    replacement = '''    List<BlofyModels.Category> categories(String type) {\n        String cacheKey = activeSource() + "|" + type;\n        synchronized (r11e8CategoryCache) {\n            java.util.List<BlofyModels.Category> cached = r11e8CategoryCache.get(cacheKey);\n            if (cached != null) return new ArrayList<>(cached);\n        }\n        List<BlofyModels.Category> result = new ArrayList<>();\n        try (Cursor cursor = getReadableDatabase().query("categories", new String[]{"id", "name"},\n                "source_id=? AND type=?", new String[]{activeSource(), type}, null, null,\n                "sort_order ASC")) {\n            while (cursor.moveToNext()) result.add(new BlofyModels.Category(\n                    cursor.getString(0), cursor.getString(1), type));\n        }\n        synchronized (r11e8CategoryCache) {\n            r11e8CategoryCache.put(cacheKey, new ArrayList<>(result));\n        }\n        return result;\n    }\n\n    List<BlofyModels.Media> media'''
    s = s[:m.start()] + replacement + s[m.end():]

# Replace count() with a source-scoped memory cache.
if "r11e8CountCache.get(cacheKey)" not in s:
    pattern = re.compile(r'''    int count\(String type\) \{.*?\n    \}\n\n    boolean isFavorite''', re.S)
    m = pattern.search(s)
    if not m:
        raise SystemExit("R11E8: count method anchor missing")
    replacement = '''    int count(String type) {\n        String cacheKey = activeSource() + "|" + type;\n        synchronized (r11e8CountCache) {\n            Integer cached = r11e8CountCache.get(cacheKey);\n            if (cached != null) return cached;\n        }\n        int value = 0;\n        try (Cursor cursor = getReadableDatabase().rawQuery(\n                "SELECT COUNT(*) FROM media WHERE source_id=? AND type=?",\n                new String[]{activeSource(), type})) {\n            if (cursor.moveToFirst()) value = cursor.getInt(0);\n        }\n        synchronized (r11e8CountCache) { r11e8CountCache.put(cacheKey, value); }\n        return value;\n    }\n\n    boolean isFavorite'''
    s = s[:m.start()] + replacement + s[m.end():]

# Cache invalidation helper.
if "private void r11e8InvalidateCatalogCache()" not in s:
    insert_at = s.rfind("\n}")
    if insert_at < 0:
        raise SystemExit("R11E8: class end missing")
    helper = '''\n\n    private void r11e8InvalidateCatalogCache() {\n        synchronized (r11e8CountCache) { r11e8CountCache.clear(); }\n        synchronized (r11e8CategoryCache) { r11e8CategoryCache.clear(); }\n        PlaybackDiagnostics.marker(context, "r11e8-catalog-cache-invalidated", "ui", "", "", "catalog", "changed");\n    }\n'''
    s = s[:insert_at] + helper + s[insert_at:]

# Invalidate after atomic package commit, fresh import, and source deletion.
if "r11e8-cache-after-commit" not in s:
    anchor = "        CatalogScope.activate(context, cleanSource);\n"
    if anchor not in s:
        raise SystemExit("R11E8: commit end anchor missing")
    s = s.replace(anchor, anchor + '''        r11e8InvalidateCatalogCache();\n        PlaybackDiagnostics.marker(context, "r11e8-cache-after-commit", "ui", "", "", "catalog", cleanSource);\n''', 1)

# beginFreshImport has a distinctive sync-state marker.
if "r11e8InvalidateCatalogCache(); // fresh-import" not in s:
    anchor = '            putMetadata(database, "sync_state", "refresh_required");\n'
    if anchor not in s:
        raise SystemExit("R11E8: fresh import anchor missing")
    s = s.replace(anchor, anchor + '            r11e8InvalidateCatalogCache(); // fresh-import\n', 1)

# deleteSource cleanup.
if "r11e8InvalidateCatalogCache(); // source-delete" not in s:
    anchor = "        PlaybackProgress.clearScope(context, source);\n"
    # use last occurrence (deleteSource), not clearPersonalState
    pos = s.rfind(anchor)
    if pos < 0:
        raise SystemExit("R11E8: delete source anchor missing")
    end = pos + len(anchor)
    s = s[:end] + "        r11e8InvalidateCatalogCache(); // source-delete\n" + s[end:]

DB.write_text(s, encoding="utf-8")

g = GRADLE.read_text(encoding="utf-8")
g = re.sub(r'versionCode\s*=\s*\d+', 'versionCode = 1000353', g, count=1)
g = re.sub(r'versionName\s*=\s*"[^"]*"', 'versionName = "v340-full-stability-r11e-stage8"', g, count=1)
GRADLE.write_text(g, encoding="utf-8")

checks = {
    DB: ["r11e8CountCache", "r11e8CategoryCache", "r11e8InvalidateCatalogCache", "r11e8-cache-after-commit"],
    GRADLE: ["versionCode = 1000353", "v340-full-stability-r11e-stage8"],
}
for path, markers in checks.items():
    text = path.read_text(encoding="utf-8")
    for marker in markers:
        if marker not in text:
            raise SystemExit(f"R11E8 invariant missing {path.name}: {marker}")

print("R11E stage8 applied: source-scoped count/category caches + atomic invalidation without DB version bump")
