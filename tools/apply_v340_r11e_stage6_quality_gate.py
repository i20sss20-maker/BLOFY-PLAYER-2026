from pathlib import Path

ROOT = Path('BLOFY-ANDROID-2026/app/src/main')
prof = ROOT / 'baseline-prof.txt'
if prof.exists():
    s = prof.read_text(encoding='utf-8')
else:
    s = ''
for rule in [
    'HSPLtv/blofy/player/MainActivity;->onCreate(Landroid/os/Bundle;)V',
    'HSPLtv/blofy/player/PlayerActivity;->onCreate(Landroid/os/Bundle;)V',
    'HSPLtv/blofy/player/VodPlayerActivity;->onCreate(Landroid/os/Bundle;)V',
    'HSPLtv/blofy/player/PlaybackNegotiator;-><init>()V',
]:
    if rule not in s:
        s += ('\n' if s and not s.endswith('\n') else '') + rule + '\n'
prof.write_text(s, encoding='utf-8')

build = Path('BLOFY-ANDROID-2026/app/build.gradle.kts')
b = build.read_text(encoding='utf-8')
for n in range(1000340,1000360):
    b = b.replace(f'versionCode = {n}', 'versionCode = 1000351')
for old in ('v340-full-stability-r11e-stage5','v340-full-stability-r11e-stage4','v340-full-stability-r11e-stage3','v340-full-stability-r11e-stage2','v340-full-stability-r11e-stage1'):
    b = b.replace(old, 'v340-full-stability-r11e-final')
build.write_text(b, encoding='utf-8')
print('R11E final quality gate applied')
