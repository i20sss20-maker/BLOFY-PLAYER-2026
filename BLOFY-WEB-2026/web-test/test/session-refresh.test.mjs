import assert from "node:assert/strict";
import test from "node:test";
import { providerSessionCacheKey, providerSessionResponseStatus, refreshProviderSession } from "../lib/session-refresh.mjs";

const session = {
  kind: "xtream",
  serverUrl: "https://provider.example",
  username: "customer",
  password: "private-password",
  name: "الباقة الأساسية",
  serverName: "old.example",
  account: { status: "Active", expiresAt: 1 },
};

test("provider refresh cache key follows credentials, not the mutable account snapshot", () => {
  const original = providerSessionCacheKey(session);
  assert.equal(providerSessionCacheKey({
    ...session,
    account: { status: "Expired", expiresAt: 2 },
    serverName: "provider.example",
  }), original);
  assert.notEqual(providerSessionCacheKey({ ...session, password: "changed" }), original);
  assert.notEqual(providerSessionCacheKey({ ...session, username: "other" }), original);
});

test("Xtream revalidation updates account metadata without mutating credentials", async () => {
  const refreshed = await refreshProviderSession(session, (received) => {
    assert.equal(received, session);
    return {
      accountStatus: async () => ({
        authenticated: false,
        account: {
          authenticated: false,
          username: "customer",
          status: "Expired",
          expiresAt: 1_700_000_000_000,
          maxConnections: 2,
          activeConnections: 0,
          serverName: "provider.example",
        },
      }),
    };
  });

  assert.notEqual(refreshed, session);
  assert.equal(refreshed.password, "private-password");
  assert.equal(refreshed.name, "الباقة الأساسية");
  assert.equal(refreshed.serverName, "provider.example");
  assert.equal(refreshed.account.status, "Expired");
  assert.equal(refreshed.account.authenticated, false);
  assert.equal(providerSessionResponseStatus(refreshed), 402);
  assert.equal(session.account.status, "Active");
});

test("an active or legacy account remains a successful refresh", () => {
  assert.equal(providerSessionResponseStatus({ account: { authenticated: true } }), 200);
  assert.equal(providerSessionResponseStatus({ account: { status: "Active" } }), 200);
});

test("provider outage rejects refresh and leaves the previous session intact", async () => {
  const snapshot = structuredClone(session);
  await assert.rejects(
    () => refreshProviderSession(session, () => ({
      accountStatus: async () => { throw new Error("provider unavailable"); },
    })),
    /provider unavailable/,
  );
  assert.deepEqual(session, snapshot);
});

test("M3U sessions are returned unchanged because Xtream revalidation does not apply", async () => {
  const m3u = { kind: "m3u", url: "https://provider.example/list.m3u" };
  assert.equal(await refreshProviderSession(m3u, () => { throw new Error("must not run"); }), m3u);
});
