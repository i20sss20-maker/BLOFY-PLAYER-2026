from pathlib import Path
import re

# One-shot repair for the large-package sync path.
ROOT = Path(__file__).resolve().parents[1]
server_path = ROOT / "BLOFY-WEB-2026/web-test/server.mjs"
importer_path = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player/PackageImporter.java"
main_path = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player/MainActivity.java"
gradle_path = ROOT / "BLOFY-ANDROID-2026/app/build.gradle"

server = server_path.read_text()
server = server.replace('const APP_VERSION = "2026.08.23.6";', 'const APP_VERSION = "2026.08.23.8";')
server = server.replace('const pageSize = boundedInteger(query.get("page_size"), 60, 30, 500);', 'const pageSize = boundedInteger(query.get("page_size"), 60, 30, 2000);')
old_limiter = '''  const nativeRequest = url.pathname.startsWith("/api/native-");
  if (limited(req, nativeRequest ? 1200 : 120, 60_000, nativeRequest ? "native" : "api")) {
    return json(res, 429, { error: "طلبات كثيرة، حاول بعد دقيقة." }, securityHeaders());
  }'''
new_limiter = '''  const nativeRequest = url.pathname.startsWith("/api/native-");
  const syncRequest = req.method === "GET" && (url.pathname === "/api/catalog" || url.pathname === "/api/categories");
  const requestLimit = nativeRequest ? 1200 : syncRequest ? 1800 : 120;
  const rateNamespace = nativeRequest ? "native" : syncRequest ? "sync" : "api";
  if (limited(req, requestLimit, 60_000, rateNamespace)) {
    return json(res, 429, { error: "طلبات كثيرة، حاول بعد دقيقة." }, securityHeaders());
  }'''
if old_limiter not in server:
    raise SystemExit("server limiter block not found")
server = server.replace(old_limiter, new_limiter)
server_path.write_text(server)

importer = importer_path.read_text()
new_run = r'''    Result run() throws Exception {
        emit(3, "الاتصال بخادم BLOFY", "فحص الاستضافة والاستجابة");
        JSONObject health = getWithRetry("/api/health");
        if (!health.optBoolean("ok", false)) throw new Exception("خدمة BLOFY غير جاهزة الآن.");

        emit(8, "التحقق من الجلسة", "قراءة بيانات الباقة وحالة الحساب");
        BlofyModels.Session session = new BlofyModels.Session(getWithRetry("/api/session"));
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
            emit(100, "اكتملت قراءة الباقة", "جاهز للتشغيل المباشر عبر Media3");
            return new Result(database.count("live"), database.count("movies"), database.count("series"), profile);
        } catch (Exception error) {
            database.beginFreshImport();
            database.putMetadata("sync_state", "failed");
            throw error;
        }
    }

'''
importer, count = re.subn(r'    Result run\(\) throws Exception \{.*?\n    \}\n\n(?=    private void importType)', new_run, importer, count=1, flags=re.S)
if count != 1:
    raise SystemExit("PackageImporter.run block not found")
importer = importer.replace('api.get("/api/categories?type=" + BlofyApi.encode(type))', 'getWithRetry("/api/categories?type=" + BlofyApi.encode(type))')
importer = importer.replace('api.get("/api/catalog?type=" + BlofyApi.encode(type) + "&page=1&page_size=500")', 'getWithRetry("/api/catalog?type=" + BlofyApi.encode(type) + "&page=1&page_size=2000")')
importer = importer.replace('api.get("/api/catalog?type=" + BlofyApi.encode(type) + "&page=" + page + "&page_size=500")', 'getWithRetry("/api/catalog?type=" + BlofyApi.encode(type) + "&page=" + page + "&page_size=2000")')
retry_method = r'''    private JSONObject getWithRetry(String path) throws Exception {
        final long[] delays = {1_000L, 3_000L, 8_000L, 15_000L};
        for (int attempt = 0; ; attempt++) {
            try {
                return api.get(path);
            } catch (BlofyApi.ApiException error) {
                boolean retryable = error.status == 429 || error.status == 502 || error.status == 503 || error.status == 504;
                if (!retryable || attempt >= delays.length) throw error;
                Thread.sleep(delays[attempt]);
            }
        }
    }

'''
marker = '    private void save(List<BlofyModels.Media> items) {'
if marker not in importer:
    raise SystemExit("PackageImporter save marker not found")
importer = importer.replace(marker, retry_method + marker)
importer_path.write_text(importer)

main = main_path.read_text()
old_boot = 'else if (database.count("live") + database.count("movies") + database.count("series") > 0) showHome();'
new_boot = 'else if ("complete".equals(database.metadata("sync_state", "")) && database.count("live") + database.count("movies") + database.count("series") > 0) showHome();'
if old_boot not in main:
    raise SystemExit("MainActivity boot condition not found")
main_path.write_text(main.replace(old_boot, new_boot))

gradle = gradle_path.read_text()
gradle = re.sub(r'versionName\s+"2026\.08\.23\.[^"]+"', 'versionName "2026.08.23.8-7max-syncfix"', gradle, count=1)
gradle_path.write_text(gradle)

print("BLOFY sync fix applied")
