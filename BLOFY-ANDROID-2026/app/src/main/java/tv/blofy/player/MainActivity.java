package tv.blofy.player;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import org.json.JSONObject;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private FrameLayout root;
    private BlofyApi api;
    private CatalogDatabase database;
    private ImageLoader images;
    private BlofyModels.License license;
    private BlofyModels.Session session;
    private String screen = "splash";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BlofyUi.BLACK);
        getWindow().setNavigationBarColor(BlofyUi.BLACK);
        root = new FrameLayout(this);
        root.setBackground(BlofyUi.screenGradient());
        setContentView(root);
        api = new BlofyApi(this);
        database = new CatalogDatabase(this);
        images = new ImageLoader(api);
        showSplash("جاري تشغيل BLOFY PLAYER", "تهيئة التطبيق الأصلي");
        boot();
    }

    private void boot() {
        worker.execute(() -> {
            try {
                registerDevice();
                license = new BlofyModels.License(api.get("/api/license?device_id=" + BlofyApi.encode(api.deviceId())));
                if (license.usable()) tryRemoteSetup();
                session = new BlofyModels.Session(api.get("/api/session"));
                main.post(() -> {
                    if (!license.usable() || !session.present) showLogin("");
                    else if (database.count("live") + database.count("movies") + database.count("series") > 0) showHome();
                    else importPackage();
                });
            } catch (Exception error) {
                main.post(() -> showLogin(message(error)));
            }
        });
    }

    private void registerDevice() {
        try {
            JSONObject body = new JSONObject();
            body.put("deviceId", api.deviceId());
            body.put("deviceKey", DeviceIdentity.secret(this));
            JSONObject result = api.post("/api/device/register", body);
            DeviceIdentity.pairToken(this, result.optString("pairToken", ""));
        } catch (Exception ignored) {}
    }

    private void tryRemoteSetup() {
        try { api.get("/api/device/bootstrap?device_id=" + BlofyApi.encode(api.deviceId())); }
        catch (Exception ignored) {}
    }

    private void showSplash(String title, String detail) {
        screen = "splash";
        root.removeAllViews();
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.blofy_logo);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        content.addView(logo, new LinearLayout.LayoutParams(dp(220), dp(220)));
        TextView titleView = BlofyUi.title(this, title, 25);
        titleView.setGravity(Gravity.CENTER);
        content.addView(titleView);
        TextView detailView = BlofyUi.text(this, detail, 14, BlofyUi.MUTED);
        detailView.setGravity(Gravity.CENTER);
        content.addView(detailView);
        ProgressBar progress = new ProgressBar(this);
        progress.setIndeterminateTintList(BlofyUi.progressColors());
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(50), dp(50));
        progressParams.topMargin = dp(18);
        content.addView(progress, progressParams);
        root.addView(content, match());
    }

    private void showLogin(String error) {
        screen = "login";
        root.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(BlofyUi.isTv(this) ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER);
        page.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        page.setPadding(dp(28), dp(24), dp(28), dp(24));

        LinearLayout device = devicePanel();
        LinearLayout.LayoutParams deviceParams = new LinearLayout.LayoutParams(BlofyUi.isTv(this) ? dp(340) : ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        deviceParams.setMargins(dp(10), dp(10), dp(10), dp(10));
        page.addView(device, deviceParams);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(26), dp(22), dp(26), dp(22));
        form.setBackground(BlofyUi.panel(this, Color.argb(235, 13, 13, 25), 20, Color.rgb(48, 42, 72)));
        form.addView(BlofyUi.brand(this, "P L A Y E R  •  N A T I V E"));
        form.addView(BlofyUi.title(this, "كل محتواك في مكان واحد، بسرعة ووضوح", 25));
        TextView intro = BlofyUi.text(this, "أدخل بيانات الباقة هنا، أو امسح الباركود وأرسلها من جوالك إلى الجهاز.", 14, BlofyUi.MUTED);
        form.addView(intro);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.RIGHT);
        Button xtreamTab = BlofyUi.button(this, "Xtream Codes", true);
        Button m3uTab = BlofyUi.button(this, "M3U / M3U8", false);
        tabs.addView(xtreamTab, new LinearLayout.LayoutParams(dp(180), dp(52)));
        LinearLayout.LayoutParams secondTab = new LinearLayout.LayoutParams(dp(180), dp(52));
        secondTab.setMargins(dp(10), 0, 0, 0);
        tabs.addView(m3uTab, secondTab);
        LinearLayout.LayoutParams tabsParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tabsParams.topMargin = dp(18);
        form.addView(tabs, tabsParams);

        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        EditText name = addField(fields, "اسم القائمة (اختياري)", false);
        EditText server = addField(fields, "رابط الخادم", false);
        EditText username = addField(fields, "اسم المستخدم", false);
        EditText password = addField(fields, "كلمة المرور", false);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText playlist = addField(fields, "رابط M3U أو M3U8", false);
        playlist.setVisibility(View.GONE);
        form.addView(fields);

        TextView status = BlofyUi.text(this, error, 13, error.isEmpty() ? BlofyUi.MUTED : BlofyUi.ERROR);
        status.setGravity(Gravity.RIGHT);
        form.addView(status);

        Button login = BlofyUi.button(this, "إضافة الباقة وقراءتها", true);
        LinearLayout.LayoutParams loginParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
        loginParams.topMargin = dp(10);
        form.addView(login, loginParams);

        final boolean[] xtream = {true};
        xtreamTab.setOnClickListener(view -> {
            xtream[0] = true;
            server.setVisibility(View.VISIBLE);
            username.setVisibility(View.VISIBLE);
            password.setVisibility(View.VISIBLE);
            playlist.setVisibility(View.GONE);
            xtreamTab.setText("Xtream Codes ✓");
            m3uTab.setText("M3U / M3U8");
            server.requestFocus();
        });
        m3uTab.setOnClickListener(view -> {
            xtream[0] = false;
            server.setVisibility(View.GONE);
            username.setVisibility(View.GONE);
            password.setVisibility(View.GONE);
            playlist.setVisibility(View.VISIBLE);
            xtreamTab.setText("Xtream Codes");
            m3uTab.setText("M3U / M3U8 ✓");
            playlist.requestFocus();
        });
        login.setOnClickListener(view -> {
            if (license == null || !license.usable()) {
                status.setText("فعّل الجهاز أولًا، أو اضغط تحديث التفعيل بعد التفعيل من الباركود.");
                status.setTextColor(BlofyUi.ERROR);
                return;
            }
            JSONObject body = new JSONObject();
            try {
                body.put("kind", xtream[0] ? "xtream" : "m3u");
                body.put("name", value(name));
                if (xtream[0]) {
                    body.put("serverUrl", value(server));
                    body.put("username", value(username));
                    body.put("password", value(password));
                } else body.put("url", value(playlist));
            } catch (Exception ignored) {}
            status.setText("جاري التحقق من بيانات الباقة…");
            status.setTextColor(BlofyUi.MUTED);
            login.setEnabled(false);
            worker.execute(() -> {
                try {
                    api.post("/api/session", body);
                    session = new BlofyModels.Session(api.get("/api/session"));
                    main.post(this::importPackage);
                } catch (Exception failure) {
                    main.post(() -> {
                        login.setEnabled(true);
                        status.setText(message(failure));
                        status.setTextColor(BlofyUi.ERROR);
                    });
                }
            });
        });

        LinearLayout.LayoutParams formParams = new LinearLayout.LayoutParams(BlofyUi.isTv(this) ? 0 : ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, BlofyUi.isTv(this) ? 1f : 0f);
        formParams.setMargins(dp(10), dp(10), dp(10), dp(10));
        page.addView(form, formParams);
        scroll.addView(page);
        root.addView(scroll, match());
        main.postDelayed(() -> {
            View focus = license != null && !license.usable() ? device.findViewWithTag("activation_code") : server;
            if (focus != null) focus.requestFocus();
        }, 100);
    }

    private LinearLayout devicePanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(22), dp(20), dp(22), dp(20));
        panel.setBackground(BlofyUi.panel(this, Color.argb(235, 15, 16, 30), 20, Color.rgb(61, 44, 94)));
        ImageView smallLogo = new ImageView(this);
        smallLogo.setImageResource(R.drawable.blofy_logo);
        smallLogo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        panel.addView(smallLogo, new LinearLayout.LayoutParams(dp(115), dp(115)));
        TextView heading = BlofyUi.title(this, "حالة الجهاز", 19);
        heading.setGravity(Gravity.CENTER);
        panel.addView(heading);
        TextView id = BlofyUi.text(this, api.deviceId(), 13, BlofyUi.TEXT);
        id.setTextDirection(View.TEXT_DIRECTION_LTR);
        id.setGravity(Gravity.CENTER);
        id.setTextIsSelectable(true);
        panel.addView(id);
        String licenseText = license == null ? "جاري التحقق" : license.status + " • " + license.remainingDays + " أيام";
        TextView plan = BlofyUi.text(this, licenseText, 14, license != null && license.usable() ? BlofyUi.SUCCESS : BlofyUi.ERROR);
        plan.setGravity(Gravity.CENTER);
        panel.addView(plan);

        ImageView qr = new ImageView(this);
        qr.setBackgroundColor(Color.WHITE);
        qr.setPadding(dp(7), dp(7), dp(7), dp(7));
        qr.setImageBitmap(qr(api.activationUrl(license), 260));
        LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(dp(190), dp(190));
        qrParams.topMargin = dp(12);
        panel.addView(qr, qrParams);
        TextView qrText = BlofyUi.text(this, "امسح الباركود للتفعيل وإرسال بيانات الباقة", 12, BlofyUi.MUTED);
        qrText.setGravity(Gravity.CENTER);
        panel.addView(qrText);

        EditText code = BlofyUi.input(this, "رمز التفعيل الرقمي", true);
        code.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(12)});
        code.setTag("activation_code");
        LinearLayout.LayoutParams codeParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        codeParams.topMargin = dp(12);
        panel.addView(code, codeParams);
        Button activate = BlofyUi.button(this, "تفعيل / تحديث من الموقع", true);
        LinearLayout.LayoutParams activateParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        activateParams.topMargin = dp(9);
        panel.addView(activate, activateParams);
        activate.setOnClickListener(view -> {
            activate.setEnabled(false);
            String supplied = value(code);
            worker.execute(() -> {
                try {
                    if (!supplied.isEmpty()) {
                        JSONObject body = new JSONObject();
                        body.put("deviceId", api.deviceId());
                        body.put("code", supplied);
                        api.post("/api/activate", body);
                    }
                    license = new BlofyModels.License(api.get("/api/license?device_id=" + BlofyApi.encode(api.deviceId())));
                    tryRemoteSetup();
                    session = new BlofyModels.Session(api.get("/api/session"));
                    main.post(() -> {
                        Toast.makeText(this, license.usable() ? "تم تحديث التفعيل" : "الجهاز غير مفعّل", Toast.LENGTH_LONG).show();
                        if (license.usable() && session.present) importPackage(); else showLogin("");
                    });
                } catch (Exception failure) {
                    main.post(() -> {
                        activate.setEnabled(true);
                        Toast.makeText(this, message(failure), Toast.LENGTH_LONG).show();
                    });
                }
            });
        });
        return panel;
    }

    private EditText addField(LinearLayout parent, String hint, boolean numeric) {
        EditText input = BlofyUi.input(this, hint, numeric);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        params.topMargin = dp(10);
        parent.addView(input, params);
        return input;
    }

    private void importPackage() {
        screen = "import";
        root.removeAllViews();
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(30), dp(26), dp(30), dp(26));
        panel.setBackground(BlofyUi.panel(this, Color.argb(238, 13, 13, 25), 22, Color.rgb(75, 49, 117)));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.blofy_logo);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        panel.addView(logo, new LinearLayout.LayoutParams(dp(150), dp(150)));
        TextView percent = BlofyUi.title(this, "0%", 34);
        percent.setGravity(Gravity.CENTER);
        panel.addView(percent);
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(BlofyUi.progressColors());
        progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(45, 40, 63)));
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(BlofyUi.isTv(this) ? dp(650) : ViewGroup.LayoutParams.MATCH_PARENT, dp(14));
        progressParams.topMargin = dp(12);
        panel.addView(progress, progressParams);
        TextView title = BlofyUi.title(this, "بدء قراءة الباقة", 21);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = dp(15);
        panel.addView(title, titleParams);
        TextView detail = BlofyUi.text(this, "سيتم حفظ البيانات على الجهاز لفتح أسرع لاحقًا", 14, BlofyUi.MUTED);
        detail.setGravity(Gravity.CENTER);
        panel.addView(detail);
        FrameLayout holder = new FrameLayout(this);
        FrameLayout.LayoutParams holderParams = new FrameLayout.LayoutParams(BlofyUi.isTv(this) ? dp(780) : ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        holderParams.setMargins(dp(24), dp(24), dp(24), dp(24));
        holder.addView(panel, match());
        root.addView(holder, holderParams);

        worker.execute(() -> {
            try {
                PackageImporter importer = new PackageImporter(api, database, (value, step, note) -> main.post(() -> {
                    progress.setProgress(value, true);
                    percent.setText(value + "%");
                    title.setText(step);
                    detail.setText(note);
                }));
                importer.run();
                main.postDelayed(this::showHome, 450);
            } catch (Exception error) {
                main.post(() -> showImportError(message(error)));
            }
        });
    }

    private void showImportError(String error) {
        root.removeAllViews();
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(30), dp(30), dp(30), dp(30));
        TextView title = BlofyUi.title(this, "تعذرت قراءة الباقة", 25);
        title.setGravity(Gravity.CENTER);
        panel.addView(title);
        TextView detail = BlofyUi.text(this, error, 15, BlofyUi.ERROR);
        detail.setGravity(Gravity.CENTER);
        panel.addView(detail);
        Button retry = BlofyUi.button(this, "إعادة المحاولة", true);
        retry.setOnClickListener(view -> importPackage());
        LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(dp(280), dp(58));
        retryParams.topMargin = dp(18);
        panel.addView(retry, retryParams);
        Button logout = BlofyUi.button(this, "تغيير بيانات الباقة", false);
        logout.setOnClickListener(view -> logout());
        LinearLayout.LayoutParams logoutParams = new LinearLayout.LayoutParams(dp(280), dp(58));
        logoutParams.topMargin = dp(10);
        panel.addView(logout, logoutParams);
        root.addView(panel, match());
        retry.requestFocus();
    }

    private void showHome() {
        screen = "home";
        root.removeAllViews();
        LinearLayout page = basePage("الرئيسية", database.metadata("server_name", "BLOFY PLAYER"));
        TextView welcome = BlofyUi.title(this, "مرحبًا بك في BLOFY PLAYER", 27);
        page.addView(welcome);
        TextView subtitle = BlofyUi.text(this, "اختر قسمك واستمتع بتشغيل Native سريع عبر Media3", 14, BlofyUi.MUTED);
        page.addView(subtitle);

        GridLayout grid = new GridLayout(this);
        int columns = BlofyUi.isTv(this) ? 3 : 2;
        grid.setColumnCount(columns);
        grid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        grid.setUseDefaultMargins(true);
        addHomeCard(grid, "📡", "بث مباشر", database.count("live") + " قناة", () -> showCatalog("live", false, false));
        addHomeCard(grid, "🎬", "أفلام", database.count("movies") + " فيلم", () -> showCatalog("movies", false, false));
        addHomeCard(grid, "▣", "مسلسلات", database.count("series") + " مسلسل", () -> showCatalog("series", false, false));
        addHomeCard(grid, "♥", "المفضلة", "محتواك المحفوظ", () -> showCatalog("", true, false));
        addHomeCard(grid, "◷", "سجل المشاهدة", "تابع من حيث توقفت", () -> showCatalog("", false, true));
        addHomeCard(grid, "⚙", "الإعدادات", "الجهاز والباقة والتشغيل", this::showSettings);
        LinearLayout.LayoutParams gridParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gridParams.topMargin = dp(18);
        page.addView(grid, gridParams);

        TextView profile = BlofyUi.text(this, "طريقة التشغيل: " + database.metadata("playback_profile", "Media3 مباشر"), 13, BlofyUi.PURPLE_LIGHT);
        LinearLayout.LayoutParams profileParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        profileParams.topMargin = dp(14);
        page.addView(profile, profileParams);
        wrapPage(page);
        main.postDelayed(this::focusFirstAction, 100);
    }

    private LinearLayout basePage(String title, String subtitle) {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        page.setPadding(dp(28), dp(18), dp(28), dp(24));
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        header.addView(BlofyUi.brand(this, "P L A Y E R"));
        LinearLayout spacer = new LinearLayout(this);
        header.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = BlofyUi.title(this, title, 18);
        TextView subtitleView = BlofyUi.text(this, subtitle, 12, BlofyUi.MUTED);
        heading.addView(titleView);
        heading.addView(subtitleView);
        header.addView(heading);
        page.addView(header);
        return page;
    }

    private void addHomeCard(GridLayout grid, String icon, String title, String subtitle, Runnable action) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(18), dp(17), dp(18), dp(17));
        card.setFocusable(true);
        card.setClickable(true);
        card.setBackground(BlofyUi.focusDrawable(this, Color.argb(230, 15, 16, 31), Color.rgb(40, 23, 72), BlofyUi.PURPLE_LIGHT));
        TextView iconView = BlofyUi.title(this, icon, 30);
        iconView.setGravity(Gravity.CENTER);
        card.addView(iconView);
        TextView titleView = BlofyUi.title(this, title, 19);
        titleView.setGravity(Gravity.CENTER);
        card.addView(titleView);
        TextView subView = BlofyUi.text(this, subtitle, 12, BlofyUi.MUTED);
        subView.setGravity(Gravity.CENTER);
        card.addView(subView);
        card.setOnClickListener(view -> action.run());
        scaleOnFocus(card);
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = dp(BlofyUi.isTv(this) ? 165 : 150);
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.setMargins(dp(7), dp(7), dp(7), dp(7));
        grid.addView(card, params);
    }

    private void showCatalog(String type, boolean favoritesOnly, boolean historyOnly) {
        screen = "catalog";
        root.removeAllViews();
        String label = favoritesOnly ? "المفضلة" : historyOnly ? "سجل المشاهدة" : typeName(type);
        LinearLayout page = basePage(label, database.metadata("server_name", ""));
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        Button back = BlofyUi.button(this, "رجوع", false);
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(110), dp(52)));
        EditText search = BlofyUi.input(this, "بحث في " + label, false);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(0, dp(52), 1);
        searchParams.setMargins(dp(10), 0, dp(10), 0);
        toolbar.addView(search, searchParams);
        Button refresh = BlofyUi.button(this, "تحديث الباقة", false);
        toolbar.addView(refresh, new LinearLayout.LayoutParams(dp(150), dp(52)));
        page.addView(toolbar);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        content.setGravity(Gravity.TOP);
        RecyclerView categories = new RecyclerView(this);
        categories.setLayoutManager(new LinearLayoutManager(this));
        categories.setBackground(BlofyUi.panel(this, Color.argb(220, 13, 14, 27), 16, Color.rgb(45, 42, 65)));
        if (!favoritesOnly && !historyOnly && !type.isEmpty()) {
            List<BlofyModels.Category> values = new ArrayList<>();
            values.add(new BlofyModels.Category("", "جميع " + typeName(type), type));
            values.addAll(database.categories(type));
            categories.setAdapter(new CategoryAdapter(values));
            content.addView(categories, new LinearLayout.LayoutParams(dp(230), ViewGroup.LayoutParams.MATCH_PARENT));
        }

        RecyclerView media = new RecyclerView(this);
        int span = "live".equals(type) ? (BlofyUi.isTv(this) ? 3 : 1) : (BlofyUi.isTv(this) ? 5 : 2);
        media.setLayoutManager(new GridLayoutManager(this, span));
        MediaAdapter adapter = new MediaAdapter(type, "", "", favoritesOnly, historyOnly);
        media.setAdapter(adapter);
        LinearLayout.LayoutParams mediaParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        mediaParams.setMargins(dp(12), 0, 0, 0);
        content.addView(media, mediaParams);
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        contentParams.topMargin = dp(14);
        page.addView(content, contentParams);
        root.addView(page, match());

        if (categories.getAdapter() instanceof CategoryAdapter) {
            ((CategoryAdapter) categories.getAdapter()).listener = category -> adapter.reload(category.id, search.getText().toString());
        }
        search.setOnEditorActionListener((view, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                adapter.reload(adapter.category, search.getText().toString());
                return true;
            }
            return false;
        });
        back.setOnClickListener(view -> showHome());
        refresh.setOnClickListener(view -> importPackage());
        main.postDelayed(() -> {
            if (categories.getAdapter() != null && categories.getAdapter().getItemCount() > 0) categories.requestFocus();
            else media.requestFocus();
        }, 120);
    }

    private void openMedia(BlofyModels.Media item) {
        if ("series".equals(item.type) || ("movies".equals(item.type) && session != null && "xtream".equals(session.kind))) {
            Intent detail = new Intent(this, DetailsActivity.class);
            detail.putExtra(DetailsActivity.EXTRA_ITEM, item.json().toString());
            startActivity(detail);
            return;
        }
        database.addHistory(item.type, item.id);
        Intent player = new Intent(this, PlayerActivity.class);
        player.putExtra(PlayerActivity.EXTRA_ID, item.id);
        player.putExtra(PlayerActivity.EXTRA_TITLE, item.name);
        player.putExtra(PlayerActivity.EXTRA_KIND, item.type);
        player.putExtra(PlayerActivity.EXTRA_EXTENSION, item.extension);
        startActivity(player);
    }

    private void showSettings() {
        screen = "settings";
        root.removeAllViews();
        LinearLayout page = basePage("الإعدادات", "الجهاز والاشتراك والتشغيل");
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(22), dp(24), dp(22));
        panel.setBackground(BlofyUi.panel(this, Color.argb(235, 14, 15, 28), 18, Color.rgb(53, 45, 78)));
        addSetting(panel, "رقم الجهاز", api.deviceId());
        addSetting(panel, "حالة الاشتراك", license == null ? "غير معروف" : license.status + " • " + license.remainingDays + " أيام");
        addSetting(panel, "الخادم", database.metadata("server_name", "—"));
        addSetting(panel, "آخر قراءة", formatDate(database.metadata("last_sync", "0")));
        addSetting(panel, "طريقة التشغيل", database.metadata("playback_profile", "Media3 مباشر"));
        page.addView(panel);
        Button sync = BlofyUi.button(this, "إعادة قراءة الباقة", true);
        sync.setOnClickListener(view -> importPackage());
        LinearLayout.LayoutParams syncParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
        syncParams.topMargin = dp(14);
        page.addView(sync, syncParams);
        Button logout = BlofyUi.button(this, "تغيير بيانات الباقة", false);
        logout.setOnClickListener(view -> logout());
        LinearLayout.LayoutParams logoutParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
        logoutParams.topMargin = dp(10);
        page.addView(logout, logoutParams);
        Button back = BlofyUi.button(this, "العودة للرئيسية", false);
        back.setOnClickListener(view -> showHome());
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
        backParams.topMargin = dp(10);
        page.addView(back, backParams);
        wrapPage(page);
        sync.requestFocus();
    }

    private void addSetting(LinearLayout panel, String key, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        TextView label = BlofyUi.text(this, key, 14, BlofyUi.MUTED);
        TextView content = BlofyUi.title(this, value, 14);
        row.addView(label, new LinearLayout.LayoutParams(dp(190), dp(48)));
        row.addView(content, new LinearLayout.LayoutParams(0, dp(48), 1));
        panel.addView(row);
    }

    private void logout() {
        showSplash("جاري تغيير الباقة", "إنهاء الجلسة الحالية");
        worker.execute(() -> {
            try { api.delete("/api/session"); } catch (Exception ignored) {}
            api.clearSession();
            database.beginFreshImport();
            session = new BlofyModels.Session(null);
            main.post(() -> showLogin(""));
        });
    }

    private void wrapPage(LinearLayout page) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(page);
        root.addView(scroll, match());
    }

    private void focusFirstAction() {
        ArrayList<View> views = new ArrayList<>();
        root.addFocusables(views, View.FOCUS_FORWARD);
        if (!views.isEmpty()) views.get(0).requestFocus();
    }

    private void scaleOnFocus(View view) {
        view.setOnFocusChangeListener((target, focused) -> target.animate().scaleX(focused ? 1.035f : 1f).scaleY(focused ? 1.035f : 1f).setDuration(110).start());
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(int value) { return BlofyUi.dp(this, value); }
    private static String value(EditText input) { return input.getText().toString().trim(); }
    private static String message(Throwable error) { return error == null || error.getMessage() == null ? "حدث خطأ غير متوقع." : error.getMessage(); }

    private static String typeName(String type) {
        if ("live".equals(type)) return "القنوات";
        if ("movies".equals(type)) return "الأفلام";
        if ("series".equals(type)) return "المسلسلات";
        return "المحتوى";
    }

    private static String formatDate(String value) {
        try { return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, new Locale("ar", "SA")).format(new Date(Long.parseLong(value))); }
        catch (Exception ignored) { return "—"; }
    }

    private Bitmap qr(String text, int size) {
        try {
            BitMatrix bits = new MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size);
            int[] pixels = new int[size * size];
            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) pixels[y * size + x] = bits.get(x, y) ? Color.rgb(18, 0, 51) : Color.WHITE;
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
            bitmap.setPixels(pixels, 0, size, 0, 0, size, size);
            return bitmap;
        } catch (Exception ignored) { return null; }
    }

    @Override
    public void onBackPressed() {
        if ("home".equals(screen) || "login".equals(screen) || "splash".equals(screen)) finish();
        else showHome();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        database.close();
        super.onDestroy();
    }

    private interface CategoryListener { void selected(BlofyModels.Category category); }

    private final class CategoryAdapter extends RecyclerView.Adapter<CategoryAdapter.Holder> {
        private final List<BlofyModels.Category> rows;
        CategoryListener listener;
        CategoryAdapter(List<BlofyModels.Category> rows) { this.rows = rows; }
        @Override public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            Button button = BlofyUi.button(parent.getContext(), "", false);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
            params.setMargins(dp(7), dp(5), dp(7), dp(5));
            button.setLayoutParams(params);
            return new Holder(button);
        }
        @Override public void onBindViewHolder(Holder holder, int position) {
            BlofyModels.Category item = rows.get(position);
            holder.button.setText(item.name);
            holder.button.setOnClickListener(view -> { if (listener != null) listener.selected(item); });
        }
        @Override public int getItemCount() { return rows.size(); }
        final class Holder extends RecyclerView.ViewHolder {
            final Button button;
            Holder(Button button) { super(button); this.button = button; }
        }
    }

    private final class MediaAdapter extends RecyclerView.Adapter<MediaAdapter.Holder> {
        private final String type;
        private final boolean favoritesOnly;
        private final boolean historyOnly;
        private List<BlofyModels.Media> rows = new ArrayList<>();
        private String category;
        private String search;

        MediaAdapter(String type, String category, String search, boolean favoritesOnly, boolean historyOnly) {
            this.type = type;
            this.favoritesOnly = favoritesOnly;
            this.historyOnly = historyOnly;
            reload(category, search);
        }

        void reload(String selectedCategory, String query) {
            category = selectedCategory == null ? "" : selectedCategory;
            search = query == null ? "" : query;
            rows = database.media(type, category, search, favoritesOnly, historyOnly, 5000, 0);
            notifyDataSetChanged();
        }

        @Override public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            LinearLayout card = new LinearLayout(parent.getContext());
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER);
            card.setPadding(dp(7), dp(7), dp(7), dp(8));
            card.setFocusable(true);
            card.setClickable(true);
            card.setBackground(BlofyUi.focusDrawable(MainActivity.this, Color.argb(225, 14, 15, 28), Color.rgb(43, 23, 74), BlofyUi.PURPLE_LIGHT));
            ImageView image = new ImageView(parent.getContext());
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            int height = "live".equals(type) ? dp(92) : dp(185);
            card.addView(image, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
            TextView name = BlofyUi.title(parent.getContext(), "", 14);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(2);
            card.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
            TextView meta = BlofyUi.text(parent.getContext(), "", 11, BlofyUi.MUTED);
            meta.setGravity(Gravity.CENTER);
            card.addView(meta, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)));
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(dp(6), dp(6), dp(6), dp(6));
            card.setLayoutParams(params);
            scaleOnFocus(card);
            return new Holder(card, image, name, meta);
        }

        @Override public void onBindViewHolder(Holder holder, int position) {
            BlofyModels.Media item = rows.get(position);
            holder.name.setText(item.name);
            holder.meta.setText((item.year.isEmpty() ? "" : item.year + "  •  ") + (item.rating.isEmpty() ? item.extension.toUpperCase(Locale.US) : "★ " + item.rating));
            images.load(holder.image, item.image);
            holder.card.setOnClickListener(view -> openMedia(item));
            holder.card.setOnLongClickListener(view -> {
                boolean saved = database.toggleFavorite(item.type, item.id);
                Toast.makeText(MainActivity.this, saved ? "تمت الإضافة للمفضلة" : "تمت الإزالة من المفضلة", Toast.LENGTH_SHORT).show();
                if (favoritesOnly && !saved) reload(category, search);
                return true;
            });
        }

        @Override public int getItemCount() { return rows.size(); }
        final class Holder extends RecyclerView.ViewHolder {
            final LinearLayout card;
            final ImageView image;
            final TextView name;
            final TextView meta;
            Holder(LinearLayout card, ImageView image, TextView name, TextView meta) {
                super(card); this.card = card; this.image = image; this.name = name; this.meta = meta;
            }
        }
    }
}
