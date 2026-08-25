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
    private PlaylistStore playlistStore;
    private BlofyModels.License license;
    private BlofyModels.Session session;
    private String screen = "splash";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        PlaybackTransportFactory.warmUpCronet(this);
        getWindow().setStatusBarColor(BlofyUi.BLACK);
        getWindow().setNavigationBarColor(BlofyUi.BLACK);
        root = new FrameLayout(this);
        root.setBackground(BlofyUi.screenGradient());
        setContentView(root);
        api = new BlofyApi(this);
        database = new CatalogDatabase(this);
        images = new ImageLoader(api);
        playlistStore = new PlaylistStore(this);
        showSplash("جاري تشغيل BLOFY PLAYER", "تهيئة التطبيق الأصلي");
        boot();
    }

    private void boot() {
        worker.execute(() -> {
            try {
                registerDevice();
                license = new BlofyModels.License(api.get("/api/license?device_id=" + BlofyApi.encode(api.deviceId())));
                JSONObject bootstrap = license.usable() ? tryRemoteSetup() : null;
                if (bootstrap != null) {
                    DeviceIdentity.updatePublicIdentity(this, bootstrap);
                    playlistStore.applyRemote(bootstrap);
                }
                if (license.usable() && (bootstrap == null || !bootstrap.has("playlists"))) refreshRemotePlaylists();
                session = new BlofyModels.Session(api.get("/api/session"));
                main.post(() -> {
                    showPlaylistHub("");
                });
            } catch (Exception error) {
                main.post(() -> showPlaylistHub(message(error)));
            }
        });
    }

    private void registerDevice() {
        try {
            JSONObject body = new JSONObject();
            body.put("deviceId", api.deviceId());
            body.put("deviceKey", DeviceIdentity.secret(this));
            body.put("displayId", DeviceIdentity.displayId(this));
            body.put("pairingCode", DeviceIdentity.activationCode(this));
            JSONObject result = api.post("/api/device/register", body);
            DeviceIdentity.pairToken(this, result.optString("pairToken", ""));
            DeviceIdentity.updatePublicIdentity(this, result);
        } catch (Exception ignored) {}
    }

    private JSONObject tryRemoteSetup() {
        try {
            return api.get("/api/device/bootstrap?device_id=" + BlofyApi.encode(api.deviceId())
                    + "&revision=" + playlistStore.revision() + "&connect=0");
        } catch (Exception ignored) { return null; }
    }

    private void refreshRemotePlaylists() {
        try { playlistStore.applyRemote(api.get("/api/device/playlists")); }
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

    /** Premium launcher: saved sources are visible, but never connected automatically. */
    private void showPlaylistHub(String error) {
        screen = "playlists";
        root.removeAllViews();
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(BlofyUi.isTv(this) ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER);
        page.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        page.setPadding(dp(34), dp(26), dp(34), dp(26));

        LinearLayout device = devicePanel(false);
        LinearLayout.LayoutParams deviceParams = new LinearLayout.LayoutParams(
                BlofyUi.isTv(this) ? dp(300) : ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        deviceParams.setMargins(dp(8), dp(8), dp(20), dp(8));
        page.addView(device, deviceParams);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        content.setPadding(dp(32), dp(26), dp(32), dp(26));
        content.setBackground(BlofyUi.gradientPanel(this, Color.argb(246, 13, 11, 24),
                Color.argb(244, 7, 7, 15), 24, BlofyUi.STROKE));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        TextView title = BlofyUi.title(this, "قوائم التشغيل", 30);
        title.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        TextView note = BlofyUi.text(this,
                "اختر قائمتك ثم اضغط اتصال. لن يبدأ أي سيرفر من تلقاء نفسه.", 14, BlofyUi.MUTED);
        heading.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        heading.addView(note, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));
        header.addView(heading, new LinearLayout.LayoutParams(0, dp(82), 1f));
        Button sync = BlofyUi.button(this, "↻  مزامنة", false);
        sync.setOnClickListener(v -> syncPlaylistHub(sync));
        header.addView(sync, new LinearLayout.LayoutParams(dp(145), dp(52)));
        content.addView(header);

        List<PlaylistStore.Playlist> rows = playlistStore.all();
        if (rows.isEmpty() && session != null && session.present) {
            rows = new ArrayList<>();
            rows.add(PlaylistStore.Playlist.fromSession(session));
        }

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        List<Button[]> focusRows = new ArrayList<>();
        if (rows.isEmpty()) {
            LinearLayout empty = new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(24), dp(18), dp(24), dp(18));
            empty.setBackground(BlofyUi.panel(this, Color.argb(190, 18, 15, 31), 18, BlofyUi.STROKE));
            TextView emptyTitle = BlofyUi.title(this, "ابدأ بإضافة قائمتك الأولى", 19);
            emptyTitle.setGravity(Gravity.CENTER);
            TextView emptyNote = BlofyUi.text(this,
                    "يمكنك إضافتها من التلفزيون أو من موقع BLOFY باستخدام رقم الجهاز والرمز.",
                    13, BlofyUi.MUTED);
            emptyNote.setGravity(Gravity.CENTER);
            empty.addView(emptyTitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
            empty.addView(emptyNote, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
            LinearLayout.LayoutParams emptyParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(112));
            emptyParams.setMargins(0, dp(14), 0, dp(12));
            list.addView(empty, emptyParams);
        } else {
            for (PlaylistStore.Playlist item : rows) addPlaylistCard(list, item, focusRows);
        }
        content.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        Button add = BlofyUi.button(this, "＋  إضافة قائمة تشغيل", rows.isEmpty());
        add.setOnClickListener(v -> showPlaylistEditor(null, ""));
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
        addParams.setMargins(0, dp(8), 0, 0);
        content.addView(add, addParams);

        if (error != null && !error.isEmpty()) {
            TextView status = BlofyUi.text(this, error, 13,
                    error.startsWith("تم ") ? BlofyUi.SUCCESS : BlofyUi.ERROR);
            status.setGravity(Gravity.CENTER);
            content.addView(status, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        }

        linkPlaylistFocus(focusRows, add, sync);
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
                BlofyUi.isTv(this) ? 0 : ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, BlofyUi.isTv(this) ? 1f : 0f);
        contentParams.setMargins(dp(8), dp(8), dp(8), dp(8));
        page.addView(content, contentParams);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setSmoothScrollingEnabled(true);
        scroll.addView(page);
        root.addView(scroll, match());
        Button initial = focusRows.isEmpty() ? add : focusRows.get(0)[2];
        for (Button[] row : focusRows) {
            Object tag = row[2].getTag();
            if (Boolean.TRUE.equals(tag)) { initial = row[2]; break; }
        }
        initial.requestFocus();
    }

    private void addPlaylistCard(LinearLayout list, PlaylistStore.Playlist item,
                                 List<Button[]> focusRows) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        card.setPadding(dp(20), dp(12), dp(20), dp(12));
        int stroke = item.active ? BlofyUi.PURPLE_LIGHT : BlofyUi.STROKE;
        card.setBackground(BlofyUi.gradientPanel(this, Color.argb(236, 28, 20, 49),
                Color.argb(232, 16, 14, 29), 18, stroke));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView playlistName = BlofyUi.title(this, item.displayName(), 19);
        playlistName.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        String state = item.active ? "●  القائمة المستخدمة آخر مرة"
                : item.isDefault ? "●  القائمة الافتراضية"
                : "healthy".equals(item.status) || "ready".equals(item.status) ? "●  جاهزة للاتصال"
                : "error".equals(item.status) ? "●  تحتاج فحص البيانات"
                : "●  محفوظة";
        int stateColor = item.active || item.isDefault
                || "healthy".equals(item.status) || "ready".equals(item.status)
                ? BlofyUi.SUCCESS : "error".equals(item.status) ? BlofyUi.ERROR : BlofyUi.MUTED;
        TextView meta = BlofyUi.text(this, item.kindLabel() + "     " + state, 12, stateColor);
        labels.addView(playlistName, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));
        labels.addView(meta, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)));
        card.addView(labels, new LinearLayout.LayoutParams(0, dp(70), 1f));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        Button delete = BlofyUi.button(this, "حذف", false);
        Button edit = BlofyUi.button(this, "تعديل", false);
        Button connect = BlofyUi.button(this, "اتصال", true);
        delete.setId(View.generateViewId()); edit.setId(View.generateViewId()); connect.setId(View.generateViewId());
        delete.setNextFocusRightId(edit.getId());
        edit.setNextFocusLeftId(delete.getId()); edit.setNextFocusRightId(connect.getId());
        connect.setNextFocusLeftId(edit.getId());
        delete.setOnClickListener(v -> confirmDeletePlaylist(item));
        edit.setOnClickListener(v -> openPlaylistEditor(item));
        connect.setOnClickListener(v -> connectPlaylist(item, connect));
        connect.setTag(item.active || item.isDefault);
        actions.addView(delete, new LinearLayout.LayoutParams(dp(90), dp(50)));
        LinearLayout.LayoutParams editParams = new LinearLayout.LayoutParams(dp(95), dp(50));
        editParams.setMargins(dp(8), 0, dp(8), 0);
        actions.addView(edit, editParams);
        actions.addView(connect, new LinearLayout.LayoutParams(dp(120), dp(50)));
        card.addView(actions, new LinearLayout.LayoutParams(dp(321), dp(56)));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(98));
        cardParams.setMargins(0, dp(8), 0, dp(8));
        list.addView(card, cardParams);
        focusRows.add(new Button[]{delete, edit, connect});
    }

    private void linkPlaylistFocus(List<Button[]> rows, Button add, Button sync) {
        add.setId(View.generateViewId()); sync.setId(View.generateViewId());
        for (int row = 0; row < rows.size(); row++) {
            Button[] current = rows.get(row);
            for (int column = 0; column < current.length; column++) {
                if (row > 0) current[column].setNextFocusUpId(rows.get(row - 1)[column].getId());
                else current[column].setNextFocusUpId(sync.getId());
                if (row + 1 < rows.size()) current[column].setNextFocusDownId(rows.get(row + 1)[column].getId());
                else current[column].setNextFocusDownId(add.getId());
            }
        }
        if (!rows.isEmpty()) {
            add.setNextFocusUpId(rows.get(rows.size() - 1)[2].getId());
            sync.setNextFocusDownId(rows.get(0)[2].getId());
        }
    }

    private void syncPlaylistHub(Button button) {
        button.setEnabled(false);
        button.setText("جاري المزامنة…");
        worker.execute(() -> {
            String result = "تم تحديث القوائم من الموقع.";
            try {
                registerDevice();
                JSONObject response = api.get("/api/device/playlists");
                playlistStore.applyRemote(response);
                session = new BlofyModels.Session(api.get("/api/session"));
            } catch (Exception failure) { result = message(failure); }
            String finalResult = result;
            main.post(() -> showPlaylistHub(finalResult));
        });
    }

    private void connectPlaylist(PlaylistStore.Playlist item, Button button) {
        if (license == null || !license.usable()) {
            showPlaylistHub("فعّل الجهاز أولًا من موقع BLOFY ثم اضغط مزامنة.");
            return;
        }
        button.setEnabled(false);
        button.setText("جاري الاتصال…");
        worker.execute(() -> {
            try {
                if (item.currentSessionOnly) {
                    session = new BlofyModels.Session(api.get("/api/session"));
                } else if (item.remote) {
                    api.post("/api/device/playlists/" + BlofyApi.encode(item.id) + "/connect", new JSONObject());
                    session = new BlofyModels.Session(api.get("/api/session"));
                } else if (item.canConnectLocally()) {
                    api.post("/api/session", item.sessionBody());
                    session = new BlofyModels.Session(api.get("/api/session"));
                } else {
                    throw new Exception("بيانات هذه القائمة غير مكتملة. افتح تعديل وأدخل بيانات الاتصال.");
                }
                if (session == null || !session.present) throw new Exception("لم يتم إنشاء جلسة للقائمة المختارة.");
                playlistStore.setActive(item.id);
                main.post(this::importPackage);
            } catch (Exception failure) {
                main.post(() -> showPlaylistHub(message(failure)));
            }
        });
    }

    private void confirmDeletePlaylist(PlaylistStore.Playlist item) {
        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.getWindow();
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(30), dp(26), dp(30), dp(26));
        panel.setBackground(BlofyUi.gradientPanel(this, Color.rgb(31, 20, 52),
                Color.rgb(12, 11, 23), 22, BlofyUi.PURPLE_LIGHT));
        TextView title = BlofyUi.title(this, "حذف " + item.displayName() + "؟", 23);
        title.setGravity(Gravity.CENTER);
        panel.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        TextView message = BlofyUi.text(this,
                "سيتم حذف هذه القائمة فقط، ولن تُحذف بقية قوائمك.", 14, BlofyUi.MUTED);
        message.setGravity(Gravity.CENTER);
        panel.addView(message, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        Button cancel = BlofyUi.button(this, "إلغاء", true);
        Button delete = BlofyUi.button(this, "حذف القائمة", false);
        cancel.setId(View.generateViewId()); delete.setId(View.generateViewId());
        cancel.setNextFocusRightId(delete.getId()); delete.setNextFocusLeftId(cancel.getId());
        cancel.setOnClickListener(v -> dialog.dismiss());
        delete.setOnClickListener(v -> { dialog.dismiss(); deletePlaylist(item); });
        actions.addView(cancel, new LinearLayout.LayoutParams(dp(160), dp(54)));
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(dp(180), dp(54));
        deleteParams.setMargins(dp(10), 0, 0, 0);
        actions.addView(delete, deleteParams);
        panel.addView(actions, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62)));
        dialog.setContentView(panel, new ViewGroup.LayoutParams(dp(570), ViewGroup.LayoutParams.WRAP_CONTENT));
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setDimAmount(.72f);
            dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        dialog.setOnShowListener(ignored -> cancel.requestFocus());
        dialog.show();
    }

    private void deletePlaylist(PlaylistStore.Playlist item) {
        showSplash("جاري حذف القائمة", item.displayName());
        worker.execute(() -> {
            try {
                if (item.remote) api.delete("/api/device/playlists/" + BlofyApi.encode(item.id));
                if (item.active || item.currentSessionOnly) {
                    try { api.delete("/api/session"); } catch (Exception ignored) {}
                    api.clearSession();
                    database.beginFreshImport();
                    session = new BlofyModels.Session(null);
                }
                playlistStore.delete(item.id);
                refreshRemotePlaylists();
                main.post(() -> showPlaylistHub("تم حذف القائمة."));
            } catch (Exception failure) {
                main.post(() -> showPlaylistHub(message(failure)));
            }
        });
    }

    private void showLogin(String error) { showPlaylistEditor(null, error); }

    private void openPlaylistEditor(PlaylistStore.Playlist item) {
        if (item == null || !item.remote || item.currentSessionOnly) {
            showPlaylistEditor(item, "");
            return;
        }
        showSplash("جاري فتح القائمة", "قراءة البيانات الآمنة من موقع BLOFY");
        worker.execute(() -> {
            PlaylistStore.Playlist detail = item.copy();
            String warning = "";
            try {
                JSONObject response = api.get("/api/device/playlists/" + BlofyApi.encode(item.id));
                JSONObject data = response.optJSONObject("playlist");
                if (data == null) data = response;
                detail.serverUrl = data.optString("serverUrl", detail.serverUrl).trim();
                detail.username = data.optString("username", detail.username).trim();
                detail.url = data.optString("url", detail.url).trim();
            } catch (Exception failure) {
                warning = "تعذر قراءة التفاصيل الحالية؛ يمكنك تعديل الاسم أو إدخال بيانات جديدة.";
            }
            String finalWarning = warning;
            main.post(() -> showPlaylistEditor(detail, finalWarning));
        });
    }

    private void showPlaylistEditor(PlaylistStore.Playlist editing, String error) {
        screen = "playlist_editor";
        root.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(BlofyUi.isTv(this) ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER);
        page.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        page.setPadding(dp(28), dp(22), dp(28), dp(22));

        LinearLayout device = devicePanel(true);
        LinearLayout.LayoutParams deviceParams = new LinearLayout.LayoutParams(
                BlofyUi.isTv(this) ? dp(315) : ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        deviceParams.setMargins(dp(8), dp(8), dp(12), dp(8));
        page.addView(device, deviceParams);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        form.setPadding(dp(28), dp(24), dp(28), dp(24));
        form.setBackground(BlofyUi.gradientPanel(this, Color.argb(244, 18, 14, 32),
                Color.argb(244, 9, 8, 18), 22, BlofyUi.STROKE));
        form.addView(BlofyUi.brand(this, "P L A Y E R  •  N A T I V E"),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));
        String heading = editing == null ? "إضافة قائمة تشغيل" : "تعديل قائمة التشغيل";
        TextView title = BlofyUi.title(this, heading, 27);
        title.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        form.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        TextView intro = BlofyUi.text(this,
                editing != null && editing.remote
                        ? "عدّل الاسم، وأدخل فقط بيانات الاتصال التي تريد تغييرها. ترك كلمة المرور فارغة يبقيها كما هي."
                        : "احفظ البيانات أولًا، ثم ارجع واختر القائمة واضغط اتصال.",
                13, BlofyUi.MUTED);
        form.addView(intro, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.RIGHT);
        tabs.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        Button xtreamTab = BlofyUi.button(this, "Xtream Codes", true);
        Button m3uTab = BlofyUi.button(this, "M3U / M3U8", false);
        tabs.addView(xtreamTab, new LinearLayout.LayoutParams(dp(180), dp(50)));
        LinearLayout.LayoutParams secondTab = new LinearLayout.LayoutParams(dp(180), dp(50));
        secondTab.setMargins(dp(10), 0, 0, 0);
        tabs.addView(m3uTab, secondTab);
        LinearLayout.LayoutParams tabsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tabsParams.topMargin = dp(12);
        form.addView(tabs, tabsParams);

        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        EditText name = addField(fields, "اسم القائمة", false);
        EditText server = addField(fields, editing != null && editing.remote
                ? "رابط خادم جديد (اختياري)" : "رابط الخادم", false);
        EditText username = addField(fields, editing != null && editing.remote
                ? "اسم مستخدم جديد (اختياري)" : "اسم المستخدم", false);
        EditText password = addField(fields, editing != null && editing.remote
                ? "كلمة مرور جديدة (اختياري)" : "كلمة المرور", false);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText playlist = addField(fields, editing != null && editing.remote
                ? "رابط M3U جديد (اختياري)" : "رابط M3U أو M3U8", false);
        form.addView(fields);

        xtreamTab.setId(View.generateViewId()); m3uTab.setId(View.generateViewId());
        name.setId(View.generateViewId()); server.setId(View.generateViewId());
        username.setId(View.generateViewId()); password.setId(View.generateViewId());
        playlist.setId(View.generateViewId());
        xtreamTab.setNextFocusLeftId(m3uTab.getId());
        m3uTab.setNextFocusRightId(xtreamTab.getId());
        server.setNextFocusUpId(name.getId()); server.setNextFocusDownId(username.getId());
        username.setNextFocusUpId(server.getId()); username.setNextFocusDownId(password.getId());
        password.setNextFocusUpId(username.getId());
        playlist.setNextFocusUpId(name.getId());

        if (editing != null) {
            name.setText(editing.name);
            server.setText(editing.serverUrl);
            username.setText(editing.username);
            if (!editing.remote) password.setText(editing.password);
            playlist.setText(editing.url);
        }

        TextView status = BlofyUi.text(this, error == null ? "" : error, 13,
                error == null || error.isEmpty() ? BlofyUi.MUTED : BlofyUi.ERROR);
        status.setGravity(Gravity.RIGHT);
        form.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        Button save = BlofyUi.button(this, "حفظ القائمة", true);
        Button cancel = BlofyUi.button(this, "إلغاء", false);
        save.setId(View.generateViewId()); cancel.setId(View.generateViewId());
        save.setNextFocusLeftId(cancel.getId());
        cancel.setNextFocusRightId(save.getId());
        password.setNextFocusDownId(save.getId()); playlist.setNextFocusDownId(save.getId());
        footer.addView(save, new LinearLayout.LayoutParams(0, dp(56), 1f));
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(dp(150), dp(56));
        cancelParams.setMargins(dp(10), 0, 0, 0);
        footer.addView(cancel, cancelParams);
        form.addView(footer);
        cancel.setOnClickListener(v -> showPlaylistHub(""));

        final boolean[] xtream = {editing == null || !"m3u".equals(editing.kind)};
        Runnable refreshMode = () -> {
            server.setVisibility(xtream[0] ? View.VISIBLE : View.GONE);
            username.setVisibility(xtream[0] ? View.VISIBLE : View.GONE);
            password.setVisibility(xtream[0] ? View.VISIBLE : View.GONE);
            playlist.setVisibility(xtream[0] ? View.GONE : View.VISIBLE);
            xtreamTab.setText(xtream[0] ? "Xtream Codes  ✓" : "Xtream Codes");
            m3uTab.setText(xtream[0] ? "M3U / M3U8" : "M3U / M3U8  ✓");
            xtreamTab.setNextFocusDownId(name.getId()); m3uTab.setNextFocusDownId(name.getId());
            name.setNextFocusUpId(xtream[0] ? xtreamTab.getId() : m3uTab.getId());
            name.setNextFocusDownId(xtream[0] ? server.getId() : playlist.getId());
            save.setNextFocusUpId(xtream[0] ? password.getId() : playlist.getId());
            cancel.setNextFocusUpId(xtream[0] ? password.getId() : playlist.getId());
        };
        refreshMode.run();
        xtreamTab.setOnClickListener(view -> { xtream[0] = true; refreshMode.run(); server.requestFocus(); });
        m3uTab.setOnClickListener(view -> { xtream[0] = false; refreshMode.run(); playlist.requestFocus(); });

        save.setOnClickListener(view -> {
            boolean remoteEdit = editing != null && editing.remote && !editing.currentSessionOnly;
            String enteredName = value(name);
            if (enteredName.isEmpty()) enteredName = "قائمتي";
            if (xtream[0] && !remoteEdit
                    && (value(server).isEmpty() || value(username).isEmpty() || value(password).isEmpty())) {
                status.setText("أدخل رابط الخادم واسم المستخدم وكلمة المرور.");
                status.setTextColor(BlofyUi.ERROR);
                return;
            }
            if (!xtream[0] && !remoteEdit && value(playlist).isEmpty()) {
                status.setText("أدخل رابط M3U أو M3U8.");
                status.setTextColor(BlofyUi.ERROR);
                return;
            }
            PlaylistStore.Playlist draft = editing == null || editing.currentSessionOnly
                    ? new PlaylistStore.Playlist() : editing.copy();
            draft.name = enteredName;
            draft.kind = xtream[0] ? "xtream" : "m3u";
            if (!value(server).isEmpty()) draft.serverUrl = value(server);
            if (!value(username).isEmpty()) draft.username = value(username);
            if (!value(password).isEmpty()) draft.password = value(password);
            if (!value(playlist).isEmpty()) draft.url = value(playlist);

            JSONObject body = new JSONObject();
            try {
                body.put("name", draft.name).put("kind", draft.kind);
                if (xtream[0]) {
                    if (!value(server).isEmpty()) body.put("serverUrl", value(server));
                    if (!value(username).isEmpty()) body.put("username", value(username));
                    if (!value(password).isEmpty()) body.put("password", value(password));
                } else if (!value(playlist).isEmpty()) body.put("url", value(playlist));
            } catch (Exception ignored) {}
            savePlaylist(editing, draft, body, save, status);
        });

        LinearLayout.LayoutParams formParams = new LinearLayout.LayoutParams(
                BlofyUi.isTv(this) ? 0 : ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, BlofyUi.isTv(this) ? 1f : 0f);
        formParams.setMargins(dp(8), dp(8), dp(8), dp(8));
        page.addView(form, formParams);
        scroll.addView(page);
        root.addView(scroll, match());
        main.postDelayed(() -> name.requestFocus(), 100);
    }

    private void savePlaylist(PlaylistStore.Playlist editing, PlaylistStore.Playlist draft,
                              JSONObject body, Button save, TextView status) {
        if (license == null || !license.usable()) {
            status.setText("فعّل الجهاز أولًا من موقع BLOFY.");
            status.setTextColor(BlofyUi.ERROR);
            return;
        }
        save.setEnabled(false);
        save.setText("جاري الحفظ…");
        status.setText("يتم حفظ القائمة، ولن يتم الاتصال بها الآن.");
        status.setTextColor(BlofyUi.MUTED);
        worker.execute(() -> {
            try {
                boolean editingRemote = editing != null && editing.remote && !editing.currentSessionOnly;
                boolean editingLocal = editing != null && !editing.remote && !editing.currentSessionOnly;
                if (editingLocal) {
                    playlistStore.saveLocal(draft);
                } else {
                    try {
                        if (editingRemote) {
                            api.patch("/api/device/playlists/" + BlofyApi.encode(editing.id), body);
                        } else {
                            api.post("/api/device/playlists", body);
                        }
                        refreshRemotePlaylists();
                    } catch (BlofyApi.ApiException failure) {
                        if (failure.status != 404 && failure.status != 405) throw failure;
                        if (editingRemote) throw new Exception("تحديث الموقع غير متاح على إصدار الخادم الحالي.");
                        playlistStore.saveLocal(draft);
                    }
                }
                main.post(() -> showPlaylistHub("تم حفظ القائمة. اخترها واضغط اتصال."));
            } catch (Exception failure) {
                main.post(() -> {
                    save.setEnabled(true);
                    save.setText("حفظ القائمة");
                    status.setText(message(failure));
                    status.setTextColor(BlofyUi.ERROR);
                });
            }
        });
    }

    private LinearLayout devicePanel(boolean showActivationActions) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(20), dp(18), dp(20), dp(18));
        panel.setBackground(BlofyUi.gradientPanel(this, Color.argb(238, 25, 18, 46),
                Color.argb(240, 10, 10, 21), 22, Color.rgb(75, 48, 116)));
        ImageView smallLogo = new ImageView(this);
        smallLogo.setImageResource(R.drawable.blofy_logo);
        smallLogo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        panel.addView(smallLogo, new LinearLayout.LayoutParams(dp(88), dp(88)));
        TextView heading = BlofyUi.title(this, "جهاز BLOFY", 17);
        heading.setGravity(Gravity.CENTER);
        panel.addView(heading);
        TextView id = BlofyUi.title(this, DeviceIdentity.displayId(this), 20);
        id.setTextDirection(View.TEXT_DIRECTION_LTR);
        id.setGravity(Gravity.CENTER);
        id.setTextIsSelectable(true);
        panel.addView(id);
        TextView pairing = BlofyUi.text(this, "رمز الدخول   " + DeviceIdentity.activationCode(this), 14,
                BlofyUi.PURPLE_LIGHT);
        pairing.setTextDirection(View.TEXT_DIRECTION_LTR);
        pairing.setGravity(Gravity.CENTER);
        pairing.setTextIsSelectable(true);
        panel.addView(pairing, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)));
        String licenseText = license == null ? "جاري التحقق" : license.status + " • " + license.remainingDays + " أيام";
        TextView plan = BlofyUi.text(this, licenseText, 14, license != null && license.usable() ? BlofyUi.SUCCESS : BlofyUi.ERROR);
        plan.setGravity(Gravity.CENTER);
        panel.addView(plan);

        ImageView qr = new ImageView(this);
        qr.setBackgroundColor(Color.WHITE);
        qr.setPadding(dp(7), dp(7), dp(7), dp(7));
        qr.setImageBitmap(qr(api.activationUrl(license), 240));
        int qrSize = showActivationActions ? 158 : 142;
        LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(dp(qrSize), dp(qrSize));
        qrParams.topMargin = dp(12);
        panel.addView(qr, qrParams);
        TextView qrText = BlofyUi.text(this, "امسح الباركود لفتح لوحة الجهاز وإدارة القوائم", 11, BlofyUi.MUTED);
        qrText.setGravity(Gravity.CENTER);
        panel.addView(qrText);

        if (!showActivationActions) return panel;
        Button activate = BlofyUi.button(this, "↻  تحديث من الموقع", true);
        LinearLayout.LayoutParams activateParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        activateParams.topMargin = dp(12);
        panel.addView(activate, activateParams);
        activate.setOnClickListener(view -> {
            activate.setEnabled(false);
            worker.execute(() -> {
                try {
                    registerDevice();
                    license = new BlofyModels.License(api.get("/api/license?device_id=" + BlofyApi.encode(api.deviceId())));
                    JSONObject bootstrap = tryRemoteSetup();
                    if (bootstrap != null) {
                        DeviceIdentity.updatePublicIdentity(this, bootstrap);
                        playlistStore.applyRemote(bootstrap);
                    }
                    refreshRemotePlaylists();
                    session = new BlofyModels.Session(api.get("/api/session"));
                    main.post(() -> {
                        Toast.makeText(this, license.usable() ? "تم تحديث التفعيل" : "الجهاز غير مفعّل", Toast.LENGTH_LONG).show();
                        showPlaylistHub("");
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
        Button logout = BlofyUi.button(this, "العودة لقوائم التشغيل", false);
        logout.setOnClickListener(view -> showPlaylistHub(""));
        LinearLayout.LayoutParams logoutParams = new LinearLayout.LayoutParams(dp(280), dp(58));
        logoutParams.topMargin = dp(10);
        panel.addView(logout, logoutParams);
        root.addView(panel, match());
        retry.requestFocus();
    }

    private void showHome() {
        screen = "home";
        Intent intent = new Intent(this, SevenMaxActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
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
        player.putExtra(PlayerActivity.EXTRA_CATEGORY_ID, item.categoryId);
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
        showSplash("جاري فصل القائمة", "ستبقى القائمة محفوظة في جهازك");
        worker.execute(() -> {
            try { api.delete("/api/session"); } catch (Exception ignored) {}
            api.clearSession();
            database.beginFreshImport();
            playlistStore.clearActive();
            session = new BlofyModels.Session(null);
            main.post(() -> showPlaylistHub(""));
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
        BlofyUi.attachScaleFocus(view, 1.008f);
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
        if ("playlist_editor".equals(screen) || "login".equals(screen)
                || "import".equals(screen) || "settings".equals(screen)) {
            showPlaylistHub("");
        } else if ("playlists".equals(screen) || "splash".equals(screen)) {
            finish();
        } else {
            showPlaylistHub("");
        }
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
