import { expect, test } from '@playwright/test';
import { APP, AUTH_STATE, shot } from './env';

test.use({ storageState: AUTH_STATE });

test('Home: games row from the plugin board, library rows, header follows focus, rail opens on LEFT', async ({ page }, info) => {
  await page.goto(APP);
  await expect(page.locator('.home')).toBeVisible();
  const headers = page.locator('.media-row .row-header .title');
  await expect(headers.first()).toBeVisible();
  const titles = await headers.allTextContents();
  expect(titles.some((t) => t.startsWith('RECENTLY ADDED IN'))).toBe(true);
  const games = page.locator('.game-card');
  if ((await games.count()) > 0) {
    expect(titles[0]).toMatch(/^(LIVE NOW|TODAY'S GAMES)$/);
    await expect(games.first()).toHaveAttribute('data-focused', 'true');
    await expect(page.locator('.home-header .title')).toContainText(' at ');
  }
  await shot(page, info, 'home');

  await page.keyboard.press('ArrowDown');
  const card = page.locator('.card[data-focused]');
  await expect(card).toBeVisible();
  await expect(page.locator('.home-header .kicker')).not.toHaveText('');
  await shot(page, info, 'home-card-focused');

  await page.keyboard.press('ArrowLeft');
  await expect(page.locator('.rail.open')).toBeVisible();
  await expect(page.locator('.rail .entry.selected .label')).toHaveText('Home');
  await shot(page, info, 'drawer-open');
  await page.keyboard.press('ArrowRight');
  await expect(page.locator('.rail.open')).toHaveCount(0);
});
