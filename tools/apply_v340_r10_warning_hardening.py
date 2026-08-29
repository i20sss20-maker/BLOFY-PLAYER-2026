#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
GRADLE = ROOT / "BLOFY-ANDROID-2026/app/build.gradle.kts"

g = GRADLE.read_text(encoding="utf-8")

# The native libraries from VLC/Media3 FFmpeg are already supplied in their
# distributable form. AGP previously attempted to strip them, failed, then
# packaged the exact same files. Mark them explicitly so the build does not
# perform a pointless failing strip attempt.
if "keepDebugSymbols" not in g:
    anchor = '''    buildFeatures {
        buildConfig = true
    }
'''
    block = '''    buildFeatures {
        buildConfig = true
    }

    packaging {
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
        raise SystemExit("r10: buildFeatures anchor missing")
    g = g.replace(anchor, block, 1)

# Final R10 identity.
g, c1 = re.subn(r'versionCode\s*=\s*\d+', 'versionCode = 1000343', g, count=1)
g, c2 = re.subn(r'versionName\s*=\s*"[^"]*"', 'versionName = "v340-full-stability-r10"', g, count=1)
if c1 != 1 or c2 != 1:
    raise SystemExit("r10: version stamp failed")

GRADLE.write_text(g, encoding="utf-8")

# Release-time invariants.
final = GRADLE.read_text(encoding="utf-8")
for marker in [
    'versionCode = 1000343',
    'versionName = "v340-full-stability-r10"',
    'keepDebugSymbols',
    '"**/libffmpegJNI.so"',
    '"**/libvlc.so"',
    '"**/libvlcjni.so"',
]:
    if marker not in final:
        raise SystemExit("r10: native/release invariant missing: " + marker)

print("R10 warning hardening applied: explicit native packaging + clean release identity")
