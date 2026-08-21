export const APP_VERSION = "2026.08.22.13";
export const NATIVE_PLAYBACK_MODE = "direct";

export function nativePlaybackTarget(rawUrl) {
  const value = String(rawUrl || "");
  if (!value) throw new Error("رابط Media3 غير موجود.");
  return value;
}

export function nativePlaybackPath(rawUrl) {
  const target = new URL(nativePlaybackTarget(rawUrl));
  return target.protocol === "https:" ? "/api/native-play" : "/api/proxy";
}
