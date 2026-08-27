#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player"
PLAYER = JAVA / "PlayerActivity.java"
SEVEN = JAVA / "SevenMaxActivity.java"
POLICY = JAVA / "PlaybackPolicy.java"


def read(path):
    return path.read_text(encoding="utf-8")


def write(path, text):
    path.write_text(text, encoding="utf-8")


def replace_once(text, old, new, label):
    if old not in text:
        raise SystemExit(f"v339 patch mismatch: {label}")
    return text.replace(old, new, 1)


# -----------------------------------------------------------------------------
# 1) LIVE: do not burn the whole transport ladder when the actual failure says
#    the selected Xtream output/container is wrong. Flip TS <-> HLS immediately,
#    once per channel, then continue through the existing v338 recovery ladder.
# -----------------------------------------------------------------------------
player = read(PLAYER)

helper_anchor = '''    private void recoverFromFailure(String reason) {\n'''
helper = '''    private boolean formatSpecificLiveFailure(String reason) {\n        if (!isLive() || reason == null) return false;\n        String value = reason.toUpperCase(Locale.US);\n        return value.contains("PARSING")\n                || value.contains("UNRECOGNIZED")\n                || value.contains("UNSUPPORTED")\n                || value.contains("M3U8")\n                || value.contains("MPEGTS")\n                || value.contains("MPEG-TS")\n                || value.contains("MANIFEST")\n                || value.contains("CONTENT_TYPE")\n                || value.contains("CONTENT-TYPE")\n                || value.contains("NO-EXTENSION")\n                || value.contains("SOURCE ERROR");\n    }\n\n    private void recoverFromFailure(String reason) {\n'''
player = replace_once(player, helper_anchor, helper, "live format classifier")

recovery_anchor = '''        if (!isLive()) PlaybackProfileManager.recordFailure(this, kind, extension, transportMode);\n\n        if (PlaybackProfileManager.MODE_LEGACY.equals(transportMode)) {\n'''
fast_format = '''        if (isLive() && formatSpecificLiveFailure(reason) && !liveAlternateTried && !id.isEmpty()) {\n            Log.w(TAG, "format-fast-fallback ext=" + extension + " reason=" + reason);\n            releasePlayer();\n            liveAlternateTried = true;\n            extension = PlaybackPolicy.alternateLiveExtension(extension);\n            transportMode = PlaybackProfileManager.MODE_LEGACY;\n            sourceVariant = "canonical";\n            canonicalUrl = "";\n            canonicalExtension = "";\n            canonicalReferer = "";\n            playbackReferer = "";\n            recoveryStep = 1;\n            url = null;\n            resolvePlaybackLink();\n            return;\n        }\n\n        if (!isLive()) PlaybackProfileManager.recordFailure(this, kind, extension, transportMode);\n\n        if (PlaybackProfileManager.MODE_LEGACY.equals(transportMode)) {\n'''
player = replace_once(player, recovery_anchor, fast_format, "fast TS/HLS fallback")

# Live should not show a fatal dialog while another bounded internal route still
# exists. Existing recovery remains bounded and the dialog is still the final step.
write(PLAYER, player)


# -----------------------------------------------------------------------------
# 2) TV REMOTE: preserve Android's native D-pad behavior first. If Android fails
#    to find a target (common after RecyclerView async updates), recover focus with
#    FocusFinder and reveal the focused RecyclerView child. This never hijacks text
#    input and never double-moves a key that Android already handled.
# -----------------------------------------------------------------------------
seven = read(SEVEN)

if 'import android.view.FocusFinder;' not in seven:
    seven = seven.replace('import android.view.Gravity;\n',
                          'import android.view.FocusFinder;\nimport android.view.Gravity;\nimport android.view.KeyEvent;\n', 1)

remote_anchor = '''    private void showHome() {\n'''
remote_methods = '''    @Override public boolean dispatchKeyEvent(KeyEvent event) {\n        if (event == null) return super.dispatchKeyEvent(event);\n        if (event.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event);\n        View focused = getCurrentFocus();\n        if (focused instanceof EditText) return super.dispatchKeyEvent(event);\n\n        int direction;\n        switch (event.getKeyCode()) {\n            case KeyEvent.KEYCODE_DPAD_UP: direction = View.FOCUS_UP; break;\n            case KeyEvent.KEYCODE_DPAD_DOWN: direction = View.FOCUS_DOWN; break;\n            case KeyEvent.KEYCODE_DPAD_LEFT: direction = View.FOCUS_LEFT; break;\n            case KeyEvent.KEYCODE_DPAD_RIGHT: direction = View.FOCUS_RIGHT; break;\n            default: return super.dispatchKeyEvent(event);\n        }\n\n        // Let Android honor explicit nextFocus links and normal RecyclerView focus first.\n        if (super.dispatchKeyEvent(event)) return true;\n        if (root == null) return false;\n        View next = FocusFinder.getInstance().findNextFocus(root, focused, direction);\n        if (next == null || next == focused || !next.isShown() || !next.isFocusable()) return false;\n        boolean moved = next.requestFocus(direction);\n        if (moved) revealFocusedChild(next);\n        return moved;\n    }\n\n    private void revealFocusedChild(View focused) {\n        View current = focused;\n        while (current != null && current != root) {\n            if (current.getParent() instanceof RecyclerView) {\n                RecyclerView list = (RecyclerView) current.getParent();\n                int position = list.getChildAdapterPosition(current);\n                if (position != RecyclerView.NO_POSITION) list.smoothScrollToPosition(position);\n                return;\n            }\n            if (!(current.getParent() instanceof View)) return;\n            current = (View) current.getParent();\n        }\n    }\n\n    private void showHome() {\n'''
seven = replace_once(seven, remote_anchor, remote_methods, "D-pad fallback")

# RecyclerView should keep the selected child through notify/layout passes. Apply
# this consistently to every TV list created by the cinematic shell.
import re
seven = re.sub(r'(RecyclerView\s+\w+\s*=\s*new RecyclerView\(this\);\n)(?!\s*\w+\.setPreserveFocusAfterLayout)',
               lambda m: m.group(1) + '        ' + re.search(r'RecyclerView\s+(\w+)', m.group(1)).group(1)
               + '.setPreserveFocusAfterLayout(true);\n', seven)

write(SEVEN, seven)


# -----------------------------------------------------------------------------
# 3) Shorter compatibility timeout after the first failed route. The initial
#    route remains untouched; only fallback attempts become quicker.
# -----------------------------------------------------------------------------
policy = read(POLICY)
policy = policy.replace('static final int RETRY_STARTUP_TIMEOUT_MS = 3_000;',
                        'static final int RETRY_STARTUP_TIMEOUT_MS = 2_500;', 1)
write(POLICY, policy)

# Build-time invariants: fail CI rather than silently shipping a partial patch.
final_player = read(PLAYER)
final_seven = read(SEVEN)
for token in ['formatSpecificLiveFailure', 'format-fast-fallback',
              'PlaybackPolicy.alternateLiveExtension(extension)']:
    if token not in final_player:
        raise SystemExit('v339 invariant missing: ' + token)
for token in ['dispatchKeyEvent(KeyEvent event)', 'FocusFinder.getInstance()',
              'setPreserveFocusAfterLayout(true)']:
    if token not in final_seven:
        raise SystemExit('v339 invariant missing: ' + token)

print('v339 applied: fast TS/HLS fallback + resilient TV D-pad focus + quicker bounded retry')
