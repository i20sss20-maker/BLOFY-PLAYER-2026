package tv.blofy.commercial.ui.home;

import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import tv.blofy.commercial.R;
import tv.blofy.commercial.core.BlofyImageLoader;
import tv.blofy.commercial.core.LicensedActivity;
import tv.blofy.commercial.data.CatalogStore;
import tv.blofy.commercial.data.MediaRecord;
import tv.blofy.commercial.databinding.ActivityHomeBinding;
import tv.blofy.commercial.databinding.ItemHomePosterBinding;
import tv.blofy.commercial.ui.activation.ActivationActivity;
import tv.blofy.commercial.ui.catalog.CatalogActivity;
import tv.blofy.commercial.ui.details.DetailsActivity;
import tv.blofy.commercial.ui.player.PlayerActivity;
import tv.blofy.commercial.ui.settings.SettingsActivity;
import tv.blofy.commercial.ui.sync.SyncActivity;

/** Native TV home: no WebView and no database work on the UI thread. */
public final class HomeActivity extends LicensedActivity {
    private static final int HOME_ROW_SIZE = 20;

    private ActivityHomeBinding binding;
    private CatalogStore store;
    private LatestAdapter latestAdapter;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean destroyed = new AtomicBoolean(false);
    private MediaRecord featured;
    private String packageKind = "";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityHomeBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        store = new CatalogStore(this);

        binding.latest.setLayoutManager(new LinearLayoutManager(this, RecyclerView.HORIZONTAL, false));
        binding.latest.setHasFixedSize(true);
        binding.latest.setItemViewCacheSize(10);
        latestAdapter = new LatestAdapter();
        binding.latest.setAdapter(latestAdapter);

        binding.heroPlay.setEnabled(false);
        binding.heroDetails.setEnabled(false);
        binding.heroPlay.setOnClickListener(view -> open(featured, false));
        binding.heroDetails.setOnClickListener(view -> open(featured, true));
        binding.heroPlay.setOnKeyListener((view, keyCode, event) ->
                event.getAction() == KeyEvent.ACTION_DOWN
                        && keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                        && focusLatest());
        binding.heroDetails.setOnKeyListener((view, keyCode, event) ->
                event.getAction() == KeyEvent.ACTION_DOWN
                        && keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                        && focusLatest());
        binding.status.setOnClickListener(view -> startActivity(new Intent(this, SyncActivity.class)));
        installNavigation();
        loadHome();

