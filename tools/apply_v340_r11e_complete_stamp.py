#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
GRADLE = ROOT / "BLOFY-ANDROID-2026/app/build.gradle.kts"

text = GRADLE.read_text(encoding="utf-8")
text = re.sub(r'versionCode\s*=\s*\d+', 'versionCode = 1000355', text, count=1)
text = re.sub(r'versionName\s*=\s*"[^"]*"', 'versionName = "v340-full-stability-r11e-complete"', text, count=1)
GRADLE.write_text(text, encoding="utf-8")

final = GRADLE.read_text(encoding="utf-8")
for marker in (
    "versionCode = 1000355",
    'versionName = "v340-full-stability-r11e-complete"',
):
    if marker not in final:
        raise SystemExit(f"R11E complete stamp missing: {marker}")

print("R11E COMPLETE release stamped: versionCode=1000355 versionName=v340-full-stability-r11e-complete")
