package tv.blofy.player;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

final class CatalogDatabase extends SQLiteOpenHelper {
    private static final String NAME = "blofy_catalog.db";
    private static final int VERSION = 4;

    CatalogDatabase(Context context) { super(context.getApplicationContext(), NAME, null, VERSION); }

    @Override
    public void onCreate(SQLiteDatabase database) {
        database.execSQL("CREATE TABLE categories(type TEXT NOT NULL,id TEXT NOT NULL,name TEXT NOT NULL,sort_order INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(type,id))");
        database.execSQL("CREATE TABLE media(type TEXT NOT NULL,id TEXT NOT NULL,name TEXT NOT NULL,image TEXT,backdrop TEXT,category_id TEXT,rating TEXT,year TEXT,extension TEXT,sort_order INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(type,id))");
        database.execSQL("CREATE INDEX media_type_category_order ON media(type,category_id,sort_order)");
        database.execSQL("CREATE INDEX media_type_order ON media(type,sort_order)");
        database.execSQL("CREATE INDEX media_name_search ON media(type,name)");
        database.execSQL("CREATE TABLE favorites(type TEXT NOT NULL,id TEXT NOT NULL,created_at INTEGER NOT NULL,PRIMARY KEY(type,id))");
        database.execSQL("CREATE TABLE history(type TEXT NOT NULL,id TEXT NOT NULL,watched_at INTEGER NOT NULL,PRIMARY KEY(type,id))");
        database.execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
        if (oldVersion < 3) {
            database.delete("categories", null, null);
            database.delete("media", null, null);
        }
        if (oldVersion < 4) {
            try { database.execSQL("ALTER TABLE categories ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0"); } catch (Exception ignored) {}
            try { database.execSQL("ALTER TABLE media ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0"); } catch (Exception ignored) {}
            try { database.execSQL("DROP INDEX IF EXISTS media_type_category"); } catch (Exception ignored) {}
            database.execSQL("CREATE INDEX IF NOT EXISTS media_type_category_order ON media(type,category_id,sort_order)");
            database.execSQL("CREATE INDEX IF NOT EXISTS media_type_order ON media(type,sort_order)");
            database.execSQL("CREATE INDEX IF NOT EXISTS media_name_search ON media(type,name)");
            database.delete("categories", null, null);
            database.delete("media", null, null);
        }
        ContentValues values = new ContentValues();
        values.put("key", "sync_state");
        values.put("value", "upgrade_required");
        database.insertWithOnConflict("metadata", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    void beginFreshImport() {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            database.delete("categories", null, null);
            database.delete("media", null, null);
            database.setTransactionSuccessful();
        } finally { database.endTransaction(); }
    }

    void saveCategories(List<BlofyModels.Category> categories) {
        if (categories == null || categories.isEmpty()) return;
        SQLiteDatabase database = getWritableDatabase();
        long order = nextOrder(database, "categories", categories.get(0).type);
        database.beginTransaction();
        try {
            for (BlofyModels.Category category : categories) {
                ContentValues values = new ContentValues();
                values.put("type", category.type);
                values.put("id", category.id);
                values.put("name", category.name);
                values.put("sort_order", order++);
                database.insertWithOnConflict("categories", null, values, SQLiteDatabase.CONFLICT_IGNORE);
            }
            database.setTransactionSuccessful();
        } finally { database.endTransaction(); }
    }

    void saveMedia(List<BlofyModels.Media> items) {
        if (items == null || items.isEmpty()) return;
        SQLiteDatabase database = getWritableDatabase();
        long order = nextOrder(database, "media", items.get(0).type);
        database.beginTransaction();
        try {
            for (BlofyModels.Media item : items) {
                ContentValues values = new ContentValues();
                values.put("type", item.type);
                values.put("id", item.id);
                values.put("name", item.name);
                values.put("image", item.image);
                values.put("backdrop", item.backdrop);
                values.put("category_id", item.categoryId);
                values.put("rating", item.rating);
                values.put("year", item.year);
                values.put("extension", item.extension);
                values.put("sort_order", order++);
                database.insertWithOnConflict("media", null, values, SQLiteDatabase.CONFLICT_IGNORE);
            }
            database.setTransactionSuccessful();
        } finally { database.endTransaction(); }
    }

    private long nextOrder(SQLiteDatabase database, String table, String type) {
        try (Cursor cursor = database.rawQuery("SELECT COALESCE(MAX(sort_order),-1)+1 FROM " + table + " WHERE type=?", new String[]{type})) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0L;
        }
    }

