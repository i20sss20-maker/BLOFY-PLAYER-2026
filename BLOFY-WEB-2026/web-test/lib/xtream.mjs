import { fetchSafe, readTextLimited } from "./security.mjs";

function cleanBase(value) {
  const raw = String(value).trim();
  const url = new URL(raw);
  url.search = "";
  url.hash = "";
  url.pathname = url.pathname.replace(/\/(?:player_api|get)\.php\/?$/i, "").replace(/\/+$/, "");
  return url.toString().replace(/\/+$/, "");
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
    const response = await fetchSafe(this.apiUrl(action, params), { headers: { accept: "application/json" } });
    if (!response.ok) throw new Error(`الخادم رفض الطلب (${response.status}).`);
    const text = await readTextLimited(response, 48_000_000, 15_000);
    try { return JSON.parse(text); } catch { throw new Error("الخادم أعاد بيانات غير صالحة."); }
  }

  async validate() {
    const data = await this.request();
    if (!data?.user_info || String(data.user_info.auth) !== "1") throw new Error("بيانات Xtream غير صحيحة أو الاشتراك غير نشط.");
    return {
      username: data.user_info.username || this.username,
      status: data.user_info.status || "Active",
      expiresAt: Number(data.user_info.exp_date || 0) * 1000 || null,
      maxConnections: Number(data.user_info.max_connections || 0),
      activeConnections: Number(data.user_info.active_cons || 0),
      serverName: new URL(this.base).host,
    };
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
    return rows.map((row) => ({
      id: String(row.stream_id ?? row.series_id),
      name: row.name || row.title || "بدون اسم",
      image: row.stream_icon || row.cover || "",
      backdrop: Array.isArray(row.backdrop_path) ? row.backdrop_path[0] || "" : "",
      categoryId: String(row.category_id || ""),
      rating: row.rating_5based || row.rating || "",
      year: row.year || row.releaseDate || row.releasedate || "",
      extension: String(row.container_extension || (type === "live" ? "ts" : "mp4")).toLowerCase(),
      epgId: row.epg_channel_id || "",
      type,
    }));
  }

  async movieInfo(id) {
    const data = await this.request("get_vod_info", { vod_id: id });
    const info = data?.info || {};
    const movie = data?.movie_data || {};
    return {
      id: String(id),
      name: info.name || movie.name || "فيلم",
      description: info.plot || info.description || "",
      image: info.movie_image || movie.stream_icon || "",
      backdrop: Array.isArray(info.backdrop_path) ? info.backdrop_path[0] || "" : "",
      rating: info.rating || "",
      year: info.releasedate || info.year || "",
      duration: info.duration || "",
      genre: info.genre || "",
      extension: movie.container_extension || extensionFromUrl(movie.direct_source) || "mp4",
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

function extensionFromUrl(value) {
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
    rating: info.rating || info.rating_5based || "",
    year: info.releaseDate || info.releasedate || info.year || "",
    genre: info.genre || "",
    seasons,
    type: "series",
  };
}

function decode64(value) {
  if (!value) return "";
  try { return Buffer.from(value, "base64").toString("utf8"); } catch { return String(value); }
}
