"use strict";

const form = document.getElementById("activationForm");
const message = document.getElementById("activationMessage");
const deviceInput = document.getElementById("deviceId");
const query = new URLSearchParams(location.search);
const queryDevice = query.get("device_id");
const pairToken = query.get("pair_token") || "";
const profileSection = document.getElementById("profileSection");
const saveProfile = document.getElementById("saveProfile");
const profileFields = document.getElementById("profileFields");
const xtreamTab = document.getElementById("xtreamTab");
const m3uTab = document.getElementById("m3uTab");
const xtreamFields = document.getElementById("xtreamFields");
const m3uFields = document.getElementById("m3uFields");
let profileKind = "xtream";
if (queryDevice) deviceInput.value = queryDevice.toUpperCase();
if (!pairToken) profileSection.hidden = true;

function switchProfile(kind) {
  profileKind = kind;
  const xtream = kind === "xtream";
  xtreamTab.classList.toggle("active", xtream);
  m3uTab.classList.toggle("active", !xtream);
  xtreamFields.hidden = !xtream;
  m3uFields.hidden = xtream;
}

xtreamTab.addEventListener("click", () => switchProfile("xtream"));
m3uTab.addEventListener("click", () => switchProfile("m3u"));
saveProfile.addEventListener("change", () => { profileFields.hidden = !saveProfile.checked; });

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  const button = form.querySelector("button");
  button.disabled = true;
  button.textContent = "جاري التفعيل…";
  message.className = "activation-message";
  message.textContent = "";
  try {
    const values = Object.fromEntries(new FormData(form));
    values.deviceId = String(values.deviceId || "").trim().toUpperCase();
    values.code = String(values.code || "").replace(/\D/g, "");
    if (values.code && !/^[0-9]{6,12}$/.test(values.code)) throw new Error("رمز التفعيل يجب أن يكون من 6 إلى 12 رقمًا.");
    if (pairToken) values.pairToken = pairToken;
    if (pairToken && saveProfile.checked) {
      values.kind = profileKind;
      if (profileKind === "xtream" && (!values.serverUrl || !values.username || !values.password)) throw new Error("أكمل رابط الخادم واسم المستخدم وكلمة المرور.");
      if (profileKind === "m3u" && !values.url) throw new Error("أدخل رابط قائمة M3U أو M3U8.");
    } else {
      delete values.kind;
      delete values.serverUrl;
      delete values.username;
      delete values.password;
      delete values.url;
      delete values.name;
    }
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 30_000);
    const response = await fetch(pairToken ? "/api/device/configure" : "/api/activate", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(values),
      signal: controller.signal,
    }).finally(() => clearTimeout(timeout));
    const data = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(data.error || "تعذر تفعيل الجهاز.");
    message.className = "activation-message success";
    const expiry = data.expiresAt ? ` حتى ${new Intl.DateTimeFormat("ar-SA", { dateStyle: "long" }).format(new Date(data.expiresAt))}` : "";
    message.textContent = `${data.configured ? "تم التفعيل وإرسال بيانات الباقة" : "تم تفعيل الجهاز"}${expiry}. ارجع للتطبيق واضغط تحديث التفعيل.`;
    button.textContent = data.configured ? "تم الربط ✓" : "تم التفعيل ✓";
  } catch (error) {
    message.className = "activation-message error";
    message.textContent = error.name === "AbortError" ? "تأخر خادم الباقة في الرد. تحقق من الرابط ثم أعد المحاولة." : error.message;
    button.disabled = false;
    button.textContent = "تفعيل الآن";
  }
});
