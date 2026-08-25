import http from "node:http";
import crypto from "node:crypto";
import { accessSync, constants as fsConstants, createReadStream, existsSync, mkdirSync, statSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { LicenseStore } from "./lib/license-store.mjs";
import { DeviceIdentityConflictError, DeviceProfileStore, persistDeviceSessionFromHeaders } from "./lib/device-profile-store.mjs";
import { extensionFromUrl, XtreamClient } from "./lib/xtream.mjs";
import { pageItems, parseM3u } from "./lib/playlist.mjs";
import { publicCatalogItem, publicSeriesItem } from "./lib/catalog-response.mjs";
import { enrichMediaDetails } from "./lib/tmdb.mjs";
import {
  assertSafeUrl,
  clearPortalCookie,
  clearSessionCookie,
  clientKey,
  fetchSafe,
  json,
  licensePayloadIsActive,
  licenseCookie,
  parseCookies,
  portalCookie,
  readJson,
  readTextLimited,
  seal,
  sessionCookie,
  signResource,
  unseal,
  verifyResource,
} from "./lib/security.mjs";

const APP_VERSION = "2026.08.26.2-v326";
const NATIVE_PLAYBACK_MODE = "direct-provider";
const here = path.dirname(fileURLToPath(import.meta.url));
const publicDir = path.join(here, "public");
const production = process.env.NODE_ENV === "production";
const port = Number(process.env.PORT || 3000);
const cacheTtl = boundedInteger(process.env.CACHE_TTL_MS, 300_000, 1_000, 86_400_000);
const catalogCacheTtl = Math.max(cacheTtl,
  boundedInteger(process.env.CATALOG_CACHE_TTL_MS, 3_600_000, 60_000, 86_400_000));
const maxMemoryCacheEntries = boundedInteger(process.env.MAX_MEMORY_CACHE_ENTRIES, 96, 16, 1_000);
const configuredActivationUrl = String(process.env.ACTIVATION_URL || "").trim();
const trialDays = Math.max(1, Number(process.env.TRIAL_DAYS || 7));
const configuredDataDir = String(process.env.DATA_DIR || "").trim();
const configuredLicenseDbPath = String(process.env.LICENSE_DB_PATH || "").trim();
const configuredDeviceProfileDbPath = String(process.env.DEVICE_PROFILE_DB_PATH || "").trim();
const explicitStoragePath = configuredDataDir || configuredLicenseDbPath || configuredDeviceProfileDbPath;
if (production && !explicitStoragePath && process.env.ALLOW_EPHEMERAL_DATA !== "1") {
  throw new Error("Persistent storage is required in production. Mount a Railway Volume and set DATA_DIR (for example /data), or set LICENSE_DB_PATH/DEVICE_PROFILE_DB_PATH inside that volume.");
}
// Older BLOFY Railway deployments already set LICENSE_DB_PATH=/data/licenses.json.
// Keep that production-safe contract and place the new device database beside it,
// so the v323 portal can deploy without losing the existing licence volume.
const inferredDataDir = configuredDataDir
  || (configuredLicenseDbPath ? path.dirname(path.resolve(configuredLicenseDbPath)) : "")
  || (configuredDeviceProfileDbPath ? path.dirname(path.resolve(configuredDeviceProfileDbPath)) : "");
const dataDir = path.resolve(inferredDataDir || path.join(here, "data"));
const licenseDbPath = configuredLicenseDbPath || path.join(dataDir, "licenses.json");
const deviceProfileDbPath = configuredDeviceProfileDbPath || path.join(dataDir, "device-profiles.json");
for (const directory of new Set([path.dirname(licenseDbPath), path.dirname(deviceProfileDbPath)])) {
  mkdirSync(directory, { recursive: true });
  accessSync(directory, fsConstants.R_OK | fsConstants.W_OK);
}
const playbackSessionMaxAge = boundedInteger(process.env.PLAYBACK_SESSION_MAX_AGE_SECONDS, 30 * 24 * 60 * 60,
  process.env.NODE_ENV === "test" ? 1 : 300, 400 * 24 * 60 * 60);
const portalSessionMaxAge = boundedInteger(process.env.PORTAL_SESSION_MAX_AGE_SECONDS, 12 * 60 * 60,
  process.env.NODE_ENV === "test" ? 1 : 300, 7 * 24 * 60 * 60);
const pairTokenMaxAge = boundedInteger(process.env.PAIR_TOKEN_MAX_AGE_SECONDS, 15 * 60,
  process.env.NODE_ENV === "test" ? 1 : 60, 60 * 60);
const memoryCache = new Map();
const directSourceCache = new Map();
const rateBuckets = new Map();
const licenses = new LicenseStore(licenseDbPath, { trialDays });
const deviceProfiles = new DeviceProfileStore(deviceProfileDbPath);

function requestOrigin(req) {
  const forwarded = String(req.headers["x-forwarded-proto"] || "").split(",")[0].trim();
  const protocol = forwarded === "https" ? "https" : process.env.NODE_ENV === "production" ? "https" : "http";
  const host = String(req.headers["x-forwarded-host"] || req.headers.host || `localhost:${port}`).split(",")[0].trim();
  return `${protocol}://${host}`;
}

function activationUrlFor(req) {
  return configuredActivationUrl || `${requestOrigin(req)}/activate`;
}

function securityHeaders() {
  return {
    "x-content-type-options": "nosniff",
    "x-frame-options": "DENY",
    "referrer-policy": "no-referrer",
    "permissions-policy": "camera=(), microphone=(), geolocation=()",
    "content-security-policy": "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'",
    ...(process.env.NODE_ENV === "production" ? { "strict-transport-security": "max-age=31536000; includeSubDomains" } : {}),
  };
}

function activeLicense(req) {
  const token = parseCookies(req.headers.cookie || "").blofy_license;
  const license = token ? unseal(token) : null;
  const nativeDeviceId = String(req.headers["x-blofy-device-id"] || "");
  return licensePayloadIsActive(license, Date.now(), nativeDeviceId) ? license : null;
}

function requireActiveLicense(req, res) {
  if (activeLicense(req)) return true;
  json(res, 402, { error: "انتهت الفترة التجريبية. فعّل الجهاز ثم اضغط تحديث التفعيل." }, securityHeaders());
  return false;
}

function adminAuthorized(req) {
  const expected = String(process.env.ADMIN_TOKEN || "");
  if (expected.length < 20) return false;
  const supplied = String(req.headers.authorization || "").replace(/^Bearer\s+/i, "") || String(req.headers["x-admin-token"] || "");
  const left = Buffer.from(expected);
  const right = Buffer.from(supplied);
  return left.length === right.length && crypto.timingSafeEqual(left, right);
}

function limitedSubject(subject, limit = 120, windowMs = 60_000, namespace = "api") {
  const key = `${namespace}:${subject}`;
  const now = Date.now();
  const bucket = rateBuckets.get(key);
  if (!bucket || bucket.resetAt < now) {
    rateBuckets.set(key, { count: 1, resetAt: now + windowMs });
    return false;
  }
  bucket.count += 1;
  return bucket.count > limit;
}

function limited(req, limit = 120, windowMs = 60_000, namespace = "api") {
  return limitedSubject(clientKey(req), limit, windowMs, namespace);
}

function boundedInteger(value, fallback, minimum, maximum) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return fallback;
  return Math.min(maximum, Math.max(minimum, Math.trunc(parsed)));
}

