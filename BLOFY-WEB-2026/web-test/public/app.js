"use strict";

const app = document.getElementById("app");
const toast = document.getElementById("toast");
const playerModal = document.getElementById("playerModal");
const video = document.getElementById("videoPlayer");
const playerStatus = document.getElementById("playerStatus");
const qualitySelect = document.getElementById("qualitySelect");
const audioSelect = document.getElementById("audioSelect");
const subtitleSelect = document.getElementById("subtitleSelect");
const seek = document.getElementById("playerSeek");

const storage = {
  get(key, fallback) { try { const value = JSON.parse(localStorage.getItem(key)); return value ?? fallback; } catch { return fallback; } },
  set(key, value) { try { localStorage.setItem(key, JSON.stringify(value)); } catch {} },
};

const state = {
  sourceTab: "xtream",
  session: null,
  license: null,
  deviceId: getDeviceId(),
  route: "home",
  navStack: [],
  type: "live",
  categories: [],
  category: "",
  items: [],
  total: 0,
  page: 1,
  search: "",
  loading: false,
  selected: null,
  epg: [],
  detail: null,
  season: "",
  favorites: storage.get("blofy_favorites", []),
  history: storage.get("blofy_history", []),
  settings: { autoplayNext: true, rememberPosition: true, bufferMode: "balanced", subtitles: "auto", ...storage.get("blofy_settings", {}) },
};

let hls = null;
let searchTimer = null;
let toastTimer = null;
let playerItem = null;
let playerFailures = 0;
let playerTimeout = null;
let playerCompatibility = false;

function getDeviceId() {
  try {
    const nativeId = window.BlofyAndroid?.getDeviceId?.();
    if (/^BLOFY-[A-Z0-9-]{8,32}$/.test(nativeId || "")) return nativeId;
  } catch {}
  let id = storage.get("blofy_device_id", "");
  if (/^BLOFY-[A-Z0-9-]{8,32}$/.test(id)) return id;
  const bytes = new Uint8Array(8);
  crypto.getRandomValues(bytes);
  const value = [...bytes].map((byte) => byte.toString(16).padStart(2, "0")).join("").toUpperCase();
  id = `BLOFY-${value.slice(0, 4)}-${value.slice(4, 8)}-${value.slice(8, 12)}-${value.slice(12, 16)}`;
  storage.set("blofy_device_id", id);
  return id;
}

