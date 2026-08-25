import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import http from "node:http";
import { mkdtemp, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";

const PRIVATE_ID = "BLOFY-ABCD-EFGH-JKLM-NPQR";
const DEVICE_KEY = "B".repeat(64);

async function startProvider() {
  const state = { m3uHealthy: true };
  const server = http.createServer((req, res) => {
    const url = new URL(req.url, "http://localhost");
    if (url.pathname === "/player_api.php") {
      const authenticated = url.searchParams.get("username") === "api-user" && url.searchParams.get("password") === "api-secret";
      const body = JSON.stringify({ user_info: { auth: authenticated ? 1 : 0, username: url.searchParams.get("username"), status: authenticated ? "Active" : "Disabled",
        exp_date: "2000000000", max_connections: "2", active_cons: "0" }, server_info: {} });
      res.writeHead(200, { "content-type": "application/json", "content-length": Buffer.byteLength(body) });
      res.end(body);
      return;
    }
    if (url.pathname === "/list.m3u") {
      if (!state.m3uHealthy) { res.writeHead(503); res.end("offline"); return; }
      const body = "#EXTM3U\n#EXTINF:-1 group-title=\"Test\",Channel\nhttps://media.example/live.ts\n";
      res.writeHead(200, { "content-type": "application/x-mpegURL", "content-length": Buffer.byteLength(body) });
      res.end(body);
      return;
    }
    res.writeHead(404); res.end();
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  return { state, server, origin: `http://127.0.0.1:${server.address().port}` };
}

async function startServer(directory, extraEnv = {}) {
  const child = spawn(process.execPath, [new URL("../server.mjs", import.meta.url).pathname], {
    env: { ...process.env, PORT: "0", NODE_ENV: "test", LICENSE_DB_PATH: path.join(directory, "licenses.json"),
      DEVICE_PROFILE_DB_PATH: path.join(directory, "device-profiles.json"), SESSION_SECRET: "portal-api-test-secret-that-is-long-enough",
      ALLOW_PRIVATE_URLS_FOR_TESTS: "1", TRUSTED_PROXY_HOPS: "1", REQUEST_TIMEOUT_MS: "3000", ...extraEnv },
    stdio: ["ignore", "pipe", "pipe"],
  });
  let stderr = "";
  child.stderr.setEncoding("utf8");
  child.stderr.on("data", (chunk) => { stderr += chunk; });
  const port = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`server start timed out: ${stderr}`)), 5_000);
    child.stdout.setEncoding("utf8");
    child.stdout.on("data", (chunk) => {
      const match = chunk.match(/ready on port (\d+)/);
      if (match) { clearTimeout(timer); resolve(Number(match[1])); }
    });
    child.once("exit", (code) => { clearTimeout(timer); reject(new Error(`server exited ${code}: ${stderr}`)); });
  });
  return { process: child, origin: `http://127.0.0.1:${port}` };
}

async function stopServer(running) {
  if (!running?.process || running.process.exitCode !== null) return;
  running.process.kill("SIGTERM");
  await new Promise((resolve) => running.process.once("exit", resolve));
}

