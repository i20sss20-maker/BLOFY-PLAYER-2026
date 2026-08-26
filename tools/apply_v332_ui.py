#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player"
SEVEN = JAVA / "SevenMaxActivity.java"
SETTINGS = JAVA / "SettingsActivity.java"

text = SEVEN.read_text()
pattern = re.compile(r"    private void showHome\(\) \{.*?\n    \}\n\n    private TextView homeTile", re.S)
replacement = '''    private void showHome() {
        releasePreview();
        screen = "home";
        ScreenShell shell = shell("home", "الرئيسية", true);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setSmoothScrollingEnabled(true);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        page.setPadding(dp(22), dp(8), dp(26), dp(28));

        addHero(page);

        addHomeRail(page, "أحدث الأفلام", "وصل حديثاً إلى مكتبتك",
                new HomeRailAdapter("movies", false, false),
                () -> showCatalog("movies", false));
        addHomeRail(page, "أحدث المسلسلات", "المواسم والحلقات في مكان واحد",
                new HomeRailAdapter("series", false, false),
                () -> showCatalog("series", false));
        addHomeRail(page, "القنوات", "وصول سريع للبث المباشر",
                new HomeRailAdapter("live", false, true), this::showLive);

        TextView footer = BlofyUi.text(this,
                "BLOFY PLAYER v332  •  الواجهة يسار والمحتوى يمين",
                10, BlofyUi.MUTED);
        footer.setGravity(Gravity.CENTER);
        footer.setTextDirection(View.TEXT_DIRECTION_RTL);
        page.addView(footer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

        scroll.addView(page);
        shell.content.addView(scroll, match());

        main.postDelayed(() -> {
            ArrayList<View> focusables = new ArrayList<>();
            root.addFocusables(focusables, View.FOCUS_FORWARD);
            if (!focusables.isEmpty()) focusables.get(0).requestFocus();
        }, 90L);
    }

    private TextView homeTile'''
if not pattern.search(text):
    raise SystemExit("showHome block not found")
text = pattern.sub(replacement, text, count=1)
text = text.replace("BLOFY PLAYER  •  v328", "BLOFY PLAYER  •  v332")
SEVEN.write_text(text)

settings = SETTINGS.read_text().replace("BLOFY PLAYER v328", "BLOFY PLAYER v332")
SETTINGS.write_text(settings)
print("v332 left navigation cinematic home applied")
