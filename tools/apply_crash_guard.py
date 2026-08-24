from pathlib import Path

ROOT = Path('BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player')

p = ROOT / 'PlayerActivity.java'
s = p.read_text(encoding='utf-8')

if 'player-init-guard' not in s:
    old = '''    private void initializePlayer() {\n        if (player != null || !validUrl(url)) return;\n\n        DataSource.Factory dataSourceFactory = createDataSourceFactory();'''
    if old in s:
        s = s.replace(old, '''    private void initializePlayer() {\n        if (player != null || !validUrl(url)) return;\n        try {\n\n        DataSource.Factory dataSourceFactory = createDataSourceFactory();''', 1)
    old = '''        schedulePlaybackTimeout();\n    }\n\n    private void recoverFromFailure(String reason) {'''
    if old in s:
        s = s.replace(old, '''        schedulePlaybackTimeout();\n        } catch (Throwable fatalPlayerError) {\n            Log.e(TAG, "player-init-guard kind=" + kind + " ext=" + extension, fatalPlayerError);\n            safeReleasePlayer();\n            progress.setVisibility(View.GONE);\n            errorPanel.setVisibility(View.VISIBLE);\n            errorText.setText("تعذر بدء المشغل بدون إغلاق التطبيق.\\n" + fatalPlayerError.getClass().getSimpleName());\n            retryButton.setText("إعادة المحاولة");\n            retryButton.requestFocus();\n        }\n    }\n\n    private void recoverFromFailure(String reason) {''', 1)

if 'private void safeReleasePlayer()' not in s:
    old = '''    private void releasePlayer() {\n        playbackHandler.removeCallbacks(playbackTimeout);\n        if (player == null) return;\n        savePosition();\n        playerView.setPlayer(null);\n        player.removeListener(this);\n        player.release();\n        player = null;\n        playbackStartedAtMs = 0;\n    }'''
    if old in s:
        s = s.replace(old, old + '''\n\n    private void safeReleasePlayer() {\n        try { releasePlayer(); }\n        catch (Throwable releaseError) {\n            Log.e(TAG, "player-release-guard", releaseError);\n            try { if (playerView != null) playerView.setPlayer(null); } catch (Throwable ignored) {}\n            try { if (player != null) player.release(); } catch (Throwable ignored) {}\n            player = null;\n            playbackStartedAtMs = 0;\n        }\n    }''', 1)

s = s.replace('        releasePlayer();\n\n        if (PlaybackPolicy.shouldRetrySameFormat', '        safeReleasePlayer();\n\n        if (PlaybackPolicy.shouldRetrySameFormat')
s = s.replace('        releasePlayer();\n        reopenResolvedSource();', '        safeReleasePlayer();\n        reopenResolvedSource();')
s = s.replace('        releasePlayer();\n        super.onStop();', '        safeReleasePlayer();\n        super.onStop();')
s = s.replace('playbackHandler.postDelayed(playbackTimeout, isLive() ? 9_000L : 18_000L);', 'playbackHandler.postDelayed(playbackTimeout, isLive() ? 12_000L : 25_000L);')
p.write_text(s, encoding='utf-8')

p = ROOT / 'LivePreviewController.java'
s = p.read_text(encoding='utf-8')
if '    void stop() {' not in s:
    old = '''    void release() {\n        handler.removeCallbacksAndMessages(null);\n        generation++;\n        releasePlayer();\n        worker.shutdownNow();\n        cronetExecutor.shutdownNow();\n    }'''
    if old in s:
        s = s.replace(old, '''    void stop() {\n        handler.removeCallbacksAndMessages(null);\n        generation++;\n        releasePlayer();\n        title.setText("معاينة القناة");\n    }\n\n    void release() {\n        stop();\n        worker.shutdownNow();\n        cronetExecutor.shutdownNow();\n    }''', 1)
p.write_text(s, encoding='utf-8')

p = ROOT / 'SevenMaxActivity.java'
s = p.read_text(encoding='utf-8')
needle = '''    private void play(BlofyModels.Media item) {\n        database.addHistory(item.type,item.id);'''
if 'if (livePreview != null) livePreview.stop();' not in s and needle in s:
    s = s.replace(needle, '''    private void play(BlofyModels.Media item) {\n        if (livePreview != null) livePreview.stop();\n        database.addHistory(item.type,item.id);''', 1)
if 'protected void onStop(){ if(livePreview!=null)livePreview.stop();' not in s:
    s = s.replace('    @Override protected void onDestroy(){ if(livePreview!=null)livePreview.release(); database.close(); super.onDestroy(); }', '    @Override protected void onStop(){ if(livePreview!=null)livePreview.stop(); super.onStop(); }\n    @Override protected void onDestroy(){ if(livePreview!=null)livePreview.release(); database.close(); super.onDestroy(); }')
p.write_text(s, encoding='utf-8')

print('BLOFY crash guard applied safely')
