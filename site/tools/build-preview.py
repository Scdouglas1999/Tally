#!/usr/bin/env python3
"""Build site/preview/: the site as a single-file Artifact preview bundle, for reviewing it before it goes live.

  python3 site/tools/build-preview.py

- preview/index.html is the home page as ONE file: no doctype/html/head/body (the Artifact host wraps it),
  CSS and JS inlined, images and clips as data: URIs, fonts from Google Fonts, every link in a new tab.
- Every other page is a full HTML file next to it, with the CSS and JS inlined and its pictures and clips
  under preview/assets/, so the whole site can be published as one Artifact with supporting files.
- preview/MANIFEST.txt lists what to publish where.

The live site is untouched; run this again after editing a page.
"""
import base64, html, pathlib, re, shutil

SITE = pathlib.Path(__file__).resolve().parent.parent
OUT = SITE / 'preview'
PAGES = ['features', 'live-sports', 'download', 'how-it-works', 'faq', 'changelog', 'support', 'privacy', 'legal']
FONTS = ('<link rel="preconnect" href="https://fonts.googleapis.com">'
         '<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>'
         '<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=IBM+Plex+Mono:wght@400;500;600'
         '&amp;family=IBM+Plex+Sans:wght@300..700&amp;display=swap">')
MIME = {'.webp': 'image/webp', '.png': 'image/png', '.svg': 'image/svg+xml', '.mp4': 'video/mp4', '.ico': 'image/x-icon'}


def css():
    text = (SITE / 'assets/css/site.css').read_text()
    text = re.sub(r'@font-face\s*{[^}]*}', '', text)  # Google Fonts serves IBM Plex instead
    # The preview is always dark, and its body is the host's: give it the ground explicitly.
    return text + '\nhtml, body { background: #0e0f0e !important; color: #e3e5de; margin: 0; }\n'


def js():
    return (SITE / 'assets/js/site.js').read_text()


def data_uri(rel):
    p = SITE / rel
    return f'data:{MIME[p.suffix]};base64,' + base64.b64encode(p.read_bytes()).decode()


def smaller(rel):
    """The small variant of a picture, if there is one (board.webp -> board-640.webp)."""
    p = SITE / rel
    for suffix in ('-640', '-800'):
        alt = p.with_name(p.stem + suffix + p.suffix)
        if alt.exists():
            return str(alt.relative_to(SITE))
    return rel


def new_tab(body):
    def fix(m):
        tag = m.group(0)
        if 'target=' in tag:
            return tag
        return tag[:-1] + ' target="_blank" rel="noopener">'
    body = re.sub(r'<a\s[^>]*href="(?!#)[^"]*"[^>]*>', fix, body)
    return body.replace('href="./"', 'href="index.html"')


def parts(page):
    text = (SITE / f'{page}.html').read_text()
    title = html.unescape(re.search(r'<title>(.*?)</title>', text, re.S).group(1))
    body = re.search(r'<body>\n?(.*)</body>', text, re.S).group(1)
    return title, body


def build_home():
    title, body = parts('index')
    body = re.sub(r'\s+srcset="[^"]*"', '', body)
    body = re.sub(r'\s+sizes="[^"]*"', '', body)
    body = re.sub(r'(src|poster)="(assets/[^"]+)"', lambda m: f'{m.group(1)}="{data_uri(smaller(m.group(2)))}"', body)
    body = re.sub(r'<source src="(assets/[^"]+)"', lambda m: f'<source src="{data_uri(m.group(1))}"', body)
    body = new_tab(body)
    out = ('<title>Tally</title>\n<style>\n' + css() + '</style>\n' + FONTS + '\n'
           + '<script>window.TALLY_OFFLINE = true;</script>\n' + body
           + '<script>\n' + js() + '</script>\n')
    (OUT / 'index.html').write_text(out)
    return len(out.encode())


def build_page(page, used):
    text = (SITE / f'{page}.html').read_text()
    text = re.sub(r'<link rel="preload"[^>]*>\n', '', text)
    text = text.replace('<link rel="stylesheet" href="assets/css/site.css">', FONTS + '\n<style>\n' + css() + '</style>')
    text = text.replace('<script src="assets/js/site.js" defer></script>', '<script>window.TALLY_OFFLINE = true;</script>')
    text = text.replace('</body>', '<script>\n' + js() + '</script>\n</body>')
    text = re.sub(r'<link rel="(icon|apple-touch-icon)"[^>]*>\n', '', text)
    head, body = text.split('<body>', 1)
    body = new_tab(body)
    for m in re.finditer(r'(?:src|poster|href)="(assets/[^"]+)"', body):
        used.add(m.group(1))
    for m in re.finditer(r'srcset="([^"]+)"', body):
        for item in m.group(1).split(','):
            used.add(item.strip().split(' ')[0])
    (OUT / f'{page}.html').write_text(head + '<body>' + body)


def main():
    if OUT.exists():
        shutil.rmtree(OUT)
    OUT.mkdir()
    size = build_home()
    used = set()
    for page in PAGES:
        build_page(page, used)
    for rel in sorted(used):
        dst = OUT / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(SITE / rel, dst)
    lines = [
        'Tally site preview: publish as ONE Artifact with supporting files.',
        '',
        'Page (the Artifact itself; single file, no doctype, everything inlined):',
        f'  site/preview/index.html  ->  (the page)            {size / 1e6:.2f} MB',
        '',
        'Supporting files (published path  <-  source file):',
    ]
    for page in PAGES:
        lines.append(f'  {page}.html  <-  site/preview/{page}.html')
    for rel in sorted(used):
        lines.append(f'  {rel}  <-  site/preview/{rel}')
    lines += ['', 'Not included: 404.html (it only makes sense on the real host).',
              'Rebuild with: python3 site/tools/build-preview.py', '']
    (OUT / 'MANIFEST.txt').write_text('\n'.join(lines))
    print(f'preview/index.html {size / 1e6:.2f} MB, {len(PAGES)} pages, {len(used)} assets')


if __name__ == '__main__':
    main()
