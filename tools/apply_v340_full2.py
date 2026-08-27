#!/usr/bin/env python3
import apply_v340_full as v340

_original = v340.replace_once


def tolerant_replace(path, old, new):
    try:
        _original(path, old, new)
    except SystemExit:
        # Cosmetic/legacy wording differs between generated player generations.
        # Keep core diagnostics/recovery/caching patches strict, but do not block
        # a build because a label/count line was already changed by an older layer.
        if "تعذر تشغيل القناة بعد المحاولة" in old:
            print("v340 optional diagnostic UI wording patch skipped")
            return
        if path.name == "SevenMaxActivity.java" and (
                "TextView packageName = BlofyUi.text" in old
                or "database.categories(\"live\")" in old):
            print("v340 optional catalog presentation patch skipped")
            return
        raise


v340.replace_once = tolerant_replace
v340.main()
