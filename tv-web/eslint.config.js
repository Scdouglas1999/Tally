import js from '@eslint/js';
import tseslint from 'typescript-eslint';
import compat from 'eslint-plugin-compat';

export default tseslint.config(
  { ignores: ['dist/**', 'node_modules/**', 'shell/**', 'test-results/**', 'playwright-report/**'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ['src/**/*.{ts,tsx}'],
    plugins: { compat },
    settings: {
      // 2020 TVs: Tizen 5.5 = Chromium 69, webOS 5 = Chromium 68. Built-ins in src/polyfills.ts are allowed.
      polyfills: [
        'globalThis', 'Array.prototype.flat', 'Array.prototype.flatMap', 'Object.fromEntries', 'Promise.allSettled',
        'String.prototype.matchAll', 'String.prototype.replaceAll', 'queueMicrotask', 'Array.prototype.at',
        'Object.hasOwn', 'Promise.any',
      ],
    },
    rules: {
      'compat/compat': 'error',
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_' }],
    },
  },
  {
    files: ['scripts/**/*.mjs', '*.config.{js,ts}', 'e2e/**/*.ts'],
    languageOptions: { globals: { process: 'readonly', console: 'readonly', __dirname: 'readonly' } },
  },
);
