from pathlib import Path

ROOT = Path('BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player')
helper = ROOT / 'CatalogQueryHints.java'
helper.write_text(r'''package tv.blofy.player;

/** R11E Stage 7: central query/focus tuning hints for very large local catalogs. */
public final class CatalogQueryHints {
    private CatalogQueryHints() {}
    public static final int TV_PAGE_SIZE = 60;
    public static final int LOW_RAM_PAGE_SIZE = 36;
    public static final int DETAIL_PREFETCH_DELAY_MS = 180;
    public static final int DPAD_SETTLE_MS = 55;
    public static final int HOME_SNAPSHOT_TTL_MS = 120000;
}
''', encoding='utf-8')

build = Path('BLOFY-ANDROID-2026/app/build.gradle.kts')
b = build.read_text(encoding='utf-8')
for n in range(1000340,1000360):
    b = b.replace(f'versionCode = {n}', 'versionCode = 1000352')
for old in ('v340-full-stability-r11e-final','v340-full-stability-r11e-stage5','v340-full-stability-r11e-stage4','v340-full-stability-r11e-stage3','v340-full-stability-r11e-stage2','v340-full-stability-r11e-stage1'):
    b = b.replace(old, 'v340-full-stability-r11e-final2')
build.write_text(b, encoding='utf-8')
print('R11E stage7 catalog/search cache hints applied')
