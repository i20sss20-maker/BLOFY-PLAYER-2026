#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player"
PACKAGE = JAVA / "PackageImporter.java"
MAIN = JAVA / "MainActivity.java"
POLICY = JAVA / "PlaybackPolicy.java"
SETTINGS = JAVA / "SettingsActivity.java"
PREFLIGHT = JAVA / "ServerCompatibilityPreflight.java"
API = JAVA / "BlofyApi.java"


def replace_once(text, old, new, label):
    if old not in text:
        raise SystemExit(f"v340 final core patch mismatch: {label}")
    return text.replace(old, new, 1)

PREFLIGHT.write_text(r'''package tv.blofy.player;

import android.content.Context;
import android.os.SystemClock;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class ServerCompatibilityPreflight {
    private static final String PREFS = "blofy_compatibility_preflight";
    private static final int SAMPLE_COUNT = 2;

    static final class Result {
        final int liveScore, moviesScore, seriesScore, tested, playable;
        final String summary, report;
        Result(int liveScore, int moviesScore, int seriesScore, int tested, int playable,
               String summary, String report) {
            this.liveScore = liveScore; this.moviesScore = moviesScore; this.seriesScore = seriesScore;
            this.tested = tested; this.playable = playable; this.summary = summary; this.report = report;
        }
        boolean completeFailure() { return tested > 0 && playable == 0; }
    }

    private ServerCompatibilityPreflight() {}

    static Result run(Context context, BlofyApi api, CatalogDatabase database, String playlistId) {
        StringBuilder report = new StringBuilder(2048);
        report.append("BLOFY Server Compatibility Preflight\nplaylist=")
                .append(safe(playlistId)).append('\n');
        int[] live = testFamily(context, api, database, "live", report);
        int[] movies = testFamily(context, api, database, "movies", report);
        int[] series = testFamily(context, api, database, "series", report);
        int tested = live[1] + movies[1] + series[1];
        int playable = live[0] + movies[0] + series[0];
        int liveScore = score(live[0], live[1]);
        int moviesScore = score(movies[0], movies[1]);
        int seriesScore = score(series[0], series[1]);
        String summary = "Live " + liveScore + "% • Movies " + moviesScore + "% • Series " + seriesScore + "%";
        report.append("summary=").append(summary).append('\n');
        String key = key(playlistId);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(key + ":summary", summary)
                .putString(key + ":report", report.toString())
                .putInt(key + ":live", liveScore).putInt(key + ":movies", moviesScore)
                .putInt(key + ":series", seriesScore).putLong(key + ":at", System.currentTimeMillis()).apply();
        PlaybackDiagnostics.marker(context, "compatibility-preflight", "server", safe(playlistId), "",
                "preflight", summary + " tested=" + tested + " playable=" + playable);
        return new Result(liveScore, moviesScore, seriesScore, tested, playable, summary, report.toString());
    }

    static String savedSummary(Context context, String playlistId) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(key(playlistId) + ":summary", "لم يتم الفحص بعد");
    }

    static String savedReport(Context context, String playlistId) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(key(playlistId) + ":report", "لا يوجد تقرير توافق محفوظ بعد.");
    }

    private static int[] testFamily(Context context, BlofyApi api, CatalogDatabase database,
                                    String type, StringBuilder report) {
        List<BlofyModels.Media> items = database.media(type, "", "", false, false, SAMPLE_COUNT, 0);
        if (items == null || items.isEmpty()) { report.append(type).append(": no-samples\n"); return new int[]{0,0}; }
        int playable = 0, tested = 0;
        for (BlofyModels.Media item : items) { tested++; if (testItem(context, api, type, item, report)) playable++; }
        return new int[]{playable, tested};
    }

    private static boolean testItem(Context context, BlofyApi api, String type,
                                    BlofyModels.Media item, StringBuilder report) {
        String apiType = "series".equals(type) ? "episode" : type;
        String baseExt = PlaybackPolicy.normalizeExtension(item.extension, "live".equals(type) ? "ts" : "mp4");
        Set<String> extensions = new LinkedHashSet<>();
        extensions.add(baseExt);
        if ("live".equals(type)) extensions.add(PlaybackPolicy.alternateLiveExtension(baseExt));
        List<String> variants = new ArrayList<>();
        variants.add("canonical"); variants.add("direct"); variants.add("no-extension");
        long started = SystemClock.elapsedRealtime();
        String last = "";
        for (String extension : extensions) {
            for (String variant : variants) {
                try {
                    JSONObject data = api.getPlayback("/api/native-link/" + BlofyApi.encode(apiType) + "/"
                            + BlofyApi.encode(item.id) + "?ext=" + BlofyApi.encode(extension)
                            + "&variant=" + BlofyApi.encode(variant), new BlofyApi.Cancellation());
                    String url = data.optString("url", "");
                    String resolvedExt = PlaybackPolicy.normalizeExtension(data.optString("extension", extension), extension);
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        long elapsed = Math.max(0L, SystemClock.elapsedRealtime() - started);
                        report.append(type).append('/').append(item.id).append(" OK route=").append(variant)
                                .append(" ext=").append(resolvedExt).append(" resolve_ms=").append(elapsed).append('\n');
                        PlaybackDiagnostics.marker(context, "preflight-route-ok", type, item.id, resolvedExt,
                                variant, "resolve_ms=" + elapsed);
                        return true;
                    }
                    last = "invalid-url";
                } catch (Exception error) {
                    last = error.getClass().getSimpleName() + ":" + safe(error.getMessage());
                }
            }
        }
        long elapsed = Math.max(0L, SystemClock.elapsedRealtime() - started);
        report.append(type).append('/').append(item.id).append(" FAIL ").append(last)
                .append(" elapsed_ms=").append(elapsed).append('\n');
        PlaybackDiagnostics.marker(context, "preflight-route-fail", type, item.id, baseExt,
                "all-routes", last + " elapsed_ms=" + elapsed);
        return false;
    }

    private static int score(int ok, int tested) { return tested <= 0 ? 0 : Math.round((ok * 100f) / tested); }
    private static String key(String playlistId) {
        return Integer.toHexString(safe(playlistId).trim().toLowerCase(Locale.US).hashCode());
    }
    private static String safe(String value) { return value == null ? "" : value; }
}
''', encoding="utf-8")

