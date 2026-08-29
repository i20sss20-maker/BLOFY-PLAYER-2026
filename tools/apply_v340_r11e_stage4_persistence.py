from pathlib import Path

ROOT = Path('BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player')
importer = ROOT / 'PackageImporter.java'
text = importer.read_text(encoding='utf-8')

marker = 'r11e-stage4-package-persistence'
if marker not in text:
    insert = r'''

    // r11e-stage4-package-persistence
    // Stage 4 intentionally keeps the existing database schema/version untouched.
    // The package importer must treat a completed local snapshot as authoritative and
    // avoid reconnect/onResume reloads unless the user explicitly requests refresh
    // or integrity validation reports the snapshot incomplete.
    private static final String R11E_COMPLETE_KEY = "r11e_package_complete";
    private static final String R11E_FINGERPRINT_KEY = "r11e_package_fingerprint";
    private static final String R11E_COUNTS_KEY = "r11e_package_counts";

    private boolean r11eHasCompleteSnapshot(android.content.Context context, String fingerprint, String counts) {
        android.content.SharedPreferences p = context.getSharedPreferences("blofy_package_state", android.content.Context.MODE_PRIVATE);
        if (!p.getBoolean(R11E_COMPLETE_KEY, false)) return false;
        String savedFingerprint = p.getString(R11E_FINGERPRINT_KEY, "");
        String savedCounts = p.getString(R11E_COUNTS_KEY, "");
        if (fingerprint != null && !fingerprint.isEmpty() && !fingerprint.equals(savedFingerprint)) return false;
        return counts == null || counts.isEmpty() || savedCounts.isEmpty() || counts.equals(savedCounts);
    }

    private void r11eMarkCompleteSnapshot(android.content.Context context, String fingerprint, String counts) {
        context.getSharedPreferences("blofy_package_state", android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean(R11E_COMPLETE_KEY, true)
                .putString(R11E_FINGERPRINT_KEY, fingerprint == null ? "" : fingerprint)
                .putString(R11E_COUNTS_KEY, counts == null ? "" : counts)
                .apply();
    }

    private void r11eInvalidateSnapshot(android.content.Context context) {
        context.getSharedPreferences("blofy_package_state", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean(R11E_COMPLETE_KEY, false).apply();
    }
'''
    idx = text.rfind('\n}')
    if idx == -1:
        raise SystemExit('PackageImporter.java class end not found')
    text = text[:idx] + insert + text[idx:]
    importer.write_text(text, encoding='utf-8')

build = Path('BLOFY-ANDROID-2026/app/build.gradle.kts')
b = build.read_text(encoding='utf-8')
b = b.replace('versionCode = 1000348', 'versionCode = 1000349')
b = b.replace('versionCode = 1000347', 'versionCode = 1000349')
b = b.replace('versionCode = 1000346', 'versionCode = 1000349')
for old in ('v340-full-stability-r11e-stage3','v340-full-stability-r11e-stage2','v340-full-stability-r11e-stage1'):
    b = b.replace(old, 'v340-full-stability-r11e-stage4')
build.write_text(b, encoding='utf-8')

print('R11E stage4 package persistence guard applied')
