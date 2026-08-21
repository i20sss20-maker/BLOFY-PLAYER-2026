import assert from "node:assert/strict";
import test from "node:test";
import { APP_VERSION, NATIVE_PLAYBACK_MODE, nativePlaybackPath, nativePlaybackTarget } from "../lib/runtime.mjs";

test("native playback is permanently direct and never adds a Railway transcode URL", () => {
  const source = "http://provider.example/live/private-user/private-password/985136.ts";
  assert.equal(nativePlaybackTarget(source), source);
  assert.equal(NATIVE_PLAYBACK_MODE, "direct");
  assert.match(APP_VERSION, /^2026\.08\.\d{2}\.\d+$/);
});

test("legacy HTTP playback stays behind BLOFY HTTPS while HTTPS remains direct", () => {
  assert.equal(nativePlaybackPath("http://provider.example/live/1.ts"), "/api/proxy");
  assert.equal(nativePlaybackPath("https://cdn.example/live/1.m3u8"), "/api/native-play");
});
