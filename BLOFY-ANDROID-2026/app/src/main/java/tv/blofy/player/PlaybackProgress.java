package tv.blofy.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.format.DateUtils;

/** One source of truth for VOD resume positions and the last watched series episode. */
final class PlaybackProgress {
    private static final String PREFS = "blofy_positions";
    static final long RESUME_THRESHOLD_MS = 60_000L;

    private PlaybackProgress() {}

    static long get(Context context, String kind, String id) {
        return prefs(context).getLong(positionKey(kind, id), 0L);
    }

    static void save(Context context, String kind, String id, long position) {
        prefs(context).edit().putLong(positionKey(kind, id), Math.max(0L, position)).apply();
    }

    static void clear(Context context, String kind, String id) {
        prefs(context).edit().remove(positionKey(kind, id)).apply();
    }

    static void rememberEpisode(Context context, String seriesId, String episodeId,
                                String title, String extension) {
        String key = seriesKey(seriesId);
        prefs(context).edit()
                .putString(key + "_id", value(episodeId))
                .putString(key + "_title", value(title))
                .putString(key + "_extension", value(extension))
                .apply();
    }

    static EpisodeResume episode(Context context, String seriesId) {
        SharedPreferences values = prefs(context);
        String key = seriesKey(seriesId);
        String id = values.getString(key + "_id", "");
        if (id == null || id.isEmpty()) return null;
        String title = values.getString(key + "_title", "");
        String extension = values.getString(key + "_extension", "");
        long position = get(context, "episode", id);
        return new EpisodeResume(id, value(title), value(extension), position);
    }

    static String format(long positionMs) {
        return DateUtils.formatElapsedTime(Math.max(0L, positionMs) / 1000L);
    }

    static String positionKey(String kind, String id) {
        return "position_" + Integer.toHexString((value(kind) + ":" + value(id)).hashCode());
    }

    private static String seriesKey(String seriesId) {
        return "series_last_" + Integer.toHexString(value(seriesId).hashCode());
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String value(String value) { return value == null ? "" : value; }

    static final class EpisodeResume {
        final String id;
        final String title;
        final String extension;
        final long position;

        EpisodeResume(String id, String title, String extension, long position) {
            this.id = id;
            this.title = title;
            this.extension = extension;
            this.position = position;
        }

        boolean available() { return position >= RESUME_THRESHOLD_MS; }
    }
}
