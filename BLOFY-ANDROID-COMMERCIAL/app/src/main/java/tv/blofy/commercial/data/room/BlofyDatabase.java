package tv.blofy.commercial.data.room;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

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

    private static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE media ADD COLUMN direct_source TEXT NOT NULL DEFAULT ''");
        }
    };

    public abstract CatalogDao catalogDao();

    /** Each playlist owns its own Room file and upgrades without clearing saved catalogue state. */
    public static BlofyDatabase get(Context context, String playlistId) {
        String key = safeKey(playlistId);
        synchronized (BlofyDatabase.class) {
            BlofyDatabase current = INSTANCES.get(key);
            if (current == null) {
                current = Room.databaseBuilder(
                                context.getApplicationContext(),
                                BlofyDatabase.class,
                                "blofy_catalog_" + key + ".db")
                        .addMigrations(MIGRATION_1_2)
                        .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                        .build();
                INSTANCES.put(key, current);
            }
            return current;
        }
    }

    public static BlofyDatabase get(Context context) { return get(context, "legacy"); }

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
