"use strict";

const DISPLAY_ID_PATTERN = /^BLOFY-[A-Z0-9]{4}-[A-Z0-9]{4}$/;
const LEGACY_DISPLAY_ID_PATTERN = /^BLOFY-[A-Z0-9]{2}$/;
const PAIRING_CODE_PATTERN = /^\d{6}$/;

const $ = (id) => document.getElementById(id);
const loginView = $("loginView");
const dashboardView = $("dashboardView");
const loginForm = $("loginForm");
const loginMessage = $("loginMessage");
const playlistDialog = $("playlistDialog");
const playlistForm = $("playlistForm");
const playlistMessage = $("playlistMessage");
const grid = $("playlistGrid");
const emptyState = $("emptyState");
const sessionActions = $("sessionActions");
let state = { displayId: "", revision: 0, defaultPlaylistId: "", playlists: [], license: null };
let formKind = "xtream";
let toastTimer;

function normalizeDisplayId(value) {
  const typed = String(value || "").trim().toUpperCase().replace(/[^A-Z0-9-]/g, "");
  const compact = typed.replace(/-/g, "");
  if (!compact.startsWith("BLOFY")) return typed.slice(0, 15);
  const suffix = compact.slice(5, 13);
  if (!suffix) return "BLOFY";
  if (suffix.length <= 4) return `BLOFY-${suffix}`;
  return `BLOFY-${suffix.slice(0, 4)}-${suffix.slice(4, 8)}`;
}

function isValidDisplayId(value) {
  return DISPLAY_ID_PATTERN.test(value) || LEGACY_DISPLAY_ID_PATTERN.test(value);
}

$("deviceId").addEventListener("input", (event) => {
  event.target.value = normalizeDisplayId(event.target.value);
});

$("pairingCode").addEventListener("input", (event) => {
  event.target.value = String(event.target.value || "").replace(/\D/g, "").slice(0, 6);
});

function toast(message) {
  const node = $("toast");
  node.textContent = message;
  node.classList.add("show");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => node.classList.remove("show"), 2600);
}

async function api(path, options = {}, timeoutMs = 25_000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(path, {
      ...options,
      credentials: "same-origin",
      signal: controller.signal,
      headers: { ...(options.body ? { "content-type": "application/json" } : {}), ...(options.headers || {}) },
    });
    const data = await response.json().catch(() => ({}));
    if (!response.ok) {
      const error = new Error(data.error || "تعذر إكمال الطلب.");
      error.status = response.status;
      error.data = data;
      throw error;
    }
    return data;
  } catch (error) {
    if (error.name === "AbortError") throw new Error("تأخر الخادم في الرد. حاول مرة أخرى.");
    throw error;
  } finally { clearTimeout(timer); }
}

function showLogin() {
  loginView.hidden = false;
  dashboardView.hidden = true;
  sessionActions.hidden = true;
}

function showDashboard() {
  loginView.hidden = true;
  dashboardView.hidden = false;
  sessionActions.hidden = false;
  $("headerDevice").textContent = state.displayId;
  $("overviewDevice").textContent = state.displayId;
  $("playlistCount").textContent = new Intl.NumberFormat("ar-SA").format(state.playlists.length);
  $("licenseStatus").textContent = state.license?.status || "مرتبط";
  renderPlaylists();
}

function relativeTime(value) {
  if (!Number(value)) return "لم يتم الاختبار";
  const minutes = Math.max(0, Math.floor((Date.now() - Number(value)) / 60_000));
  if (minutes < 1) return "تم الاختبار الآن";
  if (minutes < 60) return `منذ ${minutes} د`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `منذ ${hours} س`;
  return new Intl.DateTimeFormat("ar-SA", { dateStyle: "medium" }).format(new Date(value));
}

