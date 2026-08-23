import http from "node:http";
import crypto from "node:crypto";
import { createReadStream, existsSync, mkdirSync, readFileSync, rmSync, statSync } from "node:fs";
import { readFile, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { Readable } from "node:stream";
import { spawn } from "node:child_process";
import QRCode from "qrcode";
import { LicenseStore } from "./lib/license-store.mjs";
import { DeviceProfileStore, persistDeviceSessionFromHeaders } from "./lib/device-profile-store.mjs";
import { inspectPlaylistBody, pipeInspectedBody } from "./lib/media-response.mjs";
import { bindRelayCancellation, providerRequestHeaders, providerResponseStatus } from "./lib/media-relay.mjs";
import {
  appendMediaAttemptId,
  buildNativeLinkContract,
  createMediaAttemptId,
  mediaErrorStatus,
  mediaLogContext,
  mediaProviderStatus,
  normalizeMediaAttemptId,
} from "./lib/native-link.mjs";
import { APP_VERSION, NATIVE_PLAYBACK_MODE, nativePlaybackPath, nativePlaybackTarget } from "./lib/runtime.mjs";
import { providerSessionCacheKey, providerSessionResponseStatus, refreshProviderSession } from "./lib/session-refresh.mjs";
import { extensionFromUrl, XtreamClient } from "./lib/xtream.mjs";
import { pageItems, parseM3u } from "./lib/playlist.mjs";
import { publicCatalogItem, publicSeriesItem } from "./lib/catalog-response.mjs";
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

const here = path.dirname(fileURLToPath(import.meta.url));
const publicDir = path.join(here, "public");
const transcodeRoot = path.join(os.tmpdir(), "blofy-player-transcodes");
const port = Number(process.env.PORT || 3000);
const cacheTtl = boundedInteger(process.env.CACHE_TTL_MS, 300_000, 1_000, 86_400_000);
const catalogCacheTtl = Math.max(cacheTtl,
  boundedInteger(process.env.CATALOG_CACHE_TTL_MS, 3_600_000, 60_000, 86_400_000));
const maxMemoryCacheEntries = boundedInteger(process.env.MAX_MEMORY_CACHE_ENTRIES, 96, 16, 1_000);
const configuredActivationUrl = String(process.env.ACTIVATION_URL || "").trim();
const trialDays = Math.max(1, Number(process.env.TRIAL_DAYS || 7));
const maxTranscodes = Math.max(1, Number(process.env.MAX_TRANSCODE_SESSIONS || 4));
const licenseDbPath = process.env.LICENSE_DB_PATH || path.join(here, "data", "licenses.json");
const deviceProfileDbPath = process.env.DEVICE_PROFILE_DB_PATH || path.join(path.dirname(licenseDbPath), "device-profiles.json");
const memoryCache = new Map();
const directSourceCache = new Map();
const rateBuckets = new Map();
const transcodes = new Map();
const licenses = new LicenseStore(licenseDbPath, { trialDays });
const deviceProfiles = new DeviceProfileStore(deviceProfileDbPath);
mkdirSync(transcodeRoot, { recursive: true });

function requestOrigin(req) {
  const forwarded = String(req.headers["x-forwarded-proto"] || "").split(",")[0].trim();
  const protocol = forwarded === "https" ? "https" : process.env.NODE_ENV === "production" ? "https" : "http";
  const host = String(req.headers["x-forwarded-host"] || req.headers.host || `localhost:${port}`).split(",")[0].trim();
  return `${protocol}://${host}`;
}

function activationUrlFor(req) {
  return configuredActivationUrl || `${requestOrigin(req)}/activate`;
}

function activeLicense(req) {
  const token = parseCookies(req.headers.cookie || "").blofy_license;
  const license = token ? unseal(token) : null;
  // Bind native requests to the device declared by their encrypted license.
  // Browser requests remain cookie-only because they do not send this header.
  const nativeDeviceId = String(req.headers["x-blofy-device-id"] || "");
  return licensePayloadIsActive(license, Date.now(), nativeDeviceId) ? license : null;
}

function requireActiveLicense(req, res, attemptId = "") {
  if (activeLicense(req)) return true;
  const normalizedAttempt = normalizeMediaAttemptId(attemptId);
  json(res, 402, {
    error: "انتهت الفترة التجريبية. فعّل الجهاز ثم اضغط تحديث التفعيل.",
    ...(normalizedAttempt ? { attemptId: normalizedAttempt } : {}),
  }, {
    ...securityHeaders(),
    ...(normalizedAttempt ? { "x-blofy-attempt-id": normalizedAttempt } : {}),
  });
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

function securityHeaders() {
  return {
    "x-content-type-options": "nosniff",
    "x-frame-options": "DENY",
    "referrer-policy": "no-referrer",
    "permissions-policy": "camera=(), microphone=(), geolocation=()",
    "content-security-policy": "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; media-src 'self' blob:; connect-src 'self'; worker-src 'self' blob:; font-src 'self' data:; object-src 'none'; base-uri 'self'; frame-ancestors 'none'",
    ...(process.env.NODE_ENV === "production" ? { "strict-transport-security": "max-age=31536000; includeSubDomains" } : {}),
  };
}

function limited(req, limit = 100, windowMs = 60_000, namespace = "api") {
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

function cacheKey(session, suffix) {
  return crypto.createHash("sha256").update(JSON.stringify(session)).update(suffix).digest("hex");
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

function boundedInteger(value, fallback, minimum, maximum) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return fallback;
  return Math.min(maximum, Math.max(minimum, Math.trunc(parsed)));
}

function pruneMemoryCache(now = Date.now()) {
  for (const [key, record] of memoryCache) {
    if (record.expiresAt <= now) memoryCache.delete(key);
  }
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
  // Cache the in-flight Promise too. Large Xtream catalogs can otherwise be
  // downloaded several times when multiple native requests arrive together.
  const pending = Promise.resolve().then(loader);
  memoryCache.set(key, { value: pending, expiresAt: now + ttl });
  pruneMemoryCache(now);
  try {
    const value = await pending;
    // A very slow loader can outlive its TTL. Never overwrite a newer load.
    if (memoryCache.get(key)?.value === pending) {
      memoryCache.set(key, { value, expiresAt: Date.now() + ttl });
    }
    return value;
  } catch (error) {
    if (memoryCache.get(key)?.value === pending) memoryCache.delete(key);
    throw error;
  }
}

function getSession(req) {
  const token = parseCookies(req.headers.cookie || "").blofy_session;
  const session = token ? unseal(token) : null;
  if (!session || !["xtream", "m3u"].includes(session.kind)) return null;
  return session;
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

function signedPath(url, prefix = "/api/proxy", lifetime = 7200) {
  const { encoded, expires, signature } = signResource(url, lifetime);
  return `${prefix}?u=${encodeURIComponent(encoded)}&e=${expires}&s=${encodeURIComponent(signature)}`;
}

function transcodePath(url, transcodeVideo = false) {
  return `${signedPath(url, "/api/transcode/index.m3u8", 21_600)}${transcodeVideo ? "&v=1" : ""}`;
}

function sourceHost(rawUrl) {
  try { return new URL(rawUrl).host; } catch { return "unknown"; }
}

function safeImage(url) {
  return url ? signedPath(url, "/api/proxy", 86_400) : "";
}

async function loadM3u(session) {
  return cached(cacheKey(session, "m3u"), async () => {
    const response = await fetchSafe(session.url, { headers: { accept: "application/x-mpegURL,text/plain,*/*" } });
    if (!response.ok) {
      throw Object.assign(new Error(`تعذر تحميل القائمة (${response.status}).`), {
        status: response.status,
        providerStatus: response.status,
      });
    }
    const text = await readTextLimited(response, 64_000_000, 15_000);
    const items = parseM3u(text, response.url || session.url);
    if (!items.length) throw new Error("القائمة لا تحتوي على قنوات صالحة.");
    return items;
  }, catalogCacheTtl);
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
  const maximumPageSize = query.get("native") === "1" ? 5000 : 500;
  const pageSize = boundedInteger(query.get("page_size"), 60, 30, maximumPageSize);
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
    // Catalog pagination and playback must reference the exact same M3U
    // snapshot. Otherwise rotating playlist URLs can invalidate stored ids.
    rows = (await loadM3u(session)).filter((item) => item.type === type);
  }
  // Paginate first. Signing every image in a 50k+ catalog for every page made
  // native synchronization repeat expensive work and eventually hit timeouts.
  const result = pageItems(rows, {
    category,
    search,
    page,
    pageSize,
  });
  // Keep optional direct_source values only for the page the user just read.
  // Playback itself never waits for a full 50k+ catalogue download.
  for (const item of result.items) rememberDirectSource(session, type, item.id, item.sourceUrl);
  return {
    ...result,
    // Native clients can load provider artwork directly. Sending every poster
    // through Railway is slow for large libraries and fails when a CDN blocks
    // data-centre IPs. Browser clients keep the protected BLOFY image route.
    items: result.items.map((item) => publicCatalogItem(item,
      query.get("native") === "1"
        ? (raw) => String(raw || "")
        : (_raw, kind, row) => `/api/image/${encodeURIComponent(type)}/${encodeURIComponent(String(row.id))}/${kind}`)),
  };
}

async function imageFor(session, type, id, kind) {
  const index = await cached(cacheKey(session, `image-index:${type}`), async () => {
    let rows;
    if (session.kind === "xtream") {
      const client = new XtreamClient(session);
      rows = await cached(cacheKey(session, `catalog:${type}:`), () => client.catalog(type), catalogCacheTtl);
    }
    else rows = (await loadM3u(session)).filter((item) => item.type === type);
    return new Map(rows.map((item) => [String(item.id), {
      poster: item.image || "",
      backdrop: item.backdrop || item.image || "",
    }]));
  }, catalogCacheTtl);
  return index.get(String(id))?.[kind] || "";
}

async function sourceFor(session, type, id, extension = "") {
  if (session.kind === "xtream") {
    const client = new XtreamClient(session);
    // A catalogue can contain hundreds of thousands of rows. Looking it up
    // here delayed every play request and could restart sync. Use a previously
    // observed direct_source when available; otherwise Xtream's canonical URL
    // is deterministic and can be issued immediately.
    const remembered = recalledDirectSource(session, type, id);
    if (remembered) return remembered;
    return client.streamUrl(type, id, extension || (type === "live" ? "ts" : "mp4"));
  }
  const item = (await loadM3u(session)).find((entry) => entry.id === id);
  if (!item) throw Object.assign(new Error("لم يتم العثور على رابط التشغيل."), { status: 404 });
  return item.sourceUrl;
}

function rewritePlaylist(text, baseUrl, attemptId = "") {
  return text.split(/\r?\n/).map((line) => {
    if (!line.trim()) return line;
    if (!line.startsWith("#")) {
      try {
        return appendMediaAttemptId(signedPath(new URL(line.trim(), baseUrl).toString()), attemptId);
      } catch { return line; }
    }
    return line.replace(/URI="([^"]+)"/g, (_, value) => {
      try {
        const target = appendMediaAttemptId(signedPath(new URL(value, baseUrl).toString()), attemptId);
        return `URI="${target}"`;
      } catch { return `URI="${value}"`; }
    });
  }).join("\n");
}