function cacheKey(session, suffix) {
  return crypto.createHash("sha256").update(JSON.stringify(session)).update(suffix).digest("hex");
}

function pruneMemoryCache(now = Date.now()) {
  for (const [key, record] of memoryCache) if (record.expiresAt <= now) memoryCache.delete(key);
  while (memoryCache.size > maxMemoryCacheEntries) {
    const oldest = memoryCache.keys().next().value;
    if (oldest === undefined) break;
    memoryCache.delete(oldest);
  }
}

async function cached(key, loader, ttl = cacheTtl) {
  const now = Date.now();
  const current = memoryCache.get(key);
  if (current && current.expiresAt > now) return current.value;
  if (current) memoryCache.delete(key);
  const pending = Promise.resolve().then(loader);
  memoryCache.set(key, { value: pending, expiresAt: now + ttl });
  pruneMemoryCache(now);
  try {
    const value = await pending;
    if (memoryCache.get(key)?.value === pending) memoryCache.set(key, { value, expiresAt: Date.now() + ttl });
    return value;
  } catch (error) {
    if (memoryCache.get(key)?.value === pending) memoryCache.delete(key);
    throw error;
  }
}

function rememberDirectSource(session, type, id, source) {
  if (!source) return;
  const key = cacheKey(session, `direct-source:${type}:${id}`);
  directSourceCache.set(key, { source, expiresAt: Date.now() + catalogCacheTtl });
  while (directSourceCache.size > 5_000) directSourceCache.delete(directSourceCache.keys().next().value);
}

function recalledDirectSource(session, type, id) {
  const key = cacheKey(session, `direct-source:${type}:${id}`);
  const current = directSourceCache.get(key);
  if (!current || current.expiresAt <= Date.now()) {
    directSourceCache.delete(key);
    return "";
  }
  return current.source;
}

function getSession(req) {
  const token = parseCookies(req.headers.cookie || "").blofy_session;
  const payload = token ? unseal(token) : null;
  const session = payload?.kind === "playback-session" && Number(payload.expiresAt || 0) > Date.now()
    ? payload.session : null;
  if (!session || !["xtream", "m3u"].includes(session.kind)) return null;
  return session;
}

function sealedPlaybackSession(session) {
  return seal({ kind: "playback-session", expiresAt: Date.now() + playbackSessionMaxAge * 1000, session });
}

async function fetchM3u(session) {
  const response = await fetchSafe(session.url, { headers: { accept: "application/x-mpegURL,text/plain,*/*" } });
  if (!response.ok) throw new Error(`تعذر تحميل القائمة (${response.status}).`);
  const text = await readTextLimited(response, 64_000_000, 15_000);
  const items = parseM3u(text, response.url || session.url);
  if (!items.length) throw new Error("القائمة لا تحتوي على قنوات صالحة.");
  return items;
}

async function loadM3u(session, { fresh = false } = {}) {
  return fresh ? fetchM3u(session) : cached(cacheKey(session, "m3u"), () => fetchM3u(session), catalogCacheTtl);
}

async function sessionFromInput(body, { freshM3u = false } = {}) {
  const kind = body.kind === "m3u" ? "m3u" : "xtream";
  if (kind === "xtream") {
    const serverUrl = String(body.serverUrl || "").trim().replace(/\/+$/, "");
    const username = String(body.username || "").trim();
    const password = String(body.password || "");
    if (!serverUrl || !username || !password) throw new Error("أدخل رابط الخادم واسم المستخدم وكلمة المرور.");
    await assertSafeUrl(serverUrl);
    const client = new XtreamClient({ serverUrl, username, password });
    const account = await client.validate();
    return { kind, serverUrl, username, password, account, serverName: account.serverName, name: String(body.name || "قائمتي").slice(0, 50) };
  }
  const playlistUrl = String(body.url || "").trim();
  if (!playlistUrl) throw new Error("أدخل رابط M3U أو M3U8.");
  await assertSafeUrl(playlistUrl);
  const session = { kind, url: playlistUrl, serverName: new URL(playlistUrl).host, name: String(body.name || "قائمتي").slice(0, 50) };
  await loadM3u(session, { fresh: freshM3u });
  return session;
}

