import crypto from "node:crypto";
import path from "node:path";
import { mkdir, readFile, rename, writeFile } from "node:fs/promises";

const SHORT_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

function cleanDeviceId(value) {
  const id = String(value || "").trim().toUpperCase();
  if (!/^BLOFY-[A-Z0-9-]{8,32}$/.test(id)) throw new Error("رقم الجهاز الخاص غير صالح.");
  return id;
}

function cleanDisplayId(value) {
  const id = String(value || "").trim().toUpperCase();
  if (!/^BLOFY-[A-Z0-9]{2}$/.test(id)) throw new Error("رقم الجهاز المختصر يجب أن يكون مثل BLOFY-A7.");
  return id;
}

function cleanPairingCode(value) {
  const code = String(value || "").replace(/\D/g, "");
  if (!/^\d{6}$/.test(code)) throw new Error("رمز الربط يجب أن يتكون من 6 أرقام.");
  return code;
}

function keyHash(value) {
  const key = String(value || "").trim().toUpperCase();
  if (!/^[A-F0-9]{64}$/.test(key)) throw new Error("مفتاح الجهاز غير صالح.");
  return crypto.createHash("sha256").update(key).digest("hex");
}

function safeEqual(left, right) {
  const first = Buffer.from(String(left || ""));
  const second = Buffer.from(String(right || ""));
  return first.length === second.length && crypto.timingSafeEqual(first, second);
}

function pairingHash(code, salt) {
  return crypto.scryptSync(cleanPairingCode(code), salt, 32).toString("hex");
}

function stablePairingCode(deviceKeyHash) {
  const digest = crypto.createHash("sha256").update(`blofy-pair:${deviceKeyHash}`).digest();
  return String(digest.readUInt32BE(0) % 1_000_000).padStart(6, "0");
}

function playlistId() {
  return crypto.randomUUID().replaceAll("-", "").slice(0, 16);
}

function copy(value) {
  return JSON.parse(JSON.stringify(value));
}

export class DeviceProfileStore {
  constructor(filePath, { now = () => Date.now() } = {}) {
    this.filePath = filePath;
    this.now = now;
    this.data = { version: 2, devices: {}, aliases: {} };
    this.loaded = false;
    this.queue = Promise.resolve();
  }

  async ensureLoaded() {
    if (this.loaded) return;
    try {
      const parsed = JSON.parse(await readFile(this.filePath, "utf8"));
      if (parsed?.devices && typeof parsed.devices === "object") {
        this.data.devices = parsed.devices;
        this.data.aliases = parsed.aliases && typeof parsed.aliases === "object" ? parsed.aliases : {};
      }
    } catch (error) {
      if (error?.code !== "ENOENT") throw error;
    }
    this.data.version = 2;
    for (const [deviceId, record] of Object.entries(this.data.devices)) this.migrateRecord(deviceId, record);
    this.loaded = true;
  }

  migrateRecord(deviceId, record) {
    record.playlists = Array.isArray(record.playlists) ? record.playlists : [];
    if (record.profileToken && !record.playlists.length) {
      const now = Number(record.updatedAt || record.createdAt || this.now());
      record.playlists.push({ id: "legacy", name: "قائمتي", kind: "unknown", serverName: "", profileToken: record.profileToken,
        status: "unknown", createdAt: now, updatedAt: now, lastTestedAt: 0, latencyMs: 0 });
      record.defaultPlaylistId = "legacy";
    }
    delete record.profileToken;
    record.defaultPlaylistId = record.playlists.some((item) => item.id === record.defaultPlaylistId)
      ? record.defaultPlaylistId : record.playlists[0]?.id || "";
    record.revision = Math.max(1, Number(record.revision || 1));
    record.portalVersion = Math.max(1, Number(record.portalVersion || 1));
    if (record.displayId && /^BLOFY-[A-Z0-9]{2}$/.test(record.displayId)) this.data.aliases[record.displayId] = deviceId;
  }

