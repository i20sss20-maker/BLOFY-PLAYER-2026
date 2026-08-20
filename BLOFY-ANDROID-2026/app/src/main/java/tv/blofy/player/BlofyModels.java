package tv.blofy.player;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class BlofyModels {
    private BlofyModels() {}

    static String string(JSONObject object, String key) {
        if (object == null || object.isNull(key)) return "";
        return object.optString(key, "");
    }

    static final class License {
        final String plan;
        final String status;
        final long expiresAt;
        final int remainingDays;
        final String activationUrl;

        License(JSONObject data) {
            plan = string(data, "plan");
            status = string(data, "status");
            expiresAt = data == null ? 0 : data.optLong("expiresAt", 0);
            remainingDays = data == null ? 0 : data.optInt("remainingDays", 0);
            activationUrl = string(data, "activationUrl");
        }

        boolean usable() { return "trial".equals(plan) || "active".equals(plan); }
    }

    static final class Session {
        final boolean present;
        final String kind;
        final String name;
        final String serverName;
        final JSONObject account;

        Session(JSONObject response) {
            JSONObject data = response == null ? null : response.optJSONObject("session");
            present = data != null;
            kind = string(data, "kind");
            name = string(data, "name");
            serverName = string(data, "serverName");
            account = data == null ? null : data.optJSONObject("account");
        }
    }

    static final class Category {
        final String id;
        final String name;
        final String type;

        Category(String id, String name, String type) {
            this.id = id;
            this.name = name;
            this.type = type;
        }

        static List<Category> list(JSONObject response, String type) {
            List<Category> result = new ArrayList<>();
            JSONArray rows = response == null ? null : response.optJSONArray("categories");
            if (rows == null) return result;
            for (int index = 0; index < rows.length(); index++) {
                JSONObject row = rows.optJSONObject(index);
                if (row != null) result.add(new Category(string(row, "id"), string(row, "name"), type));
            }
            return result;
        }
    }

    static final class Media {
        final String id;
        final String name;
        final String image;
        final String backdrop;
        final String categoryId;
        final String rating;
        final String year;
        final String extension;
        final String type;

        Media(String id, String name, String image, String backdrop, String categoryId,
              String rating, String year, String extension, String type) {
            this.id = id;
            this.name = name;
            this.image = image;
            this.backdrop = backdrop;
            this.categoryId = categoryId;
            this.rating = rating;
            this.year = year;
            this.extension = extension;
            this.type = type;
        }

        static Media from(JSONObject row, String fallbackType) {
            return new Media(
                    string(row, "id"), string(row, "name"), string(row, "image"),
                    string(row, "backdrop"), string(row, "categoryId"), string(row, "rating"),
                    string(row, "year"), string(row, "extension"),
                    string(row, "type").isEmpty() ? fallbackType : string(row, "type"));
        }

        JSONObject json() {
            JSONObject value = new JSONObject();
            try {
                value.put("id", id).put("name", name).put("image", image).put("backdrop", backdrop)
                        .put("categoryId", categoryId).put("rating", rating).put("year", year)
                        .put("extension", extension).put("type", type);
            } catch (Exception ignored) {}
            return value;
        }

        static List<Media> list(JSONObject response, String type) {
            List<Media> result = new ArrayList<>();
            JSONArray rows = response == null ? null : response.optJSONArray("items");
            if (rows == null) return result;
            for (int index = 0; index < rows.length(); index++) {
                JSONObject row = rows.optJSONObject(index);
                if (row != null) result.add(from(row, type));
            }
            return result;
        }
    }

    static final class Episode {
        final String id;
        final int number;
        final String title;
        final String extension;
        final String duration;
        final String image;

        Episode(JSONObject row) {
            id = string(row, "id");
            number = row == null ? 0 : row.optInt("number", 0);
            title = string(row, "title");
            extension = string(row, "extension");
            duration = string(row, "duration");
            image = string(row, "image");
        }
    }

    static final class Season {
        final String number;
        final List<Episode> episodes = new ArrayList<>();

        Season(JSONObject row) {
            number = string(row, "season");
            JSONArray values = row == null ? null : row.optJSONArray("episodes");
            if (values != null) for (int index = 0; index < values.length(); index++) {
                JSONObject episode = values.optJSONObject(index);
                if (episode != null) episodes.add(new Episode(episode));
            }
        }
    }

    static final class Detail {
        final String id;
        final String name;
        final String description;
        final String image;
        final String backdrop;
        final String rating;
        final String year;
        final String duration;
        final String genre;
        final String extension;
        final String type;
        final List<Season> seasons = new ArrayList<>();

        Detail(JSONObject data, String fallbackType) {
            id = string(data, "id");
            name = string(data, "name");
            description = string(data, "description");
            image = string(data, "image");
            backdrop = string(data, "backdrop");
            rating = string(data, "rating");
            year = string(data, "year");
            duration = string(data, "duration");
            genre = string(data, "genre");
            extension = string(data, "extension");
            String readType = string(data, "type");
            type = readType.isEmpty() ? fallbackType : readType;
            JSONArray values = data == null ? null : data.optJSONArray("seasons");
            if (values != null) for (int index = 0; index < values.length(); index++) {
                JSONObject season = values.optJSONObject(index);
                if (season != null) seasons.add(new Season(season));
            }
        }
    }
}
