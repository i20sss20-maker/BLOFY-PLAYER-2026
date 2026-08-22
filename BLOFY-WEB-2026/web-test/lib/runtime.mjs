export const APP_VERSION = "2026.08.22.13";
export const NATIVE_PLAYBACK_MODE = "direct-provider";

export function nativePlaybackTarget(rawUrl) {
  const value = String(rawUrl || "");
  if (!value) throw new Error("رابط Media3 غير موجود.");
  const target = new URL(value);
  if (target.protocol !== "http:" && target.protocol !== "https:") {
    throw new Error("بروتوكول رابط التشغيل غير مدعوم.");
  }
  return target.toString();
}

export function nativePlaybackPath(rawUrl) {
  nativePlaybackTarget(rawUrl);
  // Railway authorizes the request and issues a redirect only. The video body
  // must travel directly from the IPTV provider to the Android TV device.
  return "/api/native-play";
}