function jsonRequest(method, body, cookie = "") {
  return { method, headers: { ...(body ? { "content-type": "application/json" } : {}), ...(cookie ? { cookie } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {}) };
}

test("full portal API supports encrypted CRUD, connect, fresh tests, bootstrap, logout, and one-time QR", async () => {
  const directory = await mkdtemp(path.join(os.tmpdir(), "blofy-portal-api-"));
  const provider = await startProvider();
  const running = await startServer(directory, { PLAYBACK_SESSION_MAX_AGE_SECONDS: "1" });
  try {
    const health = await (await fetch(`${running.origin}/api/health`)).json();
    assert.equal(health.version, "2026.08.25.15-v323");
    assert.equal(health.portal, "v323-multi-playlist");
    const portalHtml = await (await fetch(`${running.origin}/activate`)).text();
    assert.match(portalHtml, /\/brand\.css/);
    assert.match(portalHtml, /\/assets\/blofy-logo-192\.png/);
    assert.equal((await fetch(`${running.origin}/brand.css`)).status, 200);

    const register = await fetch(`${running.origin}/api/device/register`, jsonRequest("POST", {
      deviceId: PRIVATE_ID, deviceKey: DEVICE_KEY, displayId: "BLOFY-D4", pairingCode: "654321",
    }));
    assert.equal(register.status, 201);
    const registered = await register.json();
    assert.equal(registered.displayId, "BLOFY-D4");
    assert.match(registered.pairToken, /.+/);
    assert.equal((await fetch(`${running.origin}/api/license?device_id=${encodeURIComponent(PRIVATE_ID)}`)).status, 200);

    const login = await fetch(`${running.origin}/api/device/login`, jsonRequest("POST", { deviceId: "BLOFY-D4", pairingCode: "654321" }));
    assert.equal(login.status, 200);
    const portalCookie = login.headers.get("set-cookie").split(";")[0];
    assert.match(login.headers.get("set-cookie"), /HttpOnly/);
    assert.match(login.headers.get("set-cookie"), /SameSite=Strict/);

    const createdXtreamResponse = await fetch(`${running.origin}/api/device/playlists`, jsonRequest("POST", {
      name: "الرئيسية", kind: "xtream", serverUrl: provider.origin, username: "api-user", password: "api-secret",
    }, portalCookie));
    assert.equal(createdXtreamResponse.status, 201);
    const createdXtream = await createdXtreamResponse.json();
    const xtreamId = createdXtream.playlist.id;
    assert.equal(JSON.stringify(createdXtream).includes("api-secret"), false);
    assert.equal(JSON.stringify(createdXtream).includes("api-user"), false);

    const detail = await (await fetch(`${running.origin}/api/device/playlists/${xtreamId}`, { headers: { cookie: portalCookie } })).json();
    assert.equal(detail.playlist.username, "api-user");
    assert.equal(detail.playlist.passwordPresent, true);
    assert.equal(Object.hasOwn(detail.playlist, "password"), false);

    const updatedResponse = await fetch(`${running.origin}/api/device/playlists/${xtreamId}`, jsonRequest("PATCH", {
      name: "الرئيسية المعدلة", kind: "xtream", serverUrl: provider.origin, username: "api-user", password: "",
    }, portalCookie));
    assert.equal(updatedResponse.status, 200);
    assert.equal((await updatedResponse.json()).playlist.name, "الرئيسية المعدلة");
    const detailAfterBlankPassword = await (await fetch(`${running.origin}/api/device/playlists/${xtreamId}`, { headers: { cookie: portalCookie } })).json();
    assert.equal(detailAfterBlankPassword.playlist.passwordPresent, true);
    assert.equal(Object.hasOwn(detailAfterBlankPassword.playlist, "password"), false);

    const createdM3uResponse = await fetch(`${running.origin}/api/device/playlists`, jsonRequest("POST", {
      name: "M3U", kind: "m3u", url: `${provider.origin}/list.m3u`, makeDefault: true,
    }, portalCookie));
    assert.equal(createdM3uResponse.status, 201);
    const m3u = await createdM3uResponse.json();
    const m3uId = m3u.playlist.id;
    assert.equal(m3u.defaultPlaylistId, m3uId);
    const listBody = await (await fetch(`${running.origin}/api/device/playlists`, { headers: { cookie: portalCookie } })).json();
    assert.equal(JSON.stringify(listBody).includes("api-secret"), false);
    assert.equal(JSON.stringify(listBody).includes("api-user"), false);

    const setDefault = await fetch(`${running.origin}/api/device/playlists/${xtreamId}/default`, jsonRequest("POST", null, portalCookie));
    assert.equal(setDefault.status, 200);
    assert.equal((await setDefault.json()).defaultPlaylistId, xtreamId);
    const connect = await fetch(`${running.origin}/api/device/playlists/${xtreamId}/connect`, jsonRequest("POST", null, portalCookie));
    assert.equal(connect.status, 200);
    const playbackCookie = connect.headers.get("set-cookie").split(";")[0];
    assert.equal(JSON.stringify(await connect.json()).includes("api-secret"), false);
    assert.equal((await (await fetch(`${running.origin}/api/session`, { headers: { cookie: playbackCookie } })).json()).session.name, "الرئيسية المعدلة");
    await new Promise((resolve) => setTimeout(resolve, 1100));
    assert.equal((await (await fetch(`${running.origin}/api/session`, { headers: { cookie: playbackCookie } })).json()).session, null);

    const bootstrap = await fetch(`${running.origin}/api/device/bootstrap?device_id=${encodeURIComponent(PRIVATE_ID)}&connect=0`, {
      headers: { "x-blofy-device-id": PRIVATE_ID, "x-blofy-device-key": DEVICE_KEY },
    });
    assert.equal(bootstrap.status, 200);
    const bootstrapBody = await bootstrap.json();
    assert.equal(bootstrapBody.playlists.length, 2);
    assert.equal(Object.hasOwn(bootstrapBody, "profile"), false);
    assert.equal(JSON.stringify(bootstrapBody).includes("api-secret"), false);
    assert.equal(JSON.stringify(bootstrapBody).includes("api-user"), false);

    assert.equal((await fetch(`${running.origin}/api/device/playlists/${m3uId}/test`, jsonRequest("POST", null, portalCookie))).status, 200);
    provider.state.m3uHealthy = false;
    const failedTest = await fetch(`${running.origin}/api/device/playlists/${m3uId}/test`, jsonRequest("POST", null, portalCookie));
    assert.equal(failedTest.status, 422);
    const failedTestBody = await failedTest.json();
    assert.equal(failedTestBody.playlist.status, "error");
    assert.match(failedTestBody.playlist.lastError, /تعذر تحميل القائمة/);
    const listAfterFailure = await (await fetch(`${running.origin}/api/device/playlists`, { headers: { cookie: portalCookie } })).json();
    assert.equal(listAfterFailure.playlists.find((item) => item.id === m3uId).lastError, failedTestBody.playlist.lastError);
    const deleted = await fetch(`${running.origin}/api/device/playlists/${m3uId}`, jsonRequest("DELETE", null, portalCookie));
    assert.equal(deleted.status, 200);
    assert.equal((await deleted.json()).playlists.length, 1);

    const logout = await fetch(`${running.origin}/api/device/login`, jsonRequest("DELETE", null, portalCookie));
    assert.equal(logout.status, 200);
    assert.match(logout.headers.get("set-cookie"), /blofy_portal=.*Max-Age=0/);
    assert.match(logout.headers.get("set-cookie"), /blofy_session=.*Max-Age=0/);
    assert.equal((await fetch(`${running.origin}/api/device/playlists`, { headers: { cookie: portalCookie } })).status, 401);

    assert.equal((await fetch(`${running.origin}/api/device/login`, jsonRequest("POST", { pairToken: registered.pairToken }))).status, 200);
    assert.equal((await fetch(`${running.origin}/api/device/login`, jsonRequest("POST", { pairToken: registered.pairToken }))).status, 401);

    provider.state.m3uHealthy = true;
    const secondId = "BLOFY-2222-3333-4444-5555";
    const secondRegister = await fetch(`${running.origin}/api/device/register`, jsonRequest("POST", {
      deviceId: secondId, deviceKey: "E".repeat(64), displayId: "BLOFY-E5", pairingCode: "111222",
    }));
    const secondPair = (await secondRegister.json()).pairToken;
    const configureBody = { deviceId: secondId, pairToken: secondPair, kind: "m3u", name: "قديمة", url: `${provider.origin}/list.m3u` };
    assert.equal((await fetch(`${running.origin}/api/device/configure`, jsonRequest("POST", configureBody))).status, 200);
    assert.equal((await fetch(`${running.origin}/api/device/configure`, jsonRequest("POST", configureBody))).status, 403);
  } finally {
    await stopServer(running);
    await new Promise((resolve) => provider.server.close(resolve));
    await rm(directory, { recursive: true, force: true });
  }
});

test("login rate limit uses the trusted right side of the proxy chain", async () => {
  const directory = await mkdtemp(path.join(os.tmpdir(), "blofy-rate-api-"));
  const running = await startServer(directory);
  try {
    for (let index = 1; index <= 8; index += 1) {
      const response = await fetch(`${running.origin}/api/device/login`, { ...jsonRequest("POST", { deviceId: "BLOFY-Z9", pairingCode: "000000" }),
        headers: { "content-type": "application/json", "x-forwarded-for": `${index}.1.1.1, 8.8.8.8` } });
      assert.equal(response.status, 401);
    }
    const blocked = await fetch(`${running.origin}/api/device/login`, { ...jsonRequest("POST", { deviceId: "BLOFY-Z9", pairingCode: "000000" }),
      headers: { "content-type": "application/json", "x-forwarded-for": "99.1.1.1, 8.8.8.8" } });
    assert.equal(blocked.status, 429);
  } finally { await stopServer(running); await rm(directory, { recursive: true, force: true }); }
});

test("portal cookie has server-enforced expiry", async () => {
  const directory = await mkdtemp(path.join(os.tmpdir(), "blofy-expiry-api-"));
  const running = await startServer(directory, { PORTAL_SESSION_MAX_AGE_SECONDS: "1" });
  try {
    await fetch(`${running.origin}/api/device/register`, jsonRequest("POST", {
      deviceId: PRIVATE_ID, deviceKey: DEVICE_KEY, displayId: "BLOFY-T7", pairingCode: "777888",
    }));
    const login = await fetch(`${running.origin}/api/device/login`, jsonRequest("POST", { deviceId: "BLOFY-T7", pairingCode: "777888" }));
    const cookie = login.headers.get("set-cookie").split(";")[0];
    await new Promise((resolve) => setTimeout(resolve, 1100));
    assert.equal((await fetch(`${running.origin}/api/device/playlists`, { headers: { cookie } })).status, 401);
  } finally { await stopServer(running); await rm(directory, { recursive: true, force: true }); }
});

test("production refuses implicit ephemeral storage", async () => {
  const env = { ...process.env, NODE_ENV: "production", PORT: "0", SESSION_SECRET: "production-secret-that-is-long-enough" };
  for (const key of ["DATA_DIR", "LICENSE_DB_PATH", "DEVICE_PROFILE_DB_PATH", "ALLOW_EPHEMERAL_DATA"]) delete env[key];
  const child = spawn(process.execPath, [new URL("../server.mjs", import.meta.url).pathname], { env, stdio: ["ignore", "ignore", "pipe"] });
  let stderr = "";
  child.stderr.setEncoding("utf8");
  child.stderr.on("data", (chunk) => { stderr += chunk; });
  const code = await new Promise((resolve) => child.once("exit", resolve));
  assert.notEqual(code, 0);
  assert.match(stderr, /Persistent storage is required/);
});

test("production keeps the legacy Railway licence path and derives portal storage beside it", async () => {
  const directory = await mkdtemp(path.join(os.tmpdir(), "blofy-production-storage-"));
  const env = { ...process.env, NODE_ENV: "production", PORT: "0",
    SESSION_SECRET: "production-secret-that-is-long-enough",
    LICENSE_DB_PATH: path.join(directory, "licenses.json") };
  for (const key of ["DATA_DIR", "DEVICE_PROFILE_DB_PATH", "ALLOW_EPHEMERAL_DATA"]) delete env[key];
  const child = spawn(process.execPath, [new URL("../server.mjs", import.meta.url).pathname], {
    env, stdio: ["ignore", "pipe", "pipe"],
  });
  let stderr = "";
  child.stderr.setEncoding("utf8");
  child.stderr.on("data", (chunk) => { stderr += chunk; });
  try {
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`production server start timed out: ${stderr}`)), 5_000);
      child.stdout.setEncoding("utf8");
      child.stdout.on("data", (chunk) => {
        if (/ready on port \d+/.test(chunk)) { clearTimeout(timer); resolve(); }
      });
      child.once("exit", (code) => { clearTimeout(timer); reject(new Error(`production server exited ${code}: ${stderr}`)); });
    });
  } finally {
    if (child.exitCode === null) {
      child.kill("SIGTERM");
      await new Promise((resolve) => child.once("exit", resolve));
    }
    await rm(directory, { recursive: true, force: true });
  }
});
