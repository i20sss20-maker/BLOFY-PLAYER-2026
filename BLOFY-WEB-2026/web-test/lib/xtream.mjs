import { fetchSafe, readTextLimited } from "./security.mjs";

const LARGE_CATALOG_ACTIONS = new Set(["get_live_streams", "get_vod_streams", "get_series"]);
let catalogQueue = Promise.resolve();

function boundedInteger(value, fallback, minimum, maximum) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? Math.min(maximum, Math.max(minimum, Math.trunc(parsed))) : fallback;
}

export function xtreamResponseLimits(action = "") {
  if (!LARGE_CATALOG_ACTIONS.has(action)) {
    return { catalog: false, maxBytes: 48_000_000, headerTimeoutMs: 9_000, idleTimeoutMs: 15_000, totalTimeoutMs: 0 };
  }
  return {
    catalog: true,
    maxBytes: boundedInteger(process.env.XTREAM_CATALOG_MAX_BYTES, 160_000_000, 48_000_000, 160_000_000),
    headerTimeoutMs: boundedInteger(process.env.XTREAM_CATALOG_HEADER_TIMEOUT_MS, 60_000, 9_000, 120_000),
    idleTimeoutMs: boundedInteger(process.env.XTREAM_CATALOG_IDLE_TIMEOUT_MS, 30_000, 10_000, 60_000),
    totalTimeoutMs: boundedInteger(process.env.XTREAM_CATALOG_TOTAL_TIMEOUT_MS, 120_000, 30_000, 180_000),
  };
}

async function serializeLargeCatalog(loader) {
  const previous = catalogQueue;
  let release;
  catalogQueue = new Promise((resolve) => { release = resolve; });
  await previous.catch(() => {});
  try { return await loader(); }
  finally { release(); }
}

function cleanBase(value) {
  const raw = String(value).trim();
  const url = new URL(raw);
  url.search = "";
  url.hash = "";
  url.pathname = url.pathname.replace(/\/(?:player_api|get)\.php\/?$/i, "").replace(/\/+$/, "");
  return url.toString().replace(/\/+$/, "");
}

export function normalizeDirectSource(value) {
  const raw = String(value || "").trim();
  if (!raw) return "";
  try {
    const url = new URL(raw);
    if (!["http:", "https:"].includes(url.protocol) || url.username || url.password) return "";
    return url.toString();
  } catch {
    return "";
  }
}

function cleanDate(...values) {
  for (const value of values) {
    const raw = String(value ?? "").trim();
    if (!raw || raw === "0") continue;
    if (/^(?:19|20)\d{2}$/.test(raw)) return raw;
    if (/^\d{10,13}$/.test(raw)) {
      const numeric = Number(raw) * (raw.length === 10 ? 1000 : 1);
      const date = new Date(numeric);
      if (Number.isFinite(date.getTime())) return date.toISOString().slice(0, 10);
    }
    const parsed = Date.parse(raw);
    if (Number.isFinite(parsed)) return new Date(parsed).toISOString().slice(0, 10);
  }
  return "";
}

function firstUseful(...values) {
  for (const value of values) {
    const clean = String(value ?? "").trim();
    if (clean && clean !== "null" && clean !== "undefined") return clean;
  }
  return "";
}

function firstRating(...values) {
  for (const value of values) {
    const clean = firstUseful(value);
    if (clean && !/^0(?:[.,]0+)?(?:\s*\/\s*(?:5|10|100))?$/.test(clean)) return clean;
  }
  return "";
}

function tenPointRating(value, fiveBased = false) {
  const raw = firstRating(value);
  if (!raw) return "";
  const match = raw.replace(",", ".").match(/\d+(?:\.\d+)?/);
  if (!match) return raw;
  let score = Number(match[0]);
  if (!Number.isFinite(score)) return raw;
  if (fiveBased || /\/\s*5\b/.test(raw)) score *= 2;
  else if (/%|\/\s*100\b/.test(raw) || score > 10) score /= 10;
  score = Math.min(10, Math.max(0, score));
  return String(Math.round(score * 10) / 10);
}

function primaryRating(info = {}) {
  const explicitSource = firstUseful(info.ratingSource, info.rating_source);
  const explicitRating = firstRating(info.rating);
  if (explicitSource && explicitRating) return { value: tenPointRating(explicitRating), source: explicitSource };
  const imdb = firstRating(info.imdb_rating, info.imdbRating);
  if (imdb) return { value: tenPointRating(imdb), source: "IMDb" };
  const tmdb = firstRating(info.tmdb_rating, info.tmdbRating, info.vote_average);
  if (tmdb) return { value: tenPointRating(tmdb), source: "TMDB" };
  if (explicitRating) return { value: tenPointRating(explicitRating), source: "مزود المحتوى" };
  const fiveBased = firstRating(info.rating_5based);
  if (fiveBased) return { value: tenPointRating(fiveBased, true), source: "مزود المحتوى" };
  return { value: "", source: "" };
}

