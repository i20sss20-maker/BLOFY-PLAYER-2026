#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player"
PLAYER = JAVA / "PlayerActivity.java"
VOD = JAVA / "VodPlayerActivity.java"

# Stage3 goals:
# 1) First frame means the source is eligible for same-URL reconnect, but PROVEN
#    is only earned after an actual stability window.
# 2) Media3 gets an explicit IPTV retry policy before BLOFY's outer recovery runs.
# 3) The exact retry/stability invariants remain regression-testable in CI.

p = PLAYER.read_text(encoding="utf-8")

# Explicit Media3 load-error policy.
if "import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;" not in p:
    anchor = "import androidx.media3.exoplayer.source.MediaSource;\n"
    if anchor not in p:
        raise SystemExit("stage3: PlayerActivity media-source import anchor missing")
    p = p.replace(anchor, anchor + "import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;\n", 1)

# A real stability window rather than first-frame == proven.
p = re.sub(r'private static final long LIVE_STABLE_WINDOW_MS\s*=\s*[0-9_]+L;',
           'private static final long LIVE_STABLE_WINDOW_MS = 4_000L;', p, count=1)

if "private boolean liveFirstFrameSeen;" not in p:
    anchor = "    private boolean livePlaybackProven;\n"
    if anchor not in p:
        raise SystemExit("stage3: livePlaybackProven field missing")
    p = p.replace(anchor, anchor + "    private boolean liveFirstFrameSeen;\n", 1)

# Same-URL recovery is allowed after a real first frame; PROVEN remains a stronger
# stable-session signal used for route learning/diagnostics.
p = p.replace(
    "if (!isLive() || !livePlaybackProven || liveSilentRecoveryCount >= 1\n                || id.isEmpty() || !validUrl(url)) return false;",
    "if (!isLive() || !liveFirstFrameSeen || liveSilentRecoveryCount >= 1\n                || id.isEmpty() || !validUrl(url)) return false;",
    1,
)

# R11C used to set PROVEN immediately in both Media3 and VLC first-frame callbacks.
# Convert those blocks to first-frame evidence only. The stable runnable below earns PROVEN.
first_frame_block = re.compile(
    r'        if \(isLive\(\)\) \{\n'
    r'            livePlaybackProven = true;\n'
    r'            liveSilentRecoveryCount = 0;\n'
    r'            attemptedLiveCandidates\.clear\(\);\n'
    r'        \}\n'
)
p, changed = first_frame_block.subn(
    '        if (isLive()) {\n'
    '            liveFirstFrameSeen = true;\n'
    '            liveSilentRecoveryCount = 0;\n'
    '        }\n', p, count=2)
if changed < 1 and "liveFirstFrameSeen = true;" not in p:
    raise SystemExit("stage3: first-frame proven block missing")

# Stable runnable supports both Media3 and VLC and is the only place that promotes live to PROVEN.
old = '''    private final Runnable markPlaybackStable = () -> {\n        if (player == null || !firstFrameRendered || !player.isPlaying()) return;\n        rememberSuccessfulTransport();\n        recoveryStep = preferredRecoveryStep();\n        Log.i(TAG, "stable kind=" + kind + " ext=" + extension\n                + " transport=" + activeTransportName());\n    };'''
new = '''    private final Runnable markPlaybackStable = () -> {\n        if (!firstFrameRendered) return;\n        boolean activelyPlaying = usingVlc\n                ? vlcPlayer != null && vlcPlayer.isPlaying()\n                : player != null && player.isPlaying();\n        if (!activelyPlaying) return;\n        if (isLive()) {\n            livePlaybackProven = true;\n            attemptedLiveCandidates.clear();\n        }\n        rememberSuccessfulTransport();\n        recoveryStep = preferredRecoveryStep();\n        Log.i(TAG, "stable kind=" + kind + " ext=" + extension\n                + " proven=" + livePlaybackProven + " transport=" + activeTransportName());\n    };'''
if old in p:
    p = p.replace(old, new, 1)
elif 'boolean activelyPlaying = usingVlc' not in p:
    raise SystemExit("stage3: stable runnable anchor missing")

# VLC first frame also gets the stability window, not immediate PROVEN.
vlc_anchor = '        Log.i(TAG, "compat-first-frame ext=" + extension + " ms=" + firstFrameMs);\n'
if vlc_anchor in p and 'playbackHandler.postDelayed(markPlaybackStable, LIVE_STABLE_WINDOW_MS);' not in p[p.find(vlc_anchor):p.find(vlc_anchor)+400]:
    p = p.replace(vlc_anchor, vlc_anchor +
        '        playbackHandler.removeCallbacks(markPlaybackStable);\n'
        '        playbackHandler.postDelayed(markPlaybackStable, isLive() ? LIVE_STABLE_WINDOW_MS : 500L);\n', 1)

