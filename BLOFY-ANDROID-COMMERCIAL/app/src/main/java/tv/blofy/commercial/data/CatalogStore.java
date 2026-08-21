package tv.blofy.commercial.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public final class CatalogStore extends SQLiteOpenHelper {
    public CatalogStore(Context context) { super(context.getApplicationContext(), "blofy_commercial.db", null, 2); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE category(type TEXT,id TEXT,name TEXT,PRIMARY KEY(type,id))");
        db.execSQL("CREATE TABLE media(type TEXT,id TEXT,name TEXT,image TEXT,backdrop TEXT,category_id TEXT,rating TEXT,year TEXT,extension TEXT,PRIMARY KEY(type,id))");
        db.execSQL("CREATE INDEX media_filter ON media(type,category_id,name)");
        db.execSQL("CREATE INDEX media_type_name ON media(type,name COLLATE NOCASE)");
        db.execSQL("CREATE TABLE favorite(type TEXT,id TEXT,created_at INTEGER,PRIMARY KEY(type,id))");
        db.execSQL("CREATE TABLE history(type TEXT,id TEXT,watched_at INTEGER,position INTEGER DEFAULT 0,PRIMARY KEY(type,id))");
        db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY,value TEXT)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) db.execSQL("CREATE INDEX IF NOT EXISTS media_type_name ON media(type,name COLLATE NOCASE)");
    }

    public void clearCatalog() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try { db.delete("category", null, null); db.delete("media", null, null); db.setTransactionSuccessful(); }
        finally { db.endTransaction(); }
    }

    public void saveCategory(String type, String id, String name) {
        ContentValues values = new ContentValues();
        values.put("type", type); values.put("id", id); values.put("name", name);
        getWritableDatabase().insertWithOnConflict("category", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public void saveMedia(List<MediaRecord> rows) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (MediaRecord item : rows) {
                ContentValues value = new ContentValues();
                value.put("type", item.type); value.put("id", item.id); value.put("name", item.name);
                value.put("image", item.image); value.put("backdrop", item.backdrop); value.put("category_id", item.categoryId);
                value.put("rating", item.rating); value.put("year", item.year); value.put("extension", item.extension);
                db.insertWithOnConflict("media", null, value, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public int count(String type) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM media WHERE type=?", new String[]{type})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    public int count(String type, String category) {
        String selection = "type=?" + (category == null || category.isEmpty() ? "" : " AND category_id=?");
        String[] args = category == null || category.isEmpty()
                ? new String[]{type} : new String[]{type, category};
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM media WHERE " + selection, args)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    public List<CategoryRecord> categories(String type) {
        List<CategoryRecord> result = new ArrayList<>();
        String sql = "SELECT c.id,c.name,COUNT(m.id) FROM category c " +
                "LEFT JOIN media m ON m.type=c.type AND m.category_id=c.id " +
                "WHERE c.type=? GROUP BY c.id,c.name HAVING COUNT(m.id)>0 " +
                "ORDER BY c.name COLLATE NOCASE";
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, new String[]{type})) {
            while (cursor.moveToNext()) {
                result.add(new CategoryRecord(value(cursor, 0), value(cursor, 1), cursor.getInt(2)));
            }
        }
        return result;
    }

    public List<MediaRecord> media(String type, String category, String query, int limit) {
        return media(type, category, query, false, false, limit);
    }

    public List<MediaRecord> media(String type, String category, String query, boolean favorites, boolean history, int limit) {
        return media(type, category, query, favorites, history, limit, 0);
    }

    public List<MediaRecord> media(String type, String category, String query, boolean favorites, boolean history, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT m.type,m.id,m.name,m.image,m.backdrop,m.category_id,m.rating,m.year,m.extension FROM media m ");
        if (favorites) sql.append("INNER JOIN favorite f ON f.type=m.type AND f.id=m.id ");
        if (history) sql.append("INNER JOIN history h ON h.type=m.type AND h.id=m.id ");
        StringBuilder where = new StringBuilder();
        List<String> args = new ArrayList<>();
        if (type != null && !type.isEmpty()) { where.append("m.type=?"); args.add(type); }
        if (category != null && !category.isEmpty()) { where.append(" AND category_id=?"); args.add(category); }
        if (query != null && !query.trim().isEmpty()) { where.append(" AND name LIKE ?"); args.add("%" + query.trim() + "%"); }
        args.add(String.valueOf(Math.max(1, limit)));
        args.add(String.valueOf(Math.max(0, offset)));
        List<MediaRecord> result = new ArrayList<>();
        if (where.length() > 0) sql.append("WHERE ").append(where.toString().replaceFirst("^ AND ", ""));
        sql.append(history ? " ORDER BY h.watched_at DESC" : " ORDER BY m.name COLLATE NOCASE").append(" LIMIT ? OFFSET ?");
        try (Cursor c = getReadableDatabase().rawQuery(sql.toString(), args.toArray(new String[0]))) {
            while (c.moveToNext()) result.add(new MediaRecord(c.getString(0), c.getString(1), c.getString(2), value(c,3), value(c,4), value(c,5), value(c,6), value(c,7), value(c,8)));
        }
        return result;
    }

    public MediaRecord mediaById(String type, String id) {
        String sql = "SELECT type,id,name,image,backdrop,category_id,rating,year,extension FROM media WHERE type=? AND id=? LIMIT 1";
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, new String[]{type, id})) {
            return cursor.moveToFirst() ? record(cursor) : null;
        }
    }

    public MediaRecord adjacentLive(String id, int direction) {
        SQLiteDatabase db = getReadableDatabase();
        long rowId = -1L;
        try (Cursor cursor = db.rawQuery("SELECT rowid FROM media WHERE type='live' AND id=? LIMIT 1", new String[]{id})) {
            if (cursor.moveToFirst()) rowId = cursor.getLong(0);
        }
        String comparator = direction >= 0 ? ">" : "<";
        String ordering = direction >= 0 ? "ASC" : "DESC";
        if (rowId >= 0L) {
            try (Cursor cursor = db.rawQuery(
                    "SELECT type,id,name,image,backdrop,category_id,rating,year,extension FROM media WHERE type='live' AND rowid" + comparator + "? ORDER BY rowid " + ordering + " LIMIT 1",
                    new String[]{String.valueOf(rowId)})) {
                if (cursor.moveToFirst()) return record(cursor);
            }
        }
        try (Cursor cursor = db.rawQuery(
                "SELECT type,id,name,image,backdrop,category_id,rating,year,extension FROM media WHERE type='live' ORDER BY rowid " + ordering + " LIMIT 1", null)) {
            return cursor.moveToFirst() ? record(cursor) : null;
        }
    }

    public String getMeta(String key) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT value FROM meta WHERE key=?", new String[]{key})) {
            return cursor.moveToFirst() ? value(cursor, 0) : "";
        }
    }

    public boolean toggleFavorite(String type, String id) {
        SQLiteDatabase db = getWritableDatabase();
        try (Cursor cursor = db.rawQuery("SELECT 1 FROM favorite WHERE type=? AND id=?", new String[]{type,id})) {
            if (cursor.moveToFirst()) { db.delete("favorite", "type=? AND id=?", new String[]{type,id}); return false; }
        }
        ContentValues row = new ContentValues(); row.put("type", type); row.put("id", id); row.put("created_at", System.currentTimeMillis());
        db.insertWithOnConflict("favorite", null, row, SQLiteDatabase.CONFLICT_REPLACE); return true;
    }

    public void addHistory(String type, String id) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues initial = new ContentValues(); initial.put("type", type); initial.put("id", id); initial.put("watched_at", System.currentTimeMillis());
        db.insertWithOnConflict("history", null, initial, SQLiteDatabase.CONFLICT_IGNORE);
        ContentValues update = new ContentValues(); update.put("watched_at", System.currentTimeMillis());
        db.update("history", update, "type=? AND id=?", new String[]{type, id});
    }

    public void putMeta(String key, String value) {
        ContentValues row = new ContentValues(); row.put("key", key); row.put("value", value == null ? "" : value);
        getWritableDatabase().insertWithOnConflict("meta", null, row, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private static String value(Cursor cursor, int index) { return cursor.isNull(index) ? "" : cursor.getString(index); }
    private static MediaRecord record(Cursor cursor) {
        return new MediaRecord(value(cursor,0), value(cursor,1), value(cursor,2), value(cursor,3), value(cursor,4), value(cursor,5), value(cursor,6), value(cursor,7), value(cursor,8));
    }
}