  async persist() {
    await mkdir(path.dirname(this.filePath), { recursive: true });
    const temporary = `${this.filePath}.${process.pid}.${crypto.randomBytes(4).toString("hex")}.tmp`;
    await writeFile(temporary, `${JSON.stringify(this.data, null, 2)}\n`, { mode: 0o600 });
    await rename(temporary, this.filePath);
  }

  locked(operation) {
    const run = this.queue.then(async () => { await this.ensureLoaded(); return operation(); });
    this.queue = run.catch(() => {});
    return run;
  }

  assignDisplayId(deviceId, deviceKeyHash, preferred = "") {
    const preferredId = String(preferred || "").trim().toUpperCase();
    if (/^BLOFY-[A-Z0-9]{2}$/.test(preferredId) && (!this.data.aliases[preferredId] || this.data.aliases[preferredId] === deviceId)) {
      return preferredId;
    }
    const digest = crypto.createHash("sha256").update(`${deviceId}:${deviceKeyHash}`).digest();
    for (let offset = 0; offset < digest.length - 1; offset += 1) {
      const candidate = `BLOFY-${SHORT_ALPHABET[digest[offset] % SHORT_ALPHABET.length]}${SHORT_ALPHABET[digest[offset + 1] % SHORT_ALPHABET.length]}`;
      if (!this.data.aliases[candidate] || this.data.aliases[candidate] === deviceId) return candidate;
    }
    throw new Error("تعذر تخصيص رقم مختصر لهذا الجهاز.");
  }

  register(deviceId, deviceKey, { displayId = "", pairingCode = "" } = {}) {
    return this.locked(async () => {
      const id = cleanDeviceId(deviceId);
      const hash = keyHash(deviceKey);
      let record = this.data.devices[id];
      if (record && !safeEqual(record.keyHash, hash)) throw new Error("الجهاز مسجل بمفتاح مختلف. استخدم استعادة الجهاز من الإدارة.");
      const code = pairingCode ? cleanPairingCode(pairingCode) : stablePairingCode(hash);
      if (!record) {
        const assigned = this.assignDisplayId(id, hash, displayId);
        const salt = crypto.randomBytes(16).toString("hex");
        const now = this.now();
        record = { keyHash: hash, displayId: assigned, pairingSalt: salt, pairingCodeHash: pairingHash(code, salt),
          portalVersion: 1, playlists: [], defaultPlaylistId: "", revision: 1, createdAt: now, updatedAt: now };
        this.data.devices[id] = record;
        this.data.aliases[assigned] = id;
        await this.persist();
      } else {
        this.migrateRecord(id, record);
        let changed = false;
        if (!record.displayId) {
          record.displayId = this.assignDisplayId(id, hash, displayId);
          this.data.aliases[record.displayId] = id;
          changed = true;
        }
        if (!record.pairingSalt || !record.pairingCodeHash) {
          record.pairingSalt = crypto.randomBytes(16).toString("hex");
          record.pairingCodeHash = pairingHash(code, record.pairingSalt);
          changed = true;
        } else {
          const suppliedHash = pairingHash(code, record.pairingSalt);
          if (!safeEqual(record.pairingCodeHash, suppliedHash)) {
            // Registration is authenticated by the device key. Rotating to the
            // six digits currently displayed on that TV keeps portal login and
            // the device screen synchronized after reinstall/migration.
            record.pairingCodeHash = suppliedHash;
            record.portalVersion = Number(record.portalVersion || 1) + 1;
            changed = true;
          }
        }
        if (changed) {
          record.updatedAt = this.now();
          await this.persist();
        }
      }
      return { deviceId: id, displayId: record.displayId, pairingCode: code, keyHash: hash,
        portalVersion: record.portalVersion, revision: record.revision };
    });
  }

