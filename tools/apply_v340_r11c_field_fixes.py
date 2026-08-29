#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player"
PLAYER = JAVA / "PlayerActivity.java"
POLICY = JAVA / "PlaybackPolicy.java"
IMPORTER = JAVA / "PackageImporter.java"
TEST = ROOT / "BLOFY-ANDROID-2026/app/src/test/java/tv/blofy/player/PlaybackPolicyTest.java"

# ---------------------------------------------------------------------------
# R11C: field-test fixes.
# 1) A stream that rendered a frame is never treated as a startup failure later.
# 2) Startup candidates are unique inside one attempt plan (TS -> HLS -> route).
# 3) A post-start runtime failure recovers silently before any fatal UI.
# ---------------------------------------------------------------------------
p = PLAYER.read_text(encoding="utf-8")

field_anchor = "    private int postStartRecoveryCount;\n"
fields = """    private int postStartRecoveryCount;\n    private boolean livePlaybackProven;\n    private int liveSessionGeneration;\n    private final java.util.HashSet<String> attemptedLiveCandidates = new java.util.HashSet<>();\n"""
if "private boolean livePlaybackProven;" not in p:
    if field_anchor in p:
        p = p.replace(field_anchor, fields, 1)
    else:
        p = p.replace("    private boolean lifecycleStarted;\n",
                      "    private boolean lifecycleStarted;\n" + fields, 1)

helper_marker = "private String liveCandidateKey(String route, String ext, String engine)"
if helper_marker not in p:
    helpers = r'''
    private String liveCandidateKey(String route, String ext, String engine) {
        return valueOr(route, "canonical") + "|"
                + PlaybackPolicy.normalizeExtension(ext, "ts") + "|" + valueOr(engine, "media3");
    }

    private void resetLiveAttemptPlan() {
        attemptedLiveCandidates.clear();
        livePlaybackProven = false;
        postStartRecoveryCount = 0;
        liveSessionGeneration++;
    }

    /** Move forward through startup candidates; never repeat the same candidate in one plan. */
    private boolean tryNextLiveStartupCandidate(String reason) {
        if (!isLive() || id.isEmpty()) return false;
        String currentExt = PlaybackPolicy.normalizeExtension(extension, "ts");
        String alternate = PlaybackPolicy.alternateLiveExtension(currentExt);
        String alternateKey = liveCandidateKey("canonical", alternate, "media3");
        if (!attemptedLiveCandidates.contains(alternateKey)) {
            PlaybackDiagnostics.marker(this, "r11c-live-next-candidate", "live", id, currentExt,
                    sourceVariant, "reason=" + valueOr(reason, "") + " next=canonical+" + alternate + "+media3");
            releaseMedia3Player();
            releaseVlcPlayer();
            usingVlc = false;
            extension = alternate;
            sourceVariant = "canonical";
            recoveryStep = 0;
            url = null;
            resolvePlaybackLink();
            return true;
        }

        String directKey = liveCandidateKey("direct", currentExt, "media3");
        if (!attemptedLiveCandidates.contains(directKey)) {
            PlaybackDiagnostics.marker(this, "r11c-live-next-candidate", "live", id, currentExt,
                    sourceVariant, "reason=" + valueOr(reason, "") + " next=direct+" + currentExt + "+media3");
            releaseMedia3Player();
            releaseVlcPlayer();
            usingVlc = false;
            extension = currentExt;
            sourceVariant = "direct";
            recoveryStep = 1;
            url = null;
            resolvePlaybackLink();
            return true;
        }
        return false;
    }

    /** A stream that already rendered video gets an invisible reconnect first. */
    private boolean recoverProvenLiveSilently(String reason) {
        if (!isLive() || !livePlaybackProven) return false;
        postStartRecoveryCount++;
        PlaybackDiagnostics.marker(this, "r11c-live-silent-recovery", "live", id, extension,
                sourceVariant, "attempt=" + postStartRecoveryCount + " reason=" + valueOr(reason, ""));
        errorPanel.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        playbackHandler.removeCallbacks(playbackTimeout);
        playbackHandler.removeCallbacks(markPlaybackStable);

        // First recovery mirrors the user's successful manual retry, but automatically:
        // obtain a fresh signed link for the already-proven route/format.
        if (postStartRecoveryCount == 1 && !id.isEmpty()) {
            int generation = ++liveSessionGeneration;
            firstFrameRendered = false;
            releaseMedia3Player();
            releaseVlcPlayer();
            usingVlc = false;
            url = null;
            playbackHandler.post(() -> {
                if (generation != liveSessionGeneration || isFinishing() || isDestroyed()) return;
                resolvePlaybackLink();
            });
            return true;
        }

        // If a fresh link still cannot render, change stream family/route instead of
        // displaying a false fatal dialog over a provider that already proved playback.
        if (tryNextLiveStartupCandidate(reason)) return true;
        return false;
    }

'''
    anchor = "    private void schedulePlaybackTimeout() {\n"
    if anchor not in p:
        raise SystemExit("R11C: schedulePlaybackTimeout anchor missing")
    p = p.replace(anchor, helpers + anchor, 1)

