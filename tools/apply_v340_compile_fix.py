#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SEVEN = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player/SevenMaxActivity.java"
text = SEVEN.read_text(encoding="utf-8")

# The perf patch assigns `next` through more than one branch, therefore it is not
# effectively-final and Java refuses to capture it in the main-thread lambda.
# Do this structurally instead of depending on an exact long source block.
anchor = "CatalogUiCache.rememberCategoryPage(database, type, selectedCategory, next);"
pos = text.find(anchor)
if pos < 0:
    raise SystemExit("v340 compile fix: catalog cache anchor not found")

post = text.find("main.post(() -> {", pos)
if post < 0 or post - pos > 2500:
    raise SystemExit("v340 compile fix: catalog UI lambda not found")

insert = "                final List<BlofyModels.Media> delivered = next;\n"
if "final List<BlofyModels.Media> delivered = next;" not in text[pos:post]:
    text = text[:post] + insert + text[post:]
    post += len(insert)

# Limit replacements to this UI-delivery lambda so unrelated `next` variables stay untouched.
window_end = min(len(text), post + 2200)
window = text[post:window_end]
replacements = {
    "next.size() < POSTER_PAGE": "delivered.size() < POSTER_PAGE",
    "!next.isEmpty()": "!delivered.isEmpty()",
    "rows.addAll(next)": "rows.addAll(delivered)",
    "notifyItemRangeInserted(offset, next.size())": "notifyItemRangeInserted(offset, delivered.size())",
}
for old, new in replacements.items():
    window = window.replace(old, new)
text = text[:post] + window + text[window_end:]

required = [
    "final List<BlofyModels.Media> delivered = next;",
    "delivered.size() < POSTER_PAGE",
    "rows.addAll(delivered)",
]
for token in required:
    if token not in text:
        raise SystemExit("v340 compile fix invariant missing: " + token)

SEVEN.write_text(text, encoding="utf-8")
print("v340 compile fix applied: catalog result safely captured for UI lambda")
