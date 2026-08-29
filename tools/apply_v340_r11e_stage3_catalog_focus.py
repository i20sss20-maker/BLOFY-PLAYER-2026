#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "BLOFY-ANDROID-2026/app"
JAVA = APP / "src/main/java/tv/blofy/player"
SEVEN = JAVA / "SevenMaxActivity.java"
GRADLE = APP / "build.gradle.kts"

s = SEVEN.read_text(encoding="utf-8")

# R11E3 deliberately changes only UI state/rendering. No DB schema/version changes here.
if "private View r11e3CachedHomePage;" not in s:
    class_anchor = re.search(r'public\s+final\s+class\s+SevenMaxActivity\s+extends\s+Activity\s*\{', s)
    if not class_anchor:
        raise SystemExit("R11E3: SevenMaxActivity class anchor missing")
    pos = class_anchor.end()
    s = s[:pos] + "\n    private View r11e3CachedHomePage;\n    private View r11e3CachedHomeFocus;\n" + s[pos:]


def method_region(text: str, method_name: str):
    # Find the method signature, then match braces so reconstruction formatting cannot break us.
    m = re.search(r'(?m)^\s*private\s+void\s+' + re.escape(method_name) + r'\s*\([^)]*\)\s*\{', text)
    if not m:
        return -1, -1
    brace = text.find('{', m.start(), m.end())
    depth = 0
    in_string = False
    quote = ''
    escaped = False
    i = brace
    while i < len(text):
        ch = text[i]
        if in_string:
            if escaped:
                escaped = False
            elif ch == '\\':
                escaped = True
            elif ch == quote:
                in_string = False
        else:
            if ch in ('"', "'"):
                in_string = True
                quote = ch
            elif ch == '{':
                depth += 1
            elif ch == '}':
                depth -= 1
                if depth == 0:
                    return m.start(), i + 1
        i += 1
    return -1, -1


if "r11e3-home-cache-hit" not in s:
    start, end = method_region(s, "showHome")
    if start < 0:
        raise SystemExit("R11E3: showHome method missing")
    block = s[start:end]
    reset = re.search(r'root\s*\.\s*removeAllViews\s*\(\s*\)\s*;', block)
    if not reset:
        raise SystemExit("R11E3: showHome root reset missing")
    pos = start + reset.end()
    inject = '''
        if (r11e3CachedHomePage != null) {
            android.view.ViewParent oldParent = r11e3CachedHomePage.getParent();
            if (oldParent instanceof ViewGroup) ((ViewGroup) oldParent).removeView(r11e3CachedHomePage);
            root.addView(r11e3CachedHomePage, match());
            final View restore = r11e3CachedHomeFocus;
            main.post(() -> {
                if (!destroyed && "home".equals(screen) && restore != null && restore.isFocusable()) {
                    restore.requestFocus();
                }
            });
            PlaybackDiagnostics.marker(this, "r11e3-home-cache-hit", "ui", "home", "", "cache", "instant-restore");
            return;
        }'''
    s = s[:pos] + inject + s[pos:]

if "r11e3CachedHomePage = page;" not in s:
    start, end = method_region(s, "showHome")
    if start < 0:
        raise SystemExit("R11E3: showHome region missing after cache injection")
    block = s[start:end]
    attach = re.search(r'root\s*\.\s*addView\s*\(\s*page\s*,\s*match\s*\(\s*\)\s*\)\s*;', block)
    if not attach:
        raise SystemExit("R11E3: home page attach missing")
    pos = start + attach.end()
    s = s[:pos] + "\n        r11e3CachedHomePage = page;" + s[pos:]

# Remember which launcher opened the child screen so Back restores exactly that focus.
if "r11e3CachedHomeFocus = tile;" not in s:
    click = re.search(r'tile\s*\.\s*setOnClickListener\s*\(v\s*->\s*action\s*\.\s*run\s*\(\s*\)\s*\)\s*;', s)
    if click:
        repl = '''tile.setOnClickListener(v -> {
            r11e3CachedHomeFocus = tile;
            action.run();
        });'''
        s = s[:click.start()] + repl + s[click.end():]
    else:
        click2 = re.search(r'tile\s*\.\s*setOnClickListener\s*\(v\s*->\s*\{', s)
        if not click2:
            raise SystemExit("R11E3: homeTile click anchor missing")
        s = s[:click2.end()] + "\n            r11e3CachedHomeFocus = tile;" + s[click2.end():]

