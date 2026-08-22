package tv.blofy.commercial.provider;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Local playlist repository. Supports multiple accounts and one active playlist. */
public final class PlaylistRepository {
    private static final String PREFS = "blofy_playlists";
    private static final String KEY_ITEMS = "items";
    private static final String KEY_ACTIVE = "active";

    private PlaylistRepository() { }

    public static List<PlaylistProfile> all(Context context) {
        String raw = prefs(context).getString(KEY_ITEMS, "[]");
        List<PlaylistProfile> out = new ArrayList<>();
        try {
            JSONArray rows = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < rows.length(); i++) {
                PlaylistProfile row = PlaylistProfile.fromJson(rows.optJSONObject(i));
                if (row != null && row.provider != null && row.provider.isValid()) out.add(row);
            }
        } catch (Exception ignored) { }
        return Collections.unmodifiableList(out);
    }

    public static PlaylistProfile active(Context context) {
        String id = prefs(context).getString(KEY_ACTIVE, "");
        List<PlaylistProfile> rows = all(context);
        for (PlaylistProfile row : rows) if (row.id.equals(id)) return row;
        return rows.isEmpty() ? null : rows.get(0);
    }

    public static PlaylistProfile upsert(Context context, PlaylistProfile value, boolean makeActive) {
        if (value == null || value.provider == null || !value.provider.isValid()) return null;
        List<PlaylistProfile> rows = new ArrayList<>(all(context));
        int index = -1;
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).id.equals(value.id)) { index = i; break; }
        if (index >= 0) rows.set(index, value); else rows.add(value);
        write(context, rows);
        if (makeActive) setActive(context, value.id);
        return value;
    }

    public static PlaylistProfile importLegacySingleProfile(Context context) {
        if (!all(context).isEmpty()) return active(context);
        ProviderProfile legacy = ProviderProfileStore.load(context);
        if (legacy == null) return null;
        PlaylistProfile created = PlaylistProfile.create(legacy);
        upsert(context, created, true);
        return created;
    }

    public static void setActive(Context context, String id) {
        if (id == null || id.trim().isEmpty()) return;
        for (PlaylistProfile row : all(context)) {
            if (row.id.equals(id.trim())) {
                prefs(context).edit().putString(KEY_ACTIVE, row.id).apply();
                ProviderProfileStore.save(context, row.provider); // compatibility bridge during migration
                return;
            }
        }
    }

    public static void remove(Context context, String id) {
        if (id == null || id.trim().isEmpty()) return;
        List<PlaylistProfile> rows = new ArrayList<>();
        for (PlaylistProfile row : all(context)) if (!row.id.equals(id.trim())) rows.add(row);
        write(context, rows);
        String active = prefs(context).getString(KEY_ACTIVE, "");
        if (id.trim().equals(active)) {
            prefs(context).edit().putString(KEY_ACTIVE, rows.isEmpty() ? "" : rows.get(0).id).apply();
            if (!rows.isEmpty()) ProviderProfileStore.save(context, rows.get(0).provider);
        }
        CompatibilityProfileStore.remove(context, id.trim());
    }

    private static void write(Context context, List<PlaylistProfile> rows) {
        JSONArray out = new JSONArray();
        for (PlaylistProfile row : rows) out.put(row.toJson());
        prefs(context).edit().putString(KEY_ITEMS, out.toString()).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
