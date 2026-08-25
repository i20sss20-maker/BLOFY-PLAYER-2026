package tv.blofy.player;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class BlofyModels {
    private BlofyModels() {}

    static String string(JSONObject object, String key) {
        if (object == null || object.isNull(key)) return "";
        return object.optString(key, "");
    }

    static String first(JSONObject object, String... keys) {
        if (object == null || keys == null) return "";
        for (String key : keys) {
            String value = string(object, key).trim();
            if (!value.isEmpty() && !"null".equalsIgnoreCase(value)) return value;
        }
        return "";
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
        final String releaseDate;
        final String ratingSource;
        final String updatedAt;

        Media(String id, String name, String image, String backdrop, String categoryId,
              String rating, String year, String extension, String type) {
            this(id, name, image, backdrop, categoryId, rating, year, extension, type,
                    "", "", "");
        }

        Media(String id, String name, String image, String backdrop, String categoryId,
              String rating, String year, String extension, String type,
              String releaseDate, String ratingSource, String updatedAt) {
            this.id = id;
            this.name = name;
            this.image = image;
            this.backdrop = backdrop;
            this.categoryId = categoryId;
            this.rating = rating;
            this.year = year;
            this.extension = extension;
            this.type = type;
            this.releaseDate = releaseDate == null ? "" : releaseDate;
            this.ratingSource = ratingSource == null ? "" : ratingSource;
            this.updatedAt = updatedAt == null ? "" : updatedAt;
        }

        static Media from(JSONObject row, String fallbackType) {
            return new Media(
                    string(row, "id"), string(row, "name"), string(row, "image"),
                    string(row, "backdrop"), string(row, "categoryId"), string(row, "rating"),
                    string(row, "year"), string(row, "extension"),
                    string(row, "type").isEmpty() ? fallbackType : string(row, "type"),
                    first(row, "releaseDate", "release_date", "airDate", "air_date", "lastAirDate", "last_air_date"),
                    first(row, "ratingSource", "rating_source", "voteSource", "vote_source"),
                    first(row, "updatedAt", "updated_at", "addedAt", "added_at", "dateAdded", "date_added", "added"));
        }

        JSONObject json() {
            JSONObject value = new JSONObject();
            try {
                value.put("id", id).put("name", name).put("image", image).put("backdrop", backdrop)
                        .put("categoryId", categoryId).put("rating", rating).put("year", year)
                        .put("extension", extension).put("type", type)
                        .put("releaseDate", releaseDate).put("ratingSource", ratingSource)
                        .put("updatedAt", updatedAt);
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
        final String airDate;

        Episode(JSONObject row) {
            id = string(row, "id");
            number = row == null ? 0 : row.optInt("number", 0);
            title = string(row, "title");
            extension = string(row, "extension");
            duration = string(row, "duration");
            image = string(row, "image");
            airDate = first(row, "airDate", "air_date", "releaseDate", "release_date", "date");
        }
    }

    static final class Actor {
        final String name;
        final String character;
        final String image;

        Actor(String name, String character, String image) {
            this.name = name == null ? "" : name;
            this.character = character == null ? "" : character;
            this.image = image == null ? "" : image;
        }
    }

    static final class Rating {
        final String source;
        final String value;

        Rating(String source, String value) {
            this.source = source == null ? "" : source;
            this.value = value == null ? "" : value;
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
        final String releaseDate;
        final String ratingSource;
        final String updatedAt;
        final List<Season> seasons = new ArrayList<>();
        final List<Actor> cast = new ArrayList<>();
        final List<Rating> ratings = new ArrayList<>();

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
            releaseDate = first(data, "releaseDate", "release_date", "airDate", "air_date", "lastAirDate", "last_air_date");
            ratingSource = first(data, "ratingSource", "rating_source", "voteSource", "vote_source");
            updatedAt = first(data, "updatedAt", "updated_at", "addedAt", "added_at", "dateAdded", "date_added", "added");
            JSONArray values = data == null ? null : data.optJSONArray("seasons");
            if (values != null) for (int index = 0; index < values.length(); index++) {
                JSONObject season = values.optJSONObject(index);
                if (season != null) seasons.add(new Season(season));
            }
            parseCast(data, cast);
            parseRatings(data, rating, ratingSource, ratings);
        }
    }

    private static void parseCast(JSONObject data, List<Actor> result) {
        if (data == null) return;
        JSONArray rows = data.optJSONArray("cast");
        if (rows == null) rows = data.optJSONArray("actors");
        JSONObject credits = data.optJSONObject("credits");
        if (rows == null && credits != null) rows = credits.optJSONArray("cast");
        Set<String> seen = new LinkedHashSet<>();
        if (rows != null) {
            for (int index = 0; index < rows.length() && result.size() < 24; index++) {
                Object value = rows.opt(index);
                String name;
                String character = "";
                String image = "";
                if (value instanceof JSONObject) {
                    JSONObject actor = (JSONObject) value;
                    name = first(actor, "name", "original_name", "actor", "title");
                    character = first(actor, "character", "role", "known_for_department");
                    image = first(actor, "image", "profile", "profilePath", "profile_path", "photo");
                } else {
                    name = value == null ? "" : String.valueOf(value).trim();
                }
                String key = name.toLowerCase(java.util.Locale.US);
                if (!name.isEmpty() && seen.add(key)) result.add(new Actor(name, character, image));
            }
        }
        if (!result.isEmpty()) return;
        String flat = "";
        String[] flatKeys = {"castText", "cast_text", "actorsText", "actors_text", "cast", "actors"};
        for (String key : flatKeys) {
            Object raw = data.opt(key);
            if (raw instanceof String && !((String) raw).trim().isEmpty()) {
                flat = ((String) raw).trim();
                break;
            }
        }
        if (flat.isEmpty()) return;
        for (String name : flat.split("[,،|]")) {
            String clean = name.trim();
            if (!clean.isEmpty() && result.size() < 24) result.add(new Actor(clean, "", ""));
        }
    }

    private static void parseRatings(JSONObject data, String fallbackValue, String fallbackSource,
                                     List<Rating> result) {
        if (data == null) return;
        Set<String> seen = new LinkedHashSet<>();
        Object raw = data.opt("ratings");
        if (raw instanceof JSONArray) {
            JSONArray values = (JSONArray) raw;
            for (int index = 0; index < values.length() && result.size() < 6; index++) {
                JSONObject row = values.optJSONObject(index);
                if (row == null) continue;
                addRating(result, seen, first(row, "source", "name", "site"),
                        first(row, "value", "rating", "score"));
            }
        } else if (raw instanceof JSONObject) {
            JSONObject values = (JSONObject) raw;
            JSONArray names = values.names();
            if (names != null) for (int index = 0; index < names.length() && result.size() < 6; index++) {
                String source = names.optString(index, "");
                Object value = values.opt(source);
                String ratingValue = value instanceof JSONObject
                        ? first((JSONObject) value, "value", "rating", "score")
                        : (value == null ? "" : String.valueOf(value));
                addRating(result, seen, source, ratingValue);
            }
        }
        addRating(result, seen, "IMDb", first(data, "imdbRating", "imdb_rating"));
        addRating(result, seen, "TMDB", first(data, "tmdbRating", "tmdb_rating", "vote_average"));
        addRating(result, seen, "Rotten Tomatoes", first(data, "rottenTomatoesRating", "rotten_tomatoes_rating"));
        addRating(result, seen, fallbackSource.isEmpty() ? "المصدر" : fallbackSource, fallbackValue);
    }

    private static void addRating(List<Rating> result, Set<String> seen, String source, String value) {
        String cleanValue = value == null ? "" : value.trim();
        if (cleanValue.isEmpty() || "0".equals(cleanValue) || "0.0".equals(cleanValue)) return;
        String cleanSource = source == null || source.trim().isEmpty() ? "المصدر" : source.trim();
        String key = cleanSource.toLowerCase(java.util.Locale.US);
        if (seen.add(key)) result.add(new Rating(cleanSource, cleanValue));
    }
}
