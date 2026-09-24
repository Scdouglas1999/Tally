#!/usr/bin/env node
// Guards the 2020-TV floor (Chromium 68, webOS 5; Tizen 5.5 is 69):
//  - every built script must parse as ES2019 (Chromium 68 runs ES2018 plus optional catch binding, the only ES2019 syntax; anything newer was not lowered by the build);
//  - source stylesheets must not use CSS that Chromium 68 ignores (flexbox gap, aspect-ratio, inset, clamp/min/max,
//    :is/:where, :focus-visible, text-transform on labels is a style rule, see base.css).
// Usage: node scripts/check-legacy.mjs [bundle dir]   (run after `npm run build`; `npm run lint` runs the CSS half)
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, extname } from 'node:path';
import { parse } from 'acorn';

const failures = [];

function walk(dir, ext, out = []) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, ext, out);
    else if (extname(p) === ext) out.push(p);
  }
  return out;
}

const CSS_RULES = [
  [/(^|[;{\s])gap\s*:/, 'gap (flexbox gap is Chromium 84)'],
  [/row-gap\s*:|column-gap\s*:/, 'row-gap/column-gap'],
  [/aspect-ratio\s*:/, 'aspect-ratio (Chromium 88)'],
  [/(^|[;{\s])inset\s*:/, 'inset (Chromium 87)'],
  [/\b(clamp|min|max)\(/, 'clamp()/min()/max() (Chromium 79)'],
  [/:is\(|:where\(/, ':is()/:where() (Chromium 88)'],
  [/:focus-visible/, ':focus-visible (Chromium 86)'],
];

for (const file of walk('src', '.css')) {
  const text = readFileSync(file, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
  for (const [re, what] of CSS_RULES) if (re.test(text)) failures.push(`${file}: ${what}`);
}

const bundle = process.argv[2];
if (bundle !== undefined) {
  for (const file of walk(bundle, '.js')) {
    if (/hls-[\d.]+\.min\.js$/.test(file)) continue; // hls.js is only loaded by desktop browsers
    try {
      parse(readFileSync(file, 'utf8'), { ecmaVersion: 2019, sourceType: 'script' });
    } catch (e) {
      failures.push(`${file}: not ES2019 (${e.message})`);
    }
  }
}

if (failures.length > 0) {
  console.error('Legacy check failed:\n  ' + failures.join('\n  '));
  process.exit(1);
}
console.log('Legacy check passed' + (bundle !== undefined ? ` (${bundle})` : ' (CSS)'));
