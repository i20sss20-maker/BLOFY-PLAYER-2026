#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "BLOFY-ANDROID-2026/app"
JAVA = APP / "src/main/java/tv/blofy/player"
SEVEN = JAVA / "SevenMaxActivity.java"
GRADLE = APP / "build.gradle.kts"

s = SEVEN.read_text(encoding="utf-8")

# State fields: tolerate reconstruction layers reordering members.
if "private View r11e3CachedHomePage;" not in s:
    m = re.search(r'(?m)^\s*private\s+volatile\s+boolean\s+destroyed\s*;\s*$', s)
    if not m:
        m = re.search(r'public\s+final\s+class\s+SevenMaxActivity\s+extends\s+Activity\s*\{', s)
        if not m:
            raise SystemExit("R11E3: class/state anchor missing")
    insert_at = m.end()
    s = s[:insert_at] + "\n    private View r11e3CachedHomePage;\n    private View r11e3CachedHomeFocus;" + s[insert_at:]

# Locate Home by semantic statements instead of a fragile Java declaration parser.
def home_region(text):
    home = re.search(r'screen\s*=\s*"home"\s*;', text)
    if not home:
        # Fallback: the first root reset followed by construction of the home page.
        home = re.search(r'root\s*\.\s*removeAllViews\s*\(\s*\)\s*;(?=[\s\S]{0,900}LinearLayout\s+page\s*=)', text)
    if not home:
        return -1, -1
    start = max(0, text.rfind('\n', 0, home.start()))
    next_method = re.search(r'(?m)^\s*private\s+(?:[\w<>\[\].?, ]+)\s+(?:homeTile|addHomeGridTile|showSports)\s*\(', text[home.end():])
    end = home.end() + (next_method.start() if next_method else min(len(text) - home.end(), 12000))
    return start, end

if "r11e3-home-cache-hit" not in s:
    start, end = home_region(s)
    if start < 0:
        raise SystemExit("R11E3: home semantic anchor missing")
    block = s[start:end]
    reset = re.search(r'root\s*\.\s*removeAllViews\s*\(\s*\)\s*;', block)
    if not reset:
        raise SystemExit("R11E3: home root reset missing")
    insert_at = start + reset.end()
    inject = '''
        if (r11e3CachedHomePage != null) {
            ViewGroup parent = (ViewGroup) r11e3CachedHomePage.getParent();
            if (parent != null) parent.removeView(r11e3CachedHomePage);
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
    start, end = home_region(s)
    if start < 0:
        raise SystemExit("R11E3: home region missing after cache injection")
    block = s[start:end]
    # Prefer the explicit home page attach. Fall back to the final root add in Home.
    attach = re.search(r'root\s*\.\s*addView\s*\(\s*page\s*,\s*match\s*\(\s*\)\s*\)\s*;', block)
    if not attach:
        all_attach = list(re.finditer(r'root\s*\.\s*addView\s*\([^;]+\)\s*;', block))
        attach = all_attach[-1] if all_attach else None
    if not attach:
        raise SystemExit("R11E3: home root attach missing")
    insert_at = start + attach.end()
    s = s[:insert_at] + "\n        r11e3CachedHomePage = root.getChildAt(0);" + s[insert_at:]

# Persist the last launcher selected so returning Home restores focus instantly.
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

# Reduce focus animation work on weaker TV boxes while preserving polish on strong devices.
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

# Recycler tuning is deliberately UI-only: no DB schema bump, so it cannot trigger a package reload.
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

    rv_pattern = re.compile(r'RecyclerView\s+(\w+)\s*=\s*new RecyclerView\(this\);')
    matches = list(rv_pattern.finditer(s))
    offset = 0
    for m in matches:
        name = m.group(1)
        pos = m.end() + offset
        tail = s[pos:pos + 650]
        if f'r11e3TuneCatalogRecycler({name},' in tail:
            continue
        posters = bool(re.search(rf'{re.escape(name)}\.setLayoutManager\(new\s+GridLayoutManager\(', tail))
        inject = f'\n        r11e3TuneCatalogRecycler({name}, {str(posters).lower()});'
        s = s[:pos] + inject + s[pos:]
        offset += len(inject)

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

print("R11E stage3 applied: instant home restore + focus-state retention + reduced TV focus motion + catalog RecyclerView tuning")
