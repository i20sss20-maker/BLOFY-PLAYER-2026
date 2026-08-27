package tv.blofy.player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ready-state cache layered on top of the persistent SQLite catalog.
 * SQLite remains the source of truth across app restarts; this cache keeps the
 * first screen and recently visited category pages hot so large playlists do not
 * block TV navigation on repeated queries.
 */
final class CatalogUiCache {
    private static final Object LOCK = new Object();
    private static final int CATEGORY_CACHE_LIMIT = 36;
    private static String sourceId = "";
    private static final Map<String, Integer> counts = new LinkedHashMap<>();
    private static final Map<String, List<BlofyModels.Category>> categories = new LinkedHashMap<>();
    private static final Map<String, List<BlofyModels.Media>> firstPages = new LinkedHashMap<>();
    private static final LinkedHashMap<String, List<BlofyModels.Media>> categoryPages =
            new LinkedHashMap<String, List<BlofyModels.Media>>(48, .75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, List<BlofyModels.Media>> eldest) {
                    return size() > CATEGORY_CACHE_LIMIT;
                }
            };

    private CatalogUiCache() {}

    static void warm(CatalogDatabase database) {
        if (database == null) return;
        String source = database.activeSource();
        Map<String, Integer> nextCounts = new LinkedHashMap<>();
        Map<String, List<BlofyModels.Category>> nextCategories = new LinkedHashMap<>();
        Map<String, List<BlofyModels.Media>> nextPages = new LinkedHashMap<>();
        LinkedHashMap<String, List<BlofyModels.Media>> nextCategoryPages = new LinkedHashMap<>();
        for (String type : new String[]{"live", "movies", "series"}) {
            nextCounts.put(type, database.count(type));
            List<BlofyModels.Category> typeCategories = new ArrayList<>(database.categories(type));
            nextCategories.put(type, typeCategories);
            int limit = "live".equals(type) ? 140 : 80;
            nextPages.put(type, new ArrayList<>(database.media(type, "", "", false, false, limit, 0)));

            // Warm the first few category pages only. This gives instant entry for
            // common TV navigation without exploding RAM on huge IPTV libraries.
            int categoryWarmCount = Math.min(typeCategories.size(), "live".equals(type) ? 8 : 5);
            int categoryLimit = "live".equals(type) ? 100 : 60;
            for (int index = 0; index < categoryWarmCount; index++) {
                BlofyModels.Category category = typeCategories.get(index);
                nextCategoryPages.put(key(type, category.id), new ArrayList<>(
                        database.media(type, category.id, "", false, false, categoryLimit, 0)));
            }
        }
        synchronized (LOCK) {
            sourceId = source;
            counts.clear();
            counts.putAll(nextCounts);
            categories.clear();
            categories.putAll(nextCategories);
            firstPages.clear();
            firstPages.putAll(nextPages);
            categoryPages.clear();
            categoryPages.putAll(nextCategoryPages);
        }
    }

    static int count(CatalogDatabase database, String type) {
        synchronized (LOCK) {
            if (matches(database)) {
                Integer value = counts.get(type);
                if (value != null) return value;
            }
        }
        return database.count(type);
    }

    static List<BlofyModels.Category> categories(CatalogDatabase database, String type) {
        synchronized (LOCK) {
            if (matches(database)) {
                List<BlofyModels.Category> value = categories.get(type);
                if (value != null) return new ArrayList<>(value);
            }
        }
        return database.categories(type);
    }

    static List<BlofyModels.Media> firstPage(CatalogDatabase database, String type, int limit) {
        synchronized (LOCK) {
            if (!matches(database)) return Collections.emptyList();
            return slice(firstPages.get(type), limit);
        }
    }

    static List<BlofyModels.Media> categoryPage(CatalogDatabase database, String type,
                                                 String categoryId, int limit) {
        if (database == null || categoryId == null || categoryId.isEmpty()) {
            return firstPage(database, type, limit);
        }
        String cacheKey = key(type, categoryId);
        synchronized (LOCK) {
            if (matches(database)) {
                List<BlofyModels.Media> value = categoryPages.get(cacheKey);
                if (value != null && !value.isEmpty()) return slice(value, limit);
            }
        }
        List<BlofyModels.Media> loaded = new ArrayList<>(
                database.media(type, categoryId, "", false, false, Math.max(1, limit), 0));
        synchronized (LOCK) {
            if (matches(database) && !loaded.isEmpty()) categoryPages.put(cacheKey, loaded);
        }
        return loaded;
    }

    static void rememberCategoryPage(CatalogDatabase database, String type, String categoryId,
                                     List<BlofyModels.Media> rows) {
        if (database == null || categoryId == null || categoryId.isEmpty() || rows == null || rows.isEmpty()) return;
        synchronized (LOCK) {
            if (matches(database)) categoryPages.put(key(type, categoryId), new ArrayList<>(rows));
        }
    }

    static void invalidate() {
        synchronized (LOCK) {
            sourceId = "";
            counts.clear();
            categories.clear();
            firstPages.clear();
            categoryPages.clear();
        }
    }

    private static List<BlofyModels.Media> slice(List<BlofyModels.Media> value, int limit) {
        if (value == null || value.isEmpty()) return Collections.emptyList();
        int end = Math.min(Math.max(1, limit), value.size());
        return new ArrayList<>(value.subList(0, end));
    }

    private static String key(String type, String categoryId) {
        return (type == null ? "" : type) + "|" + (categoryId == null ? "" : categoryId);
    }

    private static boolean matches(CatalogDatabase database) {
        return database != null && !sourceId.isEmpty() && sourceId.equals(database.activeSource());
    }
}
