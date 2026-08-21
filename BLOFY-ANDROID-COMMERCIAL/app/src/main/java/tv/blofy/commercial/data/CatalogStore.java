package tv.blofy.commercial.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public final class CatalogStore extends SQLiteOpenHelper {
    public CatalogStore(Context context) { super(context.getApplicationContext(), "blofy_commercial.db", null, 1); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE category(type TEXT,id TEXT,name TEXT,PRIMARY KEY(type,id))");
        db.execSQL("CREATE TABLE media(type TEXT,id TEXT,name TEXT,image TEXT,backdrop TEXT,category_id TEXT,rating TEXT,year TEXT,extension TEXT,PRIMARY KEY(type,id))");
        db.execSQL("CREATE INDEX media_filter ON media(type,category_id,name)");
        db.execSQL("CREATE TABLE favorite(type TEXT,id TEXT,created_at INTEGER,PRIMARY KEY(type,id))");
        db.execSQL("CREATE TABLE history(type TEXT,id TEXT,watched_at INTEGER,position INTEGER DEFAULT 0,PRIMARY KEY(type,id))");
        db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY,value TEXT)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

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

    public List<MediaRecord> media(String type, String category, String query, int limit) {
        return media(type, category, query, false, false, limit);
    }

    public List<MediaRecord> media(String type, String category, String query, boolean favorites, boolean history, int limit) {
        StringBuilder sql = new StringBuilder("SELECT m.type,m.id,m.name,m.image,m.backdrop,m.category_id,m.rating,m.year,m.extension FROM media m ");
        if (favorites) sql.append("INNER JOIN favorite f ON f.type=m.type AND f.id=m.id ");
        if (history) sql.append("INNER JOIN history h ON h.type=m.type AND h.id=m.id ");
        StringBuilder where = new StringBuilder();
        List<String> args = new ArrayList<>();
        if (type != null && !type.isEmpty()) { where.append("m.type=?"); args.add(type); }
        if (category != null && !category.isEmpty()) { where.append(" AND category_id=?"); args.add(category); }
        if (query != null && !query.trim().isEmpty()) { where.append(" AND name LIKE ?"); args.add("%" + query.trim() + "%"); }
        args.add(String.valueOf(Math.max(1, limit)));
        List<MediaRecord> result = new ArrayList<>();
        if (where.length() > 0) sql.append("WHERE ").append(where.toString().replaceFirst("^ AND ", ""));
        sql.append(history ? " ORDER BY h.watched_at DESC" : " ORDER BY m.name COLLATE NOCASE").append(" LIMIT ?");
        try (Cursor c = getReadableDatabase().rawQuery(sql.toString(), args.toArray(new String[0]))) {
            while (c.moveToNext()) result.add(new MediaRecord(c.getString(0), c.getString(1), c.getString(2), value(c,3), value(c,4), value(c,5), value(c,6), value(c,7), value(c,8)));
        }
        return result;
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
        ContentValues row = new ContentValues(); row.put("type", type); row.put("id", id); row.put("watched_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("history", null, row, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public void putMeta(String key, String value) {
        ContentValues row = new ContentValues(); row.put("key", key); row.put("value", value == null ? "" : value);
        getWritableDatabase().insertWithOnConflict("meta", null, row, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private static String value(Cursor cursor, int index) { return cursor.isNull(index) ? "" : cursor.getString(index); }
}
