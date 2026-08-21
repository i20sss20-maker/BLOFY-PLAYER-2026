export const APP_VERSION = "2026.08.21.6";
export const NATIVE_PLAYBACK_MODE = "direct";

export function nativePlaybackTarget(rawUrl) {
  const value = String(rawUrl || "");
  if (!value) throw new Error("رابط Media3 غير موجود.");
  return value;
}
