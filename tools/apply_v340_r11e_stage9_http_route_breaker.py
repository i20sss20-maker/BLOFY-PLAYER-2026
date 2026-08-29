#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "BLOFY-ANDROID-2026/app"
JAVA = APP / "src/main/java/tv/blofy/player"
FAIL = JAVA / "PlaybackFailureMemory.java"
NEG = JAVA / "PlaybackNegotiator.java"
GRADLE = APP / "build.gradle.kts"

# Stage 1 deliberately keyed negative memory by engine. Decoder/time-out evidence can stay
# engine-scoped, but HTTP source failures cannot be repaired by changing Media3 <-> VLC.
# Stage 9 adds an engine-independent route circuit breaker for hard HTTP failures.
f = FAIL.read_text(encoding="utf-8")

# Broaden transport classification for real provider behaviour seen in IPTV: throttling and
# transient upstream/gateway failures should move to another candidate instead of retrying the
# identical URL through another decoder engine.
if "TTL_429_MS" not in f:
    f = f.replace(
        "    private static final long TTL_OTHER_MS = 20L * 1000L;\n",
        "    private static final long TTL_429_MS = 60L * 1000L;\n"
        "    private static final long TTL_5XX_MS = 30L * 1000L;\n"
        "    private static final long TTL_OTHER_MS = 20L * 1000L;\n",
        1,
    )

if "static boolean is429(" not in f:
    anchor = '''    static boolean isTimeout(String reason) {
        String r = safe(reason).toUpperCase(Locale.US);
        return r.contains("TIMEOUT") || r.contains("TIMED OUT") || r.contains("ERROR_CODE_TIMEOUT");
    }
'''
    if anchor not in f:
        raise SystemExit("R11E9: timeout classification anchor missing")
    helpers = anchor + '''    static boolean is429(String reason) {
        String r = safe(reason).toUpperCase(Locale.US);
        return r.contains("HTTP 429") || r.contains("HTTP_429") || r.contains("RESPONSE CODE: 429");
    }
    static boolean is5xx(String reason) {
        String r = safe(reason).toUpperCase(Locale.US);
        return r.contains("HTTP 500") || r.contains("HTTP 501") || r.contains("HTTP 502")
                || r.contains("HTTP 503") || r.contains("HTTP 504") || r.contains("HTTP 505")
                || r.contains("RESPONSE CODE: 500") || r.contains("RESPONSE CODE: 502")
                || r.contains("RESPONSE CODE: 503") || r.contains("RESPONSE CODE: 504");
    }
'''
    f = f.replace(anchor, helpers, 1)

if "if (is429(reason)) return TTL_429_MS;" not in f:
    old = '''        if (isTimeout(reason)) return TTL_TIMEOUT_MS;
        return TTL_OTHER_MS;'''
    new = '''        if (is429(reason)) return TTL_429_MS;
        if (is5xx(reason)) return TTL_5XX_MS;
        if (isTimeout(reason)) return TTL_TIMEOUT_MS;
        return TTL_OTHER_MS;'''
    if old not in f:
        raise SystemExit("R11E9: TTL anchor missing")
    f = f.replace(old, new, 1)

if "private static String routeKey(" not in f:
    anchor = "    private static String key(String provider, String family, String route, String ext, String engine) {\n"
    if anchor not in f:
        raise SystemExit("R11E9: failure-memory key anchor missing")
    helper = r'''    private static String routeKey(String provider, String family, String route, String ext) {
        String host = safe(provider);
        try { String h = Uri.parse(host).getHost(); if (h != null) host = h; } catch (Exception ignored) {}
        return digest("route|" + host + "|" + safe(family) + "|" + safe(route) + "|" + safe(ext));
    }

'''
    f = f.replace(anchor, helper + anchor, 1)

