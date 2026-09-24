/// <reference types="vitest/config" />
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, resolve } from 'node:path';
import { execSync } from 'node:child_process';
import { defineConfig, type Plugin } from 'vite';

const require = createRequire(import.meta.url);
const pkg = JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf8')) as { version: string };
const hlsPkg = require('hls.js/package.json') as { version: string };
const HLS_FILE = `hls-${hlsPkg.version}.min.js`;
/** Oldest installed shell this bundle runs in (src/shell-contract/shell.ts MIN_SHELL_VERSION). */
const MIN_SHELL = 1;

function gitRevision(): string {
  try {
    return execSync('git rev-parse --short HEAD', { stdio: ['ignore', 'pipe', 'ignore'] }).toString().trim();
  } catch {
    return 'dev';
  }
}

/**
 * The bundle the Tally plugin serves at /JellyTV/TV/: one classic (non-module) script, one stylesheet, the fonts,
 * hls.js as its own file (browsers only), and manifest.json, which the installed shell reads to know what to load.
 * Classic script because module scripts need CORS on file:// origins, which TV shells are.
 */
function tallyBundle(): Plugin {
  return {
    name: 'tally-bundle',
    apply: 'build',
    enforce: 'post',
    transformIndexHtml: {
      order: 'post',
      handler(html) {
        // <script type="module" crossorigin src=…> in <head> → a deferred classic script; no crossorigin anywhere
        return html
          .replace(/<script type="module" crossorigin src="([^"]+)"><\/script>/, '<script defer src="$1"></script>')
          .replace(/ crossorigin/g, '');
      },
    },
    generateBundle(_options, bundle) {
      const files = Object.values(bundle);
      const js = files.find((f) => f.type === 'chunk' && f.isEntry);
      const css = files.find((f) => f.type === 'asset' && f.fileName.endsWith('.css'));
      if (js === undefined || css === undefined) this.error('bundle: entry script or stylesheet missing: ' + files.map((f) => f.fileName).join(', '));
      this.emitFile({
        type: 'asset',
        fileName: HLS_FILE,
        source: readFileSync(resolve(dirname(require.resolve('hls.js/package.json')), 'dist/hls.min.js')),
      });
      this.emitFile({
        type: 'asset',
        fileName: 'hls.js-LICENSE.txt',
        source: readFileSync(resolve(dirname(require.resolve('hls.js/package.json')), 'LICENSE')),
      });
      this.emitFile({ type: 'asset', fileName: 'fonts-OFL.txt', source: readFileSync(resolve(__dirname, '../IBM-PLEX-OFL.txt')) });
      const manifest = {
        name: 'tally-tv',
        version: pkg.version,
        revision: gitRevision(),
        built: new Date().toISOString(),
        minShell: MIN_SHELL,
        js: js.fileName,
        css: css.fileName,
        hls: HLS_FILE,
      };
      this.emitFile({ type: 'asset', fileName: 'manifest.json', source: JSON.stringify(manifest, null, 2) + '\n' });
    },
  };
}

export default defineConfig({
  base: './',
  define: {
    __TALLY_VERSION__: JSON.stringify(pkg.version),
    __HLS_FILE__: JSON.stringify(HLS_FILE),
  },
  server: {
    // the fonts come from the Android app's res/font (one source of truth)
    fs: { allow: ['..'] },
  },
  build: {
    outDir: 'dist/bundle',
    emptyOutDir: true,
    // Tizen 5.5 (2020) runs Chromium 69, webOS 5 Chromium 68: syntax is lowered to that; built-ins: src/polyfills.ts
    target: 'chrome68',
    cssTarget: 'chrome68',
    modulePreload: false,
    assetsInlineLimit: 0,
    // one stylesheet file (a TV parses CSS faster from a <link> than from a string injected by script)
    cssCodeSplit: false,
    rolldownOptions: {
      output: {
        format: 'iife',
        codeSplitting: false,
        entryFileNames: 'app.[hash].js',
        assetFileNames: (info) => (info.names[0]?.endsWith('.css') === true ? 'app.[hash][extname]' : 'assets/[name].[hash][extname]'),
      },
    },
  },
  plugins: [tallyBundle()],
  test: {
    include: ['tests/**/*.test.ts'],
    environment: 'node',
  },
});
