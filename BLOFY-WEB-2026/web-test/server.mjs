import http from "node:http";
import crypto from "node:crypto";
import { createReadStream, existsSync, statSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { LicenseStore } from "./lib/license-store.mjs";
import { DeviceProfileStore, persistDeviceSessionFromHeaders } from "./lib/device-profile-store.mjs";
import { extensionFromUrl, XtreamClient } from "./lib/xtream.mjs";
import { pageItems, parseM3u } from "./lib/playlist.mjs";
import { publicCatalogItem, publicSeriesItem } from "./lib/catalog-response.mjs";
import { enrichMediaDetails } from "./lib/tmdb.mjs";
import {
  assertSafeUrl,
  clearSessionCookie,
  clientKey,
  fetchSafe,
  json,
  licensePayloadIsActive,
  licenseCookie,
  parseCookies,
  readJson,
  readTextLimited,
  seal,
  sessionCookie,
  signResource,
  unseal,
  verifyResource,
} from "./lib/security.mjs";

const APP_VERSION = "2026.08.25.11";
const NATIVE_PLAYBACK_MODE = "direct-provider";
const here = path.dirname(fileURLToPath(import.meta.url));
const publicDir = path.join(here, "public");
const port = Number(process.env.PORT || 3000);
const cacheTtl = boundedInteger(process.env.CACHE_TTL_MS, 300_000, 1_000, 86_400_000);
const catalogCacheTtl = Math.max(cacheTtl,
  boundedInteger(process.env.CATALOG_CACHE_TTL_MS, 3_600_000, 60_000, 86_400_000));
const maxMemoryCacheEntries = boundedInteger(process.env.MAX_MEMORY_CACHE_ENTRIES, 96, 16, 1_000);
const configuredActivationUrl = String(process.env.ACTIVATION_URL || "").trim();
const trialDays = Math.max(1, Number(process.env.TRIAL_DAYS || 7));
const licenseDbPath = process.env.LICENSE_DB_PATH || path.join(here, "data", "licenses.json");
const deviceProfileDbPath = process.env.DEVICE_PROFILE_DB_PATH || path.join(path.dirname(licenseDbPath), "device-profiles.json");
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

function limited(req, limit = 120, windowMs = 60_000, namespace = "api") {
  const key = `${namespace}:${clientKey(req)}`;
  const now = Date.now();
  const bucket = rateBuckets.get(key);
  if (!bucket || bucket.resetAt < now) {
    rateBuckets.set(key, { count: 1, resetAt: now + windowMs });
    return false;
  }
  bucket.count += 1;
  return bucket.count > limit;
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
  const session = token ? unseal(token) : null;
  if (!session || !["xtream", "m3u"].includes(session.kind)) return null;
  return session;
}

async function loadM3u(session) {
  return cached(cacheKey(session, "m3u"), async () => {
    const response = await fetchSafe(session.url, { headers: { accept: "application/x-mpegURL,text/plain,*/*" } });
    if (!response.ok) throw new Error(`تعذر تحميل القائمة (${response.status}).`);
    const text = await readTextLimited(response, 64_000_000, 15_000);
    const items = parseM3u(text, response.url || session.url);
    if (!items.length) throw new Error("القائمة لا تحتوي على قنوات صالحة.");
    return items;
  }, catalogCacheTtl);
}

async function sessionFromInput(body) {
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
  await loadM3u(session);
  return session;
}

function publicSession(session) {
  if (!session) return null;
  return {
    kind: session.kind,
    name: session.name || (session.kind === "xtream" ? "Xtream Codes" : "M3U / M3U8"),
    serverName: session.serverName || "",
    account: session.account || null,
  };
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

async function sourceFor(session, type, id, extension = "") {
  if (session.kind === "xtream") {
    const client = new XtreamClient(session);
    if (type === "live") {
      return client.streamUrl("live", id, extension || "ts");
    }
    if (type === "episode") {
      const remembered = recalledDirectSource(session, type, id);
      if (remembered) return remembered;
      return client.streamUrl("episode", id, extension || "mp4");
    }
    try {
      const rows = await cached(cacheKey(session, "catalog:movies:"), () => client.catalog("movies"), catalogCacheTtl);
      const direct = rows.find((item) => String(item.id) === String(id))?.sourceUrl || "";
      if (direct) return direct;
    } catch {}
    try {
      const movie = await cached(cacheKey(session, `movie:${id}`), () => client.movieInfo(id));
      if (movie.sourceUrl) return movie.sourceUrl;
    } catch {}
    return client.streamUrl("movies", id, extension || "mp4");
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
    const registered = await deviceProfiles.register(body.deviceId || body.device_id, body.deviceKey || body.device_key);
    const pairToken = seal({
      kind: "device-pair",
      deviceId: registered.deviceId,
      keyHash: registered.keyHash,
      expiresAt: Date.now() + 24 * 60 * 60 * 1000,
    });
    return json(res, 201, { ok: true, deviceId: registered.deviceId, pairToken }, securityHeaders());
  }

  if (req.method === "POST" && url.pathname === "/api/device/configure") {
    const body = await readJson(req, 32_768);
    const deviceId = String(body.deviceId || body.device_id || "").trim().toUpperCase();
    const pairing = unseal(String(body.pairToken || body.pair_token || ""));
    if (!pairing || pairing.kind !== "device-pair" || pairing.deviceId !== deviceId ||
        Number(pairing.expiresAt || 0) <= Date.now() || !pairing.keyHash) {
      return json(res, 403, { error: "انتهت صلاحية ربط الجهاز. حدّث الباركود من التطبيق." }, securityHeaders());
    }
    const license = await licenses.get(deviceId);
    const hasProfile = body.kind === "xtream" || body.kind === "m3u";
    let configured = false;
    if (hasProfile) {
      const session = await sessionFromInput(body);
      const result = await deviceProfiles.configure(deviceId, pairing.keyHash, seal(session));
      configured = result.configured;
    }
    return json(res, 200, { ok: true, configured, ...license, activationUrl: activationUrlFor(req) }, securityHeaders());
  }

  if (req.method === "GET" && url.pathname === "/api/device/bootstrap") {
    const deviceId = String(url.searchParams.get("device_id") || req.headers["x-blofy-device-id"] || "").trim().toUpperCase();
    const deviceKey = String(req.headers["x-blofy-device-key"] || "");
    const license = await licenses.get(deviceId, { create: false });
    if (!license || !["trial", "active"].includes(license.plan)) {
      return json(res, 402, { error: "الجهاز غير مفعّل أو انتهت صلاحيته." }, securityHeaders());
    }
    const licenseValue = seal({ deviceId, expiresAt: license.expiresAt, plan: license.plan });
    const profileToken = await deviceProfiles.profile(deviceId, deviceKey);
    if (!profileToken) return json(res, 200, { ok: true, configured: false, license }, {
      ...securityHeaders(), "set-cookie": licenseCookie(licenseValue),
    });
    const session = unseal(profileToken);
    if (!session || !["xtream", "m3u"].includes(session.kind)) throw new Error("بيانات الباقة المحفوظة غير صالحة. أعد إرسالها من صفحة الربط.");
    const profile = session.kind === "xtream"
      ? { kind: "xtream", name: session.name || "Xtream Codes", serverUrl: session.serverUrl || "", username: session.username || "", password: session.password || "" }
      : { kind: "m3u", name: session.name || "M3U / M3U8", url: session.url || "" };
    return json(res, 200, { ok: true, configured: true, license, session: publicSession(session), profile }, {
      ...securityHeaders(),
      "set-cookie": [licenseCookie(licenseValue), sessionCookie(seal(session))],
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
    await persistDeviceSessionFromHeaders(deviceProfiles, req.headers, sealedSession);
    return json(res, 200, { ok: true, session: publicSession(session) }, {
      ...securityHeaders(), "set-cookie": sessionCookie(sealedSession),
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
    const source = await sourceFor(session, type, id, extension);
    const resolvedExtension = extensionFromUrl(source) || extension;
    await assertSafeUrl(source);
    console.log(`[media] direct-link type=${type} id=${id} ext=${resolvedExtension} host=${new URL(source).host}`);
    return json(res, 200, {
      url: signedNativePath(source),
      extension: resolvedExtension,
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
  ["/activate.js", "activate.js"],
  ["/styles.css", "styles.css"],
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
    "cache-control": /activate|styles/.test(relative) ? "no-cache" : "public, max-age=86400",
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
  console.log(`BLOFY PLAYER API ${APP_VERSION} ready on port ${port}`);
});

function shutdown() {
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(1), 5000).unref();
}
process.on("SIGTERM", shutdown);
process.on("SIGINT", shutdown);
