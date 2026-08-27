#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ACTIVITY = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player/SevenMaxActivity.java"
text = ACTIVITY.read_text(encoding="utf-8")


def patch(old, new, label):
    global text
    if old not in text:
        raise SystemExit(f"v335 ui cache patch mismatch: {label}")
    text = text.replace(old, new, 1)

# Keep the complete catalog preloaded locally, but render smaller first pages so
# TV/receiver UIs become interactive immediately. Pagination remains local-only.
patch('''    private static final int LIVE_PAGE = 140;\n    private static final int POSTER_PAGE = 80;\n''',
'''    private static final int LIVE_PAGE = 70;\n    private static final int POSTER_PAGE = 40;\n''', 'smaller first render pages')

patch('''    private volatile int screenGeneration;\n    private volatile boolean destroyed;\n''',
'''    private volatile int screenGeneration;\n    private volatile boolean destroyed;\n    private final Map<String, Integer> catalogCountCache = new java.util.concurrent.ConcurrentHashMap<>();\n    private final Map<String, List<BlofyModels.Category>> catalogCategoryCache =\n            new java.util.concurrent.ConcurrentHashMap<>();\n    private volatile boolean catalogSummaryReady;\n    private volatile boolean catalogSummaryWarming;\n''', 'summary cache fields')

patch('''        database = new CatalogDatabase(this);\n        api = new BlofyApi(this);\n        images = new ImageLoader(api);\n        showHome();\n''',
'''        database = new CatalogDatabase(this);\n        api = new BlofyApi(this);\n        images = new ImageLoader(api);\n        warmCatalogSummary();\n        showHome();\n''', 'warm summary on activity launch')

# Replace synchronous count/category reads on the UI thread. The cache is warmed
# once per activity/playlist from the already-preloaded local SQLite catalog.
text = text.replace('database.count("live")', 'fastCount("live")')
text = text.replace('database.count("movies")', 'fastCount("movies")')
text = text.replace('database.count("series")', 'fastCount("series")')
text = text.replace('database.count(type)', 'fastCount(type)')
text = text.replace('database.categories("live")', 'fastCategories("live")')
text = text.replace('database.categories(type)', 'fastCategories(type)')

marker = '''    private boolean isCurrentScreen(int ownerGeneration) {\n        return !destroyed && ownerGeneration == screenGeneration;\n    }\n\n'''
if marker not in text:
    raise SystemExit('v335 ui cache patch mismatch: helper insertion marker')
helpers = '''    private boolean isCurrentScreen(int ownerGeneration) {\n        return !destroyed && ownerGeneration == screenGeneration;\n    }\n\n    private int fastCount(String type) {\n        Integer cached = catalogCountCache.get(type);\n        if (cached != null) return cached;\n        warmCatalogSummary();\n        return 0;\n    }\n\n    private List<BlofyModels.Category> fastCategories(String type) {\n        List<BlofyModels.Category> cached = catalogCategoryCache.get(type);\n        if (cached != null) return new ArrayList<>(cached);\n        warmCatalogSummary();\n        return new ArrayList<>();\n    }\n\n    private void warmCatalogSummary() {\n        if (destroyed || catalogSummaryReady || catalogSummaryWarming) return;\n        catalogSummaryWarming = true;\n        boolean submitted = submitCatalog(() -> {\n            Map<String, Integer> counts = new LinkedHashMap<>();\n            Map<String, List<BlofyModels.Category>> categories = new LinkedHashMap<>();\n            String[] types = {"live", "movies", "series"};\n            try {\n                for (String type : types) {\n                    counts.put(type, database.count(type));\n                    categories.put(type, new ArrayList<>(database.categories(type)));\n                }\n            } catch (Throwable ignored) {\n                // A screen can still load its first media page even if summary warming fails.\n            }\n            main.post(() -> {\n                if (destroyed) return;\n                catalogCountCache.putAll(counts);\n                catalogCategoryCache.putAll(categories);\n                catalogSummaryReady = counts.size() == 3 && categories.size() == 3;\n                catalogSummaryWarming = false;\n            });\n        });\n        if (!submitted) catalogSummaryWarming = false;\n    }\n\n'''
text = text.replace(marker, helpers, 1)

ACTIVITY.write_text(text, encoding="utf-8")
print("v335 UI cache applied: async local summary + smaller first-page rendering; full preload preserved")
