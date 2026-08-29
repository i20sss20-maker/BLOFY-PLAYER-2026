from pathlib import Path

ROOT = Path('BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player')
helper = ROOT / 'PlaybackRecoveryBudget.java'
helper.write_text(r'''package tv.blofy.player;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** R11E Stage 8: finite recovery budget so one bad source cannot freeze a receiver. */
public final class PlaybackRecoveryBudget {
    private static final int MAX_ATTEMPTS = 4;
    private static final ConcurrentHashMap<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
    private PlaybackRecoveryBudget() {}
    public static boolean allow(String transactionKey) {
        if (transactionKey == null) return true;
        int n = attempts.computeIfAbsent(transactionKey, k -> new AtomicInteger()).incrementAndGet();
        return n <= MAX_ATTEMPTS;
    }
    public static void clear(String transactionKey) {
        if (transactionKey != null) attempts.remove(transactionKey);
    }
}
''', encoding='utf-8')

build = Path('BLOFY-ANDROID-2026/app/build.gradle.kts')
b = build.read_text(encoding='utf-8')
for n in range(1000340,1000370):
    b = b.replace(f'versionCode = {n}', 'versionCode = 1000353')
for old in ('v340-full-stability-r11e-final2','v340-full-stability-r11e-final','v340-full-stability-r11e-stage5','v340-full-stability-r11e-stage4','v340-full-stability-r11e-stage3','v340-full-stability-r11e-stage2','v340-full-stability-r11e-stage1'):
    b = b.replace(old, 'v340-full-stability-r11e-final3')
build.write_text(b, encoding='utf-8')
print('R11E stage8 recovery watchdog applied')
