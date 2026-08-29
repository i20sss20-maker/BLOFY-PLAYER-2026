#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "BLOFY-ANDROID-2026/app"
JAVA = APP / "src/main/java/tv/blofy/player"
SEVEN = JAVA / "SevenMaxActivity.java"
WATCH = JAVA / "UiResponsivenessWatchdog.java"
GRADLE = APP / "build.gradle.kts"

WATCH.write_text(r'''package tv.blofy.player;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Detects long UI stalls without crashing or restarting the app. */
final class UiResponsivenessWatchdog {
    private static final long PING_MS = 500L;
    private static final long STALL_MS = 2_500L;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService watcher = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "blofy-ui-watchdog");
        t.setDaemon(true);
        return t;
    });
    private final AtomicLong lastAck = new AtomicLong(SystemClock.elapsedRealtime());
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean reported = new AtomicBoolean(false);

    UiResponsivenessWatchdog(Context context) {
        app = context.getApplicationContext();
    }

    void start() {
        if (!started.compareAndSet(false, true)) return;
        lastAck.set(SystemClock.elapsedRealtime());
        main.post(heartbeat);
        watcher.scheduleAtFixedRate(() -> {
            long delay = SystemClock.elapsedRealtime() - lastAck.get();
            if (delay >= STALL_MS) {
                if (reported.compareAndSet(false, true)) {
                    PlaybackDiagnostics.marker(app, "r11e7-ui-stall", "ui", "", "", "main-thread",
                            "delay_ms=" + delay);
                }
            } else if (delay < 1_200L) {
                reported.set(false);
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    void stop() {
        if (!started.compareAndSet(true, false)) return;
        main.removeCallbacks(heartbeat);
        watcher.shutdownNow();
    }

    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (!started.get()) return;
            lastAck.set(SystemClock.elapsedRealtime());
            main.postDelayed(this, PING_MS);
        }
    };
}
''', encoding="utf-8")

s = SEVEN.read_text(encoding="utf-8")
if "private UiResponsivenessWatchdog r11e7Watchdog;" not in s:
    anchor = "    private volatile boolean destroyed;\n"
    if anchor not in s:
        raise SystemExit("R11E7: state anchor missing")
    s = s.replace(anchor, anchor + "    private UiResponsivenessWatchdog r11e7Watchdog;\n", 1)

if "r11e7Watchdog = new UiResponsivenessWatchdog(this);" not in s:
    anchor = "        super.onCreate(state);\n"
    if anchor not in s:
        raise SystemExit("R11E7: onCreate anchor missing")
    s = s.replace(anchor, anchor + '''        r11e7Watchdog = new UiResponsivenessWatchdog(this);\n        r11e7Watchdog.start();\n        PlaybackDiagnostics.marker(this, "r11e7-ui-watchdog", "ui", "", "", "main-thread", "started");\n''', 1)

if "r11e7Watchdog.stop();" not in s:
    anchor = "    @Override protected void onDestroy() {\n"
    if anchor not in s:
        raise SystemExit("R11E7: onDestroy anchor missing")
    s = s.replace(anchor, anchor + '''        if (r11e7Watchdog != null) {\n            r11e7Watchdog.stop();\n            r11e7Watchdog = null;\n        }\n''', 1)

SEVEN.write_text(s, encoding="utf-8")

g = GRADLE.read_text(encoding="utf-8")
g = re.sub(r'versionCode\s*=\s*\d+', 'versionCode = 1000352', g, count=1)
g = re.sub(r'versionName\s*=\s*"[^"]*"', 'versionName = "v340-full-stability-r11e-stage7"', g, count=1)
GRADLE.write_text(g, encoding="utf-8")

checks = {
    WATCH: ["r11e7-ui-stall", "STALL_MS = 2_500L", "blofy-ui-watchdog"],
    SEVEN: ["r11e7Watchdog", "r11e7-ui-watchdog", "r11e7Watchdog.stop()"],
    GRADLE: ["versionCode = 1000352", "v340-full-stability-r11e-stage7"],
}
for path, markers in checks.items():
    text = path.read_text(encoding="utf-8")
    for marker in markers:
        if marker not in text:
            raise SystemExit(f"R11E7 invariant missing {path.name}: {marker}")

print("R11E stage7 applied: non-invasive main-thread stall watchdog + diagnostics")
