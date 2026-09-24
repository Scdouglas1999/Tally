import { expect, test } from '@playwright/test';
import { APP, AUTH_STATE, SERVER, adminToken, holdOk, shot, stopKey, videoTime } from './env';

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

test('Home: HOLD OK on a game opens its menu, on a card the item menu (MENU too); PLAY plays; the clock', async ({ page }, info) => {
  // playback reports are answered here: the server's resume points stay as they are
  await page.route('**/Sessions/Playing**', (route) => route.fulfill({ status: 204 }));
  await page.goto(APP);
  await expect(page.locator('.home')).toBeVisible();
  await expect(page.locator('.home [data-focused]')).toHaveCount(1, { timeout: 30_000 });
  const focusedCard = page.locator('.home .card[data-focused]');
  const kicker = page.locator('.home-header .kicker');

  // the clock: Android's size and place (the library's: Plex Sans 38 px, 49 px from the right edge)
  const clock = await page.locator('.home-clock').evaluate((el) => ({ size: getComputedStyle(el).fontSize, right: Math.round(el.getBoundingClientRect().right) }));
  expect(clock).toEqual({ size: '38px', right: 1871 });

  // the games row: the Sports card (scores roll: sportsExtras) and HOLD OK's game menu, as on the Sports board
  const games = page.locator('.home .game-card');
  if ((await games.count()) > 0) {
    const card = page.locator('.home .game-card[data-focused]');
    await expect(card).toBeVisible();
    if ((await page.locator('.home .game-card .score').count()) > 0) {
      await expect(page.locator('.home .game-card .score:not(.score-digits)')).toHaveCount(0);
    }
    const id = await card.getAttribute('data-game');
    await holdOk(page);
    const menu = page.locator('.menu-panel');
    await expect(menu).toBeVisible();
    const labels = await menu.locator('.tally-row .label').allTextContents();
    expect(labels).toContain('Hide scores');
    expect(labels.some((l) => /^Follow(ing)? the /.test(l))).toBe(true);
    await page.waitForTimeout(300);
    await shot(page, info, 'home-game-menu');
    // BACK closes it, focus is back on the same card
    await page.keyboard.press('Escape');
    await expect(menu).toHaveCount(0);
    await expect(page.locator(`.home .game-card[data-focused][data-game="${id ?? ''}"]`)).toBeVisible();
  }

  // DOWN to the first row with something playable (Next up, Continue watching: an episode or a film), and along it
  // to that card: HOLD OK opens the item menu (Go to first), BACK closes it on the same card
  const playableIndex = (): Promise<number> =>
    page.evaluate(() => {
      const row = document.querySelector('.page:not(.hidden) .home .card[data-focused]')?.closest('.media-row');
      const cards = Array.from(row?.querySelectorAll('.card') ?? []);
      // an episode's label bar has its S·E kicker, a film's its year
      return cards.findIndex((c) => /^S\d+ E\d+/.test(c.querySelector('.bar .kicker')?.textContent ?? '') || /^\d{4}$/.test(c.querySelector('.bar .detail')?.textContent ?? ''));
    });
  for (let i = 0; i < 8; i++) {
    const now = (await kicker.textContent()) ?? '';
    if ((await focusedCard.count()) > 0 && (await playableIndex()) >= 0) break;
    await page.keyboard.press('ArrowDown');
    // the header follows focus on the next render
    await expect(kicker).not.toHaveText(now);
  }
  const target = await playableIndex();
  expect(target).toBeGreaterThanOrEqual(0);
  for (let i = 0; i < target; i++) await page.keyboard.press('ArrowRight');
  await page.waitForTimeout(200);
  const title = (await focusedCard.locator('.bar .title').textContent()) ?? '';
  await holdOk(page);
  const panel = page.locator('.panel-window');
  await expect(panel).toBeVisible();
  await expect(page.locator('.panel-row[data-focused] .headline')).toHaveText('Go to');
  const entries = await panel.locator('.panel-row .headline').allTextContents();
  expect(entries).toEqual(expect.arrayContaining(['Go to', 'Add to playlist']));
  expect(entries.some((e) => /^Mark (un)?watched$/.test(e))).toBe(true);
  await page.waitForTimeout(200);
  await shot(page, info, 'home-item-menu');
  await page.keyboard.press('Escape');
  await expect(panel).toHaveCount(0);
  await expect(focusedCard.locator('.bar .title')).toHaveText(title);
  // MENU opens the same menu; Favorite toggles in place (the row reloads: the card's square), and back
  await page.keyboard.press('ContextMenu');
  await expect(panel).toBeVisible();
  const favRow = panel.locator('.panel-row .headline', { hasText: /^(Un)?[Ff]avorite$/ });
  const wasFavorite = ((await favRow.textContent()) ?? '') === 'Unfavorite';
  let toggled: string | null = null;
  try {
    for (let i = 0; i < 8 && !/^(Un)?[Ff]avorite$/.test((await page.locator('.panel-row[data-focused] .headline').textContent()) ?? ''); i++) await page.keyboard.press('ArrowDown');
    const request = page.waitForRequest((r) => r.url().includes('/UserFavoriteItems/'));
    await page.keyboard.press('Enter');
    toggled = new URL((await request).url()).pathname.split('/').pop() ?? null;
    await expect(panel).toHaveCount(0);
    await expect(focusedCard.locator('.favorite')).toHaveCount(wasFavorite ? 0 : 1);
    await shot(page, info, 'home-item-favorite');
    await page.keyboard.press('ContextMenu');
    for (let i = 0; i < 8 && !/^(Un)?[Ff]avorite$/.test((await page.locator('.panel-row[data-focused] .headline').textContent()) ?? ''); i++) await page.keyboard.press('ArrowDown');
    await page.keyboard.press('Enter');
    await expect(focusedCard.locator('.favorite')).toHaveCount(wasFavorite ? 1 : 0);
    toggled = null;
  } finally {
    // put the favorite back if the test stopped halfway
    if (toggled !== null) await fetch(`${SERVER}/UserFavoriteItems/${toggled}`, { method: wasFavorite ? 'POST' : 'DELETE', headers: { Authorization: `MediaBrowser Token="${adminToken()}"` } });
  }

  // not watched yet: the header ends with its end time (a watched one has none: the library's Recommended test)
  await expect(page.locator('.home-header .meta')).toContainText('ENDS');
  await shot(page, info, 'home-card-header');

  // PLAY on the card plays it; STOP comes back to the same card
  await page.keyboard.press('MediaPlayPause');
  await expect(page.locator('.player')).toBeVisible();
  await expect.poll(() => videoTime(page), { timeout: 30_000 }).toBeGreaterThan(0.5);
  await page.keyboard.press('ArrowUp');
  await expect(page.locator('.player .pc-top .title')).toHaveText(title);
  await stopKey(page);
  await expect(page.locator('.page:not(.hidden) .home')).toBeVisible();
  await expect(focusedCard.locator('.bar .title')).toHaveText(title);
});
