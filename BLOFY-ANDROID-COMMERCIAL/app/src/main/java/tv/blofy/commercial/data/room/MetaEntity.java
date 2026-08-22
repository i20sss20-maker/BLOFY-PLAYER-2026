package tv.blofy.commercial.data.room;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "meta")
public final class MetaEntity {
    @PrimaryKey @NonNull public String key;
    @NonNull public String value;

    public MetaEntity(@NonNull String key, @NonNull String value) {
        this.key = key;
        this.value = value;
    }
}
