#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player"
DIAG = JAVA / "PlaybackDiagnostics.java"
SETTINGS = JAVA / "SettingsActivity.java"


def replace_once(text, old, new, label):
    if old not in text:
        raise SystemExit(f"v340 final foundation patch mismatch: {label}")
    return text.replace(old, new, 1)

# --- PlaybackDiagnostics: keep breadcrumbs even when diagnostic mode is not manually enabled,
# persist a compact rolling history across crashes/restarts, and expose a human-readable report.
diag = DIAG.read_text(encoding="utf-8")
diag = replace_once(
    diag,
    '    static final String KEY_DIAGNOSTICS = "diagnostics_mode";\n',
    '    static final String KEY_DIAGNOSTICS = "diagnostics_mode";\n'
    '    private static final String KEY_BREADCRUMBS = "diagnostic_breadcrumbs";\n'
    '    private static final int MAX_PERSISTED_CHARS = 16000;\n',
    'diagnostic constants')

diag = replace_once(
    diag,
    '        if (context != null && !enabled(context) && !"failure".equals(outcome)) return;\n',
    '        // Always retain lightweight breadcrumbs. The UI toggle controls verbose usage,\n'
    '        // but basic playback routing/timing must still be available after a failure.\n',
    'always-on breadcrumbs')

diag = replace_once(
    diag,
    '        Log.i(TAG, item.toString());\n',
    '        Log.i(TAG, item.toString());\n'
    '        if (context != null) {\n'
    '            try {\n'
    '                SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);\n'
    '                String previous = preferences.getString(KEY_BREADCRUMBS, "");\n'
    '                String line = item.toString() + "\\n";\n'
    '                String next = previous + line;\n'
    '                if (next.length() > MAX_PERSISTED_CHARS) {\n'
    '                    next = next.substring(next.length() - MAX_PERSISTED_CHARS);\n'
    '                    int firstBreak = next.indexOf("\\n");\n'
    '                    if (firstBreak >= 0 && firstBreak + 1 < next.length()) next = next.substring(firstBreak + 1);\n'
    '                }\n'
    '                preferences.edit().putString(KEY_BREADCRUMBS, next).apply();\n'
    '            } catch (Exception ignored) {}\n'
    '        }\n',
    'persist breadcrumbs')

insert = '''\n    static String readableReport(Context context) {\n        StringBuilder out = new StringBuilder(8192);\n        out.append("BLOFY PLAYER — تقرير التشخيص\\n");\n        out.append("Version: ").append(BuildConfig.VERSION_NAME).append('\\n');\n        out.append("Device: ").append(DeviceIdentity.displayId(context)).append('\\n');\n        out.append("Generated: ").append(System.currentTimeMillis()).append('\\n');\n        String code = latestCode();\n        if (!code.isEmpty()) out.append("Latest code: ").append(code).append('\\n');\n        out.append("--------------------------------\\n");\n\n        synchronized (LOCK) {\n            for (JSONObject event : EVENTS) {\n                appendReadableEvent(out, event);\n            }\n        }\n\n        if (context != null) {\n            String persisted = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)\n                    .getString(KEY_BREADCRUMBS, "");\n            if (out.length() < 700 && persisted != null && !persisted.isEmpty()) {\n                out.append("\\nآخر أحداث محفوظة قبل إعادة فتح التطبيق:\\n");\n                String[] lines = persisted.split("\\n");\n                int start = Math.max(0, lines.length - 40);\n                for (int i = start; i < lines.length; i++) {\n                    try { appendReadableEvent(out, new JSONObject(lines[i])); } catch (Exception ignored) {}\n                }\n            }\n        }\n        out.append("--------------------------------\\n");\n        out.append("ملاحظة: بيانات الدخول والتوكنات يتم إخفاؤها تلقائيًا.");\n        return out.toString();\n    }\n\n    private static void appendReadableEvent(StringBuilder out, JSONObject event) {\n        if (event == null) return;\n        String outcome = event.optString("outcome", "");\n        String stage = event.optString("stage", "");\n        String kind = event.optString("kind", "");\n        String ext = event.optString("ext", "");\n        String route = event.optString("route", "");\n        long elapsed = event.optLong("elapsed_ms", 0L);\n        int http = event.optInt("http", 0);\n        String code = event.optString("code", "");\n        String detail = event.optString("detail", "");\n        out.append('[').append(outcome).append("] ").append(stage);\n        if (!kind.isEmpty()) out.append(" • ").append(kind);\n        if (!ext.isEmpty()) out.append(" • ").append(ext);\n        if (!route.isEmpty()) out.append(" • route=").append(route);\n        if (elapsed > 0) out.append(" • ").append(elapsed).append("ms");\n        if (http > 0) out.append(" • HTTP ").append(http);\n        if (!code.isEmpty()) out.append(" • code=").append(code);\n        if (!detail.isEmpty()) out.append("\\n  ").append(redact(detail));\n        out.append('\\n');\n    }\n'''

