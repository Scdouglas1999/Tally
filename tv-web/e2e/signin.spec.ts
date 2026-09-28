import { expect, test, type Page } from '@playwright/test';
import { APP, approveQuickConnect, shot } from './env';

test('Quick Connect: the code appears, approval on a phone signs the TV in and Home opens', async ({ page }, info) => {
  await page.goto(APP);
  const code = page.locator('.signin .code');
  await expect(code).toHaveText(/^\d{3} \d{3}$/);
  await expect(page.locator('.btn[data-focused]')).toHaveText('USE USERNAME/PASSWORD');
  await shot(page, info, 'signin-quick-connect');
  await approveQuickConnect(((await code.textContent()) ?? '').replace(' ', ''));
  await expect(page.locator('.home')).toBeVisible({ timeout: 30_000 });
  await expect(page.locator('.rail')).toBeVisible();
});

test('Password sign-in: a wrong password shows the error in red', async ({ page }) => {
  await page.goto(APP);
  await expect(page.locator('.signin .code')).toBeVisible();
  await page.keyboard.press('Enter'); // USE USERNAME/PASSWORD
  await expect(page.locator('.signin .kicker')).toHaveText('SIGN IN');
  await expect(page.locator('.field.focused input[placeholder="USERNAME"]')).toBeVisible();
  await page.keyboard.press('Enter'); // start typing in the name field
  await page.keyboard.type('friend');
  await page.keyboard.press('ArrowDown');
  await expect(page.locator('.field.focused input[placeholder="PASSWORD"]')).toBeVisible();
  await page.keyboard.press('Enter');
  await page.keyboard.type('not-the-password');
  await page.keyboard.press('Enter'); // submit from the password field
  await expect(page.locator('.signin .error')).toHaveText('Wrong user name or password.');
});

/** LEFT into the rail, DOWN to `label`, OK. */
async function openFromRail(page: Page, label: string): Promise<void> {
  await page.keyboard.press('ArrowLeft');
  await expect(page.locator('.rail.open')).toBeVisible();
  for (let i = 0; i < 14; i++) {
    const current = (await page.locator('.rail .spot[data-focused] .label').textContent()) ?? '';
    if (current === label) break;
    await page.keyboard.press('ArrowDown');
  }
  await expect(page.locator('.rail .spot[data-focused] .label')).toHaveText(label);
  await page.keyboard.press('Enter');
}

async function quickConnect(page: Page): Promise<void> {
  const code = page.locator('.signin .code');
  await expect(code).toHaveText(/^\d{3} \d{3}$/);
  await approveQuickConnect(((await code.textContent()) ?? '').replace(' ', ''));
}

test('Sign out from Settings, then Quick Connect: the new session opens on Home, not on Settings', async ({ page }, info) => {
  await page.goto(APP);
  await quickConnect(page);
  await expect(page.locator('.page:not(.hidden) .home')).toBeVisible({ timeout: 30_000 });
  await expect(page.locator('.page:not(.hidden) .home [data-focused]')).toHaveCount(1, { timeout: 30_000 });

  // Settings from the rail, SIGN OUT (focused on arrival)
  await openFromRail(page, 'Settings');
  await expect(page.locator('.page:not(.hidden) .settings-page')).toBeVisible();
  await expect(page.locator('.page:not(.hidden) .btn[data-focused]')).toHaveText(/^SIGN OUT$/i);
  // the TV app's line carries the version the bundle was built as (TALLY_VERSION, the release's), never a placeholder
  const manifest = (await (await page.request.get('/manifest.json')).json()) as { version: string };
  expect(manifest.version).toMatch(/^\d+\.\d+\.\d+$/);
  const tv = page.locator('.settings-page tr', { hasText: 'TALLY TV' }).locator('td').nth(1);
  await expect(tv).toHaveText(new RegExp('^' + manifest.version.replace(/\./g, '\\.') + ' · shell '));
  await page.waitForTimeout(300);
  await shot(page, info, 'settings-version');
  await page.keyboard.press('Enter');

  // Quick Connect again: Home, with the rail's light on Home and focus in the page; Settings is gone from the stack
  await quickConnect(page);
  await expect(page.locator('.page:not(.hidden) .home')).toBeVisible({ timeout: 30_000 });
  await expect(page.locator('.settings-page')).toHaveCount(0);
  await expect(page.locator('.rail .entry.selected .label')).toHaveText('Home');
  await expect(page.locator('.page:not(.hidden) .home [data-focused]')).toHaveCount(1, { timeout: 30_000 });
  const depth = await page.evaluate(() => (window as unknown as { TallyDebug: { stack: { get: () => unknown[] } } }).TallyDebug.stack.get().length);
  expect(depth).toBe(1);
  await page.waitForTimeout(400);
  await shot(page, info, 'signin-again-home');

  // BACK on Home opens the rail (nothing under Home to go back to)
  await page.keyboard.press('Escape');
  await expect(page.locator('.rail.open')).toBeVisible();
});
