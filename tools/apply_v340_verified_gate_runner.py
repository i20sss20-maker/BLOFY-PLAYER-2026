#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "tools/apply_v340_verified_gate.py"
source = BASE.read_text(encoding="utf-8")
# The first implementation writes the new profile/gate classes correctly. Some textual
# patches target generated source and are finalized below with regexes, so bypass its
# early invariant block and validate only after the robust postfix pass.
source = re.sub(r'\n# Invariants after all patches\..*\Z',
                '\nprint("v340 verified gate base classes applied")\n', source,
                flags=re.S)
exec(compile(source, str(BASE), "exec"), {"__name__": "__main__", "__file__": str(BASE)})

JAVA = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player"
PACKAGE = JAVA / "PackageImporter.java"
PLAYER = JAVA / "PlayerActivity.java"
VOD = JAVA / "VodPlayerActivity.java"
PREVIEW = JAVA / "LivePreviewController.java"
POLICY = JAVA / "PlaybackPolicy.java"
PROFILE = JAVA / "ServerPlaybackProfile.java"
PREFLIGHT = JAVA / "ServerCompatibilityPreflight.java"

# 1) Cached catalog must still pass the verified 3/3 gate before entering.
text = PACKAGE.read_text(encoding="utf-8")
cache_pattern = re.compile(
    r'(        if \(sourceIdentity\.equals\(activeSource\).*?\n'
    r'                && !"in_progress"\.equals\(database\.metadata\("sync_state", ""\)\)\) \{\n)'
    r'(.*?)(        \}\n\n        emit\(12, "تحليل الخادم")', re.S)
match = cache_pattern.search(text)
if not match:
    raise SystemExit("verified runner: cached import block not found")
cache_body = '''            String profile = database.metadata("playback_profile", "Media3 مباشر");
            if (ServerCompatibilityPreflight.savedAccepted(api.context(), playlistId)) {
                emit(100, "جاهز", "تم التحقق من ملف تشغيل هذا السيرفر مسبقًا");
                return new Result(cachedLive, cachedMovies, cachedSeries, profile);
            }
            emit(96, "إعادة التحقق من التشغيل", "البيانات محفوظة؛ يتم التحقق من Live و Movies و Series فقط");
            ServerCompatibilityPreflight.Result cachedGate = ServerCompatibilityPreflight.run(
                    api.context(), api, database, playlistId);
            if (!cachedGate.accepted()) {
                throw new Exception("السيرفر غير متوافق بالكامل. " + cachedGate.summary
                        + " • Live و Movies و Series يجب أن تنجح جميعًا قبل الدخول.");
            }
            emit(100, "جاهز", cachedGate.summary);
            return new Result(cachedLive, cachedMovies, cachedSeries, profile);
'''
text = text[:match.start(2)] + cache_body + text[match.end(2):]
# Strict post-import gate, regardless of the older completeFailure condition.
text = re.sub(
    r'            if \(preflight\.completeFailure\(\).*?\n            \}\n',
    '''            if (!preflight.accepted()) {
                throw new Exception("تم حفظ الباقة كاملة، لكن لم يتم اعتماد السيرفر لأن التشغيل ليس 3/3. "
                        + preflight.summary + " • Live و Movies و Series يجب أن تنجح جميعًا قبل الدخول.");
            }
''', text, count=1, flags=re.S)
PACKAGE.write_text(text, encoding="utf-8")

# 2) Live fullscreen: remove the known-bad drawer and make OK return to channel list.
text = PLAYER.read_text(encoding="utf-8")
text = text.replace('ServerPlaybackProfile.load(this, canonicalUrl)',
                    'ServerPlaybackProfile.load(this, canonicalUrl, kind)')
text = re.sub(
    r'\n        if \(isLive\(\)\) \{\n            liveOverlay = new LiveChannelOverlay\(this, categoryId, this::switchLiveChannel\);.*?\n        \}\n',
    '\n', text, count=1, flags=re.S)
text = re.sub(
    r'                case KeyEvent\.KEYCODE_DPAD_CENTER:\n'
    r'                case KeyEvent\.KEYCODE_ENTER:\n'
    r'                    if \(isLive\(\).*?\n'
    r'                    \}\n'
    r'                    break;',
    '''                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (isLive()) { finish(); return true; }
                    break;''', text, count=1, flags=re.S)
# Persist provider learning only after a real stable first frame.
text = re.sub(
    r'    private void rememberSuccessfulTransport\(\) \{.*?\n    \}\n',
    '''    private void rememberSuccessfulTransport() {
        if (!validUrl(url) || !firstFrameRendered) return;
        long verifiedMs = playbackStartedAtMs == 0 ? -1L
                : Math.max(0L, SystemClock.elapsedRealtime() - playbackStartedAtMs);
        ServerPlaybackProfile.rememberVerifiedSuccess(this, url, kind, extension, sourceVariant,
                usingVlc ? "libvlc" : "media3", PlaybackTransportFactory.userAgent(usingVlc ? 2 : 0),
                playbackReferer, verifiedMs);
    }
''', text, count=1, flags=re.S)
# Reject 404/551 immediately rather than re-learning them.
if 'private void rejectCurrentRouteIfHard' not in text:
    text = text.replace('    private static String playbackErrorReason(PlaybackException error) {',
'''    private void rejectCurrentRouteIfHard(String reason) {
        if (!PlaybackPolicy.isHardRouteFailure(reason) || !validUrl(url)) return;
        ServerPlaybackProfile.rejectRoute(this, url, kind, sourceVariant, reason);
    }

    private static String playbackErrorReason(PlaybackException error) {''', 1)
