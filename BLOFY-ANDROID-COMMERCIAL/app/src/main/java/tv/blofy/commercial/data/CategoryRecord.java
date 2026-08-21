package tv.blofy.commercial.data;

public final class CategoryRecord {
    public final String id;
    public final String name;
    public final int count;

    public CategoryRecord(String id, String name, int count) {
        this.id = id == null ? "" : id;
        this.name = name == null || name.trim().isEmpty() ? "غير مصنف" : name.trim();
        this.count = Math.max(0, count);
    }
}
