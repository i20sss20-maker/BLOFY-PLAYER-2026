package tv.blofy.commercial.data.room;

import androidx.annotation.NonNull;
import androidx.room.Entity;

@Entity(tableName = "category", primaryKeys = {"type", "id"})
public final class CategoryEntity {
    @NonNull public String type;
    @NonNull public String id;
    @NonNull public String name;

    public CategoryEntity(@NonNull String type, @NonNull String id, @NonNull String name) {
        this.type = type;
        this.id = id;
        this.name = name;
    }
}
