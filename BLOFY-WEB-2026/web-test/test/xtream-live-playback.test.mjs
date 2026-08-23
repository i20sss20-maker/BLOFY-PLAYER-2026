import assert from "node:assert/strict";
import test from "node:test";
import { XtreamClient } from "../lib/xtream.mjs";

test("Xtream live uses TS by default like 7 Max", () => {
  const client = new XtreamClient({
    serverUrl: "http://provider.example/",
    username: "demo-user",
    password: "demo-pass",
  });
  assert.equal(
    client.streamUrl("live", "12345"),
    "http://provider.example/live/demo-user/demo-pass/12345.ts",
  );
});

test("Xtream live fallback can switch to HLS without changing the stream id", () => {
  const client = new XtreamClient({
    serverUrl: "https://provider.example/player_api.php",
    username: "user name",
    password: "p@ss",
  });
  assert.equal(
    client.streamUrl("live", "99", "m3u8"),
    "https://provider.example/live/user%20name/p%40ss/99.m3u8",
  );
});

test("Xtream non-live containers remain independent from live format policy", () => {
  const client = new XtreamClient({
    serverUrl: "https://provider.example",
    username: "u",
    password: "p",
  });
  assert.equal(client.streamUrl("movies", "10", "mkv"), "https://provider.example/movie/u/p/10.mkv");
  assert.equal(client.streamUrl("episode", "11", "mp4"), "https://provider.example/series/u/p/11.mp4");
});
