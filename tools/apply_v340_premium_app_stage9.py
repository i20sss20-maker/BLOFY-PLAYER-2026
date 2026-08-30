#!/usr/bin/env python3
from pathlib import Path
import runpy

# Stage9 wrapper intentionally delegates to the reconstruction-safe implementation.
replacement = Path(__file__).with_name("apply_v340_premium_app_stage9_replacement.py")
if not replacement.exists():
    raise SystemExit("stage9 replacement script missing")
runpy.run_path(str(replacement), run_name="__main__")

# Final reconstruction hygiene gate: catches duplicate insertions, stale version identity,
# unconditional-return regressions, and lost playback invariants before Gradle starts.
cleanliness = Path(__file__).with_name("verify_v340_cleanliness.py")
if not cleanliness.exists():
    raise SystemExit("v340 cleanliness verifier missing")
runpy.run_path(str(cleanliness), run_name="__main__")
