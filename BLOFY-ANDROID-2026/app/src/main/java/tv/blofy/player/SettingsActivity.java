package tv.blofy.player;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** BLOFY TV playback settings. */
public final class SettingsActivity extends Activity {
    static final String PREFS = "blofy_player_settings";
    static final String KEY_STREAM = "stream_mode";
    static final String KEY_DECODER = "decoder_mode";
    static final String KEY_BUFFER = "buffer_mode";

    private SharedPreferences prefs;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BlofyUi.BLACK);
        getWindow().setNavigationBarColor(BlofyUi.BLACK);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        build();
    }

    private void build() {
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.HORIZONTAL);
        shell.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        shell.setBackground(BlofyUi.screenGradient());

        LinearLayout sidebar = new LinearLayout(this);
        sidebar.setOrientation(LinearLayout.VERTICAL);
        sidebar.setPadding(dp(22), dp(24), dp(22), dp(24));
        sidebar.setBackground(BlofyUi.panel(this, Color.argb(238, 7, 6, 15), 0, BlofyUi.DIVIDER));
        sidebar.addView(BlofyUi.brand(this, "P L A Y E R"),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));
        TextView selected = BlofyUi.sidebarItem(this, "⚙", "الإعدادات", true);
        LinearLayout.LayoutParams selectedParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
        selectedParams.topMargin = dp(42);
        sidebar.addView(selected, selectedParams);
        TextView device = BlofyUi.text(this, "معرّف الجهاز\n" + DeviceIdentity.id(this), 11, BlofyUi.MUTED);
        device.setTextDirection(View.TEXT_DIRECTION_LTR);
        device.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams deviceParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(76), 1f);
        sidebar.addView(device, deviceParams);
        Button sideBack = BlofyUi.button(this, "العودة للرئيسية", false);
        sideBack.setOnClickListener(v -> finish());
        sidebar.addView(sideBack, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        shell.addView(sidebar, new LinearLayout.LayoutParams(dp(260), ViewGroup.LayoutParams.MATCH_PARENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        page.setPadding(dp(38), dp(28), dp(38), dp(32));

        TextView eyebrow = BlofyUi.title(this, "BLOFY PLAYER", 12);
        eyebrow.setTextColor(BlofyUi.PURPLE_LIGHT);
        eyebrow.setTextDirection(View.TEXT_DIRECTION_LTR);
        page.addView(eyebrow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)));
        TextView title = BlofyUi.title(this, "إعدادات التشغيل", 31);
        title.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        page.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));
        TextView note = BlofyUi.text(this,
                "اختر الإعداد المناسب لجهازك وسرعة اتصالك. الوضع التلقائي هو الأفضل لمعظم الباقات.",
                14, BlofyUi.MUTED);
        page.addView(note, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(14), dp(18), dp(14));
        panel.setBackground(BlofyUi.panel(this, Color.argb(228, 15, 13, 28), 18, BlofyUi.STROKE));
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pp.setMargins(0, dp(18), 0, 0);
        page.addView(panel, pp);

        Button stream = cycleButton("صيغة البث", KEY_STREAM, new String[]{"auto", "ts", "hls"}, new String[]{"Auto", "MPEG-TS", "HLS"});
        addSettingRow(panel, "صيغة البث", "اختيار الصيغة الأنسب للقنوات المباشرة", stream);
        Button decoder = cycleButton("فك الترميز", KEY_DECODER, new String[]{"auto", "hardware", "software"}, new String[]{"Auto", "Hardware", "Software fallback"});
        addSettingRow(panel, "فك الترميز", "Hardware أسرع، وSoftware يعمل كخيار احتياطي", decoder);
        Button buffer = cycleButton("Buffer", KEY_BUFFER, new String[]{"fast", "auto", "stable"}, new String[]{"Fast", "Auto", "Stable / 4K"});
        addSettingRow(panel, "التخزين المؤقت", "Fast للسرعة، وStable للبث الثقيل و4K", buffer);

        TextView info = BlofyUi.text(this,
                "• Fast: أسرع فتح للقنوات العادية\n• Auto: توازن السرعة والثبات\n• Stable / 4K: Buffer أكبر للقنوات الثقيلة\n• Software fallback يستخدم فقط عند تعذر الـHardware decoder.",
                13, BlofyUi.MUTED);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ip.setMargins(0, dp(15), 0, dp(15));
        page.addView(info, ip);

        Button reset = BlofyUi.button(this, "استعادة الإعدادات التلقائية", false);
        reset.setOnClickListener(v -> {
            prefs.edit().clear().apply();
            build();
        });
        page.addView(reset, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));

        Button back = BlofyUi.button(this, "تم  •  العودة", true);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
        bp.setMargins(0, dp(10), 0, 0);
        page.addView(back, bp);
        back.setOnClickListener(v -> finish());

        scroll.addView(page);
        shell.addView(scroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        setContentView(shell);
        stream.requestFocus();
    }

    private void addSettingRow(LinearLayout panel, String title, String description, Button action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        row.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView heading = BlofyUi.title(this, title, 16);
        heading.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        TextView detail = BlofyUi.text(this, description, 12, BlofyUi.MUTED);
        detail.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        labels.addView(heading, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)));
        labels.addView(detail, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)));
        row.addView(labels, new LinearLayout.LayoutParams(0, dp(68), 1f));
        row.addView(action, new LinearLayout.LayoutParams(dp(275), dp(52)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(84));
        params.setMargins(0, dp(4), 0, dp(4));
        panel.addView(row, params);
    }

    private Button cycleButton(String label, String key, String[] values, String[] labels) {
        Button button = BlofyUi.button(this, "", false);
        Runnable refresh = () -> {
            String current = prefs.getString(key, values[0]);
            int index = indexOf(values, current);
            button.setText(labels[index] + "   ‹");
        };
        refresh.run();
        button.setOnClickListener(v -> {
            String current = prefs.getString(key, values[0]);
            int next = (indexOf(values, current) + 1) % values.length;
            prefs.edit().putString(key, values[next]).apply();
            refresh.run();
        });
        return button;
    }

    private static int indexOf(String[] values, String value) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(value)) return i;
        return 0;
    }

    private LinearLayout.LayoutParams row() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62));
        p.setMargins(0, dp(6), 0, dp(6));
        return p;
    }

    private int dp(int value) { return BlofyUi.dp(this, value); }
}
