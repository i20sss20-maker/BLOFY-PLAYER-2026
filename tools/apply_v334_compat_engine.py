#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player"
PLAYER = JAVA / "PlayerActivity.java"
POLICY = JAVA / "PlaybackPolicy.java"
IMPORTER = JAVA / "PackageImporter.java"
SEVEN = JAVA / "SevenMaxActivity.java"


def read(path): return path.read_text(encoding="utf-8")
def write(path, text): path.write_text(text, encoding="utf-8")
def must_replace(text, old, new, label):
    if old not in text:
        raise SystemExit(f"v334 patch mismatch: {label}")
    return text.replace(old, new, 1)

# 1) Per-item adaptive playback memory. Live is stream-scoped; VOD/Series may also
# keep the provider-level fallback because those catalogs are usually homogeneous.
player = read(PLAYER)
needle = 'transportMode = PlaybackProfileManager.preferredMode(this, kind, extension);'
if needle not in player:
    raise SystemExit('v334 patch mismatch: preferred transport assignment')
player = player.replace(needle,
    'transportMode = PlaybackRouteMemory.preferredMode(this, kind, id,\n'
    '                PlaybackProfileManager.preferredMode(this, kind, extension));')

player = must_replace(player,
'''    private String configuredExtension(String candidate) {\n        if (!isLiveKind(kind)) return candidate;\n        String mode = playerSetting(SettingsActivity.KEY_STREAM, "auto");\n        if ("ts".equals(mode)) return "ts";\n        if ("hls".equals(mode)) return "m3u8";\n        return PlaybackProfileManager.preferredLiveExtension(this, candidate);\n    }\n''',
'''    private String configuredExtension(String candidate) {\n        if (!isLiveKind(kind)) return candidate;\n        String mode = playerSetting(SettingsActivity.KEY_STREAM, "auto");\n        if ("ts".equals(mode)) return "ts";\n        if ("hls".equals(mode)) return "m3u8";\n        return PlaybackRouteMemory.preferredLiveExtension(this, id, candidate);\n    }\n''', 'per-channel live extension')

player = must_replace(player,
'''    private void rememberSuccessfulTransport() {\n        PlaybackProfileManager.recordSuccess(this, kind, extension, activeTransportName());\n    }\n''',
'''    private void rememberSuccessfulTransport() {\n        PlaybackRouteMemory.recordSuccess(this, kind, id, extension, activeTransportName());\n        if (!isLive()) PlaybackProfileManager.recordSuccess(this, kind, extension, activeTransportName());\n    }\n''', 'per-item success')

player = must_replace(player,
'''        PlaybackProfileManager.recordFailure(this, kind, extension, transportMode);\n\n        if (PlaybackProfileManager.MODE_LEGACY.equals(transportMode)) {\n''',
'''        if (!isLive()) PlaybackProfileManager.recordFailure(this, kind, extension, transportMode);\n\n        if (PlaybackProfileManager.MODE_LEGACY.equals(transportMode)) {\n''', 'live failure isolation')

player = must_replace(player,
'''        Log.i(TAG, "compat-first-frame ext=" + extension + " ms=" + firstFrameMs);\n        PlaybackProfileManager.recordSuccess(this, kind, extension, PlaybackProfileManager.MODE_VLC);\n''',
'''        Log.i(TAG, "compat-first-frame ext=" + extension + " ms=" + firstFrameMs);\n        PlaybackRouteMemory.recordSuccess(this, kind, id, extension, PlaybackProfileManager.MODE_VLC);\n        if (!isLive()) PlaybackProfileManager.recordSuccess(this, kind, extension, PlaybackProfileManager.MODE_VLC);\n''', 'per-item VLC success')

old_reason = '''            if (cause instanceof HttpDataSource.InvalidResponseCodeException) {\n                int status = ((HttpDataSource.InvalidResponseCodeException) cause).responseCode;\n                return "HTTP " + status;\n            }\n            cause = cause.getCause();\n'''
new_reason = '''            if (cause instanceof HttpDataSource.InvalidResponseCodeException) {\n                int status = ((HttpDataSource.InvalidResponseCodeException) cause).responseCode;\n                return "HTTP " + status;\n            }\n            String message = cause.getMessage();\n            if (message != null) {\n                String upper = message.toUpperCase(Locale.US);\n                if (upper.contains("EAI_NODATA") || upper.contains("UNKNOWNHOST")\n                        || upper.contains("NO ADDRESS ASSOCIATED")\n                        || upper.contains("UNABLE TO RESOLVE HOST")) return "DNS EAI_NODATA";\n            }\n            cause = cause.getCause();\n'''
player = must_replace(player, old_reason, new_reason, 'surface DNS failure')
write(PLAYER, player)

policy = read(POLICY)
policy = must_replace(policy,
'''        return value.contains("HTTP") || value.contains("IO_") || value.contains("NETWORK")\n                || value.contains("CONNECTION") || value.contains("TIMEOUT")\n                || value.contains("BAD_HTTP_STATUS");\n''',
'''        return value.contains("HTTP") || value.contains("IO_") || value.contains("NETWORK")\n                || value.contains("CONNECTION") || value.contains("TIMEOUT")\n                || value.contains("BAD_HTTP_STATUS") || value.contains("EAI_NODATA")\n                || value.contains("DNS") || value.contains("UNKNOWNHOST")\n                || value.contains("NO ADDRESS ASSOCIATED") || value.contains("UNABLE TO RESOLVE HOST");\n''', 'DNS fast failure')
write(POLICY, policy)

