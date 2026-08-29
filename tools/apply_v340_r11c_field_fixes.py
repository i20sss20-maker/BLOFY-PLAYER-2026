#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player"
PLAYER = JAVA / "PlayerActivity.java"
POLICY = JAVA / "PlaybackPolicy.java"
IMPORTER = JAVA / "PackageImporter.java"
TEST = ROOT / "BLOFY-ANDROID-2026/app/src/test/java/tv/blofy/player/PlaybackPolicyTest.java"

p = PLAYER.read_text(encoding="utf-8")

if "private boolean livePlaybackProven;" not in p:
    anchor = "    private boolean lifecycleStarted;\n"
    addition = (
        "    private boolean lifecycleStarted;\n"
        "    private boolean livePlaybackProven;\n"
        "    private int liveSilentRecoveryCount;\n"
        "    private final java.util.HashSet<String> attemptedLiveCandidates = new java.util.HashSet<>();\n"
    )
    if anchor not in p:
        raise SystemExit("R11C: lifecycle field missing")
    p = p.replace(anchor, addition, 1)

if "attemptedLiveCandidates.add(" not in p:
    marker = "        String requestedVariant = sourceVariant;\n"
    if marker in p:
        p = p.replace(marker, marker +
            "        if (isLive()) attemptedLiveCandidates.add(requestedVariant + \"|\" + requestedExtension);\n", 1)

if "private boolean recoverProvenLiveSilently(String reason)" not in p:
    helper = r'''
    private boolean recoverProvenLiveSilently(String reason) {
        if (!isLive() || !livePlaybackProven || liveSilentRecoveryCount >= 1 || id.isEmpty()) return false;
        liveSilentRecoveryCount++;
        PlaybackDiagnostics.marker(this, "r11c-live-silent-recovery", "live", id, extension,
                sourceVariant, "reason=" + valueOr(reason, ""));
        playbackHandler.removeCallbacks(playbackTimeout);
        playbackHandler.removeCallbacks(markPlaybackStable);
        errorPanel.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        firstFrameRendered = false;
        releaseMedia3Player();
        releaseVlcPlayer();
        usingVlc = false;
        url = null;
        resolvePlaybackLink();
        return true;
    }

'''
    anchor = "    private void schedulePlaybackTimeout() {\n"
    if anchor not in p:
        raise SystemExit("R11C: schedule timeout method missing")
    p = p.replace(anchor, helper + anchor, 1)

ff_sig = "    @Override public void onRenderedFirstFrame() {\n"
if ff_sig in p and "liveSilentRecoveryCount = 0;" not in p[p.find(ff_sig):p.find(ff_sig)+900]:
    target = "        firstFrameRendered = true;\n"
    pos = p.find(target, p.find(ff_sig))
    if pos >= 0:
        pos += len(target)
        p = p[:pos] + (
            "        if (isLive()) {\n"
            "            livePlaybackProven = true;\n"
            "            liveSilentRecoveryCount = 0;\n"
            "            attemptedLiveCandidates.clear();\n"
            "        }\n"
        ) + p[pos:]

vlc_sig = "    private void markVlcFirstFrame() {\n"
if vlc_sig in p:
    section = p[p.find(vlc_sig):p.find(vlc_sig)+1000]
    if "livePlaybackProven = true;" not in section:
        target = "        firstFrameRendered = true;\n"
        pos = p.find(target, p.find(vlc_sig))
        if pos >= 0:
            pos += len(target)
            p = p[:pos] + (
                "        if (isLive()) {\n"
                "            livePlaybackProven = true;\n"
                "            liveSilentRecoveryCount = 0;\n"
                "            attemptedLiveCandidates.clear();\n"
                "        }\n"
            ) + p[pos:]

err_sig = "    @Override public void onPlayerError(PlaybackException error) {\n"
if err_sig in p:
    section_start = p.find(err_sig)
    next_method = p.find("\n    private static String playbackErrorReason", section_start)
    section = p[section_start: next_method if next_method > 0 else section_start + 3000]
    if "recoverProvenLiveSilently(runtimeReason)" not in section:
        log_end = p.find("\n", p.find("transport=\" + activeTransportName(), error);", section_start))
        if log_end > 0:
            insertion = (
                "        if (isLive() && livePlaybackProven\n"
                "                && error.errorCode != PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {\n"
                "            String runtimeReason = playbackErrorReason(error);\n"
                "            PlaybackNegotiator.stale(this, url, \"live\", runtimeReason);\n"
                "            if (recoverProvenLiveSilently(runtimeReason)) return;\n"
                "        }\n"
            )
            p = p[:log_end+1] + insertion + p[log_end+1:]

