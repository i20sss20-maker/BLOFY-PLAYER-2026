package tv.blofy.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/** Lightweight in-app diagnostics for playback and UI latency. */
final class PlaybackDiagnostics {
    private static final String TAG = "BlofyDiag";
    private static final int MAX_EVENTS = 160;
    private static final Object LOCK = new Object();
    private static final Deque<JSONObject> EVENTS = new ArrayDeque<>();

    static final String PREFS = "blofy_player_settings";
    static final String KEY_DIAGNOSTICS = "diagnostics_mode";

    private PlaybackDiagnostics() {}

    static boolean enabled(Context context) {
        if (context == null) return false;
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_DIAGNOSTICS, false);
    }

    static long start(Context context, String stage, String kind, String id, String extension, String route) {
        long now = SystemClock.elapsedRealtime();
        event(context, "start", stage, kind, id, extension, route, "", 0L, 0, "");
        return now;
    }

    static void success(Context context, String stage, String kind, String id, String extension,
                        String route, long startedAtMs, String detail) {
        long elapsed = Math.max(0L, SystemClock.elapsedRealtime() - startedAtMs);
        event(context, "success", stage, kind, id, extension, route, detail, elapsed, 0, "");
    }

    static void failure(Context context, String stage, String kind, String id, String extension,
                        String route, long startedAtMs, int httpStatus, Throwable error) {
        long elapsed = startedAtMs <= 0 ? 0L : Math.max(0L, SystemClock.elapsedRealtime() - startedAtMs);
        String message = error == null ? "" : String.valueOf(error.getMessage());
        String code = classify(stage, extension, httpStatus, message);
        event(context, "failure", stage, kind, id, extension, route, message, elapsed, httpStatus, code);
    }

    static void marker(Context context, String stage, String kind, String id, String extension,
                       String route, String detail) {
        event(context, "marker", stage, kind, id, extension, route, detail, 0L, 0, "");
    }

    static String latestCode() {
        synchronized (LOCK) {
            JSONObject last = EVENTS.peekLast();
            return last == null ? "" : last.optString("code", "");
        }
    }

    static String report(Context context) {
        JSONObject root = new JSONObject();
        try {
            root.put("app", "BLOFY PLAYER");
            root.put("version", BuildConfig.VERSION_NAME);
            root.put("device", DeviceIdentity.displayId(context));
            root.put("generated_at", System.currentTimeMillis());
            JSONArray events = new JSONArray();
            synchronized (LOCK) {
                for (JSONObject event : EVENTS) events.put(new JSONObject(event.toString()));
            }
            root.put("events", events);
        } catch (Exception ignored) {}
        return root.toString();
    }

    static void clear() {
        synchronized (LOCK) { EVENTS.clear(); }
    }

    private static void event(Context context, String outcome, String stage, String kind, String id,
                              String extension, String route, String detail, long elapsedMs,
                              int httpStatus, String code) {
        if (context != null && !enabled(context) && !"failure".equals(outcome)) return;
        JSONObject item = new JSONObject();
        try {
            item.put("ts", System.currentTimeMillis());
            item.put("outcome", outcome);
            item.put("stage", safe(stage));
            item.put("kind", safe(kind));
            item.put("id", safe(id));
            item.put("ext", safe(extension));
            item.put("route", safe(route));
            item.put("elapsed_ms", elapsedMs);
            item.put("http", httpStatus);
            item.put("code", safe(code));
            item.put("detail", redact(detail));
        } catch (Exception ignored) {}
        synchronized (LOCK) {
            EVENTS.addLast(item);
            while (EVENTS.size() > MAX_EVENTS) EVENTS.removeFirst();
        }
        Log.i(TAG, item.toString());
    }

    private static String classify(String stage, String extension, int status, String message) {
        String ext = safe(extension).toUpperCase(Locale.US);
        String text = safe(message).toUpperCase(Locale.US);
        String family = stage != null && stage.toLowerCase(Locale.US).contains("live") ? "LIVE" : "VOD";
        if (status > 0) return family + "-" + ext + "-HTTP" + status;
        if (text.contains("403")) return family + "-" + ext + "-HTTP403";
        if (text.contains("404")) return family + "-" + ext + "-HTTP404";
        if (text.contains("TIMEOUT") || text.contains("مهلة")) return family + "-" + ext + "-TIMEOUT";
        if (text.contains("UNRECOGNIZED") || text.contains("SNIFF")) return "MEDIA3-UNRECOGNIZED";
        if (text.contains("DECODER") || text.contains("CODEC")) return "DECODER-FAILED";
        if (text.contains("VLC")) return "VLC-FALLBACK-FAILED";
        return family + "-" + ext + "-FAILED";
    }

    private static String redact(String value) {
        String text = safe(value);
        return text.replaceAll("(?i)(username|user|password|pass|token|auth)=([^&\\s]+)", "$1=<redacted>")
                .replaceAll("(?i)(Bearer\\s+)[A-Za-z0-9._~-]+", "$1<redacted>");
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