# Keep focus responsive on weak receivers; avoid queued scale animations on rapid D-pad input.
if "r11e3ReducedFocusMotion" not in s:
    pattern = re.compile(
        r'tile\.setOnFocusChangeListener\(\(view,\s*focusedNow\)\s*->\s*view\.animate\(\)\s*'
        r'\.scaleX\(focusedNow\s*\?\s*1\.025f\s*:\s*1f\)\s*'
        r'\.scaleY\(focusedNow\s*\?\s*1\.025f\s*:\s*1f\)\s*'
        r'\.setDuration\(110L\)\.start\(\)\);', re.S)
    m = pattern.search(s)
    if not m:
        raise SystemExit("R11E3: home focus animation anchor missing")
    new = '''final boolean r11e3ReducedFocusMotion = DeviceCapabilityProfile.detect(this).usesReducedPerformance();
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
    s = s[:m.start()] + new + s[m.end():]

# Recycler tuning: rendering-window optimization only; all catalog rows remain in SQLite.
if "private void r11e3TuneCatalogRecycler(" not in s:
    pos = s.rfind("\n}")
    if pos < 0:
        raise SystemExit("R11E3: class end missing")
    helper = r'''

    private void r11e3TuneCatalogRecycler(RecyclerView list, boolean posters) {
        if (list == null) return;
        list.setItemAnimator(null);
        list.setHasFixedSize(true);
        list.setItemViewCacheSize(posters ? 18 : 12);
        PlaybackDiagnostics.marker(this, "r11e3-catalog-window", "ui", screen, "",
                posters ? "poster" : "list", "cache=" + (posters ? 18 : 12));
    }
'''
    s = s[:pos] + helper + s[pos:]

if "r11e3-catalog-window" not in s:
    raise SystemExit("R11E3: recycler helper marker missing")

# Wire each RecyclerView once. Determine grid/list from nearby layout manager assignment.
rv_pattern = re.compile(r'RecyclerView\s+(\w+)\s*=\s*new RecyclerView\(this\);')
matches = list(rv_pattern.finditer(s))
for m in reversed(matches):
    name = m.group(1)
    tail = s[m.end():m.end() + 900]
    if f'r11e3TuneCatalogRecycler({name},' in tail:
        continue
    posters = bool(re.search(rf'{re.escape(name)}\.setLayoutManager\(new\s+GridLayoutManager\(', tail))
    inject = f'\n        r11e3TuneCatalogRecycler({name}, {str(posters).lower()});'
    s = s[:m.end()] + inject + s[m.end():]

SEVEN.write_text(s, encoding="utf-8")

g = GRADLE.read_text(encoding="utf-8")
g = re.sub(r'versionCode\s*=\s*\d+', 'versionCode = 1000348', g, count=1)
g = re.sub(r'versionName\s*=\s*"[^"]*"', 'versionName = "v340-full-stability-r11e-stage3"', g, count=1)
GRADLE.write_text(g, encoding="utf-8")

checks = {
    SEVEN: ["r11e3CachedHomePage", "r11e3-home-cache-hit", "r11e3CachedHomeFocus = tile",
            "r11e3ReducedFocusMotion", "r11e3TuneCatalogRecycler", "r11e3-catalog-window"],
    GRADLE: ["versionCode = 1000348", "v340-full-stability-r11e-stage3"],
}
for path, markers in checks.items():
    text = path.read_text(encoding="utf-8")
    for marker in markers:
        if marker not in text:
            raise SystemExit(f"R11E3 invariant missing {path.name}: {marker}")

print("R11E stage3 applied: instant home restore + focus retention + reduced TV focus motion + catalog RecyclerView tuning")
