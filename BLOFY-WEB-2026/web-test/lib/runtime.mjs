export const APP_VERSION = "2026.08.23.4";
export const NATIVE_PLAYBACK_MODE = "direct";

export function nativePlaybackTarget(rawUrl) {
  const value = String(rawUrl || "");
  if (!value) throw new Error("رابط Media3 غير موجود.");
  return value;
}

export function nativePlaybackPath(rawUrl) {
  // Native Android allows cleartext provider URLs where required. Always use
  // the authorization redirect endpoint so BLOFY can verify the device once,
  // then let Media3 connect directly to the provider for both HTTP and HTTPS.
  nativePlaybackTarget(rawUrl);
  return "/api/native-play";
}
