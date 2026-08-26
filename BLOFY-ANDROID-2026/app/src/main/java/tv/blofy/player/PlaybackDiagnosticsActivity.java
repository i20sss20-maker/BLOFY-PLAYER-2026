package tv.blofy.player;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/** Hidden support screen for diagnosing provider-specific playback without exposing credentials. */
public final class PlaybackDiagnosticsActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BlofyUi.BLACK);
        getWindow().setNavigationBarColor(BlofyUi.BLACK);
        build();
    }

    private void build() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackground(BlofyUi.screenGradient());

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        page.setPadding(dp(34), dp(26), dp(34), dp(30));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = BlofyUi.button(this, "↩  رجوع", false);
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(150), dp(52)));
        View spacer = new View(this);
        header.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        TextView title = BlofyUi.title(this, "تشخيص التشغيل", 28);
        title.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(dp(520), dp(58)));
        page.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)));

        TextView note = BlofyUi.text(this,
                "آخر محاولات Live / Movies / Series. لا يتم حفظ اسم المستخدم أو كلمة المرور أو التوكن.",
                13, BlofyUi.MUTED);
        note.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        page.addView(note, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        String snapshot = PlaybackDiagnostics.snapshot(this);
        boolean any = false;
        try {
            JSONArray rows = new JSONArray(snapshot);
            any = rows.length() > 0;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                String line = row.optString("kind", "?").toUpperCase()
                        + "  •  " + row.optString("family", "?")
                        + "  •  " + row.optString("transport", "?")
                        + "\n" + row.optString("stage", "?")
                        + "  •  " + row.optLong("elapsedMs", 0) + " ms"
                        + "\n" + row.optString("detail", "");
                TextView card = BlofyUi.text(this, line, 13, Color.WHITE);
                card.setTextDirection(View.TEXT_DIRECTION_LTR);
                card.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
                card.setPadding(dp(18), dp(12), dp(18), dp(12));
                card.setBackground(BlofyUi.panel(this, Color.argb(220, 20, 14, 33), 14, BlofyUi.STROKE));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                params.setMargins(0, dp(5), 0, dp(5));
                page.addView(card, params);
            }
        } catch (Exception ignored) {}

        if (!any) {
            TextView empty = BlofyUi.text(this,
                    "ما فيه سجلات حتى الآن. شغّل قناة أو فيلم أو حلقة ثم ارجع هنا.",
                    15, BlofyUi.MUTED);
            empty.setGravity(Gravity.CENTER);
            page.addView(empty, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(180)));
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        actions.setGravity(Gravity.CENTER);
        Button refresh = BlofyUi.button(this, "↻  تحديث", true);
        refresh.setOnClickListener(v -> build());
        Button clear = BlofyUi.button(this, "⌫  مسح السجل", false);
        clear.setOnClickListener(v -> {
            PlaybackDiagnostics.clear(this);
            build();
        });
        actions.addView(refresh, new LinearLayout.LayoutParams(dp(210), dp(54)));
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(dp(210), dp(54));
        clearParams.leftMargin = dp(12);
        actions.addView(clear, clearParams);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(70));
        actionsParams.topMargin = dp(16);
        page.addView(actions, actionsParams);

        scroll.removeAllViews();
        scroll.addView(page);
        setContentView(scroll);
        back.requestFocus();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