function people(...values) {
  const rows = [];
  for (const value of values) {
    if (Array.isArray(value)) rows.push(...value);
    else if (value != null && typeof value !== "object") rows.push(...String(value).split(/[,،|]/));
  }
  const seen = new Set();
  return rows.map((entry) => {
    if (entry && typeof entry === "object") {
      const rawImage = firstUseful(entry.image, entry.profile, entry.profile_path, entry.profilePath, entry.photo);
      return {
        name: firstUseful(entry.name, entry.actor, entry.original_name, entry.title),
        character: firstUseful(entry.character, entry.role, entry.known_for_department),
        image: rawImage.startsWith("/") ? `https://image.tmdb.org/t/p/w185${rawImage}` : rawImage,
      };
    }
    return { name: String(entry || "").trim(), character: "", image: "" };
  }).filter((entry) => {
    const key = entry.name.toLocaleLowerCase("en");
    if (!entry.name || seen.has(key)) return false;
    seen.add(key);
    return true;
  }).slice(0, 24);
}

function ratingRows(info = {}) {
  const rows = [];
  const add = (source, value) => {
    const clean = firstRating(value);
    const cleanSource = firstUseful(source);
    if (!clean || !cleanSource) return;
    if (!rows.some((entry) => entry.source.toLocaleLowerCase("en") === cleanSource.toLocaleLowerCase("en"))) {
      rows.push({ source: cleanSource, value: clean });
    }
  };
  add("IMDb", firstRating(info.imdb_rating, info.imdbRating));
  add("TMDB", firstRating(info.tmdb_rating, info.tmdbRating, info.vote_average));
  add("Rotten Tomatoes", firstRating(info.rottenTomatoesRating, info.rotten_tomatoes_rating));
  const upstream = info.ratings;
  if (Array.isArray(upstream)) {
    for (const entry of upstream) if (entry && typeof entry === "object") {
      add(firstUseful(entry.source, entry.name, entry.site), firstRating(entry.value, entry.rating, entry.score));
    }
  } else if (upstream && typeof upstream === "object") {
    for (const [source, value] of Object.entries(upstream)) {
      add(source, value && typeof value === "object"
        ? firstRating(value.value, value.rating, value.score) : value);
    }
  }
  const provider = firstRating(info.rating);
  if (provider) add("مزود المحتوى", provider);
  else {
    const fiveBased = firstRating(info.rating_5based);
    if (fiveBased) add("مزود المحتوى", `${fiveBased}/5`);
  }
  return rows.slice(0, 6);
}

export class XtreamClient {
  constructor({ serverUrl, username, password }) {
    this.base = cleanBase(serverUrl);
    this.username = String(username);
    this.password = String(password);
  }

  apiUrl(action = "", params = {}) {
    const url = new URL(`${this.base}/player_api.php`);
    url.searchParams.set("username", this.username);
    url.searchParams.set("password", this.password);
    if (action) url.searchParams.set("action", action);
    for (const [key, value] of Object.entries(params)) if (value !== "" && value != null) url.searchParams.set(key, String(value));
    return url.toString();
  }

  async request(action = "", params = {}) {
    const limits = xtreamResponseLimits(action);
    const load = async () => {
      const response = await fetchSafe(this.apiUrl(action, params), {
        headers: { accept: "application/json" },
        requestTimeoutMs: limits.headerTimeoutMs,
      });
      if (!response.ok) throw new Error(`الخادم رفض الطلب (${response.status}).`);
      const text = await readTextLimited(response, limits.maxBytes, limits.idleTimeoutMs, limits.totalTimeoutMs);
      try { return JSON.parse(text); } catch { throw new Error("الخادم أعاد بيانات غير صالحة."); }
    };
    return limits.catalog ? serializeLargeCatalog(load) : load();
  }

  async accountStatus() {
    const data = await this.request();
    if (!data?.user_info) throw new Error("الخادم لم يرسل حالة اشتراك Xtream صالحة.");
    const authenticated = String(data.user_info.auth) === "1";
    return { authenticated, account: {
      authenticated,
      username: data.user_info.username || this.username,
      status: data.user_info.status || (authenticated ? "Active" : "Expired"),
      expiresAt: Number(data.user_info.exp_date || 0) * 1000 || null,
      maxConnections: Number(data.user_info.max_connections || 0),
      activeConnections: Number(data.user_info.active_cons || 0),
      serverName: new URL(this.base).host,
    } };
  }