function escapeHtml(value = "") {
  return String(value).replace(/[&<>'"]/g, (char) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;" })[char]);
}

function formatDate(value) {
  if (!value) return "غير محدد";
  try { return new Intl.DateTimeFormat("ar-SA", { year: "numeric", month: "short", day: "numeric" }).format(new Date(value)); } catch { return "غير محدد"; }
}

function formatTime(value) {
  const number = Number(value || 0);
  if (!Number.isFinite(number)) return "00:00";
  const hours = Math.floor(number / 3600);
  const minutes = Math.floor((number % 3600) / 60);
  const seconds = Math.floor(number % 60);
  return hours ? `${hours}:${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}` : `${minutes}:${String(seconds).padStart(2, "0")}`;
}

async function api(path, options = {}) {
  const response = await fetch(path, {
    ...options,
    headers: { "content-type": "application/json", "x-blofy-device-id": state.deviceId, ...(options.headers || {}) },
  });
  let data = {};
  try { data = await response.json(); } catch {}
  if (!response.ok) {
    const error = new Error(data.error || `تعذر إكمال الطلب (${response.status}).`);
    error.status = response.status;
    throw error;
  }
  return data;
}

function notify(message, kind = "") {
  clearTimeout(toastTimer);
  toast.textContent = message;
  toast.className = `toast ${kind}`.trim();
  toast.hidden = false;
  toastTimer = setTimeout(() => { toast.hidden = true; }, 3500);
}

function brand() {
  return `<div class="wordmark" aria-label="BLOFY PLAYER"><span class="brand-mark"></span><span class="brand-name"><b>BLOFY</b><span>PLAYER</span></span></div>`;
}

function image(url, alt, className = "") {
  return url ? `<img class="${className}" src="${escapeHtml(url)}" alt="${escapeHtml(alt)}" loading="lazy">` : `<span class="poster-placeholder ${className}">▶</span>`;
}

function favoriteKey(item) { return `${item.type}:${item.id}`; }
function isFavorite(item) { return state.favorites.some((entry) => favoriteKey(entry) === favoriteKey(item)); }

async function init() {
  if (window.BlofyAndroid) document.documentElement.classList.add("native-android");
  if ("serviceWorker" in navigator) navigator.serviceWorker.register("/sw.js").catch(() => {});
  try {
    const [sessionData, license] = await Promise.all([
      api("/api/session"),
      api(`/api/license?device_id=${encodeURIComponent(state.deviceId)}`),
    ]);
    state.session = sessionData.session;
    state.license = license;
  } catch (error) {
    notify(error.message, "error");
  }
  if (state.session && state.license?.plan !== "expired") renderMain(); else renderLogin();
}

function renderLogin(error = "") {
  const license = state.license || {};
  const expired = license.plan === "expired";
  const xtream = state.sourceTab === "xtream";
  app.innerHTML = `
    <main class="login-screen">
      <section class="login-card">
        <div class="login-main">
          ${brand()}
          <div class="login-copy">
            <span class="eyebrow">مرحبًا بك في BLOFY</span>
            <h1>كل محتواك في مكان واحد، بسرعة ووضوح.</h1>
            <p>أضف قائمة التشغيل الخاصة بك للوصول إلى البث المباشر والأفلام والمسلسلات والحلقات.</p>
          </div>
          <div class="source-tabs" role="tablist">
            <button class="source-tab ${xtream ? "active" : ""}" data-action="source-tab" data-value="xtream" data-focusable>Xtream Codes</button>
            <button class="source-tab ${!xtream ? "active" : ""}" data-action="source-tab" data-value="m3u" data-focusable>M3U / M3U8</button>
          </div>
          <form id="sourceForm" class="source-form">
            <div class="field full"><label>اسم القائمة</label><input name="name" value="قائمتي" maxlength="50" autocomplete="off" data-focusable /></div>
            ${xtream ? `
              <div class="field full"><label>رابط الخادم</label><input name="serverUrl" type="url" dir="ltr" placeholder="http://server.example:8080" autocomplete="url" required data-focusable /></div>
              <div class="field"><label>اسم المستخدم</label><input name="username" dir="ltr" autocomplete="username" required data-focusable /></div>
              <div class="field"><label>كلمة المرور</label><input name="password" type="password" dir="ltr" autocomplete="current-password" required data-focusable /></div>
            ` : `
              <div class="field full"><label>رابط M3U أو M3U8</label><input name="url" type="url" dir="ltr" placeholder="https://example.com/playlist.m3u" autocomplete="url" required data-focusable /></div>
            `}
            <div class="form-actions">
              <button class="primary-button" type="submit" data-focusable>إضافة قائمة تشغيل　＋</button>
              <span id="formError" class="form-error">${escapeHtml(error)}</span>
            </div>
          </form>
          <p class="privacy-note">بيانات قائمتك مشفّرة داخل جلسة آمنة ولا تظهر في روابط التشغيل. BLOFY PLAYER لا يوفّر أي محتوى.</p>
        </div>
        <aside class="device-panel">
          <div class="device-card">
            <div class="device-head"><h2>حالة الجهاز</h2><p>فعّل التطبيق أو استخدم الفترة التجريبية</p></div>
            <div class="device-row"><label>رقم الجهاز</label><div class="device-code"><b>${escapeHtml(state.deviceId)}</b><button class="copy-button" data-action="copy-device" data-focusable aria-label="نسخ رقم الجهاز">▣</button></div></div>
            <div class="device-row"><label>حالة الاشتراك</label><div class="license-line"><b>${escapeHtml(license.status || "جاري التحقق")}</b><span class="status-badge ${expired ? "expired" : ""}">${expired ? "منتهي" : `${escapeHtml(license.remainingDays ?? "—")} أيام متبقية`}</span></div><div class="small muted" style="margin-top:9px">الانتهاء: ${formatDate(license.expiresAt)}</div></div>
            <div class="qr-block"><b>تفعيل الجهاز</b><p class="small muted">امسح الرمز ثم أدخل رقم الجهاز</p><div class="qr-wrap"><img src="/api/qr?text=${encodeURIComponent(`${license.activationUrl || "https://blofy.tv/activate"}?device_id=${state.deviceId}`)}" alt="رمز تفعيل الجهاز" /></div><div class="activation-link">${escapeHtml(license.activationUrl || "https://blofy.tv/activate")}</div></div>
          </div>
        </aside>
      </section>
    </main>`;
  focusFirst();
}

function navButton(route, label) {
  return `<button class="nav-button ${state.route === route ? "active" : ""}" data-action="navigate" data-route="${route}" data-focusable>${label}</button>`;
}

function renderMain() {
  app.innerHTML = `
    <div class="app-shell">
      <header class="topbar">
        ${brand()}
        <nav class="topnav" aria-label="التنقل الرئيسي">
          ${navButton("home", "الرئيسية")}${navButton("live", "بث مباشر")}${navButton("movies", "أفلام")}${navButton("series", "مسلسلات")}
        </nav>
        <div class="top-actions">
          <button class="search-button" data-action="global-search" data-focusable aria-label="بحث">⌕　بحث</button>
          <button class="account-button" data-action="navigate" data-route="settings" data-focusable><span class="avatar">B</span><span>${escapeHtml(state.session?.name || "الحساب")}</span></button>
        </div>
      </header>
      <main id="mainContent" class="main-content">${renderRoute()}</main>
      <nav class="bottom-nav" aria-label="التنقل للجوال">
        ${bottomButton("home", "⌂", "الرئيسية")}${bottomButton("live", "◉", "مباشر")}${bottomButton("movies", "▤", "أفلام")}${bottomButton("series", "▦", "مسلسلات")}${bottomButton("settings", "⚙", "الإعدادات")}
      </nav>
    </div>`;
  focusFirst(false);
}

function bottomButton(route, icon, label) {
  return `<button class="bottom-button ${state.route === route ? "active" : ""}" data-action="navigate" data-route="${route}" data-focusable><span>${icon}</span>${label}</button>`;
}

function renderRoute() {
  if (state.route === "home") return renderHome();
  if (["live", "movies", "series"].includes(state.route)) return renderCatalog();
  if (state.route === "movie-detail" || state.route === "series-detail") return renderDetail();
  if (state.route === "favorites") return renderCollection("المفضلة", "قنواتك وأفلامك ومسلسلاتك المحفوظة", state.favorites, "♡");
  if (state.route === "history") return renderCollection("سجل المشاهدة", "تابع ما شاهدته من حيث توقفت", state.history, "◷");
  if (state.route === "settings") return renderSettings();
  return renderHome();
}

function renderHome() {
  const recent = state.history.slice(0, 6);
  return `
    <section class="page-heading"><div><h1>مساء الخير 👋</h1><p>${escapeHtml(state.session?.name || "قائمتي")} • جاهز للمشاهدة</p></div><span class="status-badge">● متصل</span></section>
    <section class="home-grid">
      <button class="feature-card featured" data-action="navigate" data-route="live" data-focusable><span class="feature-icon">♒</span><h2>بث مباشر</h2><p>مشاهدة القنوات المباشرة</p></button>
      <button class="feature-card" data-action="navigate" data-route="movies" data-focusable><span class="feature-icon">▤</span><h2>أفلام</h2><p>أحدث الأفلام العالمية</p></button>
      <button class="feature-card" data-action="navigate" data-route="series" data-focusable><span class="feature-icon">▦</span><h2>مسلسلات</h2><p>المواسم والحلقات</p></button>
      <button class="feature-card small-card" data-action="navigate" data-route="favorites" data-focusable><span class="feature-icon">♥</span><span><h2>المفضلة</h2><p>قنواتك وقوائمك المفضلة</p></span></button>
      <button class="feature-card small-card" data-action="navigate" data-route="history" data-focusable><span class="feature-icon">◷</span><span><h2>سجل المشاهدة</h2><p>تابع ما شاهدته</p></span></button>
      <button class="feature-card small-card" data-action="navigate" data-route="settings" data-focusable><span class="feature-icon">⚙</span><span><h2>الإعدادات</h2><p>تخصيص التطبيق</p></span></button>
    </section>
    <section class="home-wide">
      <div class="section-title"><h2>آخر المشاهدات</h2><span>${recent.length ? "أكمل من حيث توقفت" : "ستظهر هنا تلقائيًا"}</span></div>
      ${recent.length ? `<div class="recent-strip">${recent.map(recentCard).join("")}</div>` : emptyState("◷", "لا يوجد سجل مشاهدة", "ابدأ تشغيل قناة أو فيلم وستجده هنا.")}
    </section>`;
}

function recentCard(item) {
  return `<button class="recent-card" data-action="open-item" data-key="${escapeHtml(favoriteKey(item))}" data-collection="history" data-focusable>${item.image ? `<img src="${escapeHtml(item.image)}" alt="" />` : ""}<strong>${escapeHtml(item.name)}</strong></button>`;
}

function pageTitle() {
  if (state.route === "live") return ["البث المباشر", "قنواتك مع دليل البرامج EPG"];
  if (state.route === "movies") return ["الأفلام", "تصفّح أحدث مكتبة أفلامك"];
  return ["المسلسلات", "المواسم والحلقات مرتبة وواضحة"];
}

function renderCatalog() {
  const [title, subtitle] = pageTitle();
  const allLabel = state.route === "live" ? "جميع القنوات" : state.route === "movies" ? "جميع الأفلام" : "جميع المسلسلات";
  return `
    <section class="page-heading"><div><h1>${title}</h1><p>${subtitle}</p></div></section>
    <section class="content-layout">
      <aside class="category-sidebar">
        <div class="category-title">الفئات</div>
        <div class="category-list">
          <button class="category-button ${!state.category ? "active" : ""}" data-action="category" data-value="" data-focusable>☆　${allLabel}</button>
          ${state.categories.map((category) => `<button class="category-button ${state.category === category.id ? "active" : ""}" data-action="category" data-value="${escapeHtml(category.id)}" data-focusable>◇　${escapeHtml(category.name)}</button>`).join("")}
        </div>
      </aside>
      <div class="catalog-panel">
        <div class="catalog-toolbar"><div class="search-wrap"><input id="catalogSearch" class="search-input" value="${escapeHtml(state.search)}" placeholder="ابحث بالاسم…" data-focusable /></div><span class="count-label">${state.loading ? "جاري التحميل…" : `${state.total} نتيجة`}</span></div>
        ${state.loading ? renderSkeletons() : state.route === "live" ? renderLive() : renderPosters(state.items)}
        ${!state.loading && state.items.length < state.total ? `<button class="secondary-button load-more" data-action="load-more" data-focusable>عرض المزيد</button>` : ""}
      </div>
    </section>`;
}

function renderSkeletons() {
  if (state.route === "live") return `<div class="live-grid"><div class="channel-list">${Array.from({ length: 7 }, () => `<div class="channel-row"><span class="skeleton" style="width:28px;height:12px"></span><span class="skeleton" style="width:34px;height:34px;border-radius:8px"></span><span class="skeleton" style="height:13px;border-radius:7px"></span></div>`).join("")}</div><div class="channel-preview skeleton"></div></div>`;
  return `<div class="poster-grid">${Array.from({ length: 12 }, () => `<div><div class="poster skeleton"></div><div class="skeleton" style="height:12px;margin:9px 3px;border-radius:5px"></div></div>`).join("")}</div>`;
}

function renderPosters(items) {
  if (!items.length) return emptyState("▤", "لا يوجد محتوى", "جرّب فئة أخرى أو امسح عبارة البحث.");
  return `<div class="poster-grid">${items.map(mediaCard).join("")}</div>`;
}

function mediaCard(item) {
  return `<button class="media-card" data-action="select-media" data-id="${escapeHtml(item.id)}" data-focusable>
    <span class="poster">${image(item.image, item.name)}<span class="card-action">▶</span></span>
    <span class="media-info"><strong>${escapeHtml(item.name)}</strong><span class="media-meta"><span>${escapeHtml(item.year || item.extension || "")}</span>${item.rating ? `<span>★ ${escapeHtml(item.rating)}</span>` : ""}</span></span>
  </button>`;
}

function renderLive() {
  if (!state.items.length) return emptyState("♒", "لا توجد قنوات", "جرّب فئة أخرى أو امسح عبارة البحث.");
  const selected = state.selected || state.items[0];
  return `<div class="live-grid">
    <div class="channel-list">${state.items.map((item, index) => `<button class="channel-row ${selected?.id === item.id ? "active" : ""}" data-action="select-channel" data-id="${escapeHtml(item.id)}" data-focusable><span class="channel-number">${index + 1}</span>${item.image ? `<img src="${escapeHtml(item.image)}" alt="" loading="lazy" />` : `<span class="avatar">▶</span>`}<span class="channel-name">${escapeHtml(item.name)}</span><span class="favorite-mini">${isFavorite(item) ? "♥" : "♡"}</span></button>`).join("")}</div>
    <article class="channel-preview">
      <div class="preview-art">${selected?.image ? `<img src="${escapeHtml(selected.image)}" alt="${escapeHtml(selected.name)}" />` : "<span>♒</span>"}</div>
      <div class="preview-body"><span class="live-pill">LIVE</span><h2>${escapeHtml(selected?.name || "اختر قناة")}</h2>
        <div class="epg-list">${state.epg.length ? state.epg.map(epgRow).join("") : `<div class="muted small">دليل البرامج غير متوفر لهذه القناة.</div>`}</div>
        <div class="button-row"><button class="primary-button" data-action="play-selected" data-focusable>تشغيل　▶</button><button class="secondary-button" data-action="toggle-favorite" data-id="${escapeHtml(selected?.id || "")}" data-focusable>${selected && isFavorite(selected) ? "إزالة من المفضلة ♥" : "إضافة للمفضلة ♡"}</button></div>
      </div>
    </article>
  </div>`;
}

function epgRow(entry) {
  const now = Date.now();
  const current = entry.start <= now && entry.end >= now;
  return `<div class="epg-row ${current ? "current" : ""}"><span>${new Date(entry.start).toLocaleTimeString("ar-SA", { hour: "2-digit", minute: "2-digit" })} - ${new Date(entry.end).toLocaleTimeString("ar-SA", { hour: "2-digit", minute: "2-digit" })}</span><b>${escapeHtml(entry.title)}</b></div>`;
}

function renderDetail() {
  if (state.loading || !state.detail) return `<div class="detail-hero skeleton"></div>`;
  const item = state.detail;
  const series = state.route === "series-detail";
  return `
    <article class="detail-hero">
      ${item.backdrop ? `<img class="detail-backdrop" src="${escapeHtml(item.backdrop)}" alt="" />` : ""}
      <div class="detail-content">
        ${item.image ? `<img class="detail-poster" src="${escapeHtml(item.image)}" alt="${escapeHtml(item.name)}" />` : `<div class="detail-poster poster-placeholder">▶</div>`}
        <div class="detail-copy"><span class="eyebrow">${series ? "مسلسل" : "فيلم"}</span><h1>${escapeHtml(item.name)}</h1><div class="chips">${item.year ? `<span class="chip">${escapeHtml(item.year)}</span>` : ""}${item.rating ? `<span class="chip">★ ${escapeHtml(item.rating)}</span>` : ""}${item.duration ? `<span class="chip">${escapeHtml(item.duration)}</span>` : ""}${item.genre ? `<span class="chip">${escapeHtml(item.genre)}</span>` : ""}</div><p>${escapeHtml(item.description || "لا توجد نبذة متاحة لهذا المحتوى.")}</p><div class="button-row">${!series ? `<button class="primary-button" data-action="play-detail" data-focusable>تشغيل الآن　▶</button>` : ""}<button class="secondary-button" data-action="toggle-detail-favorite" data-focusable>${isFavorite(item) ? "إزالة من المفضلة ♥" : "إضافة للمفضلة ♡"}</button><button class="secondary-button" data-action="back" data-focusable>رجوع</button></div></div>
      </div>
    </article>
    ${series ? renderSeasons(item) : ""}`;
}

function renderSeasons(item) {
  if (!item.seasons?.length) return `<div class="section-title"><h2>الحلقات</h2></div>${emptyState("▦", "لا توجد حلقات", "لم يرسل الخادم بيانات المواسم والحلقات.")}`;
  const season = item.seasons.find((entry) => entry.season === state.season) || item.seasons[0];
  return `<div class="section-title"><h2>المواسم والحلقات</h2><span>${season.episodes.length} حلقة</span></div><div class="season-tabs">${item.seasons.map((entry) => `<button class="season-button ${entry.season === season.season ? "active" : ""}" data-action="season" data-value="${escapeHtml(entry.season)}" data-focusable>الموسم ${escapeHtml(entry.season)}</button>`).join("")}</div><div class="episode-list">${season.episodes.map((episode) => `<button class="episode-row" data-action="play-episode" data-id="${escapeHtml(episode.id)}" data-focusable><span class="episode-number">${episode.number}</span><span><b>${escapeHtml(episode.title)}</b><small class="muted">${escapeHtml(episode.duration || `الموسم ${season.season}`)}</small></span><span>تشغيل　▶</span></button>`).join("")}</div>`;
}

function renderCollection(title, subtitle, entries, icon) {
  const collection = title === "سجل المشاهدة" ? "history" : "favorites";
  return `<section class="page-heading"><div><h1>${title}</h1><p>${subtitle}</p></div><span class="count-label">${entries.length} عنصر</span></section>${entries.length ? `<div class="poster-grid">${entries.map((item) => collectionCard(item, collection)).join("")}</div>` : emptyState(icon, `لا توجد عناصر في ${title}`, title === "المفضلة" ? "أضف أي قناة أو فيلم أو مسلسل بالضغط على القلب." : "سيظهر المحتوى هنا عند بدء المشاهدة.")}`;
}

function collectionCard(item, collection) {
  return `<button class="media-card" data-action="open-item" data-key="${escapeHtml(favoriteKey(item))}" data-collection="${collection}" data-focusable><span class="poster">${image(item.image, item.name)}<span class="card-action">▶</span></span><span class="media-info"><strong>${escapeHtml(item.name)}</strong><span class="media-meta"><span>${escapeHtml(item.year || item.extension || "")}</span></span></span></button>`;
}

function renderSettings() {
  const license = state.license || {};
  return `
    <section class="page-heading"><div><h1>الإعدادات</h1><p>تحكم في تجربة التشغيل والجهاز</p></div></section>
    <div class="settings-grid">
      <section class="settings-card"><h2>التشغيل</h2>${settingToggle("autoplayNext", "تشغيل الحلقة التالية تلقائيًا", "عند نهاية الحلقة الحالية")}${settingToggle("rememberPosition", "حفظ موضع المشاهدة", "لإكمال الفيلم أو الحلقة لاحقًا")}<div class="setting-row"><span><b>وضع التخزين المؤقت</b><small>المتوازن مناسب لمعظم الاتصالات</small></span><select class="setting-select" data-setting="bufferMode" data-focusable><option value="fast" ${state.settings.bufferMode === "fast" ? "selected" : ""}>سريع</option><option value="balanced" ${state.settings.bufferMode === "balanced" ? "selected" : ""}>متوازن</option><option value="stable" ${state.settings.bufferMode === "stable" ? "selected" : ""}>ثابت</option></select></div></section>
      <section class="settings-card"><h2>الجهاز والاشتراك</h2><div class="account-summary"><div class="stat"><label>رقم الجهاز</label><b>${escapeHtml(state.deviceId)}</b></div><div class="stat"><label>الخطة</label><b>${escapeHtml(license.status || "—")}</b></div><div class="stat"><label>الانتهاء</label><b>${formatDate(license.expiresAt)}</b></div></div><div class="button-row" style="margin-top:18px"><button class="secondary-button" data-action="refresh-license" data-focusable>تحديث التفعيل</button><button class="secondary-button" data-action="copy-device" data-focusable>نسخ رقم الجهاز</button>${window.BlofyAndroid ? `<button class="secondary-button" data-action="server-settings" data-focusable>إعداد رابط الخادم</button>` : ""}</div></section>
      <section class="settings-card"><h2>قائمة التشغيل</h2><div class="setting-row"><span><b>${escapeHtml(state.session?.name || "قائمتي")}</b><small>${escapeHtml(state.session?.kind === "xtream" ? "Xtream Codes" : "M3U / M3U8")} • ${escapeHtml(state.session?.serverName || "")}</small></span><span class="status-badge">متصل</span></div><button class="danger-button" data-action="logout" data-focusable>حذف القائمة والعودة للدخول</button></section>
      <section class="settings-card"><h2>حول التطبيق</h2><div class="setting-row"><span><b>BLOFY PLAYER WEB</b><small>نسخة 2026.08 • مشغل فقط دون محتوى</small></span>${brand()}</div><p class="small muted">يدعم Xtream Codes وM3U/M3U8 وHLS، ومصمم للجوال والتابلت والتلفزيون مع تنقّل كامل بالريموت.</p></section>
    </div>`;
}

function settingToggle(key, title, subtitle) {
  return `<div class="setting-row"><span><b>${title}</b><small>${subtitle}</small></span><button class="toggle ${state.settings[key] ? "on" : ""}" data-action="setting-toggle" data-setting="${key}" data-focusable aria-label="${title}"></button></div>`;
}

function emptyState(icon, title, subtitle) {
  return `<div class="empty-state"><span class="empty-icon">${icon}</span><b>${title}</b><span>${subtitle}</span></div>`;
}

async function navigate(route, push = true) {
  if (push && state.route !== route) state.navStack.push(state.route);
  state.route = route;
  state.detail = null;
  state.selected = null;
  state.epg = [];
  if (["live", "movies", "series"].includes(route)) {
    state.type = route;
    state.category = "";
    state.search = "";
    state.page = 1;
    await loadCatalog(true);
  } else renderMain();
}

async function loadCatalog(reset = false) {
  if (reset) { state.items = []; state.categories = []; state.total = 0; state.page = 1; }
  state.loading = true;
  renderMain();
  try {
    const params = new URLSearchParams({ type: state.type, page: String(state.page), category: state.category, search: state.search });
    let data;
    if (!state.categories.length) {
      const [categoryData, catalogData] = await Promise.all([
        api(`/api/categories?type=${encodeURIComponent(state.type)}`),
        api(`/api/catalog?${params}`),
      ]);
      state.categories = categoryData.categories || [];
      data = catalogData;
    } else data = await api(`/api/catalog?${params}`);
    state.items = state.page === 1 ? data.items : [...state.items, ...data.items];
    state.total = data.total || 0;
    if (state.route === "live" && !state.selected) state.selected = state.items[0] || null;
  } catch (error) {
    if (error.status === 401) { state.session = null; return renderLogin(error.message); }
    notify(error.message, "error");
  } finally {
    state.loading = false;
    renderMain();
    if (state.route === "live" && state.selected) loadEpg(state.selected.id);
  }
}

async function loadEpg(id) {
  try {
    const data = await api(`/api/epg/${encodeURIComponent(id)}`);
    if (state.selected?.id === id) { state.epg = data.entries || []; renderMain(); }
  } catch { state.epg = []; }
}

async function openDetail(item) {
  state.navStack.push(state.route);
  state.route = item.type === "series" ? "series-detail" : "movie-detail";
  state.loading = true;
  state.detail = null;
  renderMain();
  try {
    state.detail = await api(`/api/${item.type === "series" ? "series" : "movie"}/${encodeURIComponent(item.id)}`);
    state.season = state.detail.seasons?.[0]?.season || "";
  } catch (error) {
    notify(error.message, "error");
    state.route = state.navStack.pop() || "home";
  } finally { state.loading = false; renderMain(); }
}

function goBack() {
  if (!playerModal.hidden) return closePlayer();
  const route = state.navStack.pop() || "home";
  navigate(route, false);
}

function toggleFavorite(item) {
  if (!item) return;
  const key = favoriteKey(item);
  const index = state.favorites.findIndex((entry) => favoriteKey(entry) === key);
  if (index >= 0) { state.favorites.splice(index, 1); notify("تمت الإزالة من المفضلة"); }
  else { state.favorites.unshift({ ...item }); notify("تمت الإضافة للمفضلة"); }
  storage.set("blofy_favorites", state.favorites);
  renderMain();
}

function addHistory(item) {
  const copy = { ...item, watchedAt: Date.now() };
  state.history = [copy, ...state.history.filter((entry) => favoriteKey(entry) !== favoriteKey(item))].slice(0, 50);
  storage.set("blofy_history", state.history);
}

function clearPlayerTimeout() {
  if (playerTimeout) clearTimeout(playerTimeout);
  playerTimeout = null;
}

function armPlayerTimeout() {
  clearPlayerTimeout();
  playerTimeout = setTimeout(() => {
    if (!playerModal.hidden && video.readyState < 2) {
      showPlayerError(playerCompatibility
        ? "لم يستجب المصدر حتى بوضع التوافق. تحقق من المصدر أو جرّب قناة أخرى."
        : "تأخر المصدر في الاستجابة. جرّب وضع التوافق لتحويل البث إلى H.264/AAC.");
    }
  }, playerCompatibility ? 32_000 : 22_000);
}

async function openPlayer(item, compatibility = false) {
  if (!item?.id) return;
  playerItem = item;
  playerFailures = 0;
  playerCompatibility = compatibility;
  clearPlayerTimeout();
  addHistory(item);
  const type = item.type === "series" ? "episode" : item.type;
  const extension = item.extension || (type === "live" ? "ts" : "mp4");
  const url = `/api/play/${type}/${encodeURIComponent(item.id)}?ext=${encodeURIComponent(extension)}${compatibility ? "&compat=2" : ""}`;
  if (window.BlofyAndroid?.play) {
    try {
      const native = await api(`/api/native-link/${type}/${encodeURIComponent(item.id)}?ext=${encodeURIComponent(extension)}`);
      if (native.mode !== "direct") throw new Error("الخادم لا يستخدم وضع Media3 المباشر");
      const nativeUrl = new URL(native.url, location.origin);
      window.BlofyAndroid.play(nativeUrl.toString(), item.name || "BLOFY PLAYER", type, native.extension || extension);
      return;
    } catch (error) {
      notify(`تعذر فتح Media3: ${error.message || "خطأ غير معروف"}`, "error");
      return;
    }
  }
  document.getElementById("playerTitle").textContent = item.name || "BLOFY PLAYER";
  document.getElementById("playerSubtitle").textContent = item.type === "live" ? "بث مباشر" : item.type === "episode" ? "حلقة" : "فيلم";
  document.getElementById("livePill").hidden = item.type !== "live";
  playerStatus.hidden = false;
  playerStatus.innerHTML = `<span class="spinner"></span><b>${compatibility ? "جاري تشغيل وضع التوافق…" : "جاري تجهيز البث…"}</b><small>${compatibility ? "تحويل آمن إلى H.264/AAC" : "نختار أسرع طريقة تشغيل متوافقة"}</small>`;
  playerModal.hidden = false;
  document.body.style.overflow = "hidden";
  destroyHls();
  video.removeAttribute("src");
  video.load();
  const useHls = type === "live" || !/^(mp4|m4v|webm|mov)$/i.test(item.extension || "mp4");
  if (useHls && window.Hls?.isSupported()) attachHls(url);
  else {
    video.src = url;
    video.addEventListener("error", onNativeError, { once: true });
    video.play().catch(() => {});
  }
  armPlayerTimeout();
  setTimeout(() => document.querySelector('[data-player-action="play"]')?.focus(), 60);
}

function hlsConfig() {
  const mode = state.settings.bufferMode;
  return {
    enableWorker: true,
    lowLatencyMode: mode === "fast",
    backBufferLength: mode === "stable" ? 60 : 30,
    maxBufferLength: mode === "stable" ? 45 : mode === "fast" ? 12 : 25,
    manifestLoadingTimeOut: 9000,
    levelLoadingTimeOut: 9000,
    fragLoadingTimeOut: 12000,
    manifestLoadingMaxRetry: 2,
    levelLoadingMaxRetry: 3,
    fragLoadingMaxRetry: 4,
    startLevel: -1,
  };
}

function attachHls(url) {
  hls = new window.Hls(hlsConfig());
  hls.loadSource(url);
  hls.attachMedia(video);
  hls.on(window.Hls.Events.MANIFEST_PARSED, () => {
    populateTracks();
    video.play().catch(() => {});
  });
  hls.on(window.Hls.Events.LEVEL_SWITCHED, populateTracks);
  hls.on(window.Hls.Events.AUDIO_TRACKS_UPDATED, populateTracks);
  hls.on(window.Hls.Events.SUBTITLE_TRACKS_UPDATED, populateTracks);
  hls.on(window.Hls.Events.ERROR, (_, data) => {
    if (!data.fatal) return;
    playerFailures += 1;
    if (data.type === window.Hls.ErrorTypes.NETWORK_ERROR && playerFailures <= 2) {
      playerStatus.hidden = false;
      hls.startLoad();
    } else if (data.type === window.Hls.ErrorTypes.MEDIA_ERROR && playerFailures <= 2) hls.recoverMediaError();
    else showPlayerError("تعذر تشغيل هذا المصدر. جرّب قناة أخرى أو غيّر وضع التخزين المؤقت.");
  });
}

function onNativeError() { showPlayerError("صيغة هذا المحتوى غير مدعومة على الجهاز أو أن الرابط لا يستجيب."); }
function onPlayerReady() { clearPlayerTimeout(); playerStatus.hidden = true; populateTracks(); }
function showPlayerError(message) {
  clearPlayerTimeout();
  hls?.stopLoad();
  playerStatus.hidden = false;
  playerStatus.innerHTML = `<b>تعذر التشغيل</b><small>${escapeHtml(message)}</small><div class="button-row">${playerCompatibility ? `<button class="secondary-button" data-player-action="retry" data-focusable>إعادة المحاولة</button>` : `<button class="primary-button" data-player-action="compat" data-focusable>تشغيل بوضع التوافق</button>`}<button class="secondary-button" data-player-action="close" data-focusable>إغلاق</button></div>`;
  setTimeout(() => playerStatus.querySelector("[data-focusable]")?.focus(), 30);
}

function populateTracks() {
  qualitySelect.innerHTML = `<option value="auto">تلقائي</option>${hls?.levels?.map((level, index) => `<option value="${index}" ${hls.currentLevel === index ? "selected" : ""}>${level.height ? `${level.height}p` : `جودة ${index + 1}`}</option>`).join("") || ""}`;
  audioSelect.innerHTML = `<option value="auto">الصوت</option>${hls?.audioTracks?.map((track, index) => `<option value="${index}">${escapeHtml(track.name || track.lang || `مسار ${index + 1}`)}</option>`).join("") || ""}`;
  subtitleSelect.innerHTML = `<option value="off">بدون ترجمة</option>${hls?.subtitleTracks?.map((track, index) => `<option value="${index}">${escapeHtml(track.name || track.lang || `ترجمة ${index + 1}`)}</option>`).join("") || ""}`;
}

function destroyHls() { if (hls) { hls.destroy(); hls = null; } }
function closePlayer() {
  clearPlayerTimeout();
  destroyHls();
  video.pause();
  video.removeAttribute("src");
  video.load();
  playerModal.hidden = true;
  playerItem = null;
  playerCompatibility = false;
  document.body.style.overflow = "";
  if (document.fullscreenElement === playerModal) document.exitFullscreen().catch(() => {});
  renderMain();
}

app.addEventListener("submit", async (event) => {
  if (event.target.id !== "sourceForm") return;
  event.preventDefault();
  const button = event.target.querySelector('button[type="submit"]');
  const formError = document.getElementById("formError");
  button.disabled = true;
  button.textContent = "جاري التحقق…";
  formError.textContent = "";
  const values = Object.fromEntries(new FormData(event.target));
  try {
    const data = await api("/api/session", { method: "POST", body: JSON.stringify({ kind: state.sourceTab, ...values }) });
    state.session = data.session;
    notify("تمت إضافة القائمة بنجاح");
    state.route = "home";
    renderMain();
  } catch (error) {
    formError.textContent = error.message;
    button.disabled = false;
    button.textContent = "إضافة قائمة تشغيل　＋";
  }
});

app.addEventListener("input", (event) => {
  if (event.target.id !== "catalogSearch") return;
  clearTimeout(searchTimer);
  state.search = event.target.value;
  searchTimer = setTimeout(() => { state.page = 1; loadCatalog(false); }, 420);
});

app.addEventListener("error", (event) => {
  const target = event.target;
  if (!(target instanceof HTMLImageElement) || target.dataset.failed) return;
  target.dataset.failed = "1";
  const replacement = document.createElement("span");
  replacement.className = "poster-placeholder";
  replacement.textContent = "▶";
  target.replaceWith(replacement);
}, true);

app.addEventListener("change", (event) => {
  const setting = event.target.dataset.setting;
  if (setting) { state.settings[setting] = event.target.value; storage.set("blofy_settings", state.settings); notify("تم حفظ الإعداد"); }
});

app.addEventListener("click", async (event) => {
  const target = event.target.closest("[data-action]");
  if (!target) return;
  const action = target.dataset.action;
  if (action === "source-tab") { state.sourceTab = target.dataset.value; renderLogin(); }
  if (action === "navigate") await navigate(target.dataset.route);
  if (action === "back") goBack();
  if (action === "copy-device") { await navigator.clipboard?.writeText(state.deviceId).catch(() => {}); notify("تم نسخ رقم الجهاز"); }
  if (action === "category") { state.category = target.dataset.value; state.page = 1; await loadCatalog(false); }
  if (action === "load-more") { state.page += 1; await loadCatalog(false); }
  if (action === "select-channel") {
    state.selected = state.items.find((item) => item.id === target.dataset.id) || null;
    state.epg = [];
    renderMain();
    if (state.selected) await loadEpg(state.selected.id);
  }
  if (action === "play-selected") await openPlayer(state.selected);
  if (action === "toggle-favorite") toggleFavorite(state.items.find((item) => item.id === target.dataset.id) || state.selected);
  if (action === "select-media") {
    const item = state.items.find((entry) => entry.id === target.dataset.id);
    if (!item) return;
    if (state.session.kind === "xtream") await openDetail(item); else await openPlayer(item);
  }
  if (action === "toggle-detail-favorite") toggleFavorite(state.detail);
  if (action === "play-detail") await openPlayer(state.detail);
  if (action === "season") { state.season = target.dataset.value; renderMain(); }
  if (action === "play-episode") {
    const season = state.detail.seasons.find((entry) => entry.season === state.season) || state.detail.seasons[0];
    const episode = season.episodes.find((entry) => entry.id === target.dataset.id);
    if (episode) await openPlayer({ ...episode, type: "episode", name: `${state.detail.name} • ${episode.title}`, parentId: state.detail.id });
  }
  if (action === "open-item") {
    const collection = target.dataset.collection === "history" ? state.history : state.favorites;
    const item = collection.find((entry) => favoriteKey(entry) === target.dataset.key);
    if (item) item.type === "series" ? openDetail(item) : openPlayer(item);
  }
  if (action === "setting-toggle") { const key = target.dataset.setting; state.settings[key] = !state.settings[key]; storage.set("blofy_settings", state.settings); renderMain(); }
  if (action === "refresh-license") {
    try { state.license = await api(`/api/license?device_id=${encodeURIComponent(state.deviceId)}`); notify("تم تحديث حالة التفعيل"); renderMain(); } catch (error) { notify(error.message, "error"); }
  }
  if (action === "server-settings") window.BlofyAndroid?.openServerSettings?.();
  if (action === "logout") {
    await api("/api/session", { method: "DELETE" }).catch(() => {});
    state.session = null;
    state.items = [];
    state.categories = [];
    renderLogin();
  }
  if (action === "global-search") {
    if (["live", "movies", "series"].includes(state.route)) document.getElementById("catalogSearch")?.focus();
    else await navigate("movies");
  }
});

playerModal.addEventListener("click", (event) => {
  const target = event.target.closest("[data-player-action]");
  if (!target) return;
  const action = target.dataset.playerAction;
  if (action === "close") closePlayer();
  if (action === "play") video.paused ? video.play().catch(() => {}) : video.pause();
  if (action === "mute") video.muted = !video.muted;
  if (action === "fullscreen") document.fullscreenElement ? document.exitFullscreen() : playerModal.requestFullscreen?.();
  if (action === "compat" && playerItem) openPlayer(playerItem, true);
  if (action === "retry" && playerItem) openPlayer(playerItem, playerCompatibility);
});

qualitySelect.addEventListener("change", () => { if (hls) hls.currentLevel = qualitySelect.value === "auto" ? -1 : Number(qualitySelect.value); });
audioSelect.addEventListener("change", () => { if (hls && audioSelect.value !== "auto") hls.audioTrack = Number(audioSelect.value); });
subtitleSelect.addEventListener("change", () => { if (hls) hls.subtitleTrack = subtitleSelect.value === "off" ? -1 : Number(subtitleSelect.value); });
seek.addEventListener("input", () => { if (Number.isFinite(video.duration) && video.duration > 0) video.currentTime = (Number(seek.value) / 1000) * video.duration; });
video.addEventListener("timeupdate", () => {
  const duration = Number.isFinite(video.duration) ? video.duration : 0;
  seek.value = duration ? String(Math.round((video.currentTime / duration) * 1000)) : "0";
  document.getElementById("playerTime").textContent = duration ? `${formatTime(video.currentTime)} / ${formatTime(duration)}` : formatTime(video.currentTime);
});
video.addEventListener("waiting", () => { if (!video.paused) playerStatus.hidden = false; });
video.addEventListener("playing", onPlayerReady);

function focusables() {
  return [...document.querySelectorAll("[data-focusable]")].filter((element) => !element.disabled && !element.hidden && element.offsetParent !== null);
}

function focusFirst(force = true) {
  if (!force && document.activeElement && document.activeElement !== document.body) return;
  setTimeout(() => focusables()[0]?.focus(), 30);
}

function spatialMove(direction) {
  const items = focusables();
  if (!items.length) return;
  const current = items.includes(document.activeElement) ? document.activeElement : items[0];
  if (current !== document.activeElement) return current.focus();
  const source = current.getBoundingClientRect();
  const sx = source.left + source.width / 2;
  const sy = source.top + source.height / 2;
  let best = null;
  let bestScore = Infinity;
  for (const candidate of items) {
    if (candidate === current) continue;
    const rect = candidate.getBoundingClientRect();
    const cx = rect.left + rect.width / 2;
    const cy = rect.top + rect.height / 2;
    const dx = cx - sx;
    const dy = cy - sy;
    if ((direction === "left" && dx >= -4) || (direction === "right" && dx <= 4) || (direction === "up" && dy >= -4) || (direction === "down" && dy <= 4)) continue;
    const primary = direction === "left" || direction === "right" ? Math.abs(dx) : Math.abs(dy);
    const secondary = direction === "left" || direction === "right" ? Math.abs(dy) : Math.abs(dx);
    const score = primary + secondary * 2.2;
    if (score < bestScore) { bestScore = score; best = candidate; }
  }
  if (best) { best.focus(); best.scrollIntoView({ block: "nearest", inline: "nearest", behavior: "auto" }); }
}

window.BlofyRemote = {
  key(key) {
    const active = document.activeElement;
    const activeTag = active?.tagName;
    const typing = activeTag === "INPUT" && active?.type !== "range";
    if (["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown"].includes(key) && !typing) {
      spatialMove(key.replace("Arrow", "").toLowerCase());
      return true;
    }
    if (["Enter", "Center"].includes(key)) {
      if (!active || active === document.body) focusFirst();
      else if (activeTag === "INPUT") active.focus();
      else active.click?.();
      return true;
    }
    if (["Escape", "Back", "BrowserBack"].includes(key)) {
      if (typing) return false;
      goBack();
      return true;
    }
    return false;
  },
};

let lastResumeRefresh = 0;
window.addEventListener("blofyresume", async () => {
  if (Date.now() - lastResumeRefresh < 2000) return;
  lastResumeRefresh = Date.now();
  try {
    state.license = await api(`/api/license?device_id=${encodeURIComponent(state.deviceId)}`);
    if (state.session && state.license?.plan !== "expired") renderMain(); else renderLogin();
  } catch {}
});

document.addEventListener("keydown", (event) => {
  const key = event.key;
  const activeTag = document.activeElement?.tagName;
  const typing = activeTag === "INPUT" && document.activeElement?.type !== "range";
  if (["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown"].includes(key) && !typing) {
    event.preventDefault();
    spatialMove(key.replace("Arrow", "").toLowerCase());
  }
  if ((key === "Enter" || event.keyCode === 13) && document.activeElement && !["BUTTON", "INPUT", "SELECT"].includes(activeTag)) {
    event.preventDefault(); document.activeElement.click();
  }
  if (["Escape", "Backspace", "BrowserBack"].includes(key) || [10009, 461].includes(event.keyCode)) {
    if (typing && key === "Backspace") return;
    event.preventDefault(); goBack();
  }
});

init();
