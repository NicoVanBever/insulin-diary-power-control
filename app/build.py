"""Builds the single app page (app + translations) and copies it into the Windows and Android projects."""
import os, subprocess, sys
here = os.path.dirname(os.path.abspath(__file__)); root = os.path.dirname(here)
subprocess.run([sys.executable, os.path.join(here, 'i18n_src.py')], check=True)
src = open(os.path.join(here, 'Insulin-Diary.html'), encoding='utf-8').read()
i18n = open(os.path.join(here, 'i18n.js'), encoding='utf-8').read()
tag = '<script src="i18n.js" data-inline></script>'
assert src.count(tag) == 1
out = src.replace(tag, '<script>\n' + i18n + '\n</script>')
for dst in ['windows/app.html', 'android/InsulinDiary/app/src/main/assets/index.html', 'dist/Insulin-Diary.html']:
    p = os.path.join(root, dst); os.makedirs(os.path.dirname(p), exist_ok=True)
    open(p, 'w', encoding='utf-8').write(out)
print('built', len(out) // 1024, 'KB')
