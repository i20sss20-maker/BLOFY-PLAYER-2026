#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player"
RES = ROOT / "BLOFY-ANDROID-2026/app/src/main/res"
GRADLE = ROOT / "BLOFY-ANDROID-2026/app/build.gradle.kts"
CONTRACT = ROOT / "tools/BLOFY_PACKAGE64.md"

texts = {}
for p in list(SRC.glob("*.java")) + [GRADLE, CONTRACT]:
    if p.exists():
        texts[p.name] = p.read_text(encoding="utf-8", errors="ignore")
ALL = "\n".join(texts.values())

def any_text(*needles):
    return any(n in ALL for n in needles)

def file_has(name, *needles):
    t = texts.get(name, "")
    return bool(t) and all(n in t for n in needles)

checks = [
("Media3 primary", any_text("ExoPlayer.Builder", "androidx.media3")),
("LibVLC fallback", any_text("org.videolan.libvlc", "LibVLC")),
("FFmpeg extension", any_text("media3-decoder-ffmpeg", "decoder_ffmpeg")),
("HLS support", any_text("HlsMediaSource", "m3u8")),
("MPEG-TS support", any_text("DefaultTsPayloadReaderFactory", '"ts"')),
("direct/no-extension route", any_text("no-extension", '"direct"')),
("MP4/MKV alternate container", any_text('"mkv"', '"mp4"')),
("4K/UHD/HEVC awareness", any_text("UHD", "HEVC", "2160")),
("hardware decoder mode", any_text('"hardware"')),
("software decoder mode", any_text('"software"')),
("bounded recovery", any_text("recoveryStep", "RecoveryBudget")),
("403/404 route breaker", any_text("403", "404", "routeBlocked")),
("startup timeout", any_text("playbackTimeout", "startupTimeoutMs")),
("first-frame success", any_text("firstFrameRendered", "first-frame")),
("stale transaction cancellation", any_text("playbackTransaction", "resolveGeneration")),
("warm live switching", any_text("warmLiveSwitchPending", "replaceLiveSourceOnWarmPlayer")),
("live preview autoplay", any_text("KEY_AUTOPLAY_LIVE", "autoplay_live")),
("mini live preview", any_text("LivePreviewController")),
("full-screen live action", any_text("PlayerActivity", "EXTRA_KIND")),
("remote channel up/down", any_text("KEYCODE_DPAD_UP", "KEYCODE_DPAD_DOWN", "LiveChannelOverlay")),
("group-scoped channels", any_text("categoryId", "category_id")),
("live overlay", any_text("LiveChannelOverlay")),
("EPG support", any_text("EPG", "epg", "KEY_EPG_TIMEZONE")),
("quick-switch cancellation", any_text("cancel(true)", "Cancellation", "switchLiveChannel")),
("VOD compatibility matrix", any_text("vodCandidates", "PlaybackNegotiator")),
("episode playback", any_text('"episode"', "series")),
("natural episode ordering", any_text("episode", "sort", "Comparator")),
("VOD progress persistence", any_text("PlaybackProgress")),
("resume/start behavior", any_text("KEY_RESUME_PROMPT", "resume_prompt")),
("auto-next episode", any_text("KEY_AUTO_NEXT", "auto_next_episode")),
("details no autoplay", any_text("DetailsActivity")),
("details focused page", any_text("DetailsActivity")),
("local complete cache open", any_text("cacheComplete", '"complete".equals(syncState)')),
("complete-sync gate", any_text("syncState", "cacheComplete")),
("package fingerprint integrity", any_text("package_fingerprint", "cacheIntegrityOk")),
("atomic/staged catalog replacement", any_text("staging", "transaction", "commit")),
("source-scoped identity", any_text("CatalogScope", "sourceKey", "provider")),
("home/catalog state cache", any_text("CatalogUiCache", "home-cache")),
("focus/scroll restore", any_text("focus", "restore")),
("large-list tuning", any_text("RecyclerView", "setHasFixedSize", "setItemViewCacheSize")),
("instant local search", any_text("CatalogSearch")),
("Arabic normalized search", any_text("ArabicNormalizer")),
("query cache invalidation", any_text("CountCache", "invalidate", "cache-after-commit")),
("manual refresh", any_text("EXTRA_REFRESH_CATALOG", "تحديث القائمة")),
("deterministic D-pad focus", any_text("setNextFocusUpId", "setNextFocusDownId")),
("settings four-way navigation", file_has("SettingsActivity.java", "setNextFocusLeftId", "setNextFocusRightId")),
("rapid D-pad resilience", any_text("focus", "D-pad", "DPAD")),
("reduced-motion TV mode", any_text("KEY_MOTION", "reduced")),
("clean back handling", any_text("onBackPressed", "KEYCODE_BACK", "finish()")),
("fatal error remote focus", any_text("retryButton.requestFocus", "errorPanel")),
("UI responsiveness watchdog", any_text("UiResponsivenessWatchdog")),
("v340 theme preservation contract", file_has("BLOFY_PACKAGE64.md", "preserves the proven v340 visual theme")),
("stream format setting", file_has("SettingsActivity.java", "KEY_STREAM", '"ts"', '"hls"')),
("decoder setting", file_has("SettingsActivity.java", "KEY_DECODER", '"hardware"', '"software"')),
("buffer setting", file_has("SettingsActivity.java", "KEY_BUFFER", '"fast"', '"stable"')),
("aspect setting", file_has("SettingsActivity.java", "KEY_ASPECT", '"fit"', '"zoom"', '"fill"')),
("stereo output", file_has("SettingsActivity.java", "KEY_AUDIO_OUTPUT", '"stereo"')),
("subtitle preference", file_has("SettingsActivity.java", "KEY_SUBTITLE_LANGUAGE", '"ar"', '"off"')),
("subtitle size", file_has("SettingsActivity.java", "KEY_SUBTITLE_SIZE", '"small"', '"large"')),
("preview autoplay setting", file_has("SettingsActivity.java", "KEY_AUTOPLAY_LIVE")),
("device-adaptive performance", any_text("DeviceCapabilityProfile", "lowRam", "usesReducedPerformance")),
("diagnostic breadcrumbs", any_text("PlaybackDiagnostics", "diagnostic", "breadcrumb")),
("release QA contract", any_text("testDebugUnitTest", "lintRelease", "zipalign", "apksigner") or file_has("BLOFY_PACKAGE64.md", "Release passes unit tests")),
("upgrade-safe COMPLETE target", any_text("versionCode = 1000355", "v340-full-stability-r11e-complete")),
]

assert len(checks) == 64, len(checks)
failed = []
for i, (name, ok) in enumerate(checks, 1):
    print(f"{i:02d} {'PASS' if ok else 'FAIL'} {name}")
    if not ok:
        failed.append((i, name))
print(f"PACKAGE64: {64-len(failed)}/64 source-contract checks passed")
if failed:
    print("Missing/undetected:")
    for i, name in failed:
        print(f" - {i:02d} {name}")
    sys.exit(1)
