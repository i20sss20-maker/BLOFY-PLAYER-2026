package tv.blofy.commercial.ui.sync;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import tv.blofy.commercial.core.ApiClient;
import tv.blofy.commercial.data.CatalogStore;
import tv.blofy.commercial.data.MediaRecord;
import tv.blofy.commercial.databinding.ActivitySyncBinding;
import tv.blofy.commercial.ui.activation.ActivationActivity;
import tv.blofy.commercial.ui.home.HomeActivity;

public final class SyncActivity extends AppCompatActivity {
    private ActivitySyncBinding binding;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String, Integer> formats = new LinkedHashMap<>();
    private CatalogStore store;
    private ApiClient api;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySyncBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        api = new ApiClient(this);
        store = new CatalogStore(this);
        worker.execute(this::sync);
    }

    private void sync() {
        try {
            emit(4, "الاتصال بخادم BLOFY", "فحص الاستضافة وزمن الاستجابة");
            if (!api.get("/api/health").optBoolean("ok")) throw new Exception("خدمة BLOFY غير جاهزة.");
            emit(10, "التحقق من الجلسة", "قراءة نوع الباقة والحساب");
            JSONObject session = api.get("/api/session").optJSONObject("session");
            if (session == null) throw new Exception("لم يتم العثور على جلسة الباقة.");
            store.clearCatalog();
            store.putMeta("kind", session.optString("kind"));
            store.putMeta("server", session.optString("serverName"));
            importType("live", "القنوات المباشرة", 12, 42);
            importType("movies", "الأفلام", 42, 70);
            importType("series", "المسلسلات", 70, 94);
            String profile = profile();
            store.putMeta("profile", profile);
            store.putMeta("last_sync", String.valueOf(System.currentTimeMillis()));
            getSharedPreferences("blofy_commercial_state", MODE_PRIVATE).edit().putBoolean("catalog_ready", true).apply();
            emit(100, "اكتملت قراءة الباقة", profile + " • جاهز للتشغيل");
            Thread.sleep(650);
            runOnUiThread(() -> { startActivity(new Intent(this, HomeActivity.class)); finish(); });
        } catch (Exception error) {
            emit(0, "تعذر قراءة الباقة", error.getMessage());
            try { Thread.sleep(1500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            runOnUiThread(() -> { startActivity(new Intent(this, ActivationActivity.class).putExtra("boot_error", error.getMessage())); finish(); });
        }
    }

    private void importType(String type, String label, int start, int end) throws Exception {
        emit(start, "قراءة " + label, "جلب التصنيفات");
        JSONArray categories = api.get("/api/categories?type=" + ApiClient.encode(type)).optJSONArray("categories");
        if (categories != null) for (int i = 0; i < categories.length(); i++) {
            JSONObject row = categories.optJSONObject(i);
            if (row != null) store.saveCategory(type, row.optString("id"), row.optString("name"));
        }
        JSONObject first = api.get("/api/catalog?type=" + ApiClient.encode(type) + "&page=1&page_size=500");
        int total = Math.max(0, first.optInt("total"));
        int pageSize = Math.max(1, first.optInt("pageSize", 500));
        int pages = Math.max(1, (int) Math.ceil(total / (double) pageSize));
        save(first.optJSONArray("items"), type);
        for (int page = 2; page <= pages; page++) {
            int percent = start + Math.round((end - start) * ((page - 1f) / pages));
            emit(percent, "قراءة " + label, "تمت قراءة " + Math.min(total, (page - 1) * pageSize) + " من " + total);
            save(api.get("/api/catalog?type=" + ApiClient.encode(type) + "&page=" + page + "&page_size=500").optJSONArray("items"), type);
        }
        emit(end, "اكتملت " + label, total + " عنصر محفوظ على الجهاز");
    }

    private void save(JSONArray values, String type) {
        List<MediaRecord> rows = new ArrayList<>();
        if (values != null) for (int i = 0; i < values.length(); i++) {
            JSONObject row = values.optJSONObject(i);
            if (row == null) continue;
            MediaRecord item = MediaRecord.from(row, type); rows.add(item);
            String ext = item.extension.isEmpty() ? "unknown" : item.extension.toLowerCase(Locale.US);
            formats.put(ext, formats.containsKey(ext) ? formats.get(ext) + 1 : 1);
        }
        store.saveMedia(rows);
    }

    private String profile() {
        int hls = formats.containsKey("m3u8") ? formats.get("m3u8") : 0;
        int ts = formats.containsKey("ts") ? formats.get("ts") : 0;
        int files = (formats.containsKey("mp4") ? formats.get("mp4") : 0) + (formats.containsKey("mkv") ? formats.get("mkv") : 0);
        if (ts >= hls && ts >= files) return "Media3 • بث TS مباشر منخفض التأخير";
        if (hls >= files) return "Media3 • بث HLS متكيف";
        return "Media3 • فيديو مباشر مع استكمال المشاهدة";
    }

    private void emit(int percent, String title, String detail) {
        runOnUiThread(() -> {
            binding.progress.setProgressCompat(percent, true);
            binding.percent.setText(percent + "%");
            binding.title.setText(title);
            binding.detail.setText(detail == null ? "" : detail);
            binding.analysis.setText("القنوات: " + store.count("live") + "   •   الأفلام: " + store.count("movies") + "   •   المسلسلات: " + store.count("series") + "\n" + profile());
        });
    }

    @Override protected void onDestroy() { worker.shutdownNow(); if (store != null) store.close(); super.onDestroy(); }
}
