package tv.blofy.player;

import android.app.Activity;
import android.content.Intent;
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
    static final String KEY_AUDIO_OUTPUT = "audio_output";
    static final String KEY_SUBTITLE_LANGUAGE = "subtitle_language";
    static final String KEY_SUBTITLE_SIZE = "subtitle_size";
    static final String KEY_ASPECT = "aspect_mode";
    static final String KEY_AUTOPLAY_LIVE = "autoplay_live";
    static final String KEY_RESUME_PROMPT = "resume_prompt";
    static final String KEY_AUTO_NEXT = "auto_next_episode";
    static final String KEY_EPG_TIMEZONE = "epg_timezone";
    static final String KEY_MOTION = "motion_mode";

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
        TextView device = BlofyUi.text(this, "معرّف الجهاز\n" + DeviceIdentity.displayId(this)
                + "\nرمز الجهاز  " + DeviceIdentity.activationCode(this), 11, BlofyUi.MUTED);
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

        LinearLayout panel = section(page, "التشغيل والأداء", "سرعة البداية والتوافق مع صيغ البث المختلفة");
        Button performance = cycleButton("الأداء", DeviceCapabilityProfile.KEY_PERFORMANCE_MODE,
                new String[]{"auto", "fast", "quality"},
                new String[]{"تلقائي حسب الجهاز", "سريع للأجهزة الضعيفة", "جودة أعلى"},
                ImageLoader::resetRuntime);
        addSettingRow(panel, "وضع الأداء",
                "يضبط دقة الصور والذاكرة وعدد مهام التحميل حسب قوة الجهاز", performance);
        Button stream = cycleButton("صيغة البث", KEY_STREAM, new String[]{"auto", "ts", "hls"}, new String[]{"Auto", "MPEG-TS", "HLS"});
        addSettingRow(panel, "صيغة البث", "اختيار الصيغة الأنسب للقنوات المباشرة", stream);
        Button decoder = cycleButton("فك الترميز", KEY_DECODER, new String[]{"auto", "hardware", "software"}, new String[]{"Auto", "Hardware", "Software fallback"});
        addSettingRow(panel, "فك الترميز", "Hardware أسرع، وSoftware يعمل كخيار احتياطي", decoder);
        Button buffer = cycleButton("Buffer", KEY_BUFFER, new String[]{"fast", "auto", "stable"}, new String[]{"Fast", "Auto", "Stable / 4K"});
        addSettingRow(panel, "التخزين المؤقت", "Fast للسرعة، وStable للبث الثقيل و4K", buffer);

        Button aspect = cycleButton("حجم الصورة", KEY_ASPECT,
                new String[]{"fit", "zoom", "fill"}, new String[]{"ملاءمة", "تكبير", "ملء الشاشة"});
        addSettingRow(panel, "نسبة عرض الفيديو", "تثبيت طريقة عرض الصورة في الأفلام والمسلسلات", aspect);

        LinearLayout sound = section(page, "الصوت والترجمة", "اللغة والمخرج المفضلان عند بداية التشغيل");
        Button audio = cycleButton("الصوت", KEY_AUDIO_OUTPUT,
                new String[]{"auto", "stereo", "passthrough"}, new String[]{"تلقائي", "ستيريو 2.0", "Dolby / تمرير"});
        addSettingRow(sound, "مخرج الصوت", "اختيار تلقائي أو ستيريو أو تمرير الصوت المحيطي", audio);
        Button subtitle = cycleButton("الترجمة", KEY_SUBTITLE_LANGUAGE,
                new String[]{"ar", "auto", "off"}, new String[]{"العربية أولًا", "تلقائي", "إيقاف"});
        addSettingRow(sound, "لغة الترجمة", "يفضّل المسار العربي إذا كان موجودًا داخل الملف", subtitle);
        Button subtitleSize = cycleButton("الحجم", KEY_SUBTITLE_SIZE,
                new String[]{"small", "medium", "large"}, new String[]{"صغير", "متوسط", "كبير"});
        addSettingRow(sound, "حجم الترجمة", "حجم النص الظاهر فوق الفيديو", subtitleSize);

        LinearLayout live = section(page, "البث المباشر", "سلوك المعاينة والتنقل بين القنوات");
        Button autoplay = cycleButton("التشغيل", KEY_AUTOPLAY_LIVE,
                new String[]{"on", "off"}, new String[]{"تشغيل أول قناة", "انتظار الاختيار"});
        addSettingRow(live, "التشغيل التلقائي", "تشغيل أول قناة ظاهرة عند دخول الفئة", autoplay);
        addSettingRow(live, "تبديل القنوات", "الأسهم داخل القائمة و CH+/CH- داخل نفس الفئة",
                lockedButton("مفعّل"));

        LinearLayout vod = section(page, "الأفلام والمسلسلات", "الاستئناف وترتيب الحلقات");
        Button resume = cycleButton("الاستئناف", KEY_RESUME_PROMPT,
                new String[]{"on", "off"}, new String[]{"إظهار دائمًا", "تشغيل مباشر"});
        addSettingRow(vod, "نافذة الاستئناف", "استئناف المشاهدة أو البدء من جديد", resume);
        Button nextEpisode = cycleButton("التالي", KEY_AUTO_NEXT,
                new String[]{"ask", "on", "off"}, new String[]{"اسألني", "تشغيل تلقائي", "إيقاف"});
        addSettingRow(vod, "الحلقة التالية", "اختيار سلوك التطبيق عند نهاية الحلقة", nextEpisode);
        addSettingRow(vod, "ترتيب الحلقات", "من الحلقة 1 إلى آخر حلقة داخل كل موسم",
                lockedButton("1 ← الأخير"));

        LinearLayout guide = section(page, "دليل البرامج والمظهر", "الوقت والحركة داخل واجهة التلفزيون");
        Button timezone = cycleButton("المنطقة", KEY_EPG_TIMEZONE,
                new String[]{"device", "riyadh", "utc"}, new String[]{"وقت الجهاز", "الرياض", "UTC"});
        addSettingRow(guide, "المنطقة الزمنية EPG", "ضبط أوقات البرنامج الحالي والقادم", timezone);
        Button motion = cycleButton("الحركة", KEY_MOTION,
                new String[]{"smooth", "reduced"}, new String[]{"سلسة", "خفيفة للأجهزة الضعيفة"});
        addSettingRow(guide, "حركة الواجهة", "تأثير التركيز والانتقال بين العناصر", motion);

        LinearLayout system = section(page, "التخزين والنظام", "إدارة البيانات وفحص حالة التطبيق");
        Button switchPlaylist = BlofyUi.button(this, "اختيار قائمة التشغيل", false);
        switchPlaylist.setOnClickListener(v -> openPlaylistHub());
        addSettingRow(system, "قوائم التشغيل",
                "العودة إلى القائمة المحفوظة أو إضافة سيرفر جديد", switchPlaylist);
        Button logoutPlaylist = BlofyUi.button(this, "فصل القائمة الحالية", false);
        logoutPlaylist.setOnClickListener(v -> logoutCurrentPlaylist());
        addSettingRow(system, "فصل القائمة الحالية",
                "إنهاء الاتصال الحالي مع إبقاء جميع القوائم محفوظة", logoutPlaylist);
        Button clearProgress = BlofyUi.button(this, "مسح سجل المشاهدة", false);
        clearProgress.setOnClickListener(v -> {
            PlaybackProgress.clearAll(this);
            ToastBridge.show(this, "تم مسح سجل المشاهدة");
        });
        addSettingRow(system, "سجل المشاهدة", "حذف مواضع الاستئناف والحلقات الأخيرة", clearProgress);
        Button diagnostic = BlofyUi.button(this, "تشغيل الفحص", false);
        diagnostic.setOnClickListener(v -> ToastBridge.show(this,
                "الجهاز جاهز • اختبر مصدر البث من شاشة القناة عند وجود مشكلة"));
        addSettingRow(system, "فحص التشغيل", "حالة الجهاز والشبكة ومحركات الفيديو", diagnostic);

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

    private void openPlaylistHub() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void logoutCurrentPlaylist() {
        ToastBridge.show(this, "جاري تسجيل الخروج من قائمة التشغيل");
        new Thread(() -> {
            BlofyApi api = new BlofyApi(this);
            try { api.delete("/api/session"); } catch (Exception ignored) {}
            api.clearSession();
            new PlaylistStore(this).clearActive();
            CatalogDatabase database = new CatalogDatabase(this);
            database.beginFreshImport();
            database.close();
            runOnUiThread(this::openPlaylistHub);
        }, "blofy-playlist-logout").start();
    }

    private LinearLayout section(LinearLayout page, String title, String description) {
        TextView heading = BlofyUi.title(this, title, 19);
        heading.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        hp.setMargins(0, dp(20), 0, 0);
        page.addView(heading, hp);
        TextView detail = BlofyUi.text(this, description, 12, BlofyUi.MUTED);
        page.addView(detail, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(32)));
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(12), dp(18), dp(12));
        panel.setBackground(BlofyUi.panel(this, Color.argb(228, 15, 13, 28), 18, BlofyUi.STROKE));
        page.addView(panel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return panel;
    }

    private Button lockedButton(String label) {
        Button button = BlofyUi.button(this, label, false);
        button.setEnabled(false);
        button.setAlpha(.78f);
        return button;
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
        return cycleButton(label, key, values, labels, null);
    }

    private Button cycleButton(String label, String key, String[] values, String[] labels,
                               Runnable afterChange) {
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
            if (afterChange != null) afterChange.run();
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