  login(displayId, pairingCode) {
    return this.locked(async () => {
      const alias = cleanDisplayId(displayId);
      const deviceId = this.data.aliases[alias];
      const record = deviceId ? this.data.devices[deviceId] : null;
      const supplied = record?.pairingSalt ? pairingHash(pairingCode, record.pairingSalt) : "";
      if (!record || !safeEqual(record.pairingCodeHash, supplied)) throw new Error("رقم الجهاز أو رمز الربط غير صحيح.");
      record.lastPortalLoginAt = this.now();
      await this.persist();
      return { deviceId, displayId: alias, portalVersion: record.portalVersion, revision: record.revision };
    });
  }

  portal(deviceId, portalVersion) {
    return this.locked(async () => {
      const id = cleanDeviceId(deviceId);
      const record = this.data.devices[id];
      if (!record || Number(record.portalVersion) !== Number(portalVersion)) throw new Error("انتهت جلسة إدارة الجهاز.");
      return { deviceId: id, displayId: record.displayId, portalVersion: record.portalVersion, revision: record.revision };
    });
  }

  withDeviceKey(deviceId, deviceKey) {
    return this.locked(async () => {
      const id = cleanDeviceId(deviceId);
      const record = this.data.devices[id];
      if (!record || !safeEqual(record.keyHash, keyHash(deviceKey))) throw new Error("الجهاز غير مسجل أو تغيّر مفتاحه.");
      this.migrateRecord(id, record);
      return { deviceId: id, displayId: record.displayId, portalVersion: record.portalVersion, revision: record.revision };
    });
  }

  snapshot(deviceId) {
    return this.locked(async () => {
      const id = cleanDeviceId(deviceId);
      const record = this.data.devices[id];
      if (!record) throw new Error("الجهاز غير مسجل.");
      this.migrateRecord(id, record);
      return copy({ deviceId: id, displayId: record.displayId, revision: record.revision,
        defaultPlaylistId: record.defaultPlaylistId, playlists: record.playlists });
    });
  }

  createPlaylist(deviceId, playlist) {
    return this.locked(async () => {
      const id = cleanDeviceId(deviceId);
      const record = this.data.devices[id];
      if (!record) throw new Error("الجهاز غير مسجل.");
      if (record.playlists.length >= 20) throw new Error("الحد الأقصى 20 قائمة تشغيل لكل جهاز.");
      const now = this.now();
      const entry = { id: playlistId(), name: String(playlist.name || "قائمتي").slice(0, 50), kind: playlist.kind,
        serverName: String(playlist.serverName || "").slice(0, 120), profileToken: String(playlist.profileToken || ""),
        status: playlist.status || "connected", createdAt: now, updatedAt: now,
        lastTestedAt: Number(playlist.lastTestedAt || now), latencyMs: Number(playlist.latencyMs || 0) };
      record.playlists.push(entry);
      if (!record.defaultPlaylistId || playlist.makeDefault) record.defaultPlaylistId = entry.id;
      record.revision += 1;
      record.updatedAt = now;
      await this.persist();
      return copy(entry);
    });
  }

  updatePlaylist(deviceId, playlistIdValue, changes) {
    return this.locked(async () => {
      const id = cleanDeviceId(deviceId);
      const record = this.data.devices[id];
      const entry = record?.playlists?.find((item) => item.id === String(playlistIdValue));
      if (!entry) throw new Error("قائمة التشغيل غير موجودة.");
      for (const field of ["name", "kind", "serverName", "profileToken", "status", "lastTestedAt", "latencyMs"]) {
        if (changes[field] !== undefined) entry[field] = changes[field];
      }
      entry.name = String(entry.name || "قائمتي").slice(0, 50);
      entry.serverName = String(entry.serverName || "").slice(0, 120);
      entry.updatedAt = this.now();
      record.revision += 1;
      record.updatedAt = entry.updatedAt;
      await this.persist();
      return copy(entry);
    });
  }