function publicSession(session) {
  if (!session) return null;
  const account = session.account ? {
    authenticated: Boolean(session.account.authenticated),
    status: session.account.status || "",
    expiresAt: session.account.expiresAt || null,
    maxConnections: Number(session.account.maxConnections || 0),
    activeConnections: Number(session.account.activeConnections || 0),
  } : null;
  return {
    kind: session.kind,
    name: session.name || (session.kind === "xtream" ? "Xtream Codes" : "M3U / M3U8"),
    serverName: session.serverName || "",
    account,
  };
}

function publicPlaylist(entry, defaultPlaylistId = "") {
  const restored = unseal(entry?.profileToken || "");
  const kind = ["xtream", "m3u"].includes(entry.kind) ? entry.kind : restored?.kind;
  const name = entry.name && entry.name !== "قائمتي" ? entry.name : restored?.name || entry.name;
  const serverName = entry.serverName || restored?.serverName || "";
  return {
    id: entry.id,
    name: name || "قائمتي",
    kind: kind === "m3u" ? "m3u" : "xtream",
    serverName,
    isDefault: entry.id === defaultPlaylistId,
    status: entry.status || "unknown",
    lastError: String(entry.lastError || ""),
    latencyMs: Number(entry.latencyMs || 0),
    lastTestedAt: Number(entry.lastTestedAt || 0),
    createdAt: Number(entry.createdAt || 0),
    updatedAt: Number(entry.updatedAt || 0),
  };
}

function safeConnectionError(error) {
  return String(error?.message || "فشل اختبار الاتصال.")
    .replace(/https?:\/\/\S+/gi, "[server]")
    .replace(/[\r\n\t]+/g, " ")
    .slice(0, 200);
}

function privatePlaylist(entry) {
  const session = unseal(entry?.profileToken || "");
  if (!session || !["xtream", "m3u"].includes(session.kind)) throw new Error("بيانات قائمة التشغيل المحفوظة غير صالحة.");
  if (session.kind === "m3u") return { id: entry.id, name: session.name || entry.name, kind: "m3u", url: session.url || "" };
  return {
    id: entry.id,
    name: session.name || entry.name,
    kind: "xtream",
    serverUrl: session.serverUrl || "",
    username: session.username || "",
    passwordPresent: Boolean(session.password),
  };
}

async function deviceAuthorization(req) {
  const deviceId = String(req.headers["x-blofy-device-id"] || "").trim().toUpperCase();
  const deviceKey = String(req.headers["x-blofy-device-key"] || "").trim();
  if (deviceId && deviceKey) return deviceProfiles.withDeviceKey(deviceId, deviceKey);
  const portalToken = parseCookies(req.headers.cookie || "").blofy_portal;
  const portal = portalToken ? unseal(portalToken) : null;
  if (!portal || portal.kind !== "device-portal" || Number(portal.expiresAt || 0) <= Date.now()) {
    throw new Error("سجّل دخول الجهاز أولًا.");
  }
  return deviceProfiles.portal(portal.deviceId, portal.portalVersion);
}

async function snapshotPayload(deviceId) {
  const snapshot = await deviceProfiles.snapshot(deviceId);
  return {
    deviceId: snapshot.deviceId,
    displayId: snapshot.displayId,
    revision: snapshot.revision,
    defaultPlaylistId: snapshot.defaultPlaylistId,
    playlists: snapshot.playlists.map((entry) => publicPlaylist(entry, snapshot.defaultPlaylistId)),
  };
}

async function sessionFromPlaylistUpdate(existing, body) {
  const current = unseal(existing.profileToken || "");
  if (!current || !["xtream", "m3u"].includes(current.kind)) throw new Error("بيانات القائمة المحفوظة غير صالحة.");
  const kind = body.kind === "m3u" ? "m3u" : body.kind === "xtream" ? "xtream" : current.kind;
  const name = String(body.name ?? current.name ?? existing.name ?? "قائمتي").trim().slice(0, 50) || "قائمتي";
  const connectionChanged = body.kind !== undefined || body.serverUrl !== undefined || body.username !== undefined ||
    (body.password !== undefined && String(body.password) !== "") || body.url !== undefined;
  if (!connectionChanged) return { session: { ...current, name }, tested: false, latencyMs: Number(existing.latencyMs || 0) };
  const input = kind === "m3u"
    ? { kind, name, url: body.url ?? current.url }
    : { kind, name, serverUrl: body.serverUrl ?? current.serverUrl, username: body.username ?? current.username,
      password: String(body.password || "") || current.password };
  const startedAt = Date.now();
  return { session: await sessionFromInput(input, { freshM3u: true }), tested: true, latencyMs: Date.now() - startedAt };
}

async function categoriesFor(session, type) {
  if (session.kind === "xtream") {
    const client = new XtreamClient(session);
    return cached(cacheKey(session, `categories:${type}`), () => client.categories(type));
  }
  const items = await loadM3u(session);
  return [...new Set(items.filter((item) => item.type === type).map((item) => item.category))]
    .sort((a, b) => a.localeCompare(b, "ar"))
    .map((name) => ({ id: name, name }));
}

async function catalogFor(session, type, query) {
  const page = boundedInteger(query.get("page"), 1, 1, 1_000_000);
  const pageSize = boundedInteger(query.get("page_size"), 60, 30, 2000);
  const category = query.get("category") || "";
  const search = query.get("search") || "";
  let rows;
  if (session.kind === "xtream") {
    const client = new XtreamClient(session);
    rows = await cached(
      cacheKey(session, `catalog:${type}:${category}`),
      () => client.catalog(type, category),
      category ? cacheTtl : catalogCacheTtl,
    );
  } else {
    rows = (await loadM3u(session)).filter((item) => item.type === type);
  }
  const result = pageItems(rows, { category, search, page, pageSize });
  return {
    ...result,
    items: result.items.map((item) => publicCatalogItem(item, (raw) => String(raw || ""))),
  };
}

