package tv.blofy.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * v331 provider-aware playback profile.
 *
 * Profiles are scoped to the active playlist + content kind + container family.
 * The import screen performs a short provider analysis before catalog download.
 * Runtime success then teaches the profile which transport should be tried first.
 */
final class PlaybackProfileManager {
    static final String MODE_LEGACY = "legacy7max";
    static final String MODE_CRONET = "cronet";
    static final String MODE_COMPAT = "compat";
    static final String MODE_VLC = "vlc";

    private static final String PREFS = "blofy_playback_profiles_v331";
    private static final String KEY_HOST_PREFIX = "host:";
    private static final String KEY_MODE_PREFIX = "mode:";
    private static final String KEY_FAIL_PREFIX = "fail:";

    private PlaybackProfileManager() {}

    static Analysis analyzeProvider(Context context, PlaylistStore store, BlofyModels.Session session) {
        String playlistId = activePlaylistId(store);
        PlaylistStore.Playlist active = store.find(playlistId);
        String source = "";
        if (active != null) {
            source = "m3u".equals(active.kind) ? active.url : active.serverUrl;
        }
        if ((source == null || source.isEmpty()) && session != null && session.serverName != null) {
            source = session.serverName;
        }

        String host = hostOf(source);
        SharedPreferences prefs = prefs(context);
        String previousHost = prefs.getString(KEY_HOST_PREFIX + playlistId, "");
        boolean hostChanged = !host.isEmpty() && !host.equalsIgnoreCase(previousHost);
        if (hostChanged) clearPlaylistModes(prefs, playlistId);

        ProbeResult probe = probe(source, false);
        ProbeResult compatProbe = probe.success ? probe : probe(source, true);
        String suggested = probe.success ? MODE_LEGACY
                : compatProbe.success ? MODE_COMPAT
                : PlaybackTransportFactory.isCronetReady() ? MODE_CRONET : MODE_LEGACY;

        SharedPreferences.Editor editor = prefs.edit();
        if (!host.isEmpty()) editor.putString(KEY_HOST_PREFIX + playlistId, host);
        putDefaultMode(editor, prefs, playlistId, "live", "ts", suggested);
        putDefaultMode(editor, prefs, playlistId, "live", "hls", suggested);
        putDefaultMode(editor, prefs, playlistId, "movies", "vod", suggested);
        putDefaultMode(editor, prefs, playlistId, "series", "vod", suggested);
        editor.apply();

        return new Analysis(host, suggested, probe.statusCode, compatProbe.statusCode,
                probe.redirected || compatProbe.redirected);
    }

    static String preferredMode(Context context, String kind, String extension) {
        PlaylistStore store = new PlaylistStore(context);
        String playlistId = activePlaylistId(store);
        SharedPreferences prefs = prefs(context);
        String key = modeKey(playlistId, kind, extension);
        return prefs.getString(key, MODE_LEGACY);
    }

    static void recordSuccess(Context context, String kind, String extension, String mode) {
        if (mode == null || mode.isEmpty()) return;
        PlaylistStore store = new PlaylistStore(context);
        String playlistId = activePlaylistId(store);
        String key = modeKey(playlistId, kind, extension);
        prefs(context).edit().putString(key, mode).putInt(KEY_FAIL_PREFIX + key, 0).apply();
    }

    static void recordFailure(Context context, String kind, String extension, String mode) {
        PlaylistStore store = new PlaylistStore(context);
        String playlistId = activePlaylistId(store);
        String key = modeKey(playlistId, kind, extension);
        SharedPreferences prefs = prefs(context);
        int failures = prefs.getInt(KEY_FAIL_PREFIX + key, 0) + 1;
        SharedPreferences.Editor editor = prefs.edit().putInt(KEY_FAIL_PREFIX + key, failures);
        if (failures >= 2 && mode != null && mode.equals(prefs.getString(key, MODE_LEGACY))) {
            editor.remove(key).putInt(KEY_FAIL_PREFIX + key, 0);
        }
        editor.apply();
    }

