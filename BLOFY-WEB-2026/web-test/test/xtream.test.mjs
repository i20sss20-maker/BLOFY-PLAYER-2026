import assert from "node:assert/strict";
import test from "node:test";
import { extensionFromUrl, normalizeDirectSource, normalizeSeriesInfo, XtreamClient, xtreamResponseLimits } from "../lib/xtream.mjs";

test("large Xtream catalogs use a larger bounded response window", () => {
  const names = [
    "XTREAM_CATALOG_MAX_BYTES",
    "XTREAM_CATALOG_HEADER_TIMEOUT_MS",
    "XTREAM_CATALOG_IDLE_TIMEOUT_MS",
    "XTREAM_CATALOG_TOTAL_TIMEOUT_MS",
  ];
  const original = Object.fromEntries(names.map((name) => [name, process.env[name]]));
  try {
    for (const name of names) delete process.env[name];
    const regular = xtreamResponseLimits("get_vod_categories");
    for (const action of ["get_live_streams", "get_vod_streams", "get_series"]) {
      const catalog = xtreamResponseLimits(action);
      assert.equal(catalog.catalog, true);
      assert.equal(catalog.maxBytes, 160_000_000);
      assert.equal(catalog.headerTimeoutMs, 60_000);
      assert.equal(catalog.totalTimeoutMs, 120_000);
    }
    assert.equal(regular.catalog, false);
    assert.equal(regular.maxBytes, 48_000_000);
    process.env.XTREAM_CATALOG_MAX_BYTES = "999999999";
    process.env.XTREAM_CATALOG_HEADER_TIMEOUT_MS = "invalid";
    const bounded = xtreamResponseLimits("get_vod_streams");
    assert.equal(bounded.maxBytes, 160_000_000);
    assert.equal(bounded.headerTimeoutMs, 60_000);
  } finally {
    for (const name of names) {
      if (original[name] === undefined) delete process.env[name];
      else process.env[name] = original[name];
    }
  }
});

test("Xtream live streams default to transport stream and honor provider extensions", () => {
  const client = new XtreamClient({ serverUrl: "https://provider.example", username: "user", password: "pass" });
  assert.equal(client.streamUrl("live", "123"), "https://provider.example/live/user/pass/123.ts");
  assert.equal(client.streamUrl("live", "123", "m3u8"), "https://provider.example/live/user/pass/123.m3u8");
  assert.equal(extensionFromUrl("https://cdn.example/live/123.m3u8?token=private"), "m3u8");
  assert.equal(extensionFromUrl("https://cdn.example/live/123"), "");
});

test("Xtream account revalidation distinguishes an expired subscription from an outage", async () => {
  const client = new XtreamClient({ serverUrl: "https://provider.example", username: "user", password: "pass" });
  client.request = async () => ({
    user_info: {
      auth: 0,
      username: "user",
      status: "Expired",
      exp_date: "1700000000",
      max_connections: "2",
      active_cons: "0",
    },
  });
  const expired = await client.accountStatus();
  assert.equal(expired.authenticated, false);
  assert.equal(expired.account.authenticated, false);
  assert.equal(expired.account.status, "Expired");
  assert.equal(expired.account.expiresAt, 1_700_000_000_000);
  await assert.rejects(() => client.validate(), /غير صحيحة|غير نشط/);

  client.request = async () => { throw new Error("provider unavailable"); };
  await assert.rejects(() => client.accountStatus(), /provider unavailable/);
});

test("Xtream keeps valid direct_source internally and rejects unsafe schemes", async () => {
  assert.equal(normalizeDirectSource("javascript:alert(1)"), "");
  assert.equal(normalizeDirectSource("https://user:pass@provider.example/video.ts"), "");
  assert.equal(normalizeDirectSource("not a url"), "");
  assert.equal(normalizeDirectSource("https://cdn.example/video.ts?token=private"), "https://cdn.example/video.ts?token=private");

  const client = new XtreamClient({ serverUrl: "https://provider.example", username: "user", password: "pass" });
  client.request = async (action) => action === "get_vod_info"
    ? {
        movie_data: { stream_id: 44, direct_source: "https://cdn.example/movie/44.mkv?token=secret" },
        info: {
          name: "Movie",
          cast: "Actor One, Actor Two",
          imdb_rating: "7.8",
          release_date: "2026-08-20",
        },
      }
    : [{ stream_id: 33, name: "Live", direct_source: "https://cdn.example/live/33.m3u8?token=secret" }];

  const catalog = await client.catalog("live");
  assert.equal(catalog[0].sourceUrl, "");
  assert.equal(catalog[0].extension, "ts");
  const movie = await client.movieInfo("44");
  assert.equal(movie.sourceUrl, "https://cdn.example/movie/44.mkv?token=secret");
  assert.equal(movie.releaseDate, "2026-08-20");
  assert.deepEqual(movie.cast.map((entry) => entry.name), ["Actor One", "Actor Two"]);
  assert.deepEqual(movie.ratings[0], { source: "IMDb", value: "7.8" });
});

test("series normalization accepts provider-specific episode identifiers", () => {
  const result = normalizeSeriesInfo({
    info: {
      name: "مسلسل اختبار",
      cover: "https://img.example/cover.jpg",
      cast: "ممثل أول، ممثل ثان",
      tmdb_rating: "8.2",
      release_date: "2026-08-01",
    },
    episodes: {
      "2": [{ stream_id: 22, episode_num: "2", name: "الثانية", container_extension: "mkv" }],
      "1": {
        first: { episode_id: 11, episode_number: "1", title: "الأولى", info: { duration: "42:00" } },
      },
    },
  }, "99");

  assert.deepEqual(result.seasons.map((season) => season.season), ["1", "2"]);
  assert.equal(result.seasons[0].episodes[0].id, "11");
  assert.equal(result.seasons[1].episodes[0].extension, "mkv");
  assert.equal(result.releaseDate, "2026-08-01");
  assert.equal(result.ratings[0].source, "TMDB");
  assert.equal(result.cast.length, 2);
});

test("series normalization retains a valid private episode direct_source", () => {
  const result = normalizeSeriesInfo({
    info: { name: "Direct series" },
    episodes: {
      "1": [{ id: 81, episode_num: 1, direct_source: "https://cdn.example/episode/81.mp4?token=private" }],
    },
  }, "8");
  assert.equal(result.seasons[0].episodes[0].sourceUrl, "https://cdn.example/episode/81.mp4?token=private");
});

test("series normalization groups flat arrays by season", () => {
  const result = normalizeSeriesInfo({
    info: { title: "Flat" },
    episodes: [
      { id: 2, season: 1, episode_num: 2 },
      { id: 1, season: 1, episode_num: 1 },
      { id: 3, season_number: 2, episode_num: 1 },
    ],
  }, "7");
  assert.deepEqual(result.seasons[0].episodes.map((episode) => episode.id), ["1", "2"]);
  assert.equal(result.seasons[1].season, "2");
});
