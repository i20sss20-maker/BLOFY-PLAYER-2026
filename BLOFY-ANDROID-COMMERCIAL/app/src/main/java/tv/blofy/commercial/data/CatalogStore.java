package tv.blofy.commercial.data;

import android.content.Context;

import androidx.sqlite.db.SimpleSQLiteQuery;

import java.util.ArrayList;
import java.util.List;

import tv.blofy.commercial.data.room.BlofyDatabase;
import tv.blofy.commercial.data.room.CatalogDao;
import tv.blofy.commercial.data.room.CategoryCountRow;
import tv.blofy.commercial.data.room.CategoryEntity;
import tv.blofy.commercial.data.room.FavoriteEntity;
import tv.blofy.commercial.data.room.HistoryEntity;
import tv.blofy.commercial.data.room.MediaEntity;
import tv.blofy.commercial.data.room.MetaEntity;
import tv.blofy.commercial.provider.PlaylistProfile;
import tv.blofy.commercial.provider.PlaylistRepository;

/**
 * Room-backed local catalogue facade scoped to the active playlist.
 * The UI deliberately keeps bounded LIMIT/OFFSET reads through this class.
 */
public final class CatalogStore {
    private final BlofyDatabase database;
    private final CatalogDao dao;
    private long nextSortOrder;

    public CatalogStore(Context context) {
        PlaylistProfile active = PlaylistRepository.active(context);
        String playlistId = active == null ? "legacy" : active.id;
        database = BlofyDatabase.get(context, playlistId);
        dao = database.catalogDao();
        nextSortOrder = 0L;
    }

    public synchronized void clearCatalog() {
        database.runInTransaction(() -> {
            dao.clearCategories();
            dao.clearMedia();
        });
        nextSortOrder = 0L;
    }

    public void saveCategory(String type, String id, String name) {
        dao.insertCategory(new CategoryEntity(safe(type), safe(id), safeName(name)));
    }

    public synchronized void saveMedia(List<MediaRecord> rows) {
        if (rows == null || rows.isEmpty()) return;
        List<MediaEntity> entities = new ArrayList<>(rows.size());
        for (MediaRecord item : rows) {
            entities.add(new MediaEntity(
                    safe(item.type), safe(item.id), safeName(item.name), safe(item.image),
                    safe(item.backdrop), safe(item.categoryId), safe(item.rating), safe(item.year),
                    safe(item.extension), nextSortOrder++));
        }
        dao.insertMedia(entities);
    }

    public int count(String type) { return dao.count(safe(type)); }
    public int count(String type, String category) { return dao.count(safe(type), safe(category)); }

    public List<CategoryRecord> categories(String type) {
        List<CategoryRecord> result = new ArrayList<>();
        List<CategoryCountRow> rows = dao.categories(safe(type));
        if (rows == null) return result;
        for (CategoryCountRow row : rows) {
            result.add(new CategoryRecord(safe(row.id), safeName(row.name), Math.max(0, row.count)));
        }
        return result;
    }

    public List<MediaRecord> media(String type, String category, String query, int limit) {
        return media(type, category, query, false, false, limit);
    }

    public List<MediaRecord> media(String type, String category, String query,
                                   boolean favorites, boolean history, int limit) {
        return media(type, category, query, favorites, history, limit, 0);
    }

    public List<MediaRecord> media(String type, String category, String query,
                                   boolean favorites, boolean history, int limit, int offset) {
        StringBuilder sql = new StringBuilder(
                "SELECT m.type,m.id,m.name,m.image,m.backdrop,m.category_id,m.rating,m.year,m.extension,m.sort_order FROM media m ");
        if (favorites) sql.append("INNER JOIN favorite f ON f.type=m.type AND f.id=m.id ");
        if (history) sql.append("INNER JOIN history h ON h.type=m.type AND h.id=m.id ");

        List<Object> args = new ArrayList<>();
        List<String> where = new ArrayList<>();
        String safeType = safe(type);
        String safeCategory = safe(category);
        String safeQuery = safe(query);
        if (!safeType.isEmpty()) { where.add("m.type=?"); args.add(safeType); }
        if (!safeCategory.isEmpty()) { where.add("m.category_id=?"); args.add(safeCategory); }
        if (!safeQuery.isEmpty()) { where.add("m.name LIKE ?"); args.add("%" + safeQuery + "%"); }
        if (!where.isEmpty()) sql.append("WHERE ").append(String.join(" AND ", where)).append(' ');
        if (history) sql.append("ORDER BY h.watched_at DESC ");
        else if ("live".equals(safeType) && safeQuery.isEmpty()) sql.append("ORDER BY m.sort_order ASC ");
        else sql.append("ORDER BY m.name COLLATE NOCASE ");
        sql.append("LIMIT ? OFFSET ?");
        args.add(Math.max(1, limit));
        args.add(Math.max(0, offset));
        return records(dao.rawMedia(new SimpleSQLiteQuery(sql.toString(), args.toArray())));
    }

    public List<MediaRecord> recent(String type, int limit) {
        String sql = "SELECT type,id,name,image,backdrop,category_id,rating,year,extension,sort_order "
                + "FROM media WHERE type=? ORDER BY sort_order DESC LIMIT ?";
        return records(dao.rawMedia(new SimpleSQLiteQuery(
                sql, new Object[]{safe(type), Math.max(1, limit)})));
    }

    public MediaRecord mediaById(String type, String id) {
        MediaEntity row = dao.mediaById(safe(type), safe(id));
        return row == null ? null : record(row);
    }

    public MediaRecord adjacentLive(String id, int direction) {
        Long order = dao.liveOrder(safe(id));
        MediaEntity row = null;
        if (order != null) row = direction >= 0 ? dao.nextLive(order) : dao.previousLive(order);
        if (row == null) row = direction >= 0 ? dao.firstLive() : dao.lastLive();
        return row == null ? null : record(row);
    }

    public String getMeta(String key) {
        String value = dao.getMeta(safe(key));
        return value == null ? "" : value;
    }

    public boolean toggleFavorite(String type, String id) {
        String safeType = safe(type);
        String safeId = safe(id);
        if (dao.favoriteCount(safeType, safeId) > 0) {
            dao.deleteFavorite(safeType, safeId);
            return false;
        }
        dao.insertFavorite(new FavoriteEntity(safeType, safeId, System.currentTimeMillis()));
        return true;
    }

    public void addHistory(String type, String id) {
        dao.insertHistory(new HistoryEntity(safe(type), safe(id), System.currentTimeMillis(), 0L));
    }

    public void putMeta(String key, String value) { dao.putMeta(new MetaEntity(safe(key), safe(value))); }
    public void close() { }

    private static List<MediaRecord> records(List<MediaEntity> values) {
        List<MediaRecord> result = new ArrayList<>(values == null ? 0 : values.size());
        if (values != null) for (MediaEntity row : values) result.add(record(row));
        return result;
    }

    private static MediaRecord record(MediaEntity row) {
        return new MediaRecord(
                safe(row.type), safe(row.id), safeName(row.name), safe(row.image), safe(row.backdrop),
                safe(row.categoryId), safe(row.rating), safe(row.year), safe(row.extension));
    }

    private static String safe(String value) { return value == null ? "" : value; }
    private static String safeName(String value) {
        String text = safe(value).trim();
        return text.isEmpty() ? "غير مصنف" : text;
    }
}