# Every resolve candidate is recorded before the request. This prevents the old
# canonical+TS -> canonical+TS loops seen in the field diagnostics.
resolve_anchor = '''        String requestedVariant = sourceVariant;
        String requestedReferer = playbackReferer;
        resolveTask = network.submit(() -> {'''
resolve_repl = '''        String requestedVariant = sourceVariant;
        String requestedReferer = playbackReferer;
        if (isLive()) attemptedLiveCandidates.add(
                liveCandidateKey(requestedVariant, requestedExtension, "media3"));
        resolveTask = network.submit(() -> {'''
if "liveCandidateKey(requestedVariant, requestedExtension" not in p:
    if resolve_anchor not in p:
        raise SystemExit("R11C: resolve candidate anchor missing")
    p = p.replace(resolve_anchor, resolve_repl, 1)

# Resolve failures now advance to another candidate before showing an error.
catch_anchor = '''                    warmLiveSwitchPending = false;
                    if ("direct".equals(requestedVariant) && restoreCanonicalSource()) {'''
catch_repl = '''                    warmLiveSwitchPending = false;
                    String resolveMessage = PlaybackPolicy.resolveErrorMessage(error);
                    if (isLive() && tryNextLiveStartupCandidate(resolveMessage)) return;
                    if ("direct".equals(requestedVariant) && restoreCanonicalSource()) {'''
if "String resolveMessage = PlaybackPolicy.resolveErrorMessage(error);" not in p:
    if catch_anchor not in p:
        raise SystemExit("R11C: resolve failure anchor missing")
    p = p.replace(catch_anchor, catch_repl, 1)

# Avoid re-computing a different message and use the same diagnosed reason.
p = p.replace('if (lifecycleStarted) openVlc(PlaybackPolicy.resolveErrorMessage(error));',
              'if (lifecycleStarted) openVlc(resolveMessage);', 1)
p = p.replace('showResolveError(PlaybackPolicy.resolveErrorMessage(error));',
              'showResolveError(resolveMessage);', 1)

# First frame permanently closes the startup phase for that generation.
ff_anchor = '''        firstFrameRendered = true;
        playbackHandler.removeCallbacks(playbackTimeout);
        progress.setVisibility(View.GONE);
        long firstFrameMs = SystemClock.elapsedRealtime() - playbackStartedAtMs;'''
ff_repl = '''        firstFrameRendered = true;
        playbackHandler.removeCallbacks(playbackTimeout);
        if (isLive()) {
            livePlaybackProven = true;
            postStartRecoveryCount = 0;
            attemptedLiveCandidates.clear();
            liveSessionGeneration++;
            errorPanel.setVisibility(View.GONE);
        }
        progress.setVisibility(View.GONE);
        long firstFrameMs = SystemClock.elapsedRealtime() - playbackStartedAtMs;'''
if "attemptedLiveCandidates.clear();\n            liveSessionGeneration++;" not in p:
    if ff_anchor not in p:
        raise SystemExit("R11C: Media3 first-frame anchor missing")
    p = p.replace(ff_anchor, ff_repl, 1)

# VLC first frame is equally proven and must cancel every startup guard.
vlc_ff_anchor = '''        firstFrameRendered = true;
        playbackHandler.removeCallbacks(playbackTimeout);
        progress.setVisibility(View.GONE);
        playbackHandler.removeCallbacks(hideTitle);'''
