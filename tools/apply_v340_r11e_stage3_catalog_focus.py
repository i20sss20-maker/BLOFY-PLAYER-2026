#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "BLOFY-ANDROID-2026/app"
JAVA = APP / "src/main/java/tv/blofy/player"
SEVEN = JAVA / "SevenMaxActivity.java"
GRADLE = APP / "build.gradle.kts"

s = SEVEN.read_text(encoding="utf-8")

if "private View r11e3CachedHomePage;" not in s:
    anchor = "    private volatile boolean destroyed;\n"
    if anchor not in s:
        raise SystemExit("R11E3: state anchor missing")
    s = s.replace(anchor, anchor +
        "    private View r11e3CachedHomePage;\n"
        "    private View r11e3CachedHomeFocus;\n", 1)

# Reconstruction-safe method locator: prior layers may alter modifiers/spacing.
def method_bounds(text, method_name, next_method_name=None):
    m = re.search(r'(?m)^\s*(?:private|protected|public)?\s*(?:final\s+)?(?:void|TextView|View)\s+' + re.escape(method_name) + r'\s*\([^\n]*\)\s*\{', text)
    if not m:
        return -1, -1
    start = m.start()
    if next_method_name:
        n = re.search(r'(?m)^\s*(?:private|protected|public)?\s*(?:final\s+)?(?:void|TextView|View)\s+' + re.escape(next_method_name) + r'\s*\(', text[m.end():])
        if n:
            return start, m.end() + n.start()
    n = re.search(r'(?m)^\s*(?:private|protected|public)\s+', text[m.end():])
    return start, (m.end() + n.start()) if n else len(text)

if "r11e3-home-cache-hit" not in s:
    start, end = method_bounds(s, "showHome", "homeTile")
    if start < 0 or end < 0:
        raise SystemExit("R11E3: showHome bounds missing")
    block = s[start:end]
    reset = re.search(r'root\s*\.\s*removeAllViews\s*\(\s*\)\s*;', block)
    if not reset:
        raise SystemExit("R11E3: showHome root reset missing")
    insert_at = start + reset.end()
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
    start, end = method_bounds(s, "showHome", "homeTile")
    if start < 0 or end < 0:
        raise SystemExit("R11E3: showHome bounds missing")
    block = s[start:end]
    attach = list(re.finditer(r'root\s*\.\s*addView\s*\([^;]+\)\s*;', block))
    if not attach:
        raise SystemExit("R11E3: home root attach missing")
    insert_at = start + attach[-1].end()
    s = s[:insert_at] + "\n        r11e3CachedHomePage = root.getChildAt(0);" + s[insert_at:]

if "r11e3CachedHomeFocus = getCurrentFocus();" not in s:
    cursor = 0
    injected = False
    home_start, home_end = method_bounds(s, "showHome", "homeTile")
    for m in list(re.finditer(r'root\s*\.\s*removeAllViews\s*\(\s*\)\s*;', s)):
        idx = m.start()
        if home_start <= idx < home_end:
            continue
        guard = '''if (r11e3CachedHomePage != null && getCurrentFocus() != null) {
            r11e3CachedHomeFocus = getCurrentFocus();
        }
        '''
        s = s[:idx] + guard + s[idx:]
        injected = True
        break
    if not injected:
        click = re.search(r'tile\s*\.\s*setOnClickListener\s*\(v\s*->\s*action\s*\.\s*run\s*\(\s*\)\s*\)\s*;', s)
        if click:
            repl = '''tile.setOnClickListener(v -> {
            r11e3CachedHomeFocus = tile;
            action.run();
        });'''
            s = s[:click.start()] + repl + s[click.end():]
        else:
            print("R11E3: no transition focus anchor; default home focus remains safe")

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
        # Flexible animation replacement for reconstructed formatting.
        pattern = re.compile(r'\s*tile\.setOnFocusChangeListener\(\(view,\s*focusedNow\)\s*->\s*view\.animate\(\)\s*\.scaleX\(focusedNow\s*\?\s*1\.025f\s*:\s*1f\)\s*\.scaleY\(focusedNow\s*\?\s*1\.025f\s*:\s*1f\)\s*\.setDuration\(110L\)\.start\(\)\);', re.S)
        m = pattern.search(s)
        if not m:
            raise SystemExit("R11E3: home focus animation anchor missing")
        s = s[:m.start()] + "\n" + new + s[m.end():]
    else:
        s = s.replace(old, new, 1)

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
