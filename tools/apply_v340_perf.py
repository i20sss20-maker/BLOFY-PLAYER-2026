#!/usr/bin/env python3
from pathlib import Path

# v340 R3: hot catalog pagination layer.
ROOT = Path(__file__).resolve().parents[1]
SEVEN = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player/SevenMaxActivity.java"
text = SEVEN.read_text(encoding="utf-8")

old = '''                List<BlofyModels.Media> next = type.isEmpty() && !favorites && !history
                        ? database.searchAll(selectedQuery, POSTER_PAGE, offset)
                        : database.media(type, selectedCategory, selectedQuery,
                                favorites, history, POSTER_PAGE, offset);
'''
new = '''                List<BlofyModels.Media> next;
                if (!type.isEmpty() && !favorites && !history && offset == 0
                        && (selectedQuery == null || selectedQuery.isEmpty())) {
                    next = selectedCategory.isEmpty()
                            ? CatalogUiCache.firstPage(database, type, POSTER_PAGE)
                            : CatalogUiCache.categoryPage(database, type, selectedCategory, POSTER_PAGE);
                    if (next.isEmpty()) {
                        next = database.media(type, selectedCategory, "",
                                false, false, POSTER_PAGE, 0);
                    }
                } else {
                    next = type.isEmpty() && !favorites && !history
                            ? database.searchAll(selectedQuery, POSTER_PAGE, offset)
                            : database.media(type, selectedCategory, selectedQuery,
                                    favorites, history, POSTER_PAGE, offset);
                }
                if (offset == 0 && !selectedCategory.isEmpty() && !next.isEmpty()) {
                    CatalogUiCache.rememberCategoryPage(database, type, selectedCategory, next);
                }
'''
if old not in text:
    raise SystemExit("v340 perf patch mismatch: catalog pagination")
text = text.replace(old, new, 1)
SEVEN.write_text(text, encoding="utf-8")
print("v340 perf applied: hot first/category pages + SQLite fallback")