async function sourceFor(session, type, id, extension = "", variant = "canonical") {
  if (session.kind === "xtream") {
    const client = new XtreamClient(session);
    if (type === "live") {
      return client.streamUrl("live", id, extension || "ts", variant);
    }
    if (type === "episode") {
      if (variant === "direct") {
        const remembered = recalledDirectSource(session, type, id);
        if (remembered) return remembered;
      }
      return client.streamUrl("episode", id, extension || "mp4", variant);
    }
    if (variant === "direct") {
      try {
        const rows = await cached(cacheKey(session, "catalog:movies:"), () => client.catalog("movies"), catalogCacheTtl);
        const direct = rows.find((item) => String(item.id) === String(id))?.sourceUrl || "";
        if (direct) return direct;
      } catch {}
      try {
        const movie = await cached(cacheKey(session, `movie:${id}`), () => client.movieInfo(id));
        if (movie.sourceUrl) return movie.sourceUrl;
      } catch {}
    }
    return client.streamUrl("movies", id, extension || "mp4", variant);
  }
  const item = (await loadM3u(session)).find((entry) => String(entry.id) === String(id));
  if (!item) throw new Error("لم يتم العثور على رابط التشغيل.");
  return item.sourceUrl;
}

function signedNativePath(rawUrl, lifetime = 7200) {
  const { encoded, expires, signature } = signResource(rawUrl, lifetime);
  return `/api/native-play?u=${encodeURIComponent(encoded)}&e=${expires}&s=${encodeURIComponent(signature)}`;
}

