import test from "node:test";
import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import {
  bindRelayCancellation,
  providerRequestHeaders,
  providerResponseStatus,
} from "../lib/media-relay.mjs";

test("provider relay uses a compatible media user-agent and preserves ranges", () => {
  const headers = providerRequestHeaders({ range: "bytes=100-" });
  assert.equal(headers["user-agent"], "VLC/3.0.20 LibVLC/3.0.20");
  assert.equal(headers.range, "bytes=100-");
  assert.match(headers.accept, /video\/mp2t/);

  const custom = providerRequestHeaders({}, " IPTV-App/1.0\r\nInjected: no ");
  assert.equal(custom["user-agent"], "IPTV-App/1.0  Injected: no");
});

test("provider HTTP failures retain an actionable status", () => {
  for (const status of [400, 401, 403, 404, 408, 429, 451, 500, 502, 503, 599]) {
    assert.equal(providerResponseStatus(status), status);
  }
  for (const status of [0, 200, 302, 600, "bad"]) {
    assert.equal(providerResponseStatus(status), 502);
  }
});

test("relay cancellation closes upstream exactly once when the player disconnects", async () => {
  const req = new EventEmitter();
  const res = new EventEmitter();
  req.aborted = false;
  res.destroyed = false;
  let cancellations = 0;
  const lifecycle = bindRelayCancellation(req, res, async () => { cancellations += 1; });

  res.emit("close");
  req.emit("aborted");
  await new Promise((resolve) => setImmediate(resolve));

  assert.equal(lifecycle.cancelled, true);
  assert.equal(cancellations, 1);
  assert.equal(req.listenerCount("aborted"), 0);
  assert.equal(res.listenerCount("close"), 0);
});

test("completed relay detaches cancellation without cancelling upstream", async () => {
  const req = new EventEmitter();
  const res = new EventEmitter();
  req.aborted = false;
  res.destroyed = false;
  let cancellations = 0;
  const lifecycle = bindRelayCancellation(req, res, () => { cancellations += 1; });

  lifecycle.complete();
  res.emit("close");
  req.emit("aborted");
  await new Promise((resolve) => setImmediate(resolve));

  assert.equal(lifecycle.cancelled, false);
  assert.equal(cancellations, 0);
});
