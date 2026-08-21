package tv.blofy.commercial.data.room;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;

@Entity(tableName = "favorite", primaryKeys = {"type", "id"})
public final class FavoriteEntity {
    @NonNull public String type;
    @NonNull public String id;
    @ColumnInfo(name = "created_at") public long createdAt;

    public FavoriteEntity(@NonNull String type, @NonNull String id, long createdAt) {
        this.type = type;
        this.id = id;
        this.createdAt = createdAt;
    }
}
