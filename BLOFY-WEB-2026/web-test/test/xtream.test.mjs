import assert from "node:assert/strict";
import test from "node:test";
import { normalizeSeriesInfo } from "../lib/xtream.mjs";

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
