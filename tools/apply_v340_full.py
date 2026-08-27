from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player"


def replace_once(path: Path, old: str, new: str):
    text = path.read_text()
    if old not in text:
        raise SystemExit(f"missing patch anchor in {path.name}: {old[:80]!r}")
    path.write_text(text.replace(old, new, 1))


def patch_settings():
    path = JAVA / "SettingsActivity.java"
    replace_once(path,
        '        addGridSetting(grid, gridCycle("✦  حركة الواجهة", KEY_MOTION,\n                new String[]{"smooth", "reduced"}, new String[]{"سلسة", "خفيفة"}, null));\n',
        '        addGridSetting(grid, gridCycle("✦  حركة الواجهة", KEY_MOTION,\n                new String[]{"smooth", "reduced"}, new String[]{"سلسة", "خفيفة"}, null));\n'
        '        addGridSetting(grid, gridAction("🩺  وضع التشخيص",\n'
        '                PlaybackDiagnostics.enabled(this) ? "مفعّل • تسجيل أسباب الفشل" : "متوقف", () -> {\n'
        '            boolean enabled = !PlaybackDiagnostics.enabled(this);\n'
        '            prefs.edit().putBoolean(PlaybackDiagnostics.KEY_DIAGNOSTICS, enabled).apply();\n'
        '            ToastBridge.show(this, enabled ? "تم تفعيل وضع التشخيص" : "تم إيقاف وضع التشخيص");\n'
        '            buildGrid();\n'
        '        }));\n'
        '        addGridSetting(grid, gridAction("▤  نسخ تقرير المشكلة", "آخر محاولات التشغيل والأداء", () -> {\n'
        '            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);\n'
        '            if (clipboard != null) clipboard.setPrimaryClip(android.content.ClipData.newPlainText("BLOFY diagnostics", PlaybackDiagnostics.report(this)));\n'
        '            ToastBridge.show(this, "تم نسخ تقرير التشخيص");\n'
        '        }));\n')


