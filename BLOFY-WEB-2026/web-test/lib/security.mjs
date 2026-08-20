import crypto from "node:crypto";
import dns from "node:dns/promises";
import net from "node:net";

const production = process.env.NODE_ENV === "production";
const configuredSecret = process.env.SESSION_SECRET || "blofy-local-development-secret-change-me";
if (production && configuredSecret.length < 32) {
  throw new Error("SESSION_SECRET must contain at least 32 characters in production.");
}

const key = crypto.createHash("sha256").update(configuredSecret).digest();
const safeHostCache = new Map();

export function seal(value) {
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv("aes-256-gcm", key, iv);
  const body = Buffer.concat([cipher.update(JSON.stringify(value), "utf8"), cipher.final()]);
  return Buffer.concat([iv, cipher.getAuthTag(), body]).toString("base64url");
}

export function unseal(token) {
  try {
    const bytes = Buffer.from(token, "base64url");
    if (bytes.length < 29) return null;
    const iv = bytes.subarray(0, 12);
    const tag = bytes.subarray(12, 28);
    const decipher = crypto.createDecipheriv("aes-256-gcm", key, iv);
    decipher.setAuthTag(tag);
    const plain = Buffer.concat([decipher.update(bytes.subarray(28)), decipher.final()]);
    return JSON.parse(plain.toString("utf8"));
  } catch {
    return null;
  }
}

export function parseCookies(header = "") {
  const decode = (value) => {
    try { return decodeURIComponent(value); } catch { return value; }
  };
  return Object.fromEntries(
    header.split(";").map((part) => part.trim()).filter(Boolean).map((part) => {
      const at = part.indexOf("=");
      if (at < 0) return [part, ""];
      return [decode(part.slice(0, at)), decode(part.slice(at + 1))];
    }),
  );
}

export function sessionCookie(token, maxAge = 60 * 60 * 24 * 30) {
  return [
    `blofy_session=${encodeURIComponent(token)}`,
    "Path=/",
    "HttpOnly",
    "SameSite=Lax",
    production ? "Secure" : "",
    `Max-Age=${maxAge}`,
  ].filter(Boolean).join("; ");
}

export function licenseCookie(token, maxAge = 60 * 60 * 24 * 400) {
  return [
    `blofy_license=${encodeURIComponent(token)}`,
    "Path=/",
    "HttpOnly",
    "SameSite=Lax",
    production ? "Secure" : "",
    `Max-Age=${maxAge}`,
  ].filter(Boolean).join("; ");
}

export function clearSessionCookie() {
  return [
    "blofy_session=",
    "Path=/",
    "HttpOnly",
    "SameSite=Lax",
    production ? "Secure" : "",
    "Max-Age=0",
  ].filter(Boolean).join("; ");
}

export function signResource(url, expiresInSeconds = 1800) {
  const expires = Math.floor(Date.now() / 1000) + expiresInSeconds;
  const encoded = seal({ url: String(url), expires });
  const signature = crypto.createHmac("sha256", key).update(`${encoded}.${expires}`).digest("base64url");
  return { encoded, expires, signature };
}

export function verifyResource(encoded, expires, signature) {
  if (!encoded || !signature || !Number.isFinite(Number(expires))) return null;
  if (Number(expires) < Math.floor(Date.now() / 1000)) return null;
  const expected = crypto.createHmac("sha256", key).update(`${encoded}.${expires}`).digest();
  let supplied;
  try { supplied = Buffer.from(signature, "base64url"); } catch { return null; }
  if (supplied.length !== expected.length || !crypto.timingSafeEqual(supplied, expected)) return null;
  const payload = unseal(encoded);
  if (!payload || payload.expires !== Number(expires) || typeof payload.url !== "string") return null;
  return payload.url;
}

export function isPrivateIp(address) {
  if (net.isIP(address) === 4) {
    const [a, b, c] = address.split(".").map(Number);
    return a === 0 || a === 10 || a === 127 ||
      (a === 100 && b >= 64 && b <= 127) ||
      (a === 169 && b === 254) ||
      (a === 172 && b >= 16 && b <= 31) ||
      (a === 192 && (b === 0 || b === 168)) ||
      (a === 198 && (b === 18 || b === 19 || (b === 51 && c === 100))) ||
      (a === 203 && b === 0 && c === 113) ||
      a >= 224;
  }
  const value = address.toLowerCase();
  const mapped = value.match(/(?:^|:)ffff:(\d+\.\d+\.\d+\.\d+)$/);
  if (mapped) return isPrivateIp(mapped[1]);
  const first = Number.parseInt(value.split(":")[0] || "0", 16);
  return value.startsWith("::") ||
    (first >= 0xfc00 && first <= 0xfdff) ||
    (first >= 0xfe80 && first <= 0xfebf) ||
    first >= 0xff00 ||
    value.startsWith("2001:db8:");
}

