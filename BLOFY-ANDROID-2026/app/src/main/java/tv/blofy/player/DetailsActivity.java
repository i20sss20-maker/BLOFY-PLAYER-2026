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
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
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

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        root = new FrameLayout(this);
        root.setBackground(BlofyUi.screenGradient());
        setContentView(root);
        api = new BlofyApi(this);
        images = new ImageLoader(api);
        database = new CatalogDatabase(this);
        try { item = BlofyModels.Media.from(new JSONObject(getIntent().getStringExtra(EXTRA_ITEM)), "movies"); }
        catch (Exception error) { finish(); return; }
        showLoading();
        worker.execute(() -> {
            try {
                String path = "series".equals(item.type) ? "/api/series/" : "/api/movie/";
                BlofyModels.Detail detail = new BlofyModels.Detail(api.get(path + BlofyApi.encode(item.id)), item.type);
                main.post(() -> showDetail(detail));
            } catch (Exception error) { main.post(() -> showError(error.getMessage())); }
        });
    }

    private void showLoading() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        ProgressBar progress = new ProgressBar(this);
        progress.setIndeterminateTintList(BlofyUi.progressColors());
        panel.addView(progress, new LinearLayout.LayoutParams(dp(58), dp(58)));
        TextView text = BlofyUi.title(this, "جاري تحميل التفاصيل…", 20);
        text.setGravity(Gravity.CENTER);
        panel.addView(text);
        root.addView(panel, match());
    }

    private void showDetail(BlofyModels.Detail detail) {
        root.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(28), dp(22), dp(28), dp(26));
        page.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        LinearLayout header = BlofyUi.brand(this, "P L A Y E R");
        Button back = BlofyUi.button(this, "رجوع", false);
        back.setOnClickListener(view -> finish());
        header.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        header.addView(back, new LinearLayout.LayoutParams(dp(110), dp(52)));
        page.addView(header);

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.HORIZONTAL);
        hero.setGravity(Gravity.TOP);
        hero.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        hero.setPadding(dp(22), dp(22), dp(22), dp(22));
        hero.setBackground(BlofyUi.panel(this, Color.argb(232, 14, 15, 28), 20, Color.rgb(63, 45, 94)));
        ImageView poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        images.load(poster, detail.image.isEmpty() ? item.image : detail.image);
        hero.addView(poster, new LinearLayout.LayoutParams(dp(240), dp(340)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(24), 0, dp(24), 0);
        TextView title = BlofyUi.title(this, detail.name, 28);
        info.addView(title);
        TextView meta = BlofyUi.text(this, join(detail.year, detail.genre, detail.rating.isEmpty() ? "" : "★ " + detail.rating, detail.duration), 14, BlofyUi.PURPLE_LIGHT);
        info.addView(meta);
        TextView description = BlofyUi.text(this, detail.description.isEmpty() ? "لا يوجد وصف متاح." : detail.description, 15, BlofyUi.MUTED);
        description.setMaxLines(8);
        info.addView(description);
        if (!"series".equals(detail.type)) {
            Button play = BlofyUi.button(this, "تشغيل الفيلم ▶", true);
            play.setOnClickListener(view -> play(detail.id, detail.name, "movies", detail.extension));
            LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(dp(260), dp(58));
            playParams.topMargin = dp(20);
            info.addView(play, playParams);
            play.requestFocus();
        }
        hero.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams heroParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        heroParams.topMargin = dp(14);
        page.addView(hero, heroParams);

        if ("series".equals(detail.type)) addSeasons(page, detail);
        scroll.addView(page);
        root.addView(scroll, match());
        if ("series".equals(detail.type)) main.postDelayed(() -> {
            ArrayList<View> focusables = new ArrayList<>();
            page.addFocusables(focusables, View.FOCUS_FORWARD);
            if (!focusables.isEmpty()) focusables.get(Math.min(1, focusables.size() - 1)).requestFocus();
        }, 100);
    }

    private void addSeasons(LinearLayout page, BlofyModels.Detail detail) {
        TextView heading = BlofyUi.title(this, "المواسم والحلقات", 22);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headingParams.topMargin = dp(20);
        page.addView(heading, headingParams);
        HorizontalScrollView seasonScroll = new HorizontalScrollView(this);
        seasonScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout seasonButtons = new LinearLayout(this);
        seasonButtons.setOrientation(LinearLayout.HORIZONTAL);
        seasonButtons.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        seasonScroll.addView(seasonButtons);
        page.addView(seasonScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(70)));

        RecyclerView episodes = new RecyclerView(this);
        episodes.setNestedScrollingEnabled(false);
        episodes.setLayoutManager(new LinearLayoutManager(this));
        EpisodeAdapter adapter = new EpisodeAdapter(detail.name);
        episodes.setAdapter(adapter);
        page.addView(episodes, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        for (BlofyModels.Season season : detail.seasons) {
            Button button = BlofyUi.button(this, "الموسم " + season.number, false);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(150), dp(54));
            params.setMargins(dp(6), dp(6), dp(6), dp(6));
            seasonButtons.addView(button, params);
            button.setOnClickListener(view -> adapter.setEpisodes(season.episodes));
        }
        if (!detail.seasons.isEmpty()) adapter.setEpisodes(detail.seasons.get(0).episodes);
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
        close.setOnClickListener(view -> finish());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(220), dp(56));
        params.topMargin = dp(16);
        panel.addView(close, params);
        root.addView(panel, match());
        close.requestFocus();
    }

    private static String join(String... values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) if (value != null && !value.trim().isEmpty()) {
            if (result.length() > 0) result.append("  •  ");
            result.append(value.trim());
        }
        return result.toString();
    }

    private int dp(int value) { return BlofyUi.dp(this, value); }
    private FrameLayout.LayoutParams match() { return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT); }

    @Override protected void onDestroy() { worker.shutdownNow(); database.close(); super.onDestroy(); }

    private final class EpisodeAdapter extends RecyclerView.Adapter<EpisodeAdapter.Holder> {
        private final String seriesName;
        private List<BlofyModels.Episode> rows = new ArrayList<>();
        EpisodeAdapter(String seriesName) { this.seriesName = seriesName; }
        void setEpisodes(List<BlofyModels.Episode> values) { rows = values == null ? new ArrayList<>() : values; notifyDataSetChanged(); }
        @Override public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            Button button = BlofyUi.button(parent.getContext(), "", false);
            button.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60));
            params.setMargins(dp(4), dp(4), dp(4), dp(4));
            button.setLayoutParams(params);
            return new Holder(button);
        }
        @Override public void onBindViewHolder(Holder holder, int position) {
            BlofyModels.Episode episode = rows.get(position);
            holder.button.setText("الحلقة " + episode.number + "  •  " + episode.title + (episode.duration.isEmpty() ? "" : "  •  " + episode.duration));
            holder.button.setOnClickListener(view -> play(episode.id, seriesName + " — " + episode.title, "episode", episode.extension));
        }
        @Override public int getItemCount() { return rows.size(); }
        final class Holder extends RecyclerView.ViewHolder { final Button button; Holder(Button button) { super(button); this.button = button; } }
    }
}
