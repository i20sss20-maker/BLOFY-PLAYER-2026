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
        page.setPadding(dp(30), dp(24), dp(30), dp(28));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        Button back = BlofyUi.button(this, "← رجوع", false);
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(130), dp(52)));
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        top.addView(BlofyUi.brand(this, "P L A Y E R"), new LinearLayout.LayoutParams(dp(230), dp(64)));
        page.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)));

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.HORIZONTAL);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setPadding(dp(26), dp(24), dp(26), dp(24));
        hero.setBackground(BlofyUi.panel(this, Color.argb(235, 11, 16, 26), 8, Color.rgb(50, 58, 78)));

        ImageView poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        images.load(poster, detail.image.isEmpty() ? item.image : detail.image);
        hero.addView(poster, new LinearLayout.LayoutParams(dp(250), dp(360)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(30), 0, dp(10), 0);
        TextView title = BlofyUi.title(this, detail.name.isEmpty() ? item.name : detail.name, 30);
        title.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        title.setTextDirection(View.TEXT_DIRECTION_LTR);
        info.addView(title);

        TextView meta = BlofyUi.text(this, join(detail.year, detail.genre,
                detail.rating.isEmpty() ? "" : "★ " + detail.rating, detail.duration), 14, BlofyUi.PURPLE_LIGHT);
        meta.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        meta.setTextDirection(View.TEXT_DIRECTION_LTR);
        info.addView(meta);

        TextView description = BlofyUi.text(this,
                detail.description.isEmpty() ? "لا يوجد وصف متاح." : detail.description,
                15, BlofyUi.MUTED);
        description.setGravity(Gravity.LEFT | Gravity.TOP);
        description.setMaxLines(7);
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        descriptionParams.topMargin = dp(12);
        info.addView(description, descriptionParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        Button primary = BlofyUi.button(this,
                "series".equals(detail.type) ? "▶  مشاهدة المواسم" : "▶  تشغيل الفيلم", true);
        primary.setOnClickListener(v -> {
            if ("series".equals(detail.type)) showSeasons(detail);
            else play(detail.id, detail.name, "movies", detail.extension);
        });
        actions.addView(primary, new LinearLayout.LayoutParams(dp(265), dp(58)));
        Button favorite = BlofyUi.button(this, "★  المفضلة", false);
        favorite.setOnClickListener(v -> {
            database.toggleFavorite(item.type, item.id);
            ToastBridge.show(this, "تم تحديث المفضلة");
        });
        LinearLayout.LayoutParams favoriteParams = new LinearLayout.LayoutParams(dp(180), dp(58));
        favoriteParams.leftMargin = dp(12);
        actions.addView(favorite, favoriteParams);
        info.addView(actions, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(70)));

        hero.addView(info, new LinearLayout.LayoutParams(0, dp(360), 1));
        LinearLayout.LayoutParams heroParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        heroParams.topMargin = dp(12);
        page.addView(hero, heroParams);
        root.addView(page, match());
        primary.requestFocus();
    }

    private void showSeasons(BlofyModels.Detail detail) {
        seasonsScreen = true;
        root.removeAllViews();
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(26), dp(22), dp(26), dp(26));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        Button back = BlofyUi.button(this, "← التفاصيل", false);
        back.setOnClickListener(v -> showDetail(detail));
        top.addView(back, new LinearLayout.LayoutParams(dp(150), dp(52)));
        TextView title = BlofyUi.title(this, detail.name, 24);
        title.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        top.addView(title, new LinearLayout.LayoutParams(0, dp(58), 1));
        page.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(68)));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);

        RecyclerView seasons = new RecyclerView(this);
        seasons.setLayoutManager(new LinearLayoutManager(this));
        seasons.setItemAnimator(null);
        seasons.setBackground(BlofyUi.panel(this, Color.rgb(11, 17, 27), 5, Color.rgb(50, 58, 76)));

        RecyclerView episodes = new RecyclerView(this);
        episodes.setLayoutManager(new LinearLayoutManager(this));
        episodes.setItemAnimator(null);
        episodes.setItemViewCacheSize(12);
        EpisodeAdapter episodeAdapter = new EpisodeAdapter(detail.name);
        episodes.setAdapter(episodeAdapter);

        SeasonAdapter seasonAdapter = new SeasonAdapter(detail.seasons, season -> {
            episodeAdapter.setEpisodes(season.episodes);
            episodes.post(() -> { if (episodeAdapter.getItemCount() > 0) episodes.requestFocus(); });
        });
        seasons.setAdapter(seasonAdapter);

        body.addView(seasons, new LinearLayout.LayoutParams(dp(285), ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        ep.leftMargin = dp(12);
        body.addView(episodes, ep);
        page.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(page, match());

        if (!detail.seasons.isEmpty()) episodeAdapter.setEpisodes(detail.seasons.get(0).episodes);
        seasons.requestFocus();
    }

    private void play(String id, String title, String type, String extension) {
        database.addHistory(item.type, item.id);
        Intent player = new Intent(this, PlayerActivity.class);
        player.putExtra(PlayerActivity.EXTRA_ID, id);
        player.putExtra(PlayerActivity.EXTRA_TITLE, title);
        player.putExtra(PlayerActivity.EXTRA_KIND, type);
        player.putExtra(PlayerActivity.EXTRA_EXTENSION, extension);
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
        SeasonAdapter(List<BlofyModels.Season> rows, SeasonListener listener) {
            this.rows = rows == null ? new ArrayList<>() : rows;
            this.listener = listener;
        }
        @Override public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            Button button = BlofyUi.button(parent.getContext(), "", false);
            button.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            button.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));
            return new Holder(button);
        }
        @Override public void onBindViewHolder(Holder holder, int position) {
            BlofyModels.Season season = rows.get(position);
            holder.button.setText("Season " + season.number + "   •   " + season.episodes.size() + " حلقة");
            holder.button.setOnClickListener(v -> listener.selected(season));
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
            card.setBackground(BlofyUi.focusDrawable(DetailsActivity.this, Color.rgb(14, 20, 30), Color.rgb(61, 43, 90), Color.WHITE));
            ImageView image = new ImageView(parent.getContext());
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(image, new LinearLayout.LayoutParams(dp(180), dp(94)));
            TextView text = BlofyUi.title(parent.getContext(), "", 14);
            text.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            text.setTextDirection(View.TEXT_DIRECTION_LTR);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, dp(94), 1);
            textParams.leftMargin = dp(16);
            card.addView(text, textParams);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(112));
            params.setMargins(dp(5), dp(4), dp(5), dp(4));
            card.setLayoutParams(params);
            return new Holder(card, image, text);
        }
        @Override public void onBindViewHolder(Holder holder, int position) {
            BlofyModels.Episode episode = rows.get(position);
            String name = episode.title == null || episode.title.isEmpty() ? "Episode " + episode.number : episode.title;
            holder.text.setText("" + episode.number + "   " + name + (episode.duration.isEmpty() ? "" : "   •   " + episode.duration));
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
