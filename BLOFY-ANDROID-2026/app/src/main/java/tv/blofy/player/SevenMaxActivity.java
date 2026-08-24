package tv.blofy.player;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** BLOFY's cinematic TV shell and catalog experience. */
public final class SevenMaxActivity extends Activity {
    private static final int LIVE_PAGE = 140;
    private static final int POSTER_PAGE = 80;
    private static final int SIDEBAR_WIDTH = 232;

    private FrameLayout root;
    private CatalogDatabase database;
    private ImageLoader images;
    private BlofyApi api;
    private LivePreviewController livePreview;
    private String screen = "home";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BlofyUi.BLACK);
        getWindow().setNavigationBarColor(BlofyUi.BLACK);
        root = new FrameLayout(this);
        root.setBackground(BlofyUi.screenGradient());
        setContentView(root);
        database = new CatalogDatabase(this);
        api = new BlofyApi(this);
        images = new ImageLoader(api);
        showHome();
    }

    private void showHome() {
        releasePreview();
        screen = "home";
        ScreenShell shell = shell("home", "الرئيسية");

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(8), dp(28), dp(34));
        scroll.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View initialFocus = addHero(content);

        HomeRailAdapter continueAdapter = new HomeRailAdapter("", true, true);
        addHomeRail(content, "متابعة المشاهدة", "أكمل من حيث توقفت", continueAdapter,
                this::showHistory);

        HomeRailAdapter moviesAdapter = new HomeRailAdapter("movies", false, false);
        addHomeRail(content, "أحدث الأفلام", "اختيارات جديدة على BLOFY", moviesAdapter,
                () -> showCatalog("movies", false));

        HomeRailAdapter seriesAdapter = new HomeRailAdapter("series", false, false);
        addHomeRail(content, "أحدث المسلسلات", "حلقات ومواسم تستحق المتابعة", seriesAdapter,
                () -> showCatalog("series", false));

        shell.content.addView(scroll, match());
        if (initialFocus != null) initialFocus.requestFocus();
    }

    private View addHero(LinearLayout parent) {
        final BlofyModels.Media featured = featuredMedia();

        FrameLayout hero = new FrameLayout(this);
        hero.setClipToOutline(true);
        hero.setBackground(BlofyUi.gradientPanel(this, BlofyUi.PANEL_ALT, BlofyUi.BLACK, 20, BlofyUi.STROKE));

        ImageView backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setAlpha(.78f);
        if (featured != null) {
            String art = TextUtils.isEmpty(featured.backdrop) ? featured.image : featured.backdrop;
            images.load(backdrop, art);
        } else {
            backdrop.setImageResource(R.drawable.blofy_logo);
            backdrop.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            backdrop.setAlpha(.28f);
        }
        hero.addView(backdrop, match());

        View scrim = new View(this);
        scrim.setBackground(BlofyUi.heroScrim());
        hero.addView(scrim, match());

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        copy.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);

        TextView eyebrow = BlofyUi.chip(this, featured == null ? "BLOFY ORIGINAL" : "مقترح لك  •  BLOFY");
        eyebrow.setGravity(Gravity.CENTER);
        copy.addView(eyebrow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28)));

        String titleValue = featured == null ? "كل ترفيهك في مكان واحد" : featured.name;
        TextView title = BlofyUi.title(this, titleValue, 31);
        title.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        title.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.setMargins(0, dp(12), 0, dp(4));
        copy.addView(title, titleParams);

        TextView meta = BlofyUi.text(this,
                featured == null ? "بث مباشر  •  أفلام  •  مسلسلات" : formatMeta(featured),
                13, BlofyUi.PURPLE_LIGHT);
        meta.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        meta.setTextDirection(View.TEXT_DIRECTION_LTR);
        copy.addView(meta, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)));

        TextView description = BlofyUi.text(this,
                featured == null
                        ? "استكشف مكتبتك، تابع قنواتك، وارجع بسرعة إلى آخر ما شاهدته."
                        : "اكتشف التفاصيل وابدأ المشاهدة بتجربة BLOFY السينمائية الجديدة.",
                13, Color.rgb(220, 216, 230));
        description.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        description.setTextDirection(View.TEXT_DIRECTION_RTL);
        description.setMaxLines(2);
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        descriptionParams.setMargins(0, dp(4), 0, dp(8));
        copy.addView(description, descriptionParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        Button primary = BlofyUi.button(this, featured == null ? "شاهد البث المباشر" : "شاهد الآن  ▶", true);
        primary.setOnClickListener(v -> {
            if (featured == null) showLive(); else routeMedia(featured);
        });
        actions.addView(primary, new LinearLayout.LayoutParams(dp(178), dp(48)));
        Button more = BlofyUi.button(this, "مزيد من المعلومات", false);
        more.setOnClickListener(v -> {
            if (featured == null) showCatalog("movies", false); else openDetails(featured);
        });
        LinearLayout.LayoutParams moreParams = new LinearLayout.LayoutParams(dp(154), dp(48));
        moreParams.setMargins(dp(10), 0, 0, 0);
        actions.addView(more, moreParams);
        copy.addView(actions, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));

        FrameLayout.LayoutParams copyParams = new FrameLayout.LayoutParams(dp(570),
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.LEFT | Gravity.CENTER_VERTICAL);
        copyParams.setMargins(dp(38), dp(20), 0, dp(20));
        hero.addView(copy, copyParams);

        LinearLayout.LayoutParams heroParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(300));
        heroParams.setMargins(0, dp(4), 0, dp(22));
        parent.addView(hero, heroParams);
        return primary;
    }

    private BlofyModels.Media featuredMedia() {
        List<BlofyModels.Media> candidates = database.media("movies", "", "", false, false, 8, 0);
        if (candidates.isEmpty()) candidates = database.media("series", "", "", false, false, 8, 0);
        if (candidates.isEmpty()) return null;
        for (BlofyModels.Media item : candidates) {
            if (!TextUtils.isEmpty(item.backdrop)) return item;
        }
        return candidates.get(0);
    }

    private void addHomeRail(LinearLayout parent, String titleValue, String subtitle,
                             HomeRailAdapter adapter, Runnable showAll) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView title = BlofyUi.title(this, titleValue, 20);
        title.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        title.setTextDirection(View.TEXT_DIRECTION_RTL);
        TextView sub = BlofyUi.text(this, subtitle, 11, BlofyUi.MUTED);
        sub.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        sub.setTextDirection(View.TEXT_DIRECTION_RTL);
        labels.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(27)));
        labels.addView(sub, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(19)));
        header.addView(labels, new LinearLayout.LayoutParams(0, dp(48), 1));

        TextView all = BlofyUi.navChip(this, "عرض الكل  ←");
        all.setTextSize(12);
        all.setOnClickListener(v -> showAll.run());
        header.addView(all, new LinearLayout.LayoutParams(dp(118), dp(40)));
        parent.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));

        adapter.reload();
        if (adapter.rows.isEmpty()) {
            TextView empty = BlofyUi.text(this,
                    adapter.history ? "ابدأ المشاهدة وسيظهر المحتوى هنا" : "لا يوجد محتوى في هذا القسم بعد",
                    13, BlofyUi.MUTED);
            empty.setGravity(Gravity.CENTER);
            empty.setBackground(BlofyUi.panel(this, Color.argb(155, 18, 15, 31), 14, BlofyUi.STROKE));
            LinearLayout.LayoutParams emptyParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(adapter.landscape ? 112 : 150));
            emptyParams.setMargins(0, 0, 0, dp(20));
            parent.addView(empty, emptyParams);
            return;
        }

        RecyclerView rail = new RecyclerView(this);
        rail.setLayoutManager(new LinearLayoutManager(this, RecyclerView.HORIZONTAL, false));
        rail.setItemAnimator(null);
        rail.setHasFixedSize(true);
        rail.setItemViewCacheSize(adapter.landscape ? 10 : 14);
        rail.setClipToPadding(false);
        rail.setPadding(dp(2), dp(4), dp(14), dp(8));
        rail.setAdapter(adapter);
        LinearLayout.LayoutParams railParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(adapter.landscape ? 190 : 282));
        railParams.setMargins(0, 0, 0, dp(18));
        parent.addView(rail, railParams);
    }

    private ScreenShell shell(String selected, String titleValue) {
        root.removeAllViews();

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.HORIZONTAL);
        page.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        page.setBackground(BlofyUi.screenGradient());

        LinearLayout sidebar = buildSidebar(selected);
        page.addView(sidebar, new LinearLayout.LayoutParams(dp(SIDEBAR_WIDTH),
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        main.addView(buildTopBar(titleValue), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(76)));

        FrameLayout content = new FrameLayout(this);
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        main.addView(content, contentParams);

        page.addView(main, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        root.addView(page, match());
        return new ScreenShell(content);
    }

    private LinearLayout buildSidebar(String selected) {
        LinearLayout sidebar = new LinearLayout(this);
        sidebar.setOrientation(LinearLayout.VERTICAL);
        sidebar.setPadding(dp(14), dp(16), dp(14), dp(16));
        sidebar.setBackground(BlofyUi.gradientPanel(this, Color.rgb(13, 8, 27),
                Color.rgb(6, 5, 15), 0, BlofyUi.DIVIDER));

        LinearLayout brand = BlofyUi.brand(this, "P L A Y E R");
        sidebar.addView(brand, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));

        TextView menu = BlofyUi.text(this, "القائمة", 10, Color.rgb(123, 113, 144));
        menu.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        menu.setTextDirection(View.TEXT_DIRECTION_RTL);
        LinearLayout.LayoutParams menuParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(32));
        menuParams.setMargins(dp(5), dp(8), 0, dp(3));
        sidebar.addView(menu, menuParams);

        addSidebarItem(sidebar, "⌂", "الرئيسية", "home".equals(selected), this::showHome);
        addSidebarItem(sidebar, "◉", "البث المباشر", "live".equals(selected), this::showLive);
        addSidebarItem(sidebar, "▶", "الأفلام", "movies".equals(selected),
                () -> showCatalog("movies", false));
        addSidebarItem(sidebar, "▣", "المسلسلات", "series".equals(selected),
                () -> showCatalog("series", false));
        addSidebarItem(sidebar, "★", "المفضلة", "favorites".equals(selected), this::showFavorites);
        addSidebarItem(sidebar, "◷", "المشاهدة لاحقاً", "history".equals(selected), this::showHistory);
        addSidebarItem(sidebar, "EPG", "دليل البرامج", false, this::showLive);
        addSidebarItem(sidebar, "⚙", "الإعدادات", false, this::openLegacySettings);

        View spacer = new View(this);
        sidebar.addView(spacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        sidebar.addView(buildDeviceCard(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(128)));
        return sidebar;
    }

    private void addSidebarItem(LinearLayout sidebar, String icon, String label,
                                boolean selected, Runnable action) {
        TextView item = BlofyUi.sidebarItem(this, icon, label, selected);
        item.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        params.setMargins(0, dp(2), 0, dp(2));
        sidebar.addView(item, params);
    }

    private LinearLayout buildDeviceCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(11), dp(14), dp(10));
        card.setBackground(BlofyUi.gradientPanel(this, Color.rgb(43, 22, 77),
                Color.rgb(21, 13, 39), 15, Color.rgb(83, 48, 133)));

        TextView state = BlofyUi.text(this, "●  الجهاز متصل", 11, BlofyUi.SUCCESS);
        state.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        state.setTextDirection(View.TEXT_DIRECTION_RTL);
        card.addView(state, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)));

        TextView server = BlofyUi.title(this, database.metadata("server_name", "BLOFY Playlist"), 12);
        server.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        server.setTextDirection(View.TEXT_DIRECTION_LTR);
        server.setSingleLine(true);
        server.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(server, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)));

        TextView device = BlofyUi.text(this, DeviceIdentity.id(this), 9, BlofyUi.PURPLE_LIGHT);
        device.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        device.setTextDirection(View.TEXT_DIRECTION_LTR);
        device.setSingleLine(true);
        device.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        card.addView(device, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)));

        TextView counts = BlofyUi.text(this,
                database.count("live") + " LIVE  •  " + (database.count("movies") + database.count("series")) + " VOD",
                9, Color.rgb(180, 169, 198));
        counts.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        counts.setTextDirection(View.TEXT_DIRECTION_LTR);
        card.addView(counts, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(22)));
        return card;
    }

    private LinearLayout buildTopBar(String titleValue) {
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        top.setPadding(dp(26), dp(10), dp(24), dp(8));
        top.setBackground(BlofyUi.panel(this, Color.argb(185, 7, 6, 15), 0, BlofyUi.DIVIDER));

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        TextView title = BlofyUi.title(this, titleValue, 23);
        title.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        title.setTextDirection(View.TEXT_DIRECTION_RTL);
        TextView welcome = BlofyUi.text(this, "مرحباً بك في BLOFY", 10, BlofyUi.MUTED);
        welcome.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        welcome.setTextDirection(View.TEXT_DIRECTION_RTL);
        heading.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(31)));
        heading.addView(welcome, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(20)));
        top.addView(heading, new LinearLayout.LayoutParams(0, dp(56), 1));

        TextView status = BlofyUi.chip(this, "●  BLOFY NATIVE");
        status.setTextColor(BlofyUi.SUCCESS);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(dp(132), dp(32));
        statusParams.setMargins(0, 0, dp(10), 0);
        top.addView(status, statusParams);
        addTopAction(top, "⌕  بحث", () -> showCatalog("movies", true), 96);
        addTopAction(top, "⚙", this::openLegacySettings, 50);
        addTopAction(top, "↻", this::openLegacyRefresh, 50);
        return top;
    }

    private void addTopAction(LinearLayout top, String label, Runnable action, int width) {
        TextView button = BlofyUi.navChip(this, label);
        button.setTextSize(12);
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(width), dp(42));
        params.setMargins(dp(4), 0, dp(4), 0);
        top.addView(button, params);
    }

    private void showLive() {
        releasePreview();
        screen = "live";
        ScreenShell shell = shell("live", "البث المباشر");

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(6), dp(24), dp(20));

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        TextView count = BlofyUi.text(this, formatCount(database.count("live"), "قناة متاحة"), 12, BlofyUi.MUTED);
        count.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        tools.addView(count, new LinearLayout.LayoutParams(dp(220), dp(50)));
        EditText search = BlofyUi.input(this, "ابحث باسم أو رقم القناة", false);
        tools.addView(search, new LinearLayout.LayoutParams(0, dp(48), 1));
        page.addView(tools, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)));

        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        columns.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);

        LinearLayout categoryPanel = columnPanel("التصنيفات");
        RecyclerView cats = new RecyclerView(this);
        cats.setLayoutManager(new LinearLayoutManager(this));
        cats.setItemAnimator(null);
        cats.setClipToPadding(false);
        cats.setPadding(dp(5), dp(3), dp(5), dp(8));
        List<BlofyModels.Category> categoryRows = new ArrayList<>();
        categoryRows.add(new BlofyModels.Category("", "الكل  •  " + database.count("live"), "live"));
        categoryRows.addAll(database.categories("live"));
        CategoryListAdapter catAdapter = new CategoryListAdapter(categoryRows);
        cats.setAdapter(catAdapter);
        categoryPanel.addView(cats, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        columns.addView(categoryPanel, new LinearLayout.LayoutParams(dp(224), ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout channelsPanel = columnPanel("القنوات");
        RecyclerView channels = new RecyclerView(this);
        channels.setLayoutManager(new LinearLayoutManager(this));
        channels.setItemAnimator(null);
        channels.setClipToPadding(false);
        channels.setPadding(dp(4), dp(3), dp(4), dp(8));
        LiveListAdapter liveAdapter = new LiveListAdapter();
        channels.setAdapter(liveAdapter);
        channelsPanel.addView(channels, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout.LayoutParams channelParams = new LinearLayout.LayoutParams(dp(338),
                ViewGroup.LayoutParams.MATCH_PARENT);
        channelParams.setMargins(dp(10), 0, dp(10), 0);
        columns.addView(channelsPanel, channelParams);

        LinearLayout previewPanel = new LinearLayout(this);
        previewPanel.setOrientation(LinearLayout.VERTICAL);
        previewPanel.setPadding(dp(12), dp(12), dp(12), dp(10));
        previewPanel.setBackground(BlofyUi.gradientPanel(this, Color.rgb(12, 10, 23),
                Color.rgb(7, 7, 14), 16, BlofyUi.STROKE));

        FrameLayout previewFrame = new FrameLayout(this);
        previewFrame.setBackgroundColor(Color.BLACK);
        livePreview = new LivePreviewController(this);
        previewFrame.addView(livePreview.view(), match());
        ImageView fallbackLogo = new ImageView(this);
        fallbackLogo.setImageResource(R.drawable.blofy_logo);
        fallbackLogo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        fallbackLogo.setPadding(dp(74), dp(74), dp(74), dp(74));
        previewFrame.addView(fallbackLogo, match());
        previewPanel.addView(previewFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        TextView now = BlofyUi.text(this, "يعرض الآن", 10, BlofyUi.PURPLE_LIGHT);
        now.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        now.setTextDirection(View.TEXT_DIRECTION_RTL);
        previewPanel.addView(now, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)));
        TextView channelName = BlofyUi.title(this, "اختر قناة للمعاينة", 20);
        channelName.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        channelName.setTextDirection(View.TEXT_DIRECTION_LTR);
        channelName.setSingleLine(true);
        channelName.setEllipsize(TextUtils.TruncateAt.END);
        previewPanel.addView(channelName, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
        TextView hint = BlofyUi.text(this, "↑↓ تنقل  •  OK تشغيل ملء الشاشة", 11, BlofyUi.MUTED);
        hint.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        hint.setTextDirection(View.TEXT_DIRECTION_RTL);
        previewPanel.addView(hint, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)));
        columns.addView(previewPanel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1));

        liveAdapter.listener = item -> {
            channelName.setText(item.name);
            fallbackLogo.setVisibility(View.GONE);
            if (livePreview != null) livePreview.preview(item);
        };
        catAdapter.listener = category -> {
            liveAdapter.reload(category.id, search.getText().toString());
            channels.post(() -> {
                if (liveAdapter.getItemCount() > 0) channels.requestFocus();
            });
        };
        search.setOnEditorActionListener((v, action, event) -> {
            liveAdapter.reload(liveAdapter.category, search.getText().toString());
            return true;
        });

        page.addView(columns, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        shell.content.addView(page, match());
        liveAdapter.reload("", "");
        cats.requestFocus();
    }

    private LinearLayout columnPanel(String titleValue) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(BlofyUi.panel(this, Color.argb(205, 14, 12, 25), 16, BlofyUi.STROKE));
        TextView title = BlofyUi.title(this, titleValue, 13);
        title.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        title.setTextDirection(View.TEXT_DIRECTION_RTL);
        title.setPadding(dp(14), 0, dp(14), 0);
        panel.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(45)));
        return panel;
    }

    private void showCatalog(String type, boolean focusSearch) {
        releasePreview();
        screen = type;
        String titleValue = "series".equals(type) ? "المسلسلات" : "الأفلام";
        ScreenShell shell = shell(type, titleValue);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(6), dp(24), dp(20));

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        TextView count = BlofyUi.text(this, formatCount(database.count(type),
                "series".equals(type) ? "مسلسل" : "فيلم"), 12, BlofyUi.MUTED);
        count.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        tools.addView(count, new LinearLayout.LayoutParams(dp(220), dp(50)));
        EditText search = BlofyUi.input(this, "ابحث في " + titleValue, false);
        tools.addView(search, new LinearLayout.LayoutParams(0, dp(48), 1));
        page.addView(tools, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);

        LinearLayout categoryPanel = columnPanel("التصنيفات");
        RecyclerView cats = new RecyclerView(this);
        cats.setLayoutManager(new LinearLayoutManager(this));
        cats.setItemAnimator(null);
        cats.setClipToPadding(false);
        cats.setPadding(dp(5), dp(3), dp(5), dp(8));
        List<BlofyModels.Category> rows = new ArrayList<>();
        rows.add(new BlofyModels.Category("", "الكل  •  " + database.count(type), type));
        rows.addAll(database.categories(type));
        CategoryListAdapter catAdapter = new CategoryListAdapter(rows);
        cats.setAdapter(catAdapter);
        categoryPanel.addView(cats, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        body.addView(categoryPanel, new LinearLayout.LayoutParams(dp(224), ViewGroup.LayoutParams.MATCH_PARENT));

        RecyclerView media = new RecyclerView(this);
        media.setLayoutManager(new GridLayoutManager(this, 5));
        media.setItemAnimator(null);
        media.setHasFixedSize(true);
        media.setItemViewCacheSize(24);
        media.setClipToPadding(false);
        media.setPadding(dp(8), 0, dp(4), dp(16));
        PosterAdapter adapter = new PosterAdapter(type, false, false);
        media.setAdapter(adapter);
        LinearLayout.LayoutParams mediaParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        mediaParams.setMargins(dp(12), 0, 0, 0);
        body.addView(media, mediaParams);

        catAdapter.listener = category -> {
            adapter.reload(category.id, search.getText().toString());
            media.post(() -> {
                if (adapter.getItemCount() > 0) media.requestFocus();
            });
        };
        search.setOnEditorActionListener((v, action, event) -> {
            adapter.reload(adapter.category, search.getText().toString());
            return true;
        });

        page.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        shell.content.addView(page, match());
        adapter.reload("", "");
        if (focusSearch) search.requestFocus(); else cats.requestFocus();
    }

    private void showFavorites() {
        showSpecial(true, false, "المفضلة", "favorites");
    }

    private void showHistory() {
        showSpecial(false, true, "متابعة المشاهدة", "history");
    }

    private void showSpecial(boolean favorites, boolean history, String titleValue, String route) {
        releasePreview();
        screen = route;
        ScreenShell shell = shell(route, titleValue);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(24), dp(12), dp(26), dp(22));

        LinearLayout intro = new LinearLayout(this);
        intro.setOrientation(LinearLayout.VERTICAL);
        TextView title = BlofyUi.title(this, titleValue, 24);
        title.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        title.setTextDirection(View.TEXT_DIRECTION_RTL);
        TextView subtitle = BlofyUi.text(this,
                favorites ? "كل ما حفظته في مكتبتك الخاصة" : "آخر ما شاهدته، مرتب من الأحدث",
                12, BlofyUi.MUTED);
        subtitle.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        subtitle.setTextDirection(View.TEXT_DIRECTION_RTL);
        intro.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));
        intro.addView(subtitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(26)));
        page.addView(intro, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)));

        RecyclerView media = new RecyclerView(this);
        media.setLayoutManager(new GridLayoutManager(this, 5));
        media.setItemAnimator(null);
        media.setHasFixedSize(true);
        media.setItemViewCacheSize(24);
        media.setClipToPadding(false);
        media.setPadding(dp(4), dp(2), dp(4), dp(18));
        PosterAdapter adapter = new PosterAdapter("", favorites, history);
        media.setAdapter(adapter);
        adapter.reload("", "");
        page.addView(media, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        shell.content.addView(page, match());
        media.requestFocus();
    }

    private void routeMedia(BlofyModels.Media item) {
        if ("live".equals(item.type)) play(item); else openDetails(item);
    }

    private void play(BlofyModels.Media item) {
        releasePreview();
        database.addHistory(item.type, item.id);
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra(PlayerActivity.EXTRA_ID, item.id);
        intent.putExtra(PlayerActivity.EXTRA_TITLE, item.name);
        intent.putExtra(PlayerActivity.EXTRA_KIND, item.type);
        intent.putExtra(PlayerActivity.EXTRA_EXTENSION, item.extension);
        startActivity(intent);
    }

    private void openDetails(BlofyModels.Media item) {
        Intent intent = new Intent(this, DetailsActivity.class);
        intent.putExtra(DetailsActivity.EXTRA_ITEM, item.json().toString());
        startActivity(intent);
    }

    private void releasePreview() {
        if (livePreview == null) return;
        livePreview.release();
        livePreview = null;
    }

    private void openLegacySettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    private void openLegacyRefresh() {
        ToastBridge.show(this, "تحديث الباقة سيبقى بدون تغيير حتى اكتمال واجهة BLOFY الجديدة");
    }

    private String formatCount(int count, String noun) {
        return String.format(Locale.US, "%,d %s", count, noun);
    }

    private String formatMeta(BlofyModels.Media item) {
        List<String> values = new ArrayList<>();
        if (!TextUtils.isEmpty(item.year)) values.add(item.year);
        if (!TextUtils.isEmpty(item.rating)) values.add("★ " + item.rating);
        values.add("series".equals(item.type) ? "مسلسل" : "movies".equals(item.type) ? "فيلم" : "بث مباشر");
        return TextUtils.join("  •  ", values);
    }

    private int dp(int value) {
        return BlofyUi.dp(this, value);
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    @Override public void onBackPressed() {
        if ("home".equals(screen)) finishAffinity(); else showHome();
    }

    @Override protected void onDestroy() {
        releasePreview();
        database.close();
        super.onDestroy();
    }

    private static final class ScreenShell {
        final FrameLayout content;
        ScreenShell(FrameLayout content) {
            this.content = content;
        }
    }

    private interface CategoryListener {
        void selected(BlofyModels.Category category);
    }

    private interface LiveListener {
        void selected(BlofyModels.Media media);
    }

    private final class CategoryListAdapter extends RecyclerView.Adapter<CategoryListAdapter.Holder> {
        final List<BlofyModels.Category> rows;
        CategoryListener listener;
        int selectedPosition;

        CategoryListAdapter(List<BlofyModels.Category> rows) {
            this.rows = rows;
        }

        @Override public Holder onCreateViewHolder(ViewGroup parent, int type) {
            TextView item = BlofyUi.title(parent.getContext(), "", 12);
            item.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            item.setTextDirection(View.TEXT_DIRECTION_RTL);
            item.setFocusable(true);
            item.setClickable(true);
            item.setSingleLine(true);
            item.setEllipsize(TextUtils.TruncateAt.END);
            item.setPadding(dp(14), 0, dp(14), 0);
            item.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(47)));
            BlofyUi.attachScaleFocus(item, 1.015f);
            return new Holder(item);
        }

        @Override public void onBindViewHolder(Holder holder, int position) {
            BlofyModels.Category category = rows.get(position);
            boolean selected = position == selectedPosition;
            holder.item.setText(category.name);
            holder.item.setTextColor(selected ? BlofyUi.TEXT : Color.rgb(203, 198, 215));
            holder.item.setBackground(BlofyUi.focusDrawable(SevenMaxActivity.this,
                    selected ? Color.rgb(62, 24, 114) : Color.TRANSPARENT,
                    BlofyUi.PANEL_SOFT, BlofyUi.PURPLE_LIGHT));
            holder.item.setOnClickListener(v -> {
                int previous = selectedPosition;
                selectedPosition = holder.getBindingAdapterPosition();
                if (previous >= 0) notifyItemChanged(previous);
                if (selectedPosition >= 0) notifyItemChanged(selectedPosition);
                if (listener != null) listener.selected(category);
            });
        }

        @Override public int getItemCount() {
            return rows.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final TextView item;
            Holder(TextView item) {
                super(item);
                this.item = item;
            }
        }
    }

    private final class LiveListAdapter extends RecyclerView.Adapter<LiveListAdapter.Holder> {
        final List<BlofyModels.Media> rows = new ArrayList<>();
        String category = "";
        String query = "";
        boolean exhausted;
        LiveListener listener;

        void reload(String category, String query) {
            this.category = category == null ? "" : category;
            this.query = query == null ? "" : query;
            rows.clear();
            exhausted = false;
            loadMore();
        }

        void loadMore() {
            if (exhausted) return;
            int offset = rows.size();
            List<BlofyModels.Media> next = database.media("live", category, query,
                    false, false, LIVE_PAGE, offset);
            if (next.size() < LIVE_PAGE) exhausted = true;
            if (next.isEmpty()) {
                if (offset == 0) notifyDataSetChanged();
                return;
            }
            rows.addAll(next);
            if (offset == 0) notifyDataSetChanged();
            else notifyItemRangeInserted(offset, next.size());
        }

        @Override public Holder onCreateViewHolder(ViewGroup parent, int type) {
            LinearLayout card = new LinearLayout(parent.getContext());
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            card.setFocusable(true);
            card.setClickable(true);
            card.setPadding(dp(8), dp(5), dp(10), dp(5));
            card.setBackground(BlofyUi.focusDrawable(SevenMaxActivity.this,
                    Color.argb(112, 26, 22, 39), BlofyUi.PANEL_SOFT, BlofyUi.PURPLE_LIGHT));

            TextView number = BlofyUi.text(parent.getContext(), "", 10, BlofyUi.MUTED);
            number.setGravity(Gravity.CENTER);
            number.setTextDirection(View.TEXT_DIRECTION_LTR);
            card.addView(number, new LinearLayout.LayoutParams(dp(34), ViewGroup.LayoutParams.MATCH_PARENT));

            ImageView logo = new ImageView(parent.getContext());
            logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
            logo.setBackground(BlofyUi.panel(SevenMaxActivity.this, BlofyUi.PANEL_ALT, 9, BlofyUi.STROKE));
            card.addView(logo, new LinearLayout.LayoutParams(dp(40), dp(40)));

            TextView name = BlofyUi.title(parent.getContext(), "", 12);
            name.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            name.setTextDirection(View.TEXT_DIRECTION_RTL);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1);
            nameParams.setMargins(dp(8), 0, 0, 0);
            card.addView(name, nameParams);

            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
            params.setMargins(dp(3), dp(2), dp(3), dp(2));
            card.setLayoutParams(params);
            return new Holder(card, number, logo, name);
        }

        @Override public void onBindViewHolder(Holder holder, int position) {
            if (position >= rows.size() - 20) loadMore();
            BlofyModels.Media media = rows.get(position);
            holder.number.setText(String.valueOf(position + 1));
            holder.name.setText(media.name);
            images.load(holder.logo, media.image);
            holder.card.setScaleX(1f);
            holder.card.setScaleY(1f);
            holder.card.setOnFocusChangeListener((v, focused) -> {
                float target = focused ? 1.018f : 1f;
                v.animate().scaleX(target).scaleY(target).setDuration(105).start();
                v.setElevation(focused ? dp(12) : 0);
                if (focused && listener != null) listener.selected(media);
            });
            holder.card.setOnClickListener(v -> play(media));
        }

        @Override public int getItemCount() {
            return rows.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final LinearLayout card;
            final TextView number;
            final ImageView logo;
            final TextView name;

            Holder(LinearLayout card, TextView number, ImageView logo, TextView name) {
                super(card);
                this.card = card;
                this.number = number;
                this.logo = logo;
                this.name = name;
            }
        }
    }

    private final class HomeRailAdapter extends RecyclerView.Adapter<HomeRailAdapter.Holder> {
        final String type;
        final boolean history;
        final boolean landscape;
        final List<BlofyModels.Media> rows = new ArrayList<>();
        boolean exhausted;

        HomeRailAdapter(String type, boolean history, boolean landscape) {
            this.type = type;
            this.history = history;
            this.landscape = landscape;
        }

        void reload() {
            rows.clear();
            exhausted = false;
            loadMore();
        }

        void loadMore() {
            if (exhausted) return;
            int offset = rows.size();
            List<BlofyModels.Media> next = database.media(type, "", "", false,
                    history, POSTER_PAGE, offset);
            if (next.size() < POSTER_PAGE) exhausted = true;
            if (next.isEmpty()) {
                if (offset == 0) notifyDataSetChanged();
                return;
            }
            rows.addAll(next);
            if (offset == 0) notifyDataSetChanged();
            else notifyItemRangeInserted(offset, next.size());
        }

        @Override public Holder onCreateViewHolder(ViewGroup parent, int type) {
            LinearLayout card = new LinearLayout(parent.getContext());
            card.setOrientation(LinearLayout.VERTICAL);
            card.setFocusable(true);
            card.setClickable(true);
            card.setPadding(dp(2), dp(2), dp(2), dp(3));
            card.setBackground(BlofyUi.focusDrawable(SevenMaxActivity.this,
                    Color.rgb(15, 13, 27), BlofyUi.PANEL_SOFT, BlofyUi.PURPLE_LIGHT));
            BlofyUi.attachScaleFocus(card, 1.035f);

            ImageView image = new ImageView(parent.getContext());
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(image, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(landscape ? 116 : 196)));

            TextView name = BlofyUi.title(parent.getContext(), "", landscape ? 12 : 11);
            name.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            name.setTextDirection(View.TEXT_DIRECTION_RTL);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(landscape ? 34 : 42)));

            TextView meta = BlofyUi.text(parent.getContext(), "", 9, BlofyUi.MUTED);
            meta.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            meta.setTextDirection(View.TEXT_DIRECTION_LTR);
            meta.setSingleLine(true);
            card.addView(meta, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(landscape ? 20 : 24)));

            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(
                    dp(landscape ? 244 : 150), dp(landscape ? 176 : 270));
            params.setMargins(dp(5), dp(4), dp(8), dp(4));
            card.setLayoutParams(params);
            return new Holder(card, image, name, meta);
        }

        @Override public void onBindViewHolder(Holder holder, int position) {
            if (position >= rows.size() - 14) loadMore();
            BlofyModels.Media media = rows.get(position);
            holder.name.setText(media.name);
            holder.meta.setText(formatMeta(media));
            String art = landscape && !TextUtils.isEmpty(media.backdrop) ? media.backdrop : media.image;
            images.load(holder.image, art);
            holder.card.setOnClickListener(v -> routeMedia(media));
            holder.card.setOnLongClickListener(v -> {
                database.toggleFavorite(media.type, media.id);
                return true;
            });
        }

        @Override public int getItemCount() {
            return rows.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final LinearLayout card;
            final ImageView image;
            final TextView name;
            final TextView meta;

            Holder(LinearLayout card, ImageView image, TextView name, TextView meta) {
                super(card);
                this.card = card;
                this.image = image;
                this.name = name;
                this.meta = meta;
            }
        }
    }

    private final class PosterAdapter extends RecyclerView.Adapter<PosterAdapter.Holder> {
        final String type;
        final boolean favorites;
        final boolean history;
        final List<BlofyModels.Media> rows = new ArrayList<>();
        String category = "";
        String query = "";
        boolean exhausted;

        PosterAdapter(String type, boolean favorites, boolean history) {
            this.type = type;
            this.favorites = favorites;
            this.history = history;
        }

        void reload(String category, String query) {
            this.category = category == null ? "" : category;
            this.query = query == null ? "" : query;
            rows.clear();
            exhausted = false;
            loadMore();
        }

        void loadMore() {
            if (exhausted) return;
            int offset = rows.size();
            List<BlofyModels.Media> next = database.media(type, category, query,
                    favorites, history, POSTER_PAGE, offset);
            if (next.size() < POSTER_PAGE) exhausted = true;
            if (next.isEmpty()) {
                if (offset == 0) notifyDataSetChanged();
                return;
            }
            rows.addAll(next);
            if (offset == 0) notifyDataSetChanged();
            else notifyItemRangeInserted(offset, next.size());
        }

        @Override public Holder onCreateViewHolder(ViewGroup parent, int type) {
            LinearLayout card = new LinearLayout(parent.getContext());
            card.setOrientation(LinearLayout.VERTICAL);
            card.setFocusable(true);
            card.setClickable(true);
            card.setPadding(dp(2), dp(2), dp(2), dp(3));
            card.setBackground(BlofyUi.focusDrawable(SevenMaxActivity.this,
                    Color.rgb(15, 13, 27), BlofyUi.PANEL_SOFT, BlofyUi.PURPLE_LIGHT));
            BlofyUi.attachScaleFocus(card, 1.035f);

            ImageView image = new ImageView(parent.getContext());
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(image, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(194)));

            TextView name = BlofyUi.title(parent.getContext(), "", 11);
            name.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            name.setTextDirection(View.TEXT_DIRECTION_RTL);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

            TextView meta = BlofyUi.text(parent.getContext(), "", 9, BlofyUi.MUTED);
            meta.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            meta.setTextDirection(View.TEXT_DIRECTION_LTR);
            meta.setSingleLine(true);
            card.addView(meta, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)));

            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(dp(6), dp(5), dp(6), dp(7));
            card.setLayoutParams(params);
            return new Holder(card, image, name, meta);
        }

        @Override public void onBindViewHolder(Holder holder, int position) {
            if (position >= rows.size() - 18) loadMore();
            BlofyModels.Media media = rows.get(position);
            holder.name.setText(media.name);
            holder.meta.setText(formatMeta(media));
            images.load(holder.image, media.image);
            holder.card.setOnClickListener(v -> routeMedia(media));
            holder.card.setOnLongClickListener(v -> {
                database.toggleFavorite(media.type, media.id);
                return true;
            });
        }

        @Override public int getItemCount() {
            return rows.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final LinearLayout card;
            final ImageView image;
            final TextView name;
            final TextView meta;

            Holder(LinearLayout card, ImageView image, TextView name, TextView meta) {
                super(card);
                this.card = card;
                this.image = image;
                this.name = name;
                this.meta = meta;
            }
        }
    }
}
