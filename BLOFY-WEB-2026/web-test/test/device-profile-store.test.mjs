import assert from "node:assert/strict";
import crypto from "node:crypto";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { DeviceProfileStore } from "../lib/device-profile-store.mjs";
import { seal, unseal } from "../lib/security.mjs";

const PRIVATE_ID = "BLOFY-ABCD-EFGH-JKLM-NPQR";
const DEVICE_KEY = "A".repeat(64);

async function fixture(run) {
  const directory = await mkdtemp(path.join(os.tmpdir(), "blofy-profile-"));
  const filePath = path.join(directory, "profiles.json");
  try { await run(new DeviceProfileStore(filePath, { now: () => 1_700_000_000_000 }), filePath); }
  finally { await rm(directory, { recursive: true, force: true }); }
}

test("register returns a short id and stable six-digit pairing code", async () => fixture(async (store) => {
  const first = await store.register(PRIVATE_ID, DEVICE_KEY, { displayId: "BLOFY-A7" });
  const second = await store.register(PRIVATE_ID, DEVICE_KEY);
  assert.equal(first.displayId, "BLOFY-A7");
  assert.match(first.pairingCode, /^\d{6}$/);
  assert.equal(second.displayId, first.displayId);
  assert.equal(second.pairingCode, first.pairingCode);
  const login = await store.login(first.displayId, first.pairingCode);
  assert.equal(login.deviceId, PRIVATE_ID);
  await assert.rejects(() => store.login(first.displayId, "000001"), /غير صحيح/);
}));

test("authenticated re-registration keeps the TV pairing code synchronized", async () => fixture(async (store) => {
  const first = await store.register(PRIVATE_ID, DEVICE_KEY,
    { displayId: "BLOFY-R7", pairingCode: "123456" });
  assert.equal((await store.login(first.displayId, "123456")).deviceId, PRIVATE_ID);
  const rotated = await store.register(PRIVATE_ID, DEVICE_KEY,
    { displayId: first.displayId, pairingCode: "654321" });
  assert.equal(rotated.pairingCode, "654321");
  await assert.rejects(() => store.login(first.displayId, "123456"), /غير صحيح/);
  assert.equal((await store.login(first.displayId, "654321")).deviceId, PRIVATE_ID);
}));

test("multi-playlist CRUD increments revision and preserves a valid default", async () => fixture(async (store) => {
  await store.register(PRIVATE_ID, DEVICE_KEY, { displayId: "BLOFY-B8", pairingCode: "483921" });
  const first = await store.createPlaylist(PRIVATE_ID, {
    name: "الرئيسية", kind: "xtream", serverName: "provider.example", profileToken: seal({ kind: "xtream", username: "user", password: "secret" }),
  });
  const second = await store.createPlaylist(PRIVATE_ID, {
    name: "العائلة", kind: "m3u", serverName: "m3u.example", profileToken: seal({ kind: "m3u", url: "https://m3u.example/list" }), makeDefault: true,
  });
  let snapshot = await store.snapshot(PRIVATE_ID);
  assert.equal(snapshot.playlists.length, 2);
  assert.equal(snapshot.defaultPlaylistId, second.id);
  assert.deepEqual(unseal(await store.profileToken(PRIVATE_ID)), { kind: "m3u", url: "https://m3u.example/list" });
  const revision = snapshot.revision;
  await store.updatePlaylist(PRIVATE_ID, first.id, { name: "القائمة الأولى", latencyMs: 72 });
  await store.setDefault(PRIVATE_ID, first.id);
  snapshot = await store.snapshot(PRIVATE_ID);
  assert.ok(snapshot.revision > revision);
  assert.equal(snapshot.defaultPlaylistId, first.id);
  assert.equal(snapshot.playlists.find((item) => item.id === first.id).name, "القائمة الأولى");
  await store.deletePlaylist(PRIVATE_ID, first.id);
  snapshot = await store.snapshot(PRIVATE_ID);
  assert.equal(snapshot.defaultPlaylistId, second.id);
  assert.equal(snapshot.playlists.length, 1);
}));

test("credential payload is encrypted at rest", async () => fixture(async (store, filePath) => {
  await store.register(PRIVATE_ID, DEVICE_KEY, { displayId: "BLOFY-C9", pairingCode: "123456" });
  const session = { kind: "xtream", serverUrl: "https://provider.example", username: "visible-user", password: "very-secret-password" };
  await store.createPlaylist(PRIVATE_ID, { name: "سرية", kind: "xtream", serverName: "provider.example", profileToken: seal(session) });
  const persisted = await readFile(filePath, "utf8");
  assert.equal(persisted.includes("visible-user"), false);
  assert.equal(persisted.includes("very-secret-password"), false);
  assert.equal(persisted.includes("https://provider.example"), false);
}));

test("legacy one-profile records migrate without data loss", async () => fixture(async (store, filePath) => {
  const token = seal({ kind: "m3u", name: "قديمة", url: "https://example.com/list.m3u" });
  await writeFile(filePath, JSON.stringify({ version: 1, devices: {
    [PRIVATE_ID]: { keyHash: crypto.createHash("sha256").update(DEVICE_KEY).digest("hex"), profileToken: token, createdAt: 1 },
  } }));
  const restored = new DeviceProfileStore(filePath);
  const snapshot = await restored.snapshot(PRIVATE_ID);
  assert.equal(snapshot.playlists.length, 1);
  assert.equal(snapshot.playlists[0].id, "legacy");
  assert.equal(await restored.profileToken(PRIVATE_ID), token);
}));