function renderPlaylists() {
  grid.replaceChildren();
  emptyState.hidden = state.playlists.length > 0;
  grid.hidden = state.playlists.length === 0;
  for (const item of state.playlists) {
    const card = document.createElement("article");
    card.className = "playlist-card";
    card.innerHTML = `<div class="playlist-top"><span class="playlist-symbol">${item.kind === "m3u" ? "M3" : "XC"}</span>${item.isDefault ? '<span class="default-badge">الافتراضية</span>' : ""}</div><h3></h3><div class="playlist-meta"><span>${item.kind === "m3u" ? "M3U / M3U8" : "Xtream Codes"}</span><i></i><span>${relativeTime(item.lastTestedAt)}</span></div><div class="card-actions"><button class="connect" data-action="connect">اتصال</button><button data-action="test">اختبار</button><button data-action="edit">تعديل</button>${item.isDefault ? "" : '<button data-action="default">افتراضية</button>'}<button class="danger" data-action="delete">حذف</button></div>`;
    card.querySelector("h3").textContent = item.name;
    card.querySelector(".card-actions").addEventListener("click", (event) => {
      const action = event.target?.dataset?.action;
      if (action) playlistAction(item, action, event.target);
    });
    grid.append(card);
  }
}

async function loadDashboard() {
  try {
    const data = await api("/api/device/playlists");
    state = { ...state, ...data };
    showDashboard();
    return true;
  } catch (error) {
    if (error.status === 401) showLogin(); else toast(error.message);
    return false;
  }
}

loginForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const button = loginForm.querySelector("button[type=submit]");
  const deviceId = normalizeDisplayId($("deviceId").value);
  const pairingCode = String($("pairingCode").value || "").replace(/\D/g, "");
  loginMessage.textContent = "";
  if (!isValidDisplayId(deviceId) || !PAIRING_CODE_PATTERN.test(pairingCode)) {
    loginMessage.textContent = "أدخل رقمًا مثل BLOFY-66HL-GB09 ورمز الربط المكوّن من 6 أرقام.";
    return;
  }
  button.disabled = true;
  button.querySelector("span").textContent = "جاري تسجيل الدخول…";
  try {
    const data = await api("/api/device/login", { method: "POST", body: JSON.stringify({ deviceId, pairingCode }) });
    state.displayId = data.displayId;
    state.license = data.license;
    await loadDashboard();
  } catch (error) { loginMessage.textContent = error.message; }
  finally { button.disabled = false; button.querySelector("span").textContent = "دخول وإدارة القوائم"; }
});

$("logoutButton").addEventListener("click", async () => {
  try { await api("/api/device/login", { method: "DELETE" }); } catch {}
  state = { displayId: "", revision: 0, defaultPlaylistId: "", playlists: [], license: null };
  $("pairingCode").value = "";
  showLogin();
});

$("refreshButton").addEventListener("click", async () => { await loadDashboard(); toast("تم تحديث القوائم"); });

function switchKind(kind) {
  formKind = kind;
  const xtream = kind === "xtream";
  $("xtreamTab").classList.toggle("active", xtream);
  $("m3uTab").classList.toggle("active", !xtream);
  $("xtreamFields").hidden = !xtream;
  $("m3uFields").hidden = xtream;
}

$("xtreamTab").addEventListener("click", () => switchKind("xtream"));
$("m3uTab").addEventListener("click", () => switchKind("m3u"));

function openNewPlaylist() {
  playlistForm.reset();
  $("playlistId").value = "";
  $("dialogTitle").textContent = "إضافة قائمة تشغيل";
  $("savePlaylist").textContent = "اختبار وحفظ";
  $("passwordHint").hidden = true;
  playlistMessage.textContent = "";
  switchKind("xtream");
  playlistDialog.showModal();
}

$("addPlaylistButton").addEventListener("click", openNewPlaylist);
document.querySelectorAll(".add-trigger").forEach((node) => node.addEventListener("click", openNewPlaylist));
$("closeDialog").addEventListener("click", () => playlistDialog.close());
$("cancelDialog").addEventListener("click", () => playlistDialog.close());
playlistDialog.addEventListener("click", (event) => { if (event.target === playlistDialog) playlistDialog.close(); });

