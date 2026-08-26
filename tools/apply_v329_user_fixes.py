from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player"


def edit(name, transform):
    path = JAVA / name
    text = path.read_text(encoding="utf-8")
    new = transform(text)
    if new == text:
        raise SystemExit(f"{name}: expected patch did not apply")
    path.write_text(new, encoding="utf-8")
    print(f"patched {name}")


def main_activity(text):
    old = '''                playlistStore.setActive(item.id);\n                main.post(this::importPackage);'''
    new = '''                playlistStore.setActive(item.id);\n                String cachedSource = item.currentSessionOnly ? "" : CatalogScope.forPlaylist(item.id);\n                boolean cached = !cachedSource.isEmpty() && database.activateCachedSource(cachedSource);\n                main.post(cached ? this::showHome : this::importPackage);'''
    if old not in text:
        raise SystemExit("MainActivity connect path not found")
    text = text.replace(old, new, 1)
    text = text.replace('main.postDelayed(this::showHome, 450);', 'main.post(this::showHome);', 1)
    return text


def catalog_db(text):
    marker = '''    String metadata(String key, String fallback) {\n        return readMetadata(getReadableDatabase(), key, fallback);\n    }\n'''
    addition = marker + '''\n    /** Opens a previously committed playlist partition without downloading it again. */\n    boolean activateCachedSource(String sourceId) {\n        String source = cleanSource(sourceId);\n        activeSource = source;\n        CatalogScope.activate(context, source);\n        int total = count("live") + count("movies") + count("series");\n        if (total <= 0) return false;\n        putMetadata("active_source_id", source);\n        putMetadata("source_identity", source);\n        return true;\n    }\n'''
    if marker not in text:
        raise SystemExit("CatalogDatabase metadata marker not found")
    return text.replace(marker, addition, 1)


def importer(text):
    text = text.replace('emit(95, "اعتماد بيانات الباقة", "حفظ القنوات والأفلام والمسلسلات للاستخدام");',
                        'emit(97, "اعتماد بيانات الباقة", "حفظ القنوات والأفلام والمسلسلات للاستخدام");', 1)
    text = text.replace('emit(98, "تجهيز التشغيل", profile);', 'emit(99, "تجهيز التشغيل", profile);', 1)
    return text


def settings(text):
    old = '''            item.setNextFocusLeftId(column + 1 < columns && left < count\n                    ? grid.getChildAt(left).getId() : item.getId());\n            item.setNextFocusRightId(column > 0\n                    ? grid.getChildAt(right).getId() : item.getId());'''
    new = '''            // GridLayout is RTL: visually-right is the next inserted cell.\n            item.setNextFocusRightId(column + 1 < columns && left < count\n                    ? grid.getChildAt(left).getId() : item.getId());\n            item.setNextFocusLeftId(column > 0\n                    ? grid.getChildAt(right).getId() : item.getId());'''
    if old not in text:
        raise SystemExit("Settings focus block not found")
    return text.replace(old, new, 1)


def sevenmax(text):
    start = text.index('    private void showHome() {')
    end = text.index('    private TextView homeTile', start)
    modern_home = '''    private void showHome() {\n        releasePreview();\n        screen = "home";\n        ScreenShell shell = shell("home", "الرئيسية");\n\n        ScrollView scroll = new ScrollView(this);\n        scroll.setFillViewport(true);\n        scroll.setClipToPadding(false);\n        scroll.setVerticalScrollBarEnabled(false);\n\n        LinearLayout content = new LinearLayout(this);\n        content.setOrientation(LinearLayout.VERTICAL);\n        content.setPadding(dp(24), dp(8), dp(28), dp(34));\n        scroll.addView(content, new FrameLayout.LayoutParams(\n                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));\n\n        View initialFocus = addHero(content);\n\n        HomeRailAdapter continueAdapter = new HomeRailAdapter("", true, true);\n        addHomeRail(content, "متابعة المشاهدة", "أكمل من حيث توقفت", continueAdapter,\n                this::showHistory);\n\n        HomeRailAdapter moviesAdapter = new HomeRailAdapter("movies", false, false);\n        addHomeRail(content, "أحدث الأفلام", "اختيارات جديدة على BLOFY", moviesAdapter,\n                () -> showCatalog("movies", false));\n\n        HomeRailAdapter seriesAdapter = new HomeRailAdapter("series", false, false);\n        addHomeRail(content, "أحدث المسلسلات والحلقات", "مرتبة حسب آخر إضافة وتاريخ العرض", seriesAdapter,\n                () -> showCatalog("series", false));\n\n        shell.content.addView(scroll, match());\n        if (initialFocus != null) initialFocus.requestFocus();\n    }\n\n'''
    text = text[:start] + modern_home + text[end:]
    text = text.replace('main.postDelayed(pendingPreview, 220L);', 'main.postDelayed(pendingPreview, 60L);', 1)
    return text


