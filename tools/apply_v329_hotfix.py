from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def patch(path: str, old: str, new: str, count: int = 1):
    target = ROOT / path
    text = target.read_text(encoding="utf-8")
    hits = text.count(old)
    if hits < count:
        raise SystemExit(f"v329 patch mismatch: {path}: expected >= {count}, found {hits}: {old[:80]!r}")
    text = text.replace(old, new, count)
    target.write_text(text, encoding="utf-8")


# 1) Resume must never cover the series/movie page or steal initial focus.
patch(
    "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player/DetailsActivity.java",
    '''        // Progress is local, so the choice can appear immediately without waiting\n        // for a remote metadata request to complete.\n        showResumePrompt(null);\n''',
    '''        // Resume remains available as a secondary action inside details.\n        // Never cover the page or steal initial TV focus on entry.\n''')
patch(
    "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player/DetailsActivity.java",
    "        if (!showResumePrompt(detail)) primary.requestFocus();\n",
    "        primary.requestFocus();\n")

# 2) Durable catalog cache: reuse unchanged playlists, but manual refresh and
# server/account edits must invalidate the cached source identity.
package_file = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player/PackageImporter.java"
package_text = package_file.read_text(encoding="utf-8")
if "forceNextRefresh" not in package_text:
    package_text = package_text.replace(
        "final class PackageImporter {\n",
        "final class PackageImporter {\n    private static volatile boolean forceNextRefresh;\n\n"
        "    static void forceNextRefresh() { forceNextRefresh = true; }\n\n")
    package_text = package_text.replace(
        "        String activeSource = database.metadata(\"active_source_id\", \"\");\n",
        "        boolean forceRefresh = forceNextRefresh;\n"
        "        forceNextRefresh = false;\n"
        "        String activeSource = database.metadata(\"active_source_id\", \"\");\n")
    package_text = package_text.replace(
        "        if (sourceIdentity.equals(activeSource) && (cachedLive + cachedMovies + cachedSeries) > 0\n",
        "        if (!forceRefresh && sourceIdentity.equals(activeSource) && (cachedLive + cachedMovies + cachedSeries) > 0\n")

old_identity = '''    private static String sourceIdentity(BlofyModels.Session session, String playlistId) {\n        if (playlistId != null && !playlistId.trim().isEmpty()\n                && !"current-session".equals(playlistId.trim())) {\n            return CatalogScope.forPlaylist(playlistId);\n        }\n        String username = session.account == null ? "" : session.account.optString("username",\n                session.account.optString("user", session.account.optString("id", "")));\n        String raw = session.kind + "|" + session.serverName + "|" + session.name + "|" + username;\n        return CatalogScope.forSession(raw);\n    }\n'''
new_identity = '''    private static String sourceIdentity(BlofyModels.Session session, String playlistId) {\n        String username = session.account == null ? "" : session.account.optString("username",\n                session.account.optString("user", session.account.optString("id", "")));\n        String sessionFingerprint = session.kind + "|" + session.serverName + "|"\n                + session.name + "|" + username;\n        if (playlistId != null && !playlistId.trim().isEmpty()\n                && !"current-session".equals(playlistId.trim())) {\n            // Same saved playlist opens instantly, but editing its server/account\n            // changes this identity and forces one fresh import automatically.\n            return CatalogScope.forPlaylist(playlistId.trim() + "|" + sessionFingerprint);\n        }\n        return CatalogScope.forSession(sessionFingerprint);\n    }\n'''
if old_identity in package_text:
    package_text = package_text.replace(old_identity, new_identity, 1)
package_file.write_text(package_text, encoding="utf-8")

main_file = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player/MainActivity.java"
main_text = main_file.read_text(encoding="utf-8")
main_text = main_text.replace(
    '''                    if (refreshCatalogRequested && session.present) {\n                        if (playlistStore.activeId().isEmpty()) playlistStore.setActive("current-session");\n                        importPackage();\n''',
    '''                    if (refreshCatalogRequested && session.present) {\n                        if (playlistStore.activeId().isEmpty()) playlistStore.setActive("current-session");\n                        PackageImporter.forceNextRefresh();\n                        importPackage();\n''')
main_text = main_text.replace(
    '        refresh.setOnClickListener(view -> importPackage());\n',
    '        refresh.setOnClickListener(view -> { PackageImporter.forceNextRefresh(); importPackage(); });\n')
main_file.write_text(main_text, encoding="utf-8")

# 3) Playback transport should fail over in seconds, not sit on a black screen.
player_file = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player/PlayerActivity.java"
player = player_file.read_text(encoding="utf-8")
player = player.replace(
    '''        return PlaybackTransportFactory.create(this, false, network,\n                15_000, 30_000, recoveryStep, playbackReferer);\n''',
    '''        return PlaybackTransportFactory.create(this, false, network,\n                3_500, 10_000, recoveryStep, playbackReferer);\n''')
player = player.replace(
    '''        playerView.setUseController(useMedia3Controller);\n        playerView.setControllerAutoShow(useMedia3Controller);\n''',
    '''        // Do not expose dead audio/subtitle controls while the source is unresolved.\n        playerView.setUseController(false);\n        playerView.setControllerAutoShow(false);\n''')
player = player.replace(
    '''        progress.setVisibility(View.GONE);\n        long firstFrameMs = SystemClock.elapsedRealtime() - playbackStartedAtMs;\n''',
    '''        progress.setVisibility(View.GONE);\n        if (!isLive()) {\n            playerView.setUseController(true);\n            playerView.setControllerAutoShow(true);\n        }\n        long firstFrameMs = SystemClock.elapsedRealtime() - playbackStartedAtMs;\n''', 1)
player_file.write_text(player, encoding="utf-8")

# 4) Make the existing home shell more cinematic without touching catalog/detail pages.
seven_file = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player/SevenMaxActivity.java"
seven = seven_file.read_text(encoding="utf-8")
anchor = '''        page.addView(header, new LinearLayout.LayoutParams(\n                ViewGroup.LayoutParams.MATCH_PARENT, dp(72)));\n\n        LinearLayout launchers = new LinearLayout(this);\n'''
if anchor in seven and "v329-home-hero" not in seven:
    seven = seven.replace(anchor,
        '''        page.addView(header, new LinearLayout.LayoutParams(\n                ViewGroup.LayoutParams.MATCH_PARENT, dp(72)));\n\n        // v329-home-hero: Netflix/OSN-style cinematic feature area; all routes stay unchanged.\n        addHero(page);\n\n        LinearLayout launchers = new LinearLayout(this);\n''')
seven = seven.replace('"BLOFY PLAYER  •  v328"', '"BLOFY PLAYER  •  v329"')
seven_file.write_text(seven, encoding="utf-8")

# 5) Keep visible version labels consistent.
settings_file = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player/SettingsActivity.java"
settings = settings_file.read_text(encoding="utf-8").replace("BLOFY PLAYER v328", "BLOFY PLAYER v329")
settings_file.write_text(settings, encoding="utf-8")

print("v329 hotfixes applied")
