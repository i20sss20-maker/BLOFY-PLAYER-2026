#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player"
PLAYER = JAVA / "PlayerActivity.java"
POLICY = JAVA / "PlaybackPolicy.java"
IMPORTER = JAVA / "PackageImporter.java"
SEVEN = JAVA / "SevenMaxActivity.java"


def read(p): return p.read_text(encoding="utf-8")
def write(p, s): p.write_text(s, encoding="utf-8")
def rep(s, old, new): return s.replace(old, new, 1) if old in s else s

player = read(PLAYER)

# State is bounded per source. Never loop forever.
if "private boolean alternateLiveAttempted;" not in player:
    player = rep(player,
        "    private boolean vlcSubtitlePreferenceApplied;\n",
        "    private boolean vlcSubtitlePreferenceApplied;\n"
        "    private boolean alternateLiveAttempted;\n"
        "    private boolean containerSniffAttempted;\n")

# Per-channel learned Live extension. Respect explicit user TS/HLS override first.
old_cfg_plain = '''    private String configuredExtension(String candidate) {\n        if (!isLiveKind(kind)) return candidate;\n        String mode = playerSetting(SettingsActivity.KEY_STREAM, "auto");\n        if ("ts".equals(mode)) return "ts";\n        if ("hls".equals(mode)) return "m3u8";\n        return candidate;\n    }\n'''
old_cfg_provider = '''    private String configuredExtension(String candidate) {\n        if (!isLiveKind(kind)) return candidate;\n        String mode = playerSetting(SettingsActivity.KEY_STREAM, "auto");\n        if ("ts".equals(mode)) return "ts";\n        if ("hls".equals(mode)) return "m3u8";\n        return PlaybackProfileManager.preferredLiveExtension(this, candidate);\n    }\n'''
new_cfg = '''    private String configuredExtension(String candidate) {\n        if (!isLiveKind(kind)) return candidate;\n        String mode = playerSetting(SettingsActivity.KEY_STREAM, "auto");\n        if ("ts".equals(mode)) return "ts";\n        if ("hls".equals(mode)) return "m3u8";\n        return PlaybackRouteMemory.preferredLiveExtension(this, id, candidate);\n    }\n'''
if old_cfg_provider in player: player = rep(player, old_cfg_provider, new_cfg)
elif old_cfg_plain in player: player = rep(player, old_cfg_plain, new_cfg)

# Cronet existed but the player always requested platform HTTP. Keep canonical on
# platform HTTP; compatibility step 1 may use Cronet and falls back safely if absent.
old_ds = '''    private DataSource.Factory createDataSourceFactory() {\n        // Avoid an identical queued retry while the Cronet provider is still\n        // installing. The compatibility fallback is a different decoder/stack.\n        return PlaybackTransportFactory.create(this, false, network,\n                15_000, 30_000, recoveryStep, playbackReferer);\n    }\n'''
new_ds = '''    private DataSource.Factory createDataSourceFactory() {\n        boolean preferCronet = recoveryStep == 1 && PlaybackTransportFactory.isCronetReady();\n        return PlaybackTransportFactory.create(this, preferCronet, network,\n                recoveryStep == 0 ? 8_000 : 4_000,\n                recoveryStep == 0 ? 20_000 : 10_000,\n                recoveryStep, playbackReferer);\n    }\n'''
player = rep(player, old_ds, new_ds)

old_transport = '''    private String activeTransportName() {\n        return usingVlc ? "libvlc" : "default-http";\n    }\n'''
new_transport = '''    private String activeTransportName() {\n        if (usingVlc) return "libvlc";\n        return recoveryStep == 1 && PlaybackTransportFactory.isCronetReady()\n                ? "cronet" : "default-http";\n    }\n'''
player = rep(player, old_transport, new_transport)

# Record only a route that rendered a real frame. This keeps one broken channel
# from changing every other channel in the playlist.
old_remember_empty = '''    private void rememberSuccessfulTransport() {\n        // Deliberately session-only. Persisting this choice by file extension\n        // made one unusual host slow down every other host using that extension.\n    }\n'''
old_remember_profile = '''    private void rememberSuccessfulTransport() {\n        PlaybackProfileManager.recordSuccess(this, kind, extension, activeTransportName());\n    }\n'''
new_remember = '''    private void rememberSuccessfulTransport() {\n        PlaybackRouteMemory.recordSuccess(this, kind, id, extension, activeTransportName());\n        if (!isLive()) PlaybackProfileManager.recordSuccess(this, kind, extension, activeTransportName());\n    }\n'''
if old_remember_profile in player: player = rep(player, old_remember_profile, new_remember)
elif old_remember_empty in player: player = rep(player, old_remember_empty, new_remember)

