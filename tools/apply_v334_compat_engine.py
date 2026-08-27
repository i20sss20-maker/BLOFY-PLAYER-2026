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
def need_replace(s, old, new, label, all_occurrences=False):
    if old not in s:
        raise SystemExit(f"v334 patch mismatch: {label}")
    return s.replace(old, new) if all_occurrences else s.replace(old, new, 1)

# -----------------------------------------------------------------------------
# Playback: KEEP v331's proven transport ladder. Add per-stream learning and
# faster DNS classification only. Do not duplicate Cronet/TS-HLS/VLC logic.
# -----------------------------------------------------------------------------
player = read(PLAYER)

# All v331 profile selections become per-item first, provider-profile fallback.
old_assign = 'transportMode = PlaybackProfileManager.preferredMode(this, kind, extension);'
new_assign = '''transportMode = PlaybackRouteMemory.preferredMode(this, kind, id,
                PlaybackProfileManager.preferredMode(this, kind, extension));'''
if old_assign not in player:
    raise SystemExit('v334 patch mismatch: transport profile assignment')
player = player.replace(old_assign, new_assign)

# Explicit TS/HLS setting still wins. Auto learns extension per Live stream.
old_cfg = '''    private String configuredExtension(String candidate) {
        if (!isLiveKind(kind)) return candidate;
        String mode = playerSetting(SettingsActivity.KEY_STREAM, "auto");
        if ("ts".equals(mode)) return "ts";
        if ("hls".equals(mode)) return "m3u8";
        return PlaybackProfileManager.preferredLiveExtension(this, candidate);
    }
'''
old_cfg_plain = '''    private String configuredExtension(String candidate) {
        if (!isLiveKind(kind)) return candidate;
        String mode = playerSetting(SettingsActivity.KEY_STREAM, "auto");
        if ("ts".equals(mode)) return "ts";
        if ("hls".equals(mode)) return "m3u8";
        return candidate;
    }
'''
new_cfg = '''    private String configuredExtension(String candidate) {
        if (!isLiveKind(kind)) return candidate;
        String mode = playerSetting(SettingsActivity.KEY_STREAM, "auto");
        if ("ts".equals(mode)) return "ts";
        if ("hls".equals(mode)) return "m3u8";
        return PlaybackRouteMemory.preferredLiveExtension(this, id, candidate);
    }
'''
if old_cfg in player:
    player = player.replace(old_cfg, new_cfg, 1)
elif old_cfg_plain in player:
    player = player.replace(old_cfg_plain, new_cfg, 1)
else:
    raise SystemExit('v334 patch mismatch: configured live extension')

old_remember = '''    private void rememberSuccessfulTransport() {
        PlaybackProfileManager.recordSuccess(this, kind, extension, activeTransportName());
    }
'''
new_remember = '''    private void rememberSuccessfulTransport() {
        PlaybackRouteMemory.recordSuccess(this, kind, id, extension, activeTransportName());
        if (!isLive()) PlaybackProfileManager.recordSuccess(this, kind, extension, activeTransportName());
    }
'''
player = need_replace(player, old_remember, new_remember, 'remember per-stream success')

# A bad Live channel must not poison the provider-wide mode used by good channels.
old_failure = '''        PlaybackProfileManager.recordFailure(this, kind, extension, transportMode);

        if (PlaybackProfileManager.MODE_LEGACY.equals(transportMode)) {
'''
new_failure = '''        if (!isLive()) PlaybackProfileManager.recordFailure(this, kind, extension, transportMode);

        if (PlaybackProfileManager.MODE_LEGACY.equals(transportMode)) {
'''
player = need_replace(player, old_failure, new_failure, 'live failure isolation')

# LibVLC first real frame teaches this item too.
old_vlc = '''        Log.i(TAG, "compat-first-frame ext=" + extension + " ms=" + firstFrameMs);
        PlaybackProfileManager.recordSuccess(this, kind, extension, PlaybackProfileManager.MODE_VLC);
'''
new_vlc = '''        Log.i(TAG, "compat-first-frame ext=" + extension + " ms=" + firstFrameMs);
        PlaybackRouteMemory.recordSuccess(this, kind, id, extension, PlaybackProfileManager.MODE_VLC);
        if (!isLive()) PlaybackProfileManager.recordSuccess(this, kind, extension, PlaybackProfileManager.MODE_VLC);
'''
player = need_replace(player, old_vlc, new_vlc, 'VLC per-stream success')

# Expose nested hostname failures so v331 transport ladder advances immediately.
old_reason = '''            if (cause instanceof HttpDataSource.InvalidResponseCodeException) {
                int status = ((HttpDataSource.InvalidResponseCodeException) cause).responseCode;
                return "HTTP " + status;
            }
            cause = cause.getCause();
'''
new_reason = '''            if (cause instanceof HttpDataSource.InvalidResponseCodeException) {
                int status = ((HttpDataSource.InvalidResponseCodeException) cause).responseCode;
                return "HTTP " + status;
            }
            String message = cause.getMessage();
            if (message != null) {
                String upper = message.toUpperCase(Locale.US);
                if (upper.contains("EAI_NODATA") || upper.contains("UNKNOWNHOST")
                        || upper.contains("NO ADDRESS ASSOCIATED")
                        || upper.contains("UNABLE TO RESOLVE HOST")) return "DNS EAI_NODATA";
            }
            cause = cause.getCause();
'''
player = need_replace(player, old_reason, new_reason, 'surface DNS failure')
write(PLAYER, player)

