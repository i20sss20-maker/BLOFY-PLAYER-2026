package tv.blofy.commercial.data.room;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

@Database(
        entities = {
                CategoryEntity.class,
                MediaEntity.class,
                FavoriteEntity.class,
                HistoryEntity.class,
                MetaEntity.class
        },
        version = 2,
        exportSchema = false)
public abstract class BlofyDatabase extends RoomDatabase {
    private static final Map<String, BlofyDatabase> INSTANCES = new HashMap<>();

    public abstract CatalogDao catalogDao();

    /**
     * Each playlist owns its own Room file. This avoids media-id collisions between providers,
     * keeps favorites/history isolated, and lets playlist switching happen without clearing
     * another provider's local catalogue.
     */
    public static BlofyDatabase get(Context context, String playlistId) {
        String key = safeKey(playlistId);
        synchronized (BlofyDatabase.class) {
            BlofyDatabase current = INSTANCES.get(key);
            if (current == null) {
                current = Room.databaseBuilder(
                                context.getApplicationContext(),
                                BlofyDatabase.class,
                                "blofy_catalog_" + key + ".db")
                        // Catalogue content is provider-derived and can always be re-synced.
                        // A destructive migration is safer than keeping a stale schema that
                        // loses provider direct_source URLs needed for reliable playback.
                        .fallbackToDestructiveMigration(true)
                        .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                        .build();
                INSTANCES.put(key, current);
            }
            return current;
        }
    }

    /** Legacy/default database is retained only as a migration bridge. */
    public static BlofyDatabase get(Context context) {
        return get(context, "legacy");
    }

    private static String safeKey(String value) {
        String raw = value == null ? "legacy" : value.trim();
        if (raw.isEmpty()) raw = "legacy";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 10; i++) out.append(String.format(java.util.Locale.US, "%02x", digest[i]));
            return out.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(raw.hashCode());
        }
    }
}
