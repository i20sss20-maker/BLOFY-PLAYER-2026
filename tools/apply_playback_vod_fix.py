from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player"

# MainActivity: warm Cronet during app boot and never accept a partial catalog.
p = JAVA / "MainActivity.java"
s = p.read_text()
s = s.replace(
    "        super.onCreate(state);\n        getWindow().setStatusBarColor(BlofyUi.BLACK);",
    "        super.onCreate(state);\n        PlaybackTransportFactory.warmUpCronet(this);\n        getWindow().setStatusBarColor(BlofyUi.BLACK);",
)
s = s.replace(
    '                    else if (database.count("live") + database.count("movies") + database.count("series") > 0) showHome();',
    '                    else if ("complete".equals(database.metadata("sync_state", "")) '
    '&& database.count("live") + database.count("movies") + database.count("series") > 0) showHome();',
)
p.write_text(s)

# CatalogDatabase: v3 invalidates the known Live-only partial catalog once.
p = JAVA / "CatalogDatabase.java"
s = p.read_text()
s = s.replace("private static final int VERSION = 2;", "private static final int VERSION = 3;")
old = '''    @Override
    public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            // Builds before v2 could leave a partially imported package after HTTP 429.
            // Keep favorites/history, but force a clean catalog import once after upgrade.
            database.delete("categories", null, null);
            database.delete("media", null, null);
            ContentValues values = new ContentValues();
            values.put("key", "sync_state");
            values.put("value", "upgrade_required");
            database.insertWithOnConflict("metadata", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        }
    }
'''
new = '''    @Override
    public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
        if (oldVersion < 3) {
            // v3 invalidates catalogs that could contain Live only after an interrupted sync.
            // Favorites/history remain intact; only provider catalog data is refreshed.
            database.delete("categories", null, null);
            database.delete("media", null, null);
            ContentValues values = new ContentValues();
            values.put("key", "sync_state");
            values.put("value", "upgrade_required");
            database.insertWithOnConflict("metadata", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        }
    }
'''
if old in s:
    s = s.replace(old, new)
elif "if (oldVersion < 3)" not in s:
    raise SystemExit("CatalogDatabase onUpgrade block not found")
p.write_text(s)

# PlayerActivity: Cronet first, warmed immediately, and a Live-oriented LoadControl.
p = JAVA / "PlayerActivity.java"
s = p.read_text()
s = s.replace(
    "import androidx.media3.exoplayer.DefaultRenderersFactory;",
    "import androidx.media3.exoplayer.DefaultLoadControl;\nimport androidx.media3.exoplayer.DefaultRenderersFactory;",
)
s = s.replace(
    "        super.onCreate(savedInstanceState);\n        url = getIntent().getStringExtra(EXTRA_URL);",
    "        super.onCreate(savedInstanceState);\n        PlaybackTransportFactory.warmUpCronet(this);\n        url = getIntent().getStringExtra(EXTRA_URL);",
)
s = s.replace(
    "        return stickyCronet || PlaybackPolicy.useCronet(recoveryStep);",
    "        return PlaybackPolicy.useCronet(recoveryStep);",
)
s = s.replace(
    "            if (PlaybackPolicy.useCronet(recoveryStep)) stickyCronet = true;\n",
    "",
)
needle = '''        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this)
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER);

        player = new ExoPlayer.Builder(this, renderers)
                .setMediaSourceFactory(mediaSourceFactory)
                .build();'''
replacement = '''        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this)
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER);

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        isLive() ? 10_000 : 20_000,
                        isLive() ? 45_000 : 60_000,
                        isLive() ? 1_000 : 1_500,
                        isLive() ? 3_500 : 3_000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();

        player = new ExoPlayer.Builder(this, renderers)
                .setMediaSourceFactory(mediaSourceFactory)
                .setLoadControl(loadControl)
                .build();'''
if needle in s:
    s = s.replace(needle, replacement)
elif ".setLoadControl(loadControl)" not in s:
    raise SystemExit("PlayerActivity builder block not found")
p.write_text(s)

# Version bump.
p = ROOT / "BLOFY-ANDROID-2026/app/build.gradle.kts"
s = p.read_text()
s = s.replace("versionCode = 202608238", "versionCode = 202608239")
s = s.replace('versionName = "2026.08.23.8-7max-syncfix"', 'versionName = "2026.08.23.9-7max-fast"')
p.write_text(s)

print("BLOFY playback/VOD repair applied")
