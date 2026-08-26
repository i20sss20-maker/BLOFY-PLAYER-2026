from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def write(path, text):
    (ROOT / path).write_text(text, encoding="utf-8")


def replace_once(text, old, new, label):
    if old not in text:
        raise SystemExit(f"v330 patch mismatch: {label}")
    return text.replace(old, new, 1)

# -----------------------------------------------------------------------------
# 1) Playback compatibility chain.
# Preserve the fast canonical path. Only if it fails:
# canonical -> direct (different UA/profile) -> canonical without forced MIME
# using VLC-like headers -> LibVLC. No long retries and no provider-wide memory.
# -----------------------------------------------------------------------------
player_path = "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player/PlayerActivity.java"
player = read(player_path)

old_direct_catch = '''                    if ("direct".equals(requestedVariant) && restoreCanonicalSource()) {
                        if (lifecycleStarted) openVlc(PlaybackPolicy.resolveErrorMessage(error));
                        return;
                    }
                    showResolveError(PlaybackPolicy.resolveErrorMessage(error));
'''
new_direct_catch = '''                    if ("direct".equals(requestedVariant) && restoreCanonicalSource()) {
                        // v330: direct-link resolution itself failed. Before falling to
                        // LibVLC, retry the proven signed source without forcing a MIME
                        // type and with compatibility headers. This helps providers that
                        // rotate between TS/HLS/MP4 responses behind the same endpoint.
                        sourceVariant = "no-extension";
                        recoveryStep = 2;
                        if (lifecycleStarted) initializePlayer();
                        return;
                    }
                    showResolveError(PlaybackPolicy.resolveErrorMessage(error));
'''
player = replace_once(player, old_direct_catch, new_direct_catch, "direct resolve fallback")

old_recovery = '''        // A fast HTTP/connection error can be specific to the signed relay.
        // Resolve the direct source once; do not add TS/HLS and Cronet retries
        // behind it. Slow startup and decoder failures go straight to LibVLC.
        if (PlaybackPolicy.isNetworkFailure(reason)
                && !PlaybackPolicy.isStartupTimeout(reason)
                && "canonical".equals(sourceVariant) && !id.isEmpty()) {
            releasePlayer();
            sourceVariant = "direct";
            recoveryStep = 1;
            url = null;
            resolvePlaybackLink();
            return;
        }

        if (!vlcAttempted) {
            recoveryStep = 2;
            if ("direct".equals(sourceVariant)) restoreCanonicalSource();
            openVlc(reason);
            return;
        }
'''
new_recovery = '''        // v330 bounded compatibility chain. The first canonical attempt is
        // untouched, so providers that already start instantly stay instant.
        // Only a failed source advances to another transport/profile.
        if ("canonical".equals(sourceVariant) && !id.isEmpty()) {
            releasePlayer();
            sourceVariant = "direct";
            recoveryStep = 1; // ExoPlayer-compatible UA + direct source
            url = null;
            resolvePlaybackLink();
            return;
        }

        if ("direct".equals(sourceVariant) && restoreCanonicalSource()) {
            releasePlayer();
            sourceVariant = "no-extension";
            recoveryStep = 2; // VLC-compatible UA, Media3 container sniffing
            initializePlayer();
            return;
        }

        if (!vlcAttempted) {
            if (!"no-extension".equals(sourceVariant)) restoreCanonicalSource();
            recoveryStep = 2;
            openVlc(reason);
            return;
        }
'''
player = replace_once(player, old_recovery, new_recovery, "bounded playback recovery")

old_failure = '''        errorText.setText("تعذر تشغيل القناة بعد المحاولة بالمشغل الأساسي والمتوافق."
                + "\\n" + detail + "\\nالصيغة: " + extension);
'''
new_failure = '''        errorText.setText("تعذر تشغيل المصدر بعد تجربة المسار السريع ومسارات التوافق."
                + "\\n" + detail + "\\nالصيغة: " + extension
                + "  •  المسار: " + sourceVariant);
'''
if old_failure in player:
    player = player.replace(old_failure, new_failure, 1)