async function openEditPlaylist(item) {
  playlistMessage.textContent = "";
  const data = await api(`/api/device/playlists/${item.id}`);
  const details = data.playlist;
  playlistForm.reset();
  $("playlistId").value = item.id;
  $("dialogTitle").textContent = "تعديل قائمة التشغيل";
  $("savePlaylist").textContent = "اختبار وحفظ التعديل";
  $("profileName").value = details.name || item.name;
  switchKind(details.kind);
  if (details.kind === "m3u") $("playlistUrl").value = details.url || "";
  else {
    $("serverUrl").value = details.serverUrl || "";
    $("username").value = details.username || "";
    $("password").value = "";
    $("passwordHint").hidden = false;
  }
  $("makeDefault").checked = item.isDefault;
  playlistDialog.showModal();
}

playlistForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const id = $("playlistId").value;
  const button = $("savePlaylist");
  const body = { kind: formKind, name: $("profileName").value.trim(), makeDefault: $("makeDefault").checked };
  if (formKind === "m3u") body.url = $("playlistUrl").value.trim();
  else {
    body.serverUrl = $("serverUrl").value.trim();
    body.username = $("username").value.trim();
    if ($("password").value || !id) body.password = $("password").value;
  }
  playlistMessage.className = "form-message";
  playlistMessage.textContent = "";
  button.disabled = true;
  button.textContent = "جاري الاختبار…";
  try {
    const data = await api(id ? `/api/device/playlists/${id}` : "/api/device/playlists", { method: id ? "PATCH" : "POST", body: JSON.stringify(body) }, 35_000);
    state = { ...state, ...data };
    playlistDialog.close();
    showDashboard();
    toast(id ? "تم حفظ التعديلات ومزامنتها" : "تمت إضافة القائمة ومزامنتها");
  } catch (error) { playlistMessage.textContent = error.message; }
  finally { button.disabled = false; button.textContent = id ? "اختبار وحفظ التعديل" : "اختبار وحفظ"; }
});

async function playlistAction(item, action, button) {
  if (action === "edit") {
    try { await openEditPlaylist(item); } catch (error) { toast(error.message); }
    return;
  }
  if (action === "delete" && !window.confirm(`حذف «${item.name}» من الجهاز؟`)) return;
  const original = button.textContent;
  button.disabled = true;
  button.textContent = "…";
  try {
    if (action === "delete") {
      const data = await api(`/api/device/playlists/${item.id}`, { method: "DELETE" });
      state = { ...state, ...data };
      showDashboard();
      toast("تم حذف القائمة");
      return;
    }
    const data = await api(`/api/device/playlists/${item.id}/${action}`, { method: "POST" }, 35_000);
    if (action === "test") {
      const at = state.playlists.findIndex((entry) => entry.id === item.id);
      if (at >= 0) state.playlists[at] = { ...state.playlists[at], ...data.playlist };
      renderPlaylists();
      toast(`الاتصال سليم${data.playlist?.latencyMs ? ` — ${data.playlist.latencyMs}ms` : ""}`);
    } else {
      await loadDashboard();
      toast(action === "connect" ? "تم اختيار القائمة. اضغط اتصال في التطبيق" : "تم تعيين القائمة الافتراضية");
    }
  } catch (error) {
    if (action === "test" && error.data?.playlist) {
      const at = state.playlists.findIndex((entry) => entry.id === item.id);
      if (at >= 0) state.playlists[at] = { ...state.playlists[at], ...error.data.playlist };
      renderPlaylists();
    }
    toast(error.message);
  }
  finally { button.disabled = false; button.textContent = original; }
}

const query = new URLSearchParams(location.search);
const queryDevice = normalizeDisplayId(query.get("device_id"));
if (isValidDisplayId(queryDevice)) $("deviceId").value = queryDevice;
async function bootPortal() {
  const pairToken = query.get("pair_token") || "";
  if (pairToken) {
    try {
      const data = await api("/api/device/login", { method: "POST", body: JSON.stringify({ pairToken }) });
      state.displayId = data.displayId;
      state.license = data.license;
      history.replaceState({}, "", location.pathname);
      await loadDashboard();
      toast("تم ربط الجهاز بأمان. رابط QR استُخدم مرة واحدة");
      return;
    } catch (error) {
      history.replaceState({}, "", location.pathname);
      loginMessage.textContent = error.message;
    }
  }
  await loadDashboard();
}
bootPortal();
