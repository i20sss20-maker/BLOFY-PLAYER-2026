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
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(34), dp(26), dp(34), dp(30));
        page.setBackground(BlofyUi.screenGradient());

        page.addView(BlofyUi.brand(this, "P L A Y E R  •  S E T T I N G S"));
        TextView title = BlofyUi.title(this, "إعدادات التشغيل", 27);
        title.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        page.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62)));
        TextView note = BlofyUi.text(this, "الإعدادات تطبق على التشغيل الجديد. وضع Auto هو الأنسب لمعظم السيرفرات.", 13, BlofyUi.MUTED);
        page.addView(note);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(18), dp(20), dp(18));
        panel.setBackground(BlofyUi.panel(this, Color.rgb(13, 18, 31), 10, Color.rgb(55, 47, 82)));
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pp.setMargins(0, dp(18), 0, 0);
        page.addView(panel, pp);

        Button stream = cycleButton("صيغة البث", KEY_STREAM, new String[]{"auto", "ts", "hls"}, new String[]{"Auto", "MPEG-TS", "HLS"});
        panel.addView(stream, row());
        Button decoder = cycleButton("فك الترميز", KEY_DECODER, new String[]{"auto", "hardware", "software"}, new String[]{"Auto", "Hardware", "Software fallback"});
        panel.addView(decoder, row());
        Button buffer = cycleButton("Buffer", KEY_BUFFER, new String[]{"fast", "auto", "stable"}, new String[]{"Fast", "Auto", "Stable / 4K"});
        panel.addView(buffer, row());

        TextView info = BlofyUi.text(this,
                "• Fast: أسرع فتح للقنوات العادية\n• Auto: توازن السرعة والثبات\n• Stable / 4K: Buffer أكبر للقنوات الثقيلة\n• Software fallback يستخدم فقط عند تعذر الـHardware decoder.",
                13, BlofyUi.MUTED);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ip.setMargins(0, dp(15), 0, dp(15));
        page.addView(info, ip);

        Button reset = BlofyUi.button(this, "إعادة إعدادات التشغيل للوضع التلقائي", false);
        reset.setOnClickListener(v -> {
            prefs.edit().clear().apply();
            build();
        });
        page.addView(reset, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));

        Button back = BlofyUi.button(this, "رجوع", true);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
        bp.setMargins(0, dp(10), 0, 0);
        page.addView(back, bp);
        back.setOnClickListener(v -> finish());

        scroll.addView(page);
        setContentView(scroll);
        stream.requestFocus();
    }

    private Button cycleButton(String label, String key, String[] values, String[] labels) {
        Button button = BlofyUi.button(this, "", false);
        Runnable refresh = () -> {
            String current = prefs.getString(key, values[0]);
            int index = indexOf(values, current);
            button.setText(label + "     •     " + labels[index]);
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