def details(text):
    text = text.replace('''        // Progress is local, so the choice can appear immediately without waiting\n        // for a remote metadata request to complete.\n        showResumePrompt(null);\n''', '', 1)
    text = text.replace('if (!showResumePrompt(detail)) primary.requestFocus();', 'primary.requestFocus();', 1)
    old = '''            if (resume != null && resume.available()) {\n                primary = BlofyUi.button(this,\n                        "▶  استئناف  " + PlaybackProgress.format(resume.position), true);\n                primary.setOnClickListener(v -> play(resume.id,\n                        resume.title.isEmpty() ? detail.name : resume.title,\n                        "episode", resume.extension, false));\n                actions.addView(primary, new LinearLayout.LayoutParams(dp(190), dp(56)));\n\n                Button restart = BlofyUi.button(this, "↺  من البداية", false);\n                restart.setOnClickListener(v -> play(resume.id,\n                        resume.title.isEmpty() ? detail.name : resume.title,\n                        "episode", resume.extension, true));\n                LinearLayout.LayoutParams restartParams = new LinearLayout.LayoutParams(dp(120), dp(56));\n                restartParams.leftMargin = dp(8);\n                actions.addView(restart, restartParams);\n\n                Button episodes = BlofyUi.button(this, "المواسم", false);\n                episodes.setOnClickListener(v -> showSeasons(detail));\n                LinearLayout.LayoutParams episodesParams = new LinearLayout.LayoutParams(dp(100), dp(56));\n                episodesParams.leftMargin = dp(8);\n                actions.addView(episodes, episodesParams);\n            } else {'''
    new = '''            if (resume != null && resume.available()) {\n                primary = BlofyUi.button(this, "▶  المواسم والحلقات", true);\n                primary.setOnClickListener(v -> showSeasons(detail));\n                actions.addView(primary, new LinearLayout.LayoutParams(dp(245), dp(56)));\n\n                Button resumeButton = BlofyUi.button(this,\n                        "استئناف  " + PlaybackProgress.format(resume.position), false);\n                resumeButton.setOnClickListener(v -> play(resume.id,\n                        resume.title.isEmpty() ? detail.name : resume.title,\n                        "episode", resume.extension, false));\n                LinearLayout.LayoutParams resumeParams = new LinearLayout.LayoutParams(dp(175), dp(56));\n                resumeParams.leftMargin = dp(8);\n                actions.addView(resumeButton, resumeParams);\n            } else {'''
    if old not in text:
        raise SystemExit("Details resume action block not found")
    return text.replace(old, new, 1)


def bump_version():
    path = ROOT / "BLOFY-ANDROID-2026/app/build.gradle.kts"
    text = path.read_text(encoding="utf-8")
    text = text.replace('versionCode = 328', 'versionCode = 329', 1)
    text = text.replace('versionName = "2026.08.26.4-v328"', 'versionName = "2026.08.26.5-v329"', 1)
    if 'versionCode = 329' not in text or '2026.08.26.5-v329' not in text:
        raise SystemExit("Gradle v329 version bump failed")
    path.write_text(text, encoding="utf-8")
    print("patched app/build.gradle.kts")


edit("MainActivity.java", main_activity)
edit("CatalogDatabase.java", catalog_db)
edit("PackageImporter.java", importer)
edit("SettingsActivity.java", settings)
edit("SevenMaxActivity.java", sevenmax)
edit("DetailsActivity.java", details)
bump_version()
print("BLOFY v329 user feedback patch applied")
