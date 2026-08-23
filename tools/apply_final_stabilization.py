from pathlib import Path

ROOT = Path('BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player')


def replace_once(text, old, new, label):
    if old not in text:
        raise SystemExit(f'{label}: pattern not found')
    return text.replace(old, new, 1)

# 1) Never expose a half-imported catalog by backing out of the sync screen.
main_path = ROOT / 'MainActivity.java'
main = main_path.read_text(encoding='utf-8')
old = '''    @Override\n    public void onBackPressed() {\n        if (\"home\".equals(screen) || \"login\".equals(screen) || \"splash\".equals(screen)) finish();\n        else showHome();\n    }'''
new = '''    @Override\n    public void onBackPressed() {\n        if (\"import\".equals(screen)) {\n            Toast.makeText(this, \"انتظر حتى تكتمل قراءة الباقة 100٪\", Toast.LENGTH_SHORT).show();\n            return;\n        }\n        if (\"home\".equals(screen) || \"login\".equals(screen) || \"splash\".equals(screen)) finish();\n        else showHome();\n    }'''
main = replace_once(main, old, new, 'MainActivity back guard')
main_path.write_text(main, encoding='utf-8')

# 2) Treat Movies/Series=0 as an incomplete import when categories exist.
imp_path = ROOT / 'PackageImporter.java'
imp = imp_path.read_text(encoding='utf-8')
old = '''        emit(end, \"اكتملت \" + label, database.count(type) + \" عنصر محفوظ محليًا\");\n    }\n\n    private void importByCategories'''
new = '''        if (!\"live\".equals(type) && !categories.isEmpty() && database.count(type) == 0) {\n            throw new Exception(\"الخادم أرسل تصنيفات \" + label + \" لكن لم تصل العناصر. سيتم إعادة المحاولة بدل حفظ قائمة ناقصة.\");\n        }\n        emit(end, \"اكتملت \" + label, database.count(type) + \" عنصر محفوظ محليًا\");\n    }\n\n    private void importByCategories'''
imp = replace_once(imp, old, new, 'PackageImporter completeness guard')
old = '''        emit(end, \"اكتملت \" + label, database.count(type) + \" عنصر محفوظ محليًا\");\n    }\n\n    private JSONObject getWithRetry'''
new = '''        if (!categories.isEmpty() && database.count(type) == 0) {\n            throw new Exception(\"تعذر جلب \" + label + \" من جميع التصنيفات.\");\n        }\n        emit(end, \"اكتملت \" + label, database.count(type) + \" عنصر محفوظ محليًا\");\n    }\n\n    private JSONObject getWithRetry'''
imp = replace_once(imp, old, new, 'PackageImporter category guard')
imp_path.write_text(imp, encoding='utf-8')

# 3) Deeper live buffer + reconnect loop that stays in the player.
player_path = ROOT / 'PlayerActivity.java'
player = player_path.read_text(encoding='utf-8')
player = replace_once(player,
'''    private long playbackStartedAtMs;\n''',
'''    private long playbackStartedAtMs;\n    private int consecutiveLiveFailures;\n    private long lastReadyAtMs;\n''', 'Player fields')
player = replace_once(player,
'''        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()\n                .setBufferDurationsMs(\n                        isLive() ? 10_000 : 20_000,\n                        isLive() ? 45_000 : 60_000,\n                        isLive() ? 1_000 : 1_500,\n                        isLive() ? 3_500 : 3_000)\n                .setPrioritizeTimeOverSizeThresholds(true)\n                .build();''',
'''        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()\n                .setBufferDurationsMs(\n                        isLive() ? 12_000 : 20_000,\n                        isLive() ? 120_000 : 75_000,\n                        isLive() ? 1_200 : 1_500,\n                        isLive() ? 6_000 : 4_000)\n                .setBackBuffer(isLive() ? 15_000 : 30_000, false)\n                .setPrioritizeTimeOverSizeThresholds(true)\n                .build();''', 'Player load control')