  async validate() {
    const state = await this.accountStatus();
    if (!state.authenticated) throw new Error("بيانات Xtream غير صحيحة أو الاشتراك غير نشط.");
    return state.account;
  }

  async categories(type) {
    const action = type === "live" ? "get_live_categories" : type === "movies" ? "get_vod_categories" : "get_series_categories";
    const rows = await this.request(action);
    return Array.isArray(rows) ? rows.map((row) => ({ id: String(row.category_id), name: row.category_name || "غير مصنف" })) : [];
  }

  async catalog(type, categoryId = "") {
    const action = type === "live" ? "get_live_streams" : type === "movies" ? "get_vod_streams" : "get_series";
    const rows = await this.request(action, categoryId ? { category_id: categoryId } : {});
    if (!Array.isArray(rows)) return [];
    return rows.map((row) => {
      const primary = primaryRating(row);
      return {
        id: String(row.stream_id ?? row.series_id),
        name: row.name || row.title || "بدون اسم",
        image: row.stream_icon || row.cover || "",
        backdrop: Array.isArray(row.backdrop_path) ? row.backdrop_path[0] || "" : "",
        categoryId: String(row.category_id || ""),
        rating: primary.value,
        ratingSource: primary.source,
        year: row.year || row.releaseDate || row.releasedate || "",
        releaseDate: cleanDate(row.releaseDate, row.release_date, row.releasedate,
          row.last_air_date, row.first_air_date, row.year),
        updatedAt: cleanDate(row.last_modified, row.updated_at, row.added, row.added_at,
          type === "series" ? row.last_air_date : ""),
        // Match 7 Max behaviour for Xtream Live: TS is the default and the
        // player's fallback decision controls when HLS is requested. A provider
        // direct_source must not silently lock Live to one format, otherwise a
        // TS -> HLS recovery would keep reopening the exact same URL.
        extension: type === "live"
          ? "ts"
          : String(row.container_extension || extensionFromUrl(row.direct_source) || "mp4").toLowerCase(),
        epgId: row.epg_channel_id || "",
        sourceUrl: type === "live" ? "" : normalizeDirectSource(row.direct_source),
        type,
      };
    });
  }

  async movieInfo(id) {
    const data = await this.request("get_vod_info", { vod_id: id });
    const info = data?.info || {};
    const movie = data?.movie_data || {};
    const primary = primaryRating(info);
    return {
      id: String(id),
      name: info.name || movie.name || "فيلم",
      description: info.plot || info.description || "",
      image: info.movie_image || movie.stream_icon || "",
      backdrop: Array.isArray(info.backdrop_path) ? info.backdrop_path[0] || "" : "",
      rating: primary.value,
      year: info.releasedate || info.year || "",
      releaseDate: cleanDate(info.releaseDate, info.release_date, info.releasedate, info.year),
      updatedAt: cleanDate(movie.added, info.added, info.updated_at),
      ratingSource: primary.source,
      ratings: ratingRows(info),
      cast: people(info.cast, info.actors, info.credits?.cast),
      director: String(info.director || ""),
      imdbId: String(info.imdb_id || info.imdbId || ""),
      tmdbId: String(info.tmdb_id || info.tmdbId || ""),
      duration: info.duration || "",
      genre: info.genre || "",
      extension: movie.container_extension || extensionFromUrl(movie.direct_source) || "mp4",
      sourceUrl: normalizeDirectSource(movie.direct_source || info.direct_source),
      type: "movies",
    };
  }

  async seriesInfo(id) {
    const data = await this.request("get_series_info", { series_id: id });
    return normalizeSeriesInfo(data, id);
  }

  async epg(id, limit = 4) {
    const data = await this.request("get_short_epg", { stream_id: id, limit });
    return Array.isArray(data?.epg_listings) ? data.epg_listings.map((entry) => ({
      title: decode64(entry.title) || "بدون عنوان",
      description: decode64(entry.description),
      start: entry.start_timestamp ? Number(entry.start_timestamp) * 1000 : Date.parse(entry.start || ""),
      end: entry.stop_timestamp ? Number(entry.stop_timestamp) * 1000 : Date.parse(entry.end || ""),
    })) : [];
  }

