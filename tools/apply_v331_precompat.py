from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PATH = ROOT / "BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player/PlayerActivity.java"
text = PATH.read_text(encoding="utf-8")

old = '''    private DataSource.Factory createDataSourceFactory() {\n        // Avoid an identical queued retry while the Cronet provider is still\n        // installing. The compatibility fallback is a different decoder/stack.\n        return PlaybackTransportFactory.create(this, false, network,\n                3_500, 10_000, recoveryStep, playbackReferer);\n    }\n'''
new = '''    private DataSource.Factory createDataSourceFactory() {\n        // Avoid an identical queued retry while the Cronet provider is still\n        // installing. The compatibility fallback is a different decoder/stack.\n        return PlaybackTransportFactory.create(this, false, network,\n                15_000, 30_000, recoveryStep, playbackReferer);\n    }\n'''

if old not in text:
    raise SystemExit("v331 precompat mismatch: PlayerActivity data-source block")
PATH.write_text(text.replace(old, new, 1), encoding="utf-8")
print("v331 precompat normalized")