async function relayRemote(req, res, rawUrl, {
  allowTranscode = false,
  forceHls = false,
  transcodeVideo = false,
  preferTranscode = false,
  attemptId = "",
} = {}) {
  const traceId = normalizeMediaAttemptId(attemptId) || createMediaAttemptId();
  const traceHeaders = { "x-blofy-attempt-id": traceId };
  if (allowTranscode && forceHls && preferTranscode) {
    res.writeHead(302, {
      ...securityHeaders(),
      ...traceHeaders,
      location: appendMediaAttemptId(transcodePath(rawUrl, transcodeVideo), traceId),
      "cache-control": "no-store",
    });
    res.end();
    return;
  }
  const relayController = new AbortController();
  let cancelUpstream = () => relayController.abort();
  const lifecycle = bindRelayCancellation(req, res, () => cancelUpstream());
  let response;
  try {
    response = await fetchSafe(rawUrl, {
      headers: providerRequestHeaders(req.headers),
      signal: relayController.signal,
    });
  } catch (error) {
    if (lifecycle.cancelled || relayController.signal.aborted) return;
    lifecycle.complete();
    const status = mediaErrorStatus(error, 502);
    console.error(`[media] provider-connection-error ${mediaLogContext({ attemptId: traceId, type: "relay", host: sourceHost(rawUrl), status })} message=${mediaErrorSummary(error?.message)}`);
    return json(res, status, {
      error: status === 504 ? "انتهت مهلة اتصال مصدر التشغيل." : "تعذر الاتصال بمصدر التشغيل.",
      attemptId: traceId,
    }, { ...securityHeaders(), ...traceHeaders });
  }
  cancelUpstream = () => {
    relayController.abort();
    return response.body?.cancel().catch(() => {});
  };
  if (lifecycle.cancelled) {
    await cancelUpstream();
    return;
  }
  if (!response.ok && response.status !== 206) {
    await cancelUpstream();
    lifecycle.complete();
    const status = providerResponseStatus(response.status);
    console.error(`[media] provider-http-error ${mediaLogContext({ attemptId: traceId, type: "relay", host: sourceHost(rawUrl), status: response.status })}`);
    return json(res, status, {
      error: `مصدر التشغيل أعاد الخطأ ${response.status}.`,
      providerStatus: response.status,
      attemptId: traceId,
    }, { ...securityHeaders(), ...traceHeaders });
  }
  const type = (response.headers.get("content-type") || "").toLowerCase();
  const looksPlaylist = /mpegurl|m3u8/.test(type) || /\.m3u8(?:$|\?)/i.test(rawUrl);
  let inspected = null;
  if (looksPlaylist) {
    inspected = await inspectPlaylistBody(response);
    if (inspected.playlist && !transcodeVideo) {
      const body = rewritePlaylist(inspected.playlist, response.url || rawUrl, traceId);
      res.writeHead(200, {
        ...securityHeaders(),
        ...traceHeaders,
        "content-type": "application/vnd.apple.mpegurl; charset=utf-8",
        "cache-control": "no-store",
        "content-length": Buffer.byteLength(body),
      });
      res.end(body);
      lifecycle.complete();
      return;
    }
  }
  if (allowTranscode && (forceHls || !/video\/(mp4|webm)|audio\//.test(type))) {
    await inspected?.reader?.cancel().catch(() => {});
    if (!inspected && response.body) await response.body.cancel().catch(() => {});
    res.writeHead(302, {
      ...securityHeaders(),
      ...traceHeaders,
      location: appendMediaAttemptId(transcodePath(rawUrl, transcodeVideo), traceId),
      "cache-control": "no-store",
    });
    res.end();
    lifecycle.complete();
    return;
  }
  const passthrough = {
    ...securityHeaders(),
    ...traceHeaders,
    "content-type": response.headers.get("content-type") || "application/octet-stream",
    "cache-control": "no-store",
    "accept-ranges": response.headers.get("accept-ranges") || "bytes",
  };
  for (const name of ["content-length", "content-range"]) {
    const value = response.headers.get(name);
    if (value) passthrough[name] = value;
  }
  res.writeHead(response.status, passthrough);
  res.once("finish", lifecycle.complete);
  if (inspected) {
    cancelUpstream = () => {
      relayController.abort();
      return inspected.reader?.cancel().catch(() => {});
    };
    return pipeInspectedBody(res, inspected.reader, inspected.prefix);
  }
  if (!response.body) {
    res.end();
    lifecycle.complete();
    return;
  }
  const sourceStream = Readable.fromWeb(response.body);
  cancelUpstream = () => {
    relayController.abort();
    sourceStream.destroy();
  };
  sourceStream.on("error", () => res.destroy()).pipe(res);
}

function transcodeKey(url, transcodeVideo) {
  return crypto.createHash("sha256").update(url).update(transcodeVideo ? ":h264" : ":copy").digest("hex").slice(0, 24);
}

function mediaErrorSummary(value) {
  return String(value || "")
    .replace(/https?:\/\/\S+/gi, "[source]")
    .replace(/\/(live|movie|series)\/[^/\s]+\/[^/\s]+/gi, "/$1/[redacted]/[redacted]")
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .slice(-3)
    .join(" | ")
    .slice(0, 600);
}

function appendMediaError(record, value) {
  record.error = `${record.error}\n${String(value || "")}`.slice(-2400);
}

async function pipeTransportSource(url, child, record) {
  try {
    const response = await fetchSafe(url, {
      headers: {
        accept: "video/mp2t,application/octet-stream,*/*",
        "user-agent": "VLC/3.0.20 LibVLC/3.0.20",
      },
    });
    if (!response.ok && response.status !== 206) throw new Error(`Source HTTP ${response.status}`);
    if (!response.body) throw new Error("Source returned an empty response");
    console.log(`[media] source-open key=${record.key} status=${response.status} type=${String(response.headers.get("content-type") || "unknown").split(";")[0]}`);
    const sourceStream = Readable.fromWeb(response.body);
    record.sourceStream = sourceStream;
    sourceStream.on("error", (error) => {
      appendMediaError(record, error.message);
      child.stdin?.destroy(error);
    });
    child.stdin?.on("error", () => sourceStream.destroy());
    sourceStream.pipe(child.stdin);
  } catch (error) {
    appendMediaError(record, error?.name === "AbortError" ? "Source connection timed out" : error?.message || error);
    if (!child.killed && child.exitCode === null) child.kill("SIGTERM");
  }
}

function startTranscode(url, forceVideo = false) {
  const transcodeVideo = forceVideo || String(process.env.TRANSCODE_VIDEO || "false") === "true";
  const pipeSource = /\.ts(?:$|\?)/i.test(url);
  const key = transcodeKey(url, transcodeVideo);
  const existing = transcodes.get(key);
  if (existing?.process && !existing.process.killed && existing.process.exitCode === null) {
    existing.lastAccess = Date.now();
    return existing;
  }
  if (transcodes.size >= maxTranscodes) {
    const oldest = [...transcodes.values()].sort((a, b) => a.lastAccess - b.lastAccess)[0];
    if (oldest) stopTranscode(oldest.key);
  }
  const dir = path.join(transcodeRoot, key);
  rmSync(dir, { recursive: true, force: true });
  mkdirSync(dir, { recursive: true });
  const args = [
    "-nostdin", "-hide_banner", "-loglevel", "warning",
    "-fflags", "+genpts+discardcorrupt",
    ...(pipeSource
      ? ["-f", "mpegts", "-analyzeduration", "2500000", "-probesize", "2500000", "-i", "pipe:0"]
      : [
        "-user_agent", "VLC/3.0.20 LibVLC/3.0.20",
        "-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "2",
        "-rw_timeout", "12000000", "-analyzeduration", "2500000", "-probesize", "2500000",
        "-i", url,
      ]),
    "-map", "0:v:0?", "-map", "0:a:0?",
    "-c:v", transcodeVideo ? "libx264" : "copy",
    ...(transcodeVideo ? ["-preset", "veryfast", "-tune", "zerolatency"] : []),
    "-c:a", "aac", "-ac", "2", "-b:a", "128k",
    "-f", "hls", "-hls_time", "1", "-hls_list_size", "8",
    "-hls_flags", transcodeVideo
      ? "delete_segments+append_list+omit_endlist+independent_segments"
      : "delete_segments+append_list+omit_endlist+split_by_time",
    "-hls_segment_filename", path.join(dir, "segment-%06d.ts"),
    path.join(dir, "index.m3u8"),
  ];
  const child = spawn("ffmpeg", args, { stdio: [pipeSource ? "pipe" : "ignore", "ignore", "pipe"] });
  const record = { key, dir, process: child, sourceStream: null, lastAccess: Date.now(), error: "" };
  console.log(`[media] ffmpeg-start key=${key} host=${sourceHost(url)} mode=${transcodeVideo ? "h264" : "copy"} input=${pipeSource ? "node-pipe" : "direct"}`);
  child.stderr.setEncoding("utf8");
  child.stderr.on("data", (chunk) => appendMediaError(record, chunk));
  child.on("exit", (code) => {
    record.sourceStream?.destroy();
    if (code && !record.error) record.error = `FFmpeg stopped (${code})`;
    const detail = mediaErrorSummary(record.error);
    console.log(`[media] ffmpeg-exit key=${key} code=${code ?? "signal"}${detail ? ` detail=${detail}` : ""}`);
  });
  transcodes.set(key, record);
  if (pipeSource) void pipeTransportSource(url, child, record);
  return record;
}

function stopTranscode(key) {
  const record = transcodes.get(key);
  if (!record) return;
  try { record.sourceStream?.destroy(); } catch {}
  try { record.process.kill("SIGTERM"); } catch {}
  try { rmSync(record.dir, { recursive: true, force: true }); } catch {}
  transcodes.delete(key);
}

async function waitForFile(file, timeout = 8000) {
  const start = Date.now();
  while (Date.now() - start < timeout) {
    if (existsSync(file) && statSync(file).size > 20) return true;
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  return false;
}

async function serveTranscode(res, rawUrl, query, fileName) {
  await assertSafeUrl(rawUrl);
  const record = startTranscode(rawUrl, query.get("v") === "1");
  record.lastAccess = Date.now();
  const safeName = fileName === "index.m3u8" || /^segment-\d{6}\.ts$/.test(fileName) ? fileName : "";
  if (!safeName) throw new Error("ملف تشغيل غير صالح.");
  const target = path.join(record.dir, safeName);
  if (!(await waitForFile(target, safeName === "index.m3u8" ? 12_000 : 5000))) {
    console.error(`[media] transcode-timeout key=${record.key} host=${sourceHost(rawUrl)} code=${record.process.exitCode ?? "running"}`);
    throw new Error(record.error ? "تعذر تحويل هذا البث إلى صيغة متوافقة." : "البث لم يستجب بالسرعة المطلوبة.");
  }
  if (safeName === "index.m3u8") {
    let text = await readFile(target, "utf8");
    const suffix = `?u=${encodeURIComponent(query.get("u"))}&e=${encodeURIComponent(query.get("e"))}&s=${encodeURIComponent(query.get("s"))}${query.get("v") === "1" ? "&v=1" : ""}`;
    text = text.split(/\r?\n/).map((line) => /^segment-\d{6}\.ts$/.test(line) ? `/api/transcode/${line}${suffix}` : line).join("\n");
    res.writeHead(200, { ...securityHeaders(), "content-type": "application/vnd.apple.mpegurl", "cache-control": "no-store" });
    return res.end(text);
  }
  res.writeHead(200, { ...securityHeaders(), "content-type": "video/mp2t", "cache-control": "no-store" });
  createReadStream(target).pipe(res);
}

setInterval(() => {
  pruneMemoryCache();
  const cutoff = Date.now() - 120_000;
  for (const [key, record] of transcodes) if (record.lastAccess < cutoff) stopTranscode(key);
  const rateCutoff = Date.now() - 120_000;
  for (const [key, bucket] of rateBuckets) if (bucket.resetAt < rateCutoff) rateBuckets.delete(key);
}, 30_000).unref();

async function handleApi(req, res, url) {
  const mediaRequest = url.pathname.startsWith("/api/proxy") || url.pathname.startsWith("/api/image/") || url.pathname.startsWith("/api/play/") || url.pathname.startsWith("/api/native-") || url.pathname.startsWith("/api/transcode/");
  const requestAttemptId = mediaRequest
    ? normalizeMediaAttemptId(url.searchParams.get("a")) || createMediaAttemptId()
    : "";
  if (limited(req, mediaRequest ? 1200 : 120, 60_000, mediaRequest ? "media" : "api")) {
    return json(res, 429, {
      error: "طلبات كثيرة، حاول بعد دقيقة.",
      ...(requestAttemptId ? { attemptId: requestAttemptId } : {}),
    }, {
      ...securityHeaders(),
      ...(requestAttemptId ? { "x-blofy-attempt-id": requestAttemptId } : {}),
    });
  }

  if (req.method === "GET" && url.pathname === "/api/health") {
    return json(res, 200, {
      ok: true,
      service: "BLOFY PLAYER",
      version: APP_VERSION,
      nativePlayback: NATIVE_PLAYBACK_MODE,
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
    const cookie = licenseCookie(seal({ deviceId, expiresAt, plan }));
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
      "set-cookie": cookie,
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

    // Pairing/configuration no longer uses activation codes.
    // Payment/license status is managed separately by device id.
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
    const profileToken = await deviceProfiles.profile(deviceId, deviceKey);
    if (!profileToken) return json(res, 200, { ok: true, configured: false, license }, {
      ...securityHeaders(),
      "set-cookie": licenseCookie(seal({ deviceId, expiresAt: license.expiresAt, plan: license.plan })),
    });
    const session = unseal(profileToken);
    if (!session || !["xtream", "m3u"].includes(session.kind)) throw new Error("بيانات الباقة المحفوظة غير صالحة. أعد إرسالها من صفحة الربط.");
    const profile = session.kind === "xtream"
      ? { kind: "xtream", name: session.name || "Xtream Codes", serverUrl: session.serverUrl || "", username: session.username || "", password: session.password || "" }
      : { kind: "m3u", name: session.name || "M3U / M3U8", url: session.url || "" };
    return json(res, 200, { ok: true, configured: true, license, session: publicSession(session), profile }, {
      ...securityHeaders(),
      "set-cookie": [
        licenseCookie(seal({ deviceId, expiresAt: license.expiresAt, plan: license.plan })),
        sessionCookie(seal(session)),
      ],
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

  if (req.method === "GET" && url.pathname === "/api/qr") {
    const text = String(url.searchParams.get("text") || "").slice(0, 500);
    const svg = await QRCode.toString(text || activationUrlFor(req), { type: "svg", margin: 1, width: 220, color: { dark: "#120033", light: "#ffffff" } });
    res.writeHead(200, { ...securityHeaders(), "content-type": "image/svg+xml; charset=utf-8", "cache-control": "private, max-age=300" });
    return res.end(svg);
  }

  if (url.pathname === "/api/session" && req.method === "GET") {
    const session = getSession(req);
    if (url.searchParams.get("refresh") !== "1" || !session || session.kind !== "xtream") {
      return json(res, 200, { session: publicSession(session) }, securityHeaders());
    }
    if (!requireActiveLicense(req, res)) return;
    let refreshed;
    try {
      refreshed = await cached(providerSessionCacheKey(session), () => refreshProviderSession(session), 60_000);
    } catch (error) {
      console.warn(`[session] provider-refresh-failed host=${session.serverName || "unknown"} message=${mediaErrorSummary(error?.message)}`);
      return json(res, 503, { error: "تعذر تحديث حالة الباقة حاليًا. احتفظنا بالجلسة السابقة." }, securityHeaders());
    }
    const sealedSession = seal(refreshed);
    await persistDeviceSessionFromHeaders(deviceProfiles, req.headers, sealedSession);
    if (providerSessionResponseStatus(refreshed) === 402) {
      return json(res, 402, {
        error: "انتهى اشتراك مزود الباقة أو أصبحت بياناته غير صالحة. جدّد الاشتراك ثم أعد تسجيل الدخول.",
        session: publicSession(refreshed),
        refreshed: true,
      }, {
        ...securityHeaders(),
        "set-cookie": sessionCookie(sealedSession),
      });
    }
    return json(res, 200, { session: publicSession(refreshed), refreshed: true }, {
      ...securityHeaders(),
      "set-cookie": sessionCookie(sealedSession),
    });
  }
  if (url.pathname === "/api/session" && req.method === "DELETE") {
    return json(res, 200, { ok: true }, { ...securityHeaders(), "set-cookie": clearSessionCookie() });
  }
  if (url.pathname === "/api/session" && req.method === "POST") {
    if (!requireActiveLicense(req, res)) return;
    const body = await readJson(req);
    const session = await sessionFromInput(body);
    const sealedSession = seal(session);
    await persistDeviceSessionFromHeaders(deviceProfiles, req.headers, sealedSession);
    return json(res, 200, { ok: true, session: publicSession(session) }, {
      ...securityHeaders(),
      "set-cookie": sessionCookie(sealedSession),
    });
  }

  if (req.method === "GET" && url.pathname === "/api/proxy") {
    const attemptId = requestAttemptId;
    if (!requireActiveLicense(req, res, attemptId)) return;
    const raw = verifyResource(url.searchParams.get("u"), url.searchParams.get("e"), url.searchParams.get("s"));
    if (!raw) return json(res, 403, {
      error: "انتهى رابط الوسيط، أعد فتح المحتوى.",
      attemptId,
    }, { ...securityHeaders(), "x-blofy-attempt-id": attemptId });
    return relayRemote(req, res, raw, { attemptId });
  }

  if (req.method === "GET" && url.pathname === "/api/native-play") {
    const attemptId = requestAttemptId;
    if (!requireActiveLicense(req, res, attemptId)) return;
    const raw = verifyResource(url.searchParams.get("u"), url.searchParams.get("e"), url.searchParams.get("s"));
    if (!raw) return json(res, 403, {
      error: "انتهى رابط Media3، أعد فتح المحتوى.",
      attemptId,
    }, { ...securityHeaders(), "x-blofy-attempt-id": attemptId });
    await assertSafeUrl(raw);
    const location = nativePlaybackTarget(raw);
    console.log(`[media] native-open ${mediaLogContext({ attemptId, type: "direct", host: sourceHost(raw) })} mode=${NATIVE_PLAYBACK_MODE}`);
    res.writeHead(302, {
      ...securityHeaders(),
      "x-blofy-attempt-id": attemptId,
      location,
      "cache-control": "no-store",
    });
    res.end();
    return;
  }

  if (req.method === "GET" && (url.pathname === "/api/transcode/index.m3u8" || url.pathname.startsWith("/api/transcode/segment-"))) {
    if (!requireActiveLicense(req, res, requestAttemptId)) return;
    const raw = verifyResource(url.searchParams.get("u"), url.searchParams.get("e"), url.searchParams.get("s"));
    if (!raw) return json(res, 403, { error: "انتهت جلسة التشغيل." }, securityHeaders());
    const fileName = url.pathname.split("/").pop();
    return serveTranscode(res, raw, url.searchParams, fileName);
  }

  const session = getSession(req);
  if (!session) return json(res, 401, {
    error: "أضف قائمة تشغيل أولًا.",
    ...(requestAttemptId ? { attemptId: requestAttemptId } : {}),
  }, {
    ...securityHeaders(),
    ...(requestAttemptId ? { "x-blofy-attempt-id": requestAttemptId } : {}),
  });
  if (!requireActiveLicense(req, res, requestAttemptId)) return;

  const imageMatch = url.pathname.match(/^\/api\/image\/(live|movies|series)\/([^/]+)\/(poster|backdrop)$/);
  if (req.method === "GET" && imageMatch) {
    const [, type, id, kind] = imageMatch;
    const source = await imageFor(session, type, decodeURIComponent(id), kind);
    if (!source) return json(res, 404, { error: "لا توجد صورة لهذا المحتوى." }, securityHeaders());
    return relayRemote(req, res, source, { attemptId: requestAttemptId });
  }

  const nativeLinkMatch = url.pathname.match(/^\/api\/native-link\/(live|movies|episode)\/([^/]+)$/);
  if (req.method === "GET" && nativeLinkMatch) {
    const [, type, id] = nativeLinkMatch;
    const extension = String(url.searchParams.get("ext") || (type === "live" ? "ts" : "mp4")).replace(/[^a-zA-Z0-9]/g, "") || (type === "live" ? "ts" : "mp4");
    const attemptId = requestAttemptId;
    try {
      const source = await sourceFor(session, type, id, extension);
      await assertSafeUrl(source);
      const resolvedExtension = extensionFromUrl(source) || extension;
      const context = mediaLogContext({
        attemptId,
        type,
        id,
        extension: resolvedExtension,
        host: sourceHost(source),
      });
      console.log(`[media] native-link ${context} strategy=direct-first`);
      const contract = buildNativeLinkContract({
        // The JSON never contains provider credentials. New native clients
        // resolve a short-lived redirect and then connect from the TV itself.
        // `url` preserves the HTTPS relay for older cleartext-disabled APKs.
        directUrl: signedPath(source, "/api/native-play", 7200),
        relayUrl: signedPath(source, "/api/proxy", 7200),
        legacyUrl: signedPath(source, nativePlaybackPath(source), 7200),
        extension: resolvedExtension,
        attemptId,
        mode: NATIVE_PLAYBACK_MODE,
      });
      return json(res, 200, contract, {
        ...securityHeaders(),
        "x-blofy-attempt-id": attemptId,
      });
    } catch (error) {
      const providerStatus = mediaProviderStatus(error);
      const status = mediaErrorStatus(error);
      console.error(`[media] native-link-failed ${mediaLogContext({ attemptId, type, id, extension, status })} providerStatus=${providerStatus || "none"} message=${mediaErrorSummary(error?.message)}`);
      const payload = {
        error: providerStatus
          ? `مصدر التشغيل أعاد الخطأ ${providerStatus}.`
          : status === 404
            ? "لم يتم العثور على رابط التشغيل."
            : status === 504
              ? "انتهت مهلة الاتصال بمصدر التشغيل."
              : "تعذر إصدار رابط التشغيل من المصدر.",
        attemptId,
        ...(providerStatus ? { providerStatus } : {}),
      };
      return json(res, status, payload, {
        ...securityHeaders(),
        "x-blofy-attempt-id": attemptId,
      });
    }
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
    const item = await cached(cacheKey(session, `movie:${movieMatch[1]}`), () => client.movieInfo(movieMatch[1]));
    const directArtwork = url.searchParams.get("native") === "1";
    const { sourceUrl: _privateSource, ...publicItem } = item;
    return json(res, 200, {
      ...publicItem,
      image: directArtwork ? item.image : safeImage(item.image),
      backdrop: directArtwork ? item.backdrop : safeImage(item.backdrop),
    }, securityHeaders());
  }

  const seriesMatch = url.pathname.match(/^\/api\/series\/([^/]+)$/);
  if (req.method === "GET" && seriesMatch) {
    if (session.kind !== "xtream") return json(res, 400, { error: "المواسم والحلقات تحتاج مصدر Xtream Codes." }, securityHeaders());
    const client = new XtreamClient(session);
    const item = await cached(cacheKey(session, `series:${seriesMatch[1]}`), () => client.seriesInfo(seriesMatch[1]));
    if (!item.seasons.length) return json(res, 404, { error: "لم يرسل مزود القائمة مواسم أو حلقات لهذا المسلسل." }, securityHeaders());
    for (const season of item.seasons) {
      for (const episode of season.episodes) rememberDirectSource(session, "episode", episode.id, episode.sourceUrl);
    }
    const artwork = url.searchParams.get("native") === "1"
      ? (raw) => String(raw || "")
      : safeImage;
    return json(res, 200, publicSeriesItem(item, artwork), securityHeaders());
  }

  const epgMatch = url.pathname.match(/^\/api\/epg\/([^/]+)$/);
  if (req.method === "GET" && epgMatch) {
    if (session.kind !== "xtream") return json(res, 200, { entries: [] }, securityHeaders());
    const client = new XtreamClient(session);
    const entries = await cached(cacheKey(session, `epg:${epgMatch[1]}`), () => client.epg(epgMatch[1], 5), 120_000);
    return json(res, 200, { entries }, securityHeaders());
  }

  const playMatch = url.pathname.match(/^\/api\/play\/(live|movies|episode)\/([^/]+)$/);
  if (req.method === "GET" && playMatch) {
    const [, type, id] = playMatch;
    const extension = String(url.searchParams.get("ext") || (type === "live" ? "ts" : "mp4")).replace(/[^a-zA-Z0-9]/g, "") || (type === "live" ? "ts" : "mp4");
    const source = await sourceFor(session, type, id, extension);
    const compatibilityLevel = url.searchParams.get("compat");
    const compatibilityMode = compatibilityLevel === "1" || compatibilityLevel === "2";
    const secureRawRelay = url.searchParams.get("raw") === "1";
    console.log(`[media] web-open type=${type} id=${id} ext=${extension} host=${sourceHost(source)} mode=${compatibilityLevel === "2" ? "compat" : "standard"}`);
    return relayRemote(req, res, source, {
      allowTranscode: !secureRawRelay && (type === "live" || compatibilityMode),
      forceHls: !secureRawRelay && (type === "live" || compatibilityMode),
      transcodeVideo: compatibilityLevel === "2",
      preferTranscode: type === "live" && !/^m3u8$/i.test(extension),
      attemptId: requestAttemptId,
    });
  }

  return json(res, 404, { error: "المسار غير موجود." }, securityHeaders());
}

const mime = {
  ".html": "text/html; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".webmanifest": "application/manifest+json; charset=utf-8",
  ".png": "image/png",
  ".svg": "image/svg+xml",
  ".ico": "image/x-icon",
};

async function serveStatic(req, res, pathname) {
  let target;
  if (pathname === "/vendor/hls.min.js") target = path.join(here, "node_modules", "hls.js", "dist", "hls.min.js");
  else {
    const relative = pathname === "/" ? "index.html" : pathname === "/activate" || pathname === "/activate/" ? "activate.html" : decodeURIComponent(pathname).replace(/^\/+/, "");
    target = path.resolve(publicDir, relative);
    if (target !== publicDir && !target.startsWith(`${publicDir}${path.sep}`)) return false;
  }
  if (!existsSync(target) || !statSync(target).isFile()) return false;
  const stat = statSync(target);
  res.writeHead(200, {
    ...securityHeaders(),
    "content-type": mime[path.extname(target).toLowerCase()] || "application/octet-stream",
    "content-length": stat.size,
    "cache-control": /(?:app(?:\.compat)?\.js|activate(?:\.html|\.js)|styles\.css|index\.html)$/.test(target) ? "no-cache" : "public, max-age=86400",
  });
  createReadStream(target).pipe(res);
  return true;
}

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url || "/", `http://${req.headers.host || "localhost"}`);
    if (url.pathname.startsWith("/api/")) return await handleApi(req, res, url);
    if (req.method !== "GET" && req.method !== "HEAD") return json(res, 405, { error: "الطريقة غير مسموحة." }, securityHeaders());
    if (await serveStatic(req, res, url.pathname)) return;
    await serveStatic(req, res, "/");
  } catch (error) {
    const message = error?.name === "AbortError" ? "انتهت مهلة اتصال الخادم." : error?.message || "حدث خطأ غير متوقع.";
    if (String(req.url || "").startsWith("/api/play/") || String(req.url || "").startsWith("/api/native-") || String(req.url || "").startsWith("/api/transcode/")) {
      console.error(`[media] request-failed path=${String(req.url || "").split("?")[0]} message=${message}`);
    }
    if (!res.headersSent) json(res, 500, { error: message }, securityHeaders());
    else res.destroy();
  }
});

server.listen(port, "0.0.0.0", () => {
  console.log(`BLOFY PLAYER is ready on port ${port}`);
});

function shutdown() {
  for (const key of transcodes.keys()) stopTranscode(key);
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(1), 5000).unref();
}
process.on("SIGTERM", shutdown);
process.on("SIGINT", shutdown);
