import assert from "node:assert/strict";
import os from "node:os";
import path from "node:path";
import { mkdtemp, rm } from "node:fs/promises";
import test from "node:test";
import { DeviceProfileStore, persistDeviceSessionFromHeaders } from "../lib/device-profile-store.mjs";

test("device profile requires its private device key and persists encrypted profile text", async (context) => {
  const directory = await mkdtemp(path.join(os.tmpdir(), "blofy-device-profile-test-"));
  context.after(() => rm(directory, { recursive: true, force: true }));
  const file = path.join(directory, "profiles.json");
  const store = new DeviceProfileStore(file);
  const deviceId = "BLOFY-1111-AAAA-2222-BBBB";
  const deviceKey = "A".repeat(64);
  const wrongKey = "B".repeat(64);

  const registration = await store.register(deviceId, deviceKey);
  await store.configure(deviceId, registration.keyHash, "sealed-profile-token");
  assert.equal(await store.profile(deviceId, deviceKey), "sealed-profile-token");
  await assert.rejects(() => store.profile(deviceId, wrongKey), /غير مسجل|تغيّر مفتاحه/);

  const reloaded = new DeviceProfileStore(file);
  assert.equal(await reloaded.profile(deviceId, deviceKey), "sealed-profile-token");
});

test("manual native session is persisted only for a registered device key", async (context) => {
  const directory = await mkdtemp(path.join(os.tmpdir(), "blofy-manual-session-test-"));
  context.after(() => rm(directory, { recursive: true, force: true }));
  const store = new DeviceProfileStore(path.join(directory, "profiles.json"));
  const deviceId = "BLOFY-AAAA-1111-BBBB-2222";
  const deviceKey = "C".repeat(64);
  await store.register(deviceId, deviceKey);

  assert.equal(await persistDeviceSessionFromHeaders(store, {
    "x-blofy-device-id": deviceId,
    "x-blofy-device-key": deviceKey,
  }, "sealed-manual-session"), true);
  assert.equal(await store.profile(deviceId, deviceKey), "sealed-manual-session");

  assert.equal(await persistDeviceSessionFromHeaders(store, {
    "x-blofy-device-id": deviceId,
    "x-blofy-device-key": "D".repeat(64),
  }, "attacker-session"), false);
  assert.equal(await store.profile(deviceId, deviceKey), "sealed-manual-session");
  assert.equal(await persistDeviceSessionFromHeaders(store, {}, "browser-session"), false);
});