if 'putLong(rk + ".until"' not in f:
    old = '''        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(k + ".until", now + ttl)
                .putString(k + ".reason", safe(reason))
                .apply();'''
    if old not in f:
        raise SystemExit("R11E9: failure write anchor missing")
    new = '''        SharedPreferences.Editor edit = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(k + ".until", now + ttl)
                .putString(k + ".reason", safe(reason));
        // HTTP source failures are independent of decoder engine. Skip the same dead/throttled
        // route across Media3/VLC so recovery must move to a genuinely different candidate.
        if (is403(reason) || is404(reason) || is429(reason) || is5xx(reason)) {
            String rk = routeKey(provider, family, route, extension);
            edit.putLong(rk + ".until", now + ttl)
                    .putString(rk + ".reason", safe(reason));
        }
        edit.apply();'''
    f = f.replace(old, new, 1)

if "static boolean routeBlocked(" not in f:
    anchor = "    static boolean blocked(Context c, String provider, String family, String route,\n"
    if anchor not in f:
        raise SystemExit("R11E9: blocked anchor missing")
    helper = r'''    static boolean routeBlocked(Context c, String provider, String family,
                                String route, String extension) {
        String k = routeKey(provider, family, route, extension);
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long until = p.getLong(k + ".until", 0L);
        if (until <= System.currentTimeMillis()) {
            if (until != 0L) p.edit().remove(k + ".until").remove(k + ".reason").apply();
            return false;
        }
        return true;
    }

'''
    f = f.replace(anchor, helper + anchor, 1)

FAIL.write_text(f, encoding="utf-8")

n = NEG.read_text(encoding="utf-8")
needle = '''            if (!PlaybackFailureMemory.blocked(context, providerUrl, family,
                    c.route, c.extension, c.engine)) out.add(c);'''
if "PlaybackFailureMemory.routeBlocked(context" not in n:
    if needle not in n:
        raise SystemExit("R11E9: candidate filter anchor missing")
    repl = '''            if (PlaybackFailureMemory.routeBlocked(context, providerUrl, family,
                    c.route, c.extension)) {
                PlaybackDiagnostics.marker(context, "r11e9-http-route-skipped", family, "",
                        c.extension, c.route, "engine=" + c.engine + " hard-http-memory");
                continue;
            }
            if (!PlaybackFailureMemory.blocked(context, providerUrl, family,
                    c.route, c.extension, c.engine)) out.add(c);'''
    n = n.replace(needle, repl, 1)

if "static boolean hardHttpRouteFailure(" not in n:
    anchor = "    static boolean forbiddenHttpFailure(String reason) { return PlaybackFailureMemory.is403(reason); }\n"
    if anchor not in n:
        raise SystemExit("R11E9: HTTP helper anchor missing")
    n = n.replace(anchor, anchor +
        "    static boolean hardHttpRouteFailure(String reason) { return PlaybackFailureMemory.is403(reason) || PlaybackFailureMemory.is404(reason) || PlaybackFailureMemory.is429(reason) || PlaybackFailureMemory.is5xx(reason); }\n", 1)

NEG.write_text(n, encoding="utf-8")

g = GRADLE.read_text(encoding="utf-8")
g = re.sub(r'versionCode\s*=\s*\d+', 'versionCode = 1000354', g, count=1)
g = re.sub(r'versionName\s*=\s*"[^"]*"', 'versionName = "v340-full-stability-r11e-stage9"', g, count=1)
GRADLE.write_text(g, encoding="utf-8")

checks = {
    FAIL: ["routeKey(", "routeBlocked(", 'putLong(rk + ".until"', "is429(reason)", "is5xx(reason)", "TTL_429_MS", "TTL_5XX_MS"],
    NEG: ["r11e9-http-route-skipped", "PlaybackFailureMemory.routeBlocked(context", "hardHttpRouteFailure"],
    GRADLE: ["versionCode = 1000354", "v340-full-stability-r11e-stage9"],
}
for path, markers in checks.items():
    text = path.read_text(encoding="utf-8")
    for marker in markers:
        if marker not in text:
            raise SystemExit(f"R11E9 invariant missing {path.name}: {marker}")

print("R11E stage9 applied: engine-independent 403/404/429/5xx route breaker + no pointless decoder retry on dead URLs")
