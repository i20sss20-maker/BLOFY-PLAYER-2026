package tv.blofy.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Durable playlist cache used by the TV hub.
 *
 * The portal/server remains authoritative when it exposes /api/device/playlists.
 * A small encrypted local copy keeps the hub usable during a cold start and also
 * provides a backward-compatible path while an older BLOFY server only exposes
 * /api/session. Provider passwords are never written as clear text.
 */
final class PlaylistStore {
    private static final String PREFS = "blofy_playlist_hub";
    private static final String KEY_PAYLOAD = "encrypted_playlists_v1";
    private static final String KEY_ACTIVE = "active_playlist_id";
    private static final String KEY_REVISION = "remote_revision";
    private static final String KEY_ALIAS = "tv.blofy.player.playlists.v1";

    private final SharedPreferences preferences;

    PlaylistStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    synchronized List<Playlist> all() {
        List<Playlist> result = new ArrayList<>();
        try {
            String plain = decrypt(preferences.getString(KEY_PAYLOAD, ""));
            JSONArray rows = plain.isEmpty() ? new JSONArray() : new JSONArray(plain);
            String activeId = activeId();
            for (int index = 0; index < rows.length(); index++) {
                JSONObject row = rows.optJSONObject(index);
                if (row == null) continue;
                Playlist playlist = Playlist.fromJson(row);
                playlist.active = playlist.id.equals(activeId);
                result.add(playlist);
            }
        } catch (Exception ignored) {
            // A corrupt/invalidated keystore entry must not prevent the app opening.
        }
        return result;
    }

    synchronized Playlist find(String id) {
        for (Playlist item : all()) if (item.id.equals(id)) return item;
        return null;
    }

    synchronized Playlist saveLocal(Playlist value) {
        List<Playlist> rows = all();
        Playlist saved = value.copy();
        if (saved.id.isEmpty()) saved.id = "local-" + UUID.randomUUID();
        saved.remote = !saved.id.startsWith("local-") && !"current-session".equals(saved.id);
        boolean replaced = false;
        for (int index = 0; index < rows.size(); index++) {
            if (!rows.get(index).id.equals(saved.id)) continue;
            rows.set(index, saved);
            replaced = true;
            break;
        }
        if (!replaced) rows.add(saved);
        write(rows);
        return saved;
    }

    synchronized void delete(String id) {
        List<Playlist> rows = all();
        // Collection.removeIf was added in Android 7.0. Keep the hub safe on
        // the app's minSdk 23 televisions as well.
        for (int index = rows.size() - 1; index >= 0; index--) {
            if (rows.get(index).id.equals(id)) rows.remove(index);
        }
        write(rows);
        if (id != null && id.equals(activeId())) clearActive();
    }

    synchronized void setActive(String id) {
        preferences.edit().putString(KEY_ACTIVE, id == null ? "" : id).apply();
    }

    synchronized void clearActive() {
        preferences.edit().remove(KEY_ACTIVE).apply();
    }

    synchronized String activeId() {
        String value = preferences.getString(KEY_ACTIVE, "");
        return value == null ? "" : value;
    }

    synchronized int revision() { return preferences.getInt(KEY_REVISION, 0); }

    /** Merge public server rows without erasing locally encrypted fallback secrets. */
    synchronized void applyRemote(JSONObject response) {
        JSONArray remoteRows = response == null ? null : response.optJSONArray("playlists");
        if (remoteRows == null) return;
        List<Playlist> current = all();
        List<Playlist> merged = new ArrayList<>();
        for (int index = 0; index < remoteRows.length(); index++) {
            JSONObject row = remoteRows.optJSONObject(index);
            if (row == null) continue;
            Playlist remote = Playlist.fromPublicJson(row);
            Playlist local = find(current, remote.id);
            if (local != null) {
                remote.serverUrl = local.serverUrl;
                remote.username = local.username;
                remote.password = local.password;
                remote.url = local.url;
            }
            merged.add(remote);
        }
        // Retain local-only rows created while a legacy/offline server was in use.
        for (Playlist local : current) {
            if (!local.remote && find(merged, local.id) == null) merged.add(local);
        }
        String defaultId = response.optString("defaultPlaylistId", "");
        if (!defaultId.isEmpty()) for (Playlist item : merged) item.isDefault = defaultId.equals(item.id);
        write(merged);
        preferences.edit().putInt(KEY_REVISION, response.optInt("revision", revision())).apply();
    }

    private static Playlist find(List<Playlist> rows, String id) {
        for (Playlist item : rows) if (item.id.equals(id)) return item;
        return null;
    }

