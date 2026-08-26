from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
path = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player/SevenMaxActivity.java"
text = path.read_text(encoding="utf-8")
old = '        nav.add(homeSideItem("⌕", "بحث", false, this::showSearch));\n'
new = '        nav.add(homeSideItem("⌕", "بحث", false, () -> showCatalog("movies", false)));\n'
if old not in text:
    raise SystemExit("v330 compile fix mismatch: search nav")
text = text.replace(old, new, 1)
path.write_text(text, encoding="utf-8")
print("v330 compile fix applied")