    List<BlofyModels.Category> categories(String type) {
        List<BlofyModels.Category> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("categories", new String[]{"id", "name"}, "type=?", new String[]{type}, null, null, "sort_order ASC")) {
            while (cursor.moveToNext()) result.add(new BlofyModels.Category(cursor.getString(0), cursor.getString(1), type));
        }
        return result;
    }

    List<BlofyModels.Media> media(String type, String category, String search, boolean favoritesOnly, boolean historyOnly, int limit, int offset) {
        List<BlofyModels.Media> result = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT m.id,m.name,m.image,m.backdrop,m.category_id,m.rating,m.year,m.extension,m.type FROM media m ");
        if (favoritesOnly) sql.append("INNER JOIN favorites f ON f.type=m.type AND f.id=m.id ");
        if (historyOnly) sql.append("INNER JOIN history h ON h.type=m.type AND h.id=m.id ");
        List<String> where = new ArrayList<>();
        List<String> args = new ArrayList<>();
        if (type != null && !type.isEmpty()) { where.add("m.type=?"); args.add(type); }
        if (category != null && !category.isEmpty()) { where.add("m.category_id=?"); args.add(category); }
        if (search != null && !search.trim().isEmpty()) { where.add("m.name LIKE ?"); args.add("%" + search.trim() + "%"); }
        if (!where.isEmpty()) sql.append("WHERE ").append(android.text.TextUtils.join(" AND ", where)).append(' ');
        sql.append(historyOnly ? "ORDER BY h.watched_at DESC " : "ORDER BY m.sort_order ASC ");
        sql.append("LIMIT ? OFFSET ?");
        args.add(String.valueOf(Math.max(1, limit)));
        args.add(String.valueOf(Math.max(0, offset)));
        try (Cursor cursor = getReadableDatabase().rawQuery(sql.toString(), args.toArray(new String[0]))) {
            while (cursor.moveToNext()) result.add(new BlofyModels.Media(
                    cursor.getString(0), cursor.getString(1), value(cursor, 2), value(cursor, 3),
                    value(cursor, 4), value(cursor, 5), value(cursor, 6), value(cursor, 7), value(cursor, 8)));
        }
        return result;
    }

    int count(String type) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM media WHERE type=?", new String[]{type})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    boolean isFavorite(String type, String id) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT 1 FROM favorites WHERE type=? AND id=?", new String[]{type, id})) {
            return cursor.moveToFirst();
        }
    }

    boolean toggleFavorite(String type, String id) {
        SQLiteDatabase database = getWritableDatabase();
        if (isFavorite(type, id)) {
            database.delete("favorites", "type=? AND id=?", new String[]{type, id});
            return false;
        }
        ContentValues values = new ContentValues();
        values.put("type", type);
        values.put("id", id);
        values.put("created_at", System.currentTimeMillis());
        database.insertWithOnConflict("favorites", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        return true;
    }

    void addHistory(String type, String id) {
        ContentValues values = new ContentValues();
        values.put("type", type);
        values.put("id", id);
        values.put("watched_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("history", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    void putMetadata(String key, String value) {
        ContentValues values = new ContentValues();
        values.put("key", key);
        values.put("value", value == null ? "" : value);
        getWritableDatabase().insertWithOnConflict("metadata", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    String metadata(String key, String fallback) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT value FROM metadata WHERE key=?", new String[]{key})) {
            return cursor.moveToFirst() ? cursor.getString(0) : fallback;
        }
    }

    private static String value(Cursor cursor, int column) { return cursor.isNull(column) ? "" : cursor.getString(column); }
}
