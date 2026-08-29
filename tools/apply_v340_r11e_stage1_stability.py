#!/usr/bin/env python3
from pathlib import Path
import re
import runpy

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "BLOFY-ANDROID-2026/app"
JAVA = APP / "src/main/java/tv/blofy/player"
NEG = JAVA / "PlaybackNegotiator.java"
PLAYER = JAVA / "PlayerActivity.java"
VOD = JAVA / "VodPlayerActivity.java"
GRADLE = APP / "build.gradle.kts"

FAIL = JAVA / "PlaybackFailureMemory.java"
FAIL.write_text(r'''package tv.blofy.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import java.security.MessageDigest;
import java.util.Locale;

/** R11E stage 1: remembers negative route evidence so dead candidates are not retried in loops. */
final class PlaybackFailureMemory {
    private static final String PREFS = "blofy_r11e_failure_memory";
    private static final long TTL_404_MS = 10L * 60L * 1000L;
    private static final long TTL_403_MS = 2L * 60L * 1000L;
    private static final long TTL_TIMEOUT_MS = 45L * 1000L;
    private static final long TTL_OTHER_MS = 20L * 1000L;
    private PlaybackFailureMemory() {}

    static void failed(Context c, String provider, String family, String route,
                       String extension, String engine, String reason) {
        String k = key(provider, family, route, extension, engine);
        long now = System.currentTimeMillis();
        long ttl = ttl(reason);
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(k + ".until", now + ttl)
                .putString(k + ".reason", safe(reason))
                .apply();
        PlaybackDiagnostics.marker(c, "r11e-negative-route", family, "", extension,
                route, "engine=" + safe(engine) + " ttl_ms=" + ttl + " reason=" + safe(reason));
    }

    static boolean blocked(Context c, String provider, String family, String route,
                           String extension, String engine) {
        String k = key(provider, family, route, extension, engine);
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long until = p.getLong(k + ".until", 0L);
        if (until <= System.currentTimeMillis()) {
            if (until != 0L) p.edit().remove(k + ".until").remove(k + ".reason").apply();
            return false;
        }
        return true;
    }

    static boolean is404(String reason) {
        String r = safe(reason).toUpperCase(Locale.US);
        return r.contains("HTTP 404") || r.contains("HTTP_404") || r.contains("RESPONSE CODE: 404");
    }
    static boolean is403(String reason) {
        String r = safe(reason).toUpperCase(Locale.US);
        return r.contains("HTTP 403") || r.contains("HTTP_403") || r.contains("RESPONSE CODE: 403");
    }
    static boolean isTimeout(String reason) {
        String r = safe(reason).toUpperCase(Locale.US);
        return r.contains("TIMEOUT") || r.contains("TIMED OUT") || r.contains("ERROR_CODE_TIMEOUT");
    }

    private static long ttl(String reason) {
        if (is404(reason)) return TTL_404_MS;
        if (is403(reason)) return TTL_403_MS;
        if (isTimeout(reason)) return TTL_TIMEOUT_MS;
        return TTL_OTHER_MS;
    }

    private static String key(String provider, String family, String route, String ext, String engine) {
        String host = safe(provider);
        try { String h = Uri.parse(host).getHost(); if (h != null) host = h; } catch (Exception ignored) {}
        return digest(host + "|" + safe(family) + "|" + safe(route) + "|" + safe(ext) + "|" + safe(engine));
    }
    private static String digest(String value) {
        try {
            byte[] b = MessageDigest.getInstance("SHA-256").digest(safe(value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder s = new StringBuilder(24);
            for (int i = 0; i < Math.min(12, b.length); i++) s.append(String.format(Locale.US, "%02x", b[i]));
            return s.toString();
        } catch (Exception e) { return Integer.toHexString(safe(value).hashCode()); }
    }
    private static String safe(String v) { return v == null ? "" : v; }
}
''', encoding="utf-8")

n = NEG.read_text(encoding="utf-8")
if "filterFailures(Context context" not in n:
    anchor = "    static void proven(Context context, String providerUrl, String family,\n"
    helper = r'''    private static List<Candidate> filterFailures(Context context, String providerUrl,
                                                  String family, List<Candidate> input) {
        ArrayList<Candidate> out = new ArrayList<>();
        for (Candidate c : input) {
            if (!PlaybackFailureMemory.blocked(context, providerUrl, family,
                    c.route, c.extension, c.engine)) out.add(c);
        }
        return out.isEmpty() ? input : out;
    }

    static void failed(Context context, String providerUrl, String family,
                       String route, String extension, String engine, String reason) {
        PlaybackFailureMemory.failed(context, providerUrl, family, route, extension, engine, reason);
        stale(context, providerUrl, family, reason);
    }

    static boolean forbiddenHttpFailure(String reason) { return PlaybackFailureMemory.is403(reason); }
    static boolean notFoundHttpFailure(String reason) { return PlaybackFailureMemory.is404(reason); }
    static boolean timeoutFailure(String reason) { return PlaybackFailureMemory.isTimeout(reason); }

'''
    if anchor not in n: raise SystemExit("R11E1: negotiator proven anchor missing")
    n = n.replace(anchor, helper + anchor, 1)