def patch_player():
    path = JAVA / "PlayerActivity.java"
    replace_once(path,
        '    private long playbackStartedAtMs;\n',
        '    private long playbackStartedAtMs;\n    private long resolveStartedAtMs;\n')

    replace_once(path,
        '        resolveCancellation = cancellation;\n        progress.setVisibility(View.VISIBLE);\n',
        '        resolveCancellation = cancellation;\n'
        '        resolveStartedAtMs = PlaybackDiagnostics.start(this, isLive() ? "live-resolve" : "vod-resolve",\n'
        '                kind, id, extension, sourceVariant);\n'
        '        progress.setVisibility(View.VISIBLE);\n')

    replace_once(path,
        '                    url = finalResolved.startsWith("http") ? finalResolved\n                            : BuildConfig.BLOFY_BASE_URL.replaceAll("/+$", "") + finalResolved;\n                    extension = resolvedExtension;\n                    playbackReferer = resolvedReferer;\n',
        '                    url = finalResolved.startsWith("http") ? finalResolved\n'
        '                            : BuildConfig.BLOFY_BASE_URL.replaceAll("/+$", "") + finalResolved;\n'
        '                    extension = resolvedExtension;\n'
        '                    playbackReferer = resolvedReferer;\n'
        '                    if (isLive() && "auto".equals(playerSetting(SettingsActivity.KEY_STREAM, "auto"))) {\n'
        '                        ServerPlaybackProfile.Profile profile = ServerPlaybackProfile.load(this, url);\n'
        '                        if (profile.fresh() && !profile.preferredLiveExtension.isEmpty()) {\n'
        '                            extension = PlaybackPolicy.normalizeExtension(profile.preferredLiveExtension, extension);\n'
        '                        }\n'
        '                    }\n'
        '                    PlaybackDiagnostics.success(this, isLive() ? "live-resolve" : "vod-resolve",\n'
        '                            kind, id, extension, sourceVariant, resolveStartedAtMs, "link-ready");\n')

    replace_once(path,
        '                    showResolveError(PlaybackPolicy.resolveErrorMessage(error));\n',
        '                    PlaybackDiagnostics.failure(this, isLive() ? "live-resolve" : "vod-resolve",\n'
        '                            kind, id, requestedExtension, requestedVariant, resolveStartedAtMs, 0, error);\n'
        '                    showResolveError(PlaybackPolicy.resolveErrorMessage(error));\n')

    replace_once(path,
        '        Log.i(TAG, "open kind=" + kind + " ext=" + extension + " step=" + recoveryStep\n',
        '        PlaybackDiagnostics.marker(this, isLive() ? "live-open" : "vod-open", kind, id, extension,\n'
        '                activeTransportName(), "media3-init step=" + recoveryStep);\n'
        '        Log.i(TAG, "open kind=" + kind + " ext=" + extension + " step=" + recoveryStep\n')

    replace_once(path,
        '        Log.i(TAG, "compat-first-frame ext=" + extension + " ms=" + firstFrameMs);\n',
        '        Log.i(TAG, "compat-first-frame ext=" + extension + " ms=" + firstFrameMs);\n'
        '        PlaybackDiagnostics.success(this, isLive() ? "live-first-frame" : "vod-first-frame",\n'
        '                kind, id, extension, "libvlc", playbackStartedAtMs, "first-frame");\n'
        '        ServerPlaybackProfile.rememberSuccess(this, url, extension, sourceVariant, "libvlc", "", playbackReferer);\n')

    replace_once(path,
        '        Log.i(TAG, "first-frame kind=" + kind + " ext=" + extension + " ms=" + firstFrameMs\n                + " transport=" + activeTransportName());\n',
        '        Log.i(TAG, "first-frame kind=" + kind + " ext=" + extension + " ms=" + firstFrameMs\n'
        '                + " transport=" + activeTransportName());\n'
        '        PlaybackDiagnostics.success(this, isLive() ? "live-first-frame" : "vod-first-frame",\n'
        '                kind, id, extension, activeTransportName(), playbackStartedAtMs, "first-frame");\n'
        '        ServerPlaybackProfile.rememberSuccess(this, url, extension, sourceVariant,\n'
        '                activeTransportName(), "", playbackReferer);\n')

    replace_once(path,
        '        Log.w(TAG, "bounded-recovery reason=" + reason + " ext=" + extension\n                + " variant=" + sourceVariant + " uhd=" + isUltraHd());\n',
        '        Log.w(TAG, "bounded-recovery reason=" + reason + " ext=" + extension\n'
        '                + " variant=" + sourceVariant + " uhd=" + isUltraHd());\n'
        '        PlaybackDiagnostics.failure(this, isLive() ? "live-recovery" : "vod-recovery",\n'
        '                kind, id, extension, sourceVariant, playbackStartedAtMs, 0,\n'
        '                new Exception(reason == null ? "playback-failure" : reason));\n')

    replace_once(path,
        '        errorText.setText("تعذر تشغيل القناة بعد المحاولة بالمشغل الأساسي والمتوافق."\n                + "\\n" + detail + "\\nالصيغة: " + extension);\n',
        '        String diagnosticCode = PlaybackDiagnostics.latestCode();\n'
        '        String diagnosticLine = PlaybackDiagnostics.enabled(this) && !diagnosticCode.isEmpty()\n'
        '                ? "\\nرمز التشخيص: " + diagnosticCode : "";\n'
        '        errorText.setText("تعذر تشغيل القناة بعد المحاولة بالمشغل الأساسي والمتوافق."\n'
        '                + "\\n" + detail + "\\nالصيغة: " + extension + diagnosticLine);\n')

    replace_once(path,
        '        Log.w(TAG, "player-error code=" + error.errorCode + " name=" + error.getErrorCodeName()\n                + " ext=" + extension + " transport=" + activeTransportName(), error);\n',
        '        Log.w(TAG, "player-error code=" + error.errorCode + " name=" + error.getErrorCodeName()\n'
        '                + " ext=" + extension + " transport=" + activeTransportName(), error);\n'
        '        int diagnosticHttp = 0;\n'
        '        Throwable diagnosticCause = error;\n'
        '        while (diagnosticCause != null) {\n'
        '            if (diagnosticCause instanceof HttpDataSource.InvalidResponseCodeException) {\n'
        '                diagnosticHttp = ((HttpDataSource.InvalidResponseCodeException) diagnosticCause).responseCode;\n'
        '                break;\n'
        '            }\n'
        '            diagnosticCause = diagnosticCause.getCause();\n'
        '        }\n'
        '        PlaybackDiagnostics.failure(this, isLive() ? "live-player" : "vod-player", kind, id,\n'
        '                extension, activeTransportName(), playbackStartedAtMs, diagnosticHttp, error);\n')


def patch_catalog():
    path = JAVA / "SevenMaxActivity.java"
    replace_once(path,
        '        images = new ImageLoader(api);\n        showHome();\n',
        '        images = new ImageLoader(api);\n'
        '        // Persistent SQLite is already populated by PackageImporter. Warm the\n'
        '        // ready-state cache off the UI thread so huge playlists never delay launch.\n'
        '        try { catalogWorker.execute(() -> CatalogUiCache.warm(database)); } catch (RejectedExecutionException ignored) {}\n'
        '        showHome();\n')

    replace_once(path,
        '        TextView packageName = BlofyUi.text(this,\n                database.metadata("server_name", "BLOFY") + "  •  "\n                        + formatCount(database.count("live"), "قناة"), 11, BlofyUi.MUTED);\n',
        '        TextView packageName = BlofyUi.text(this,\n'
        '                database.metadata("server_name", "BLOFY") + "  •  "\n'
        '                        + formatCount(CatalogUiCache.count(database, "live"), "قناة"), 11, BlofyUi.MUTED);\n')

    replace_once(path,
        '        List<BlofyModels.Category> allCategories = database.categories("live");\n',
        '        List<BlofyModels.Category> allCategories = CatalogUiCache.categories(database, "live");\n')

    # Make Back responsive even while a child view is busy/spinning.
    replace_once(path,
        '    @Override public boolean dispatchKeyEvent(KeyEvent event) {\n',
        '    @Override public boolean dispatchKeyEvent(KeyEvent event) {\n'
        '        if (event != null && event.getAction() == KeyEvent.ACTION_DOWN\n'
        '                && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {\n'
        '            releasePreview();\n'
        '            if (!"home".equals(screen)) { showHome(); return true; }\n'
        '        }\n')


def main():
    patch_settings()
    patch_player()
    patch_catalog()
    print("v340 full stability layer applied")


if __name__ == "__main__":
    main()