recover_sig = "    private void recoverFromFailure(String reason) {\n"
if recover_sig in p:
    start = p.find(recover_sig)
    section = p[start:start+900]
    if "recoverProvenLiveSilently(reason)" not in section:
        anchor = "        playbackHandler.removeCallbacks(markPlaybackStable);\n"
        pos = p.find(anchor, start)
        if pos >= 0:
            pos += len(anchor)
            p = p[:pos] + "\n        if (isLive() && livePlaybackProven && recoverProvenLiveSilently(reason)) return;\n" + p[pos:]

switch_sig = "    private void switchLiveChannel(BlofyModels.Media media) {\n"
if switch_sig in p:
    start = p.find(switch_sig)
    section = p[start:start+1800]
    if "livePlaybackProven = false;" not in section:
        anchor = "        if (!isLive() || media == null || media.id.equals(id)) return;\n"
        pos = p.find(anchor, start)
        if pos >= 0:
            pos += len(anchor)
            p = p[:pos] + (
                "        livePlaybackProven = false;\n"
                "        liveSilentRecoveryCount = 0;\n"
                "        attemptedLiveCandidates.clear();\n"
            ) + p[pos:]

manual_sig = "    private void manualRetry() {\n"
if manual_sig in p:
    start = p.find(manual_sig)
    section = p[start:start+700]
    if "livePlaybackProven = false;" not in section:
        pos = start + len(manual_sig)
        p = p[:pos] + (
            "        livePlaybackProven = false;\n"
            "        liveSilentRecoveryCount = 0;\n"
            "        attemptedLiveCandidates.clear();\n"
        ) + p[pos:]

PLAYER.write_text(p, encoding="utf-8")

pol = POLICY.read_text(encoding="utf-8")
pol = re.sub(r'PREVIEW_STARTUP_TIMEOUT_MS\s*=\s*[0-9_]+',
             'PREVIEW_STARTUP_TIMEOUT_MS = 3_500', pol, count=1)
POLICY.write_text(pol, encoding="utf-8")

t = TEST.read_text(encoding="utf-8")
t = re.sub(r'assertEquals\([0-9_]+, PlaybackPolicy\.PREVIEW_STARTUP_TIMEOUT_MS\);',
           'assertEquals(3_500, PlaybackPolicy.PREVIEW_STARTUP_TIMEOUT_MS);', t, count=1)
TEST.write_text(t, encoding="utf-8")

i = IMPORTER.read_text(encoding="utf-8")
i = i.replace('final long[] httpDelays = {600L, 1_500L, 4_000L, 8_000L};',
              'final long[] httpDelays = {500L, 1_000L, 2_000L, 4_000L, 8_000L, 12_000L};')
i = i.replace('final long[] networkDelays = {250L, 650L, 1_500L, 3_500L};',
              'final long[] networkDelays = {250L, 500L, 1_000L, 2_000L, 4_000L, 8_000L};')
if 'اعتماد الباقة كاملة' not in i:
    i, n95 = re.subn(r'emit\(95,\s*"[^"]*",\s*"[^"]*"\);',
                     'emit(95, "اعتماد الباقة كاملة", "تم تنزيل القنوات والأفلام والمسلسلات بالكامل • جاري التثبيت الذري");',
                     i, count=1)
    if n95 != 1:
        raise SystemExit("R11C: importer 95% stage missing")
if 'تم تثبيت الباقة كاملة وحفظها محليًا' not in i:
    i = re.sub(r'emit\(99,\s*"[^"]*",\s*"[^"]*"\);',
               'emit(99, "التحقق النهائي", "تم تثبيت الباقة كاملة وحفظها محليًا");',
               i, count=1)
IMPORTER.write_text(i, encoding="utf-8")

checks = {
    PLAYER: ["livePlaybackProven", "attemptedLiveCandidates", "r11c-live-silent-recovery",
             "recoverProvenLiveSilently(runtimeReason)"],
    POLICY: ["PREVIEW_STARTUP_TIMEOUT_MS = 3_500"],
    TEST: ["assertEquals(3_500, PlaybackPolicy.PREVIEW_STARTUP_TIMEOUT_MS);"],
    IMPORTER: ["12_000L", "اعتماد الباقة كاملة", "التثبيت الذري"],
}
for path, markers in checks.items():
    text = path.read_text(encoding="utf-8")
    for marker in markers:
        if marker not in text:
            raise SystemExit(f"R11C invariant missing {path.name}: {marker}")

print("R11C field fixes applied: proven-live silent recovery + guarded candidate tracking + atomic large-package hardening")