# Core recovery: current proven source -> direct on hard network failure -> alternate
# TS/HLS for Live -> no-MIME sniff for VOD/episodes -> LibVLC. Every branch is once.
start = player.find('    private void recoverFromFailure(String reason) {')
end = player.find('    private void showPlaybackFailure(String reason) {', start)
if start < 0 or end < 0:
    raise SystemExit('v334: recoverFromFailure block not found')
new_recovery = '''    private void recoverFromFailure(String reason) {\n        if (isFinishing() || isDestroyed()) return;\n        playbackHandler.removeCallbacks(playbackTimeout);\n        playbackHandler.removeCallbacks(markPlaybackStable);\n\n        if (usingVlc) {\n            showPlaybackFailure(reason);\n            return;\n        }\n\n        Log.w(TAG, "adaptive-recovery reason=" + reason + " kind=" + kind\n                + " ext=" + extension + " variant=" + sourceVariant\n                + " transport=" + activeTransportName());\n\n        // Hard URL/DNS/HTTP failures get one direct-source resolve. Startup/black\n        // screen failures skip this so they do not waste another long resolve.\n        if (PlaybackPolicy.isNetworkFailure(reason)\n                && !PlaybackPolicy.isStartupTimeout(reason)\n                && "canonical".equals(sourceVariant) && !id.isEmpty()) {\n            releasePlayer();\n            sourceVariant = "direct";\n            recoveryStep = 1;\n            url = null;\n            resolvePlaybackLink();\n            return;\n        }\n\n        // A Live source is successful only after onRenderedFirstFrame(). If a\n        // channel connects but stays black, try the alternate Xtream output once.\n        if (isLive() && !alternateLiveAttempted && !id.isEmpty()) {\n            if ("direct".equals(sourceVariant)) restoreCanonicalSource();\n            alternateLiveAttempted = true;\n            releasePlayer();\n            extension = PlaybackPolicy.alternateLiveExtension(extension);\n            sourceVariant = "canonical";\n            recoveryStep = 1;\n            url = null;\n            resolvePlaybackLink();\n            return;\n        }\n\n        // Movies/episodes can be mislabeled MP4/MKV. Re-open the same resolved URL\n        // once without a forced MIME so Media3 sniffs the real container.\n        if (!isLive() && !containerSniffAttempted && validUrl(url)) {\n            if ("direct".equals(sourceVariant)) restoreCanonicalSource();\n            containerSniffAttempted = true;\n            sourceVariant = "no-extension";\n            recoveryStep = 1;\n            releaseMedia3Player();\n            firstFrameRendered = false;\n            initializePlayer();\n            return;\n        }\n\n        if (!vlcAttempted) {\n            recoveryStep = 2;\n            if (isLive() && alternateLiveAttempted) restoreCanonicalSource();\n            else if ("direct".equals(sourceVariant)) restoreCanonicalSource();\n            openVlc(reason);\n            return;\n        }\n        showPlaybackFailure(reason);\n    }\n\n'''
player = player[:start] + new_recovery + player[end:]

# Reset bounded attempts when changing a channel or manually retrying.
reset_anchor = '''        recoveryStep = preferredRecoveryStep();\n        vlcAttempted = false;\n'''
reset_new = '''        recoveryStep = preferredRecoveryStep();\n        vlcAttempted = false;\n        alternateLiveAttempted = false;\n        containerSniffAttempted = false;\n'''
player = player.replace(reset_anchor, reset_new)

manual_anchor = '''        recoveryStep = preferredRecoveryStep();\n        vlcAttempted = false;\n        sourceVariant = "canonical";\n'''
manual_new = '''        recoveryStep = preferredRecoveryStep();\n        vlcAttempted = false;\n        alternateLiveAttempted = false;\n        containerSniffAttempted = false;\n        PlaybackRouteMemory.forgetItem(this, kind, id);\n        sourceVariant = "canonical";\n'''
player = rep(player, manual_anchor, manual_new)

