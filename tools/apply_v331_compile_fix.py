from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PATH = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player/MainActivity.java"
text = PATH.read_text(encoding="utf-8")

old = '                database.setMetadata("playback_profile", playbackAnalysis.summary());\n'
if old not in text:
    raise SystemExit("v331 compile fix mismatch: obsolete setMetadata call not found")

# PlaybackProfileManager already persists the provider profile independently.
# CatalogDatabase writes its visible playback_profile metadata atomically when
# PackageImporter commits the staged catalog, so do not call a non-existent API here.
text = text.replace(old, "", 1)
PATH.write_text(text, encoding="utf-8")
print("v331 compile fix applied")
