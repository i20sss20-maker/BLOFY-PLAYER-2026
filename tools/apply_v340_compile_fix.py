#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SEVEN = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player/SevenMaxActivity.java"
text = SEVEN.read_text(encoding="utf-8")

old = '''                if (offset == 0 && !selectedCategory.isEmpty() && !next.isEmpty()) {
                    CatalogUiCache.rememberCategoryPage(database, type, selectedCategory, next);
                }
                if (!isCurrentScreen(ownerGeneration)) return;
                main.post(() -> {
                    if (!isCurrentScreen(ownerGeneration) || token != generation
                            || !selectedCategory.equals(category)
                            || !selectedQuery.equals(query)) return;
                    loading = false;
                    if (next.size() < POSTER_PAGE) exhausted = true;
                    if (!next.isEmpty()) {
                        rows.addAll(next);
                        if (offset == 0) notifyDataSetChanged();
                        else notifyItemRangeInserted(offset, next.size());
                    }
'''
new = '''                if (offset == 0 && !selectedCategory.isEmpty() && !next.isEmpty()) {
                    CatalogUiCache.rememberCategoryPage(database, type, selectedCategory, next);
                }
                final List<BlofyModels.Media> delivered = next;
                if (!isCurrentScreen(ownerGeneration)) return;
                main.post(() -> {
                    if (!isCurrentScreen(ownerGeneration) || token != generation
                            || !selectedCategory.equals(category)
                            || !selectedQuery.equals(query)) return;
                    loading = false;
                    if (delivered.size() < POSTER_PAGE) exhausted = true;
                    if (!delivered.isEmpty()) {
                        rows.addAll(delivered);
                        if (offset == 0) notifyDataSetChanged();
                        else notifyItemRangeInserted(offset, delivered.size());
                    }
'''

if old not in text:
    raise SystemExit("v340 compile fix anchor not found")
text = text.replace(old, new, 1)
SEVEN.write_text(text, encoding="utf-8")
print("v340 compile fix applied: catalog result is final before UI lambda")
