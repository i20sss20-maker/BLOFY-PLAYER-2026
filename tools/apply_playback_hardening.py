from pathlib import Path

ROOT = Path('BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player')


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise SystemExit(f'{label}: pattern not found')
    return text.replace(old, new, 1)

# ---------------------------------------------------------------------------
# 1) Live playback stability: do not reset recovery to step 0 just because the
# stream briefly reached READY. If HD/4K becomes unstable a few seconds later,
# continue to the next transport/format instead of looping the same path.
# ---------------------------------------------------------------------------
player_path = ROOT / 'PlayerActivity.java'
player = player_path.read_text(encoding='utf-8')
player = player.replace(
'''            recoveryStep = 0;\n            titleView.postDelayed(() -> titleView.setVisibility(View.GONE), 2500);''',
'''            // Keep the successful recovery step for this playback session.\n            // A stream that reaches READY for only a few seconds must not reset\n            // the fallback chain and loop on the same failing transport.\n            titleView.postDelayed(() -> titleView.setVisibility(View.GONE), 2500);''',
1)
player_path.write_text(player, encoding='utf-8')

# ---------------------------------------------------------------------------
# 2) Transport order and provider compatibility.
# Default HTTP is first because it provides predictable Range/redirect support
# and a stable User-Agent. Cronet becomes the second attempt, not the first.
# ---------------------------------------------------------------------------
policy_path = ROOT / 'PlaybackPolicy.java'
policy = policy_path.read_text(encoding='utf-8')
policy = replace_once(
    policy,
'''    /** step 0/2 use Cronet; step 1/3 use platform HTTP. */\n    static boolean useCronet(int recoveryStep) {\n        return recoveryStep == 0 || recoveryStep == 2;\n    }''',
'''    /** step 0/2 use platform HTTP; step 1/3 use Cronet fallback. */\n    static boolean useCronet(int recoveryStep) {\n        return recoveryStep == 1 || recoveryStep == 3;\n    }''',
    'transport order')
policy = policy.replace('''    /** One same-format fallback only: Cronet -> platform HTTP. */''',
                        '''    /** One same-format fallback only: platform HTTP -> Cronet. */''', 1)
policy_path.write_text(policy, encoding='utf-8')

transport_path = ROOT / 'PlaybackTransportFactory.java'
transport = transport_path.read_text(encoding='utf-8')
transport = replace_once(
    transport,
'''        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()\n                .setAllowCrossProtocolRedirects(true);''',
'''        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()\n                .setAllowCrossProtocolRedirects(true)\n                .setConnectTimeoutMs(5_000)\n                .setReadTimeoutMs(20_000)\n                .setUserAgent("BLOFY-PLAYER/2026 Android Media3");''',
    'provider http headers')
transport_path.write_text(transport, encoding='utf-8')

# ---------------------------------------------------------------------------
# 3) Catalog sync speed + real page resume.
# Reduce the artificial legacy delay and remember the last successful page for
# each type so a transient failure resumes instead of requesting old pages again.
# ---------------------------------------------------------------------------
importer_path = ROOT / 'PackageImporter.java'
imp = importer_path.read_text(encoding='utf-8')
imp = imp.replace('private static final int REQUESTED_PAGE_SIZE = 2000;',
                  'private static final int REQUESTED_PAGE_SIZE = 5000;', 1)
imp = imp.replace('private static final long LEGACY_MIN_REQUEST_GAP_MS = 650L;',
                  'private static final long LEGACY_MIN_REQUEST_GAP_MS = 80L;', 1)
imp = replace_once(
    imp,
'''        if (!"partial".equals(previousSync) && !"in_progress".equals(previousSync)) {\n            database.beginFreshImport();\n        }\n        database.putMetadata("sync_state", "in_progress");''',
'''        if (!"partial".equals(previousSync) && !"in_progress".equals(previousSync)) {\n            database.beginFreshImport();\n            database.putMetadata("sync_live_page", "0");\n            database.putMetadata("sync_movies_page", "0");\n            database.putMetadata("sync_series_page", "0");\n        }\n        database.putMetadata("sync_state", "in_progress");''',
    'fresh sync checkpoints')
imp = replace_once(
    imp,
'''        int pages = Math.max(1, (int) Math.ceil(total / (double) pageSize));\n        save(BlofyModels.Media.list(first, type));\n        boolean legacyRateLimit = pageSize < 1000;\n        for (int page = 2; page <= pages; page++) {''',
'''        int pages = Math.max(1, (int) Math.ceil(total / (double) pageSize));\n        save(BlofyModels.Media.list(first, type));\n        int resumePage = Math.max(1, intMetadata("sync_" + type + "_page", 0));\n        database.putMetadata("sync_" + type + "_page", String.valueOf(Math.max(1, resumePage)));\n        boolean legacyRateLimit = pageSize < 1000;\n        for (int page = Math.max(2, resumePage + 1); page <= pages; page++) {''',
    'resume page loop')
imp = replace_once(
    imp,
'''            JSONObject response = getWithRetry("/api/catalog?type=" + BlofyApi.encode(type)\n                    + "&page=" + page + "&page_size=" + REQUESTED_PAGE_SIZE, true);\n            save(BlofyModels.Media.list(response, type));\n        }''',
'''            JSONObject response = getWithRetry("/api/catalog?type=" + BlofyApi.encode(type)\n                    + "&page=" + page + "&page_size=" + REQUESTED_PAGE_SIZE, true);\n            save(BlofyModels.Media.list(response, type));\n            database.putMetadata("sync_" + type + "_page", String.valueOf(page));\n        }''',
    'checkpoint each page')
imp = replace_once(
    imp,
'''    private void emit(int percent, String title, String detail) {\n        listener.progress(Math.max(0, Math.min(100, percent)), title, detail);\n    }''',
'''    private int intMetadata(String key, int fallback) {\n        try { return Integer.parseInt(database.metadata(key, String.valueOf(fallback))); }\n        catch (Exception ignored) { return fallback; }\n    }\n\n    private void emit(int percent, String title, String detail) {\n        listener.progress(Math.max(0, Math.min(100, percent)), title, detail);\n    }''',
    'metadata parser')
importer_path.write_text(imp, encoding='utf-8')

print('BLOFY playback hardening applied: stable recovery, HTTP-first UA, fast resumable catalog sync')
