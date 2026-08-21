package tv.blofy.commercial.data;

import org.json.JSONObject;

public final class MediaRecord {
    public final String type, id, name, image, backdrop, categoryId, rating, year, extension;

    public MediaRecord(String type, String id, String name, String image, String backdrop,
                       String categoryId, String rating, String year, String extension) {
        this.type = safe(type); this.id = safe(id); this.name = safe(name); this.image = safe(image);
        this.backdrop = safe(backdrop); this.categoryId = safe(categoryId); this.rating = safe(rating);
        this.year = safe(year); this.extension = safe(extension);
    }

    public static MediaRecord from(JSONObject row, String type) {
        return new MediaRecord(row.optString("type", type), row.optString("id"), row.optString("name"),
                row.optString("image"), row.optString("backdrop"), row.optString("categoryId"),
                row.optString("rating"), row.optString("year"), row.optString("extension"));
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