async function handleApi(req, res, url) {
  const nativeRequest = url.pathname.startsWith("/api/native-");
  const syncRequest = req.method === "GET" && (url.pathname === "/api/catalog" || url.pathname === "/api/categories");
  const requestLimit = nativeRequest ? 1200 : syncRequest ? 1800 : 120;
  const rateNamespace = nativeRequest ? "native" : syncRequest ? "sync" : "api";
  if (limited(req, requestLimit, 60_000, rateNamespace)) {
    return json(res, 429, { error: "طلبات كثيرة، حاول بعد دقيقة." }, securityHeaders());
  }

  if (req.method === "GET" && url.pathname === "/api/health") {
    return json(res, 200, {
      ok: true,
      service: "BLOFY PLAYER API",
      version: APP_VERSION,
      nativePlayback: NATIVE_PLAYBACK_MODE,
      mediaProxy: false,
      transcoding: false,
      portal: "v326-device-recovery",
      pairing: "one-time-token-or-six-digits",
      storage: production ? "persistent-required" : "local-development",
      time: new Date().toISOString(),
    }, securityHeaders());
  }

  if (req.method === "GET" && url.pathname === "/api/license") {
    const deviceId = String(url.searchParams.get("device_id") || "").slice(0, 40);
    if (!/^BLOFY-[A-Z0-9-]{8,32}$/.test(deviceId)) return json(res, 400, { error: "رقم الجهاز غير صالح." }, securityHeaders());
    let data;
    if (process.env.LICENSE_API_URL) {
      const remote = new URL(process.env.LICENSE_API_URL);
      remote.searchParams.set("device_id", deviceId);
      const response = await fetchSafe(remote.toString(), { headers: { accept: "application/json" } });
      if (!response.ok) throw new Error("تعذر التحقق من التفعيل حاليًا.");
      data = JSON.parse(await readTextLimited(response, 1_000_000, 9000));
    } else {
      data = await licenses.get(deviceId);
    }
    const rawExpiry = data?.expiresAt ?? data?.expires_at ?? 0;
    let expiresAt = Number(rawExpiry);
    if (!Number.isFinite(expiresAt)) expiresAt = Date.parse(String(rawExpiry)) || 0;
    if (expiresAt > 0 && expiresAt < 10_000_000_000) expiresAt *= 1000;
    const plan = data?.plan || (data?.active === true || expiresAt > Date.now() ? "active" : "expired");
    return json(res, 200, {
      ...data,
      deviceId,
      plan,
      status: data?.status || (plan === "active" ? "مفعّل" : plan === "trial" ? "تجريبي" : "منتهي"),
      expiresAt,
      remainingDays: data?.remainingDays ?? Math.max(0, Math.ceil((expiresAt - Date.now()) / 86_400_000)),
      activationUrl: activationUrlFor(req),
    }, {
      ...securityHeaders(),
      "set-cookie": licenseCookie(seal({ deviceId, expiresAt, plan })),
    });
  }

  if (req.method === "POST" && url.pathname === "/api/activate") {
    const body = await readJson(req, 4096);
    const license = await licenses.redeem(body.deviceId || body.device_id, body.code);
    return json(res, 200, { ok: true, ...license, activationUrl: activationUrlFor(req) }, securityHeaders());
  }

  if (req.method === "POST" && url.pathname === "/api/device/register") {
    const body = await readJson(req, 4096);
    const registrationId = String(body.deviceId || body.device_id || "").trim().toUpperCase();
    // A household or reseller network can legitimately register many TVs behind
    // one public IP. Bound re-registration by the private device identity; the
    // general API limiter above still caps abusive traffic from an IP.
    const registrationSubject = crypto.createHash("sha256").update(registrationId).digest("hex");
    if (limitedSubject(registrationSubject, 30, 15 * 60_000, "device-register")) {
      return json(res, 429, { error: "محاولات تسجيل كثيرة. حاول بعد 15 دقيقة." }, securityHeaders());
    }
    let registered;
    try {
      registered = await deviceProfiles.register(body.deviceId || body.device_id, body.deviceKey || body.device_key, {
        displayId: body.displayId || body.display_id,
        pairingCode: body.pairingCode || body.pairing_code,
      });
    } catch (error) {
      if (error instanceof DeviceIdentityConflictError) {
        return json(res, error.statusCode, {
          ok: false,
          error: error.message,
          errorCode: error.code,
          recoveryAction: error.recoveryAction,
        }, securityHeaders());
      }
      throw error;
    }
    const nonce = crypto.randomBytes(24).toString("base64url");
    const pairExpiresAt = Date.now() + pairTokenMaxAge * 1000;
    await deviceProfiles.issuePairNonce(registered.deviceId, registered.keyHash, nonce, pairExpiresAt);
    const pairToken = seal({
      kind: "device-pair",
      deviceId: registered.deviceId,
      displayId: registered.displayId,
      nonce,
      expiresAt: pairExpiresAt,
    });
    return json(res, 201, {
      ok: true,
      deviceId: registered.deviceId,
      displayId: registered.displayId,
      pairingCode: registered.pairingCode,
      revision: registered.revision,
      pairExpiresAt,
      recovered: registered.recovered,
      pairToken,
    }, securityHeaders());
  }

  if (req.method === "POST" && url.pathname === "/api/device/login") {
    if (limited(req, 8, 15 * 60_000, "device-login")) {
      return json(res, 429, { error: "محاولات دخول كثيرة. حاول بعد 15 دقيقة." }, securityHeaders());
    }
    const body = await readJson(req, 4096);
    try {
      let portal;
      const suppliedPairToken = String(body.pairToken || body.pair_token || "");
      if (suppliedPairToken) {
        const pairing = unseal(suppliedPairToken);
        if (!pairing || pairing.kind !== "device-pair" || !pairing.nonce || Number(pairing.expiresAt || 0) <= Date.now()) {
          throw new Error("انتهت صلاحية رابط QR أو تم استخدامه.");
        }
        portal = await deviceProfiles.consumePairNonce(pairing.deviceId, pairing.nonce);
      } else {
        portal = await deviceProfiles.login(body.deviceId || body.device_id, body.pairingCode || body.pairing_code);
      }
      const token = seal({ kind: "device-portal", deviceId: portal.deviceId, portalVersion: portal.portalVersion,
        expiresAt: Date.now() + portalSessionMaxAge * 1000 });
      const license = await licenses.get(portal.deviceId, { create: false });
      return json(res, 200, { ok: true, displayId: portal.displayId, revision: portal.revision, license }, {
        ...securityHeaders(), "set-cookie": portalCookie(token, portalSessionMaxAge),
      });
    } catch {
      return json(res, 401, { error: "رقم الجهاز أو رمز الربط غير صحيح." }, securityHeaders());
    }
  }

  if (req.method === "DELETE" && url.pathname === "/api/device/login") {
    const token = parseCookies(req.headers.cookie || "").blofy_portal;
    const portal = token ? unseal(token) : null;
    if (portal?.kind === "device-portal") await deviceProfiles.revokePortal(portal.deviceId, portal.portalVersion).catch(() => {});
    return json(res, 200, { ok: true }, {
      ...securityHeaders(), "set-cookie": [clearPortalCookie(), clearSessionCookie()],
    });
  }

  if (url.pathname === "/api/device/playlists" && req.method === "GET") {
    try {
      const auth = await deviceAuthorization(req);
      return json(res, 200, await snapshotPayload(auth.deviceId), securityHeaders());
    } catch (error) {
      return json(res, 401, { error: error.message || "سجّل دخول الجهاز أولًا." }, securityHeaders());
    }
  }

  if (url.pathname === "/api/device/playlists" && req.method === "POST") {
    let auth;
    try { auth = await deviceAuthorization(req); }
    catch (error) { return json(res, 401, { error: error.message }, securityHeaders()); }
    const body = await readJson(req, 32_768);
    const startedAt = Date.now();
    const session = await sessionFromInput(body, { freshM3u: true });
    const created = await deviceProfiles.createPlaylist(auth.deviceId, {
      name: session.name,
      kind: session.kind,
      serverName: session.serverName,
      profileToken: seal(session),
      status: "connected",
      lastError: "",
      lastTestedAt: Date.now(),
      latencyMs: Date.now() - startedAt,
      makeDefault: body.makeDefault === true || body.make_default === true,
    });
    const payload = await snapshotPayload(auth.deviceId);
    return json(res, 201, { ok: true, playlist: publicPlaylist(created, payload.defaultPlaylistId), ...payload }, securityHeaders());
  }

  const playlistDetailsMatch = url.pathname.match(/^\/api\/device\/playlists\/([a-f0-9]{16}|legacy)$/);
  if (playlistDetailsMatch && req.method === "GET") {
    let auth;
    try { auth = await deviceAuthorization(req); }
    catch (error) { return json(res, 401, { error: error.message }, securityHeaders()); }
    const snapshot = await deviceProfiles.snapshot(auth.deviceId);
    const entry = snapshot.playlists.find((item) => item.id === playlistDetailsMatch[1]);
    if (!entry) return json(res, 404, { error: "قائمة التشغيل غير موجودة." }, securityHeaders());
    return json(res, 200, { playlist: privatePlaylist(entry) }, securityHeaders());
  }

  if (playlistDetailsMatch && req.method === "PATCH") {
    let auth;
    try { auth = await deviceAuthorization(req); }
    catch (error) { return json(res, 401, { error: error.message }, securityHeaders()); }
    const body = await readJson(req, 32_768);
    const snapshot = await deviceProfiles.snapshot(auth.deviceId);
    const existing = snapshot.playlists.find((item) => item.id === playlistDetailsMatch[1]);
    if (!existing) return json(res, 404, { error: "قائمة التشغيل غير موجودة." }, securityHeaders());
    const result = await sessionFromPlaylistUpdate(existing, body);
    const updated = await deviceProfiles.updatePlaylist(auth.deviceId, existing.id, {
      name: result.session.name,
      kind: result.session.kind,
      serverName: result.session.serverName,
      profileToken: seal(result.session),
      status: "connected",
      lastError: "",
      ...(result.tested ? { lastTestedAt: Date.now(), latencyMs: result.latencyMs } : {}),
    });
    if (body.makeDefault === true || body.make_default === true) await deviceProfiles.setDefault(auth.deviceId, existing.id);
    const payload = await snapshotPayload(auth.deviceId);
    return json(res, 200, { ok: true, playlist: publicPlaylist(updated, payload.defaultPlaylistId), ...payload }, securityHeaders());
  }

  if (playlistDetailsMatch && req.method === "DELETE") {
    let auth;
    try { auth = await deviceAuthorization(req); }
    catch (error) { return json(res, 401, { error: error.message }, securityHeaders()); }
    await deviceProfiles.deletePlaylist(auth.deviceId, playlistDetailsMatch[1]);
    return json(res, 200, { ok: true, ...(await snapshotPayload(auth.deviceId)) }, securityHeaders());
  }

  const playlistActionMatch = url.pathname.match(/^\/api\/device\/playlists\/([a-f0-9]{16}|legacy)\/(test|default|connect)$/);
  if (playlistActionMatch && req.method === "POST") {
    let auth;
    try { auth = await deviceAuthorization(req); }
    catch (error) { return json(res, 401, { error: error.message }, securityHeaders()); }
    const [, playlistIdValue, action] = playlistActionMatch;
    const snapshot = await deviceProfiles.snapshot(auth.deviceId);
    const entry = snapshot.playlists.find((item) => item.id === playlistIdValue);
    if (!entry) return json(res, 404, { error: "قائمة التشغيل غير موجودة." }, securityHeaders());
    const session = unseal(entry.profileToken || "");
    if (!session) throw new Error("بيانات القائمة المحفوظة غير صالحة.");
    if (action === "test") {
      const startedAt = Date.now();
      try {
        const validated = await sessionFromInput(session, { freshM3u: true });
        const updated = await deviceProfiles.updatePlaylist(auth.deviceId, entry.id, {
          name: validated.name, kind: validated.kind, serverName: validated.serverName, profileToken: seal(validated),
          status: "connected", lastError: "", lastTestedAt: Date.now(), latencyMs: Date.now() - startedAt,
        });
        return json(res, 200, { ok: true, playlist: publicPlaylist(updated, snapshot.defaultPlaylistId) }, securityHeaders());
      } catch (error) {
        const lastError = safeConnectionError(error);
        const updated = await deviceProfiles.updatePlaylist(auth.deviceId, entry.id, {
          status: "error", lastError, lastTestedAt: Date.now(), latencyMs: Date.now() - startedAt,
        });
        return json(res, 422, { ok: false, error: lastError,
          playlist: publicPlaylist(updated, snapshot.defaultPlaylistId) }, securityHeaders());
      }
    }
    const selected = await deviceProfiles.setDefault(auth.deviceId, entry.id);
    if (action === "default") return json(res, 200, { ok: true, ...selected }, securityHeaders());
    return json(res, 200, { ok: true, session: publicSession(session), ...selected }, {
      ...securityHeaders(), "set-cookie": sessionCookie(sealedPlaybackSession(session), playbackSessionMaxAge),
    });
  }

  if (req.method === "POST" && url.pathname === "/api/device/configure") {
    const body = await readJson(req, 32_768);
    const deviceId = String(body.deviceId || body.device_id || "").trim().toUpperCase();
    const pairing = unseal(String(body.pairToken || body.pair_token || ""));
    if (!pairing || pairing.kind !== "device-pair" || pairing.deviceId !== deviceId ||
        Number(pairing.expiresAt || 0) <= Date.now() || !pairing.nonce) {
      return json(res, 403, { error: "انتهت صلاحية ربط الجهاز. حدّث الباركود من التطبيق." }, securityHeaders());
    }
    let verified;
    try { verified = await deviceProfiles.verifyPairNonce(deviceId, pairing.nonce); }
    catch (error) { return json(res, 403, { error: error.message }, securityHeaders()); }
    const license = await licenses.get(deviceId);
    const hasProfile = body.kind === "xtream" || body.kind === "m3u";
    let configured = false;
    let session = null;
    if (hasProfile) {
      session = await sessionFromInput(body, { freshM3u: true });
    }
    try { await deviceProfiles.consumePairNonce(deviceId, pairing.nonce); }
    catch (error) { return json(res, 403, { error: error.message }, securityHeaders()); }
    if (session) {
      const result = await deviceProfiles.configure(deviceId, verified.keyHash, seal(session), session);
      configured = result.configured;
    }
    return json(res, 200, { ok: true, configured, ...license, activationUrl: activationUrlFor(req) }, securityHeaders());
  }

  if (req.method === "GET" && url.pathname === "/api/device/bootstrap") {
    const deviceId = String(url.searchParams.get("device_id") || req.headers["x-blofy-device-id"] || "").trim().toUpperCase();
    const deviceKey = String(req.headers["x-blofy-device-key"] || "");
    const auth = await deviceProfiles.withDeviceKey(deviceId, deviceKey);
    const license = await licenses.get(deviceId, { create: false });
    if (!license || !["trial", "active"].includes(license.plan)) {
      return json(res, 402, { error: "الجهاز غير مفعّل أو انتهت صلاحيته." }, securityHeaders());
    }
    const licenseValue = seal({ deviceId, expiresAt: license.expiresAt, plan: license.plan });
    const snapshot = await deviceProfiles.snapshot(auth.deviceId);
    const profileToken = await deviceProfiles.profileToken(auth.deviceId, snapshot.defaultPlaylistId);
    const sync = {
      displayId: snapshot.displayId,
      revision: snapshot.revision,
      defaultPlaylistId: snapshot.defaultPlaylistId,
      playlists: snapshot.playlists.map((entry) => publicPlaylist(entry, snapshot.defaultPlaylistId)),
    };
    // v322 opens the playlist hub first and requires an explicit TV-side
    // "اتصال" action. Keep the legacy behavior for older installed clients.
    if (url.searchParams.get("connect") === "0") {
      return json(res, 200, { ok: true, configured: Boolean(profileToken), license, ...sync }, {
        ...securityHeaders(), "set-cookie": licenseCookie(licenseValue),
      });
    }
    if (!profileToken) return json(res, 200, { ok: true, configured: false, license, ...sync }, {
      ...securityHeaders(), "set-cookie": licenseCookie(licenseValue),
    });
    const session = unseal(profileToken);
    if (!session || !["xtream", "m3u"].includes(session.kind)) throw new Error("بيانات الباقة المحفوظة غير صالحة. أعد إرسالها من صفحة الربط.");
    return json(res, 200, { ok: true, configured: true, license, session: publicSession(session),
      activePlaylistId: snapshot.defaultPlaylistId, ...sync }, {
      ...securityHeaders(),
      "set-cookie": [licenseCookie(licenseValue), sessionCookie(sealedPlaybackSession(session), playbackSessionMaxAge)],
    });
  }

  if (req.method === "DELETE" && url.pathname === "/api/device/profile") {
    const deviceId = String(req.headers["x-blofy-device-id"] || "").trim().toUpperCase();
    const deviceKey = String(req.headers["x-blofy-device-key"] || "");
    await deviceProfiles.withDeviceKey(deviceId, deviceKey);
    if (url.searchParams.get("purge") === "1") await deviceProfiles.clearWithDeviceKey(deviceId, deviceKey);
    return json(res, 200, { ok: true, cloudPlaylistsPreserved: url.searchParams.get("purge") !== "1" }, {
      ...securityHeaders(), "set-cookie": clearSessionCookie(),
    });
  }

  if (url.pathname === "/api/admin/codes" && req.method === "POST") {
    if (!adminAuthorized(req)) return json(res, 401, { error: "رمز إدارة غير صحيح أو ADMIN_TOKEN غير مضبوط." }, securityHeaders());
    const entry = await licenses.createCode(await readJson(req, 8192));
    return json(res, 201, { ok: true, code: entry }, securityHeaders());
  }

  if (url.pathname === "/api/admin/codes" && req.method === "GET") {
    if (!adminAuthorized(req)) return json(res, 401, { error: "رمز إدارة غير صحيح أو ADMIN_TOKEN غير مضبوط." }, securityHeaders());
    return json(res, 200, { codes: await licenses.listCodes() }, securityHeaders());
  }

  if (url.pathname === "/api/session" && req.method === "GET") {
    return json(res, 200, { session: publicSession(getSession(req)) }, securityHeaders());
  }

  if (url.pathname === "/api/session" && req.method === "DELETE") {
    return json(res, 200, { ok: true }, { ...securityHeaders(), "set-cookie": clearSessionCookie() });
  }

  if (url.pathname === "/api/session" && req.method === "POST") {
    if (!requireActiveLicense(req, res)) return;
    const session = await sessionFromInput(await readJson(req));
    const sealedSession = seal(session);
    await persistDeviceSessionFromHeaders(deviceProfiles, req.headers, sealedSession, session);
    return json(res, 200, { ok: true, session: publicSession(session) }, {
      ...securityHeaders(), "set-cookie": sessionCookie(sealedPlaybackSession(session), playbackSessionMaxAge),
    });
  }

  if (req.method === "GET" && url.pathname === "/api/native-play") {
    if (!requireActiveLicense(req, res)) return;
    const raw = verifyResource(url.searchParams.get("u"), url.searchParams.get("e"), url.searchParams.get("s"));
    if (!raw) return json(res, 403, { error: "انتهى رابط التشغيل، أعد فتح المحتوى." }, securityHeaders());
    await assertSafeUrl(raw);
    console.log(`[media] direct-open host=${new URL(raw).host}`);
    res.writeHead(302, { ...securityHeaders(), location: raw, "cache-control": "no-store" });
    res.end();
    return;
  }

  const session = getSession(req);
  if (!session) return json(res, 401, { error: "أضف قائمة تشغيل أولًا." }, securityHeaders());
  if (!requireActiveLicense(req, res)) return;

  const nativeLinkMatch = url.pathname.match(/^\/api\/native-link\/(live|movies|episode)\/([^/]+)$/);
  if (req.method === "GET" && nativeLinkMatch) {
    const [, type, id] = nativeLinkMatch;
    const fallbackExt = type === "live" ? "ts" : "mp4";
    const extension = String(url.searchParams.get("ext") || fallbackExt).replace(/[^a-zA-Z0-9]/g, "") || fallbackExt;
    const requestedVariant = String(url.searchParams.get("variant") || "canonical");
    const variant = ["canonical", "direct", "no-extension"].includes(requestedVariant)
      ? requestedVariant : "canonical";
    const source = await sourceFor(session, type, id, extension, variant);
    const resolvedExtension = extensionFromUrl(source) || extension;
    await assertSafeUrl(source);
    console.log(`[media] direct-link type=${type} id=${id} ext=${resolvedExtension} host=${new URL(source).host}`);
    return json(res, 200, {
      url: signedNativePath(source),
      extension: resolvedExtension,
      variant,
      referer: session.kind === "xtream" ? `${new URL(session.serverUrl).origin}/` : `${new URL(source).origin}/`,
      mode: NATIVE_PLAYBACK_MODE,
    }, securityHeaders());
  }

  if (req.method === "GET" && url.pathname === "/api/categories") {
    const type = ["live", "movies", "series"].includes(url.searchParams.get("type")) ? url.searchParams.get("type") : "live";
    return json(res, 200, { categories: await categoriesFor(session, type) }, securityHeaders());
  }

  if (req.method === "GET" && url.pathname === "/api/catalog") {
    const type = ["live", "movies", "series"].includes(url.searchParams.get("type")) ? url.searchParams.get("type") : "live";
    return json(res, 200, await catalogFor(session, type, url.searchParams), securityHeaders());
  }

  const movieMatch = url.pathname.match(/^\/api\/movie\/([^/]+)$/);
  if (req.method === "GET" && movieMatch) {
    if (session.kind !== "xtream") return json(res, 400, { error: "تفاصيل الفيلم غير متوفرة لهذا النوع من القوائم." }, securityHeaders());
    const client = new XtreamClient(session);
    const rawItem = await cached(cacheKey(session, `movie:${movieMatch[1]}`), () => client.movieInfo(movieMatch[1]));
    const item = await cached(cacheKey(session, `movie-ar:${movieMatch[1]}`),
      () => enrichMediaDetails(rawItem, "movies"), 6 * 60 * 60 * 1000);
    const { sourceUrl: _privateSource, ...publicItem } = item;
    return json(res, 200, { ...publicItem, image: item.image || "", backdrop: item.backdrop || "" }, securityHeaders());
  }

  const seriesMatch = url.pathname.match(/^\/api\/series\/([^/]+)$/);
  if (req.method === "GET" && seriesMatch) {
    if (session.kind !== "xtream") return json(res, 400, { error: "المواسم والحلقات تحتاج مصدر Xtream Codes." }, securityHeaders());
    const client = new XtreamClient(session);
    const rawItem = await cached(cacheKey(session, `series:${seriesMatch[1]}`), () => client.seriesInfo(seriesMatch[1]));
    const item = await cached(cacheKey(session, `series-ar:${seriesMatch[1]}`),
      () => enrichMediaDetails(rawItem, "series"), 6 * 60 * 60 * 1000);
    if (!item.seasons.length) return json(res, 404, { error: "لم يرسل مزود القائمة مواسم أو حلقات لهذا المسلسل." }, securityHeaders());
    for (const season of item.seasons) for (const episode of season.episodes) {
      rememberDirectSource(session, "episode", episode.id, episode.sourceUrl);
    }
    return json(res, 200, publicSeriesItem(item, (raw) => String(raw || "")), securityHeaders());
  }

  const epgMatch = url.pathname.match(/^\/api\/epg\/([^/]+)$/);
  if (req.method === "GET" && epgMatch) {
    if (session.kind !== "xtream") return json(res, 200, { entries: [] }, securityHeaders());
    const client = new XtreamClient(session);
    const entries = await cached(cacheKey(session, `epg:${epgMatch[1]}`), () => client.epg(epgMatch[1], 5), 120_000);
    return json(res, 200, { entries }, securityHeaders());
  }

  return json(res, 404, { error: "المسار غير موجود." }, securityHeaders());
}

