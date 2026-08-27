#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ACTIVITY = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player/SevenMaxActivity.java"

text = ACTIVITY.read_text(encoding="utf-8")


def replace_once(old, new, label):
    global text
    if old not in text:
        raise SystemExit(f"v334 catalog speed patch mismatch: {label}")
    text = text.replace(old, new, 1)

# Smaller first pages render immediately; the existing adapters continue local
# pagination as the user scrolls. Full preload remains unchanged in PackageImporter.
replace_once("    private static final int LIVE_PAGE = 140;\n    private static final int POSTER_PAGE = 80;",
             "    private static final int LIVE_PAGE = 72;\n    private static final int POSTER_PAGE = 48;",
             "first-page sizes")

# Never run COUNT(*) on the UI thread when entering Live.
replace_once(
'''        TextView count = BlofyUi.text(this, formatCount(database.count("live"), "قناة متاحة"), 12, BlofyUi.MUTED);''',
'''        TextView count = BlofyUi.text(this, "جارٍ تجهيز القنوات…", 12, BlofyUi.MUTED);''',
"live count placeholder")

# Build Live immediately, then populate categories/count from the local DB worker.
replace_once(
'''        List<BlofyModels.Category> categoryRows = new ArrayList<>();\n        List<BlofyModels.Category> allCategories = database.categories("live");\n        if (sportsMode) {\n            for (BlofyModels.Category category : allCategories) {\n                if (isSportsCategory(category.name)) categoryRows.add(category);\n            }\n        } else {\n            categoryRows.add(new BlofyModels.Category("", "الكل  •  " + database.count("live"), "live"));\n            categoryRows.addAll(allCategories);\n        }\n''',
'''        List<BlofyModels.Category> categoryRows = new ArrayList<>();\n        if (!sportsMode) categoryRows.add(new BlofyModels.Category("", "الكل", "live"));\n''',
"defer live categories")

replace_once(
'''        liveAdapter.reload(firstCategory, fallbackQuery);\n        if (!categoryRows.isEmpty()) focusItem(cats, 0);\n    }\n''',
'''        liveAdapter.reload(firstCategory, fallbackQuery);\n        if (!categoryRows.isEmpty()) focusItem(cats, 0);\n\n        final int liveOwner = screenGeneration;\n        submitCatalog(() -> {\n            int total = database.count("live");\n            List<BlofyModels.Category> loadedCategories = database.categories("live");\n            List<BlofyModels.Category> visibleCategories = new ArrayList<>();\n            if (sportsMode) {\n                for (BlofyModels.Category category : loadedCategories) {\n                    if (isSportsCategory(category.name)) visibleCategories.add(category);\n                }\n            } else {\n                visibleCategories.add(new BlofyModels.Category("", "الكل  •  " + total, "live"));\n                visibleCategories.addAll(loadedCategories);\n            }\n            main.post(() -> {\n                if (!isCurrentScreen(liveOwner) || !"live".equals(screen)) return;\n                count.setText(formatCount(total, "قناة متاحة"));\n                categoryRows.clear();\n                categoryRows.addAll(visibleCategories);\n                catAdapter.notifyDataSetChanged();\n                if (sportsMode && !categoryRows.isEmpty()) {\n                    liveAdapter.reload(categoryRows.get(0).id, "");\n                    focusItem(cats, 0);\n                }\n            });\n        });\n    }\n''',
"async live overview")

# Same treatment for Movies/Series: shell appears immediately and the DB worker
# supplies counts/categories without freezing remote navigation.
replace_once(
'''        TextView count = BlofyUi.text(this, formatCount(database.count(type),\n                "series".equals(type) ? "مسلسل" : "فيلم"), 12, BlofyUi.MUTED);''',
'''        TextView count = BlofyUi.text(this, "جارٍ تجهيز " + titleValue + "…", 12, BlofyUi.MUTED);''',
"catalog count placeholder")

replace_once(
'''        List<BlofyModels.Category> rows = new ArrayList<>();\n        rows.add(new BlofyModels.Category("", "الكل  •  " + database.count(type), type));\n        rows.addAll(database.categories(type));\n''',
'''        List<BlofyModels.Category> rows = new ArrayList<>();\n        rows.add(new BlofyModels.Category("", "الكل", type));\n''',
"defer catalog categories")

replace_once(
'''        adapter.reload("", "");\n        if (focusSearch) search.requestFocus(); else focusItem(cats, 0);\n    }\n''',
'''        adapter.reload("", "");\n        if (focusSearch) search.requestFocus(); else focusItem(cats, 0);\n\n        final int catalogOwner = screenGeneration;\n        submitCatalog(() -> {\n            int total = database.count(type);\n            List<BlofyModels.Category> loadedCategories = database.categories(type);\n            main.post(() -> {\n                if (!isCurrentScreen(catalogOwner) || !type.equals(screen)) return;\n                count.setText(formatCount(total, "series".equals(type) ? "مسلسل" : "فيلم"));\n                rows.clear();\n                rows.add(new BlofyModels.Category("", "الكل  •  " + total, type));\n                rows.addAll(loadedCategories);\n                catAdapter.notifyDataSetChanged();\n            });\n        });\n    }\n''',
"async catalog overview")

ACTIVITY.write_text(text, encoding="utf-8")
print("v334 catalog speed applied: non-blocking overview + smaller local first pages + existing lazy pagination")
