package tv.blofy.commercial.data.room;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(
        entities = {
                CategoryEntity.class,
                MediaEntity.class,
                FavoriteEntity.class,
                HistoryEntity.class,
                MetaEntity.class
        },
        version = 1,
        exportSchema = false)
public abstract class BlofyDatabase extends RoomDatabase {
    private static volatile BlofyDatabase instance;

    public abstract CatalogDao catalogDao();

    public static BlofyDatabase get(Context context) {
        BlofyDatabase current = instance;
        if (current != null) return current;
        synchronized (BlofyDatabase.class) {
            current = instance;
            if (current == null) {
                current = Room.databaseBuilder(
                                context.getApplicationContext(),
                                BlofyDatabase.class,
                                "blofy_catalog_room.db")
                        .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                        .build();
                instance = current;
            }
            return current;
        }
    }
}