  deletePlaylist(deviceId, playlistIdValue) {
    return this.locked(async () => {
      const id = cleanDeviceId(deviceId);
      const record = this.data.devices[id];
      const index = record?.playlists?.findIndex((item) => item.id === String(playlistIdValue)) ?? -1;
      if (index < 0) throw new Error("قائمة التشغيل غير موجودة.");
      const [removed] = record.playlists.splice(index, 1);
      if (record.defaultPlaylistId === removed.id) record.defaultPlaylistId = record.playlists[0]?.id || "";
      record.revision += 1;
      record.updatedAt = this.now();
      await this.persist();
      return copy(removed);
    });
  }

  setDefault(deviceId, playlistIdValue) {
    return this.locked(async () => {
      const id = cleanDeviceId(deviceId);
      const record = this.data.devices[id];
      if (!record?.playlists?.some((item) => item.id === String(playlistIdValue))) throw new Error("قائمة التشغيل غير موجودة.");
      record.defaultPlaylistId = String(playlistIdValue);
      record.revision += 1;
      record.updatedAt = this.now();
      await this.persist();
      return { defaultPlaylistId: record.defaultPlaylistId, revision: record.revision };
    });
  }

  profileToken(deviceId, playlistIdValue = "") {
    return this.locked(async () => {
      const id = cleanDeviceId(deviceId);
      const record = this.data.devices[id];
      const requested = String(playlistIdValue || record?.defaultPlaylistId || "");
      const entry = record?.playlists?.find((item) => item.id === requested);
      return entry?.profileToken || "";
    });
  }

  configure(deviceId, expectedHash, profileToken) {
    return this.locked(async () => {
      const id = cleanDeviceId(deviceId);
      const record = this.data.devices[id];
      if (!record || !safeEqual(record.keyHash, expectedHash)) throw new Error("انتهت صلاحية ربط الجهاز. حدّث الباركود من التطبيق.");
      const now = this.now();
      let entry = record.playlists.find((item) => item.id === record.defaultPlaylistId) || record.playlists[0];
      if (!entry) {
        entry = { id: playlistId(), name: "قائمتي", kind: "unknown", serverName: "", profileToken: String(profileToken || ""),
          status: "connected", createdAt: now, updatedAt: now, lastTestedAt: now, latencyMs: 0 };
        record.playlists.push(entry);
      } else entry.profileToken = String(profileToken || "");
      record.defaultPlaylistId = entry.id;
      record.revision += 1;
      record.updatedAt = now;
      await this.persist();
      return { deviceId: id, configured: Boolean(entry.profileToken), updatedAt: now };
    });
  }

  async configureWithDeviceKey(deviceId, deviceKey, profileToken) {
    const auth = await this.withDeviceKey(deviceId, deviceKey);
    return this.configure(auth.deviceId, (await this.keyHashFor(auth.deviceId)), profileToken);
  }

  keyHashFor(deviceId) {
    return this.locked(async () => this.data.devices[cleanDeviceId(deviceId)]?.keyHash || "");
  }

  async profile(deviceId, deviceKey) {
    const auth = await this.withDeviceKey(deviceId, deviceKey);
    return this.profileToken(auth.deviceId);
  }

  async clearWithDeviceKey(deviceId, deviceKey) {
    const auth = await this.withDeviceKey(deviceId, deviceKey);
    return this.locked(async () => {
      const record = this.data.devices[auth.deviceId];
      record.playlists = [];
      record.defaultPlaylistId = "";
      record.revision += 1;
      record.updatedAt = this.now();
      await this.persist();
      return { deviceId: auth.deviceId, configured: false, updatedAt: record.updatedAt };
    });
  }
}

export async function persistDeviceSessionFromHeaders(store, headers = {}, profileToken = "") {
  const deviceId = String(headers["x-blofy-device-id"] || "").trim();
  const deviceKey = String(headers["x-blofy-device-key"] || "").trim();
  if (!deviceId || !deviceKey || !profileToken) return false;
  try { await store.configureWithDeviceKey(deviceId, deviceKey, profileToken); return true; } catch { return false; }
}

export { cleanDeviceId, cleanDisplayId, cleanPairingCode, keyHash, stablePairingCode };
