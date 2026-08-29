#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "BLOFY-ANDROID-2026/app"
JAVA = APP / "src/main/java/tv/blofy/player"
PLAYER = JAVA / "PlayerActivity.java"
VOD = JAVA / "VodPlayerActivity.java"
POLICY = JAVA / "PlaybackPolicy.java"
GRADLE = APP / "build.gradle.kts"

# ---------------------------------------------------------------------------
# 1) Adaptive per-provider/per-family playback negotiator.
# ---------------------------------------------------------------------------
NEG = JAVA / "PlaybackNegotiator.java"
NEG.write_text(r'''package tv.blofy.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * BLOFY R11 adaptive playback policy.
 *
 * Learns only from proven playback (first frame) and keeps decisions separated
 * by provider + content family. A single dead item never poisons a whole host.
 */
final class PlaybackNegotiator {
    private static final String PREFS = "blofy_r11_playback_negotiator";
    private static final long STALE_MS = 14L * 24L * 60L * 60L * 1000L;

    static final class Candidate {
        final String route;
        final String extension;
        final String engine;
        Candidate(String route, String extension, String engine) {
            this.route = route; this.extension = extension; this.engine = engine;
        }
        String label() { return route + "+" + extension + "+" + engine; }
    }

    static final class Profile {
        final String route;
        final String extension;
        final String engine;
        final int confidence;
        final long verifiedAt;
        Profile(String route, String extension, String engine, int confidence, long verifiedAt) {
            this.route = route; this.extension = extension; this.engine = engine;
            this.confidence = confidence; this.verifiedAt = verifiedAt;
        }
        boolean fresh() { return verifiedAt > 0 && System.currentTimeMillis() - verifiedAt < STALE_MS; }
    }

    private PlaybackNegotiator() {}

    static List<Candidate> liveCandidates(Context context, String providerUrl, String itemExtension) {
        Profile profile = load(context, providerUrl, "live");
        String base = normalize(itemExtension, "ts");
        String alternate = "m3u8".equals(base) ? "ts" : "m3u8";
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        ArrayList<Candidate> out = new ArrayList<>();
        if (profile.fresh()) add(out, seen, profile.route, profile.extension, profile.engine);
        // Provider-declared/current extension first, then alternate stream family.
        add(out, seen, "canonical", base, "media3");
        add(out, seen, "canonical", alternate, "media3");
        add(out, seen, "direct", base, "media3");
        add(out, seen, "no-extension", base, "media3");
        // VLC is a bounded compatibility engine, not the default path.
        add(out, seen, "canonical", base, "vlc");
        add(out, seen, "canonical", alternate, "vlc");
        return out;
    }

    static List<Candidate> vodCandidates(Context context, String providerUrl, String itemExtension, boolean ultraHd) {
        Profile profile = load(context, providerUrl, "vod");
        String base = normalize(itemExtension, "mp4");
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        ArrayList<Candidate> out = new ArrayList<>();
        if (profile.fresh()) add(out, seen, profile.route, base, profile.engine);
        // Canonical is intentionally first. R10 field tests proved that learned
        // direct/no-extension retries could add 10-15 seconds before canonical.
        add(out, seen, "canonical", base, ultraHd ? "vlc" : "media3");
        add(out, seen, "canonical", base, ultraHd ? "media3" : "vlc");
        add(out, seen, "direct", base, "media3");
        add(out, seen, "no-extension", base, "media3");
        return out;
    }

    static void proven(Context context, String providerUrl, String family,
                       String route, String extension, String engine, long firstFrameMs) {
        String key = key(providerUrl, family);
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int previous = p.getInt(key + ".confidence", 0);
        int next = Math.min(100, Math.max(50, previous + (firstFrameMs > 0 && firstFrameMs <= 3000 ? 15 : 8)));
        p.edit().putString(key + ".route", safe(route))
                .putString(key + ".extension", normalize(extension, "live".equals(family) ? "ts" : "mp4"))
                .putString(key + ".engine", safe(engine).isEmpty() ? "media3" : engine)
                .putInt(key + ".confidence", next)
                .putLong(key + ".verified_at", System.currentTimeMillis()).apply();
        PlaybackDiagnostics.marker(context, "r11-profile-proven", family, "", extension,
                route, "engine=" + engine + " confidence=" + next + " first_frame_ms=" + firstFrameMs);
    }

    static void stale(Context context, String providerUrl, String family, String reason) {
        String key = key(providerUrl, family);
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int current = p.getInt(key + ".confidence", 0);
        // Negative evidence only reduces confidence. It never erases a proven
        // provider profile because one channel/movie may be dead.
        p.edit().putInt(key + ".confidence", Math.max(0, current - 10)).apply();
        PlaybackDiagnostics.marker(context, "r11-profile-soft-failure", family, "", "",
                "self-heal", safe(reason));
    }

    static Profile load(Context context, String providerUrl, String family) {
        String key = key(providerUrl, family);
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new Profile(p.getString(key + ".route", "canonical"),
                p.getString(key + ".extension", "live".equals(family) ? "ts" : "mp4"),
                p.getString(key + ".engine", "media3"), p.getInt(key + ".confidence", 0),
                p.getLong(key + ".verified_at", 0L));
    }

    static String containerHint(String url, String extension) {
        String e = normalize(extension, "");
        if (!e.isEmpty()) return e;
        try {
            String path = Uri.parse(safe(url)).getLastPathSegment();
            if (path != null && path.contains(".")) return normalize(path.substring(path.lastIndexOf('.') + 1), "");
        } catch (Exception ignored) {}
        return "";
    }

    static boolean hardHttpFailure(String reason) {
        String r = safe(reason).toUpperCase(Locale.US);
        return r.contains("HTTP 400") || r.contains("HTTP 404") || r.contains("HTTP 410") || r.contains("HTTP 551");
    }

    private static void add(List<Candidate> out, Set<String> seen, String route, String ext, String engine) {
        String key = safe(route) + "|" + normalize(ext, "") + "|" + safe(engine);
        if (seen.add(key)) out.add(new Candidate(route, normalize(ext, ""), engine));
    }

    private static String normalize(String value, String fallback) {
        String v = safe(value).trim().toLowerCase(Locale.US).replace(".", "");
        if ("hls".equals(v)) v = "m3u8";
        if ("mpegts".equals(v) || "mpeg-ts".equals(v)) v = "ts";
        return v.isEmpty() ? fallback : v;
    }

    private static String key(String providerUrl, String family) {
        String host = safe(providerUrl);
        try { host = Uri.parse(host).getHost(); } catch (Exception ignored) {}
        return digest(host + "|" + safe(family));
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

# ---------------------------------------------------------------------------
# 2) Live: post-first-frame errors are runtime recovery, not startup failure.
#    HTTP 404 on TS fast-falls to HLS/M3U8 before direct/no-extension retries.
# ---------------------------------------------------------------------------
p = PLAYER.read_text(encoding="utf-8")
if "private int postStartRecoveryCount;" not in p:
    p = p.replace("    private boolean lifecycleStarted;\n",
                  "    private boolean lifecycleStarted;\n    private int postStartRecoveryCount;\n", 1)

# Reset post-start recovery on source/channel changes and manual retry.
p = p.replace("        vlcAttempted = false;\n        if (usingVlc) {",
              "        vlcAttempted = false;\n        postStartRecoveryCount = 0;\n        if (usingVlc) {", 1)
p = p.replace("        recoveryStep = preferredRecoveryStep();\n        vlcAttempted = false;\n        sourceVariant = \"canonical\";",
              "        recoveryStep = preferredRecoveryStep();\n        vlcAttempted = false;\n        postStartRecoveryCount = 0;\n        sourceVariant = \"canonical\";", 1)

# Mark proven live profile at first frame.
old_ff = '''        Log.i(TAG, "first-frame kind=" + kind + " ext=" + extension + " ms=" + firstFrameMs
                + " transport=" + activeTransportName());
        playbackHandler.removeCallbacks(markPlaybackStable);'''
new_ff = '''        Log.i(TAG, "first-frame kind=" + kind + " ext=" + extension + " ms=" + firstFrameMs
                + " transport=" + activeTransportName());
        if (isLive()) PlaybackNegotiator.proven(this, url, "live", sourceVariant, extension,
                usingVlc ? "vlc" : "media3", firstFrameMs);
        playbackHandler.removeCallbacks(markPlaybackStable);'''
if old_ff in p: p = p.replace(old_ff, new_ff, 1)

# Fast format fallback before the old direct-source fallback.
needle = '''        // A fast HTTP/connection error can be specific to the signed relay.
        // Resolve the direct source once; do not add TS/HLS and Cronet retries
        // behind it. Slow startup and decoder failures go straight to LibVLC.
        if (PlaybackPolicy.isNetworkFailure(reason)'''
insert = '''        // R11: a hard TS 404/410/551 means this provider/channel may publish
        // HLS instead. Switch stream family immediately instead of waiting through
        // multiple TS retries. The successful first frame will persist the choice.
        if (isLive() && PlaybackNegotiator.hardHttpFailure(reason) && "canonical".equals(sourceVariant)
                && !id.isEmpty()) {
            String alternate = PlaybackPolicy.alternateLiveExtension(extension);
            if (alternate != null && !alternate.isEmpty() && !alternate.equals(extension)) {
                PlaybackDiagnostics.marker(this, "r11-live-format-fallback", "live", id, extension,
                        "canonical", "reason=" + reason + " next=" + alternate);
                releasePlayer();
                extension = alternate;
                sourceVariant = "canonical";
                url = null;
                recoveryStep = 0;
                resolvePlaybackLink();
                return;
            }
        }

        // A fast HTTP/connection error can be specific to the signed relay.
        // Resolve the direct source once; do not add TS/HLS and Cronet retries
        // behind it. Slow startup and decoder failures go straight to LibVLC.
        if (PlaybackPolicy.isNetworkFailure(reason)'''
if needle in p: p = p.replace(needle, insert, 1)

# Runtime error after first frame: one silent restart, then alternate format; do not
# paint a false startup failure over a stream that already proved playback.
old_err = '''        if (isLive() && error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && player != null) {'''
new_err = '''        if (isLive() && firstFrameRendered && player != null
                && error.errorCode != PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW
                && postStartRecoveryCount < 2) {
            postStartRecoveryCount++;
            String reason = playbackErrorReason(error);
            PlaybackDiagnostics.marker(this, "r11-live-post-start-recovery", "live", id, extension,
                    sourceVariant, "attempt=" + postStartRecoveryCount + " reason=" + reason);
            PlaybackNegotiator.stale(this, url, "live", reason);
            try {
                firstFrameRendered = false;
                if (postStartRecoveryCount == 1) {
                    player.prepare(); player.play(); schedulePlaybackTimeout();
                } else {
                    String alternate = PlaybackPolicy.alternateLiveExtension(extension);
                    releasePlayer(); extension = alternate; sourceVariant = "canonical"; url = null;
                    resolvePlaybackLink();
                }
                return;
            } catch (Exception ignored) {}
        }
        if (isLive() && error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && player != null) {'''
if old_err not in p:
    raise SystemExit("r11: PlayerActivity onPlayerError anchor missing")
p = p.replace(old_err, new_err, 1)

PLAYER.write_text(p, encoding="utf-8")

# ---------------------------------------------------------------------------
# 3) VOD: canonical-first, full diagnostics, proven-route learning only after
#    first frame, and 4K can prefer VLC without poisoning normal VOD.
# ---------------------------------------------------------------------------
v = VOD.read_text(encoding="utf-8")
# Add timing field if absent.
if "private long r11AttemptStartedMs;" not in v:
    v = v.replace("    private int vlcGeneration;\n",
                  "    private int vlcGeneration;\n    private long r11AttemptStartedMs;\n", 1)

# Insert diagnostics at resolve() entry.
resolve_anchor = '''    private void resolve() {
        if (resolving || id.isEmpty()) return;'''
if resolve_anchor in v:
    v = v.replace(resolve_anchor, '''    private void resolve() {
        if (resolving || id.isEmpty()) return;
        r11AttemptStartedMs = android.os.SystemClock.elapsedRealtime();
        PlaybackDiagnostics.marker(this, "r11-vod-resolve-start", kind, id, extension,
                sourceVariant, "uhd=" + ultraHd());''', 1)

# The older route-learning patch may try learned direct before canonical. Force
# every new VOD attempt to start canonical; fallbacks remain bounded afterwards.
v = v.replace('sourceVariant = ServerPlaybackProfile.load(this, reference, kind).preferredRoute;',
              'sourceVariant = "canonical";')
v = v.replace('sourceVariant = profile.route;', 'sourceVariant = "canonical";')

# First-frame proven marker: support either common first-frame callback shape.
ff_marker = 'firstFrame = true;\n        main.removeCallbacks(startupTimeout);'
if ff_marker in v and 'r11-vod-first-frame' not in v:
    v = v.replace(ff_marker, '''firstFrame = true;
        main.removeCallbacks(startupTimeout);
        long r11FirstFrameMs = r11AttemptStartedMs == 0 ? -1
                : android.os.SystemClock.elapsedRealtime() - r11AttemptStartedMs;
        PlaybackDiagnostics.marker(this, "r11-vod-first-frame", kind, id, extension,
                sourceVariant, "engine=" + (usingVlc ? "vlc" : "media3") + " ms=" + r11FirstFrameMs);
        PlaybackNegotiator.proven(this, resolvedUrl, "vod", sourceVariant, extension,
                usingVlc ? "vlc" : "media3", r11FirstFrameMs);''', 1)

# Add diagnostics to recovery entry.
recover_anchor = '    private void recover(String reason) {\n'
if recover_anchor in v and 'r11-vod-recover' not in v:
    v = v.replace(recover_anchor, '''    private void recover(String reason) {
        PlaybackDiagnostics.marker(this, "r11-vod-recover", kind, id, extension,
                sourceVariant, "engine=" + (usingVlc ? "vlc" : "media3") + " reason=" + reason);
        PlaybackNegotiator.stale(this, resolvedUrl, "vod", reason);
''', 1)

VOD.write_text(v, encoding="utf-8")

# ---------------------------------------------------------------------------
# 4) Playback policy: bounded budgets. We keep them short enough to avoid the
#    15-30s retry chains seen in field tests, while allowing UHD a larger budget.
# ---------------------------------------------------------------------------
pol = POLICY.read_text(encoding="utf-8")
for name, value in [
    ("INITIAL_STARTUP_TIMEOUT_MS", "4_000"),
    ("RETRY_STARTUP_TIMEOUT_MS", "2_250"),
    ("VOD_STARTUP_TIMEOUT_MS", "5_000"),
    ("UHD_VOD_STARTUP_TIMEOUT_MS", "7_500"),
    ("VLC_STARTUP_TIMEOUT_MS", "4_500"),
    ("UHD_VLC_STARTUP_TIMEOUT_MS", "7_000"),
    ("PREVIEW_STARTUP_TIMEOUT_MS", "3_000"),
]:
    pol, n = re.subn(r'(static final int ' + re.escape(name) + r' = )[^;]+;', r'\g<1>' + value + ';', pol, count=1)
    if n != 1: raise SystemExit("r11: timeout marker missing " + name)
POLICY.write_text(pol, encoding="utf-8")

# ---------------------------------------------------------------------------
# 5) Media3 modules required by the R11 registry. HLS/DASH already exist in the
#    project; RTSP is added explicitly. Keep version aligned with the project.
# ---------------------------------------------------------------------------
g = GRADLE.read_text(encoding="utf-8")
if 'media3-exoplayer-rtsp' not in g:
    anchor = '    implementation("androidx.media3:media3-exoplayer-dash:$media3Version")\n'
    if anchor not in g: raise SystemExit("r11: dash dependency anchor missing")
    g = g.replace(anchor, anchor + '    implementation("androidx.media3:media3-exoplayer-rtsp:$media3Version")\n', 1)
# R11 release identity.
g, n1 = re.subn(r'versionCode\s*=\s*\d+', 'versionCode = 1000344', g, count=1)
g, n2 = re.subn(r'versionName\s*=\s*"[^"]*"', 'versionName = "v340-full-stability-r11"', g, count=1)
if n1 != 1 or n2 != 1: raise SystemExit("r11: version stamp failed")
GRADLE.write_text(g, encoding="utf-8")

# ---------------------------------------------------------------------------
# 6) Baseline profile seed for the hottest user journeys. This is consumed by
#    ART profile installation on supported Android builds and is safe on older OS.
# ---------------------------------------------------------------------------
profile_dir = APP / "src/main"
profile_dir.mkdir(parents=True, exist_ok=True)
(profile_dir / "baseline-prof.txt").write_text('''HSPLtv/blofy/player/MainActivity;->**(**)**\nHSPLtv/blofy/player/PlayerActivity;->**(**)**\nHSPLtv/blofy/player/VodPlayerActivity;->**(**)**\nHSPLtv/blofy/player/LivePreviewController;->**(**)**\nHSPLtv/blofy/player/CatalogDatabase;->**(**)**\nHSPLtv/blofy/player/PlaybackNegotiator;->**(**)**\n''', encoding="utf-8")

# Hard invariants: if an earlier reconstruction changes, fail CI instead of
# silently shipping a half-integrated R11.
checks = {
    NEG: ["liveCandidates", "vodCandidates", "r11-profile-proven", "hardHttpFailure"],
    PLAYER: ["r11-live-format-fallback", "r11-live-post-start-recovery", "PlaybackNegotiator.proven"],
    VOD: ["r11-vod-resolve-start", "r11-vod-recover", "sourceVariant = \"canonical\""],
    GRADLE: ["media3-exoplayer-rtsp", "versionCode = 1000344", "v340-full-stability-r11"],
    profile_dir / "baseline-prof.txt": ["PlayerActivity", "VodPlayerActivity", "PlaybackNegotiator"],
}
for path, markers in checks.items():
    text = path.read_text(encoding="utf-8")
    for marker in markers:
        if marker not in text: raise SystemExit(f"r11 invariant missing {path.name}: {marker}")

print("R11 applied: adaptive route/format negotiation + live post-start recovery + canonical-first VOD + diagnostics + RTSP + baseline profile")
