#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "BLOFY-ANDROID-2026/app"
JAVA = APP / "src/main/java/tv/blofy/player"
PLAYER = JAVA / "PlayerActivity.java"
OVERLAY = JAVA / "LiveChannelOverlay.java"
GRADLE = APP / "build.gradle.kts"

p = PLAYER.read_text(encoding="utf-8")

# ---------------------------------------------------------------------------
# 1) Hard playback transaction boundary.
#    Every source change invalidates old resolve/recovery callbacks while allowing
#    warm Media3 reuse for live channel switches.
# ---------------------------------------------------------------------------
if "private int playbackTransaction;" not in p:
    anchor = "    private int resolveGeneration;\n"
    if anchor not in p:
        raise SystemExit("R11E2: resolveGeneration anchor missing")
    p = p.replace(anchor, anchor + "    private int playbackTransaction;\n", 1)

if "private int beginPlaybackTransaction(" not in p:
    anchor = "    private void cancelResolve(boolean invalidateGeneration) {\n"
    if anchor not in p:
        raise SystemExit("R11E2: cancelResolve anchor missing")
    helper = r'''    private int beginPlaybackTransaction(boolean preserveLivePlayer, String reason) {
        int token = ++playbackTransaction;
        playbackHandler.removeCallbacks(playbackTimeout);
        playbackHandler.removeCallbacks(markPlaybackStable);
        playbackHandler.removeCallbacks(hideTitle);
        cancelResolve(true);
        PlaybackPreloadManager.clear();
        warmLiveSwitchPending = false;
        firstFrameRendered = false;
        if (!preserveLivePlayer) {
            releaseMedia3Player();
            releaseVlcPlayer();
            usingVlc = false;
            playbackStartedAtMs = 0L;
        }
        PlaybackDiagnostics.marker(this, "r11e2-transaction", kind, id, extension,
                sourceVariant, "token=" + token + " preserve=" + preserveLivePlayer + " reason=" + valueOr(reason, ""));
        return token;
    }

'''
    p = p.replace(anchor, helper + anchor, 1)

# Resolve callbacks must belong to the active playback transaction as well as the
# resolve generation. This is the hard guard against stale session callbacks.
if "int transactionToken = playbackTransaction;" not in p:
    sig = "    private void resolvePlaybackLink() {\n        int token = ++resolveGeneration;\n"
    if sig not in p:
        raise SystemExit("R11E2: resolve method anchor missing")
    p = p.replace(sig, sig + "        int transactionToken = playbackTransaction;\n", 1)
    p = p.replace("if (token != resolveGeneration || isFinishing() || isDestroyed()) return;",
                  "if (token != resolveGeneration || transactionToken != playbackTransaction || isFinishing() || isDestroyed()) return;")

# Live channel switch: cancel the old session work, but preserve Media3 so the
# source can be replaced without a black teardown/recreate cycle.
if "r11e2-live-warm-boundary" not in p:
    sig = "    private void switchLiveChannel(BlofyModels.Media media) {\n        if (!isLive() || media == null || media.id.equals(id)) return;\n"
    if sig not in p:
        raise SystemExit("R11E2: switchLiveChannel anchor missing")
    inject = sig + '''        boolean preserveWarmMedia3 = player != null && !usingVlc;
        beginPlaybackTransaction(preserveWarmMedia3, "live-channel-switch");
        PlaybackDiagnostics.marker(this, "r11e2-live-warm-boundary", "live", media.id,
                media.extension, "switch", "preserve=" + preserveWarmMedia3);
'''
    p = p.replace(sig, inject, 1)

# Manual retry is a clean new transaction.
if "beginPlaybackTransaction(false, \"manual-retry\")" not in p:
    sig = "    private void manualRetry() {\n"
    if sig not in p:
        raise SystemExit("R11E2: manualRetry anchor missing")
    p = p.replace(sig, sig + "        beginPlaybackTransaction(false, \"manual-retry\");\n", 1)

# ---------------------------------------------------------------------------
# 2) Persistent live playback while channel drawer/overlay is visible.
#    Opening the drawer never pauses/releases/reprepares the stream.
# ---------------------------------------------------------------------------
if "private void ensureLivePlaybackContinues()" not in p:
    anchor = "    private void requestPlaybackFocus() {\n"
    if anchor not in p:
        raise SystemExit("R11E2: requestPlaybackFocus anchor missing")
    helper = r'''    private void ensureLivePlaybackContinues() {
        if (!isLive()) return;
        try {
            if (usingVlc && vlcPlayer != null) {
                if (!vlcPlayer.isPlaying()) vlcPlayer.play();
            } else if (player != null && player.getPlaybackState() != Player.STATE_ENDED) {
                player.setPlayWhenReady(true);
                if (!player.isPlaying() && player.getPlaybackState() == Player.STATE_READY) player.play();
            }
        } catch (Throwable error) {
            Log.w(TAG, "live-overlay-keepalive", error);
        }
    }

'''
    p = p.replace(anchor, helper + anchor, 1)

