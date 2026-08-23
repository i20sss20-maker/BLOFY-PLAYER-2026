from pathlib import Path

ROOT = Path('BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player')
GRADLE = Path('BLOFY-ANDROID-2026/app/build.gradle.kts')


def replace_required(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f'{label}: pattern not found')
    return text.replace(old, new, 1)

# AndroidX Activity gives us the backward-compatible predictive-back dispatcher.
gradle = GRADLE.read_text(encoding='utf-8')
if 'androidx.activity:activity:' not in gradle:
    gradle = replace_required(
        gradle,
        '    implementation("androidx.recyclerview:recyclerview:1.4.0")',
        '    implementation("androidx.recyclerview:recyclerview:1.4.0")\n    implementation("androidx.activity:activity:1.10.1")',
        'androidx activity dependency',
    )
GRADLE.write_text(gradle, encoding='utf-8')

# Media3 UnstableApi is an implementation detail here. Opt in internally instead
# of marking our own classes unstable and forcing every caller to opt in.
for filename in ('PlaybackTransportFactory.java', 'PlayerActivity.java'):
    path = ROOT / filename
    text = path.read_text(encoding='utf-8')
    if 'import androidx.annotation.OptIn;' not in text:
        marker = 'import androidx.media3.common.util.UnstableApi;'
        text = replace_required(text, marker, 'import androidx.annotation.OptIn;\n' + marker, f'{filename} OptIn import')
    text = text.replace('@UnstableApi\n', '@OptIn(markerClass = UnstableApi.class)\n', 1)
    path.write_text(text, encoding='utf-8')

# MainActivity: predictive-back aware navigation and API-23-safe progress updates.
path = ROOT / 'MainActivity.java'
text = path.read_text(encoding='utf-8')
text = replace_required(text, 'import android.app.Activity;\n', '', 'MainActivity Activity import')
anchor = 'import androidx.recyclerview.widget.GridLayoutManager;'
text = replace_required(
    text,
    anchor,
    'import androidx.activity.ComponentActivity;\nimport androidx.activity.OnBackPressedCallback;\n' + anchor,
    'MainActivity activity imports',
)
text = replace_required(text, 'public final class MainActivity extends Activity {', 'public final class MainActivity extends ComponentActivity {', 'MainActivity base class')
text = replace_required(
    text,
    '        super.onCreate(state);\n        PlaybackTransportFactory.warmUpCronet(this);',
    '        super.onCreate(state);\n        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {\n            @Override public void handleOnBackPressed() { handleBackNavigation(); }\n        });\n        PlaybackTransportFactory.warmUpCronet(this);',
    'MainActivity back callback',
)
text = text.replace('progress.setProgress(value, true);', 'progress.setProgress(value);')
old_back = '''    @Override
    public void onBackPressed() {
        if ("import".equals(screen)) {
            Toast.makeText(this, "انتظر حتى تكتمل قراءة الباقة 100٪", Toast.LENGTH_SHORT).show();
            return;
        }
        if ("home".equals(screen) || "login".equals(screen) || "splash".equals(screen)) finish();
        else showHome();
    }'''
new_back = '''    private void handleBackNavigation() {
        if ("import".equals(screen)) {
            Toast.makeText(this, "انتظر حتى تكتمل قراءة الباقة 100٪", Toast.LENGTH_SHORT).show();
            return;
        }
        if ("home".equals(screen) || "login".equals(screen) || "splash".equals(screen)) finish();
        else showHome();
    }'''
text = replace_required(text, old_back, new_back, 'MainActivity old back override')
path.write_text(text, encoding='utf-8')

# SevenMaxActivity: same backward-compatible back dispatcher.
path = ROOT / 'SevenMaxActivity.java'
text = path.read_text(encoding='utf-8')
text = replace_required(text, 'import android.app.Activity;\n', '', 'SevenMax Activity import')
anchor = 'import androidx.recyclerview.widget.GridLayoutManager;'
text = replace_required(
    text,
    anchor,
    'import androidx.activity.ComponentActivity;\nimport androidx.activity.OnBackPressedCallback;\n' + anchor,
    'SevenMax activity imports',
)
text = replace_required(text, 'public final class SevenMaxActivity extends Activity {', 'public final class SevenMaxActivity extends ComponentActivity {', 'SevenMax base class')
text = replace_required(
    text,
    '    @Override protected void onCreate(Bundle state) {\n        super.onCreate(state);',
    '    @Override protected void onCreate(Bundle state) {\n        super.onCreate(state);\n        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {\n            @Override public void handleOnBackPressed() {\n                if ("home".equals(screen)) finishAffinity(); else showHome();\n            }\n        });',
    'SevenMax back callback',
)
text = replace_required(
    text,
    '    @Override public void onBackPressed(){ if("home".equals(screen)) finishAffinity(); else showHome(); }\n',
    '',
    'SevenMax old back override',
)
path.write_text(text, encoding='utf-8')

# PlayerActivity: back gestures and TV remote BACK both flow through the dispatcher.
path = ROOT / 'PlayerActivity.java'
text = path.read_text(encoding='utf-8')
text = replace_required(text, 'import android.app.Activity;\n', '', 'Player Activity import')
anchor = 'import androidx.annotation.Nullable;'
text = replace_required(
    text,
    anchor,
    'import androidx.activity.ComponentActivity;\nimport androidx.activity.OnBackPressedCallback;\n' + anchor,
    'Player activity imports',
)
text = replace_required(text, 'public final class PlayerActivity extends Activity implements Player.Listener {', 'public final class PlayerActivity extends ComponentActivity implements Player.Listener {', 'Player base class')
text = replace_required(
    text,
    '        super.onCreate(savedInstanceState);',
    '        super.onCreate(savedInstanceState);\n        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {\n            @Override public void handleOnBackPressed() { finish(); }\n        });',
    'Player back callback',
)
text = replace_required(
    text,
    '''                case KeyEvent.KEYCODE_BACK:
                    finish();
                    return true;
''',
    '',
    'Player intercepted back key',
)
text = replace_required(
    text,
    '''    @Override
    public void onBackPressed() {
        finish();
    }

''',
    '',
    'Player old back override',
)
path.write_text(text, encoding='utf-8')

print('BLOFY lint fixes applied: predictive back, API 23 progress, Media3 opt-in')