write(player_path, player)

# -----------------------------------------------------------------------------
# 2) Settings DPAD. Physical RIGHT must always move horizontally instead of
# self-looping on the RTL first column. LEFT is the opposite; edge wraps within row.
# -----------------------------------------------------------------------------
settings_path = "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player/SettingsActivity.java"
settings = read(settings_path)
old_focus = '''            int left = index + 1;
            int right = index - 1;
            item.setNextFocusUpId(up >= 0 ? grid.getChildAt(up).getId() : back.getId());
            item.setNextFocusDownId(down < count ? grid.getChildAt(down).getId() : item.getId());
            item.setNextFocusLeftId(column + 1 < columns && left < count
                    ? grid.getChildAt(left).getId() : item.getId());
            item.setNextFocusRightId(column > 0
                    ? grid.getChildAt(right).getId() : item.getId());
'''
new_focus = '''            int rowStart = index - column;
            int rowEnd = Math.min(count - 1, rowStart + columns - 1);
            int physicalRight = index < rowEnd ? index + 1 : rowStart;
            int physicalLeft = index > rowStart ? index - 1 : rowEnd;
            item.setNextFocusUpId(up >= 0 ? grid.getChildAt(up).getId() : back.getId());
            item.setNextFocusDownId(down < count ? grid.getChildAt(down).getId() : item.getId());
            // Explicit physical DPAD mapping; do not rely on RTL focus heuristics.
            item.setNextFocusRightId(grid.getChildAt(physicalRight).getId());
            item.setNextFocusLeftId(grid.getChildAt(physicalLeft).getId());
'''
settings = replace_once(settings, old_focus, new_focus, "settings physical DPAD")
settings = settings.replace("BLOFY PLAYER v329", "BLOFY PLAYER v330")
write(settings_path, settings)

# -----------------------------------------------------------------------------
# 3) Playlist editor/login: remote-first focus graph and a clear save/continue CTA.
# Every visible control gets deterministic DPAD links; no need to leave/re-enter.
# -----------------------------------------------------------------------------
main_path = "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player/MainActivity.java"
main = read(main_path)
main = main.replace(
    '                        : "احفظ البيانات أولًا، ثم ارجع واختر القائمة واضغط اتصال.",',
    '                        : "أدخل بيانات القائمة ثم اختر حفظ ومتابعة. بعد الحفظ ستظهر القائمة وزر اتصال مباشرة.",',
    1)
main = main.replace('Button save = BlofyUi.button(this, "حفظ القائمة", true);',
                    'Button save = BlofyUi.button(this, "حفظ ومتابعة", true);', 1)

old_editor_links = '''        xtreamTab.setNextFocusLeftId(m3uTab.getId());
        m3uTab.setNextFocusRightId(xtreamTab.getId());
        server.setNextFocusUpId(name.getId()); server.setNextFocusDownId(username.getId());
        username.setNextFocusUpId(server.getId()); username.setNextFocusDownId(password.getId());
        password.setNextFocusUpId(username.getId());
        playlist.setNextFocusUpId(name.getId());
'''
new_editor_links = '''        // Deterministic TV focus graph. Both horizontal directions work even
        // when Android applies RTL heuristics to the form.
        xtreamTab.setNextFocusRightId(m3uTab.getId());
        xtreamTab.setNextFocusLeftId(m3uTab.getId());
        m3uTab.setNextFocusRightId(xtreamTab.getId());
        m3uTab.setNextFocusLeftId(xtreamTab.getId());
        server.setNextFocusUpId(name.getId()); server.setNextFocusDownId(username.getId());
        username.setNextFocusUpId(server.getId()); username.setNextFocusDownId(password.getId());
        password.setNextFocusUpId(username.getId());
        playlist.setNextFocusUpId(name.getId());
'''
main = replace_once(main, old_editor_links, new_editor_links, "playlist editor DPAD")

