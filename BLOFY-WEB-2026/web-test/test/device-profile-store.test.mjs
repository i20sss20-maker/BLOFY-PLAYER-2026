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

test("register returns a public id and stable six-digit pairing code", async () => fixture(async (store) => {
  const first = await store.register(PRIVATE_ID, DEVICE_KEY, { displayId: "BLOFY-A7B2-C9D4" });
  const second = await store.register(PRIVATE_ID, DEVICE_KEY);
  assert.equal(first.displayId, "BLOFY-A7B2-C9D4");
  assert.match(first.pairingCode, /^\d{6}$/);
  assert.equal(second.displayId, first.displayId);
  assert.equal(second.pairingCode, first.pairingCode);
  const login = await store.login(first.displayId, first.pairingCode);
  assert.equal(login.deviceId, PRIVATE_ID);
  await assert.rejects(() => store.login(first.displayId, "000001"), /غير صحيح/);
}));

test("authenticated re-registration keeps the TV pairing code synchronized", async () => fixture(async (store) => {
  const first = await store.register(PRIVATE_ID, DEVICE_KEY,
    { displayId: "BLOFY-R7A2-B3C4", pairingCode: "123456" });
  assert.equal((await store.login(first.displayId, "123456")).deviceId, PRIVATE_ID);
  const rotated = await store.register(PRIVATE_ID, DEVICE_KEY,
    { displayId: first.displayId, pairingCode: "654321" });
  assert.equal(rotated.pairingCode, "654321");
  await assert.rejects(() => store.login(first.displayId, "123456"), /غير صحيح/);
  assert.equal((await store.login(first.displayId, "654321")).deviceId, PRIVATE_ID);
}));

test("reinstall recovery rotates the device key only with the existing six digits", async () => fixture(async (store) => {
  const firstKey = "C".repeat(64);
  const replacementKey = "D".repeat(64);
  await store.register(PRIVATE_ID, firstKey, { displayId: "BLOFY-K7A2-B3C4", pairingCode: "731905" });
  await assert.rejects(() => store.register(PRIVATE_ID, replacementKey,
    { displayId: "BLOFY-K7A2-B3C4", pairingCode: "000000" }), /تعذر استعادة/);
  const recovered = await store.register(PRIVATE_ID, replacementKey,
    { displayId: "BLOFY-K7A2-B3C4", pairingCode: "731905" });
  assert.equal(recovered.recovered, true);
  await assert.rejects(() => store.withDeviceKey(PRIVATE_ID, firstKey), /تغيّر مفتاحه/);
  assert.equal((await store.withDeviceKey(PRIVATE_ID, replacementKey)).deviceId, PRIVATE_ID);
}));

test("QR nonce is high-entropy, expiring, and one-time", async () => fixture(async (store) => {
  const registered = await store.register(PRIVATE_ID, DEVICE_KEY, { displayId: "BLOFY-N8A2-B3C4", pairingCode: "481620" });
  const nonce = "A_secure_nonce_1234567890";
  await store.issuePairNonce(PRIVATE_ID, registered.keyHash, nonce, 1_700_000_100_000);
  assert.equal((await store.verifyPairNonce(PRIVATE_ID, nonce)).displayId, "BLOFY-N8A2-B3C4");
  assert.equal((await store.consumePairNonce(PRIVATE_ID, nonce)).deviceId, PRIVATE_ID);
  await assert.rejects(() => store.consumePairNonce(PRIVATE_ID, nonce), /تم استخدامه/);
}));

test("portal logout revision invalidates an issued portal session", async () => fixture(async (store) => {
  const registered = await store.register(PRIVATE_ID, DEVICE_KEY, { displayId: "BLOFY-P9A2-B3C4", pairingCode: "993812" });
  assert.equal((await store.portal(PRIVATE_ID, registered.portalVersion)).deviceId, PRIVATE_ID);
  assert.equal(await store.revokePortal(PRIVATE_ID, registered.portalVersion), true);
  await assert.rejects(() => store.portal(PRIVATE_ID, registered.portalVersion), /انتهت جلسة/);
}));

test("multi-playlist CRUD increments revision and preserves a valid default", async () => fixture(async (store) => {
  await store.register(PRIVATE_ID, DEVICE_KEY, { displayId: "BLOFY-B8A2-C3D4", pairingCode: "483921" });
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
  await store.register(PRIVATE_ID, DEVICE_KEY, { displayId: "BLOFY-C9A2-B3D4", pairingCode: "123456" });
  const session = { kind: "xtream", serverUrl: "https://provider.example", username: "visible-user", password: "very-secret-password" };
  await store.createPlaylist(PRIVATE_ID, { name: "سرية", kind: "xtream", serverName: "provider.example", profileToken: seal(session) });
  const persisted = await readFile(filePath, "utf8");
  assert.equal(persisted.includes("visible-user"), false);
  assert.equal(persisted.includes("very-secret-password"), false);
  assert.equal(persisted.includes("https://provider.example"), false);
}));

test("legacy BLOFY-SV login survives authenticated upgrade to the canonical id", async () => fixture(async (store, filePath) => {
  const original = await store.register(PRIVATE_ID, DEVICE_KEY,
    { displayId: "BLOFY-A7B2-C9D4", pairingCode: "772413" });
  const persisted = JSON.parse(await readFile(filePath, "utf8"));
  persisted.devices[PRIVATE_ID].displayId = "BLOFY-SV";
  delete persisted.aliases[original.displayId];
  persisted.aliases["BLOFY-SV"] = PRIVATE_ID;
  await writeFile(filePath, JSON.stringify(persisted));

  const restored = new DeviceProfileStore(filePath, { now: () => 1_700_000_000_000 });
  assert.equal((await restored.login("blofy-sv", "772413")).displayId, "BLOFY-SV");

  const upgraded = await restored.register(PRIVATE_ID, DEVICE_KEY,
    { displayId: "BLOFY-SV", pairingCode: "772413" });
  assert.match(upgraded.displayId, /^BLOFY-[A-Z0-9]{4}-[A-Z0-9]{4}$/);
  assert.notEqual(upgraded.displayId, "BLOFY-SV");
  assert.equal((await restored.login("BLOFY-SV", "772413")).displayId, upgraded.displayId);
  assert.equal((await restored.login(upgraded.displayId, "772413")).deviceId, PRIVATE_ID);
}));

test("legacy one-profile records migrate without data loss", async () => fixture(async (store, filePath) => {
  const token = seal({ kind: "m3u", name: "قديمة", serverName: "example.com", url: "https://example.com/list.m3u" });
  await writeFile(filePath, JSON.stringify({ version: 1, devices: {
    [PRIVATE_ID]: { keyHash: crypto.createHash("sha256").update(DEVICE_KEY).digest("hex"), profileToken: token, createdAt: 1 },
  } }));
  const restored = new DeviceProfileStore(filePath);
  const snapshot = await restored.snapshot(PRIVATE_ID);
  assert.equal(snapshot.playlists.length, 1);
  assert.equal(snapshot.playlists[0].id, "legacy");
  assert.equal(snapshot.playlists[0].kind, "m3u");
  assert.equal(snapshot.playlists[0].name, "قديمة");
  assert.equal(snapshot.playlists[0].serverName, "example.com");
  assert.equal(await restored.profileToken(PRIVATE_ID), token);
  const migrated = JSON.parse(await readFile(filePath, "utf8"));
  assert.equal(migrated.version, 2);
  assert.equal(Object.hasOwn(migrated.devices[PRIVATE_ID], "profileToken"), false);
  assert.equal(migrated.devices[PRIVATE_ID].playlists[0].kind, "m3u");
}));
