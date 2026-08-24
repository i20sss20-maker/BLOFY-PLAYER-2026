from pathlib import Path

ROOT = Path('BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player')


def req(text, old, new, label):
    if old not in text:
        raise SystemExit(f'{label}: pattern not found')
    return text.replace(old, new, 1)

# PlayerActivity: never allow a Java-side player init/release exception to close
# the Activity. Keep the user inside the player and expose Retry instead.
p = ROOT / 'PlayerActivity.java'
s = p.read_text(encoding='utf-8')

s = req(s,
'''    private void initializePlayer() {
        if (player != null || !validUrl(url)) return;

        DataSource.Factory dataSourceFactory = createDataSourceFactory();''',
'''    private void initializePlayer() {
        if (player != null || !validUrl(url)) return;
        try {

        DataSource.Factory dataSourceFactory = createDataSourceFactory();''', 'player init guard start')

s = req(s,
'''        schedulePlaybackTimeout();
    }

    private void recoverFromFailure(String reason) {''',
'''        schedulePlaybackTimeout();
        } catch (Throwable fatalPlayerError) {
            Log.e(TAG, "player-init-guard kind=" + kind + " ext=" + extension, fatalPlayerError);
            safeReleasePlayer();
            progress.setVisibility(View.GONE);
            errorPanel.setVisibility(View.VISIBLE);
            errorText.setText("تعذر بدء المشغل بدون إغلاق التطبيق.\\n" + fatalPlayerError.getClass().getSimpleName());
            retryButton.setText("إعادة المحاولة");
            retryButton.requestFocus();
        }
    }

    private void recoverFromFailure(String reason) {''', 'player init guard end')

# Replace raw release calls on recovery/manual retry with safe release.
s = s.replace('        releasePlayer();\n\n        if (PlaybackPolicy.shouldRetrySameFormat',
              '        safeReleasePlayer();\n\n        if (PlaybackPolicy.shouldRetrySameFormat')
s = s.replace('        releasePlayer();\n        reopenResolvedSource();',
              '        safeReleasePlayer();\n        reopenResolvedSource();')

s = req(s,
'''    private void releasePlayer() {
        playbackHandler.removeCallbacks(playbackTimeout);
        if (player == null) return;
        savePosition();
        playerView.setPlayer(null);
        player.removeListener(this);
        player.release();
        player = null;
        playbackStartedAtMs = 0;
    }''',
'''    private void releasePlayer() {
        playbackHandler.removeCallbacks(playbackTimeout);
        if (player == null) return;
        savePosition();
        playerView.setPlayer(null);
        player.removeListener(this);
        player.release();
        player = null;
        playbackStartedAtMs = 0;
    }

    private void safeReleasePlayer() {
        try { releasePlayer(); }
        catch (Throwable releaseError) {
            Log.e(TAG, "player-release-guard", releaseError);
            try { if (playerView != null) playerView.setPlayer(null); } catch (Throwable ignored) {}
            try { if (player != null) player.release(); } catch (Throwable ignored) {}
            player = null;
            playbackStartedAtMs = 0;
        }
    }''', 'safe release helper')

s = s.replace('        releasePlayer();\n        super.onStop();',
              '        safeReleasePlayer();\n        super.onStop();')

# A transient READY->BUFFERING state must not immediately rotate formats or leave
# the screen. Give live a short grace window and VOD more time to refill.
s = s.replace('playbackHandler.postDelayed(playbackTimeout, isLive() ? 9_000L : 18_000L);',
              'playbackHandler.postDelayed(playbackTimeout, isLive() ? 12_000L : 25_000L);')

p.write_text(s, encoding='utf-8')

# Preview: release decoder/network resources whenever full-screen playback opens.
p = ROOT / 'LivePreviewController.java'
s = p.read_text(encoding='utf-8')
s = req(s,
'''    void release() {
        handler.removeCallbacksAndMessages(null);
        generation++;
        releasePlayer();
        worker.shutdownNow();
        cronetExecutor.shutdownNow();
    }''',
'''    void stop() {
        handler.removeCallbacksAndMessages(null);
        generation++;
        releasePlayer();
        title.setText("معاينة القناة");
    }

    void release() {
        stop();
        worker.shutdownNow();
        cronetExecutor.shutdownNow();
    }''', 'preview stop')
p.write_text(s, encoding='utf-8')

# SevenMaxActivity: do not keep a second decoder alive behind PlayerActivity.
p = ROOT / 'SevenMaxActivity.java'
s = p.read_text(encoding='utf-8')
s = req(s,
'''    private void play(BlofyModels.Media item) {
        database.addHistory(item.type,item.id);''',
'''    private void play(BlofyModels.Media item) {
        if (livePreview != null) livePreview.stop();
        database.addHistory(item.type,item.id);''', 'stop preview before fullscreen')

# Also free preview on Activity stop (home/background/full-screen player).
s = s.replace('    @Override protected void onDestroy(){ if(livePreview!=null)livePreview.release(); database.close(); super.onDestroy(); }',
'''    @Override protected void onStop(){ if(livePreview!=null)livePreview.stop(); super.onStop(); }
    @Override protected void onDestroy(){ if(livePreview!=null)livePreview.release(); database.close(); super.onDestroy(); }''')
p.write_text(s, encoding='utf-8')

print('BLOFY crash guard applied: safe player init/release + preview decoder handoff')