text = text.replace('        recoverFromFailure(playbackErrorReason(error));',
'''        String reason = playbackErrorReason(error);
        rejectCurrentRouteIfHard(reason);
        recoverFromFailure(reason);''', 1)
PLAYER.write_text(text, encoding="utf-8")

# 3) VOD is a bounded canonical -> direct -> no-extension -> canonical/VLC state machine.
text = VOD.read_text(encoding="utf-8")
text = text.replace('ServerPlaybackProfile.load(this, canonicalUrl)',
                    'ServerPlaybackProfile.load(this, canonicalUrl, kind)')
if 'private void rejectVodRouteIfHard' not in text:
    text = text.replace('    private void recover(String reason) {',
'''    private void rejectVodRouteIfHard(String reason) {
        if (!PlaybackPolicy.isHardRouteFailure(reason) || !validUrl(resolvedUrl)) return;
        ServerPlaybackProfile.rejectRoute(this, resolvedUrl, kind, sourceVariant, reason);
    }

    private boolean routeAllowed(String route) {
        String reference = validUrl(canonicalUrl) ? canonicalUrl : resolvedUrl;
        ServerPlaybackProfile.Profile profile = ServerPlaybackProfile.load(this, reference, kind);
        return !profile.routeRejected(route);
    }

    private void recover(String reason) {''', 1)
recover = re.compile(r'    private void recover\(String reason\) \{.*?\n    \}\n\n    private void showFinalPlaybackError', re.S)
replacement = '''    private void recover(String reason) {
        main.removeCallbacks(startupTimeout);
        savePosition();
        rejectVodRouteIfHard(reason);

        if (usingVlc) {
            showFinalPlaybackError(reason);
            return;
        }

        if ("canonical".equals(sourceVariant) && !id.isEmpty()) {
            if (!alternateSourceAttempted && routeAllowed("direct")) {
                alternateSourceAttempted = true;
                sourceVariant = "direct";
                attempt = 1;
                releaseAllEngines();
                resolvedUrl = "";
                resolving = false;
                resolve();
                return;
            }
            if (!containerRouteAttempted && routeAllowed("no-extension")) {
                containerRouteAttempted = true;
                sourceVariant = "no-extension";
                attempt = 1;
                releaseAllEngines();
                resolvedUrl = "";
                resolving = false;
                resolve();
                return;
            }
            attempt = 2;
            openVlc(reason);
            return;
        }

        if (restoreCanonicalSource()) {
            attempt = 2;
            openVlc(reason);
            return;
        }
        showFinalPlaybackError(reason);
    }

    private void showFinalPlaybackError'''
text, hits = recover.subn(replacement, text, count=1)
if hits != 1:
    raise SystemExit("verified runner: VOD recover not replaced")
# Reset all alternate route state on manual retry.
text = re.sub(r'(        alternateSourceAttempted = false;\n)(?!        containerRouteAttempted)',
              r'\1        containerRouteAttempted = false;\n', text, count=1)
# Mark only an actually-rendered VOD route verified.
frame_anchor = '''        firstFrame = true;
        spinner.setVisibility(View.GONE);
        main.removeCallbacks(startupTimeout);
'''
if frame_anchor in text and 'rememberVerifiedSuccess(this, resolvedUrl, kind' not in text:
    text = text.replace(frame_anchor, frame_anchor + '''        if (validUrl(resolvedUrl)) {
            ServerPlaybackProfile.rememberVerifiedSuccess(this, resolvedUrl, kind, extension, sourceVariant,
                    usingVlc ? "libvlc" : "media3", PlaybackTransportFactory.userAgent(usingVlc ? 2 : 0),
                    playbackReferer, 0L);
        }
''', 1)
VOD.write_text(text, encoding="utf-8")

# 4) Mini-player: small focus debounce; first item still auto-starts from SevenMaxActivity.
text = PREVIEW.read_text(encoding="utf-8")
text = text.replace('        main.post(pending);', '        main.postDelayed(pending, 380L);', 1)
PREVIEW.write_text(text, encoding="utf-8")

checks = {
    PROFILE: ['blofy_server_playback_profiles_v2', 'rememberVerifiedSuccess', 'rejectRoute'],
    PREFLIGHT: ['rule=3-of-3 required', 'MediaExtractor', 'boolean accepted()'],
    POLICY: ['isHardRouteFailure'],
    PACKAGE: ['cachedGate.accepted()', '!preflight.accepted()'],
    PLAYER: ['if (isLive()) { finish(); return true; }', 'rememberVerifiedSuccess'],
    VOD: ['routeAllowed("direct")', 'routeAllowed("no-extension")', 'rejectVodRouteIfHard'],
    PREVIEW: ['postDelayed(pending, 380L)'],
}
for path, tokens in checks.items():
    value = path.read_text(encoding="utf-8")
    for token in tokens:
        if token not in value:
            raise SystemExit(f"verified runner invariant missing in {path.name}: {token}")
print("v340 verified gate finalized: strict entry gate + verified scoped profiles + canonical-first VOD + live drawer removed + mini-player debounce")
