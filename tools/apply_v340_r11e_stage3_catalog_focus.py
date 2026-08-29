#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "BLOFY-ANDROID-2026/app"
JAVA = APP / "src/main/java/tv/blofy/player"
SEVEN = JAVA / "SevenMaxActivity.java"
GRADLE = APP / "build.gradle.kts"

s = SEVEN.read_text(encoding="utf-8")

# Keep the home hierarchy hot in memory while opening Movies/Series/Live so
# returning home is immediate instead of rebuilding the whole launcher tree.
if "private View r11e3CachedHomePage;" not in s:
    anchor = "    private volatile boolean destroyed;\n"
    if anchor not in s:
        raise SystemExit("R11E3: state anchor missing")
    s = s.replace(anchor, anchor +
        "    private View r11e3CachedHomePage;\n"
        "    private View r11e3CachedHomeFocus;\n", 1)

if "r11e3-home-cache-hit" not in s:
    # Stage2/older reconstruction layers may insert lines around screen assignment,
    # so patch showHome structurally instead of depending on one exact block.
    pat = re.compile(r'(private void showHome\(\) \{.*?screen\s*=\s*"home";\s*\n\s*root\.removeAllViews\(\);)', re.S)
    m = pat.search(s)
    if not m:
        raise SystemExit("R11E3: showHome anchor missing")
    inject = m.group(1) + '''
        if (r11e3CachedHomePage != null) {
            root.addView(r11e3CachedHomePage, match());
            View restore = r11e3CachedHomeFocus;
            main.post(() -> {
                if (!destroyed && "home".equals(screen) && restore != null && restore.isFocusable()) {
                    restore.requestFocus();
                }
            });
            PlaybackDiagnostics.marker(this, "r11e3-home-cache-hit", "ui", "home", "", "cache", "instant-restore");
            return;
        }'''
    s = s[:m.start()] + inject + s[m.end():]

if "r11e3CachedHomePage = page;" not in s:
    pat = re.compile(r'(private void showHome\(\) \{.*?root\.addView\(page, match\(\)\);\s*\n)(\s*live\.requestFocus\(\);)', re.S)
    m = pat.search(s)
    if not m:
        raise SystemExit("R11E3: home cache store anchor missing")
    s = s[:m.start()] + m.group(1) + "        r11e3CachedHomePage = page;\n" + m.group(2) + s[m.end():]

# Save the exact focused launcher before another screen detaches the cached home.
if "r11e3CachedHomeFocus = getCurrentFocus();" not in s:
    # Insert on the first non-home shell transition pattern after stopHeroRotation.
    pat = re.compile(r'(stopHeroRotation\(\);\s*\n\s*screenGeneration\+\+;\s*\n)(\s*root\.removeAllViews\(\);)')
    m = pat.search(s)
    if m:
        repl = m.group(1) + '''        if ("home".equals(screen) && getCurrentFocus() != null) {
            r11e3CachedHomeFocus = getCurrentFocus();
        }
''' + m.group(2)
        s = s[:m.start()] + repl + s[m.end():]
    else:
        # Safe fallback: focus still restores to default launcher if no structural shell anchor exists.
        print("R11E3: shell cache-focus anchor absent; continuing with default launcher restore")

# Reduce focus animation work on weak TV boxes; retain the existing visual design
# on stronger devices.
if "r11e3ReducedFocusMotion" not in s:
    old = '''        tile.setOnFocusChangeListener((view, focusedNow) -> view.animate()
                .scaleX(focusedNow ? 1.025f : 1f)
                .scaleY(focusedNow ? 1.025f : 1f)
                .setDuration(110L).start());'''
    new = '''        final boolean r11e3ReducedFocusMotion = DeviceCapabilityProfile.detect(this).usesReducedPerformance();
        tile.setOnFocusChangeListener((view, focusedNow) -> {
            view.animate().cancel();
            if (r11e3ReducedFocusMotion) {
                view.setScaleX(1f);
                view.setScaleY(1f);
            } else {
                view.animate()
                        .scaleX(focusedNow ? 1.018f : 1f)
                        .scaleY(focusedNow ? 1.018f : 1f)
                        .setDuration(75L).start();
            }
        });'''
    if old not in s:
        raise SystemExit("R11E3: home focus animation anchor missing")
    s = s.replace(old, new, 1)

# Reuse RecyclerView child holders more aggressively for very large catalogs.
# This changes only the visible window; the full package remains stored locally.
if "r11e3-catalog-window" not in s:
    insert_at = s.rfind("\n}")
    if insert_at < 0:
        raise SystemExit("R11E3: class end missing")
    helper = r'''

    private void r11e3TuneCatalogRecycler(RecyclerView list, boolean posters) {
        if (list == null) return;
        list.setItemAnimator(null);
        list.setHasFixedSize(true);
        list.setItemViewCacheSize(posters ? 18 : 12);
        list.setDrawingCacheEnabled(false);
        PlaybackDiagnostics.marker(this, "r11e3-catalog-window", "ui", screen, "",
                posters ? "poster" : "list", "cache=" + (posters ? 18 : 12));
    }
'''
    s = s[:insert_at] + helper + s[insert_at:]

    s = re.sub(r'(RecyclerView\s+(\w+)\s*=\s*new RecyclerView\(this\);\n\s*\2\.setLayoutManager\([^\n]+\);)',
               lambda m: m.group(1) + '\n        r11e3TuneCatalogRecycler(' + m.group(2) + ', false);',
               s)
    s = re.sub(r'(RecyclerView\s+(\w+)\s*=\s*new RecyclerView\(this\);\n\s*\2\.setLayoutManager\(new GridLayoutManager\([^\n]+\);\n\s*)r11e3TuneCatalogRecycler\(\2, false\);',
               lambda m: m.group(1) + 'r11e3TuneCatalogRecycler(' + m.group(2) + ', true);',
               s)

SEVEN.write_text(s, encoding="utf-8")

g = GRADLE.read_text(encoding="utf-8")
g = re.sub(r'versionCode\s*=\s*\d+', 'versionCode = 1000348', g, count=1)
g = re.sub(r'versionName\s*=\s*"[^"]*"', 'versionName = "v340-full-stability-r11e-stage3"', g, count=1)
GRADLE.write_text(g, encoding="utf-8")

checks = {
    SEVEN: ["r11e3CachedHomePage", "r11e3-home-cache-hit", "r11e3ReducedFocusMotion", "r11e3TuneCatalogRecycler", "r11e3-catalog-window"],
    GRADLE: ["versionCode = 1000348", "v340-full-stability-r11e-stage3"],
}
for path, markers in checks.items():
    text = path.read_text(encoding="utf-8")
    for marker in markers:
        if marker not in text:
            raise SystemExit(f"R11E3 invariant missing {path.name}: {marker}")

print("R11E stage3 applied: instant home restore + focus-state retention + reduced TV focus motion + catalog RecyclerView tuning")
