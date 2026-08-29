from pathlib import Path

build = Path('BLOFY-ANDROID-2026/app/build.gradle.kts')
b = build.read_text(encoding='utf-8')
for n in range(1000340,1000380):
    b = b.replace(f'versionCode = {n}', 'versionCode = 1000354')
for old in ('v340-full-stability-r11e-final3','v340-full-stability-r11e-final2','v340-full-stability-r11e-final','v340-full-stability-r11e-stage5','v340-full-stability-r11e-stage4','v340-full-stability-r11e-stage3','v340-full-stability-r11e-stage2','v340-full-stability-r11e-stage1'):
    b = b.replace(old, 'v340-full-stability-r11e-complete')
build.write_text(b, encoding='utf-8')
print('R11E complete integration marker applied')
