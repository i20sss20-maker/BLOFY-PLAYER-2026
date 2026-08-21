import crypto from "node:crypto";

const ATTEMPT_ID_PATTERN = /^[a-f0-9]{24}$/;

export function createMediaAttemptId() {
  return crypto.randomBytes(12).toString("hex");
}

export function normalizeMediaAttemptId(value) {
  const candidate = String(value || "").trim().toLowerCase();
  return ATTEMPT_ID_PATTERN.test(candidate) ? candidate : "";
}

export function appendMediaAttemptId(path, attemptId) {
  const normalized = normalizeMediaAttemptId(attemptId);
  if (!normalized) return String(path || "");
  const value = String(path || "");
  return `${value}${value.includes("?") ? "&" : "?"}a=${normalized}`;
}

export function mediaMimeType(extension) {
  const clean = String(extension || "")
    .trim()
    .replace(/^\.+/, "")
    .replace(/[^a-zA-Z0-9]/g, "")
    .toLowerCase();
  switch (clean) {
    case "m3u8": return "application/x-mpegURL";
    case "mpd": return "application/dash+xml";
    case "mp4":
    case "m4v":
    case "mov": return "video/mp4";
    case "mkv": return "video/x-matroska";
    case "webm": return "video/webm";
    case "ts":
    case "mts":
    case "m2ts": return "video/mp2t";
    case "mp3": return "audio/mpeg";
    case "aac": return "audio/mp4a-latm";
    default: return "application/octet-stream";
  }
}

function assertSignedMediaPath(value, allowedPaths) {
  const raw = String(value || "");
  if (!raw.startsWith("/") || /[\r\n\0]/.test(raw)) throw new Error("رابط تشغيل داخلي غير صالح.");
  const parsed = new URL(raw, "https://blofy.invalid");
  const signedValues = ["u", "e", "s"].map((name) => parsed.searchParams.get(name) || "");
  if (!allowedPaths.includes(parsed.pathname) || signedValues.some((item) => !item || /[\r\n\0]/.test(item))) {
    throw new Error("رابط تشغيل داخلي غير صالح.");
  }
  return `${parsed.pathname}${parsed.search}`;
}

export function buildNativeLinkContract({ directUrl, relayUrl, legacyUrl = directUrl, extension, attemptId, mode = "direct" }) {
  const normalizedAttempt = normalizeMediaAttemptId(attemptId);
  if (!normalizedAttempt) throw new Error("رقم محاولة التشغيل غير صالح.");
  const cleanExtension = String(extension || "")
    .trim()
    .replace(/^\.+/, "")
    .replace(/[^a-zA-Z0-9]/g, "")
    .toLowerCase() || "mp4";
  const primary = appendMediaAttemptId(
    assertSignedMediaPath(directUrl, ["/api/native-play"]),
    normalizedAttempt,
  );
  const relay = appendMediaAttemptId(
    assertSignedMediaPath(relayUrl, ["/api/proxy"]),
    normalizedAttempt,
  );
  const legacy = appendMediaAttemptId(
    assertSignedMediaPath(legacyUrl, ["/api/native-play", "/api/proxy"]),
    normalizedAttempt,
  );
  return {
    // `url` is retained for already-installed APKs. New clients always use
    // directUrl first, including cleartext providers supported by Android TV.
    url: legacy,
    directUrl: primary,
    relayUrl: relay,
    extension: cleanExtension,
    mimeType: mediaMimeType(cleanExtension),
    strategy: "direct-first",
    mode: String(mode || "direct"),
    attemptId: normalizedAttempt,
  };
}

export function mediaProviderStatus(error) {
  for (const value of [error?.providerStatus, error?.response?.status]) {
    const status = Number(value);
    if (Number.isInteger(status) && status >= 400 && status <= 599) return status;
  }
  return 0;
}

export function mediaErrorStatus(error, fallback = 500) {
  const providerStatus = mediaProviderStatus(error);
  if (providerStatus) return providerStatus;
  for (const value of [error?.status, error?.statusCode]) {
    const status = Number(value);
    if (Number.isInteger(status) && status >= 400 && status <= 599) return status;
  }
  if (error?.name === "AbortError" || ["ETIMEDOUT", "ESOCKETTIMEDOUT"].includes(error?.code)) return 504;
  if (["ECONNREFUSED", "ECONNRESET", "EHOSTUNREACH", "ENETUNREACH", "ENOTFOUND", "EAI_AGAIN"].includes(error?.code)) return 502;
  const safeFallback = Number(fallback);
  return Number.isInteger(safeFallback) && safeFallback >= 400 && safeFallback <= 599 ? safeFallback : 500;
}

function safeLogToken(value, fallback, maximum = 80) {
  const token = String(value || "")
    .replace(/[\r\n\0]/g, "_")
    .replace(/[^a-zA-Z0-9._:-]/g, "_")
    .slice(0, maximum);
  return token || fallback;
}

export function mediaLogContext({ attemptId, type, id, extension, host, status } = {}) {
  const attempt = normalizeMediaAttemptId(attemptId) || "untracked";
  const reference = crypto.createHash("sha256").update(String(id || "unknown")).digest("hex").slice(0, 12);
  const fields = [
    `attempt=${attempt}`,
    `type=${safeLogToken(type, "unknown", 16)}`,
    `ref=${reference}`,
    `ext=${safeLogToken(extension, "unknown", 12)}`,
    `host=${safeLogToken(host, "unknown", 120)}`,
  ];
  const safeStatus = Number(status);
  if (Number.isInteger(safeStatus) && safeStatus >= 100 && safeStatus <= 599) fields.push(`status=${safeStatus}`);
  return fields.join(" ");
}