vlc_ff_repl = '''        firstFrameRendered = true;
        playbackHandler.removeCallbacks(playbackTimeout);
        if (isLive()) {
            livePlaybackProven = true;
            postStartRecoveryCount = 0;
            attemptedLiveCandidates.clear();
            liveSessionGeneration++;
            errorPanel.setVisibility(View.GONE);
        }
        progress.setVisibility(View.GONE);
        playbackHandler.removeCallbacks(hideTitle);'''
if p.count("attemptedLiveCandidates.clear();") < 2:
    if vlc_ff_anchor not in p:
        raise SystemExit("R11C: VLC first-frame anchor missing")
    p = p.replace(vlc_ff_anchor, vlc_ff_repl, 1)

# Replace the R11B post-start block. Crucially, do NOT schedule a startup watchdog
# after video was already proven; that was the source of the 29-32s false failure.
pattern = re.compile(r'''        if \(isLive\(\) && firstFrameRendered && player != null\n                && error\.errorCode != PlaybackException\.ERROR_CODE_BEHIND_LIVE_WINDOW\n                && postStartRecoveryCount < 2\) \{.*?\n        \}\n        if \(isLive\(\) && error\.errorCode == PlaybackException\.ERROR_CODE_BEHIND_LIVE_WINDOW''', re.S)
replacement = '''        if (isLive() && livePlaybackProven
                && error.errorCode != PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            String runtimeReason = playbackErrorReason(error);
            PlaybackNegotiator.stale(this, url, "live", runtimeReason);
            if (recoverProvenLiveSilently(runtimeReason)) return;
        }
        if (isLive() && error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW'''
p, n = pattern.subn(replacement, p, count=1)
if n != 1 and "recoverProvenLiveSilently(runtimeReason)" not in p:
    raise SystemExit("R11C: R11B post-start recovery block missing")

# A runtime failure that arrives through VLC or STATE_ENDED also gets the same
# silent recovery path before the fatal panel.
recover_anchor = '''    private void recoverFromFailure(String reason) {
        if (isFinishing() || isDestroyed()) return;
        playbackHandler.removeCallbacks(playbackTimeout);
        playbackHandler.removeCallbacks(markPlaybackStable);

        if (usingVlc) {'''
recover_repl = '''    private void recoverFromFailure(String reason) {
        if (isFinishing() || isDestroyed()) return;
        playbackHandler.removeCallbacks(playbackTimeout);
        playbackHandler.removeCallbacks(markPlaybackStable);

        if (isLive() && livePlaybackProven && recoverProvenLiveSilently(reason)) return;

        if (usingVlc) {'''
if "livePlaybackProven && recoverProvenLiveSilently(reason)" not in p:
    if recover_anchor not in p:
        raise SystemExit("R11C: recoverFromFailure anchor missing")
    p = p.replace(recover_anchor, recover_repl, 1)

# New channel/manual retry = a new candidate plan. Final fatal state clears proven.
switch_anchor = '''        recoveryStep = preferredRecoveryStep();
        vlcAttempted = false;
        if (usingVlc) {'''
if switch_anchor in p:
    p = p.replace(switch_anchor, '''        recoveryStep = preferredRecoveryStep();
        vlcAttempted = false;
        resetLiveAttemptPlan();
        if (usingVlc) {''', 1)

manual_anchor = '''    private void manualRetry() {
        recoveryStep = preferredRecoveryStep();
        vlcAttempted = false;'''
if manual_anchor in p and "private void manualRetry() {\n        resetLiveAttemptPlan();" not in p:
    p = p.replace(manual_anchor, '''    private void manualRetry() {
        resetLiveAttemptPlan();
        recoveryStep = preferredRecoveryStep();
        vlcAttempted = false;''', 1)

fatal_anchor = '''    private void showPlaybackFailure(String reason) {
        releaseMedia3Player();'''
if fatal_anchor in p and "showPlaybackFailure(String reason) {\n        livePlaybackProven = false;" not in p:
    p = p.replace(fatal_anchor, '''    private void showPlaybackFailure(String reason) {
        livePlaybackProven = false;
        liveSessionGeneration++;
        releaseMedia3Player();''', 1)

PLAYER.write_text(p, encoding="utf-8")