# Surface DNS failures from nested causes immediately.
old_reason = '''            if (cause instanceof HttpDataSource.InvalidResponseCodeException) {\n                int status = ((HttpDataSource.InvalidResponseCodeException) cause).responseCode;\n                return "HTTP " + status;\n            }\n            cause = cause.getCause();\n'''
new_reason = '''            if (cause instanceof HttpDataSource.InvalidResponseCodeException) {\n                int status = ((HttpDataSource.InvalidResponseCodeException) cause).responseCode;\n                return "HTTP " + status;\n            }\n            String message = cause.getMessage();\n            if (message != null) {\n                String upper = message.toUpperCase(Locale.US);\n                if (upper.contains("EAI_NODATA") || upper.contains("UNKNOWNHOST")\n                        || upper.contains("NO ADDRESS ASSOCIATED")\n                        || upper.contains("UNABLE TO RESOLVE HOST")) return "DNS EAI_NODATA";\n            }\n            cause = cause.getCause();\n'''
player = rep(player, old_reason, new_reason)
write(PLAYER, player)

policy = read(POLICY)
old_net = '''        return value.contains("HTTP") || value.contains("IO_") || value.contains("NETWORK")\n                || value.contains("CONNECTION") || value.contains("TIMEOUT")\n                || value.contains("BAD_HTTP_STATUS");\n'''
new_net = '''        return value.contains("HTTP") || value.contains("IO_") || value.contains("NETWORK")\n                || value.contains("CONNECTION") || value.contains("TIMEOUT")\n                || value.contains("BAD_HTTP_STATUS") || value.contains("EAI_NODATA")\n                || value.contains("DNS") || value.contains("UNKNOWNHOST")\n                || value.contains("NO ADDRESS ASSOCIATED") || value.contains("UNABLE TO RESOLVE HOST");\n'''
policy = rep(policy, old_net, new_net)
write(POLICY, policy)

# Full preload must also warm what the UI actually needs before it says 100%.
imp = read(IMPORTER)
imp = rep(imp,
'''            emit(100, "البيانات جاهزة", "تم فتح النسخة المحفوظة على الجهاز بدون إعادة تحميل");\n''',
'''            CatalogUiCache.warm(database);\n            emit(100, "البيانات جاهزة", "تم فتح النسخة المحفوظة على الجهاز بدون إعادة تحميل");\n''')
imp = rep(imp,
'''            emit(100, "جاهز", "Live " + live + " • Movies " + movies + " • Series " + series);\n''',
'''            CatalogUiCache.warm(database);\n            emit(100, "جاهز", "Live " + live + " • Movies " + movies + " • Series " + series);\n''')
write(IMPORTER, imp)

# Cheap synchronous home counts/categories use warmed memory. Smaller first pages
# reduce RecyclerView construction cost while preserving lazy loading.
seven = read(SEVEN)
seven = seven.replace('private static final int LIVE_PAGE = 140;', 'private static final int LIVE_PAGE = 72;')
seven = seven.replace('private static final int POSTER_PAGE = 80;', 'private static final int POSTER_PAGE = 48;')
seven = seven.replace('database.count("live")', 'CatalogUiCache.count(database, "live")')
seven = seven.replace('database.categories("live")', 'CatalogUiCache.categories(database, "live")')
seven = seven.replace('database.count(type)', 'CatalogUiCache.count(database, type)')
seven = seven.replace('database.categories(type)', 'CatalogUiCache.categories(database, type)')
write(SEVEN, seven)

# Essential invariants: fail CI now rather than ship a half-applied compatibility engine.
final_player = read(PLAYER)
for required in ['alternateLiveAttempted', 'containerSniffAttempted',
                 'boolean preferCronet = recoveryStep == 1',
                 'PlaybackRouteMemory.recordSuccess', 'DNS EAI_NODATA']:
    if required not in final_player:
        raise SystemExit('v334 essential patch missing: ' + required)
if 'EAI_NODATA' not in read(POLICY):
    raise SystemExit('v334 DNS classification missing')

print('v334 adaptive engine applied: per-stream Live + Cronet + TS/HLS + VOD sniff + VLC + DNS fast-fail + UI warm cache')