    private void write(List<Playlist> rows) {
        try {
            JSONArray values = new JSONArray();
            for (Playlist row : rows) values.put(row.toJson());
            String payload = encrypt(values.toString());
            if (!preferences.edit().putString(KEY_PAYLOAD, payload).commit()) {
                throw new IllegalStateException("تعذر تثبيت قائمة التشغيل على ذاكرة الجهاز.");
            }
        } catch (Exception failure) {
            // Preserve a previously valid cache, but never report a false success.
            if (failure instanceof IllegalStateException) throw (IllegalStateException) failure;
            throw new IllegalStateException(
                    "تعذر حفظ قائمة التشغيل بأمان. أعد تشغيل الجهاز ثم حاول مرة أخرى.", failure);
        }
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }

    static final class Playlist {
        String id = "";
        String name = "";
        String kind = "xtream";
        String serverName = "";
        String status = "unknown";
        long lastTestedAt;
        long updatedAt;
        boolean remote;
        boolean active;
        boolean isDefault;
        boolean currentSessionOnly;
        // Encrypted local fallback. Public server responses intentionally omit these.
        String serverUrl = "";
        String username = "";
        String password = "";
        String url = "";

        Playlist copy() {
            Playlist value = new Playlist();
            value.id = id; value.name = name; value.kind = kind; value.serverName = serverName;
            value.status = status; value.lastTestedAt = lastTestedAt; value.updatedAt = updatedAt;
            value.remote = remote; value.active = active; value.isDefault = isDefault;
            value.currentSessionOnly = currentSessionOnly;
            value.serverUrl = serverUrl; value.username = username; value.password = password; value.url = url;
            return value;
        }

        static Playlist fromSession(BlofyModels.Session session) {
            Playlist value = new Playlist();
            value.id = "current-session";
            value.name = session == null || session.name.isEmpty() ? "قائمتي الحالية" : session.name;
            value.kind = session == null || session.kind.isEmpty() ? "xtream" : session.kind;
            value.serverName = session == null ? "" : session.serverName;
            value.status = "active";
            value.active = true;
            value.currentSessionOnly = true;
            return value;
        }

        static Playlist fromPublicJson(JSONObject row) {
            Playlist value = new Playlist();
            value.id = clean(row.optString("id", ""));
            value.name = clean(row.optString("name", ""));
            value.kind = clean(row.optString("kind", "xtream"));
            value.serverName = clean(row.optString("serverName", ""));
            value.status = clean(row.optString("status", "unknown"));
            value.lastTestedAt = row.optLong("lastTestedAt", 0);
            value.updatedAt = row.optLong("updatedAt", 0);
            value.isDefault = row.optBoolean("isDefault", false);
            value.remote = true;
            return value;
        }

        static Playlist fromJson(JSONObject row) {
            Playlist value = fromPublicJson(row);
            value.remote = row.optBoolean("remote", value.remote);
            value.serverUrl = clean(row.optString("serverUrl", ""));
            value.username = clean(row.optString("username", ""));
            value.password = row.optString("password", "");
            value.url = clean(row.optString("url", ""));
            return value;
        }

        JSONObject toJson() {
            JSONObject row = new JSONObject();
            try {
                row.put("id", id).put("name", name).put("kind", kind)
                        .put("serverName", serverName).put("status", status)
                        .put("lastTestedAt", lastTestedAt).put("updatedAt", updatedAt)
                        .put("remote", remote).put("isDefault", isDefault).put("serverUrl", serverUrl)
                        .put("username", username).put("password", password).put("url", url);
            } catch (Exception ignored) {}
            return row;
        }

        boolean canConnectLocally() {
            return "m3u".equals(kind) ? !url.isEmpty()
                    : !serverUrl.isEmpty() && !username.isEmpty() && !password.isEmpty();
        }

        String displayName() { return name.isEmpty() ? "قائمة بدون اسم" : name; }
        String kindLabel() { return "m3u".equals(kind) ? "M3U / M3U8" : "Xtream Codes"; }

        JSONObject sessionBody() {
            JSONObject body = new JSONObject();
            try {
                body.put("kind", kind).put("name", name);
                if ("m3u".equals(kind)) body.put("url", url);
                else body.put("serverUrl", serverUrl).put("username", username).put("password", password);
            } catch (Exception ignored) {}
            return body;
        }
    }

    private static String encrypt(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        return "v1." + Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + "."
                + Base64.encodeToString(encrypted, Base64.NO_WRAP);
    }

    private static String decrypt(String value) throws Exception {
        if (value == null || value.isEmpty()) return "";
        String[] parts = value.split("\\.", 3);
        if (parts.length != 3 || !"v1".equals(parts[0])) return "";
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128,
                Base64.decode(parts[1], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[2], Base64.NO_WRAP)),
                StandardCharsets.UTF_8);
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        java.security.Key existing = store.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }
}
