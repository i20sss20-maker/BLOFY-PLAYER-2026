import assert from "node:assert/strict";
import test from "node:test";
import { APP_VERSION, NATIVE_PLAYBACK_MODE, nativePlaybackTarget } from "../lib/runtime.mjs";

test("native playback is permanently direct and never adds a Railway transcode URL", () => {
  const source = "http://provider.example/live/private-user/private-password/985136.ts";
  assert.equal(nativePlaybackTarget(source), source);
  assert.equal(NATIVE_PLAYBACK_MODE, "direct");
  assert.match(APP_VERSION, /^2026\.08\.20\.\d+$/);
});
