import assert from "node:assert/strict";
import test from "node:test";
import { APP_VERSION, NATIVE_PLAYBACK_MODE, nativePlaybackPath, nativePlaybackTarget } from "../lib/runtime.mjs";

test("native playback is permanently direct-provider and never adds a Railway media relay", () => {
  const source = "http://provider.example/live/private-user/private-password/985136.ts";
  assert.equal(nativePlaybackTarget(source), source);
  assert.equal(NATIVE_PLAYBACK_MODE, "direct-provider");
  assert.match(APP_VERSION, /^2026\.08\.\d{2}\.\d+$/);
});

test("HTTP and HTTPS provider playback both use the direct native redirect", () => {
  assert.equal(nativePlaybackPath("http://provider.example/live/1.ts"), "/api/native-play");
  assert.equal(nativePlaybackPath("https://cdn.example/live/1.m3u8"), "/api/native-play");
});