    static String nextMode(String current) {
        if (MODE_LEGACY.equals(current)) return MODE_CRONET;
        if (MODE_CRONET.equals(current)) return MODE_COMPAT;
        if (MODE_COMPAT.equals(current)) return MODE_VLC;
        return MODE_VLC;
    }

    private static void putDefaultMode(SharedPreferences.Editor editor, SharedPreferences prefs,
                                       String playlistId, String kind, String extension,
                                       String suggested) {
        String key = modeKey(playlistId, kind, extension);
        if (!prefs.contains(key)) editor.putString(key, suggested);
    }

    private static String activePlaylistId(PlaylistStore store) {
        String id = store.activeId();
        return id == null || id.isEmpty() ? "current-session" : id;
    }

    private static String modeKey(String playlistId, String kind, String extension) {
        return KEY_MODE_PREFIX + playlistId + ":" + normalizeKind(kind) + ":" + family(extension);
    }

    private static String normalizeKind(String kind) {
        if ("series".equals(kind) || "episode".equals(kind)) return "series";
        if ("movies".equals(kind) || "movie".equals(kind)) return "movies";
        return "live";
    }

    private static String family(String extension) {
        String ext = extension == null ? "" : extension.toLowerCase(Locale.US).replace(".", "");
        if (ext.contains("m3u8") || ext.contains("hls")) return "hls";
        if (ext.contains("ts") || ext.contains("mpegts")) return "ts";
        return "vod";
    }

    private static String hostOf(String source) {
        if (source == null || source.isEmpty()) return "";
        try {
            String normalized = source.contains("://") ? source : "http://" + source;
            String host = Uri.parse(normalized).getHost();
            return host == null ? "" : host.toLowerCase(Locale.US);
        } catch (Exception ignored) { return ""; }
    }

    private static ProbeResult probe(String source, boolean compatibilityHeaders) {
        if (source == null || source.isEmpty()) return new ProbeResult(false, -1, false);
        HttpURLConnection connection = null;
        try {
            String normalized = source.contains("://") ? source : "http://" + source;
            URL start = new URL(normalized);
            connection = (HttpURLConnection) start.openConnection();
            connection.setConnectTimeout(1200);
            connection.setReadTimeout(1200);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestMethod("HEAD");
            if (compatibilityHeaders) {
                connection.setRequestProperty("User-Agent", "ExoPlayerLib/1.11.0 (BLOFY AndroidTV)");
                connection.setRequestProperty("Accept", "*/*");
            }
            int status = connection.getResponseCode();
            URL end = connection.getURL();
            boolean redirected = end != null && !end.getHost().equalsIgnoreCase(start.getHost());
            boolean success = status >= 200 && status < 500;
            return new ProbeResult(success, status, redirected);
        } catch (Exception ignored) {
            return new ProbeResult(false, -1, false);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void clearPlaylistModes(SharedPreferences prefs, String playlistId) {
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(KEY_MODE_PREFIX + playlistId + ":")
                    || key.startsWith(KEY_FAIL_PREFIX + KEY_MODE_PREFIX + playlistId + ":")) {
                editor.remove(key);
            }
        }
        editor.apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static final class Analysis {
        final String host;
        final String suggestedMode;
        final int normalStatus;
        final int compatStatus;
        final boolean redirected;

        Analysis(String host, String suggestedMode, int normalStatus, int compatStatus, boolean redirected) {
            this.host = host;
            this.suggestedMode = suggestedMode;
            this.normalStatus = normalStatus;
            this.compatStatus = compatStatus;
            this.redirected = redirected;
        }

        String summary() {
            String provider = host == null || host.isEmpty() ? "المزوّد" : host;
            return provider + " • " + suggestedMode + (redirected ? " • redirect" : "");
        }
    }

    private static final class ProbeResult {
        final boolean success;
        final int statusCode;
        final boolean redirected;
        ProbeResult(boolean success, int statusCode, boolean redirected) {
            this.success = success;
            this.statusCode = statusCode;
            this.redirected = redirected;
        }
    }
}
