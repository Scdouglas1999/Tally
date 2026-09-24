import { chromium, type FullConfig } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { APP, AUTH_STATE, approveQuickConnect } from './env';

/** Signs in once through the real Quick Connect flow and saves the session for the other tests. */
export default async function globalSetup(config: FullConfig): Promise<void> {
  const use = config.projects[0]?.use ?? {};
  const browser = await chromium.launch(use.launchOptions);
  const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
  await page.goto(String(use.baseURL) + APP);
  const code = ((await page.locator('.signin .code').textContent({ timeout: 30_000 })) ?? '').replace(/\s/g, '');
  await approveQuickConnect(code);
  await page.waitForSelector('.home', { timeout: 30_000 });
  mkdirSync('e2e/.auth', { recursive: true });
  await page.context().storageState({ path: AUTH_STATE });
  await browser.close();
}
