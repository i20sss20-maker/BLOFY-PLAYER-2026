#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "BLOFY-ANDROID-2026/app"
JAVA = APP / "src/main/java/tv/blofy/player"
NEG = JAVA / "PlaybackNegotiator.java"
GRADLE = APP / "build.gradle.kts"
HEALTH = JAVA / "PlaybackHealthMemory.java"

HEALTH.write_text(r'''package tv.blofy.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.security.MessageDigest;
import java.util.Locale;

/** R11E stage 5: persistent per-provider playback health used to rank proven routes. */
final class PlaybackHealthMemory {
    private static final String PREFS = "blofy_r11e_playback_health";
    private PlaybackHealthMemory() {}

    static void success(Context c, String provider, String family, String route,
                        String extension, String engine, long firstFrameMs) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String k = key(provider, family, route, extension, engine);
        int ok = p.getInt(k + ".ok", 0) + 1;
        int fail = p.getInt(k + ".fail", 0);
        long oldAvg = p.getLong(k + ".ff", 0L);
        long sample = firstFrameMs > 0 ? Math.min(firstFrameMs, 60_000L) : oldAvg;
        long avg = oldAvg <= 0 ? sample : (oldAvg * 3L + sample) / 4L;
        p.edit().putInt(k + ".ok", Math.min(500, ok))
                .putInt(k + ".fail", Math.max(0, fail - 1))
                .putLong(k + ".ff", avg)
                .putLong(k + ".seen", System.currentTimeMillis()).apply();
        PlaybackDiagnostics.marker(c, "r11e5-health-success", family, "", extension, route,
                "engine=" + safe(engine) + " score=" + score(c, provider, family, route, extension, engine)
                        + " first_frame_ms=" + firstFrameMs);
    }

    static void failure(Context c, String provider, String family, String route,
                        String extension, String engine, String reason) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String k = key(provider, family, route, extension, engine);
        int fail = p.getInt(k + ".fail", 0) + 1;
        p.edit().putInt(k + ".fail", Math.min(500, fail))
                .putLong(k + ".seen", System.currentTimeMillis()).apply();
        PlaybackDiagnostics.marker(c, "r11e5-health-failure", family, "", extension, route,
                "engine=" + safe(engine) + " score=" + score(c, provider, family, route, extension, engine)
                        + " reason=" + safe(reason));
    }

    static int score(Context c, String provider, String family, String route,
                     String extension, String engine) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String k = key(provider, family, route, extension, engine);
        int ok = p.getInt(k + ".ok", 0);
        int fail = p.getInt(k + ".fail", 0);
        long ff = p.getLong(k + ".ff", 0L);
        long seen = p.getLong(k + ".seen", 0L);
        int speed = ff <= 0 ? 0 : ff <= 1_500 ? 30 : ff <= 3_000 ? 20 : ff <= 6_000 ? 10 : -5;
        int recency = seen > 0 && System.currentTimeMillis() - seen < 7L * 24L * 60L * 60L * 1000L ? 5 : 0;
        return Math.max(-1000, Math.min(1000, ok * 18 - fail * 24 + speed + recency));
    }

    private static String key(String provider, String family, String route, String ext, String engine) {
        String host = safe(provider);
        try { String h = Uri.parse(host).getHost(); if (h != null) host = h; } catch (Exception ignored) {}
        return digest(host + "|" + safe(family) + "|" + safe(route) + "|" + safe(ext) + "|" + safe(engine));
    }

    private static String digest(String value) {
        try {
            byte[] b = MessageDigest.getInstance("SHA-256")
                    .digest(safe(value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder s = new StringBuilder(24);
            for (int i = 0; i < Math.min(12, b.length); i++) s.append(String.format(Locale.US, "%02x", b[i]));
            return s.toString();
        } catch (Exception e) { return Integer.toHexString(safe(value).hashCode()); }
    }

    private static String safe(String v) { return v == null ? "" : v; }
}
''', encoding="utf-8")

n = NEG.read_text(encoding="utf-8")

# Feed proven first-frame evidence into persistent health memory.
if "PlaybackHealthMemory.success(context" not in n:
    sig = '''    static void proven(Context context, String providerUrl, String family,
                       String route, String extension, String engine, long firstFrameMs) {'''
    if sig not in n:
        raise SystemExit("R11E5: proven anchor missing")
    n = n.replace(sig, sig + '''
        PlaybackHealthMemory.success(context, providerUrl, family, route, extension, engine, firstFrameMs);''', 1)

# Feed pre-first-frame failures into the same model.
if "PlaybackHealthMemory.failure(context" not in n:
    sig = '''    static void failed(Context context, String providerUrl, String family,
                       String route, String extension, String engine, String reason) {'''
    if sig not in n:
        raise SystemExit("R11E5: failed anchor missing")
    n = n.replace(sig, sig + '''
        PlaybackHealthMemory.failure(context, providerUrl, family, route, extension, engine, reason);''', 1)

# Stable score sort after the negative-memory filter. Equal scores preserve the
# existing canonical/profile order, so fresh installs behave exactly like R11E4.
# Collections.sort is used instead of List.sort to preserve Android API 23 support.
old = '''        return out.isEmpty() ? input : out;
    }

    static void failed(Context context'''
if "r11e5-health-ranking" not in n:
    new = '''        List<Candidate> ranked = out.isEmpty() ? new ArrayList<>(input) : out;
        java.util.Collections.sort(ranked, (a, b) -> Integer.compare(
                PlaybackHealthMemory.score(context, providerUrl, family, b.route, b.extension, b.engine),
                PlaybackHealthMemory.score(context, providerUrl, family, a.route, a.extension, a.engine)));
        if (ranked.size() > 1) {
            Candidate best = ranked.get(0);
            PlaybackDiagnostics.marker(context, "r11e5-health-ranking", family, "", best.extension,
                    best.route, "engine=" + best.engine + " score="
                            + PlaybackHealthMemory.score(context, providerUrl, family,
                            best.route, best.extension, best.engine));
        }
        return ranked;
    }

    static void failed(Context context'''
    if old not in n:
        raise SystemExit("R11E5: filter return anchor missing")
    n = n.replace(old, new, 1)

NEG.write_text(n, encoding="utf-8")

g = GRADLE.read_text(encoding="utf-8")
g = re.sub(r'versionCode\s*=\s*\d+', 'versionCode = 1000350', g, count=1)
g = re.sub(r'versionName\s*=\s*"[^"]*"', 'versionName = "v340-full-stability-r11e-stage5"', g, count=1)
GRADLE.write_text(g, encoding="utf-8")

checks = {
    HEALTH: ["r11e5-health-success", "r11e5-health-failure", "static int score"],
    NEG: ["PlaybackHealthMemory.success(context", "PlaybackHealthMemory.failure(context", "r11e5-health-ranking", "java.util.Collections.sort"],
    GRADLE: ["versionCode = 1000350", "v340-full-stability-r11e-stage5"],
}
for path, markers in checks.items():
    text = path.read_text(encoding="utf-8")
    for marker in markers:
        if marker not in text:
            raise SystemExit(f"R11E5 invariant missing {path.name}: {marker}")

print("R11E stage5 applied: persistent playback health + first-frame scoring + API23-safe route ranking")
