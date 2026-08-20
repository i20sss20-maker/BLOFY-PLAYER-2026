import assert from "node:assert/strict";
import test from "node:test";
import { inspectPlaylistBody } from "../lib/media-response.mjs";

test("raw transport stream is detected from its first bytes without waiting for stream end", async () => {
  const stream = new ReadableStream({
    start(controller) {
      controller.enqueue(Uint8Array.from([0x47, 0x40, 0x00, 0x10, 0x00, 0x01]));
    },
  });
  const started = Date.now();
  const inspected = await inspectPlaylistBody(new Response(stream));
  assert.equal(inspected.playlist, null);
  assert.equal(inspected.prefix[0], 0x47);
  assert.ok(Date.now() - started < 500, "raw stream inspection should return immediately");
  await inspected.reader.cancel();
});

test("HLS playlist split across chunks is read and returned as text", async () => {
  const encoder = new TextEncoder();
  const stream = new ReadableStream({
    start(controller) {
      controller.enqueue(encoder.encode("#EX"));
      controller.enqueue(encoder.encode("TM3U\n#EXTINF:2,\nsegment.ts\n"));
      controller.close();
    },
  });
  const inspected = await inspectPlaylistBody(new Response(stream));
  assert.match(inspected.playlist, /^#EXTM3U/);
  assert.match(inspected.playlist, /segment\.ts/);
  assert.equal(inspected.reader, null);
});
