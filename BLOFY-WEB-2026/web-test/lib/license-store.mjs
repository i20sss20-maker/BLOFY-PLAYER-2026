import crypto from "node:crypto";
import path from "node:path";
import { mkdir, readFile, rename, writeFile } from "node:fs/promises";

const DAY_MS = 86_400_000;

function cleanDeviceId(value) {
  const deviceId = String(value || "").trim().toUpperCase();
  if (!/^BLOFY-[A-Z0-9-]{8,32}$/.test(deviceId)) throw new Error("رقم الجهاز غير صالح.");
  return deviceId;
}

function cleanCode(value) {
  const code = String(value || "").trim().replace(/\s+/g, "");
  if (!/^[0-9]{6,12}$/.test(code)) throw new Error("رمز التفعيل يجب أن يكون من 6 إلى 12 رقمًا.");
  return code;
}

function boundedInteger(value, fallback, minimum, maximum) {
  const parsed = Number.parseInt(value, 10);
  if (!Number.isFinite(parsed)) return fallback;
  return Math.min(maximum, Math.max(minimum, parsed));
}

export class LicenseStore {
  constructor(filePath, { trialDays = 7, now = () => Date.now() } = {}) {
    this.filePath = filePath;
    this.trialDays = boundedInteger(trialDays, 7, 1, 365);
    this.now = now;
    this.data = { version: 1, devices: {}, codes: {} };
    this.loaded = false;
    this.queue = Promise.resolve();
  }

  async ensureLoaded() {
    if (this.loaded) return;
    try {
      const parsed = JSON.parse(await readFile(this.filePath, "utf8"));
      if (parsed && typeof parsed === "object") {
        this.data = {
          version: 1,
          devices: parsed.devices && typeof parsed.devices === "object" ? parsed.devices : {},
          codes: parsed.codes && typeof parsed.codes === "object" ? parsed.codes : {},
        };
      }
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

  statusFor(deviceId, record) {
    const now = this.now();
    const trialExpiresAt = Number(record.startedAt || now) + this.trialDays * DAY_MS;
    const activatedUntil = Number(record.activatedUntil || 0);
    const active = activatedUntil > now;
    const expiresAt = active ? activatedUntil : trialExpiresAt;
    const plan = active ? "active" : trialExpiresAt > now ? "trial" : "expired";
    return { deviceId, plan, status: plan === "active" ? "مفعّل" : plan === "trial" ? "تجريبي" : "منتهي", expiresAt,
      remainingDays: Math.max(0, Math.ceil((expiresAt - now) / DAY_MS)) };
  }

  get(deviceId, { create = true } = {}) {
    return this.locked(async () => {
      const cleanId = cleanDeviceId(deviceId);
      let record = this.data.devices[cleanId];
      if (!record && create) {
        record = { startedAt: this.now(), activatedUntil: 0, createdAt: this.now() };
        this.data.devices[cleanId] = record;
        await this.persist();
      }
      return record ? this.statusFor(cleanId, record) : null;
    });
  }

  redeem(deviceId, suppliedCode) {
    return this.locked(async () => {
      const cleanId = cleanDeviceId(deviceId);
      const code = cleanCode(suppliedCode);
      const entry = this.data.codes[code];
      const now = this.now();
      if (!entry || entry.disabled) throw new Error("رمز التفعيل غير صحيح.");
      if (Number(entry.expiresAt || 0) && Number(entry.expiresAt) <= now) throw new Error("انتهت صلاحية رمز التفعيل.");
      entry.devices = Array.isArray(entry.devices) ? entry.devices : [];
      const alreadyRedeemed = entry.devices.includes(cleanId);
      if (!alreadyRedeemed && entry.devices.length >= Number(entry.maxUses || 1)) throw new Error("تم استخدام رمز التفعيل بالكامل.");
      const record = this.data.devices[cleanId] || { startedAt: now, activatedUntil: 0, createdAt: now };
      if (!alreadyRedeemed) {
        record.activatedUntil = Math.max(now, Number(record.activatedUntil || 0)) + boundedInteger(entry.days, 30, 1, 3650) * DAY_MS;
        record.lastCode = code;
        record.updatedAt = now;
        entry.devices.push(cleanId);
        entry.updatedAt = now;
      }
      this.data.devices[cleanId] = record;
      await this.persist();
      return this.statusFor(cleanId, record);
    });
  }

  createCode({ code, days = 30, maxUses = 1, validDays = 365, label = "" } = {}) {
    return this.locked(async () => {
      let generated = code;
      if (!generated) do { generated = String(crypto.randomInt(0, 100_000_000)).padStart(8, "0"); } while (this.data.codes[generated]);
      const clean = cleanCode(generated);
      if (this.data.codes[clean]) throw new Error("رمز التفعيل موجود مسبقًا.");
      const now = this.now();
      const entry = { code: clean, days: boundedInteger(days, 30, 1, 3650), maxUses: boundedInteger(maxUses, 1, 1, 10000),
        label: String(label || "").slice(0, 80), createdAt: now,
        expiresAt: now + boundedInteger(validDays, 365, 1, 3650) * DAY_MS, devices: [], disabled: false };
      this.data.codes[clean] = entry;
      await this.persist();
      return { ...entry, used: 0 };
    });
  }

  listCodes() {
    return this.locked(async () => Object.values(this.data.codes)
      .map((entry) => ({ ...entry, used: Array.isArray(entry.devices) ? entry.devices.length : 0 }))
      .sort((a, b) => Number(b.createdAt || 0) - Number(a.createdAt || 0)));
  }
}

export { cleanCode, cleanDeviceId };
