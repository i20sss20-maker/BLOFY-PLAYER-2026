package tv.blofy.player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Small in-process ready-state cache for the first screen of each catalog.
 * PackageImporter warms this before reporting 100%, so SevenMaxActivity can
 * render immediately without blocking the UI thread on SQLite.
 */
final class CatalogUiCache {
    private static final Object LOCK = new Object();
    private static String sourceId = "";
    private static final Map<String, Integer> counts = new HashMap<>();
    private static final Map<String, List<BlofyModels.Category>> categories = new HashMap<>();
    private static final Map<String, List<BlofyModels.Media>> firstPages = new HashMap<>();

    private CatalogUiCache() {}

    static void warm(CatalogDatabase database) {
        if (database == null) return;
        String source = database.activeSource();
        Map<String, Integer> nextCounts = new HashMap<>();
        Map<String, List<BlofyModels.Category>> nextCategories = new HashMap<>();
        Map<String, List<BlofyModels.Media>> nextPages = new HashMap<>();
        for (String type : new String[]{"live", "movies", "series"}) {
            nextCounts.put(type, database.count(type));
            nextCategories.put(type, new ArrayList<>(database.categories(type)));
            int limit = "live".equals(type) ? 140 : 80;
            nextPages.put(type, new ArrayList<>(database.media(type, "", "", false, false, limit, 0)));
        }
        synchronized (LOCK) {
            sourceId = source;
            counts.clear();
            counts.putAll(nextCounts);
            categories.clear();
            categories.putAll(nextCategories);
            firstPages.clear();
            firstPages.putAll(nextPages);
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
            List<BlofyModels.Media> value = firstPages.get(type);
            if (value == null || value.isEmpty()) return Collections.emptyList();
            int end = Math.min(Math.max(1, limit), value.size());
            return new ArrayList<>(value.subList(0, end));
        }
    }

    static void invalidate() {
        synchronized (LOCK) {
            sourceId = "";
            counts.clear();
            categories.clear();
            firstPages.clear();
        }
    }

    private static boolean matches(CatalogDatabase database) {
        return database != null && !sourceId.isEmpty() && sourceId.equals(database.activeSource());
    }
}
