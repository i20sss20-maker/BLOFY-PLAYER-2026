#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player"
PLAYER = JAVA / "PlayerActivity.java"
VOD = JAVA / "VodPlayerActivity.java"
HEALTH = JAVA / "PlaybackHealthMemory.java"

# ---- Live: a source that already rendered frames is proven. Reconnect the exact
# resolved URL once before asking Railway/native-link to mint/resolve another URL.
p = PLAYER.read_text(encoding="utf-8")
pattern = re.compile(
    r'    private boolean recoverProvenLiveSilently\(String reason\) \{.*?\n    \}\n\n',
    re.S,
)
match = pattern.search(p)
if not match:
    raise SystemExit("stage2: recoverProvenLiveSilently missing")
replacement = r'''    private boolean recoverProvenLiveSilently(String reason) {
        if (!isLive() || !livePlaybackProven || liveSilentRecoveryCount >= 1
                || id.isEmpty() || !validUrl(url)) return false;
        liveSilentRecoveryCount++;
        String provenUrl = url;
        String provenExtension = extension;
        String provenReferer = playbackReferer;
        String provenVariant = sourceVariant;
        PlaybackDiagnostics.marker(this, "hotfix-proven-live-same-url", "live", id, extension,
                sourceVariant, "reason=" + valueOr(reason, "") + " reconnect=1");

        // New transaction invalidates stale callbacks/resolve work but deliberately
        // keeps the already-proven source metadata. No /native-link call is made here.
        beginPlaybackTransaction(false, "proven-live-same-url-reconnect");
        url = provenUrl;
        extension = provenExtension;
        playbackReferer = provenReferer;
        sourceVariant = provenVariant;
        errorPanel.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        if (lifecycleStarted) initializePlayer();
        return true;
    }

'''
p = p[:match.start()] + replacement + p[match.end():]

# Media3 may emit EVENT_RENDERED_FIRST_FRAME again after renderer/surface resets.
# The health learner should receive exactly one first-frame success per transaction.
ff = "    @Override public void onRenderedFirstFrame() {\n"
if ff not in p:
    raise SystemExit("stage2: live first-frame callback missing")
segment_start = p.index(ff)
segment_end = p.find("\n    @Override public void onPlayerError", segment_start)
segment = p[segment_start:segment_end]
if "if (firstFrameRendered) return;" not in segment:
    segment = segment.replace(ff, ff + "        if (firstFrameRendered) return;\n", 1)
    p = p[:segment_start] + segment + p[segment_end:]
PLAYER.write_text(p, encoding="utf-8")

# ---- VOD: same first-frame de-duplication. Repeated renderer callbacks must not
# inflate route confidence or re-run first-frame side effects.
v = VOD.read_text(encoding="utf-8")
old = "    @Override public void onRenderedFirstFrame() {\n        if (usingVlc) return;\n"
new = "    @Override public void onRenderedFirstFrame() {\n        if (usingVlc || firstFrame) return;\n"
if old not in v and new not in v:
    raise SystemExit("stage2: VOD first-frame callback anchor missing")
v = v.replace(old, new, 1)
VOD.write_text(v, encoding="utf-8")

# ---- Health: transient startup/network events are observations, not evidence that
# a provider route is bad. Only explicit 403/404 hard-source failures reduce score.
h = HEALTH.read_text(encoding="utf-8")
start = h.index('    static void failure(Context c, String provider, String family, String route,')
end = h.index('\n    static int score(', start)
body = h[start:end]
if 'r11e5-health-soft-observation' not in body:
    needle = '                        String extension, String engine, String reason) {\n'
    if needle not in body:
        raise SystemExit("stage2: health failure signature missing")
    guard = '''                        String extension, String engine, String reason) {\n        String normalizedReason = safe(reason).toLowerCase(Locale.US);\n        boolean hardSourceFailure = normalizedReason.contains("403") || normalizedReason.contains("404");\n        if (!hardSourceFailure) {\n            PlaybackDiagnostics.marker(c, "r11e5-health-soft-observation", family, "", extension, route,\n                    "engine=" + safe(engine) + " reason=" + safe(reason));\n            return;\n        }\n'''
    body = body.replace(needle, guard, 1)
    h = h[:start] + body + h[end:]
HEALTH.write_text(h, encoding="utf-8")

# Regression invariants target the failures reproduced on three independent providers.
checks = {
    PLAYER: [
        'hotfix-proven-live-same-url',
        'beginPlaybackTransaction(false, "proven-live-same-url-reconnect")',
        'String provenUrl = url;',
        'if (firstFrameRendered) return;',
    ],
    VOD: ['if (usingVlc || firstFrame) return;'],
    HEALTH: ['r11e5-health-soft-observation', 'boolean hardSourceFailure'],
}
for path, markers in checks.items():
    text = path.read_text(encoding="utf-8")
    for marker in markers:
        if marker not in text:
            raise SystemExit(f"stage2 invariant missing {path.name}: {marker}")

# The proven-live recovery itself must not re-resolve or clear the proven URL.
p = PLAYER.read_text(encoding="utf-8")
block = pattern.search(p)
if not block:
    raise SystemExit("stage2: rewritten live recovery missing")
recovery = block.group(0)
if 'resolvePlaybackLink()' in recovery or 'url = null' in recovery:
    raise SystemExit("stage2: proven live recovery still re-resolves the source")

print("v340 playback-core hotfix stage2 applied: proven same-URL reconnect + first-frame de-dup + transient health isolation")