package = PACKAGE.read_text(encoding="utf-8")
pattern = re.compile(
    r'            String profile = profile\(\);\n'
    r'            emit\(95, "اعتماد بيانات الباقة", "تثبيت البيانات المحفوظة على الجهاز"\);\n'
    r'            database\.commitStagedImport\(sourceIdentity, session\.serverName, session\.kind, profile\);\n'
    r'.*?'
    r'            return new Result\(live, movies, series, profile\);\n',
    re.DOTALL)
replacement = '''            String profile = profile();\n            emit(95, "اعتماد بيانات الباقة", "تثبيت جميع البيانات المحفوظة على الجهاز");\n            database.commitStagedImport(sourceIdentity, session.serverName, session.kind, profile);\n            int live = database.count("live");\n            int movies = database.count("movies");\n            int series = database.count("series");\n            CatalogUiCache.warm(database);\n\n            emit(96, "فحص توافق التشغيل", "اختبار عينات Live و Movies و Series على أكثر من مسار");\n            ServerCompatibilityPreflight.Result preflight = ServerCompatibilityPreflight.run(\n                    api.context(), api, database, playlistId);\n            emit(99, "نتيجة التوافق", preflight.summary);\n            if (preflight.completeFailure() && (live + movies + series) > 0) {\n                throw new Exception("تم حفظ الباقة كاملة، لكن فشل اختبار التشغيل على جميع العينات والمسارات. "\n                        + preflight.summary + " • افتح تقرير التشخيص لمعرفة السبب.");\n            }\n            emit(100, "جاهز", "Live " + live + " • Movies " + movies + " • Series " + series\n                    + " • " + preflight.summary);\n            return new Result(live, movies, series, profile);\n'''
package, hits = pattern.subn(replacement, package, count=1)
if hits != 1:
    raise SystemExit("v340 final core patch mismatch: post-import preflight")
PACKAGE.write_text(package, encoding="utf-8")

api = API.read_text(encoding="utf-8")
api = replace_once(api, '    String baseUrl() { return baseUrl; }\n',
                   '    Context context() { return context; }\n    String baseUrl() { return baseUrl; }\n', 'api context accessor')
API.write_text(api, encoding="utf-8")

policy = POLICY.read_text(encoding="utf-8")
for old, new in [
    ('static final int INITIAL_STARTUP_TIMEOUT_MS = 7_000;', 'static final int INITIAL_STARTUP_TIMEOUT_MS = 5_000;'),
    ('static final int RETRY_STARTUP_TIMEOUT_MS = 5_000;', 'static final int RETRY_STARTUP_TIMEOUT_MS = 3_500;'),
    ('static final int VOD_STARTUP_TIMEOUT_MS = 8_000;', 'static final int VOD_STARTUP_TIMEOUT_MS = 6_500;'),
    ('static final int UHD_VOD_STARTUP_TIMEOUT_MS = 11_000;', 'static final int UHD_VOD_STARTUP_TIMEOUT_MS = 9_000;'),
    ('static final int VLC_STARTUP_TIMEOUT_MS = 7_000;', 'static final int VLC_STARTUP_TIMEOUT_MS = 5_500;'),
    ('static final int UHD_VLC_STARTUP_TIMEOUT_MS = 10_000;', 'static final int UHD_VLC_STARTUP_TIMEOUT_MS = 8_000;'),
    ('static final int PREVIEW_STARTUP_TIMEOUT_MS = 5_000;', 'static final int PREVIEW_STARTUP_TIMEOUT_MS = 3_500;'),
]:
    if old not in policy: raise SystemExit("v340 final core patch mismatch: playback timeout " + old)
    policy = policy.replace(old, new, 1)
