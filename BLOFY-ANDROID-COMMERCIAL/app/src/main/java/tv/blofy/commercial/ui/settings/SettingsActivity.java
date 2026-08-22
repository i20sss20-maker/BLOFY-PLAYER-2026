package tv.blofy.commercial.ui.settings;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;

import tv.blofy.commercial.R;
import tv.blofy.commercial.core.LicensedActivity;
import tv.blofy.commercial.ui.activation.ActivationActivity;
import tv.blofy.commercial.ui.playlists.PlaylistsActivity;
import tv.blofy.commercial.ui.sync.SyncActivity;

/** Fast TV settings grid inspired by practical commercial IPTV players. */
public final class SettingsActivity extends LicensedActivity {
    private SharedPreferences prefs;
    private MaterialButton playerButton, qualityButton, bufferButton, subtitlesButton, autoplayButton;

    private static final String[] PLAYER_LABELS = {
            "تلقائي", "Media3", "LibVLC", "MX Player", "MX Player Pro", "VLC", "أي مشغل خارجي"
    };
    private static final String[] PLAYER_VALUES = {
            "auto", "media3", "libvlc", "mx_free", "mx_pro", "vlc_external", "external"
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("blofy_player_settings", MODE_PRIVATE);
        setContentView(buildUi());
        refreshLabels();
        playerButton.post(playerButton::requestFocus);
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(40), dp(28), dp(40), dp(30));
        root.setBackgroundResource(R.drawable.bg_blofy);

        TextView title = new TextView(this);
        title.setText("الإعدادات");
        title.setTextColor(getColor(R.color.blofy_text));
        title.setTextSize(29);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(58)));

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(3);
        grid.setRowCount(3);
        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        gridLp.topMargin = dp(14);
        root.addView(grid, gridLp);

        playerButton = tile("");
        qualityButton = tile("");
        bufferButton = tile("");
        subtitlesButton = tile("");
        autoplayButton = tile("");
        MaterialButton sync = tile("↻\nتحديث القائمة");
        MaterialButton playlists = tile("☰\nقوائم التشغيل");
        MaterialButton account = tile("◇\nالجهاز والتفعيل");
        MaterialButton back = tile("←\nرجوع");

        add(grid, playerButton, 0, 0); add(grid, qualityButton, 0, 1); add(grid, bufferButton, 0, 2);
        add(grid, subtitlesButton, 1, 0); add(grid, autoplayButton, 1, 1); add(grid, sync, 1, 2);
        add(grid, playlists, 2, 0); add(grid, account, 2, 1); add(grid, back, 2, 2);

        playerButton.setOnClickListener(v -> choosePlayer());
        qualityButton.setOnClickListener(v -> chooseQuality());
        bufferButton.setOnClickListener(v -> chooseBuffer());
        subtitlesButton.setOnClickListener(v -> {
            prefs.edit().putBoolean("subtitles", !prefs.getBoolean("subtitles", true)).apply();
            refreshLabels();
        });
        autoplayButton.setOnClickListener(v -> {
            prefs.edit().putBoolean("autoplay", !prefs.getBoolean("autoplay", true)).apply();
            refreshLabels();
        });
        sync.setOnClickListener(v -> startActivity(new Intent(this, SyncActivity.class)));
        playlists.setOnClickListener(v -> startActivity(new Intent(this, PlaylistsActivity.class)));
        account.setOnClickListener(v -> startActivity(new Intent(this, ActivationActivity.class)
                .putExtra("force_form", true)));
        back.setOnClickListener(v -> finish());
        return root;
    }

    private void choosePlayer() {
        String saved = prefs.getString("player_engine", "auto");
        int checked = indexOf(PLAYER_VALUES, saved);
        new AlertDialog.Builder(this)
                .setTitle("مشغل الفيديو")
                .setSingleChoiceItems(PLAYER_LABELS, checked, (dialog, which) -> {
                    prefs.edit().putString("player_engine", PLAYER_VALUES[which]).apply();
                    refreshLabels();
                    dialog.dismiss();
                }).show();
    }

    private void chooseQuality() {
        String[] labels = {"تلقائي", "HD حتى 1080p", "SD حتى 480p"};
        String[] values = {"auto", "hd", "sd"};
        int checked = indexOf(values, prefs.getString("quality", "auto"));
        new AlertDialog.Builder(this).setTitle("الجودة القصوى")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    prefs.edit().putString("quality", values[which]).apply();
                    refreshLabels(); dialog.dismiss();
                }).show();
    }

    private void chooseBuffer() {
        String[] labels = {"بدء سريع", "أكثر ثباتًا"};
        String[] values = {"fast", "stable"};
        int checked = indexOf(values, prefs.getString("buffer", "fast"));
        new AlertDialog.Builder(this).setTitle("التخزين المؤقت")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    prefs.edit().putString("buffer", values[which]).apply();
                    refreshLabels(); dialog.dismiss();
                }).show();
    }

    private void refreshLabels() {
        if (playerButton == null) return;
        String engine = prefs.getString("player_engine", "auto");
        int engineIndex = indexOf(PLAYER_VALUES, engine);
        playerButton.setText("▶\nالمشغل\n" + PLAYER_LABELS[engineIndex]);
        String quality = prefs.getString("quality", "auto");
        qualityButton.setText("▣\nالجودة\n" + ("hd".equals(quality) ? "HD" : "sd".equals(quality) ? "SD" : "تلقائي"));
        bufferButton.setText("≈\nBuffer\n" + ("stable".equals(prefs.getString("buffer", "fast")) ? "ثابت" : "سريع"));
        subtitlesButton.setText("CC\nالترجمة\n" + (prefs.getBoolean("subtitles", true) ? "تشغيل" : "إيقاف"));
        autoplayButton.setText("▷\nتشغيل تلقائي\n" + (prefs.getBoolean("autoplay", true) ? "تشغيل" : "إيقاف"));
    }

    private MaterialButton tile(String text) {
        MaterialButton button = new MaterialButton(this);
        button.setText(text);
        button.setTextColor(getColor(R.color.blofy_text));
        button.setTextSize(16);
        button.setGravity(Gravity.CENTER);
        button.setFocusable(true);
        button.setBackgroundResource(R.drawable.bg_home_status);
        button.setPadding(dp(14), dp(12), dp(14), dp(12));
        button.setOnFocusChangeListener((v, focused) -> v.animate()
                .scaleX(focused ? 1.03f : 1f).scaleY(focused ? 1.03f : 1f)
                .setDuration(70).start());
        return button;
    }

    private void add(GridLayout grid, View view, int row, int col) {
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(row, 1f), GridLayout.spec(col, 1f));
        lp.width = 0; lp.height = 0;
        lp.setMargins(dp(8), dp(8), dp(8), dp(8));
        grid.addView(view, lp);
    }

    private static int indexOf(String[] values, String target) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(target)) return i;
        return 0;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && (event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE || event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_B)) {
            finish(); return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
