from pathlib import Path

p = Path('BLOFY-ANDROID-2026/app/src/main/java/tv/blofy/player/SevenMaxActivity.java')
s = p.read_text(encoding='utf-8')
needle = '    private LivePreviewController livePreview;\n'
count = s.count(needle)
if count > 1:
    first = s.find(needle)
    before = s[:first + len(needle)]
    after = s[first + len(needle):].replace(needle, '')
    s = before + after
    p.write_text(s, encoding='utf-8')
print(f'compile guard: livePreview declarations={count} -> {s.count(needle)}')
