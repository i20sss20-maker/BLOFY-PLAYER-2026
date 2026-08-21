import crypto from "node:crypto";
import path from "node:path";
import { mkdir, readFile, rename, writeFile } from "node:fs/promises";

function cleanDeviceId(value) {
  const id = String(value || "").trim().toUpperCase();
  if (!/^BLOFY-[A-Z0-9-]{8,32}$/.test(id)) throw new Error("رقم الجهاز غير صالح.");
  return id;
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

export class DeviceProfileStore {
  constructor(filePath) {
    this.filePath = filePath;
    this.data = { version: 1, devices: {} };
    this.loaded = false;
    this.queue = Promise.resolve();
  }

  async ensureLoaded() {
    if (this.loaded) return;
    try {
      const parsed = JSON.parse(await readFile(this.filePath, "utf8"));
      if (parsed?.devices && typeof parsed.devices === "object") this.data.devices = parsed.devices;
    } catch (error) {
      if (error?.code !== "ENOENT") throw error;
    }
    this.loaded = true;
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

  register(deviceId, deviceKey) {
    return this.locked(async () => {
      const id = cleanDeviceId(deviceId);
      const hash = keyHash(deviceKey);
      const current = this.data.devices[id];
      if (!current || !safeEqual(current.keyHash, hash)) {
        this.data.devices[id] = { keyHash: hash, profileToken: "", createdAt: Date.now(), updatedAt: Date.now() };
        await this.persist();
      }
      return { deviceId: id, keyHash: hash };
    });
  }

  configure(deviceId, expectedHash, profileToken) {
    return this.locked(async () => {
      const id = cleanDeviceId(deviceId);
      const record = this.data.devices[id];
      if (!record || !safeEqual(record.keyHash, expectedHash)) throw new Error("انتهت صلاحية ربط الجهاز. حدّث الباركود من التطبيق.");
      record.profileToken = String(profileToken || "");
      record.updatedAt = Date.now();
      await this.persist();
      return { deviceId: id, configured: Boolean(record.profileToken), updatedAt: record.updatedAt };
    });
  }

  configureWithDeviceKey(deviceId, deviceKey, profileToken) {
    return this.locked(async () => {
      const id = cleanDeviceId(deviceId);
      const record = this.data.devices[id];
      const suppliedHash = keyHash(deviceKey);
      if (!record || !safeEqual(record.keyHash, suppliedHash)) {
        throw new Error("الجهاز غير مسجل أو تغيّر مفتاحه.");
      }
      record.profileToken = String(profileToken || "");
      record.updatedAt = Date.now();
      await this.persist();
      return { deviceId: id, configured: Boolean(record.profileToken), updatedAt: record.updatedAt };
    });
  }

  profile(deviceId, deviceKey) {
    return this.locked(async () => {
      const id = cleanDeviceId(deviceId);
      const record = this.data.devices[id];
      if (!record || !safeEqual(record.keyHash, keyHash(deviceKey))) throw new Error("الجهاز غير مسجل أو تغيّر مفتاحه.");
      return record.profileToken || "";
    });
  }
}

export async function persistDeviceSessionFromHeaders(store, headers = {}, profileToken = "") {
  const deviceId = String(headers["x-blofy-device-id"] || "").trim();
  const deviceKey = String(headers["x-blofy-device-key"] || "").trim();
  if (!deviceId || !deviceKey || !profileToken) return false;
  try {
    await store.configureWithDeviceKey(deviceId, deviceKey, profileToken);
    return true;
  } catch {
    // Browser logins and a native client that has not registered yet still get
    // their encrypted cookie. Only a proven device key may update /data.
    return false;
  }
}

export { cleanDeviceId, keyHash };
