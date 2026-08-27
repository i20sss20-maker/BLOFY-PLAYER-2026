#!/usr/bin/env python3
import apply_v340_full as v340

_original = v340.replace_once


def tolerant_replace(path, old, new):
    try:
        _original(path, old, new)
    except SystemExit:
        # The fatal-error wording differs slightly between the legacy player
        # generations. Diagnostics are still recorded in the player callbacks;
        # skipping only this cosmetic line must never block the full build.
        if "تعذر تشغيل القناة بعد المحاولة" in old:
            print("v340 optional diagnostic UI wording patch skipped")
            return
        raise


v340.replace_once = tolerant_replace
v340.main()