POLICY.write_text(policy, encoding="utf-8")

main = MAIN.read_text(encoding="utf-8")
main = replace_once(main,
'''        Button logout = BlofyUi.button(this, "العودة لقوائم التشغيل", false);\n        logout.setOnClickListener(view -> showPlaylistHub(""));\n        LinearLayout.LayoutParams logoutParams = new LinearLayout.LayoutParams(dp(280), dp(58));\n        logoutParams.topMargin = dp(10);\n        panel.addView(logout, logoutParams);\n        root.addView(panel, match());\n        retry.requestFocus();\n''',
'''        Button diagnostics = BlofyUi.button(this, "عرض تقرير التشخيص", false);\n        diagnostics.setOnClickListener(view -> showImportDiagnostics());\n        LinearLayout.LayoutParams diagnosticsParams = new LinearLayout.LayoutParams(dp(280), dp(58));\n        diagnosticsParams.topMargin = dp(10);\n        panel.addView(diagnostics, diagnosticsParams);\n        Button logout = BlofyUi.button(this, "العودة لقوائم التشغيل", false);\n        logout.setOnClickListener(view -> showPlaylistHub(""));\n        LinearLayout.LayoutParams logoutParams = new LinearLayout.LayoutParams(dp(280), dp(58));\n        logoutParams.topMargin = dp(10);\n        panel.addView(logout, logoutParams);\n        root.addView(panel, match());\n        retry.requestFocus();\n''', 'import diagnostics button')
helper = '''    private void showImportDiagnostics() {\n        String report = PlaybackDiagnostics.readableReport(this);\n        android.widget.ScrollView scroll = new android.widget.ScrollView(this);\n        TextView body = BlofyUi.text(this, report, 12, Color.WHITE);\n        body.setTextDirection(View.TEXT_DIRECTION_LTR);\n        body.setGravity(Gravity.LEFT | Gravity.TOP);\n        body.setTextIsSelectable(true);\n        body.setPadding(dp(18), dp(16), dp(18), dp(16));\n        scroll.addView(body, new android.widget.ScrollView.LayoutParams(\n                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));\n        new android.app.AlertDialog.Builder(this).setTitle("تقرير تشخيص BLOFY").setView(scroll)\n                .setPositiveButton("نسخ", (dialog, which) -> {\n                    android.content.ClipboardManager clipboard =\n                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);\n                    if (clipboard != null) clipboard.setPrimaryClip(\n                            android.content.ClipData.newPlainText("BLOFY diagnostics", report));\n                }).setNegativeButton("إغلاق", null).show();\n    }\n\n'''
main = replace_once(main, '    private void showHome() {\n', helper + '    private void showHome() {\n', 'import diagnostics helper')
MAIN.write_text(main, encoding="utf-8")

settings = SETTINGS.read_text(encoding="utf-8")
anchor = '''        addGridSetting(grid, gridAction("▤  تقرير التشخيص", "عرض كامل • تصوير أو نسخ",\n                this::showDiagnosticsReport));\n'''
settings = replace_once(settings, anchor, anchor + '''        addGridSetting(grid, gridAction("◎  توافق السيرفر",\n                ServerCompatibilityPreflight.savedSummary(this, new PlaylistStore(this).activeId()), () -> {\n            String report = ServerCompatibilityPreflight.savedReport(this, new PlaylistStore(this).activeId());\n            android.content.ClipboardManager clipboard =\n                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);\n            if (clipboard != null) clipboard.setPrimaryClip(\n                    android.content.ClipData.newPlainText("BLOFY compatibility", report));\n            ToastBridge.show(this, "تم نسخ تقرير توافق السيرفر");\n        }));\n''', 'compatibility settings action')
SETTINGS.write_text(settings, encoding="utf-8")

for path, tokens in [
    (PREFLIGHT, ["ServerCompatibilityPreflight", "preflight-route-ok", "no-extension", "savedSummary"]),
    (PACKAGE, ["فحص توافق التشغيل", "ServerCompatibilityPreflight.Result", "preflight.completeFailure"]),
    (MAIN, ["showImportDiagnostics", "عرض تقرير التشخيص"]),
    (SETTINGS, ["◎  توافق السيرفر", "savedSummary"]),
    (POLICY, ["INITIAL_STARTUP_TIMEOUT_MS = 5_000", "RETRY_STARTUP_TIMEOUT_MS = 3_500"]),
]:
    value = path.read_text(encoding="utf-8")
    for token in tokens:
        if token not in value: raise SystemExit(f"v340 final core invariant missing in {path.name}: {token}")

print("v340 final core applied: full-sync preflight + compatibility baseline + bounded startup + diagnostics-at-failure")
