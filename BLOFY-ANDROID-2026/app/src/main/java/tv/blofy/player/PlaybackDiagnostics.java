package tv.blofy.player;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Lightweight v332 diagnostics. Never blocks playback and never stores credentials. */
final class PlaybackDiagnostics {
    private static final String PREFS = "blofy_playback_diagnostics_v332";
    private static final String KEY_PREFIX = "event:";
    private static final int MAX_EVENTS = 40;

    private PlaybackDiagnostics() {}

    static void record(Context context, String kind, String extension, String transport,
                       String stage, String detail, long elapsedMs) {
        try {
            SharedPreferences prefs = prefs(context);
            long now = System.currentTimeMillis();
            JSONObject row = new JSONObject();
            row.put("at", now);
            row.put("kind", safe(kind));
            row.put("family", family(extension));
            row.put("transport", safe(transport));
            row.put("stage", safe(stage));
            row.put("detail", sanitize(detail));
            row.put("elapsedMs", Math.max(0L, elapsedMs));
            prefs.edit().putString(KEY_PREFIX + now, row.toString()).apply();
            trim(prefs);
        } catch (Exception ignored) {}
    }

    static String snapshot(Context context) {
        JSONArray rows = new JSONArray();
        for (String json : recent(context, 20)) {
            try { rows.put(new JSONObject(json)); }
            catch (Exception ignored) {}
        }
        return rows.toString();
    }

    static void clear(Context context) {
        SharedPreferences prefs = prefs(context);
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(KEY_PREFIX)) editor.remove(key);
        }
        editor.apply();
    }

    private static List<String> recent(Context context, int limit) {
        SharedPreferences prefs = prefs(context);
        List<Long> times = new ArrayList<>();
        for (String key : prefs.getAll().keySet()) {
            if (!key.startsWith(KEY_PREFIX)) continue;
            try { times.add(Long.parseLong(key.substring(KEY_PREFIX.length()))); }
            catch (Exception ignored) {}
        }
        times.sort((a, b) -> Long.compare(b, a));
        List<String> out = new ArrayList<>();
        for (Long time : times) {
            if (out.size() >= limit) break;
            String value = prefs.getString(KEY_PREFIX + time, "");
            if (value != null && !value.isEmpty()) out.add(value);
        }
        return out;
    }

    private static void trim(SharedPreferences prefs) {
        List<Long> times = new ArrayList<>();
        for (String key : prefs.getAll().keySet()) {
            if (!key.startsWith(KEY_PREFIX)) continue;
            try { times.add(Long.parseLong(key.substring(KEY_PREFIX.length()))); }
            catch (Exception ignored) {}
        }
        if (times.size() <= MAX_EVENTS) return;
        times.sort(Long::compareTo);
        SharedPreferences.Editor editor = prefs.edit();
        for (int i = 0; i < times.size() - MAX_EVENTS; i++) {
            editor.remove(KEY_PREFIX + times.get(i));
        }
        editor.apply();
    }

    private static String sanitize(String value) {
        String out = safe(value);
        out = out.replaceAll("(?i)(username|user|password|pass|token)=([^&\\s]+)", "$1=***");
        if (out.length() > 240) out = out.substring(0, 240);
        return out;
    }

    private static String safe(String value) { return value == null ? "" : value; }

    private static String family(String extension) {
        String ext = safe(extension).toLowerCase(Locale.US).replace(".", "");
        if (ext.contains("m3u8") || ext.contains("hls")) return "hls";
        if (ext.contains("ts") || ext.contains("mpegts")) return "ts";
        if (ext.contains("mkv")) return "mkv";
        if (ext.contains("mp4")) return "mp4";
        return "vod";
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
