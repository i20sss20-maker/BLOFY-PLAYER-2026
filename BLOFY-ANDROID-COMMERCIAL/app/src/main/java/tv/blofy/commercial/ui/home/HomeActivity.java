package tv.blofy.commercial.ui.home;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import tv.blofy.commercial.BuildConfig;
import tv.blofy.commercial.R;
import tv.blofy.commercial.core.ApiClient;
import tv.blofy.commercial.core.DeviceIdentity;
import tv.blofy.commercial.core.LicensedActivity;
import tv.blofy.commercial.data.CatalogStore;
import tv.blofy.commercial.provider.PlaylistProfile;
import tv.blofy.commercial.provider.PlaylistRepository;
import tv.blofy.commercial.ui.catalog.CatalogActivity;
import tv.blofy.commercial.ui.library.LibraryActivity;
import tv.blofy.commercial.ui.live.LiveActivity;
import tv.blofy.commercial.ui.playlists.PlaylistsActivity;
import tv.blofy.commercial.ui.settings.SettingsActivity;
import tv.blofy.commercial.ui.sync.SyncActivity;

/** BLOFY 10-foot home following the familiar IBO/7up IPTV dashboard flow. */
public final class HomeActivity extends LicensedActivity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView summary, entitlement;
    private CatalogStore store;
    private ApiClient api;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new CatalogStore(this);
        api = new ApiClient(this);
        setContentView(buildUi());
        loadSummary();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(34), dp(18), dp(34), dp(20));
        root.setBackgroundResource(R.drawable.bg_blofy);
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(84)));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.blofy_brand);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        header.addView(logo, new LinearLayout.LayoutParams(dp(68), dp(68)));

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setPadding(dp(14), 0, 0, 0);
        header.addView(titleBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        titleBox.addView(label("BLOFY PLAYER", 26, true));
        summary = label("جاري تجهيز المكتبة…", 13, false);
        summary.setTextColor(getColor(R.color.blofy_muted));
        titleBox.addView(summary);

        LinearLayout accountBox = new LinearLayout(this);
        accountBox.setOrientation(LinearLayout.VERTICAL);
        accountBox.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        header.addView(accountBox, new LinearLayout.LayoutParams(dp(260), ViewGroup.LayoutParams.MATCH_PARENT));
        entitlement = label("الاشتراك: جاري التحقق…", 13, true);
        entitlement.setGravity(Gravity.END);
        accountBox.addView(entitlement);
        TextView version = label("v" + BuildConfig.VERSION_NAME, 11, false);
        version.setTextColor(getColor(R.color.blofy_muted));
        version.setGravity(Gravity.END);
        accountBox.addView(version);

        ImageView qrView = new ImageView(this);
        qrView.setBackgroundColor(Color.WHITE);
        qrView.setPadding(dp(4), dp(4), dp(4), dp(4));
        Bitmap qr = qr(pairingUrl(), 180);
        if (qr != null) qrView.setImageBitmap(qr);
        LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(dp(76), dp(76));
        qrLp.setMarginStart(dp(12));
        header.addView(qrView, qrLp);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        bodyLp.topMargin = dp(14);
        root.addView(body, bodyLp);

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        body.addView(main, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, .82f));

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        main.addView(topRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, .62f));

        MaterialButton live = tile("◉\nLIVE TV\nالبث المباشر", 23);
        LinearLayout.LayoutParams liveLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.18f);
        liveLp.setMarginEnd(dp(12));
        topRow.addView(live, liveLp);

        LinearLayout mediaColumn = new LinearLayout(this);
        mediaColumn.setOrientation(LinearLayout.VERTICAL);
        topRow.addView(mediaColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        MaterialButton movies = tile("▶  MOVIES\nالأفلام", 20);
        LinearLayout.LayoutParams moviesLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        moviesLp.bottomMargin = dp(10);
        mediaColumn.addView(movies, moviesLp);
        MaterialButton series = tile("▣  SERIES\nالمسلسلات", 20);
        mediaColumn.addView(series, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bottomRow = new LinearLayout(this);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        bottomRow.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        LinearLayout.LayoutParams bottomLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, .38f);
        bottomLp.topMargin = dp(12);
        main.addView(bottomRow, bottomLp);

        MaterialButton sports = tile("⚽\nSPORTS", 15);
        MaterialButton favorites = tile("★\nالمفضلة", 15);
        MaterialButton history = tile("↶\nالسجل", 15);
        MaterialButton search = tile("⌕\nالبحث", 15);
        MaterialButton playlists = tile("☰\nتغيير القائمة", 15);
        addBottom(bottomRow, sports, false);
        addBottom(bottomRow, favorites, false);
        addBottom(bottomRow, history, false);
        addBottom(bottomRow, search, false);
        addBottom(bottomRow, playlists, true);

        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams railLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, .18f);
        railLp.setMarginStart(dp(14));
        body.addView(rail, railLp);

        MaterialButton settings = railTile("⚙\nالإعدادات");
        MaterialButton reload = railTile("↻\nReload Portal");
        MaterialButton exit = railTile("⏻\nخروج");
        rail.addView(settings, railItem(false));
        rail.addView(reload, railItem(false));
        rail.addView(exit, railItem(true));

        live.setOnClickListener(v -> open(LiveActivity.class));
        movies.setOnClickListener(v -> library("movies"));
        series.setOnClickListener(v -> library("series"));
        sports.setOnClickListener(v -> startActivity(new Intent(this, LiveActivity.class).putExtra("sports_only", true)));
        favorites.setOnClickListener(v -> catalog("", true, false, false));
        history.setOnClickListener(v -> catalog("", false, true, false));
        search.setOnClickListener(v -> catalog("", false, false, true));
        playlists.setOnClickListener(v -> open(PlaylistsActivity.class));
        settings.setOnClickListener(v -> open(SettingsActivity.class));
        reload.setOnClickListener(v -> startActivity(new Intent(this, SyncActivity.class)));
        exit.setOnClickListener(v -> finishAffinity());

        live.post(live::requestFocus);
        return root;
    }

    private String pairingUrl() {
        String base = api.baseUrl() + "/activate?device_id=" + ApiClient.encode(api.deviceId());
        String token = DeviceIdentity.pairToken(this);
        return token == null || token.isEmpty() ? base : base + "&pair_token=" + ApiClient.encode(token);
    }

    private void addBottom(LinearLayout row, MaterialButton button, boolean last) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        if (!last) lp.setMarginEnd(dp(8));
        row.addView(button, lp);
    }

    private LinearLayout.LayoutParams railItem(boolean last) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        if (!last) lp.bottomMargin = dp(10);
        return lp;
    }

    private MaterialButton tile(String text, int size) {
        MaterialButton button = new MaterialButton(this);
        button.setText(text);
        button.setTextColor(getColor(R.color.blofy_text));
        button.setTextSize(size);
        button.setGravity(Gravity.CENTER);
        button.setFocusable(true);
        button.setAllCaps(false);
        button.setBackgroundResource(R.drawable.bg_home_status);
        button.setPadding(dp(14), dp(12), dp(14), dp(12));
        focusEffect(button, 1.04f);
        return button;
    }

    private MaterialButton railTile(String text) { return tile(text, 15); }
    private void focusEffect(View view, float scale) {
        view.setOnFocusChangeListener((v, focused) -> {
            v.setSelected(focused);
            v.animate().scaleX(focused ? scale : 1f).scaleY(focused ? scale : 1f).setDuration(70).start();
        });
    }

    private TextView label(String text, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextColor(getColor(R.color.blofy_text)); view.setTextSize(size);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private void loadSummary() {
        worker.execute(() -> {
            try {
                int live = store.count("live"); int movies = store.count("movies"); int series = store.count("series");
                PlaylistProfile playlist = PlaylistRepository.active(this);
                String name = playlist == null || playlist.provider.name.isEmpty() ? "القائمة النشطة" : playlist.provider.name;
                String text = name + "  •  " + live + " قناة  •  " + movies + " فيلم  •  " + series + " مسلسل";
                runOnUiThread(() -> { if (summary != null) summary.setText(text); });
            } catch (Exception ignored) { }
            try {
                JSONObject license = api.get("/api/license?device_id=" + ApiClient.encode(api.deviceId()));
                String plan = license.optString("plan", "");
                long expiresAt = license.optLong("expiresAt", 0L);
                String visible;
                if (expiresAt <= 0L) visible = "الاشتراك: " + (plan.isEmpty() ? "فعال" : plan);
                else visible = "ينتهي: " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(expiresAt));
                runOnUiThread(() -> { if (entitlement != null) entitlement.setText(visible); });
            } catch (Exception ignored) {
                runOnUiThread(() -> { if (entitlement != null) entitlement.setText("الاشتراك: متصل"); });
            }
        });
    }

    private static Bitmap qr(String value, int size) {
        try {
            BitMatrix bits = new MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, size, size);
            int[] pixels = new int[size * size];
            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) pixels[y * size + x] = bits.get(x, y) ? Color.BLACK : Color.WHITE;
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
            bitmap.setPixels(pixels, 0, size, 0, 0, size, size);
            return bitmap;
        } catch (Exception ignored) { return null; }
    }

    private void library(String type) { startActivity(new Intent(this, LibraryActivity.class).putExtra("type", type)); }
    private void catalog(String type, boolean favorites, boolean history, boolean focusSearch) {
        startActivity(new Intent(this, CatalogActivity.class).putExtra("type", type).putExtra("favorites", favorites)
                .putExtra("history", history).putExtra("focus_search", focusSearch));
    }
    private void open(Class<?> target) { startActivity(new Intent(this, target)); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && (event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE || event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_B)) {
            finish(); return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override protected void onDestroy() {
        worker.shutdownNow(); if (store != null) store.close(); summary = null; entitlement = null; super.onDestroy();
    }
}
