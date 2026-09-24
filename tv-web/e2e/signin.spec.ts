import { expect, test } from '@playwright/test';
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
