"use strict";

const form = document.getElementById("activationForm");
const message = document.getElementById("activationMessage");
const deviceInput = document.getElementById("deviceId");
const queryDevice = new URLSearchParams(location.search).get("device_id");
if (queryDevice) deviceInput.value = queryDevice.toUpperCase();

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  const button = form.querySelector("button");
  button.disabled = true;
  button.textContent = "جاري التفعيل…";
  message.className = "activation-message";
  message.textContent = "";
  try {
    const values = Object.fromEntries(new FormData(form));
    const response = await fetch("/api/activate", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(values),
    });
    const data = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(data.error || "تعذر تفعيل الجهاز.");
    message.className = "activation-message success";
    message.textContent = `تم التفعيل حتى ${new Intl.DateTimeFormat("ar-SA", { dateStyle: "long" }).format(new Date(data.expiresAt))}. ارجع للتطبيق واضغط تحديث التفعيل.`;
    button.textContent = "تم التفعيل ✓";
  } catch (error) {
    message.className = "activation-message error";
    message.textContent = error.message;
    button.disabled = false;
    button.textContent = "تفعيل الآن";
  }
});
