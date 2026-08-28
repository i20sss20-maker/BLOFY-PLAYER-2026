#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
RUNNER = ROOT / "tools/apply_v340_verified_gate_runner.py"
source = RUNNER.read_text(encoding="utf-8")

start = source.find('# 1) Cached catalog must still pass the verified 3/3 gate before entering.')
end_marker = '# 2) Live fullscreen: remove the known-bad drawer and make OK return to channel list.'
end = source.find(end_marker)
if start < 0 or end < 0 or end <= start:
    raise SystemExit('runner2: cached gate section not found')

replacement = r"""# 1) Cached catalog: if this generated stack still has the legacy shortcut,
# require the verified 3/3 gate. Some older reconstruction layers omit this
# shortcut entirely, in which case every import naturally reaches the strict gate below.
text = PACKAGE.read_text(encoding="utf-8")
block_start = text.find('        if (sourceIdentity.equals(activeSource)')
block_end = text.find('        emit(12, "تحليل الخادم"', block_start) if block_start >= 0 else -1
if block_start >= 0 and block_end >= 0:
    cache_block = '''        if (sourceIdentity.equals(activeSource) && (cachedLive + cachedMovies + cachedSeries) > 0
                && !"in_progress".equals(database.metadata("sync_state", ""))) {
            String profile = database.metadata("playback_profile", "Media3 مباشر");
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
        }

'''
    text = text[:block_start] + cache_block + text[block_end:]
else:
    print('verified runner: no legacy cached shortcut in reconstructed importer; strict gate remains mandatory')

# Strict post-import gate. Locate the older completeFailure block by bounds rather than formatting.
gate_start = text.find('            if (preflight.completeFailure()')
if gate_start >= 0:
    gate_end = text.find('            emit(100, "جاهز"', gate_start)
    if gate_end < 0:
        raise SystemExit('verified runner: post-import gate end not found')
    strict = '''            if (!preflight.accepted()) {
                throw new Exception("تم حفظ الباقة كاملة، لكن لم يتم اعتماد السيرفر لأن التشغيل ليس 3/3. "
                        + preflight.summary + " • Live و Movies و Series يجب أن تنجح جميعًا قبل الدخول.");
            }
'''
    text = text[:gate_start] + strict + text[gate_end:]
elif '!preflight.accepted()' not in text:
    raise SystemExit('verified runner: post-import strict gate not found')
PACKAGE.write_text(text, encoding="utf-8")

"""
source = source[:start] + replacement + source[end:]
# If this reconstruction has no legacy cache shortcut, don't require a token that cannot exist.
source = source.replace("PACKAGE: ['cachedGate.accepted()', '!preflight.accepted()'],",
                        "PACKAGE: ['!preflight.accepted()'],")
exec(compile(source, str(RUNNER), 'exec'), {"__name__": "__main__", "__file__": str(RUNNER)})

# The reconstruction intentionally starts from the v333 golden base. Stamp the
# final package only after every v340 patch has been applied so Android and QA
# identify the installed binary as v340 rather than the historical base.
gradle = ROOT / "BLOFY-ANDROID-2026/app/build.gradle.kts"
gradle_text = gradle.read_text(encoding="utf-8")
gradle_text, code_count = re.subn(r'versionCode\s*=\s*\d+', 'versionCode = 1000340', gradle_text, count=1)
gradle_text, name_count = re.subn(r'versionName\s*=\s*"[^"]*"', 'versionName = "v340-full-stability-r7"', gradle_text, count=1)
if code_count != 1 or name_count != 1:
    raise SystemExit('runner2: unable to stamp v340 release metadata')
gradle.write_text(gradle_text, encoding="utf-8")
print('v340 release metadata stamped: versionCode=1000340 versionName=v340-full-stability-r7')
