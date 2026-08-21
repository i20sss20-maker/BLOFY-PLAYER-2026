import assert from "node:assert/strict";
import test from "node:test";
import { normalizeSeriesInfo, XtreamClient, xtreamResponseLimits } from "../lib/xtream.mjs";

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
});

test("series normalization accepts provider-specific episode identifiers", () => {
  const result = normalizeSeriesInfo({
    info: { name: "مسلسل اختبار", cover: "https://img.example/cover.jpg" },
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
