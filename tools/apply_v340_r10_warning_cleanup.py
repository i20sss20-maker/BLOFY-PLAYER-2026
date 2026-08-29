#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
GRADLE = ROOT / "BLOFY-ANDROID-2026/app/build.gradle.kts"

g = GRADLE.read_text(encoding="utf-8")

# Native libraries from VLC/Media3 FFmpeg are already prebuilt/stripped upstream.
# Tell AGP not to attempt a second strip pass, which only produced noisy warnings.
if "keepDebugSymbols" not in g:
    anchor = '''    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
'''
    block = '''    packaging {
        jniLibs {
            keepDebugSymbols += setOf(
                "**/libc++_shared.so",
                "**/libffmpegJNI.so",
                "**/libvlc.so",
                "**/libvlcjni.so"
            )
        }
    }

'''
    if anchor not in g:
        raise SystemExit("r10: compileOptions anchor missing")
    g = g.replace(anchor, anchor + "\n" + block, 1)

# Final release identity.
g, c1 = re.subn(r'versionCode\s*=\s*\d+', 'versionCode = 1000343', g, count=1)
g, c2 = re.subn(r'versionName\s*=\s*"[^"]*"', 'versionName = "v340-full-stability-r10"', g, count=1)
if c1 != 1 or c2 != 1:
    raise SystemExit("r10: version stamp failed")

GRADLE.write_text(g, encoding="utf-8")

# Invariants.
out = GRADLE.read_text(encoding="utf-8")
for marker in [
    'versionCode = 1000343',
    'versionName = "v340-full-stability-r10"',
    '"**/libffmpegJNI.so"',
    '"**/libvlc.so"',
    '"**/libvlcjni.so"',
    '"**/libc++_shared.so"',
]:
    if marker not in out:
        raise SystemExit("r10: missing invariant: " + marker)

print("R10 warning cleanup applied: native strip noise removed + release identity stamped")
