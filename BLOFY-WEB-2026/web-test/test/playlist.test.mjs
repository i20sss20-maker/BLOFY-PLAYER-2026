import test from "node:test";
import assert from "node:assert/strict";
import { pageItems, parseM3u } from "../lib/playlist.mjs";

test("parses and classifies M3U entries", () => {
  const list = `#EXTM3U
#EXTINF:-1 tvg-id="ssc1" tvg-logo="https://img.example/ssc.png" group-title="رياضة",SSC 1 HD
https://media.example/live/ssc1.m3u8
#EXTINF:-1 group-title="Movies Arabic",فيلم تجريبي
https://media.example/movie/u/p/22.mp4`;
  const items = parseM3u(list);
  assert.equal(items.length, 2);
  assert.equal(items[0].type, "live");
  assert.equal(items[0].category, "رياضة");
  assert.equal(items[1].type, "movies");
});

test("paginates and filters Xtream category ids", () => {
  const items = [
    { id: "1", name: "SSC", categoryId: "9" },
    { id: "2", name: "MBC", categoryId: "4" },
  ];
  const result = pageItems(items, { category: "9", search: "ss", page: 1, pageSize: 60 });
  assert.equal(result.total, 1);
  assert.equal(result.items[0].id, "1");
});

test("extensionless live M3U sources default to raw transport stream", () => {
  const [item] = parseM3u(`#EXTM3U
#EXTINF:-1 group-title="Live",Channel
https://media.example/live/channel?id=42`);
  assert.equal(item.extension, "ts");
});

test("M3U snapshot resolves relative media and logo URLs against its final URL", () => {
  const [item] = parseM3u(`#EXTM3U
#EXTINF:-1 tvg-logo="../logos/channel.png" group-title="Live",Relative channel
./streams/channel.ts`, "https://provider.example/packages/main/list.m3u8?token=secret");
  assert.equal(item.sourceUrl, "https://provider.example/packages/main/streams/channel.ts");
  assert.equal(item.image, "https://provider.example/packages/logos/channel.png");
  assert.equal(item.extension, "ts");
});