start = player.index('    private void recoverFromFailure(String reason) {')
end = player.index('    private void reopenResolvedSource()', start)
replacement = '''    private void recoverFromFailure(String reason) {\n        if (isFinishing() || isDestroyed()) return;\n        playbackHandler.removeCallbacks(playbackTimeout);\n        recoveryStep += 1;\n        Log.w(TAG, \"recover reason=\" + reason + \" step=\" + recoveryStep + \" ext=\" + extension\n                + \" nextTransport=\" + PlaybackPolicy.transportName(recoveryStep));\n        releasePlayer();\n\n        if (PlaybackPolicy.shouldRetrySameFormat(recoveryStep)) {\n            reopenResolvedSource();\n            return;\n        }\n        if (isLive() && PlaybackPolicy.shouldTryAlternateLiveFormat(recoveryStep) && !id.isEmpty()) {\n            extension = PlaybackPolicy.alternateLiveExtension(extension);\n            url = null;\n            resolvePlaybackLink();\n            return;\n        }\n        if (isLive() && PlaybackPolicy.shouldRetryAlternateFormat(recoveryStep)) {\n            reopenResolvedSource();\n            return;\n        }\n\n        if (isLive()) {\n            consecutiveLiveFailures++;\n            recoveryStep = 0;\n            progress.setVisibility(View.VISIBLE);\n            errorPanel.setVisibility(View.GONE);\n            titleView.setVisibility(View.VISIBLE);\n            titleView.setText(\"إعادة الاتصال بالبث…\");\n            long delay = Math.min(6_000L, 1_200L + consecutiveLiveFailures * 700L);\n            playbackHandler.postDelayed(() -> {\n                if (!isFinishing() && !isDestroyed()) {\n                    url = null;\n                    reopenResolvedSource();\n                }\n            }, delay);\n            return;\n        }\n\n        progress.setVisibility(View.GONE);\n        errorPanel.setVisibility(View.VISIBLE);\n        errorText.setText(\"تعذر تشغيل المصدر. آخر سبب: \" + reason\n                + \"\\nالصيغة: \" + extension + \"\\nالنقل: \" + activeTransportName());\n        retryButton.setText(\"إعادة المحاولة\");\n        retryButton.requestFocus();\n    }\n\n'''
player = player[:start] + replacement + player[end:]

player = replace_once(player,
'''        if (playbackState == Player.STATE_BUFFERING) {\n            progress.setVisibility(View.VISIBLE);\n            return;\n        }''',
'''        if (playbackState == Player.STATE_BUFFERING) {\n            progress.setVisibility(View.VISIBLE);\n            playbackHandler.removeCallbacks(playbackTimeout);\n            playbackHandler.postDelayed(playbackTimeout, isLive() ? 15_000L : 25_000L);\n            return;\n        }''', 'Player buffering watchdog')
player = replace_once(player,
'''            recoveryStep = 0;\n            titleView.postDelayed(() -> titleView.setVisibility(View.GONE), 2500);''',
'''            recoveryStep = 0;\n            lastReadyAtMs = SystemClock.elapsedRealtime();\n            consecutiveLiveFailures = 0;\n            titleView.setText(title);\n            titleView.postDelayed(() -> titleView.setVisibility(View.GONE), 2500);''', 'Player ready state')
player_path.write_text(player, encoding='utf-8')

# 4) Details API retries; movie details may fall back to direct playback instead of a dead end.
detail_path = ROOT / 'DetailsActivity.java'
detail = detail_path.read_text(encoding='utf-8')
old = '''        worker.execute(() -> {\n            try {\n                String path = \"series\".equals(item.type) ? \"/api/series/\" : \"/api/movie/\";\n                BlofyModels.Detail detail = new BlofyModels.Detail(api.get(path + BlofyApi.encode(item.id)), item.type);\n                main.post(() -> showDetail(detail));\n            } catch (Exception error) { main.post(() -> showError(error.getMessage())); }\n        });'''
new = '''        worker.execute(() -> {\n            Exception last = null;\n            for (int attempt = 0; attempt < 3; attempt++) {\n                try {\n                    String path = \"series\".equals(item.type) ? \"/api/series/\" : \"/api/movie/\";\n                    BlofyModels.Detail detail = new BlofyModels.Detail(api.get(path + BlofyApi.encode(item.id)), item.type);\n                    main.post(() -> showDetail(detail));\n                    return;\n                } catch (Exception error) {\n                    last = error;\n                    try { Thread.sleep(700L * (attempt + 1)); } catch (InterruptedException ignored) { break; }\n                }\n            }\n            Exception failure = last;\n            main.post(() -> {\n                if (\"movies\".equals(item.type)) {\n                    play(item.id, item.name, \"movies\", item.extension);\n                    finish();\n                } else {\n                    showError(failure == null ? \"تعذر تحميل الحلقات.\" : failure.getMessage());\n                }\n            });\n        });'''
detail = replace_once(detail, old, new, 'Details retry')
detail_path.write_text(detail, encoding='utf-8')

