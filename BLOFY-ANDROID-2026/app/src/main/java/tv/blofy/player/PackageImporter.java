package tv.blofy.player;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class PackageImporter {
    interface Listener {
        void progress(int percent, String title, String detail);
    }

    static final class Result {
        final int live;
        final int movies;
        final int series;
        final String playbackProfile;

        Result(int live, int movies, int series, String playbackProfile) {
            this.live = live;
            this.movies = movies;
            this.series = series;
            this.playbackProfile = playbackProfile;
        }
    }

    private final BlofyApi api;
    private final CatalogDatabase database;
    private final Listener listener;
    private final Map<String, Integer> extensions = new LinkedHashMap<>();

    PackageImporter(BlofyApi api, CatalogDatabase database, Listener listener) {
        this.api = api;
        this.database = database;
        this.listener = listener;
    }

    Result run() throws Exception {
        emit(3, "الاتصال بخادم BLOFY", "فحص الاستضافة والاستجابة");
        JSONObject health = api.get("/api/health");
        if (!health.optBoolean("ok", false)) throw new Exception("خدمة BLOFY غير جاهزة الآن.");

        emit(8, "التحقق من الجلسة", "قراءة بيانات الباقة وحالة الحساب");
        BlofyModels.Session session = new BlofyModels.Session(api.get("/api/session"));
        if (!session.present) throw new Exception("لم يتم تسجيل بيانات الباقة بعد.");

        emit(12, "تحليل الخادم", "تحديد نوع الباقة وإمكانات التشغيل");
        database.beginFreshImport();
        database.metadata("server_name", session.serverName);
        database.metadata("session_kind", session.kind);

        importType("live", "القنوات المباشرة", 14, 42);
        importType("movies", "الأفلام", 42, 69);
        importType("series", "المسلسلات", 69, 94);

        String profile = profile();
        database.metadata("playback_profile", profile);
        database.metadata("last_sync", String.valueOf(System.currentTimeMillis()));
        emit(98, "تجهيز التشغيل", profile);
        emit(100, "اكتملت قراءة الباقة", "جاهز للتشغيل المباشر عبر Media3");
        return new Result(database.count("live"), database.count("movies"), database.count("series"), profile);
    }

    private void importType(String type, String label, int start, int end) throws Exception {
        emit(start, "قراءة " + label, "جلب التصنيفات من الخادم");
        List<BlofyModels.Category> categories = BlofyModels.Category.list(
                api.get("/api/categories?type=" + BlofyApi.encode(type)), type);
        database.saveCategories(categories);

        JSONObject first = api.get("/api/catalog?type=" + BlofyApi.encode(type) + "&page=1&page_size=500");
        int total = Math.max(0, first.optInt("total", 0));
        int pageSize = Math.max(1, first.optInt("pageSize", 60));
        int pages = Math.max(1, (int) Math.ceil(total / (double) pageSize));
        save(BlofyModels.Media.list(first, type));
        for (int page = 2; page <= pages; page++) {
            int progress = start + Math.round((end - start) * ((page - 1f) / pages));
            int read = Math.min(total, (page - 1) * pageSize);
            emit(progress, "قراءة " + label, "تمت قراءة " + read + " من " + total);
            JSONObject response = api.get("/api/catalog?type=" + BlofyApi.encode(type) + "&page=" + page + "&page_size=500");
            save(BlofyModels.Media.list(response, type));
        }
        emit(end, "اكتملت " + label, total + " عنصر محفوظ محليًا");
    }

    private void save(List<BlofyModels.Media> items) {
        database.saveMedia(items);
        for (BlofyModels.Media item : items) {
            String extension = item.extension == null || item.extension.isEmpty() ? "unknown" : item.extension.toLowerCase(Locale.US);
            extensions.put(extension, extensions.containsKey(extension) ? extensions.get(extension) + 1 : 1);
        }
    }

    private String profile() {
        int hls = extensions.containsKey("m3u8") ? extensions.get("m3u8") : 0;
        int transport = 0;
        for (String extension : new String[]{"ts", "mts", "m2ts"}) transport += extensions.containsKey(extension) ? extensions.get(extension) : 0;
        int files = 0;
        for (String extension : new String[]{"mp4", "mkv", "avi", "mov", "webm"}) files += extensions.containsKey(extension) ? extensions.get(extension) : 0;
        if (transport >= hls && transport >= files) return "Media3 مباشر • بث TS مع كشف تلقائي للترميز";
        if (hls >= files) return "Media3 مباشر • HLS/M3U8 متكيف";
        return "Media3 مباشر • ملفات فيديو مع دعم الاستكمال";
    }

    private void emit(int percent, String title, String detail) {
        listener.progress(Math.max(0, Math.min(100, percent)), title, detail);
    }
}
