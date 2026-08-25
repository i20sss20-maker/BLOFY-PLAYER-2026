package tv.blofy.player;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
    private Future<?> detailTask;
    private int detailGeneration;
    private boolean destroyed;
    private FrameLayout resumeOverlay;

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
        int token = ++detailGeneration;
        detailTask = worker.submit(() -> {
            try {
                String path = "series".equals(item.type) ? "/api/series/" : "/api/movie/";
                BlofyModels.Detail detail = new BlofyModels.Detail(api.get(path + BlofyApi.encode(item.id)), item.type);
                main.post(() -> {
                    if (!canDeliverDetail(token)) return;
                    loadedDetail = detail;
                    showDetail(detail);
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (canDeliverDetail(token)) showError(error.getMessage());
                });
            }
        });
    }

    private boolean canDeliverDetail(int token) {
        return !destroyed && token == detailGeneration && !isFinishing() && !isDestroyed();
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
        addMetaChip(chips, detail.releaseDate.isEmpty() ? detail.year : detail.releaseDate);
        addMetaChip(chips, detail.genre);
        addMetaChip(chips, detail.duration);
        if (!detail.ratings.isEmpty()) {
            int count = Math.min(2, detail.ratings.size());
            for (int index = 0; index < count; index++) {
                BlofyModels.Rating rating = detail.ratings.get(index);
                addMetaChip(chips, rating.source + "  ★ " + rating.value);
            }
        } else {
            addMetaChip(chips, detail.rating.isEmpty() ? "" : "★ " + detail.rating);
        }
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

        String sourceText = detail.ratingSource.isEmpty() ? "" : "مصدر التقييم: " + detail.ratingSource;
        String updatedText = detail.updatedAt.isEmpty() ? "" : "آخر تحديث: " + detail.updatedAt;
        TextView freshness = BlofyUi.text(this, join(sourceText, updatedText), 10, BlofyUi.MUTED);
        freshness.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        freshness.setTextDirection(View.TEXT_DIRECTION_RTL);
        freshness.setVisibility(sourceText.isEmpty() && updatedText.isEmpty() ? View.GONE : View.VISIBLE);
        info.addView(freshness, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                sourceText.isEmpty() && updatedText.isEmpty() ? 0 : dp(25)));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        actions.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        Button primary;
        if ("series".equals(detail.type)) {
            PlaybackProgress.EpisodeResume resume = PlaybackProgress.episode(this, item.id);
            if (resume != null && resume.available()) {
                primary = BlofyUi.button(this,
                        "▶  استئناف  " + PlaybackProgress.format(resume.position), true);
                primary.setOnClickListener(v -> play(resume.id,
                        resume.title.isEmpty() ? detail.name : resume.title,
                        "episode", resume.extension, false));
                actions.addView(primary, new LinearLayout.LayoutParams(dp(218), dp(56)));

                Button restart = BlofyUi.button(this, "↺  من البداية", false);
                restart.setOnClickListener(v -> play(resume.id,
                        resume.title.isEmpty() ? detail.name : resume.title,
                        "episode", resume.extension, true));
                LinearLayout.LayoutParams restartParams = new LinearLayout.LayoutParams(dp(150), dp(56));
                restartParams.leftMargin = dp(10);
                actions.addView(restart, restartParams);

                Button episodes = BlofyUi.button(this, "المواسم", false);
                episodes.setOnClickListener(v -> showSeasons(detail));
                LinearLayout.LayoutParams episodesParams = new LinearLayout.LayoutParams(dp(110), dp(56));
                episodesParams.leftMargin = dp(10);
                actions.addView(episodes, episodesParams);
            } else {
                primary = BlofyUi.button(this, "▶  المواسم والحلقات", true);
                primary.setOnClickListener(v -> showSeasons(detail));
                actions.addView(primary, new LinearLayout.LayoutParams(dp(245), dp(56)));
            }
        } else {
            long position = PlaybackProgress.get(this, "movies", detail.id);
            boolean canResume = position >= PlaybackProgress.RESUME_THRESHOLD_MS;
            primary = BlofyUi.button(this,
                    canResume ? "▶  استئناف  " + PlaybackProgress.format(position) : "▶  شاهد الآن", true);
            primary.setOnClickListener(v -> play(detail.id, detail.name, "movies", detail.extension, false));
            actions.addView(primary, new LinearLayout.LayoutParams(dp(canResume ? 218 : 245), dp(56)));
            if (canResume) {
                Button restart = BlofyUi.button(this, "↺  البدء من جديد", false);
                restart.setOnClickListener(v -> play(detail.id, detail.name, "movies", detail.extension, true));
                LinearLayout.LayoutParams restartParams = new LinearLayout.LayoutParams(dp(180), dp(56));
                restartParams.leftMargin = dp(10);
                actions.addView(restart, restartParams);
            }
        }
        Button favorite = BlofyUi.button(this, "♡  أضف للمفضلة", false);
        favorite.setOnClickListener(v -> {
            database.toggleFavorite(item.type, item.id);
            ToastBridge.show(this, "تم تحديث المفضلة");
        });
        LinearLayout.LayoutParams favoriteParams = new LinearLayout.LayoutParams(dp(158), dp(56));
        favoriteParams.leftMargin = dp(12);
        actions.addView(favorite, favoriteParams);
        info.addView(actions, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(66)));

        FrameLayout.LayoutParams infoParams = new FrameLayout.LayoutParams(dp(680),
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.LEFT | Gravity.CENTER_VERTICAL);
        hero.addView(info, infoParams);
        LinearLayout.LayoutParams heroParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        heroParams.topMargin = dp(8);
        page.addView(hero, heroParams);
        addCredits(page, detail);
        root.addView(page, match());
        if (!showResumePrompt(detail)) primary.requestFocus();
    }

    private boolean showResumePrompt(BlofyModels.Detail detail) {
        boolean enabled = !"off".equals(getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE)
                .getString(SettingsActivity.KEY_RESUME_PROMPT, "on"));
        if (!enabled) return false;
        String resumeId;
        String resumeTitle;
        String resumeKind;
        String resumeExtension;
        long position;
        if ("series".equals(detail.type)) {
            PlaybackProgress.EpisodeResume episode = PlaybackProgress.episode(this, item.id);
            if (episode == null || !episode.available()) return false;
            resumeId = episode.id;
            resumeTitle = episode.title.isEmpty() ? detail.name : episode.title;
            resumeKind = "episode";
            resumeExtension = episode.extension;
            position = episode.position;
        } else {
            position = PlaybackProgress.get(this, "movies", detail.id);
            if (position < PlaybackProgress.RESUME_THRESHOLD_MS) return false;
            resumeId = detail.id;
            resumeTitle = detail.name;
            resumeKind = "movies";
            resumeExtension = detail.extension;
        }

        resumeOverlay = new FrameLayout(this);
        resumeOverlay.setBackgroundColor(Color.argb(205, 2, 2, 8));
        resumeOverlay.setClickable(true);
        resumeOverlay.setFocusable(true);
        LinearLayout modal = new LinearLayout(this);
        modal.setOrientation(LinearLayout.VERTICAL);
        modal.setGravity(Gravity.CENTER);
        modal.setPadding(dp(34), dp(30), dp(34), dp(30));
        modal.setBackground(BlofyUi.panel(this, Color.rgb(15, 11, 26), 20, BlofyUi.PURPLE_LIGHT));
        TextView title = BlofyUi.title(this, "متابعة المشاهدة", 24);
        title.setGravity(Gravity.CENTER);
        modal.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        TextView note = BlofyUi.text(this,
                "توقفت عند " + PlaybackProgress.format(position) + " — اختر طريقة التشغيل", 14, BlofyUi.MUTED);
        note.setGravity(Gravity.CENTER);
        modal.addView(note, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        Button resume = BlofyUi.button(this, "▶  استئناف", true);
        resume.setOnClickListener(v -> play(resumeId, resumeTitle, resumeKind, resumeExtension, false));
        actions.addView(resume, new LinearLayout.LayoutParams(dp(205), dp(58)));
        Button restart = BlofyUi.button(this, "↺  البدء من جديد", false);
        restart.setOnClickListener(v -> play(resumeId, resumeTitle, resumeKind, resumeExtension, true));
        LinearLayout.LayoutParams restartParams = new LinearLayout.LayoutParams(dp(205), dp(58));
        restartParams.leftMargin = dp(12);
        actions.addView(restart, restartParams);
        modal.addView(actions, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));
        TextView cancel = BlofyUi.text(this, "رجوع: البقاء في صفحة التفاصيل", 11, BlofyUi.MUTED);
        cancel.setGravity(Gravity.CENTER);
        modal.addView(cancel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));
        resumeOverlay.addView(modal, new FrameLayout.LayoutParams(dp(610), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        root.addView(resumeOverlay, match());
        resume.requestFocus();
        return true;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (resumeOverlay != null && resumeOverlay.getParent() != null
                && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            root.removeView(resumeOverlay);
            resumeOverlay = null;
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void addCastRail(LinearLayout page, List<BlofyModels.Actor> cast) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = BlofyUi.title(this, "أبطال العمل والممثلون", 16);
        title.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        title.setTextDirection(View.TEXT_DIRECTION_RTL);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(36), 1));
        TextView source = BlofyUi.text(this, "حسب بيانات المحتوى", 10, BlofyUi.MUTED);
        source.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        header.addView(source, new LinearLayout.LayoutParams(dp(180), dp(36)));
        page.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)));

        RecyclerView people = new RecyclerView(this);
        people.setLayoutManager(new LinearLayoutManager(this, RecyclerView.HORIZONTAL, false));
        people.setItemAnimator(null);
        people.setClipToPadding(false);
        people.setPadding(dp(2), dp(2), dp(12), dp(4));
        people.setAdapter(new CastAdapter(cast));
        page.addView(people, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(142)));
    }

    private void addCredits(LinearLayout page, BlofyModels.Detail detail) {
        if (!detail.cast.isEmpty()) {
            addCastRail(page, detail.cast);
            return;
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(4), dp(14), dp(4));
        row.setBackground(BlofyUi.panel(this, Color.argb(165, 17, 14, 29), 14, BlofyUi.STROKE));
        TextView title = BlofyUi.title(this, "الممثلون وطاقم العمل", 15);
        title.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        row.addView(title, new LinearLayout.LayoutParams(dp(220), dp(48)));
        TextView value = BlofyUi.text(this, detail.director.isEmpty()
                ? "بيانات الطاقم غير متوفرة من المصدر حاليًا"
                : "إخراج: " + detail.director, 12, BlofyUi.MUTED);
        value.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        row.addView(value, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
        params.setMargins(0, dp(8), 0, 0);
        page.addView(row, params);
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

        int latestSeason = Math.max(0, detail.seasons.size() - 1);
        SeasonAdapter seasonAdapter = new SeasonAdapter(detail.seasons, latestSeason, season -> {
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

        if (!detail.seasons.isEmpty()) {
            episodeAdapter.setEpisodes(detail.seasons.get(latestSeason).episodes);
            seasons.scrollToPosition(latestSeason);
        }
        seasons.requestFocus();
    }

    private void play(String id, String title, String type, String extension) {
        play(id, title, type, extension, false);
    }

    private void play(String id, String title, String type, String extension, boolean restart) {
        database.addHistory(item.type, item.id);
        if (restart) PlaybackProgress.clear(this, type, id);
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
        destroyed = true;
        detailGeneration++;
        if (detailTask != null) detailTask.cancel(true);
        main.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        database.close();
        super.onDestroy();
    }

    private interface SeasonListener { void selected(BlofyModels.Season season); }

    private final class SeasonAdapter extends RecyclerView.Adapter<SeasonAdapter.Holder> {
        private final List<BlofyModels.Season> rows;
        private final SeasonListener listener;
        private int selected;
        SeasonAdapter(List<BlofyModels.Season> rows, int selected, SeasonListener listener) {
            this.rows = rows == null ? new ArrayList<>() : rows;
            this.selected = Math.max(0, Math.min(selected, Math.max(0, this.rows.size() - 1)));
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
            rows = values == null ? new ArrayList<>() : new ArrayList<>(values);
            // The provider may return newest-first. TV users expect episode 1 at
            // the top, regardless of air-date or the original JSON ordering.
            Collections.sort(rows, (first, second) -> Integer.compare(first.number, second.number));
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
                    + (episode.duration.isEmpty() ? "" : "\n" + episode.duration)
                    + (episode.airDate.isEmpty() ? "" : "   •   " + episode.airDate));
            images.load(holder.image, episode.image);
            holder.card.setOnClickListener(v -> {
                String playbackTitle = seriesName + " — " + name;
                PlaybackProgress.rememberEpisode(DetailsActivity.this, item.id,
                        episode.id, playbackTitle, episode.extension);
                play(episode.id, playbackTitle, "episode", episode.extension);
            });
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

    private final class CastAdapter extends RecyclerView.Adapter<CastAdapter.Holder> {
        private final List<BlofyModels.Actor> rows;
        CastAdapter(List<BlofyModels.Actor> rows) { this.rows = rows == null ? new ArrayList<>() : rows; }

        @Override public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            LinearLayout card = new LinearLayout(parent.getContext());
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(6), dp(5), dp(10), dp(5));
            card.setBackground(BlofyUi.panel(DetailsActivity.this,
                    Color.argb(210, 14, 12, 26), 13, BlofyUi.STROKE));
            ImageView image = new ImageView(parent.getContext());
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(image, new LinearLayout.LayoutParams(dp(72), dp(104)));
            LinearLayout labels = new LinearLayout(parent.getContext());
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = BlofyUi.title(parent.getContext(), "", 12);
            name.setGravity(Gravity.LEFT | Gravity.BOTTOM);
            name.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
            name.setMaxLines(2);
            labels.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
            TextView role = BlofyUi.text(parent.getContext(), "", 10, BlofyUi.MUTED);
            role.setGravity(Gravity.LEFT | Gravity.TOP);
            role.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
            role.setMaxLines(2);
            labels.addView(role, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
            LinearLayout.LayoutParams labelsParams = new LinearLayout.LayoutParams(dp(130), dp(104));
            labelsParams.leftMargin = dp(9);
            card.addView(labels, labelsParams);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(dp(230), dp(120));
            params.setMargins(dp(4), dp(3), dp(6), dp(3));
            card.setLayoutParams(params);
            return new Holder(card, image, name, role);
        }

        @Override public void onBindViewHolder(Holder holder, int position) {
            BlofyModels.Actor actor = rows.get(position);
            holder.name.setText(actor.name);
            holder.role.setText(actor.character.isEmpty() ? "ممثل" : actor.character);
            images.load(holder.image, actor.image);
        }

        @Override public int getItemCount() { return rows.size(); }

        final class Holder extends RecyclerView.ViewHolder {
            final ImageView image;
            final TextView name;
            final TextView role;
            Holder(View card, ImageView image, TextView name, TextView role) {
                super(card);
                this.image = image;
                this.name = name;
                this.role = role;
            }
        }
    }
}
