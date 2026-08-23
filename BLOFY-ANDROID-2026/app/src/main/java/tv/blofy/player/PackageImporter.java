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

    private static final int REQUESTED_PAGE_SIZE = 2000;
    private static final long LEGACY_MIN_REQUEST_GAP_MS = 650L;

    private final BlofyApi api;
    private final CatalogDatabase database;
    private final Listener listener;
    private final Map<String, Integer> extensions = new LinkedHashMap<>();
    private long lastCatalogRequestAt;

    PackageImporter(BlofyApi api, CatalogDatabase database, Listener listener) {
        this.api = api;
        this.database = database;
        this.listener = listener;
    }

    Result run() throws Exception {
        emit(3, "الاتصال بخادم BLOFY", "فحص الاستضافة والاستجابة");
        JSONObject health = getWithRetry("/api/health", false);
        if (!health.optBoolean("ok", false)) throw new Exception("خدمة BLOFY غير جاهزة الآن.");

        emit(8, "التحقق من الجلسة", "قراءة بيانات الباقة وحالة الحساب");
        BlofyModels.Session session = new BlofyModels.Session(getWithRetry("/api/session", false));
        if (!session.present) throw new Exception("لم يتم تسجيل بيانات الباقة بعد.");

        emit(12, "تحليل الخادم", "تحديد نوع الباقة وإمكانات التشغيل");
        database.beginFreshImport();
        database.putMetadata("sync_state", "in_progress");
        database.putMetadata("server_name", session.serverName);
        database.putMetadata("session_kind", session.kind);

        try {
            importType("live", "القنوات المباشرة", 14, 42);
            importType("movies", "الأفلام", 42, 69);
            importType("series", "المسلسلات", 69, 94);

            String profile = profile();
            database.putMetadata("playback_profile", profile);
            database.putMetadata("last_sync", String.valueOf(System.currentTimeMillis()));
            database.putMetadata("sync_state", "complete");
            emit(98, "تجهيز التشغيل", profile);
            emit(100, "اكتملت قراءة الباقة", "Live " + database.count("live")
                    + " • Movies " + database.count("movies")
                    + " • Series " + database.count("series"));
            return new Result(database.count("live"), database.count("movies"), database.count("series"), profile);
        } catch (Exception error) {
            database.beginFreshImport();
            database.putMetadata("sync_state", "failed");
            throw error;
        }
    }

    private void importType(String type, String label, int start, int end) throws Exception {
        emit(start, "قراءة " + label, "جلب التصنيفات من الخادم");
        List<BlofyModels.Category> categories = BlofyModels.Category.list(
                getWithRetry("/api/categories?type=" + BlofyApi.encode(type), true), type);
        database.saveCategories(categories);

        JSONObject first = getWithRetry("/api/catalog?type=" + BlofyApi.encode(type)
                + "&page=1&page_size=" + REQUESTED_PAGE_SIZE, true);
        int total = Math.max(0, first.optInt("total", 0));
        int pageSize = Math.max(1, first.optInt("pageSize", 60));

        // Some Xtream panels return an empty global VOD/series list but expose
        // valid items when category_id is supplied. Use categories as a safe
        // fallback instead of silently storing 0 Movies/Series.
        if (total == 0 && !"live".equals(type) && !categories.isEmpty()) {
            importByCategories(type, label, categories, start, end);
            return;
        }

        int pages = Math.max(1, (int) Math.ceil(total / (double) pageSize));
        save(BlofyModels.Media.list(first, type));
        boolean legacyRateLimit = pageSize < 1000;
        for (int page = 2; page <= pages; page++) {
            int progress = start + Math.round((end - start) * ((page - 1f) / pages));
            int read = Math.min(total, (page - 1) * pageSize);
            emit(progress, "قراءة " + label, "تمت قراءة " + read + " من " + total);
            if (legacyRateLimit) paceLegacyCatalog();
            JSONObject response = getWithRetry("/api/catalog?type=" + BlofyApi.encode(type)
                    + "&page=" + page + "&page_size=" + REQUESTED_PAGE_SIZE, true);
            save(BlofyModels.Media.list(response, type));
        }
        emit(end, "اكتملت " + label, database.count(type) + " عنصر محفوظ محليًا");
    }

    private void importByCategories(String type, String label,
                                    List<BlofyModels.Category> categories,
                                    int start, int end) throws Exception {
        int categoryCount = Math.max(1, categories.size());
        for (int index = 0; index < categories.size(); index++) {
            BlofyModels.Category category = categories.get(index);
            int progress = start + Math.round((end - start) * (index / (float) categoryCount));
            emit(progress, "قراءة " + label,
                    "تصنيف " + (index + 1) + " من " + categories.size());

            String base = "/api/catalog?type=" + BlofyApi.encode(type)
                    + "&category=" + BlofyApi.encode(category.id)
                    + "&page_size=" + REQUESTED_PAGE_SIZE;
            JSONObject first = getWithRetry(base + "&page=1", true);
            int total = Math.max(0, first.optInt("total", 0));
            int pageSize = Math.max(1, first.optInt("pageSize", 60));
            int pages = Math.max(1, (int) Math.ceil(total / (double) pageSize));
            save(BlofyModels.Media.list(first, type));
            for (int page = 2; page <= pages; page++) {
                JSONObject response = getWithRetry(base + "&page=" + page, true);
                save(BlofyModels.Media.list(response, type));
            }
        }
        emit(end, "اكتملت " + label, database.count(type) + " عنصر محفوظ محليًا");
    }

    private JSONObject getWithRetry(String path, boolean catalogRequest) throws Exception {
        final long[] delays = {1_000L, 3_000L, 8_000L, 15_000L, 32_000L};
        for (int attempt = 0; ; attempt++) {
            try {
                if (catalogRequest) lastCatalogRequestAt = System.currentTimeMillis();
                return api.get(path);
            } catch (BlofyApi.ApiException error) {
                boolean retryable = error.status == 429 || error.status == 502
                        || error.status == 503 || error.status == 504;
                if (!retryable || attempt >= delays.length) throw error;
                emitRetry(path, error.status, attempt + 1);
                Thread.sleep(delays[attempt]);
            }
        }
    }

    private void paceLegacyCatalog() throws InterruptedException {
        long elapsed = System.currentTimeMillis() - lastCatalogRequestAt;
        long wait = LEGACY_MIN_REQUEST_GAP_MS - elapsed;
        if (wait > 0) Thread.sleep(wait);
    }

    private void emitRetry(String path, int status, int attempt) {
        String area = path.contains("catalog") ? "الكتالوج" : "الخادم";
        emit(0, "إعادة الاتصال بـ " + area,
                "استجابة " + status + " • محاولة " + attempt + " تلقائيًا");
    }

    private void save(List<BlofyModels.Media> items) {
        database.saveMedia(items);
        for (BlofyModels.Media item : items) {
            String extension = item.extension == null || item.extension.isEmpty()
                    ? "unknown" : item.extension.toLowerCase(Locale.US);
            extensions.put(extension, extensions.containsKey(extension)
                    ? extensions.get(extension) + 1 : 1);
        }
    }

    private String profile() {
        int hls = extensions.containsKey("m3u8") ? extensions.get("m3u8") : 0;
        int transport = 0;
        for (String extension : new String[]{"ts", "mts", "m2ts"})
            transport += extensions.containsKey(extension) ? extensions.get(extension) : 0;
        int files = 0;
        for (String extension : new String[]{"mp4", "mkv", "avi", "mov", "webm"})
            files += extensions.containsKey(extension) ? extensions.get(extension) : 0;
        if (transport >= hls && transport >= files)
            return "Media3 مباشر • Cronet أولًا • TS سريع";
        if (hls >= files) return "Media3 مباشر • Cronet أولًا • HLS متكيف";
        return "Media3 مباشر • ملفات فيديو مع دعم الاستكمال";
    }

    private void emit(int percent, String title, String detail) {
        listener.progress(Math.max(0, Math.min(100, percent)), title, detail);
    }
}