diag = replace_once(diag, '    static void clear() {\n', insert + '\n    static void clear() {\n', 'readable report insertion')
diag = replace_once(
    diag,
    '    static void clear() {\n        synchronized (LOCK) { EVENTS.clear(); }\n    }\n',
    '    static void clear() {\n        synchronized (LOCK) { EVENTS.clear(); }\n    }\n\n'
    '    static void clear(Context context) {\n'
    '        clear();\n'
    '        if (context != null) context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)\n'
    '                .edit().remove(KEY_BREADCRUMBS).apply();\n'
    '    }\n',
    'context clear')
DIAG.write_text(diag, encoding="utf-8")

# --- SettingsActivity: replace copy-only action with a scrollable on-screen report.
settings = SETTINGS.read_text(encoding="utf-8")
old_action = '''        addGridSetting(grid, gridAction("▤  نسخ تقرير المشكلة", "آخر محاولات التشغيل والأداء", () -> {\n            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);\n            if (clipboard != null) clipboard.setPrimaryClip(android.content.ClipData.newPlainText("BLOFY diagnostics", PlaybackDiagnostics.report(this)));\n            ToastBridge.show(this, "تم نسخ تقرير التشخيص");\n        }));\n'''
new_action = '''        addGridSetting(grid, gridAction("▤  تقرير التشخيص", "عرض كامل • تصوير أو نسخ",\n                this::showDiagnosticsReport));\n'''
settings = replace_once(settings, old_action, new_action, 'diagnostics action')

helper = '''    private void showDiagnosticsReport() {\n        final String report = PlaybackDiagnostics.readableReport(this);\n        ScrollView scroll = new ScrollView(this);\n        TextView body = BlofyUi.text(this, report, 12, Color.WHITE);\n        body.setTextDirection(View.TEXT_DIRECTION_LTR);\n        body.setGravity(Gravity.LEFT | Gravity.TOP);\n        body.setTextIsSelectable(true);\n        body.setPadding(dp(18), dp(16), dp(18), dp(16));\n        scroll.addView(body, new ScrollView.LayoutParams(\n                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));\n\n        new android.app.AlertDialog.Builder(this)\n                .setTitle("تقرير تشخيص BLOFY")\n                .setView(scroll)\n                .setPositiveButton("نسخ التقرير", (dialog, which) -> {\n                    android.content.ClipboardManager clipboard =\n                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);\n                    if (clipboard != null) {\n                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(\n                                "BLOFY diagnostics", report));\n                    }\n                    ToastBridge.show(this, "تم نسخ تقرير التشخيص");\n                })\n                .setNeutralButton("مسح", (dialog, which) -> {\n                    PlaybackDiagnostics.clear(this);\n                    ToastBridge.show(this, "تم مسح سجل التشخيص");\n                })\n                .setNegativeButton("إغلاق", null)\n                .show();\n    }\n\n'''
settings = replace_once(settings, '    private Button gridCycle(String title, String key, String[] values, String[] labels,\n', helper + '    private Button gridCycle(String title, String key, String[] values, String[] labels,\n', 'diagnostics dialog helper')
SETTINGS.write_text(settings, encoding="utf-8")

for path, tokens in [
    (DIAG, ["readableReport", "KEY_BREADCRUMBS", "appendReadableEvent", "MAX_PERSISTED_CHARS"]),
    (SETTINGS, ["showDiagnosticsReport", "عرض كامل • تصوير أو نسخ", "PlaybackDiagnostics.clear(this)"])
]:
    text = path.read_text(encoding="utf-8")
    for token in tokens:
        if token not in text:
            raise SystemExit(f"v340 final foundation invariant missing in {path.name}: {token}")

print("v340 final foundation applied: persistent breadcrumbs + readable in-app diagnostic report")
