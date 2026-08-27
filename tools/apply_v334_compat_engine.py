#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player"
PLAYER = JAVA / "PlayerActivity.java"
POLICY = JAVA / "PlaybackPolicy.java"
IMPORTER = JAVA / "PackageImporter.java"
SEVEN = JAVA / "SevenMaxActivity.java"


def read(path):
    return path.read_text(encoding="utf-8")


def write(path, text):
    path.write_text(text, encoding="utf-8")


def replace_once(path, old, new, label):
    text = read(path)
    if old not in text:
        raise SystemExit(f"v334 patch mismatch: {label}")
    write(path, text.replace(old, new, 1))

# -----------------------------------------------------------------------------
# 1) Preserve the complete v331 playback chain:
# legacy7max -> Cronet -> compat -> direct/no-extension -> TS/HLS -> LibVLC.
# Only change learning scope so one odd live channel never poisons its provider.
# -----------------------------------------------------------------------------
player = read(PLAYER)
old_pref = '''        transportMode = PlaybackProfileManager.preferredMode(this, kind, extension);\n        recoveryStep = preferredRecoveryStep();'''
new_pref = '''        transportMode = PlaybackRouteMemory.preferredMode(this, kind, id,\n                PlaybackProfileManager.preferredMode(this, kind, extension));\n        recoveryStep = preferredRecoveryStep();'''
count = player.count(old_pref)
if count < 1:
    raise SystemExit("v334 patch mismatch: source route selection")
player = player.replace(old_pref, new_pref)
write(PLAYER, player)

replace_once(PLAYER,
'''    private String configuredExtension(String candidate) {\n        if (!isLiveKind(kind)) return candidate;\n        String mode = playerSetting(SettingsActivity.KEY_STREAM, "auto");\n        if ("ts".equals(mode)) return "ts";\n        if ("hls".equals(mode)) return "m3u8";\n        return PlaybackProfileManager.preferredLiveExtension(this, candidate);\n    }\n''',
'''    private String configuredExtension(String candidate) {\n        if (!isLiveKind(kind)) return candidate;\n        String mode = playerSetting(SettingsActivity.KEY_STREAM, "auto");\n        if ("ts".equals(mode)) return "ts";\n        if ("hls".equals(mode)) return "m3u8";\n        // Do not force one TS/HLS choice on every channel in a mixed provider.\n        return PlaybackRouteMemory.preferredLiveExtension(this, id, candidate);\n    }\n''', "per-channel live extension")

replace_once(PLAYER,
'''    private void rememberSuccessfulTransport() {\n        PlaybackProfileManager.recordSuccess(this, kind, extension, activeTransportName());\n    }\n''',
'''    private void rememberSuccessfulTransport() {\n        PlaybackRouteMemory.recordSuccess(this, kind, id, extension, activeTransportName());\n        // VOD/Series providers are usually homogeneous enough to benefit from a\n        // provider fallback. Live stays item-scoped because channels can differ.\n        if (!isLive()) {\n            PlaybackProfileManager.recordSuccess(this, kind, extension, activeTransportName());\n        }\n    }\n''', "per-item success")

replace_once(PLAYER,
'''        PlaybackProfileManager.recordFailure(this, kind, extension, transportMode);\n\n        if (PlaybackProfileManager.MODE_LEGACY.equals(transportMode)) {\n''',
'''        if (!isLive()) {\n            PlaybackProfileManager.recordFailure(this, kind, extension, transportMode);\n        }\n\n        if (PlaybackProfileManager.MODE_LEGACY.equals(transportMode)) {\n''', "live failure isolation")