export async function assertSafeUrl(raw) {
  let url;
  try { url = new URL(raw); } catch { throw new Error("رابط الخادم غير صحيح."); }
  if (!["http:", "https:"].includes(url.protocol)) throw new Error("يجب أن يبدأ الرابط بـ http أو https.");
  if (url.username || url.password) throw new Error("لا تضع بيانات الدخول داخل رابط الخادم.");
  const cached = safeHostCache.get(url.hostname);
  if (!cached || cached < Date.now()) {
    const records = await dns.lookup(url.hostname, { all: true });
    if (!records.length || records.some(({ address }) => isPrivateIp(address))) {
      throw new Error("عنوان الخادم غير مسموح من الاستضافة.");
    }
    safeHostCache.set(url.hostname, Date.now() + 600_000);
  }
  return url;
}

export async function fetchSafe(rawUrl, options = {}, redirects = 0) {
  const url = await assertSafeUrl(rawUrl);
  const timeout = Number(process.env.REQUEST_TIMEOUT_MS || 9000);
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), Math.max(2500, timeout));
  try {
    const response = await fetch(url, {
      ...options,
      redirect: "manual",
      signal: controller.signal,
      headers: {
        "user-agent": "BLOFY-PLAYER/2026 Mozilla/5.0",
        accept: "*/*",
        ...(options.headers || {}),
      },
    });
    if ([301, 302, 303, 307, 308].includes(response.status)) {
      if (redirects >= 3) throw new Error("الخادم أعاد توجيهات كثيرة.");
      const next = response.headers.get("location");
      if (!next) throw new Error("إعادة توجيه غير مكتملة.");
      return fetchSafe(new URL(next, url).toString(), options, redirects + 1);
    }
    return response;
  } finally {
    clearTimeout(timer);
  }
}

export function json(res, status, data, extraHeaders = {}) {
  const body = JSON.stringify(data);
  res.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "content-length": Buffer.byteLength(body),
    "cache-control": "no-store",
    ...extraHeaders,
  });
  res.end(body);
}

export function readJson(req, maxBytes = 32_768) {
  return new Promise((resolve, reject) => {
    let body = "";
    let received = 0;
    let settled = false;
    req.setEncoding("utf8");
    req.on("data", (chunk) => {
      if (settled) return;
      received += Buffer.byteLength(chunk);
      if (received > maxBytes) {
        settled = true;
        reject(new Error("الطلب أكبر من المسموح."));
        return;
      }
      body += chunk;
    });
    req.on("end", () => {
      if (settled) return;
      settled = true;
      try { resolve(body ? JSON.parse(body) : {}); } catch { reject(new Error("صيغة الطلب غير صحيحة.")); }
    });
    req.on("error", (error) => {
      if (settled) return;
      settled = true;
      reject(error);
    });
  });
}

export async function readTextLimited(response, maxBytes = 32_000_000, timeoutMs = Number(process.env.REQUEST_TIMEOUT_MS || 9000)) {
  if (!response.body) return "";
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let text = "";
  let received = 0;
  try {
    while (true) {
      let timer;
      const next = await Promise.race([
        reader.read(),
        new Promise((_, reject) => {
          timer = setTimeout(() => reject(Object.assign(new Error("انتهت مهلة قراءة استجابة الخادم."), { name: "AbortError" })), Math.max(2500, timeoutMs));
        }),
      ]).finally(() => clearTimeout(timer));
      if (next.done) break;
      received += next.value.byteLength;
      if (received > maxBytes) throw new Error("استجابة الخادم أكبر من الحد المسموح.");
      text += decoder.decode(next.value, { stream: true });
    }
    return text + decoder.decode();
  } catch (error) {
    await reader.cancel().catch(() => {});
    throw error;
  }
}

export function clientKey(req) {
  return String(req.headers["x-forwarded-for"] || req.socket.remoteAddress || "unknown").split(",")[0].trim();
}
