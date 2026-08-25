import { fetchSafe, readTextLimited } from "./security.mjs";

const apiRoot = "https://api.themoviedb.org/3";
const imageRoot = "https://image.tmdb.org/t/p";

function configured() {
  return Boolean(String(process.env.TMDB_BEARER_TOKEN || process.env.TMDB_API_KEY || "").trim());
}

function headers() {
  const token = String(process.env.TMDB_BEARER_TOKEN || "").trim();
  return token ? { accept: "application/json", authorization: `Bearer ${token}` }
    : { accept: "application/json" };
}

function endpoint(pathname, query = {}) {
  const url = new URL(`${apiRoot}${pathname}`);
  const key = String(process.env.TMDB_API_KEY || "").trim();
  if (key) url.searchParams.set("api_key", key);
  for (const [name, value] of Object.entries(query)) if (value !== "" && value != null) {
    url.searchParams.set(name, String(value));
  }
  return url.toString();
}

async function request(pathname, query) {
  const response = await fetchSafe(endpoint(pathname, query), {
    headers: headers(), requestTimeoutMs: 7_000,
  });
  if (!response.ok) throw new Error(`TMDB ${response.status}`);
  return JSON.parse(await readTextLimited(response, 4_000_000, 7_000));
}

function year(value) {
  return String(value || "").match(/(?:19|20)\d{2}/)?.[0] || "";
}

function image(path, size) {
  const value = String(path || "");
  return value ? (value.startsWith("http") ? value : `${imageRoot}/${size}${value}`) : "";
}

function cleanTitle(value) {
  return String(value || "")
    .replace(/\b(?:4K|UHD|FHD|HD|HEVC|H\.265|MULTI|ARABIC|مترجم)\b/gi, " ")
    .replace(/[\[\](){}|]+/g, " ").replace(/\s+/g, " ").trim();
}

function ratingRows(original, score) {
  const rows = Array.isArray(original) ? [...original] : [];
  if (Number(score) > 0 && !rows.some((row) => String(row?.source || "").toLowerCase() === "tmdb")) {
    rows.push({ source: "TMDB", value: String(Math.round(Number(score) * 10) / 10) });
  }
  return rows;
}

/** Optional Arabic metadata enrichment. It is a no-op until a TMDB key is configured. */
export async function enrichMediaDetails(item, type) {
  if (!configured() || !item) return item;
  const mediaType = type === "series" ? "tv" : "movie";
  let tmdbId = String(item.tmdbId || "").trim();
  if (!tmdbId) {
    const query = cleanTitle(item.name);
    if (!query) return item;
    const search = await request(`/search/${mediaType}`, {
      query, language: "ar-SA", include_adult: "false",
      ...(mediaType === "movie" ? { year: year(item.releaseDate || item.year) }
        : { first_air_date_year: year(item.releaseDate || item.year) }),
    });
    tmdbId = String(search?.results?.[0]?.id || "");
  }
  if (!tmdbId) return item;
  const detail = await request(`/${mediaType}/${encodeURIComponent(tmdbId)}`, {
    language: "ar-SA", append_to_response: "credits,external_ids",
  });
  const cast = Array.isArray(detail?.credits?.cast) ? detail.credits.cast.slice(0, 24).map((person) => ({
    name: String(person.name || person.original_name || ""),
    character: String(person.character || ""),
    image: image(person.profile_path, "w185"),
  })).filter((person) => person.name) : [];
  const releaseDate = String(detail.release_date || detail.first_air_date || item.releaseDate || "");
  return {
    ...item,
    name: String(detail.title || detail.name || item.name || ""),
    description: String(detail.overview || item.description || ""),
    image: image(detail.poster_path, "w500") || item.image || "",
    backdrop: image(detail.backdrop_path, "w1280") || item.backdrop || "",
    releaseDate,
    year: year(releaseDate) || item.year || "",
    genre: Array.isArray(detail.genres) && detail.genres.length
      ? detail.genres.map((entry) => entry.name).filter(Boolean).join("، ") : item.genre || "",
    rating: Number(detail.vote_average) > 0
      ? String(Math.round(Number(detail.vote_average) * 10) / 10) : item.rating || "",
    ratingSource: Number(detail.vote_average) > 0 ? "TMDB" : item.ratingSource || "",
    ratings: ratingRows(item.ratings, detail.vote_average),
    cast: cast.length ? cast : item.cast || [],
    tmdbId,
    imdbId: String(detail.external_ids?.imdb_id || item.imdbId || ""),
  };
}
