#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "tools/apply_v340_r11_negotiator.py"
source = BASE.read_text(encoding="utf-8")

old = '''if needle in p: p = p.replace(needle, insert, 1)\n\n# Runtime error after first frame:'''
new = '''if needle in p:
    p = p.replace(needle, insert, 1)
# R10's reconstructed PlayerActivity has slightly different comments around the
# same network-failure branch. Fall back to the executable anchor, never to line
# numbers, so R11 remains stable across reconstruction layers.
if "r11-live-format-fallback" not in p:
    generic = "        if (PlaybackPolicy.isNetworkFailure(reason)"
    pos = p.find(generic)
    if pos < 0:
        raise SystemExit("r11: generic live network recovery anchor missing")
    block = '''        // R11 adaptive live format negotiation.\n        if (isLive() && PlaybackNegotiator.hardHttpFailure(reason) && \"canonical\".equals(sourceVariant)\n                && !id.isEmpty()) {\n            String alternate = PlaybackPolicy.alternateLiveExtension(extension);\n            if (alternate != null && !alternate.isEmpty() && !alternate.equals(extension)) {\n                PlaybackDiagnostics.marker(this, \"r11-live-format-fallback\", \"live\", id, extension,\n                        \"canonical\", \"reason=\" + reason + \" next=\" + alternate);\n                releasePlayer();\n                extension = alternate;\n                sourceVariant = \"canonical\";\n                url = null;\n                recoveryStep = 0;\n                resolvePlaybackLink();\n                return;\n            }\n        }\n\n'''
    p = p[:pos] + block + p[pos:]

# Runtime error after first frame:'''
if old not in source:
    raise SystemExit("r11 wrapper: expected insertion point not found")
source = source.replace(old, new, 1)
exec(compile(source, str(BASE), "exec"), {"__name__": "__main__", "__file__": str(BASE)})
