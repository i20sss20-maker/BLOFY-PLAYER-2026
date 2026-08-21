import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import test from "node:test";
import { fetchSafe, isPrivateIp, licensePayloadIsActive, parseCookies, signResource, verifyResource } from "../lib/security.mjs";

test("signed media token encrypts credentials and verifies the original URL", () => {
  const source = "https://provider.example/live/private-user/private-password/123.m3u8";
  const token = signResource(source, 60);
  assert.equal(verifyResource(token.encoded, token.expires, token.signature), source);
  assert.equal(token.encoded.includes("private-user"), false);
  assert.equal(token.encoded.includes(Buffer.from(source).toString("base64url")), false);
  assert.equal(verifyResource(token.encoded, token.expires, `${token.signature}x`), null);
});

test("malformed cookie encoding does not crash the request", () => {
  const cookies = parseCookies("blofy_session=%E0%A4%A; plain=value");
  assert.equal(cookies.blofy_session, "%E0%A4%A");
  assert.equal(cookies.plain, "value");
});

test("license payload requires an allowed plan and a future expiry", () => {
  const now = 1_700_000_000_000;
  assert.equal(licensePayloadIsActive({ deviceId: "BLOFY-TEST-0000", plan: "trial", expiresAt: now + 1 }, now), true);
  assert.equal(licensePayloadIsActive({ deviceId: "BLOFY-TEST-0000", plan: "active", expiresAt: now + 1 }, now), true);
  assert.equal(licensePayloadIsActive({ deviceId: "BLOFY-TEST-0000", plan: "expired", expiresAt: now + 100_000 }, now), false);
  assert.equal(licensePayloadIsActive({ deviceId: "BLOFY-TEST-0000", plan: "active", expiresAt: now }, now), false);
  assert.equal(licensePayloadIsActive({ plan: "active", expiresAt: now + 1 }, now), false);
});

test("native license cookie is bound to its declared device id", () => {
  const now = 1_700_000_000_000;
  const payload = { deviceId: "BLOFY-DEVICE-1234", plan: "active", expiresAt: now + 60_000 };
  assert.equal(licensePayloadIsActive(payload, now, "blofy-device-1234"), true);
  assert.equal(licensePayloadIsActive(payload, now, "BLOFY-OTHER-9999"), false);
  assert.equal(licensePayloadIsActive(payload, now), true);
});

test("production refuses to start without an explicit SESSION_SECRET", () => {
  const moduleUrl = new URL("../lib/security.mjs", import.meta.url).href;
  const missingEnvironment = { ...process.env, NODE_ENV: "production" };
  delete missingEnvironment.SESSION_SECRET;
  const missing = spawnSync(process.execPath, ["--input-type=module", "-e", "await import(process.argv[1])", moduleUrl], {
    encoding: "utf8",
    env: missingEnvironment,
  });
  assert.notEqual(missing.status, 0);
  assert.match(`${missing.stderr}${missing.stdout}`, /SESSION_SECRET must be explicitly configured/);

  const configured = spawnSync(process.execPath, ["--input-type=module", "-e", "await import(process.argv[1])", moduleUrl], {
    encoding: "utf8",
    env: { ...process.env, NODE_ENV: "production", SESSION_SECRET: "test-only-explicit-session-secret-123456789" },
  });
  assert.equal(configured.status, 0, configured.stderr);
});

test("safe fetch honors caller cancellation so a closed relay cannot keep its upstream request", async () => {
  const originalFetch = globalThis.fetch;
  const controller = new AbortController();
  try {
    globalThis.fetch = (_url, options) => new Promise((_resolve, reject) => {
      const rejectCancelled = () => reject(Object.assign(new Error("cancelled"), { name: "AbortError" }));
      if (options.signal.aborted) rejectCancelled();
      else options.signal.addEventListener("abort", rejectCancelled, { once: true });
    });
    const pending = fetchSafe("http://8.8.8.8/media.ts", { signal: controller.signal, requestTimeoutMs: 10_000 });
    controller.abort();
    await assert.rejects(pending, (error) => error?.name === "AbortError");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("SSRF guard blocks local, metadata, mapped, and reserved network ranges", () => {
  for (const address of ["127.0.0.1", "10.0.0.8", "169.254.169.254", "100.64.0.1", "::1", "::ffff:127.0.0.1", "fe80::1"]) {
    assert.equal(isPrivateIp(address), true, address);
  }
  assert.equal(isPrivateIp("8.8.8.8"), false);
  assert.equal(isPrivateIp("2606:4700:4700::1111"), false);
});
