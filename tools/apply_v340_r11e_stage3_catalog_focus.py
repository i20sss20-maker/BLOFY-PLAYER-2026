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

# Locate showHome structurally. Earlier reconstruction layers can add statements
# between screen assignment and root.removeAllViews(), so do not depend on one
# exact multi-line sequence.
def method_bounds(text, signature, next_signature):
    start = text.find(signature)
    if start < 0:
        return -1, -1
    end = text.find(next_signature, start)
    if end < 0:
        end = text.find("\n    private ", start + len(signature))
    return start, end

if "r11e3-home-cache-hit" not in s:
    start, end = method_bounds(s, "    private void showHome() {", "\n    private TextView homeTile(")
    if start < 0 or end < 0:
        raise SystemExit("R11E3: showHome bounds missing")
    block = s[start:end]
    remove_rel = block.find("root.removeAllViews();")
    if remove_rel < 0:
        raise SystemExit("R11E3: showHome root reset missing")
    insert_at = start + remove_rel + len("root.removeAllViews();")
    inject = '''
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
    s = s[:insert_at] + inject + s[insert_at:]

if "r11e3CachedHomePage = root.getChildAt(0);" not in s:
    start, end = method_bounds(s, "    private void showHome() {", "\n    private TextView homeTile(")
    if start < 0 or end < 0:
        raise SystemExit("R11E3: showHome bounds missing")
    block = s[start:end]
    # Prefer the launcher page attach. Fall back to the last root.addView in showHome.
    exact = "root.addView(page, match());"
    attach_rel = block.rfind(exact)
    if attach_rel >= 0:
        insert_at = start + attach_rel + len(exact)
    else:
        matches = list(re.finditer(r'root\.addView\([^;]+\);', block))
        if not matches:
            raise SystemExit("R11E3: home root attach missing")
        insert_at = start + matches[-1].end()
    s = s[:insert_at] + "\n        r11e3CachedHomePage = root.getChildAt(0);" + s[insert_at:]

# Save the focused launcher before another screen detaches the cached home.
if "r11e3CachedHomeFocus = getCurrentFocus();" not in s:
    # Inject into screen transitions that clear root, but never into showHome itself.
    cursor = 0
    injected = False
    while True:
        idx = s.find("root.removeAllViews();", cursor)
        if idx < 0:
            break
        home_start = s.rfind("    private void showHome() {", 0, idx)
        next_private = s.rfind("\n    private ", 0, idx)
        inside_home = home_start >= 0 and home_start >= next_private
        if not inside_home:
            guard = '''if (r11e3CachedHomePage != null && getCurrentFocus() != null) {
            r11e3CachedHomeFocus = getCurrentFocus();
        }
        '''
            s = s[:idx] + guard + s[idx:]
            injected = True
            break
        cursor = idx + 1
    if not injected:
        # Safe fallback: remember focus at the moment a home tile is activated.
        click_anchor = "        tile.setOnClickListener(v -> action.run());\n"
        if click_anchor in s:
            repl = '''        tile.setOnClickListener(v -> {
            r11e3CachedHomeFocus = tile;
            action.run();
        });
'''
            s = s.replace(click_anchor, repl, 1)
        else:
            print("R11E3: no transition focus anchor; default home focus remains safe")

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
