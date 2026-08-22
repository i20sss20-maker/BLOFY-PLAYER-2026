package tv.blofy.commercial.data.room;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;

@Entity(
        tableName = "media",
        primaryKeys = {"type", "id"},
        indices = {
                @Index(value = {"type", "category_id", "name"}),
                @Index(value = {"type", "sort_order"})
        })
public final class MediaEntity {
    @NonNull public String type;
    @NonNull public String id;
    @NonNull public String name;
    @NonNull public String image;
    @NonNull public String backdrop;
    @ColumnInfo(name = "category_id") @NonNull public String categoryId;
    @NonNull public String rating;
    @NonNull public String year;
    @NonNull public String extension;
    @ColumnInfo(name = "direct_source") @NonNull public String directSource;
    @ColumnInfo(name = "sort_order") public long sortOrder;

    public MediaEntity(@NonNull String type, @NonNull String id, @NonNull String name,
                       @NonNull String image, @NonNull String backdrop,
                       @NonNull String categoryId, @NonNull String rating,
                       @NonNull String year, @NonNull String extension,
                       @NonNull String directSource, long sortOrder) {
        this.type = type;
        this.id = id;
        this.name = name;
        this.image = image;
        this.backdrop = backdrop;
        this.categoryId = categoryId;
        this.rating = rating;
        this.year = year;
        this.extension = extension;
        this.directSource = directSource;
        this.sortOrder = sortOrder;
    }
}
