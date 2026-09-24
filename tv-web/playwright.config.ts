import { defineConfig } from '@playwright/test';

/**
 * End-to-end tests in desktop Chromium at 1920x1080 against a real Jellyfin with the Tally plugin (the dev server by
 * default). `npm run build` first: the tests run the production bundle through `vite preview`.
 *   TALLY_SERVER      Jellyfin address (default http://127.0.0.1:18200)
 *   TALLY_TOKEN_FILE  an admin access token, used to approve the Quick Connect code the app shows
 *   CHROMIUM          browser binary (default /usr/bin/chromium; unset to use Playwright's own)
 *   TALLY_PREVIEW_PORT  port of the `vite preview` the tests start (default 4173; parallel worktrees use their own)
 * The preview is always this checkout's own: a server already on the port (another worktree's bundle) is never reused,
 * the run stops instead ("port already in use").
 */
const PORT = process.env.TALLY_PREVIEW_PORT ?? '4173';
export default defineConfig({
  testDir: 'e2e',
  timeout: 90_000,
  expect: { timeout: 20_000 },
  workers: 1,
  reporter: [['list'], ['html', { open: 'never' }]],
  globalSetup: './e2e/global-setup.ts',
  use: {
    baseURL: `http://127.0.0.1:${PORT}`,
    viewport: { width: 1920, height: 1080 },
    launchOptions: {
      executablePath: process.env.CHROMIUM ?? '/usr/bin/chromium',
      args: ['--autoplay-policy=no-user-gesture-required'],
    },
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
  webServer: {
    command: `npx vite preview --port ${PORT} --strictPort --host 127.0.0.1`,
    url: `http://127.0.0.1:${PORT}/manifest.json`,
    reuseExistingServer: false,
  },
});