const staticFiles = new Map([
  ["/activate", "activate.html"],
  ["/activate/", "activate.html"],
  ["/activate.html", "activate.html"],
  ["/portal", "activate.html"],
  ["/portal/", "activate.html"],
  ["/activate.js", "activate.js"],
  ["/styles.css", "styles.css"],
  ["/brand.css", "brand.css"],
  ["/assets/blofy-logo-192.png", "assets/blofy-logo-192.png"],
  ["/assets/blofy-logo-512.png", "assets/blofy-logo-512.png"],
]);

const mime = {
  ".html": "text/html; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".png": "image/png",
};

function serveStatic(res, pathname) {
  const relative = staticFiles.get(pathname);
  if (!relative) return false;
  const target = path.join(publicDir, relative);
  if (!existsSync(target) || !statSync(target).isFile()) return false;
  const stat = statSync(target);
  res.writeHead(200, {
    ...securityHeaders(),
    "content-type": mime[path.extname(target).toLowerCase()] || "application/octet-stream",
    "content-length": stat.size,
    "cache-control": /activate|styles|brand/.test(relative) ? "no-cache" : "public, max-age=86400",
  });
  createReadStream(target).pipe(res);
  return true;
}

setInterval(() => {
  pruneMemoryCache();
  const now = Date.now();
  for (const [key, record] of directSourceCache) if (record.expiresAt <= now) directSourceCache.delete(key);
  for (const [key, bucket] of rateBuckets) if (bucket.resetAt < now - 120_000) rateBuckets.delete(key);
}, 30_000).unref();

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url || "/", `http://${req.headers.host || "localhost"}`);
    if (url.pathname.startsWith("/api/")) return await handleApi(req, res, url);
    if (req.method !== "GET" && req.method !== "HEAD") return json(res, 405, { error: "الطريقة غير مسموحة." }, securityHeaders());
    if (serveStatic(res, url.pathname)) return;
    if (url.pathname === "/") {
      return json(res, 200, {
        ok: true,
        service: "BLOFY PLAYER API",
        version: APP_VERSION,
        nativePlayback: NATIVE_PLAYBACK_MODE,
        activation: "/activate",
      }, securityHeaders());
    }
    return json(res, 404, { error: "المسار غير موجود." }, securityHeaders());
  } catch (error) {
    const message = error?.name === "AbortError" ? "انتهت مهلة اتصال الخادم." : error?.message || "حدث خطأ غير متوقع.";
    if (String(req.url || "").startsWith("/api/native-")) {
      console.error(`[media] request-failed path=${String(req.url || "").split("?")[0]} message=${message}`);
    }
    if (!res.headersSent) json(res, 500, { error: message }, securityHeaders());
    else res.destroy();
  }
});

server.listen(port, "0.0.0.0", () => {
  console.log(`BLOFY PLAYER API ${APP_VERSION} ready on port ${server.address().port}`);
});

function shutdown() {
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(1), 5000).unref();
}
process.on("SIGTERM", shutdown);
process.on("SIGINT", shutdown);
