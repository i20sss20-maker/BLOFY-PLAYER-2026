package tv.blofy.player;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DetailsActivity extends Activity {
    static final String EXTRA_ITEM = "item_json";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private FrameLayout root;
    private BlofyApi api;
    private ImageLoader images;
    private CatalogDatabase database;
    private BlofyModels.Media item;
    private BlofyModels.Detail loadedDetail;
    private boolean seasonsScreen;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        root = new FrameLayout(this);
        root.setBackground(BlofyUi.screenGradient());
        setContentView(root);
        api = new BlofyApi(this);
        images = new ImageLoader(api);
        database = new CatalogDatabase(this);
        try {
            item = BlofyModels.Media.from(new JSONObject(getIntent().getStringExtra(EXTRA_ITEM)), "movies");
        } catch (Exception error) {
            finish();
            return;
        }
        showLoading();
        worker.execute(() -> {
            try {
                String path = "series".equals(item.type) ? "/api/series/" : "/api/movie/";
                BlofyModels.Detail detail = new BlofyModels.Detail(api.get(path + BlofyApi.encode(item.id)), item.type);
                main.post(() -> {
                    loadedDetail = detail;
                    showDetail(detail);
                });
            } catch (Exception error) {
                main.post(() -> showError(error.getMessage()));
            }
        });
    }

    private void showLoading() {
        root.removeAllViews();
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        ProgressBar progress = new ProgressBar(this);
        progress.setIndeterminateTintList(BlofyUi.progressColors());
        panel.addView(progress, new LinearLayout.LayoutParams(dp(54), dp(54)));
        TextView text = BlofyUi.title(this, "جاري تحميل التفاصيل…", 18);
        text.setGravity(Gravity.CENTER);
        panel.addView(text);
        root.addView(panel, match());
    }

    private void showDetail(BlofyModels.Detail detail) {
        seasonsScreen = false;
        root.removeAllViews();

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(28), dp(20), dp(28), dp(26));
        page.setBackground(BlofyUi.screenGradient());

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(BlofyUi.brand(this, "P L A Y E R"), new LinearLayout.LayoutParams(dp(230), dp(60)));
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        Button back = BlofyUi.button(this, "رجوع  ←", false);
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(132), dp(48)));
        page.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(66)));

        FrameLayout hero = new FrameLayout(this);
        hero.setClipToOutline(true);
        hero.setBackground(BlofyUi.panel(this, BlofyUi.PANEL, 18, BlofyUi.STROKE));

        ImageView backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        String heroImage = detail.backdrop.isEmpty() ? item.backdrop : detail.backdrop;
        if (heroImage.isEmpty()) heroImage = detail.image.isEmpty() ? item.image : detail.image;
        images.load(backdrop, heroImage);
        hero.addView(backdrop, match());

        View scrim = new View(this);
        scrim.setBackground(BlofyUi.heroScrim());
        hero.addView(scrim, match());

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.setPadding(dp(38), dp(24), dp(18), dp(24));
        info.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        TextView eyebrow = BlofyUi.title(this,
                "series".equals(detail.type) ? "مسلسل مميز" : "فيلم مميز", 14);
        eyebrow.setTextColor(BlofyUi.PURPLE_LIGHT);
        eyebrow.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        info.addView(eyebrow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)));

        TextView title = BlofyUi.title(this, detail.name.isEmpty() ? item.name : detail.name, 36);
        title.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        title.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        title.setMaxLines(2);
        info.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(90)));

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        chips.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        addMetaChip(chips, detail.year);
        addMetaChip(chips, detail.genre);
        addMetaChip(chips, detail.rating.isEmpty() ? "" : "★ " + detail.rating);
        addMetaChip(chips, detail.duration);
        info.addView(chips, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

        TextView description = BlofyUi.text(this,
                detail.description.isEmpty() ? "لا يوجد وصف متاح." : detail.description,
                15, Color.rgb(219, 216, 226));
        description.setGravity(Gravity.RIGHT | Gravity.TOP);
        description.setTextDirection(View.TEXT_DIRECTION_RTL);
        description.setLineSpacing(0, 1.15f);
        description.setMaxLines(6);
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        descriptionParams.topMargin = dp(8);
        info.addView(description, descriptionParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        actions.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        Button primary = BlofyUi.button(this,
                "series".equals(detail.type) ? "▶  المواسم والحلقات" : "▶  شاهد الآن", true);
        primary.setOnClickListener(v -> {
            if ("series".equals(detail.type)) showSeasons(detail);
            else play(detail.id, detail.name, "movies", detail.extension);
        });
        actions.addView(primary, new LinearLayout.LayoutParams(dp(245), dp(56)));
        Button favorite = BlofyUi.button(this, "♡  أضف للمفضلة", false);
        favorite.setOnClickListener(v -> {
            database.toggleFavorite(item.type, item.id);
            ToastBridge.show(this, "تم تحديث المفضلة");
        });
        LinearLayout.LayoutParams favoriteParams = new LinearLayout.LayoutParams(dp(190), dp(56));
        favoriteParams.leftMargin = dp(12);
        actions.addView(favorite, favoriteParams);
        info.addView(actions, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(66)));

        FrameLayout.LayoutParams infoParams = new FrameLayout.LayoutParams(dp(680),
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.LEFT | Gravity.CENTER_VERTICAL);
        hero.addView(info, infoParams);
        LinearLayout.LayoutParams heroParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        heroParams.topMargin = dp(8);
        page.addView(hero, heroParams);
        root.addView(page, match());
        primary.requestFocus();
    }

    private void addMetaChip(LinearLayout row, String value) {
        if (value == null || value.trim().isEmpty()) return;
        TextView chip = BlofyUi.chip(this, value.trim());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30));
        params.rightMargin = dp(7);
        row.addView(chip, params);
    }

    private void showSeasons(BlofyModels.Detail detail) {
        seasonsScreen = true;
        root.removeAllViews();
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(28), dp(20), dp(28), dp(26));
        page.setBackground(BlofyUi.screenGradient());

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(BlofyUi.brand(this, "P L A Y E R"), new LinearLayout.LayoutParams(dp(230), dp(58)));
        TextView title = BlofyUi.title(this, detail.name + "   •   المواسم والحلقات", 23);
        title.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        title.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        top.addView(title, new LinearLayout.LayoutParams(0, dp(58), 1));
        Button back = BlofyUi.button(this, "التفاصيل  ←", false);
        back.setOnClickListener(v -> showDetail(detail));
        top.addView(back, new LinearLayout.LayoutParams(dp(150), dp(48)));
        page.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(66)));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);

        RecyclerView seasons = new RecyclerView(this);
        seasons.setLayoutManager(new LinearLayoutManager(this));
        seasons.setItemAnimator(null);
        seasons.setPadding(dp(10), dp(10), dp(10), dp(10));
        seasons.setClipToPadding(false);
        seasons.setBackground(BlofyUi.panel(this, Color.argb(225, 12, 10, 23), 16, BlofyUi.STROKE));

        RecyclerView episodes = new RecyclerView(this);
        episodes.setLayoutManager(new LinearLayoutManager(this));
        episodes.setItemAnimator(null);
        episodes.setItemViewCacheSize(12);
        episodes.setPadding(dp(6), 0, dp(6), 0);
        episodes.setClipToPadding(false);
        EpisodeAdapter episodeAdapter = new EpisodeAdapter(detail.name);
        episodes.setAdapter(episodeAdapter);

        SeasonAdapter seasonAdapter = new SeasonAdapter(detail.seasons, season -> {
            episodeAdapter.setEpisodes(season.episodes);
            episodes.post(() -> { if (episodeAdapter.getItemCount() > 0) episodes.requestFocus(); });
        });
        seasons.setAdapter(seasonAdapter);

        body.addView(seasons, new LinearLayout.LayoutParams(dp(270), ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        ep.leftMargin = dp(16);
        body.addView(episodes, ep);
        page.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(page, match());

        if (!detail.seasons.isEmpty()) episodeAdapter.setEpisodes(detail.seasons.get(0).episodes);
        seasons.requestFocus();
    }

    private void play(String id, String title, String type, String extension) {
        database.addHistory(item.type, item.id);
        Intent player = new Intent(this, VodPlayerActivity.class);
        player.putExtra(VodPlayerActivity.EXTRA_ID, id);
        player.putExtra(VodPlayerActivity.EXTRA_TITLE, title);
        player.putExtra(VodPlayerActivity.EXTRA_KIND, type);
        player.putExtra(VodPlayerActivity.EXTRA_EXTENSION, extension);
        startActivity(player);
    }

    private void showError(String message) {
        root.removeAllViews();
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        TextView title = BlofyUi.title(this, "تعذر تحميل التفاصيل", 24);
        title.setGravity(Gravity.CENTER);
        panel.addView(title);
        TextView detail = BlofyUi.text(this, message == null ? "حدث خطأ غير متوقع." : message, 14, BlofyUi.ERROR);
        detail.setGravity(Gravity.CENTER);
        panel.addView(detail);
        Button close = BlofyUi.button(this, "رجوع", true);
        close.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(220), dp(56));
        params.topMargin = dp(16);
        panel.addView(close, params);
        root.addView(panel, match());
        close.requestFocus();
    }

    private static String join(String... values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) continue;
            if (result.length() > 0) result.append("  •  ");
            result.append(value.trim());
        }
        return result.toString();
    }

    private int dp(int value) { return BlofyUi.dp(this, value); }
    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    @Override public void onBackPressed() {
        if (seasonsScreen && loadedDetail != null) showDetail(loadedDetail);
        else finish();
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        database.close();
        super.onDestroy();
    }

    private interface SeasonListener { void selected(BlofyModels.Season season); }

    private final class SeasonAdapter extends RecyclerView.Adapter<SeasonAdapter.Holder> {
        private final List<BlofyModels.Season> rows;
        private final SeasonListener listener;
        private int selected = 0;
        SeasonAdapter(List<BlofyModels.Season> rows, SeasonListener listener) {
            this.rows = rows == null ? new ArrayList<>() : rows;
            this.listener = listener;
        }
        @Override public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            Button button = BlofyUi.button(parent.getContext(), "", false);
            button.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            button.setTextDirection(View.TEXT_DIRECTION_RTL);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
            params.setMargins(dp(3), dp(4), dp(3), dp(4));
            button.setLayoutParams(params);
            return new Holder(button);
        }
        @Override public void onBindViewHolder(Holder holder, int position) {
            BlofyModels.Season season = rows.get(position);
            holder.button.setText("الموسم " + season.number + "   •   " + season.episodes.size() + " حلقة");
            holder.button.setBackground(BlofyUi.focusDrawable(DetailsActivity.this,
                    position == selected ? Color.rgb(55, 20, 103) : Color.TRANSPARENT,
                    BlofyUi.PANEL_SOFT, BlofyUi.PURPLE_LIGHT));
            holder.button.setOnClickListener(v -> {
                int old = selected;
                selected = holder.getBindingAdapterPosition();
                if (old >= 0) notifyItemChanged(old);
                if (selected >= 0) notifyItemChanged(selected);
                listener.selected(season);
            });
        }
        @Override public int getItemCount() { return rows.size(); }
        final class Holder extends RecyclerView.ViewHolder {
            final Button button;
            Holder(Button button) { super(button); this.button = button; }
        }
    }

    private final class EpisodeAdapter extends RecyclerView.Adapter<EpisodeAdapter.Holder> {
        private final String seriesName;
        private List<BlofyModels.Episode> rows = new ArrayList<>();
        EpisodeAdapter(String seriesName) { this.seriesName = seriesName; }
        void setEpisodes(List<BlofyModels.Episode> values) {
            rows = values == null ? new ArrayList<>() : values;
            notifyDataSetChanged();
        }
        @Override public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            LinearLayout card = new LinearLayout(parent.getContext());
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setFocusable(true);
            card.setClickable(true);
            card.setPadding(dp(10), dp(8), dp(12), dp(8));
            card.setBackground(BlofyUi.focusDrawable(DetailsActivity.this,
                    Color.argb(220, 14, 12, 26), BlofyUi.PANEL_SOFT, BlofyUi.PURPLE_LIGHT));
            ImageView image = new ImageView(parent.getContext());
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(image, new LinearLayout.LayoutParams(dp(210), dp(104)));
            TextView text = BlofyUi.title(parent.getContext(), "", 15);
            text.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            text.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
            text.setMaxLines(2);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, dp(104), 1);
            textParams.leftMargin = dp(16);
            card.addView(text, textParams);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(124));
            params.setMargins(dp(5), dp(4), dp(5), dp(4));
            card.setLayoutParams(params);
            return new Holder(card, image, text);
        }
        @Override public void onBindViewHolder(Holder holder, int position) {
            BlofyModels.Episode episode = rows.get(position);
            String name = episode.title == null || episode.title.isEmpty() ? "Episode " + episode.number : episode.title;
            holder.text.setText("الحلقة " + episode.number + "   •   " + name
                    + (episode.duration.isEmpty() ? "" : "\n" + episode.duration));
            images.load(holder.image, episode.image);
            holder.card.setOnClickListener(v -> play(episode.id, seriesName + " — " + name, "episode", episode.extension));
        }
        @Override public int getItemCount() { return rows.size(); }
        final class Holder extends RecyclerView.ViewHolder {
            final LinearLayout card;
            final ImageView image;
            final TextView text;
            Holder(LinearLayout card, ImageView image, TextView text) {
                super(card);
                this.card = card;
                this.image = image;
                this.text = text;
            }
        }
    }
}
