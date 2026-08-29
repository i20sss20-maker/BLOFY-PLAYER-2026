#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player"
PLAYER = JAVA / "PlayerActivity.java"
VOD = JAVA / "VodPlayerActivity.java"
GRADLE = ROOT / "BLOFY-ANDROID-2026/app/build.gradle.kts"

p = PLAYER.read_text(encoding="utf-8")

# Ensure post-start state is tracked.
if "private int postStartRecoveryCount;" not in p:
    p = p.replace("    private boolean lifecycleStarted;\n",
                  "    private boolean lifecycleStarted;\n    private int postStartRecoveryCount;\n", 1)

# First-frame is definitive startup success: cancel startup callbacks and persist proven route.
if "PlaybackNegotiator.proven(this, url, \"live\"" not in p:
    anchor = '''        Log.i(TAG, "first-frame kind=" + kind + " ext=" + extension + " ms=" + firstFrameMs
                + " transport=" + activeTransportName());'''
    if anchor in p:
        p = p.replace(anchor, anchor + '''
        if (isLive()) {
            playbackHandler.removeCallbacks(playbackTimeout);
            PlaybackNegotiator.proven(this, url, "live", sourceVariant, extension,
                    usingVlc ? "vlc" : "media3", firstFrameMs);
        }''', 1)

# Fast TS<->HLS switch on hard provider errors, before direct/no-extension retries.
if "r11-live-format-fallback" not in p:
    anchor = '''        Log.w(TAG, "bounded-recovery reason=" + reason + " ext=" + extension
                + " variant=" + sourceVariant + " uhd=" + isUltraHd());
'''
    block = '''
        if (isLive() && PlaybackNegotiator.hardHttpFailure(reason)
                && "canonical".equals(sourceVariant) && !id.isEmpty()) {
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
'''
    if anchor in p:
        p = p.replace(anchor, anchor + block, 1)

# Runtime errors after first frame are not startup failures. Recover silently once,
# then switch TS/HLS once. Stale callbacks are bounded by existing generation guards.
if "r11-live-post-start-recovery" not in p:
    anchor = '''        if (isLive() && error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && player != null) {'''
    block = '''        if (isLive() && firstFrameRendered && player != null
                && error.errorCode != PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW
                && postStartRecoveryCount < 2) {
            postStartRecoveryCount++;
            String runtimeReason = playbackErrorReason(error);
            PlaybackDiagnostics.marker(this, "r11-live-post-start-recovery", "live", id, extension,
                    sourceVariant, "attempt=" + postStartRecoveryCount + " reason=" + runtimeReason);
            PlaybackNegotiator.stale(this, url, "live", runtimeReason);
            try {
                firstFrameRendered = false;
                if (postStartRecoveryCount == 1) {
                    player.prepare();
                    player.play();
                    schedulePlaybackTimeout();
                } else {
                    String alternate = PlaybackPolicy.alternateLiveExtension(extension);
                    releasePlayer();
                    extension = alternate;
                    sourceVariant = "canonical";
                    url = null;
                    resolvePlaybackLink();
                }
                return;
            } catch (Exception ignored) { }
        }
'''
    if anchor in p:
        p = p.replace(anchor, block + anchor, 1)

# Reset post-start recovery on channel switches/manual retries where stable anchors exist.
p = p.replace("        vlcAttempted = false;\n        if (usingVlc) {",
              "        vlcAttempted = false;\n        postStartRecoveryCount = 0;\n        if (usingVlc) {", 1)
p = p.replace("        recoveryStep = preferredRecoveryStep();\n        vlcAttempted = false;\n        sourceVariant = \"canonical\";",
              "        recoveryStep = preferredRecoveryStep();\n        vlcAttempted = false;\n        postStartRecoveryCount = 0;\n        sourceVariant = \"canonical\";", 1)
PLAYER.write_text(p, encoding="utf-8")

v = VOD.read_text(encoding="utf-8")
if "private long r11AttemptStartedMs;" not in v:
    v = v.replace("    private int vlcGeneration;\n",
                  "    private int vlcGeneration;\n    private long r11AttemptStartedMs;\n", 1)
if "r11-vod-resolve-start" not in v:
    anchor = '''    private void resolve() {
        if (resolving || id.isEmpty()) return;'''
    if anchor in v:
        v = v.replace(anchor, '''    private void resolve() {
        if (resolving || id.isEmpty()) return;
        r11AttemptStartedMs = android.os.SystemClock.elapsedRealtime();
        PlaybackDiagnostics.marker(this, "r11-vod-resolve-start", kind, id, extension,
                sourceVariant, "uhd=" + ultraHd());''', 1)
# Force clean canonical first attempt; existing fallbacks remain available.
v = v.replace('sourceVariant = ServerPlaybackProfile.load(this, reference, kind).preferredRoute;',
              'sourceVariant = "canonical";')
v = v.replace('sourceVariant = profile.route;', 'sourceVariant = "canonical";')
if "r11-vod-first-frame" not in v:
    anchor = '''        firstFrame = true;
        main.removeCallbacks(startupTimeout);'''
    if anchor in v:
        v = v.replace(anchor, anchor + '''
        long r11FirstFrameMs = r11AttemptStartedMs == 0 ? -1
                : android.os.SystemClock.elapsedRealtime() - r11AttemptStartedMs;
        PlaybackDiagnostics.marker(this, "r11-vod-first-frame", kind, id, extension,
                sourceVariant, "engine=" + (usingVlc ? "vlc" : "media3") + " ms=" + r11FirstFrameMs);
        PlaybackNegotiator.proven(this, resolvedUrl, "vod", sourceVariant, extension,
                usingVlc ? "vlc" : "media3", r11FirstFrameMs);''', 1)
if "r11-vod-recover" not in v:
    # tolerate both recover signatures seen in the reconstructed stack
    m = re.search(r'(\n    private void recover\(String reason\) \{\n)', v)
    if m:
        pos = m.end()
        v = v[:pos] + '''        PlaybackDiagnostics.marker(this, "r11-vod-recover", kind, id, extension,
                sourceVariant, "reason=" + (reason == null ? "" : reason));
''' + v[pos:]
VOD.write_text(v, encoding="utf-8")

# Release identity is deterministic after all reconstruction layers.
g = GRADLE.read_text(encoding="utf-8")
g, c1 = re.subn(r'versionCode\s*=\s*\d+', 'versionCode = 1000344', g, count=1)
g, c2 = re.subn(r'versionName\s*=\s*"[^"]*"', 'versionName = "v340-full-stability-r11"', g, count=1)
if 'media3-exoplayer-rtsp' not in g:
    dash = '    implementation("androidx.media3:media3-exoplayer-dash:$media3Version")\n'
    if dash in g:
        g = g.replace(dash, dash + '    implementation("androidx.media3:media3-exoplayer-rtsp:$media3Version")\n', 1)
GRADLE.write_text(g, encoding="utf-8")

checks = {
    PLAYER: ["r11-live-format-fallback", "r11-live-post-start-recovery", "PlaybackNegotiator.proven"],
    VOD: ["r11-vod-resolve-start", "r11-vod-first-frame", 'sourceVariant = "canonical"'],
    GRADLE: ["media3-exoplayer-rtsp", "versionCode = 1000344", "v340-full-stability-r11"],
}
for path, markers in checks.items():
    text = path.read_text(encoding="utf-8")
    for marker in markers:
        if marker not in text:
            raise SystemExit(f"R11B invariant missing {path.name}: {marker}")
print("R11B compatibility layer applied: Live TS/HLS negotiation + post-first-frame recovery + canonical-first VOD diagnostics")