# Reset both evidence and PROVEN state when changing source or manually retrying.
for signature in [
    "    private void switchLiveChannel(BlofyModels.Media media) {\n",
    "    private void manualRetry() {\n",
]:
    start = p.find(signature)
    if start < 0:
        raise SystemExit(f"stage3: reset method missing: {signature.strip()}")
    section_end = p.find("\n    }", start)
    section = p[start:section_end]
    if "liveFirstFrameSeen = false;" not in section:
        insert_at = start + len(signature)
        # switchLiveChannel has a guard; preserve it before reset.
        if "switchLiveChannel" in signature:
            guard = "        if (!isLive() || media == null || media.id.equals(id)) return;\n"
            guard_at = p.find(guard, start)
            if guard_at < 0:
                raise SystemExit("stage3: switch guard missing")
            insert_at = guard_at + len(guard)
        p = p[:insert_at] + "        liveFirstFrameSeen = false;\n" + p[insert_at:]

# Explicit Media3 retry policy: 6 load attempts for live, 3 for VOD-like content.
if "private DefaultLoadErrorHandlingPolicy loadErrorPolicy()" not in p:
    anchor = "    private DataSource.Factory createDataSourceFactory() {\n"
    helper = '''    private DefaultLoadErrorHandlingPolicy loadErrorPolicy() {\n        return new DefaultLoadErrorHandlingPolicy(isLive() ? 6 : 3);\n    }\n\n'''
    if anchor not in p:
        raise SystemExit("stage3: PlayerActivity data-source helper anchor missing")
    p = p.replace(anchor, helper + anchor, 1)

p = p.replace(
    "DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory);",
    "DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)\n                .setLoadErrorHandlingPolicy(loadErrorPolicy());",
)
p = p.replace(
    "? new HlsMediaSource.Factory(dataSourceFactory)\n                    .setExtractorFactory(new DefaultHlsExtractorFactory(tsFlags, true)).createMediaSource(item)",
    "? new HlsMediaSource.Factory(dataSourceFactory)\n                    .setLoadErrorHandlingPolicy(loadErrorPolicy())\n                    .setExtractorFactory(new DefaultHlsExtractorFactory(tsFlags, true)).createMediaSource(item)",
    1,
)
PLAYER.write_text(p, encoding="utf-8")

# VOD: explicit three-attempt Media3 loading policy before outer direct/VLC recovery.
v = VOD.read_text(encoding="utf-8")
if "import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;" not in v:
    anchor = "import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;\n"
    if anchor not in v:
        raise SystemExit("stage3: VOD media-source import anchor missing")
    v = v.replace(anchor, anchor + "import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;\n", 1)

v = v.replace(
    "DefaultMediaSourceFactory mediaSources = new DefaultMediaSourceFactory(source, extractors);",
    "DefaultMediaSourceFactory mediaSources = new DefaultMediaSourceFactory(source, extractors)\n                .setLoadErrorHandlingPolicy(new DefaultLoadErrorHandlingPolicy(3));",
    1,
)
VOD.write_text(v, encoding="utf-8")

checks = {
    PLAYER: [
        "LIVE_STABLE_WINDOW_MS = 4_000L",
        "private boolean liveFirstFrameSeen;",
        "!liveFirstFrameSeen || liveSilentRecoveryCount >= 1",
        "boolean activelyPlaying = usingVlc",
        "livePlaybackProven = true;",
        "new DefaultLoadErrorHandlingPolicy(isLive() ? 6 : 3)",
        ".setLoadErrorHandlingPolicy(loadErrorPolicy())",
        "liveFirstFrameSeen = false;",
    ],
    VOD: [
        "DefaultLoadErrorHandlingPolicy",
        ".setLoadErrorHandlingPolicy(new DefaultLoadErrorHandlingPolicy(3))",
    ],
}
for path, markers in checks.items():
    text = path.read_text(encoding="utf-8")
    for marker in markers:
        if marker not in text:
            raise SystemExit(f"stage3 invariant missing {path.name}: {marker}")

# PROVEN must not be assigned inside the Media3 first-frame callback anymore.
p = PLAYER.read_text(encoding="utf-8")
ff_start = p.find("    @Override public void onRenderedFirstFrame() {")
ff_end = p.find("\n    @Override public void onPlayerError", ff_start)
if ff_start < 0 or ff_end < 0:
    raise SystemExit("stage3: Media3 first-frame section missing")
if "livePlaybackProven = true;" in p[ff_start:ff_end]:
    raise SystemExit("stage3: first frame still promotes LIVE to PROVEN")

print("v340 playback-core hotfix stage3 applied: explicit Media3 retries + first-frame/stable separation + VLC stability parity")