        binding.navHome.setSelected(true);
        binding.navHome.post(binding.navHome::requestFocus);
    }

    private void installNavigation() {
        binding.navHome.setOnClickListener(view -> binding.heroPlay.requestFocus());
        binding.navLive.setOnClickListener(view -> catalog("live", false, false));
        binding.navMovies.setOnClickListener(view -> catalog("movies", false, false));
        binding.navSeries.setOnClickListener(view -> catalog("series", false, false));
        binding.navFavorites.setOnClickListener(view -> catalog("", true, false));
        binding.navHistory.setOnClickListener(view -> catalog("", false, true));
        binding.navSettings.setOnClickListener(view -> startActivity(new Intent(this, SettingsActivity.class)));
        binding.navAccount.setOnClickListener(view -> startActivity(
                new Intent(this, ActivationActivity.class).putExtra("force_form", true)));
    }

    private void catalog(String type, boolean favorites, boolean history) {
        startActivity(new Intent(this, CatalogActivity.class)
                .putExtra("type", type)
                .putExtra("favorites", favorites)
                .putExtra("history", history));
    }

    private boolean focusLatest() {
        if (latestAdapter == null || latestAdapter.getItemCount() == 0) return false;
        binding.latest.scrollToPosition(0);
        binding.latest.post(() -> {
            RecyclerView.ViewHolder holder = binding.latest.findViewHolderForAdapterPosition(0);
            if (holder != null) holder.itemView.requestFocus();
        });
        return true;
    }

    private void loadHome() {
        binding.status.setText("●  جاري قراءة المكتبة");
        binding.status.setTextColor(getColor(R.color.blofy_purple_light));
        worker.execute(() -> {
            try {
                int live = store.count("live");
                int movies = store.count("movies");
                int series = store.count("series");
                String kind = store.getMeta("kind");
                List<MediaRecord> rows = recentMixed(HOME_ROW_SIZE);
                HomeSnapshot snapshot = new HomeSnapshot(live, movies, series, kind, rows);
                runOnUiThread(() -> renderHome(snapshot));
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (destroyed.get() || binding == null) return;
                    binding.status.setText("تعذر قراءة المكتبة • أعد المزامنة");
                    binding.status.setTextColor(getColor(R.color.blofy_error));
                    binding.latestTitle.setText("تعذر تحميل المحتوى المحلي");
                });
            }
        });
    }

    /** Provider order is retained, alternating movies and series instead of sorting 11k rows. */
    private List<MediaRecord> recentMixed(int limit) {
        List<MediaRecord> movies = recentType("movies", limit);
        List<MediaRecord> series = recentType("series", limit);
        List<MediaRecord> rows = new ArrayList<>(limit);
        int cursor = 0;
        while (rows.size() < limit && (cursor < movies.size() || cursor < series.size())) {
            if (cursor < movies.size()) rows.add(movies.get(cursor));
            if (rows.size() < limit && cursor < series.size()) rows.add(series.get(cursor));
            cursor++;
        }
        if (rows.isEmpty()) rows.addAll(recentType("live", limit));
        return rows;
    }

    private List<MediaRecord> recentType(String type, int limit) {
        List<MediaRecord> result = new ArrayList<>();
        String sql = "SELECT type,id,name,image,backdrop,category_id,rating,year,extension "
                + "FROM media WHERE type=? ORDER BY rowid DESC LIMIT ?";
        try (Cursor cursor = store.getReadableDatabase().rawQuery(
                sql, new String[]{type, String.valueOf(Math.max(1, limit))})) {
            while (cursor.moveToNext()) {
                result.add(new MediaRecord(
                        value(cursor, 0), value(cursor, 1), value(cursor, 2),
                        value(cursor, 3), value(cursor, 4), value(cursor, 5),
                        value(cursor, 6), value(cursor, 7), value(cursor, 8)));
            }
        }
        return result;
    }

    private void renderHome(HomeSnapshot snapshot) {
        if (destroyed.get() || binding == null) return;
        packageKind = snapshot.kind;
        binding.welcome.setText("باقتك جاهزة • " + snapshot.live + " قناة • "
                + snapshot.movies + " فيلم • " + snapshot.series + " مسلسل");
        binding.status.setText("●  Media3 جاهز");
        binding.status.setTextColor(getColor(R.color.blofy_success));
        latestAdapter.submit(snapshot.rows);
        if (snapshot.rows.isEmpty()) {
            binding.latestTitle.setText("المكتبة فارغة • اضغط حالة المزامنة");
            binding.heroTitle.setText("أعد مزامنة باقتك لعرض المحتوى");
            binding.heroMeta.setText("BLOFY PLAYER");
            binding.heroDescription.setText("اضغط مؤشر الحالة أعلى الشاشة لإعادة قراءة القنوات والأفلام والمسلسلات.");
            binding.heroPlay.setEnabled(false);
            binding.heroDetails.setEnabled(false);
            return;
        }
        binding.latestTitle.setText("أضيف حديثًا");
        featured = snapshot.rows.get(0);
        renderHero(featured);
    }

    private void renderHero(MediaRecord item) {
        if (item == null || destroyed.get() || binding == null) return;
        featured = item;
        String label = typeLabel(item.type);
        binding.heroBadge.setText("live".equals(item.type) ? "بث مباشر" : "مختارات " + label);
        binding.heroTitle.setText(item.name.isEmpty() ? "BLOFY PLAYER" : item.name);
        StringBuilder meta = new StringBuilder(label);
        append(meta, item.year);
        append(meta, item.rating.isEmpty() ? "" : "★ " + item.rating);
        binding.heroMeta.setText(meta);
        binding.heroDescription.setText("live".equals(item.type)
                ? "شغّل القناة مباشرة عبر Media3 واستمتع بالتنقل السريع بين قنوات باقتك."
                : "شاهد " + item.name + " بجودة باقتك، مع تشغيل أصلي سريع وتجربة مصممة للتلفزيون.");
        String backdrop = item.backdrop.isEmpty() ? item.image : item.backdrop;
        BlofyImageLoader.backdrop(this, binding.heroBackdrop, backdrop);
        BlofyImageLoader.poster(this, binding.heroPoster, item.image);
        binding.heroPlay.setEnabled(true);
        binding.heroDetails.setEnabled(true);
    }

    private void open(MediaRecord item, boolean detailsRequested) {
        if (item == null || item.id.isEmpty()) return;
        boolean directM3u = "m3u".equalsIgnoreCase(packageKind) && !"live".equals(item.type);
        boolean detailsAvailable = !"live".equals(item.type) && !directM3u;
        Class<?> target;
        if ("live".equals(item.type) || directM3u) target = PlayerActivity.class;
        else if ("series".equals(item.type) || (detailsRequested && detailsAvailable)) target = DetailsActivity.class;
        else target = PlayerActivity.class;
        startActivity(new Intent(this, target)
                .putExtra("type", item.type)
                .putExtra("id", item.id)
                .putExtra("name", item.name)
                .putExtra("extension", item.extension)
                .putExtra("image", item.image));
    }

    private static String typeLabel(String type) {
        if ("live".equals(type)) return "قناة مباشرة";
        if ("series".equals(type)) return "مسلسل";
        return "فيلم";
    }

    private static void append(StringBuilder target, String value) {
        if (value == null || value.trim().isEmpty()) return;
        if (target.length() > 0) target.append("  •  ");
        target.append(value.trim());
    }

    private static String value(Cursor cursor, int index) {
        return cursor.isNull(index) ? "" : cursor.getString(index);
    }

    @Override protected void onDestroy() {
        destroyed.set(true);
        worker.shutdownNow();
        if (store != null) store.close();
        binding = null;
        super.onDestroy();
    }

    private static final class HomeSnapshot {
        final int live, movies, series;
        final String kind;
        final List<MediaRecord> rows;

        HomeSnapshot(int live, int movies, int series, String kind, List<MediaRecord> rows) {
            this.live = live;
            this.movies = movies;
            this.series = series;
            this.kind = kind == null ? "" : kind;
            this.rows = rows == null ? Collections.emptyList() : rows;
        }
    }

    private final class LatestAdapter extends RecyclerView.Adapter<LatestAdapter.Holder> {
        private final List<MediaRecord> rows = new ArrayList<>();

        void submit(List<MediaRecord> next) {
            rows.clear();
            if (next != null) rows.addAll(next);
            notifyDataSetChanged();
        }

        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ItemHomePosterBinding item = ItemHomePosterBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false);
            item.getRoot().setNextFocusUpId(R.id.heroPlay);
            return new Holder(item);
        }

        @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
            MediaRecord item = rows.get(position);
            holder.binding.title.setText(item.name.isEmpty() ? "BLOFY PLAYER" : item.name);
            StringBuilder meta = new StringBuilder(typeLabel(item.type));
            append(meta, item.year);
            holder.binding.meta.setText(meta);
            BlofyImageLoader.poster(HomeActivity.this, holder.binding.poster, item.image);
            holder.binding.getRoot().setOnClickListener(view -> open(item, false));
            holder.binding.getRoot().setOnFocusChangeListener((view, focused) -> {
                view.animate().scaleX(focused ? 1.04f : 1f).scaleY(focused ? 1.04f : 1f)
                        .translationZ(focused ? 8f : 0f).setDuration(120).start();
                if (focused) renderHero(item);
            });
            holder.binding.getRoot().setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN
                        || keyCode != KeyEvent.KEYCODE_DPAD_LEFT) return false;
                RecyclerView.LayoutManager raw = binding.latest.getLayoutManager();
                if (!(raw instanceof LinearLayoutManager)) return false;
                LinearLayoutManager layout = (LinearLayoutManager) raw;
                int edge = layout.findLastCompletelyVisibleItemPosition();
                if (edge == RecyclerView.NO_POSITION) edge = layout.findLastVisibleItemPosition();
                int positionNow = holder.getBindingAdapterPosition();
                if (positionNow == RecyclerView.NO_POSITION || positionNow < edge) return false;
                binding.navHome.requestFocus();
                return true;
            });
        }

        @Override public int getItemCount() { return rows.size(); }

        final class Holder extends RecyclerView.ViewHolder {
            final ItemHomePosterBinding binding;
            Holder(ItemHomePosterBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && (event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE
                || event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_B)) {
            finish();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
