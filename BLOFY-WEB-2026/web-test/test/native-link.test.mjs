import assert from "node:assert/strict";
import test from "node:test";
import {
  appendMediaAttemptId,
  buildNativeLinkContract,
  createMediaAttemptId,
  mediaErrorStatus,
  mediaLogContext,
  mediaMimeType,
  mediaProviderStatus,
  normalizeMediaAttemptId,
} from "../lib/native-link.mjs";

const attemptId = "0123456789abcdef01234567";
const directPath = "/api/native-play?u=encrypted&e=2000000000&s=signature";
const relayPath = "/api/proxy?u=encrypted&e=2000000000&s=signature";

test("native link contract is explicit, direct-first, and backwards compatible", () => {
  const contract = buildNativeLinkContract({
    directUrl: directPath,
    relayUrl: relayPath,
    extension: ".M3U8",
    attemptId,
  });

  assert.equal(contract.strategy, "direct-first");
  assert.equal(contract.directUrl, `${directPath}&a=${attemptId}`);
  assert.equal(contract.url, contract.directUrl);
  assert.equal(contract.relayUrl, `${relayPath}&a=${attemptId}`);
  assert.equal(contract.extension, "m3u8");
  assert.equal(contract.mimeType, "application/x-mpegURL");
  assert.equal(contract.mode, "direct");
  assert.equal(contract.attemptId, attemptId);
  assert.equal(JSON.stringify(contract).includes("provider.example"), false);
});

test("native link keeps a relay only in the legacy url for cleartext-disabled clients", () => {
  const contract = buildNativeLinkContract({
    directUrl: directPath,
    relayUrl: relayPath,
    legacyUrl: relayPath,
    extension: "ts",
    attemptId,
  });
  assert.equal(contract.directUrl, `${directPath}&a=${attemptId}`);
  assert.equal(contract.url, `${relayPath}&a=${attemptId}`);
  assert.equal(contract.relayUrl, `${relayPath}&a=${attemptId}`);
  assert.equal(contract.mimeType, "video/mp2t");
  assert.equal(contract.strategy, "direct-first");
});

test("native link rejects raw, unsigned, or header-injected media URLs", () => {
  for (const directUrl of [
    "https://provider.example/live/private-user/private-password/1.ts",
    "/api/native-play?u=encrypted&e=2000000000",
    "/api/native-play?u=encrypted&e=2000000000&s=sig%0d%0aInjected",
    "/api/transcode/index.m3u8?u=encrypted&e=2000000000&s=signature",
  ]) {
    assert.throws(() => buildNativeLinkContract({ directUrl, relayUrl: relayPath, extension: "ts", attemptId }), /غير صالح/);
  }
  assert.throws(() => buildNativeLinkContract({ directUrl: relayPath, relayUrl: relayPath, extension: "ts", attemptId }), /غير صالح/);
  assert.throws(() => buildNativeLinkContract({ directUrl: directPath, relayUrl: directPath, extension: "ts", attemptId }), /غير صالح/);
});

test("attempt ids are unguessable fixed tokens and invalid caller values are not reflected", () => {
  const generated = createMediaAttemptId();
  assert.match(generated, /^[a-f0-9]{24}$/);
  assert.equal(normalizeMediaAttemptId(generated), generated);
  assert.equal(normalizeMediaAttemptId("bad\r\nInjected: yes"), "");
  assert.equal(appendMediaAttemptId(relayPath, "not-valid"), relayPath);
  assert.equal(appendMediaAttemptId(relayPath, attemptId), `${relayPath}&a=${attemptId}`);
});

test("Media3 MIME types cover native live, movie, and episode containers", () => {
  assert.equal(mediaMimeType("ts"), "video/mp2t");
  assert.equal(mediaMimeType("mp4"), "video/mp4");
  assert.equal(mediaMimeType("mkv"), "video/x-matroska");
  assert.equal(mediaMimeType("mpd"), "application/dash+xml");
  assert.equal(mediaMimeType("unknown"), "application/octet-stream");
});

test("native media failures retain provider status and distinguish transport timeout", () => {
  const forbidden = Object.assign(new Error("provider refused"), { status: 403, providerStatus: 403 });
  assert.equal(mediaProviderStatus(forbidden), 403);
  assert.equal(mediaErrorStatus(forbidden), 403);
  const localMissing = Object.assign(new Error("missing"), { status: 404 });
  assert.equal(mediaProviderStatus(localMissing), 0);
  assert.equal(mediaErrorStatus(localMissing), 404);
  assert.equal(mediaErrorStatus(Object.assign(new Error("late"), { name: "AbortError" })), 504);
  assert.equal(mediaErrorStatus(Object.assign(new Error("dns"), { code: "ENOTFOUND" })), 502);
  assert.equal(mediaErrorStatus(new Error("internal")), 500);
});

test("safe media logs correlate attempts without credentials, URLs, or raw ids", () => {
  const context = mediaLogContext({
    attemptId,
    type: "episode",
    id: "private-episode-id",
    extension: "mkv\r\nInjected",
    host: "cdn.example:443",
    status: 403,
  });
  assert.match(context, new RegExp(`attempt=${attemptId}`));
  assert.match(context, /type=episode/);
  assert.match(context, /status=403/);
  assert.doesNotMatch(context, /private-episode-id|password|https?:\/\//);
  assert.doesNotMatch(context, /[\r\n]/);
});