policy = read(POLICY)
old_net = '''        return value.contains("HTTP") || value.contains("IO_") || value.contains("NETWORK")
                || value.contains("CONNECTION") || value.contains("TIMEOUT")
                || value.contains("BAD_HTTP_STATUS");
'''
new_net = '''        return value.contains("HTTP") || value.contains("IO_") || value.contains("NETWORK")
                || value.contains("CONNECTION") || value.contains("TIMEOUT")
                || value.contains("BAD_HTTP_STATUS") || value.contains("EAI_NODATA")
                || value.contains("DNS") || value.contains("UNKNOWNHOST")
                || value.contains("NO ADDRESS ASSOCIATED") || value.contains("UNABLE TO RESOLVE HOST");
'''
policy = need_replace(policy, old_net, new_net, 'DNS fast failure')
write(POLICY, policy)

# -----------------------------------------------------------------------------
# Full preload/UI: 100% means counts, categories and first unfiltered screens are
# already in memory. Catalog screens still lazy-load subsequent pages.
# -----------------------------------------------------------------------------
imp = read(IMPORTER)
old_cached_done = '''            emit(100, "البيانات جاهزة", "تم فتح النسخة المحفوظة على الجهاز بدون إعادة تحميل");
'''
new_cached_done = '''            CatalogUiCache.warm(database);
            emit(100, "البيانات جاهزة", "تم فتح النسخة المحفوظة على الجهاز بدون إعادة تحميل");
'''
if old_cached_done in imp:
    imp = imp.replace(old_cached_done, new_cached_done, 1)

old_import_done = '''            emit(100, "جاهز", "Live " + live + " • Movies " + movies + " • Series " + series);
'''
new_import_done = '''            CatalogUiCache.warm(database);
            emit(100, "جاهز", "Live " + live + " • Movies " + movies + " • Series " + series);
'''
if old_import_done in imp:
    imp = imp.replace(old_import_done, new_import_done, 1)
if 'CatalogUiCache.warm(database)' not in imp:
    raise SystemExit('v334 patch mismatch: importer warm cache')
write(IMPORTER, imp)

seven = read(SEVEN)
# Lighter first render; remaining rows still paginate automatically.
seven = seven.replace('private static final int LIVE_PAGE = 140;', 'private static final int LIVE_PAGE = 72;', 1)
seven = seven.replace('private static final int POSTER_PAGE = 80;', 'private static final int POSTER_PAGE = 48;', 1)
seven = seven.replace('database.count("live")', 'CatalogUiCache.count(database, "live")')
seven = seven.replace('database.categories("live")', 'CatalogUiCache.categories(database, "live")')
seven = seven.replace('database.count(type)', 'CatalogUiCache.count(database, type)')
seven = seven.replace('database.categories(type)', 'CatalogUiCache.categories(database, type)')

live_reload = '''            rows.clear();
            exhausted = false;
            loading = false;
            generation++;
            notifyDataSetChanged();
            loadMore();
        }

        void loadMore() {
'''
live_cached = '''            rows.clear();
            exhausted = false;
            loading = false;
            generation++;
            if (this.category.isEmpty() && this.query.isEmpty()) {
                List<BlofyModels.Media> cached = CatalogUiCache.firstPage(database, "live", LIVE_PAGE);
                if (!cached.isEmpty()) {
                    rows.addAll(cached);
                    exhausted = cached.size() < LIVE_PAGE;
                    notifyDataSetChanged();
                    if (firstPageLoaded != null) {
                        Runnable callback = firstPageLoaded;
                        firstPageLoaded = null;
                        callback.run();
                    }
                    return;
                }
            }
            notifyDataSetChanged();
            loadMore();
        }

        void loadMore() {
'''
# First occurrence belongs to LiveListAdapter.
seven = need_replace(seven, live_reload, live_cached, 'instant Live first page')

poster_reload = '''            rows.clear();
            exhausted = false;
            loading = false;
            generation++;
            notifyDataSetChanged();
            loadMore();
        }

        void loadMore() {
'''
poster_cached = '''            rows.clear();
            exhausted = false;
            loading = false;
            generation++;
            if (!type.isEmpty() && !favorites && !history
                    && this.category.isEmpty() && this.query.isEmpty()) {
                List<BlofyModels.Media> cached = CatalogUiCache.firstPage(database, type, POSTER_PAGE);
                if (!cached.isEmpty()) {
                    rows.addAll(cached);
                    exhausted = cached.size() < POSTER_PAGE;
                    notifyDataSetChanged();
                    if (firstPageLoaded != null) {
                        Runnable callback = firstPageLoaded;
                        firstPageLoaded = null;
                        callback.run();
                    }
                    return;
                }
            }
            notifyDataSetChanged();
            loadMore();
        }

        void loadMore() {
'''
# HomeRailAdapter has an identical prologue before PosterAdapter. Locate PosterAdapter
# first, then patch only inside its suffix to avoid changing latest/history semantics.
poster_class = seven.find('    private final class PosterAdapter')
if poster_class < 0:
    raise SystemExit('v334 patch mismatch: PosterAdapter')
prefix, suffix = seven[:poster_class], seven[poster_class:]
suffix = need_replace(suffix, poster_reload, poster_cached, 'instant Movies/Series first page')
seven = prefix + suffix
write(SEVEN, seven)

# Build-time invariants.
final_player = read(PLAYER)
for token in ['PlaybackRouteMemory.preferredMode', 'MODE_CRONET.equals(transportMode)',
              'liveAlternateTried', 'PlaybackRouteMemory.recordSuccess(this, kind, id',
              'DNS EAI_NODATA']:
    if token not in final_player:
        raise SystemExit('v334 invariant missing: ' + token)
if 'CatalogUiCache.firstPage' not in read(SEVEN):
    raise SystemExit('v334 invariant missing: UI first page cache')

print('v334 applied: v331 transport ladder preserved + per-stream Live learning + DNS fast-fail + instant catalog cache')