# 2) Full preload warms counts/categories/first screen before progress reports 100%.
imp = read(IMPORTER)
imp = must_replace(imp,
'''            String profile = database.metadata("playback_profile", "Media3 مباشر");\n            emit(100, "البيانات جاهزة", "تم فتح النسخة المحفوظة على الجهاز بدون إعادة تحميل");\n''',
'''            String profile = database.metadata("playback_profile", "Media3 مباشر");\n            CatalogUiCache.warm(database);\n            emit(100, "البيانات جاهزة", "تم فتح النسخة المحفوظة على الجهاز بدون إعادة تحميل");\n''', 'warm cached UI')
imp = must_replace(imp,
'''            int live = database.count("live");\n            int movies = database.count("movies");\n            int series = database.count("series");\n            emit(100, "جاهز", "Live " + live + " • Movies " + movies + " • Series " + series);\n''',
'''            int live = database.count("live");\n            int movies = database.count("movies");\n            int series = database.count("series");\n            CatalogUiCache.warm(database);\n            emit(100, "جاهز", "Live " + live + " • Movies " + movies + " • Series " + series);\n''', 'warm imported UI')
write(IMPORTER, imp)

# 3) SevenMax uses warm counts/categories immediately and warm first page when unfiltered.
seven = read(SEVEN)
seven = seven.replace('database.count("live")', 'CatalogUiCache.count(database, "live")')
seven = seven.replace('database.categories("live")', 'CatalogUiCache.categories(database, "live")')
seven = seven.replace('database.count(type)', 'CatalogUiCache.count(database, type)')
seven = seven.replace('database.categories(type)', 'CatalogUiCache.categories(database, type)')

live_reload = '''            rows.clear();\n            exhausted = false;\n            loading = false;\n            generation++;\n            notifyDataSetChanged();\n            loadMore();\n        }\n\n        void loadMore() {\n            if (!isCurrentScreen(ownerGeneration) || exhausted || loading) return;\n            loading = true;\n            int offset = rows.size();\n            int token = generation;\n            String selectedCategory = category;\n            String selectedQuery = query;\n'''
live_cached = '''            rows.clear();\n            exhausted = false;\n            loading = false;\n            generation++;\n            if (this.category.isEmpty() && this.query.isEmpty()) {\n                List<BlofyModels.Media> cached = CatalogUiCache.firstPage(database, "live", LIVE_PAGE);\n                if (!cached.isEmpty()) {\n                    rows.addAll(cached);\n                    notifyDataSetChanged();\n                    if (cached.size() < LIVE_PAGE) exhausted = true;\n                    if (firstPageLoaded != null) { Runnable cb = firstPageLoaded; firstPageLoaded = null; cb.run(); }\n                    return;\n                }\n            }\n            notifyDataSetChanged();\n            loadMore();\n        }\n\n        void loadMore() {\n            if (!isCurrentScreen(ownerGeneration) || exhausted || loading) return;\n            loading = true;\n            int offset = rows.size();\n            int token = generation;\n            String selectedCategory = category;\n            String selectedQuery = query;\n'''
seven = must_replace(seven, live_reload, live_cached, 'instant live first page')

# PosterAdapter has the same reload prologue. Replace the next occurrence only.
poster_reload = '''            rows.clear();\n            exhausted = false;\n            loading = false;\n            generation++;\n            notifyDataSetChanged();\n            loadMore();\n        }\n\n        void loadMore() {\n            if (!isCurrentScreen(ownerGeneration) || exhausted || loading) return;\n            loading = true;\n            int offset = rows.size();\n            int token = generation;\n            String selectedCategory = category;\n            String selectedQuery = query;\n'''
poster_cached = '''            rows.clear();\n            exhausted = false;\n            loading = false;\n            generation++;\n            if (!type.isEmpty() && !favorites && !history\n                    && this.category.isEmpty() && this.query.isEmpty()) {\n                List<BlofyModels.Media> cached = CatalogUiCache.firstPage(database, type, POSTER_PAGE);\n                if (!cached.isEmpty()) {\n                    rows.addAll(cached);\n                    notifyDataSetChanged();\n                    if (cached.size() < POSTER_PAGE) exhausted = true;\n                    if (firstPageLoaded != null) { Runnable cb = firstPageLoaded; firstPageLoaded = null; cb.run(); }\n                    return;\n                }\n            }\n            notifyDataSetChanged();\n            loadMore();\n        }\n\n        void loadMore() {\n            if (!isCurrentScreen(ownerGeneration) || exhausted || loading) return;\n            loading = true;\n            int offset = rows.size();\n            int token = generation;\n            String selectedCategory = category;\n            String selectedQuery = query;\n'''
seven = must_replace(seven, poster_reload, poster_cached, 'instant poster first page')
write(SEVEN, seven)

print('v334 applied: per-stream Live routes + provider VOD routes + DNS fast failure + instant UI cache')