for method, family in (("liveCandidates", "live"), ("vodCandidates", "vod")):
    start = n.find("static List<Candidate> " + method)
    if start < 0: raise SystemExit("R11E1: missing " + method)
    next_method = n.find("\n    static ", start + 10)
    end = next_method if next_method > 0 else len(n)
    block = n[start:end]
    if "filterFailures(context, providerUrl" not in block:
        idx = block.rfind("        return out;")
        if idx < 0: raise SystemExit("R11E1: return out missing in " + method)
        block = block[:idx] + f'        return filterFailures(context, providerUrl, "{family}", out);' + block[idx+len("        return out;"):]
        n = n[:start] + block + n[end:]
NEG.write_text(n, encoding="utf-8")

p = PLAYER.read_text(encoding="utf-8")
recover = "    private void recoverFromFailure(String reason) {\n"
if recover in p and "r11e-failure-recorded" not in p[p.find(recover):p.find(recover)+1400]:
    pos = p.find(recover) + len(recover)
    inject = '''        if (isLive() && !livePlaybackProven) {\n            PlaybackNegotiator.failed(this, url, "live", sourceVariant, extension,\n                    usingVlc ? "vlc" : "media3", reason);\n            PlaybackDiagnostics.marker(this, "r11e-failure-recorded", "live", id, extension,\n                    sourceVariant, "reason=" + valueOr(reason, ""));\n        }\n'''
    p = p[:pos] + inject + p[pos:]
manual = "    private void manualRetry() {\n"
if manual in p:
    pos = p.find(manual) + len(manual)
    if "r11e-manual-retry" not in p[pos:pos+800]:
        p = p[:pos] + '        PlaybackDiagnostics.marker(this, "r11e-manual-retry", kind, id, extension, sourceVariant, "reset-session");\n' + p[pos:]
PLAYER.write_text(p, encoding="utf-8")

v = VOD.read_text(encoding="utf-8")
for sig in ("    private void recover(String reason) {\n", "    private void recoverFromFailure(String reason) {\n"):
    if sig in v:
        start = v.find(sig)
        section = v[start:start+1800]
        if "r11e-vod-failure-recorded" not in section:
            pos = start + len(sig)
            inject = '''        PlaybackNegotiator.failed(this, resolvedUrl, "vod", sourceVariant, extension,\n                usingVlc ? "vlc" : "media3", reason);\n        PlaybackDiagnostics.marker(this, "r11e-vod-failure-recorded", kind, id, extension,\n                sourceVariant, "reason=" + (reason == null ? "" : reason));\n'''
            v = v[:pos] + inject + v[pos:]
        break
VOD.write_text(v, encoding="utf-8")

g = GRADLE.read_text(encoding="utf-8")
g = re.sub(r'versionCode\s*=\s*\d+', 'versionCode = 1000346', g, count=1)
g = re.sub(r'versionName\s*=\s*"[^"]*"', 'versionName = "v340-full-stability-r11e-stage1"', g, count=1)
GRADLE.write_text(g, encoding="utf-8")

checks = {
    FAIL: ["r11e-negative-route", "TTL_404_MS", "TTL_403_MS", "TTL_TIMEOUT_MS"],
    NEG: ["filterFailures(Context context", "PlaybackFailureMemory.blocked", "forbiddenHttpFailure"],
    PLAYER: ["r11e-failure-recorded"],
    GRADLE: ["versionCode = 1000346", "v340-full-stability-r11e-stage1"],
}
for path, markers in checks.items():
    text = path.read_text(encoding="utf-8")
    for marker in markers:
        if marker not in text: raise SystemExit(f"R11E1 invariant missing {path.name}: {marker}")

print("R11E stage1 applied: negative route memory + 403/404/timeout classification + candidate filtering")

# Stage 2 is chained here so the existing signed CI gate validates both stages
# without weakening the proven R11E stage1 artifact/signature checks.
runpy.run_path(str(ROOT / "tools/apply_v340_r11e_stage2_stability_ui.py"), run_name="__main__")
