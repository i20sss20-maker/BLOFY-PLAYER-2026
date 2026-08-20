import assert from "node:assert/strict";
import os from "node:os";
import path from "node:path";
import { mkdtemp, rm } from "node:fs/promises";
import test from "node:test";
import { LicenseStore } from "../lib/license-store.mjs";

test("license trial persists and a code activates only its allowed device", async (context) => {
  const directory = await mkdtemp(path.join(os.tmpdir(), "blofy-license-test-"));
  context.after(() => rm(directory, { recursive: true, force: true }));
  let now = Date.UTC(2026, 7, 20);
  const file = path.join(directory, "licenses.json");
  const store = new LicenseStore(file, { trialDays: 7, now: () => now });
  const device = "BLOFY-1111-2222-3333-4444";

  const trial = await store.get(device);
  assert.equal(trial.plan, "trial");
  assert.equal(trial.remainingDays, 7);

  await store.createCode({ code: "20260821", days: 30, maxUses: 1 });
  const active = await store.redeem(device, "20260821");
  assert.equal(active.plan, "active");
  assert.equal(active.remainingDays, 30);

  await assert.rejects(
    () => store.redeem("BLOFY-AAAA-BBBB-CCCC-DDDD", "20260821"),
    /استخدام رمز التفعيل بالكامل/,
  );

  now += 31 * 86_400_000;
  const reloaded = new LicenseStore(file, { trialDays: 7, now: () => now });
  assert.equal((await reloaded.get(device)).plan, "expired");
});

test("generated activation codes contain exactly eight digits", async (context) => {
  const directory = await mkdtemp(path.join(os.tmpdir(), "blofy-license-code-test-"));
  context.after(() => rm(directory, { recursive: true, force: true }));
  const store = new LicenseStore(path.join(directory, "licenses.json"));
  const entry = await store.createCode();
  assert.match(entry.code, /^[0-9]{8}$/);
});
