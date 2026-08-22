package tv.blofy.commercial.data.room;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;

@Entity(tableName = "history", primaryKeys = {"type", "id"})
public final class HistoryEntity {
    @NonNull public String type;
    @NonNull public String id;
    @ColumnInfo(name = "watched_at") public long watchedAt;
    public long position;

    public HistoryEntity(@NonNull String type, @NonNull String id, long watchedAt, long position) {
        this.type = type;
        this.id = id;
        this.watchedAt = watchedAt;
        this.position = position;
    }
}