replace_once(PLAYER,
'''        Log.i(TAG, "compat-first-frame ext=" + extension + " ms=" + firstFrameMs);\n        PlaybackProfileManager.recordSuccess(this, kind, extension, PlaybackProfileManager.MODE_VLC);\n    }\n''',
'''        Log.i(TAG, "compat-first-frame ext=" + extension + " ms=" + firstFrameMs);\n        PlaybackRouteMemory.recordSuccess(this, kind, id, extension, PlaybackProfileManager.MODE_VLC);\n        if (!isLive()) {\n            PlaybackProfileManager.recordSuccess(this, kind, extension, PlaybackProfileManager.MODE_VLC);\n        }\n    }\n''', "per-item VLC success")

# Surface DNS details from the nested Media3 cause so the existing recovery
# state machine can fail fast instead of waiting for every startup watchdog.
replace_once(PLAYER,
'''    private static String playbackErrorReason(PlaybackException error) {\n        if (error == null) return "Media3 error";\n        Throwable cause = error;\n        while (cause != null) {\n            if (cause instanceof HttpDataSource.InvalidResponseCodeException) {\n                int status = ((HttpDataSource.InvalidResponseCodeException) cause).responseCode;\n                return "HTTP " + status;\n            }\n            cause = cause.getCause();\n        }\n        return error.getErrorCodeName();\n    }\n''',
'''    private static String playbackErrorReason(PlaybackException error) {\n        if (error == null) return "Media3 error";\n        Throwable cause = error;\n        while (cause != null) {\n            if (cause instanceof HttpDataSource.InvalidResponseCodeException) {\n                int status = ((HttpDataSource.InvalidResponseCodeException) cause).responseCode;\n                return "HTTP " + status;\n            }\n            String message = cause.getMessage();\n            if (message != null) {\n                String upper = message.toUpperCase(Locale.US);\n                if (upper.contains("EAI_NODATA") || upper.contains("UNKNOWNHOST")\n                        || upper.contains("NO ADDRESS ASSOCIATED")\n                        || upper.contains("UNABLE TO RESOLVE HOST")) {\n                    return "DNS EAI_NODATA";\n                }\n            }\n            cause = cause.getCause();\n        }\n        return error.getErrorCodeName();\n    }\n''', "surface DNS failure")

replace_once(POLICY,
'''        return value.contains("HTTP") || value.contains("IO_") || value.contains("NETWORK")\n                || value.contains("CONNECTION") || value.contains("TIMEOUT")\n                || value.contains("BAD_HTTP_STATUS");\n''',
'''        return value.contains("HTTP") || value.contains("IO_") || value.contains("NETWORK")\n                || value.contains("CONNECTION") || value.contains("TIMEOUT")\n                || value.contains("BAD_HTTP_STATUS") || value.contains("EAI_NODATA")\n                || value.contains("DNS") || value.contains("UNKNOWNHOST")\n                || value.contains("UNKNOWN_HOST") || value.contains("NO ADDRESS ASSOCIATED")\n                || value.contains("UNABLE TO RESOLVE HOST");\n''', "DNS fast failure")

# -----------------------------------------------------------------------------
# 2) Make Full Preload mean UI-ready. The cache class already exists; warm it
# before the importer reports 100%, including the fast cached-session path.
# -----------------------------------------------------------------------------
replace_once(IMPORTER,
'''            String profile = database.metadata("playback_profile", "Media3 مباشر");\n            emit(100, "البيانات جاهزة", "تم فتح النسخة المحفوظة على الجهاز بدون إعادة تحميل");\n''',
'''            String profile = database.metadata("playback_profile", "Media3 مباشر");\n            CatalogUiCache.warm(database);\n            emit(100, "البيانات جاهزة", "تم فتح النسخة المحفوظة على الجهاز بدون إعادة تحميل");\n''', "warm cached UI")

replace_once(IMPORTER,
'''            int live = database.count("live");\n            int movies = database.count("movies");\n            int series = database.count("series");\n            emit(100, "جاهز", "Live " + live + " • Movies " + movies + " • Series " + series);\n''',
'''            int live = database.count("live");\n            int movies = database.count("movies");\n            int series = database.count("series");\n            CatalogUiCache.warm(database);\n            emit(100, "جاهز", "Live " + live + " • Movies " + movies + " • Series " + series);\n''', "warm imported UI")

