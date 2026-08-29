#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "BLOFY-ANDROID-2026/app"
JAVA = APP / "src/main/java/tv/blofy/player"
UI = JAVA / "BlofyUi.java"
GRADLE = APP / "build.gradle.kts"

u = UI.read_text(encoding="utf-8")

# Stage6 is visual-only: preserve screen structure/focus graph and upgrade the shared
# BLOFY design system so every screen gets the same premium polish safely.

# Richer cinematic background with subtle purple depth and top vignette.
old = '''                paint.setShader(new LinearGradient(0, 0, canvas.getWidth(), canvas.getHeight(),
                        new int[]{Color.rgb(4, 4, 10), Color.rgb(7, 6, 15), Color.rgb(11, 8, 25), Color.rgb(5, 5, 12)},
                        new float[]{0f, 0.42f, 0.78f, 1f}, Shader.TileMode.CLAMP));'''
new = '''                paint.setShader(new LinearGradient(0, 0, canvas.getWidth(), canvas.getHeight(),
                        new int[]{Color.rgb(3, 3, 9), Color.rgb(8, 6, 18), Color.rgb(18, 9, 37),
                                Color.rgb(9, 6, 20), Color.rgb(4, 4, 11)},
                        new float[]{0f, 0.28f, 0.58f, 0.82f, 1f}, Shader.TileMode.CLAMP));'''
if old in u:
    u = u.replace(old, new, 1)

u = u.replace('paint.setColor(Color.argb(16, 124, 43, 255));',
              'paint.setColor(Color.argb(24, 132, 58, 255));', 1)
u = u.replace('canvas.getWidth() * .34f, paint);',
              'canvas.getWidth() * .38f, paint);', 1)

# More premium chips: translucent purple glass instead of flat black.
u = u.replace(
    'chip.setBackground(panel(context, Color.argb(205, 21, 19, 34), 8, STROKE));',
    'chip.setBackground(gradientPanel(context, Color.argb(220, 43, 28, 72), '
    'Color.argb(210, 20, 17, 38), 10, Color.rgb(91, 61, 133)));', 1)
u = u.replace('chip.setPadding(dp(context, 10), 0, dp(context, 10), 0);',
              'chip.setPadding(dp(context, 12), 0, dp(context, 12), 0);', 1)

# Stronger TV focus readability. Keep geometry unchanged; only paint changes.
u = u.replace(
    'states.addState(new int[]{android.R.attr.state_pressed}, rounded(context, PURPLE_DARK, 13, PURPLE_LIGHT, 2));\n'
    '        states.addState(new int[]{android.R.attr.state_focused}, rounded(context, focused, 13, focusStroke, 2));',
    'states.addState(new int[]{android.R.attr.state_pressed}, rounded(context, PURPLE_DARK, 14, Color.WHITE, 2));\n'
    '        states.addState(new int[]{android.R.attr.state_focused}, rounded(context, focused, 14, focusStroke, 3));', 1)

# Primary action: deeper Netflix/OSN-like gradient while staying BLOFY purple.
primary_pattern = re.compile(
    r'GradientDrawable focused = new GradientDrawable\(GradientDrawable\.Orientation\.LEFT_RIGHT,\n'
    r'\s*new int\[]\{Color\.rgb\(151, 70, 255\), Color\.rgb\(102, 27, 224\)\}\);.*?'
    r'GradientDrawable idle = new GradientDrawable\(GradientDrawable\.Orientation\.LEFT_RIGHT,\n'
    r'\s*new int\[]\{Color\.rgb\(128, 44, 255\), Color\.rgb\(91, 20, 206\)\}\);',
    re.S)
replacement = '''GradientDrawable focused = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.rgb(174, 92, 255), Color.rgb(126, 46, 246), Color.rgb(86, 20, 199)});
        focused.setCornerRadius(dp(context, 14));
        focused.setStroke(dp(context, 2), Color.WHITE);
        GradientDrawable idle = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.rgb(141, 55, 255), Color.rgb(105, 28, 226), Color.rgb(75, 17, 177)});'''
u, n = primary_pattern.subn(replacement, u, count=1)
if n == 0 and 'Color.rgb(174, 92, 255)' not in u:
    raise SystemExit('stage6: primary button palette anchor missing')
# Normalize duplicate radius/stroke lines left after structural replacement.
u = u.replace('idle.setCornerRadius(dp(context, 13));', 'idle.setCornerRadius(dp(context, 14));', 1)

# Selected/sidebar items: clearer active state at couch distance.
u = u.replace(
    'new int[]{Color.rgb(84, 25, 160), Color.rgb(37, 18, 76)}',
    'new int[]{Color.rgb(108, 38, 196), Color.rgb(43, 20, 88)}', 1)
u = u.replace(
    'new int[]{Color.rgb(62, 19, 124), Color.rgb(31, 15, 62)}',
    'new int[]{Color.rgb(74, 24, 144), Color.rgb(30, 14, 61)}', 1)

# Focus animation stays deterministic and light, but now visibly communicates focus.
u = u.replace('float effectiveScale = "reduced".equals(motion) ? 1f : Math.min(scale, 1.008f);',
              'float effectiveScale = "reduced".equals(motion) ? 1f : Math.min(Math.max(scale, 1.012f), 1.018f);', 1)
u = u.replace('.setDuration("reduced".equals(motion) ? 55 : 90).start();',
              '.setDuration("reduced".equals(motion) ? 45 : 105).start();', 1)
u = u.replace('v.setElevation(focused ? dp(v.getContext(), 8) : 0);',
              'v.setElevation(focused ? dp(v.getContext(), 14) : 0);\n'
              '            v.setAlpha(focused ? 1f : 0.96f);', 1)

# Brand typography: slightly stronger premium identity without changing layout.
brand_anchor = 'TextView player = text(context, subtitle == null ? "P L A Y E R" : subtitle, 9, PURPLE_LIGHT);\n'
if brand_anchor in u and 'player.setLetterSpacing(0.18f);' not in u:
    u = u.replace(brand_anchor, brand_anchor + '        player.setLetterSpacing(0.18f);\n', 1)

UI.write_text(u, encoding="utf-8")

# Upgrade-safe visual build identity.
g = GRADLE.read_text(encoding="utf-8")
g = re.sub(r'versionCode\s*=\s*\d+', 'versionCode = 1000359', g, count=1)
g = re.sub(r'versionName\s*=\s*"[^"]*"', 'versionName = "v340-playback-hotfix-stage6-visual"', g, count=1)
GRADLE.write_text(g, encoding="utf-8")

checks = {
    UI: [
        'Color.rgb(18, 9, 37)',
        'Color.argb(24, 132, 58, 255)',
        'Color.rgb(174, 92, 255)',
        'Color.rgb(108, 38, 196)',
        'Math.max(scale, 1.012f)',
        'dp(v.getContext(), 14)',
        'player.setLetterSpacing(0.18f)',
    ],
    GRADLE: [
        'versionCode = 1000359',
        'versionName = "v340-playback-hotfix-stage6-visual"',
    ],
}
for path, markers in checks.items():
    text = path.read_text(encoding="utf-8")
    for marker in markers:
        if marker not in text:
            raise SystemExit(f'stage6 invariant missing {path.name}: {marker}')

print('v340 stage6 visual applied: cinematic background + premium purple controls + stronger TV focus + glass chips')