# ---------------------------------------------------------------------------
# Preview budget must be intentional and test-synchronized. 3.5 seconds is
# enough for preview before the app changes route, without affecting fullscreen.
# ---------------------------------------------------------------------------
pol = POLICY.read_text(encoding="utf-8")
pol = re.sub(r'PREVIEW_STARTUP_TIMEOUT_MS\s*=\s*[0-9_]+',
             'PREVIEW_STARTUP_TIMEOUT_MS = 3_500', pol, count=1)
POLICY.write_text(pol, encoding="utf-8")

t = TEST.read_text(encoding="utf-8")
t = re.sub(r'assertEquals\([0-9_]+, PlaybackPolicy\.PREVIEW_STARTUP_TIMEOUT_MS\);',
           'assertEquals(3_500, PlaybackPolicy.PREVIEW_STARTUP_TIMEOUT_MS);', t, count=1)
TEST.write_text(t, encoding="utf-8")

# ---------------------------------------------------------------------------
# Large-package import: still 100% before entry and still atomic, but much more
# tolerant of Railway/provider transients. Every page is persisted immediately
# into staging; active data is swapped only after Live+Movies+Series complete.
# ---------------------------------------------------------------------------
i = IMPORTER.read_text(encoding="utf-8")
i = i.replace('final long[] httpDelays = {600L, 1_500L, 4_000L, 8_000L};',
              'final long[] httpDelays = {500L, 1_000L, 2_000L, 4_000L, 8_000L, 12_000L};')
i = i.replace('final long[] networkDelays = {250L, 650L, 1_500L, 3_500L};',
              'final long[] networkDelays = {250L, 500L, 1_000L, 2_000L, 4_000L, 8_000L};')
# Report persisted counts after each page instead of leaving the user on an opaque 95%.
page_save = '''            JSONObject response = getWithRetry("/api/catalog?type=" + BlofyApi.encode(type)
                    + "&page=" + page + "&page_size=" + REQUESTED_PAGE_SIZE, true);
            save(BlofyModels.Media.list(response, type));'''
page_save_repl = '''            JSONObject response = getWithRetry("/api/catalog?type=" + BlofyApi.encode(type)
                    + "&page=" + page + "&page_size=" + REQUESTED_PAGE_SIZE, true);
            save(BlofyModels.Media.list(response, type));
            emit(progress, "حفظ " + label,
                    "تم حفظ " + database.importCount(type) + " من " + total + " محليًا");'''
if page_save in i and "تم حفظ \" + database.importCount(type)" not in i:
    i = i.replace(page_save, page_save_repl, 1)

# Make the final stage explicitly say that all three catalogs are already on disk;
# the user should never enter before the atomic commit reaches complete.
i = i.replace('emit(95, "اعتماد بيانات الباقة", "تثبيت البيانات المحفوظة على الجهاز");',
              'emit(95, "اعتماد الباقة كاملة", "تم تنزيل القنوات والأفلام والمسلسلات بالكامل • جاري التثبيت الذري");')
i = i.replace('emit(99, "فتح BLOFY PLAYER", "تم الحفظ بنجاح");',
              'emit(99, "التحقق النهائي", "تم تثبيت الباقة كاملة وحفظها محليًا");')
IMPORTER.write_text(i, encoding="utf-8")

checks = {
    PLAYER: [
        "livePlaybackProven", "attemptedLiveCandidates", "r11c-live-next-candidate",
        "r11c-live-silent-recovery", "recoverProvenLiveSilently(runtimeReason)",
        "resetLiveAttemptPlan();"
    ],
    POLICY: ["PREVIEW_STARTUP_TIMEOUT_MS = 3_500"],
    TEST: ["assertEquals(3_500, PlaybackPolicy.PREVIEW_STARTUP_TIMEOUT_MS);"],
    IMPORTER: ["12_000L", "8_000L", "اعتماد الباقة كاملة", "التثبيت الذري"]
}
for path, markers in checks.items():
    text = path.read_text(encoding="utf-8")
    for marker in markers:
        if marker not in text:
            raise SystemExit(f"R11C invariant missing {path.name}: {marker}")

print("R11C field fixes applied: proven-live silent recovery + unique startup candidates + full atomic large-package import hardening")