# 5) Replace placeholder theme buttons with useful in-theme settings/refresh actions.
seven_path = ROOT / 'SevenMaxActivity.java'
seven = seven_path.read_text(encoding='utf-8')
seven = replace_once(seven,
'''    private void openLegacySettings() { ToastBridge.show(this,\"الإعدادات الجديدة بتدخل في نفس الثيم بالنسخة التالية\"); }\n    private void openLegacyRefresh() { ToastBridge.show(this,\"تحديث الباقة من شاشة الدخول الحالية مؤقتًا\"); }''',
'''    private void openLegacySettings() {\n        screen = \"settings\";\n        root.removeAllViews();\n        LinearLayout page = shell();\n        LinearLayout panel = new LinearLayout(this);\n        panel.setOrientation(LinearLayout.VERTICAL);\n        panel.setPadding(dp(28), dp(22), dp(28), dp(22));\n        panel.setBackground(BlofyUi.panel(this, Color.rgb(18,18,20), 8, Color.rgb(66,66,72)));\n        panel.addView(BlofyUi.title(this, \"الإعدادات\", 24));\n        panel.addView(BlofyUi.text(this, \"الخادم: \" + database.metadata(\"server_name\", \"—\"), 15, BlofyUi.TEXT));\n        panel.addView(BlofyUi.text(this, \"طريقة التشغيل: \" + database.metadata(\"playback_profile\", \"Media3 + Cronet\"), 15, BlofyUi.MUTED));\n        panel.addView(BlofyUi.text(this, \"Live \" + database.count(\"live\") + \"  •  Movies \" + database.count(\"movies\") + \"  •  Series \" + database.count(\"series\"), 15, BlofyUi.MUTED));\n        Button refresh = BlofyUi.button(this, \"↻ إعادة قراءة الباقة بالكامل\", true);\n        refresh.setOnClickListener(v -> openLegacyRefresh());\n        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)); rp.topMargin = dp(18); panel.addView(refresh, rp);\n        Button back = BlofyUi.button(this, \"رجوع\", false);\n        back.setOnClickListener(v -> showHome());\n        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)); bp.topMargin = dp(10); panel.addView(back, bp);\n        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1); pp.setMargins(dp(70), dp(25), dp(70), dp(35)); page.addView(panel, pp);\n        root.addView(page, match());\n        refresh.requestFocus();\n    }\n    private void openLegacyRefresh() {\n        Intent i = new Intent(this, MainActivity.class);\n        i.putExtra(\"force_sync\", true);\n        startActivity(i);\n        finish();\n    }''', 'SevenMax settings')
seven_path.write_text(seven, encoding='utf-8')

# 6) MainActivity honors force_sync launched from the themed settings page.
main = main_path.read_text(encoding='utf-8')
old = '''                    if (!license.usable() || !session.present) showLogin(\"\");\n                    else if (\"complete\".equals(database.metadata(\"sync_state\", \"\")) && database.count(\"live\") + database.count(\"movies\") + database.count(\"series\") > 0) showHome();\n                    else importPackage();'''
new = '''                    if (!license.usable() || !session.present) showLogin(\"\");\n                    else if (getIntent().getBooleanExtra(\"force_sync\", false)) importPackage();\n                    else if (\"complete\".equals(database.metadata(\"sync_state\", \"\")) && database.count(\"live\") + database.count(\"movies\") + database.count(\"series\") > 0) showHome();\n                    else importPackage();'''
main = replace_once(main, old, new, 'MainActivity force sync')
main_path.write_text(main, encoding='utf-8')

print('BLOFY final stabilization applied')
