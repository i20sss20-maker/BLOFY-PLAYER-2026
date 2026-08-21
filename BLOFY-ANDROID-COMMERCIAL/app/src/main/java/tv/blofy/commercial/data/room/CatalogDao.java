package tv.blofy.commercial.data.room;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.RawQuery;
import androidx.sqlite.db.SupportSQLiteQuery;

import java.util.List;

@Dao
public interface CatalogDao {
    @Query("DELETE FROM category")
    void clearCategories();

    @Query("DELETE FROM media")
    void clearMedia();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertCategory(CategoryEntity row);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertMedia(List<MediaEntity> rows);

    @Query("SELECT COUNT(*) FROM media WHERE type=:type")
    int count(String type);

    @Query("SELECT COUNT(*) FROM media WHERE type=:type AND (:category='' OR category_id=:category)")
    int count(String type, String category);

    @Query("SELECT c.id AS id,c.name AS name,COUNT(m.id) AS count " +
            "FROM category c LEFT JOIN media m ON m.type=c.type AND m.category_id=c.id " +
            "WHERE c.type=:type GROUP BY c.id,c.name HAVING COUNT(m.id)>0 " +
            "ORDER BY c.name COLLATE NOCASE")
    List<CategoryCountRow> categories(String type);

    @RawQuery
    List<MediaEntity> rawMedia(SupportSQLiteQuery query);

    @Query("SELECT * FROM media WHERE type=:type AND id=:id LIMIT 1")
    MediaEntity mediaById(String type, String id);

    @Query("SELECT sort_order FROM media WHERE type='live' AND id=:id LIMIT 1")
    Long liveOrder(String id);

    @Query("SELECT * FROM media WHERE type='live' AND sort_order>:orderValue ORDER BY sort_order ASC LIMIT 1")
    MediaEntity nextLive(long orderValue);

    @Query("SELECT * FROM media WHERE type='live' AND sort_order<:orderValue ORDER BY sort_order DESC LIMIT 1")
    MediaEntity previousLive(long orderValue);

    @Query("SELECT * FROM media WHERE type='live' ORDER BY sort_order ASC LIMIT 1")
    MediaEntity firstLive();

    @Query("SELECT * FROM media WHERE type='live' ORDER BY sort_order DESC LIMIT 1")
    MediaEntity lastLive();

    @Query("SELECT COALESCE(MAX(sort_order), -1) FROM media")
    long maxSortOrder();

    @Query("SELECT value FROM meta WHERE `key`=:key LIMIT 1")
    String getMeta(String key);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void putMeta(MetaEntity row);

    @Query("SELECT COUNT(*) FROM favorite WHERE type=:type AND id=:id")
    int favoriteCount(String type, String id);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertFavorite(FavoriteEntity row);

    @Query("DELETE FROM favorite WHERE type=:type AND id=:id")
    void deleteFavorite(String type, String id);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertHistory(HistoryEntity row);
}
