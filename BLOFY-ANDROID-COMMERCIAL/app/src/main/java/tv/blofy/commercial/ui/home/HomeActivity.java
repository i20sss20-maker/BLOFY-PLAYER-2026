package tv.blofy.commercial.ui.home;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import tv.blofy.commercial.R;
import tv.blofy.commercial.core.LicensedActivity;
import tv.blofy.commercial.data.CatalogStore;
import tv.blofy.commercial.provider.PlaylistProfile;
import tv.blofy.commercial.provider.PlaylistRepository;
import tv.blofy.commercial.ui.catalog.CatalogActivity;
import tv.blofy.commercial.ui.playlists.PlaylistsActivity;
import tv.blofy.commercial.ui.settings.SettingsActivity;

/** Lightweight 10-foot dashboard: no hero artwork and no network work during remote navigation. */
public final class HomeActivity extends LicensedActivity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView summary;
    private CatalogStore store;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new CatalogStore(this);
        setContentView(buildUi());
        loadSummary();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(42), dp(28), dp(42), dp(30));
        root.setBackgroundResource(R.drawable.bg_blofy);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        root.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(86)));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.blofy_brand);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        header.addView(logo, new LinearLayout.LayoutParams(dp(76), dp(76)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(14), 0, 0, 0);
        header.addView(titles, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = label("BLOFY PLAYER", 27, true);
        title.setTextDirection(View.TEXT_DIRECTION_LTR);
        titles.addView(title);
        summary = label("جاهز", 13, false);
        summary.setTextColor(getColor(R.color.blofy_muted));
        titles.addView(summary);

        MaterialButton playlistsTop = tile("قوائم التشغيل");
        playlistsTop.setOnClickListener(v -> open(PlaylistsActivity.class));
        header.addView(playlistsTop, new LinearLayout.LayoutParams(dp(176), dp(54)));

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(3);
        grid.setRowCount(2);
        grid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        grid.setUseDefaultMargins(false);
        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        gridLp.topMargin = dp(24);
        root.addView(grid, gridLp);

        MaterialButton live = tile("◉\nالبث المباشر");
        MaterialButton movies = tile("▶\nالأفلام");
        MaterialButton series = tile("▣\nالمسلسلات");
        MaterialButton favorites = tile("★\nالمفضلة");
        MaterialButton playlists = tile("☰\nقوائم التشغيل");
        MaterialButton settings = tile("⚙\nالإعدادات");

        addCell(grid, live, 0, 0);
        addCell(grid, movies, 0, 1);
        addCell(grid, series, 0, 2);
        addCell(grid, favorites, 1, 0);
        addCell(grid, playlists, 1, 1);
        addCell(grid, settings, 1, 2);

        live.setOnClickListener(v -> catalog("live", false, false));
        movies.setOnClickListener(v -> catalog("movies", false, false));
        series.setOnClickListener(v -> catalog("series", false, false));
        favorites.setOnClickListener(v -> catalog("", true, false));
        playlists.setOnClickListener(v -> open(PlaylistsActivity.class));
        settings.setOnClickListener(v -> open(SettingsActivity.class));
        live.post(live::requestFocus);
        return root;
    }

    private void addCell(GridLayout grid, View view, int row, int column) {
        GridLayout.Spec rowSpec = GridLayout.spec(row, 1f);
        GridLayout.Spec colSpec = GridLayout.spec(column, 1f);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(rowSpec, colSpec);
        lp.width = 0;
        lp.height = 0;
        lp.setMargins(dp(9), dp(9), dp(9), dp(9));
        grid.addView(view, lp);
    }

    private MaterialButton tile(String text) {
        MaterialButton button = new MaterialButton(this);
        button.setText(text);
        button.setTextColor(getColor(R.color.blofy_text));
        button.setTextSize(19);
        button.setGravity(Gravity.CENTER);
        button.setFocusable(true);
        button.setBackgroundResource(R.drawable.bg_home_status);
        button.setPadding(dp(18), dp(16), dp(18), dp(16));
        button.setOnFocusChangeListener((v, focused) -> v.animate()
                .scaleX(focused ? 1.035f : 1f)
                .scaleY(focused ? 1.035f : 1f)
                .setDuration(75).start());
        return button;
    }

    private TextView label(String text, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(getColor(R.color.blofy_text));
        view.setTextSize(size);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private void loadSummary() {
        worker.execute(() -> {
            try {
                int live = store.count("live");
                int movies = store.count("movies");
                int series = store.count("series");
                PlaylistProfile playlist = PlaylistRepository.active(this);
                String name = playlist == null || playlist.provider.name.isEmpty()
                        ? "القائمة النشطة" : playlist.provider.name;
                String text = name + "  •  " + live + " قناة  •  " + movies + " فيلم  •  " + series + " مسلسل";
                runOnUiThread(() -> { if (summary != null) summary.setText(text); });
            } catch (Exception ignored) { }
        });
    }

    private void catalog(String type, boolean favorites, boolean history) {
        startActivity(new Intent(this, CatalogActivity.class)
                .putExtra("type", type)
                .putExtra("favorites", favorites)
                .putExtra("history", history));
    }

    private void open(Class<?> target) { startActivity(new Intent(this, target)); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && (event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE
                || event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_B)) {
            finish();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        if (store != null) store.close();
        summary = null;
        super.onDestroy();
    }
}
