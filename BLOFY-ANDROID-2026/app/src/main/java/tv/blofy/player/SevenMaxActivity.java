package tv.blofy.player;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SevenMaxActivity extends Activity {
    private static final int LIVE_PAGE = 140;
    private static final int POSTER_PAGE = 80;

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
        root.removeAllViews();
        LinearLayout page = shell(false);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setGravity(Gravity.CENTER_VERTICAL);
        body.setPadding(dp(38), dp(26), dp(38), dp(34));

        LinearLayout primary = new LinearLayout(this);
        primary.setOrientation(LinearLayout.VERTICAL);
        primary.setGravity(Gravity.CENTER);
        primary.setFocusable(true);
        primary.setClickable(true);
        primary.setPadding(dp(26), dp(24), dp(26), dp(24));
        primary.setBackground(BlofyUi.focusDrawable(this, Color.rgb(18, 23, 34), Color.rgb(61, 39, 94), Color.WHITE));
        TextView liveIcon = BlofyUi.title(this, "◉", 46);
        liveIcon.setGravity(Gravity.CENTER);
        TextView liveTitle = BlofyUi.title(this, "بث مباشر", 28);
        liveTitle.setGravity(Gravity.CENTER);
        TextView liveCount = BlofyUi.text(this, formatCount(database.count("live"), "قناة"), 14, BlofyUi.MUTED);
        liveCount.setGravity(Gravity.CENTER);
        primary.addView(liveIcon);
        primary.addView(liveTitle);
        primary.addView(liveCount);
        primary.setOnClickListener(v -> showLive());
        body.addView(primary, new LinearLayout.LayoutParams(dp(350), dp(360)));

        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setPadding(dp(22), 0, dp(22), 0);
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        addHomeCard(topRow, "▣", "مسلسلات", formatCount(database.count("series"), "مسلسل"), () -> showCatalog("series", false), 1f);
        addHomeCard(topRow, "▶", "أفلام", formatCount(database.count("movies"), "فيلم"), () -> showCatalog("movies", false), 1f);
        center.addView(topRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(174)));
        LinearLayout bottomRow = new LinearLayout(this);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        addHomeCard(bottomRow, "★", "المفضلة", "محتواك المحفوظ", this::showFavorites, 1f);
        addHomeCard(bottomRow, "◷", "متابعة المشاهدة", "ارجع لنفس مكانك", this::showHistory, 1f);
        center.addView(bottomRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(174)));
        body.addView(center, new LinearLayout.LayoutParams(0, dp(360), 1));

        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setGravity(Gravity.CENTER_VERTICAL);
        addSideButton(side, "⌕  البحث", () -> showCatalog("movies", true));
        addSideButton(side, "⚙  الإعدادات", this::openLegacySettings);
        addSideButton(side, "↻  تحديث الباقة", this::openLegacyRefresh);
        body.addView(side, new LinearLayout.LayoutParams(dp(245), dp(360)));

        page.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(page, match());
        primary.requestFocus();
    }

    private LinearLayout shell(boolean withTabs) {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackground(BlofyUi.screenGradient());

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(22), dp(8), dp(22), dp(8));
        top.setBackground(BlofyUi.panel(this, Color.rgb(12, 16, 25), 8, Color.rgb(43, 49, 67)));

        top.addView(BlofyUi.brand(this, "P L A Y E R"), new LinearLayout.LayoutParams(dp(220), dp(60)));
        if (withTabs) {
            LinearLayout nav = new LinearLayout(this);
            nav.setOrientation(LinearLayout.HORIZONTAL);
            nav.setGravity(Gravity.CENTER_VERTICAL);
            addNavButton(nav, "الرئيسية", this::showHome);
            addNavButton(nav, "بث مباشر", this::showLive);
            addNavButton(nav, "أفلام", () -> showCatalog("movies", false));
            addNavButton(nav, "مسلسلات", () -> showCatalog("series", false));
            top.addView(nav, new LinearLayout.LayoutParams(0, dp(60), 1));
        } else {
            TextView center = BlofyUi.text(this, database.metadata("server_name", "Playlist"), 13, BlofyUi.MUTED);
            center.setGravity(Gravity.CENTER);
            top.addView(center, new LinearLayout.LayoutParams(0, dp(60), 1));
        }
        TextView status = BlofyUi.text(this, "BLOFY • Native", 12, BlofyUi.PURPLE_LIGHT);
        status.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        status.setTextDirection(View.TEXT_DIRECTION_LTR);
        top.addView(status, new LinearLayout.LayoutParams(dp(180), dp(60)));

        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76));
        tp.setMargins(dp(16), dp(12), dp(16), dp(4));
        page.addView(top, tp);
        return page;
    }

    private void showLive() {
        releasePreview();
        screen = "live";
        root.removeAllViews();
        LinearLayout page = shell(true);

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.setPadding(dp(18), dp(4), dp(18), dp(8));
        TextView title = BlofyUi.title(this, "البث المباشر", 19);
        tools.addView(title, new LinearLayout.LayoutParams(dp(190), dp(50)));
        EditText search = BlofyUi.input(this, "بحث باسم أو رقم القناة", false);
        tools.addView(search, new LinearLayout.LayoutParams(0, dp(50), 1));
        page.addView(tools);

        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        columns.setPadding(dp(16), 0, dp(16), dp(16));

        RecyclerView cats = new RecyclerView(this);
        cats.setLayoutManager(new LinearLayoutManager(this));
        cats.setItemAnimator(null);
        List<BlofyModels.Category> categoryRows = new ArrayList<>();
        categoryRows.add(new BlofyModels.Category("", "ALL  •  " + database.count("live"), "live"));
        categoryRows.addAll(database.categories("live"));
        CategoryListAdapter catAdapter = new CategoryListAdapter(categoryRows);
        cats.setAdapter(catAdapter);
        cats.setBackground(BlofyUi.panel(this, Color.rgb(12, 17, 26), 5, Color.rgb(49, 56, 73)));
        columns.addView(cats, new LinearLayout.LayoutParams(dp(315), ViewGroup.LayoutParams.MATCH_PARENT));

        RecyclerView channels = new RecyclerView(this);
        channels.setLayoutManager(new LinearLayoutManager(this));
        channels.setItemAnimator(null);
        LiveListAdapter liveAdapter = new LiveListAdapter();
        channels.setAdapter(liveAdapter);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(410), ViewGroup.LayoutParams.MATCH_PARENT);
        cp.setMargins(dp(8), 0, dp(8), 0);
        columns.addView(channels, cp);

        LinearLayout previewPanel = new LinearLayout(this);
        previewPanel.setOrientation(LinearLayout.VERTICAL);
        previewPanel.setPadding(dp(12), dp(12), dp(12), dp(12));
        previewPanel.setBackground(BlofyUi.panel(this, Color.rgb(10, 14, 22), 5, Color.rgb(49, 56, 73)));

        FrameLayout previewFrame = new FrameLayout(this);
        previewFrame.setBackgroundColor(Color.BLACK);
        livePreview = new LivePreviewController(this);
        previewFrame.addView(livePreview.view(), match());
        ImageView fallbackLogo = new ImageView(this);
        fallbackLogo.setImageResource(R.drawable.blofy_logo);
        fallbackLogo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        previewFrame.addView(fallbackLogo, match());
        previewPanel.addView(previewFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        TextView channelName = BlofyUi.title(this, "اختر قناة", 22);
        channelName.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        channelName.setTextDirection(View.TEXT_DIRECTION_LTR);
        previewPanel.addView(channelName, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        TextView hint = BlofyUi.text(this, "↑↓ تنقل بين القنوات  •  OK تشغيل ملء الشاشة", 12, BlofyUi.MUTED);
        hint.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        previewPanel.addView(hint, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));
        columns.addView(previewPanel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));

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
        root.addView(page, match());
        liveAdapter.reload("", "");
        cats.requestFocus();
    }

    private void showCatalog(String type, boolean focusSearch) {
        releasePreview();
        screen = type;
        root.removeAllViews();
        LinearLayout page = shell(true);

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.setPadding(dp(18), dp(4), dp(18), dp(8));
        TextView title = BlofyUi.title(this, "series".equals(type) ? "المسلسلات" : "الأفلام", 19);
        tools.addView(title, new LinearLayout.LayoutParams(dp(190), dp(50)));
        EditText search = BlofyUi.input(this, "بحث", false);
        tools.addView(search, new LinearLayout.LayoutParams(0, dp(50), 1));
        page.addView(tools);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setPadding(dp(16), 0, dp(16), dp(16));

        RecyclerView cats = new RecyclerView(this);
        cats.setLayoutManager(new LinearLayoutManager(this));
        cats.setItemAnimator(null);
        List<BlofyModels.Category> rows = new ArrayList<>();
        rows.add(new BlofyModels.Category("", "ALL  •  " + database.count(type), type));
        rows.addAll(database.categories(type));
        CategoryListAdapter catAdapter = new CategoryListAdapter(rows);
        cats.setAdapter(catAdapter);
        cats.setBackground(BlofyUi.panel(this, Color.rgb(12, 17, 26), 5, Color.rgb(49, 56, 73)));
        body.addView(cats, new LinearLayout.LayoutParams(dp(315), ViewGroup.LayoutParams.MATCH_PARENT));

        RecyclerView media = new RecyclerView(this);
        media.setLayoutManager(new GridLayoutManager(this, 5));
        media.setItemAnimator(null);
        media.setHasFixedSize(true);
        media.setItemViewCacheSize(24);
        PosterAdapter adapter = new PosterAdapter(type, false, false);
        media.setAdapter(adapter);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        mp.setMargins(dp(12), 0, 0, 0);
        body.addView(media, mp);

        catAdapter.listener = category -> {
            adapter.reload(category.id, search.getText().toString());
            media.post(() -> { if (adapter.getItemCount() > 0) media.requestFocus(); });
        };
        search.setOnEditorActionListener((v, action, event) -> {
            adapter.reload(adapter.category, search.getText().toString());
            return true;
        });

        page.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(page, match());
        adapter.reload("", "");
        if (focusSearch) search.requestFocus(); else cats.requestFocus();
    }

    private void showFavorites() { showSpecial(true, false, "المفضلة"); }
    private void showHistory() { showSpecial(false, true, "متابعة المشاهدة"); }

    private void showSpecial(boolean fav, boolean hist, String titleText) {
        releasePreview();
        screen = "special";
        root.removeAllViews();
        LinearLayout page = shell(true);
        TextView title = BlofyUi.title(this, titleText, 22);
        title.setPadding(dp(24), dp(10), dp(24), dp(10));
        page.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62)));
        RecyclerView media = new RecyclerView(this);
        media.setLayoutManager(new GridLayoutManager(this, 5));
        media.setItemAnimator(null);
        PosterAdapter adapter = new PosterAdapter("", fav, hist);
        media.setAdapter(adapter);
        adapter.reload("", "");
        page.addView(media, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(page, match());
        media.requestFocus();
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

    private void openLegacySettings() { ToastBridge.show(this, "الإعدادات الجديدة قيد إعادة البناء"); }
    private void openLegacyRefresh() { ToastBridge.show(this, "تحديث الباقة سيبقى بدون تغيير حتى اكتمال واجهة BLOFY الجديدة"); }

    private void addHomeCard(LinearLayout row, String icon, String title, String sub, Runnable action, float weight) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setFocusable(true);
        card.setClickable(true);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        card.setBackground(BlofyUi.focusDrawable(this, Color.rgb(18, 23, 34), Color.rgb(61, 39, 94), Color.WHITE));
        TextView iconView = BlofyUi.title(this, icon, 34);
        iconView.setGravity(Gravity.CENTER);
        TextView titleView = BlofyUi.title(this, title, 20);
        titleView.setGravity(Gravity.CENTER);
        TextView subView = BlofyUi.text(this, sub, 12, BlofyUi.MUTED);
        subView.setGravity(Gravity.CENTER);
        card.addView(iconView);
        card.addView(titleView);
        card.addView(subView);
        card.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight);
        params.setMargins(dp(6), dp(6), dp(6), dp(6));
        row.addView(card, params);
    }

    private void addSideButton(LinearLayout side, String text, Runnable action) {
        Button button = BlofyUi.button(this, text, false);
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72));
        params.setMargins(0, dp(7), 0, dp(7));
        side.addView(button, params);
    }

    private void addNavButton(LinearLayout nav, String text, Runnable action) {
        Button button = BlofyUi.button(this, text, false);
        button.setTextSize(13);
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(46), 1);
        params.setMargins(dp(4), 0, dp(4), 0);
        nav.addView(button, params);
    }

    private String formatCount(int count, String noun) {
        return String.format(Locale.US, "%,d %s", count, noun);
    }

    private int dp(int value) { return BlofyUi.dp(this, value); }
    private FrameLayout.LayoutParams match() { return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT); }

    @Override public void onBackPressed() {
        if ("home".equals(screen)) finishAffinity(); else showHome();
    }

    @Override protected void onDestroy() {
        releasePreview();
        database.close();
        super.onDestroy();
    }

    private interface CategoryListener { void selected(BlofyModels.Category category); }
    private interface LiveListener { void selected(BlofyModels.Media media); }

    private final class CategoryListAdapter extends RecyclerView.Adapter<CategoryListAdapter.Holder> {
        final List<BlofyModels.Category> rows;
        CategoryListener listener;
        CategoryListAdapter(List<BlofyModels.Category> rows) { this.rows = rows; }
        @Override public Holder onCreateViewHolder(ViewGroup parent, int type) {
            Button button = BlofyUi.button(parent.getContext(), "", false);
            button.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            button.setTextSize(13);
            button.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
            return new Holder(button);
        }
        @Override public void onBindViewHolder(Holder holder, int position) {
            BlofyModels.Category category = rows.get(position);
            holder.button.setText(category.name);
            holder.button.setOnClickListener(v -> { if (listener != null) listener.selected(category); });
        }
        @Override public int getItemCount() { return rows.size(); }
        final class Holder extends RecyclerView.ViewHolder {
            final Button button;
            Holder(Button button) { super(button); this.button = button; }
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
            List<BlofyModels.Media> next = database.media("live", category, query, false, false, LIVE_PAGE, offset);
            if (next.size() < LIVE_PAGE) exhausted = true;
            if (next.isEmpty()) {
                if (offset == 0) notifyDataSetChanged();
                return;
            }
            rows.addAll(next);
            if (offset == 0) notifyDataSetChanged(); else notifyItemRangeInserted(offset, next.size());
        }

        @Override public Holder onCreateViewHolder(ViewGroup parent, int type) {
            Button button = BlofyUi.button(parent.getContext(), "", false);
            button.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            button.setTextSize(13);
            button.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
            return new Holder(button);
        }

        @Override public void onBindViewHolder(Holder holder, int position) {
            if (position >= rows.size() - 20) loadMore();
            BlofyModels.Media media = rows.get(position);
            holder.button.setText((position + 1) + "   " + media.name);
            holder.button.setOnFocusChangeListener((v, focused) -> {
                if (focused && listener != null) listener.selected(media);
            });
            holder.button.setOnClickListener(v -> play(media));
        }

        @Override public int getItemCount() { return rows.size(); }
        final class Holder extends RecyclerView.ViewHolder {
            final Button button;
            Holder(Button button) { super(button); this.button = button; }
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
            List<BlofyModels.Media> next = database.media(type, category, query, favorites, history, POSTER_PAGE, offset);
            if (next.size() < POSTER_PAGE) exhausted = true;
            if (next.isEmpty()) {
                if (offset == 0) notifyDataSetChanged();
                return;
            }
            rows.addAll(next);
            if (offset == 0) notifyDataSetChanged(); else notifyItemRangeInserted(offset, next.size());
        }

        @Override public Holder onCreateViewHolder(ViewGroup parent, int type) {
            LinearLayout card = new LinearLayout(parent.getContext());
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER);
            card.setFocusable(true);
            card.setClickable(true);
            card.setBackground(BlofyUi.focusDrawable(SevenMaxActivity.this, Color.rgb(17, 22, 32), Color.rgb(62, 45, 89), Color.WHITE));
            ImageView image = new ImageView(parent.getContext());
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(image, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(198)));
            TextView name = BlofyUi.title(parent.getContext(), "", 12);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(2);
            card.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(dp(5), dp(5), dp(5), dp(5));
            card.setLayoutParams(params);
            return new Holder(card, image, name);
        }

        @Override public void onBindViewHolder(Holder holder, int position) {
            if (position >= rows.size() - 18) loadMore();
            BlofyModels.Media media = rows.get(position);
            holder.name.setText(media.name);
            images.load(holder.image, media.image);
            holder.card.setOnClickListener(v -> openDetails(media));
            holder.card.setOnLongClickListener(v -> {
                database.toggleFavorite(media.type, media.id);
                return true;
            });
        }

        @Override public int getItemCount() { return rows.size(); }
        final class Holder extends RecyclerView.ViewHolder {
            final LinearLayout card;
            final ImageView image;
            final TextView name;
            Holder(LinearLayout card, ImageView image, TextView name) {
                super(card);
                this.card = card;
                this.image = image;
                this.name = name;
            }
        }
    }
}
