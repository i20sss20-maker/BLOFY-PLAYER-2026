from pathlib import Path

ROOT = Path('BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player')

profile = ROOT / 'PlaybackAdaptiveProfile.java'
profile.write_text(r'''package tv.blofy.player;

import android.content.Context;
import android.content.SharedPreferences;

/** R11E Stage 5: lightweight server/device playback intelligence without changing UI. */
public final class PlaybackAdaptiveProfile {
    private static final String PREFS = "blofy_playback_adaptive";
    private PlaybackAdaptiveProfile() {}

    public static void record(Context c, String serverKey, String kind, String route, String format,
                              String engine, long firstFrameMs, boolean success) {
        if (c == null || serverKey == null) return;
        String base = safe(serverKey) + ":" + safe(kind) + ":" + safe(route) + ":" + safe(format) + ":" + safe(engine);
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int ok = p.getInt(base + ":ok", 0);
        int fail = p.getInt(base + ":fail", 0);
        long avg = p.getLong(base + ":ff", 0L);
        if (success) {
            ok++;
            if (firstFrameMs > 0) avg = avg == 0 ? firstFrameMs : ((avg * 3L) + firstFrameMs) / 4L;
        } else fail++;
        p.edit().putInt(base + ":ok", ok).putInt(base + ":fail", fail).putLong(base + ":ff", avg).apply();
    }

    public static int score(Context c, String serverKey, String kind, String route, String format, String engine) {
        if (c == null || serverKey == null) return 0;
        String base = safe(serverKey) + ":" + safe(kind) + ":" + safe(route) + ":" + safe(format) + ":" + safe(engine);
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int ok = p.getInt(base + ":ok", 0);
        int fail = p.getInt(base + ":fail", 0);
        long ff = p.getLong(base + ":ff", 0L);
        int speed = ff <= 0 ? 0 : (int)Math.max(-20, 20 - (ff / 250L));
        return ok * 12 - fail * 18 + speed;
    }

    public static boolean isLowRam(Context c) {
        android.app.ActivityManager am = (android.app.ActivityManager)c.getSystemService(Context.ACTIVITY_SERVICE);
        return am != null && am.isLowRamDevice();
    }

    public static long suggestedVodCacheBytes(Context c) {
        return isLowRam(c) ? 128L * 1024L * 1024L : 512L * 1024L * 1024L;
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace(':', '_').replace('/', '_');
    }
}
''', encoding='utf-8')

build = Path('BLOFY-ANDROID-2026/app/build.gradle.kts')
b = build.read_text(encoding='utf-8')
for n in range(1000340,1000360):
    b = b.replace(f'versionCode = {n}', 'versionCode = 1000350')
for old in ('v340-full-stability-r11e-stage4','v340-full-stability-r11e-stage3','v340-full-stability-r11e-stage2','v340-full-stability-r11e-stage1'):
    b = b.replace(old, 'v340-full-stability-r11e-stage5')
build.write_text(b, encoding='utf-8')
print('R11E stage5 adaptive playback intelligence applied')
