import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdtemp, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";

async function startServer(directory) {
  const child = spawn(process.execPath, [new URL("../server.mjs", import.meta.url).pathname], {
    env: {
      ...process.env,
      PORT: "0",
      NODE_ENV: "development",
      LICENSE_DB_PATH: path.join(directory, "licenses.json"),
      DEVICE_PROFILE_DB_PATH: path.join(directory, "device-profiles.json"),
      SESSION_SECRET: "portal-api-test-secret-that-is-long-enough",
    },
    stdio: ["ignore", "pipe", "pipe"],
  });
  const port = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("server start timed out")), 5_000);
    child.stdout.setEncoding("utf8");
    child.stdout.on("data", (chunk) => {
      const match = chunk.match(/ready on port (\d+)/);
      if (match) { clearTimeout(timer); resolve(Number(match[1])); }
    });
    child.once("exit", (code) => { clearTimeout(timer); reject(new Error(`server exited ${code}`)); });
  });
  return { process: child, origin: `http://127.0.0.1:${port}` };
}

test("portal login exposes credential-free synchronized playlists", async () => {
  const directory = await mkdtemp(path.join(os.tmpdir(), "blofy-portal-api-"));
  const running = await startServer(directory);
  try {
    const register = await fetch(`${running.origin}/api/device/register`, {
      method: "POST", headers: { "content-type": "application/json" },
      body: JSON.stringify({ deviceId: "BLOFY-ABCD-EFGH-JKLM-NPQR", deviceKey: "B".repeat(64), displayId: "BLOFY-D4", pairingCode: "654321" }),
    });
    assert.equal(register.status, 201);
    const registered = await register.json();
    assert.equal(registered.displayId, "BLOFY-D4");
    assert.equal(registered.pairingCode, "654321");

    const login = await fetch(`${running.origin}/api/device/login`, {
      method: "POST", headers: { "content-type": "application/json" },
      body: JSON.stringify({ deviceId: "BLOFY-D4", pairingCode: "654321" }),
    });
    assert.equal(login.status, 200);
    const cookie = login.headers.get("set-cookie");
    assert.match(cookie, /^blofy_portal=/);
    assert.match(cookie, /HttpOnly/);

    const playlists = await fetch(`${running.origin}/api/device/playlists`, { headers: { cookie } });
    assert.equal(playlists.status, 200);
    assert.deepEqual(await playlists.json(), {
      deviceId: "BLOFY-ABCD-EFGH-JKLM-NPQR", displayId: "BLOFY-D4", revision: 1, defaultPlaylistId: "", playlists: [],
    });

    const unauthorized = await fetch(`${running.origin}/api/device/playlists`);
    assert.equal(unauthorized.status, 401);
  } finally {
    running.process.kill("SIGTERM");
    await new Promise((resolve) => running.process.once("exit", resolve));
    await rm(directory, { recursive: true, force: true });
  }
});