  streamUrl(type, id, extension = "") {
    const segment = type === "live" ? "live" : type === "episode" ? "series" : "movie";
    const ext = String(extension || (type === "live" ? "ts" : "mp4")).replace(/[^a-zA-Z0-9]/g, "") || (type === "live" ? "ts" : "mp4");
    return `${this.base}/${segment}/${encodeURIComponent(this.username)}/${encodeURIComponent(this.password)}/${encodeURIComponent(id)}.${ext}`;
  }
}

export function extensionFromUrl(value) {
  if (!value) return "";
  try {
    const match = new URL(String(value)).pathname.match(/\.([a-zA-Z0-9]{2,6})$/);
    return match?.[1]?.toLowerCase() || "";
  } catch {
    return "";
  }
}

function episodeRows(rawEpisodes) {
  const rows = [];
  if (Array.isArray(rawEpisodes)) {
    for (const entry of rawEpisodes) {
      if (Array.isArray(entry)) rows.push(...entry.map((episode) => ({ seasonKey: "", episode })));
      else if (Array.isArray(entry?.episodes)) rows.push(...entry.episodes.map((episode) => ({ seasonKey: entry.season_number ?? entry.season ?? "", episode })));
      else if (entry && typeof entry === "object") rows.push({ seasonKey: entry.season_number ?? entry.season ?? "", episode: entry });
    }
    return rows;
  }
  if (!rawEpisodes || typeof rawEpisodes !== "object") return rows;
  for (const [seasonKey, value] of Object.entries(rawEpisodes)) {
    const values = Array.isArray(value) ? value : value && typeof value === "object" ? Object.values(value) : [];
    for (const episode of values) if (episode && typeof episode === "object") rows.push({ seasonKey, episode });
  }
  return rows;
}

export function normalizeSeriesInfo(data, id) {
  const info = data?.info || {};
  const primary = primaryRating(info);
  const bySeason = new Map();
  for (const [index, row] of episodeRows(data?.episodes).entries()) {
    const episode = row.episode;
    const episodeId = episode.id ?? episode.stream_id ?? episode.episode_id;
    if (episodeId == null || String(episodeId).trim() === "") continue;
    const season = String(episode.season ?? episode.season_number ?? row.seasonKey ?? "1") || "1";
    const number = Number(episode.episode_num ?? episode.episode_number ?? episode.number ?? index + 1) || index + 1;
    const values = bySeason.get(season) || [];
    values.push({
      id: String(episodeId),
      number,
      title: episode.title || episode.name || `الحلقة ${number}`,
      extension: String(episode.container_extension || episode.info?.container_extension || extensionFromUrl(episode.direct_source) || "mp4").toLowerCase(),
      duration: episode.info?.duration || episode.duration || "",
      image: episode.info?.movie_image || episode.info?.cover_big || episode.movie_image || info.cover || "",
      airDate: cleanDate(episode.airDate, episode.air_date, episode.releaseDate,
        episode.release_date, episode.info?.airDate, episode.info?.air_date,
        episode.info?.releaseDate, episode.info?.release_date, episode.added, episode.info?.added),
      sourceUrl: normalizeDirectSource(episode.direct_source || episode.info?.direct_source),
    });
    bySeason.set(season, values);
  }
  const seasons = [...bySeason.entries()]
    .map(([season, episodes]) => ({ season, episodes: episodes.sort((a, b) => a.number - b.number) }))
    .sort((a, b) => {
      const first = Number(a.season);
      const second = Number(b.season);
      return Number.isFinite(first) && Number.isFinite(second) ? first - second : a.season.localeCompare(b.season, "ar", { numeric: true });
    });
  return {
    id: String(id),
    name: info.name || info.title || "مسلسل",
    description: info.plot || info.description || "",
    image: info.cover || info.movie_image || "",
    backdrop: Array.isArray(info.backdrop_path) ? info.backdrop_path[0] || "" : info.backdrop_path || "",
    rating: primary.value,
    year: info.releaseDate || info.releasedate || info.first_air_date || info.last_air_date || info.year || "",
    releaseDate: cleanDate(info.releaseDate, info.release_date, info.releasedate,
      info.first_air_date, info.year),
    updatedAt: cleanDate(info.last_modified, info.updated_at, info.added, info.last_air_date),
    ratingSource: primary.source,
    ratings: ratingRows(info),
    cast: people(info.cast, info.actors, info.credits?.cast),
    director: String(info.director || ""),
    imdbId: String(info.imdb_id || info.imdbId || ""),
    tmdbId: String(info.tmdb_id || info.tmdbId || ""),
    genre: info.genre || "",
    seasons,
    type: "series",
  };
}

function decode64(value) {
  if (!value) return "";
  try { return Buffer.from(value, "base64").toString("utf8"); } catch { return String(value); }
}