old_footer_links = '''        save.setNextFocusLeftId(cancel.getId());
        cancel.setNextFocusRightId(save.getId());
'''
new_footer_links = '''        save.setNextFocusLeftId(cancel.getId());
        save.setNextFocusRightId(cancel.getId());
        cancel.setNextFocusRightId(save.getId());
        cancel.setNextFocusLeftId(save.getId());
'''
main = replace_once(main, old_footer_links, new_footer_links, "playlist footer DPAD")
main = main.replace('save.setText("حفظ القائمة");', 'save.setText("حفظ ومتابعة");')
write(main_path, main)

# -----------------------------------------------------------------------------
# 4) Home redesign: left-only navigation, right-only hero + poster strip.
# Catalog/live/detail pages are untouched.
# -----------------------------------------------------------------------------
seven_path = "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player/SevenMaxActivity.java"
seven = read(seven_path)
if "import android.widget.HorizontalScrollView;" not in seven:
    seven = seven.replace("import android.widget.GridLayout;\n",
                          "import android.widget.GridLayout;\nimport android.widget.HorizontalScrollView;\n", 1)

start = seven.index("    private void showHome() {")
end = seven.index("    private TextView homeTile", start)
new_home = r'''    private void showHome() {
        releasePreview();
        stopHeroRotation();
        screenGeneration++;
        screen = "home";
        root.removeAllViews();

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.HORIZONTAL);
        shell.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        shell.setBackground(BlofyUi.screenGradient());
        shell.setPadding(dp(18), dp(18), dp(18), dp(18));

        // LEFT: navigation only.
        LinearLayout sidebar = new LinearLayout(this);
        sidebar.setOrientation(LinearLayout.VERTICAL);
        sidebar.setGravity(Gravity.TOP);
        sidebar.setPadding(dp(16), dp(16), dp(16), dp(16));
        sidebar.setBackground(BlofyUi.gradientPanel(this,
                Color.argb(246, 12, 9, 22), Color.argb(246, 6, 6, 13), 22, BlofyUi.STROKE));
        sidebar.addView(BlofyUi.brand(this, "P L A Y E R"),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(66)));

        List<View> nav = new ArrayList<>();
        nav.add(homeSideItem("⌂", "الرئيسية", true, this::showHome));
        nav.add(homeSideItem("◉", "بث مباشر", false, this::showLive));
        nav.add(homeSideItem("●", "الأفلام", false, () -> showCatalog("movies", false)));
        nav.add(homeSideItem("▣", "المسلسلات", false, () -> showCatalog("series", false)));
        nav.add(homeSideItem("♥", "المفضلة", false, () -> showFavorites()));
        nav.add(homeSideItem("⌕", "بحث", false, this::showSearch));
        nav.add(homeSideItem("▤", "تغيير القائمة", false, this::openPlaylistHub));
        nav.add(homeSideItem("↻", "تحديث القائمة", false, this::openLegacyRefresh));
        nav.add(homeSideItem("⚙", "الإعدادات", false, this::openLegacySettings));
        nav.add(homeSideItem("↪", "خروج", false, this::finishAffinity));

        for (int i = 0; i < nav.size(); i++) {
            View item = nav.get(i);
            item.setId(View.generateViewId());
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
            p.setMargins(0, dp(3), 0, dp(3));
            sidebar.addView(item, p);
        }
        for (int i = 0; i < nav.size(); i++) {
            View item = nav.get(i);
            item.setNextFocusUpId(nav.get(i == 0 ? nav.size() - 1 : i - 1).getId());
            item.setNextFocusDownId(nav.get(i == nav.size() - 1 ? 0 : i + 1).getId());
            item.setNextFocusLeftId(item.getId());
        }
        shell.addView(sidebar, new LinearLayout.LayoutParams(dp(250), ViewGroup.LayoutParams.MATCH_PARENT));

        // RIGHT: content only — hero/banner + posters.
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), 0, 0, 0);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = BlofyUi.title(this, "اكتشف الآن", 25);
        title.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        top.addView(title, new LinearLayout.LayoutParams(0, dp(54), 1f));
        TextView server = BlofyUi.text(this,
                database.metadata("server_name", "BLOFY") + "  •  " + formatCount(database.count("live"), "قناة"),
                11, BlofyUi.MUTED);
        server.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        top.addView(server, new LinearLayout.LayoutParams(dp(360), dp(54)));
        content.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));

        View hero = addHero(content);

        TextView latestTitle = BlofyUi.title(this, "أحدث المحتوى", 18);
        latestTitle.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        content.addView(latestTitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));

        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout posters = new LinearLayout(this);
        posters.setOrientation(LinearLayout.HORIZONTAL);
        posters.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        scroller.addView(posters, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(scroller, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        final int ownerGeneration = screenGeneration;
        submitCatalog(() -> {
            List<BlofyModels.Media> items = featuredMedia();
            main.post(() -> {
                if (!isCurrentScreen(ownerGeneration)) return;
                posters.removeAllViews();
                int limit = Math.min(10, items.size());
                View firstPoster = null;
                for (int i = 0; i < limit; i++) {
                    BlofyModels.Media media = items.get(i);
                    View card = homePoster(media);
                    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(142), dp(205));
                    p.setMargins(0, dp(6), dp(12), 0);
                    posters.addView(card, p);
                    if (firstPoster == null) firstPoster = card;
                }
                if (firstPoster != null) {
                    for (View item : nav) item.setNextFocusRightId(firstPoster.getId());
                    firstPoster.setNextFocusLeftId(nav.get(0).getId());
                }
            });
        });

        shell.addView(content, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        root.addView(shell, match());
        nav.get(0).requestFocus();
    }

    private TextView homeSideItem(String icon, String label, boolean selected, Runnable action) {
        TextView item = BlofyUi.title(this, icon + "   " + label, 15);
        item.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        item.setTextDirection(View.TEXT_DIRECTION_RTL);
        item.setPadding(dp(14), 0, dp(14), 0);
        item.setFocusable(true);
        item.setFocusableInTouchMode(true);
        item.setClickable(true);
        int normal = selected ? Color.rgb(44, 25, 72) : Color.TRANSPARENT;
        item.setBackground(BlofyUi.focusDrawable(this, normal,
                Color.rgb(92, 43, 154), BlofyUi.PURPLE_LIGHT));
        item.setOnClickListener(v -> action.run());
        return item;
    }

    private View homePoster(BlofyModels.Media media) {
        FrameLayout card = new FrameLayout(this);
        card.setId(View.generateViewId());
        card.setFocusable(true);
        card.setFocusableInTouchMode(true);
        card.setClickable(true);
        card.setBackground(BlofyUi.focusDrawable(this, Color.rgb(20, 18, 29),
                Color.rgb(42, 27, 63), BlofyUi.PURPLE_LIGHT));
        ImageView art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        images.load(art, media.image);
        card.addView(art, match());
        TextView caption = BlofyUi.text(this, media.name, 11, Color.WHITE);
        caption.setGravity(Gravity.BOTTOM | Gravity.RIGHT);
        caption.setPadding(dp(8), dp(6), dp(8), dp(8));
        caption.setBackground(BlofyUi.heroScrim());
        card.addView(caption, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52), Gravity.BOTTOM));
        card.setOnClickListener(v -> routeMedia(media));
        card.setOnFocusChangeListener((v, focused) -> v.animate()
                .scaleX(focused ? 1.035f : 1f).scaleY(focused ? 1.035f : 1f)
                .setDuration(110L).start());
        return card;
    }

'''
seven = seven[:start] + new_home + seven[end:]
seven = seven.replace('"BLOFY PLAYER  •  v329"', '"BLOFY PLAYER  •  v330"')
write(seven_path, seven)

# -----------------------------------------------------------------------------
# 5) Version bump.
# -----------------------------------------------------------------------------
gradle_path = "BLOFY-ANDROID-2026/app/build.gradle.kts"
gradle = read(gradle_path)
gradle = gradle.replace('versionCode = 329', 'versionCode = 330', 1)
gradle = gradle.replace('versionName = "2026.08.26.5-v329"', 'versionName = "2026.08.26.6-v330"', 1)
write(gradle_path, gradle)

print("v330 hotfixes applied")