open_overlay = '''                    if (isLive() && liveOverlay != null && !liveOverlay.isVisible()) {
                        liveOverlay.show(id); return true;
                    }'''
if open_overlay in p and "ensureLivePlaybackContinues();\n                        liveOverlay.show(id);" not in p:
    p = p.replace(open_overlay, '''                    if (isLive() && liveOverlay != null && !liveOverlay.isVisible()) {
                        ensureLivePlaybackContinues();
                        liveOverlay.show(id);
                        ensureLivePlaybackContinues();
                        return true;
                    }''', 1)

# Keep playback alive after closing overlay too; focus changes must not alter media state.
p = p.replace("liveOverlay.hide();\n                    requestPlaybackFocus();\n                    return true;",
              "liveOverlay.hide();\n                    ensureLivePlaybackContinues();\n                    requestPlaybackFocus();\n                    return true;")
p = p.replace("liveOverlay.hide(); requestPlaybackFocus(); return;",
              "liveOverlay.hide(); ensureLivePlaybackContinues(); requestPlaybackFocus(); return;")

PLAYER.write_text(p, encoding="utf-8")

# ---------------------------------------------------------------------------
# 3) Live drawer performance: do not re-query SQLite every time OK opens it.
#    Reuse the loaded page and only refresh if empty. This prevents a large DB
#    query from blocking video/UI on every overlay open.
# ---------------------------------------------------------------------------
o = OVERLAY.read_text(encoding="utf-8")
if "r11e2KeepRowsHot" not in o:
    o = o.replace("    private int animationGeneration;\n",
                  "    private int animationGeneration;\n    private boolean r11e2KeepRowsHot = true;\n", 1)
    old = '''        this.currentId = currentId == null ? "" : currentId;
        adapter.reload();
        animationGeneration++;'''
    new = '''        this.currentId = currentId == null ? "" : currentId;
        if (!r11e2KeepRowsHot || adapter.rows.isEmpty()) adapter.reload();
        else adapter.ensureLoaded();
        animationGeneration++;'''
    if old not in o:
        raise SystemExit("R11E2: overlay show/reload anchor missing")
    o = o.replace(old, new, 1)

# Reduce focus churn: no drawer animation on low-performance TV boxes.
if "r11e2ReducedMotion" not in o:
    old = '''        panel.setAlpha(0.88f);
        panel.setTranslationX(-dp(34));
        panel.animate().alpha(1f).translationX(0f).setDuration(150L).start();'''
    new = '''        boolean r11e2ReducedMotion = DeviceCapabilityProfile.detect(activity).usesReducedPerformance();
        if (r11e2ReducedMotion) {
            panel.setAlpha(1f);
            panel.setTranslationX(0f);
        } else {
            panel.setAlpha(0.88f);
            panel.setTranslationX(-dp(34));
            panel.animate().alpha(1f).translationX(0f).setDuration(120L).start();
        }'''
    if old in o:
        o = o.replace(old, new, 1)

OVERLAY.write_text(o, encoding="utf-8")

# ---------------------------------------------------------------------------
# 4) Release stamp.
# ---------------------------------------------------------------------------
g = GRADLE.read_text(encoding="utf-8")
g = re.sub(r'versionCode\s*=\s*\d+', 'versionCode = 1000347', g, count=1)
g = re.sub(r'versionName\s*=\s*"[^"]*"', 'versionName = "v340-full-stability-r11e-stage2"', g, count=1)
GRADLE.write_text(g, encoding="utf-8")

checks = {
    PLAYER: ["playbackTransaction", "beginPlaybackTransaction", "r11e2-transaction",
             "r11e2-live-warm-boundary", "ensureLivePlaybackContinues", "transactionToken != playbackTransaction"],
    OVERLAY: ["r11e2KeepRowsHot", "adapter.rows.isEmpty()", "r11e2ReducedMotion"],
    GRADLE: ["versionCode = 1000347", "v340-full-stability-r11e-stage2"],
}
for path, markers in checks.items():
    text = path.read_text(encoding="utf-8")
    for marker in markers:
        if marker not in text:
            raise SystemExit(f"R11E2 invariant missing {path.name}: {marker}")

print("R11E stage2 applied: hard playback transaction + persistent live overlay + hot drawer rows + reduced-motion TV focus path")