# -----------------------------------------------------------------------------
# 3) Use the warm first-screen cache immediately. SQLite pagination remains the
# source of truth for category/search/second pages, all on the background worker.
# -----------------------------------------------------------------------------
seven = read(SEVEN)
seven = seven.replace('database.count("live")', 'CatalogUiCache.count(database, "live")')
seven = seven.replace('database.categories("live")', 'CatalogUiCache.categories(database, "live")')
seven = seven.replace('database.count(type)', 'CatalogUiCache.count(database, type)')
seven = seven.replace('database.categories(type)', 'CatalogUiCache.categories(database, type)')
write(SEVEN, seven)

replace_once(SEVEN,
'''            rows.clear();\n            exhausted = false;\n            loading = false;\n            generation++;\n            notifyDataSetChanged();\n            loadMore();\n        }\n\n        void loadMore() {\n''',
'''            rows.clear();\n            exhausted = false;\n            loading = false;\n            generation++;\n            if (this.category.isEmpty() && this.query.isEmpty()) {\n                List<BlofyModels.Media> cached = CatalogUiCache.firstPage(database, "live", LIVE_PAGE);\n                if (!cached.isEmpty()) {\n                    rows.addAll(cached);\n                    notifyDataSetChanged();\n                    if (firstPageLoaded != null) {\n                        Runnable callback = firstPageLoaded;\n                        firstPageLoaded = null;\n                        callback.run();\n                    }\n                    if (cached.size() < LIVE_PAGE) exhausted = true;\n                    return;\n                }\n            }\n            notifyDataSetChanged();\n            loadMore();\n        }\n\n        void loadMore() {\n''', "instant live first page")

# PosterAdapter has a structurally identical reload block later in the file.
poster_old = '''            rows.clear();\n            exhausted = false;\n            loading = false;\n            generation++;\n            notifyDataSetChanged();\n            loadMore();\n        }\n\n        void loadMore() {\n            if (!isCurrentScreen(ownerGeneration) || exhausted || loading) return;\n            loading = true;\n            int offset = rows.size();\n            int token = generation;\n            String selectedCategory = category;\n            String selectedQuery = query;\n            boolean submitted = submitCatalog(() -> {\n'''
poster_new = '''            rows.clear();\n            exhausted = false;\n            loading = false;\n            generation++;\n            if (!type.isEmpty() && !favorites && !history\n                    && this.category.isEmpty() && this.query.isEmpty()) {\n                List<BlofyModels.Media> cached = CatalogUiCache.firstPage(database, type, POSTER_PAGE);\n                if (!cached.isEmpty()) {\n                    rows.addAll(cached);\n                    notifyDataSetChanged();\n                    if (firstPageLoaded != null) {\n                        Runnable callback = firstPageLoaded;\n                        firstPageLoaded = null;\n                        callback.run();\n                    }\n                    if (cached.size() < POSTER_PAGE) exhausted = true;\n                    return;\n                }\n            }\n            notifyDataSetChanged();\n            loadMore();\n        }\n\n        void loadMore() {\n            if (!isCurrentScreen(ownerGeneration) || exhausted || loading) return;\n            loading = true;\n            int offset = rows.size();\n            int token = generation;\n            String selectedCategory = category;\n            String selectedQuery = query;\n            boolean submitted = submitCatalog(() -> {\n'''
text = read(SEVEN)
# There are two reload blocks (Live + Poster); after the Live replacement above,
# this signature now uniquely identifies PosterAdapter.
if poster_old not in text:
    raise SystemExit("v334 patch mismatch: instant poster first page")
write(SEVEN, text.replace(poster_old, poster_new, 1))

print("v334 applied: per-stream playback learning + DNS fast failure + UI-ready preload cache")
