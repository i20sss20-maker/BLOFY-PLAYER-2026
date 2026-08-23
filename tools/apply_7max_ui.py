from pathlib import Path

path = Path('BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player/MainActivity.java')
text = path.read_text(encoding='utf-8')
needle = '    private void showHome() {'
start = text.find(needle)
if start < 0:
    raise SystemExit('showHome not found')
brace = text.find('{', start)
depth = 0
end = None
for i in range(brace, len(text)):
    if text[i] == '{':
        depth += 1
    elif text[i] == '}':
        depth -= 1
        if depth == 0:
            end = i + 1
            break
if end is None:
    raise SystemExit('showHome end not found')
replacement = '''    private void showHome() {
        screen = "home";
        Intent tvShell = new Intent(this, SevenMaxActivity.class);
        tvShell.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(tvShell);
    }'''
if 'SevenMaxActivity.class' not in text[start:end]:
    text = text[:start] + replacement + text[end:]
path.write_text(text, encoding='utf-8')
print('7 Max style BLOFY shell routed')
