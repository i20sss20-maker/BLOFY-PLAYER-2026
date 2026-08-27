#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player"
IMPORTER = JAVA / "PackageImporter.java"
SEVEN = JAVA / "SevenMaxActivity.java"


def read(path):
    return path.read_text(encoding="utf-8")


def write(path, text):
    path.write_text(text, encoding="utf-8")


def replace_once(text, old, new, label):
    if old not in text:
        raise SystemExit("v335 cache patch mismatch: " + label)
    return text.replace(old, new, 1)

# 100% must mean the first visible pages are already in RAM, not merely SQLite.
imp = read(IMPORTER)
imp = replace_once(imp,
'''            String profile = database.metadata("playback_profile", "Media3 مباشر");\n            emit(100, "البيانات جاهزة", "تم فتح النسخة المحفوظة على الجهاز بدون إعادة تحميل");\n            return new Result(cachedLive, cachedMovies, cachedSeries, profile);\n''',
'''            String profile = database.metadata("playback_profile", "Media3 مباشر");\n            emit(98, "تجهيز الواجهة", "تحضير أول القوائم من النسخة المحفوظة");\n            CatalogUiCache.warm(database);\n            emit(100, "البيانات جاهزة", "تم فتح النسخة المحفوظة على الجهاز بدون إعادة تحميل");\n            return new Result(cachedLive, cachedMovies, cachedSeries, profile);\n''', "warm cached package")
imp = replace_once(imp,
'''            database.commitStagedImport(sourceIdentity, session.serverName, session.kind, profile);\n            emit(99, "فتح BLOFY PLAYER", "تم الحفظ بنجاح");\n            int live = database.count("live");\n            int movies = database.count("movies");\n            int series = database.count("series");\n''',
'''            database.commitStagedImport(sourceIdentity, session.serverName, session.kind, profile);\n            emit(97, "تجهيز الواجهة", "تحضير التصنيفات وأول القوائم للفتح الفوري");\n            CatalogUiCache.warm(database);\n            emit(99, "فتح BLOFY PLAYER", "تم الحفظ والتجهيز بنجاح");\n            int live = CatalogUiCache.count(database, "live");\n            int movies = CatalogUiCache.count(database, "movies");\n            int series = CatalogUiCache.count(database, "series");\n''', "warm fresh package")
write(IMPORTER, imp)

seven = read(SEVEN)
# Counts/categories become memory reads after importer reaches 100%.
seven = seven.replace('database.count(', 'CatalogUiCache.count(database, ')
seven = seven.replace('database.categories(', 'CatalogUiCache.categories(database, ')

# Live first page: render immediately from RAM, then continue SQLite pagination at offset 140.
live_anchor = '''            String selectedCategory = category;\n            String selectedQuery = query;\n            boolean submitted = submitCatalog(() -> {\n'''
live_insert = '''            String selectedCategory = category;\n            String selectedQuery = query;\n            if (offset == 0 && selectedCategory.isEmpty() && selectedQuery.isEmpty()) {\n                List<BlofyModels.Media> cached = CatalogUiCache.firstPage(database, "live", LIVE_PAGE);\n                if (!cached.isEmpty()) {\n                    loading = false;\n                    rows.addAll(cached);\n                    notifyDataSetChanged();\n                    if (cached.size() < LIVE_PAGE) exhausted = true;\n                    if (firstPageLoaded != null) {\n                        Runnable callback = firstPageLoaded;\n                        firstPageLoaded = null;\n                        callback.run();\n                    }\n                    return;\n                }\n            }\n            boolean submitted = submitCatalog(() -> {\n'''
seven = replace_once(seven, live_anchor, live_insert, "live first-page RAM cache")

# Poster first page: same fast path for Movies/Series only. Search/favorites/history remain async DB.
poster_anchor = '''            String selectedCategory = category;\n            String selectedQuery = query;\n            boolean submitted = submitCatalog(() -> {\n                if (!isCurrentScreen(ownerGeneration)) return;\n                List<BlofyModels.Media> next = type.isEmpty() && !favorites && !history\n'''
poster_insert = '''            String selectedCategory = category;\n            String selectedQuery = query;\n            if (offset == 0 && !type.isEmpty() && !favorites && !history\n                    && selectedCategory.isEmpty() && selectedQuery.isEmpty()) {\n                List<BlofyModels.Media> cached = CatalogUiCache.firstPage(database, type, POSTER_PAGE);\n                if (!cached.isEmpty()) {\n                    loading = false;\n                    rows.addAll(cached);\n                    notifyDataSetChanged();\n                    if (cached.size() < POSTER_PAGE) exhausted = true;\n                    if (firstPageLoaded != null) {\n                        Runnable callback = firstPageLoaded;\n                        firstPageLoaded = null;\n                        callback.run();\n                    }\n                    return;\n                }\n            }\n            boolean submitted = submitCatalog(() -> {\n                if (!isCurrentScreen(ownerGeneration)) return;\n                List<BlofyModels.Media> next = type.isEmpty() && !favorites && !history\n'''
seven = replace_once(seven, poster_anchor, poster_insert, "poster first-page RAM cache")
write(SEVEN, seven)

print("v335 applied: 100% ready-state cache + instant first pages + cached counts/categories")
