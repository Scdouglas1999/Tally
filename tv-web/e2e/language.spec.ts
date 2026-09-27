import { expect, test, type Page, type Route } from '@playwright/test';
import { APP, AUTH_STATE, api, holdOk, shot } from './env';

/**
 * Tally 2.3 (the Apps part of the 2.3 contract): commentary language and the RedZone channel. The dev server runs a
 * plugin older than 2.3, so the board is edited on its way to the app: a live game gets a Spanish feed (a real
 * channel's stream under a Spanish name), another plays in Spanish (the ES chip), and a RedZone channel (another real
 * stream) joins `channels`; `GET redzone` is answered by a route. The settings document is the server's own (it keeps
 * unknown keys), put back as it was.
 */
test.use({ storageState: AUTH_STATE });

interface Watch {
  channelId: string;
  channelName: string;
  hlsPath: string;
  liveTvItemId: string | null;
  cardPath: string;
  confidence: string;
  language?: string;
}
interface BoardGame {
  id: string;
  state: string;
  league: string;
  home: { shortName: string; abbr: string };
  away: { shortName: string; abbr: string };
  watch?: Watch | null;
  feeds?: Array<{ language: string; label: string; watch: Watch }>;
}
interface BoardChannel {
  id: string;
  name: string;
  group: string;
  hlsPath: string;
  cardPath: string;
  gameId: string | null;
  language?: string;
  kind?: string;
  now?: unknown;
}
type Board = { games: BoardGame[]; channels: BoardChannel[] } & Record<string, unknown>;

const BOARD = '**/JellyTV/Client/v1/board*';
const REDZONE = '**/JellyTV/Client/v1/redzone';

const push = (page: Page, route: unknown) => page.evaluate((r) => (window as unknown as { TallyDebug: { push: (r: unknown) => void } }).TallyDebug.push(r), route);

const title = (g: BoardGame): string => `${g.away.shortName || g.away.abbr} at ${g.home.shortName || g.home.abbr}`;

interface Setup {
  /** The live game with an English and a Spanish feed. */
  both: BoardGame;
  spanishId: string;
  /** The live game that plays in Spanish (the ES chip). */
  spanish: BoardGame;
  /** GET redzone calls so far. */
  calls: () => number;
}

/** The dev server's board, with the 2.3 fields added (see the file comment). Skips when fewer than 2 live games play. */
async function setUp(page: Page): Promise<Setup | null> {
  const real = await api<Board>('/JellyTV/Client/v1/board');
  const live = real.games.filter((g) => g.state === 'in' && g.watch != null && g.watch.hlsPath !== '');
  const spare = real.channels.filter((c) => c.hlsPath !== '' && !live.some((g) => g.watch?.channelId === c.id));
  const [both, spanish] = live;
  const esStream = spare[0];
  const rzStream = spare[1] ?? spare[0];
  if (both === undefined || spanish === undefined || esStream === undefined || rzStream === undefined || both.watch == null) return null;
  const spanishId = 'e2e-es-' + both.id;
  const esWatch: Watch = { ...both.watch, channelId: spanishId, channelName: both.watch.channelName + ' (Español)', hlsPath: esStream.hlsPath, language: 'es' };
  await page.route(BOARD, async (route: Route) => {
    let response;
    let json: Board;
    try {
      response = await route.fetch();
      json = (await response.json()) as Board;
    } catch {
      return; // the page went away (the test ended) while the board was on its way
    }
    for (const g of json.games) {
      if (g.watch != null) g.watch.language = 'en';
      if (g.id === both.id && g.watch != null) {
        g.feeds = [
          { language: 'en', label: 'English', watch: { ...g.watch, language: 'en' } },
          { language: 'es', label: 'Español', watch: esWatch },
        ];
      }
      if (g.id === spanish.id && g.watch != null) {
        g.watch.language = 'es';
        g.watch.channelName += ' (Español)';
      }
    }
    json.channels.unshift({ id: 'redzone', name: 'Tally RedZone', group: 'RedZone', hlsPath: rzStream.hlsPath, cardPath: '', gameId: null, kind: 'redzone', language: 'en' });
    json.channels.push({ id: spanishId, name: esWatch.channelName, group: 'Baseball · Español', hlsPath: esStream.hlsPath, cardPath: '', gameId: both.id, language: 'es' });
    await route.fulfill({ response, json }).catch(() => undefined);
  });
  let calls = 0;
  await page.route(REDZONE, (route: Route) => {
    calls++;
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ active: true, gameId: both.id, title: title(both), reason: 'red zone', since: new Date().toISOString(), next: [spanish.id] }),
    });
  });
  return { both, spanishId, spanish, calls: () => calls };
}

async function openSports(page: Page): Promise<void> {
  await page.goto(APP);
  await expect(page.locator('.home')).toBeVisible();
  await push(page, { name: 'sports' });
  await expect(page.locator('.page:not(.hidden) .sports-page')).toBeVisible();
  await expect(page.locator('.page:not(.hidden) .game-card[data-focused]')).toBeVisible();
}

/** Moves focus on the board to the card `data-game` = `id` (the RedZone tile's is "redzone"). */
async function focusCard(page: Page, id: string): Promise<void> {
  for (let i = 0; i < 80; i++) {
    const step = await page.evaluate((gameId) => {
      const rows = document.querySelector('.page:not(.hidden) .board-rows');
      const focused = rows?.querySelector('.game-card[data-focused]') ?? null;
      const target = rows?.querySelector(`.game-card[data-game="${gameId}"]`) ?? null;
      if (focused === null || target === null) return 'missing';
      if (focused === target) return 'here';
      const fr = focused.closest('.media-row');
      const tr = target.closest('.media-row');
      if (fr !== tr) return (tr?.getBoundingClientRect().top ?? 0) > (fr?.getBoundingClientRect().top ?? 0) ? 'ArrowDown' : 'ArrowUp';
      return target.getBoundingClientRect().left > focused.getBoundingClientRect().left ? 'ArrowRight' : 'ArrowLeft';
    }, id);
    if (step === 'here') return;
    if (step === 'missing') throw new Error(`card ${id} is not on the board (or no card has focus)`);
    await page.keyboard.press(step);
    await page.waitForTimeout(120);
  }
  throw new Error(`could not reach card ${id}`);
}

const playing = (page: Page) =>
  expect
    .poll(() => page.evaluate(() => ((document.querySelector('.player.live video') as HTMLVideoElement | null)?.currentTime ?? 0) > 1), { timeout: 45_000 })
    .toBe(true);

test('RedZone: the tile leads the live row, its panel says what is on and why; OK plays it with ON REDZONE NOW, polled every 10 s only while it plays', async ({ page }, info) => {
  const s = await setUp(page);
  test.skip(s === null, 'needs two live games with streams and two spare channels on the dev board');
  if (s === null) return;
  await openSports(page);
  const tile = page.locator('.page:not(.hidden) .game-card.redzone-card');
  await expect(tile).toHaveCount(1);
  // first in the first live row
  const firstInRow = await tile.evaluate((el) => el.parentElement?.firstElementChild === el && el.closest('.media-row')?.querySelector('.row-header .title')?.textContent);
  expect(firstInRow).toMatch(/LIVE$/);
  await expect(tile.locator('.rz-title')).toHaveText(title(s.both));
  await expect(tile.locator('.rz-reason')).toHaveText('RED ZONE');
  await focusCard(page, 'redzone');
  const panel = page.locator('.page:not(.hidden) .hero-panel.redzone-panel');
  await expect(panel).toBeVisible();
  await expect(panel.locator('.rz-hero-title')).toHaveText(title(s.both));
  await expect(panel.locator('.hero-body').first()).toContainText('lighter than multiview');
  await expect(panel.locator('.hero-body.muted')).toContainText(title(s.spanish));
  await page.waitForTimeout(400);
  await shot(page, info, 'redzone-tile');

  // the ES chip on the game that plays in Spanish
  await expect(page.locator(`.page:not(.hidden) .game-card[data-game="${s.spanish.id}"] .lang-tag`)).toHaveText('ES');
  await expect(page.locator(`.page:not(.hidden) .game-card[data-game="${s.both.id}"] .lang-tag`)).toHaveCount(0);

  // OK: the RedZone channel full screen; the bar and the lower third say what is on
  await page.keyboard.press('Enter');
  await expect(page.locator('.page:not(.hidden) .player.live')).toBeVisible();
  await playing(page);
  await expect(page.locator('.live-top .title')).toHaveText('Tally RedZone');
  await expect(page.locator('.live-bar .channel')).toHaveText(`ON REDZONE NOW · ${title(s.both).toUpperCase()} · RED ZONE`);
  await page.waitForTimeout(300);
  await shot(page, info, 'redzone-player-bar');
  await page.keyboard.press('Escape'); // hides the bar
  const now = page.locator('.player.live .redzone-now');
  await expect(now).not.toHaveClass(/faded/);
  await expect(now.locator('.rz-title')).toHaveText(title(s.both));
  // the score bug is the game RedZone shows
  await expect(page.locator('.player.live .score-bug')).toHaveCount(1);
  await page.waitForTimeout(300);
  await shot(page, info, 'redzone-player-now');

  // polled every 10 s while it plays
  const before = s.calls();
  await page.waitForTimeout(21_000);
  expect(s.calls() - before).toBeGreaterThanOrEqual(2);

  // leaving: no more polling from the player (the board, not on screen, asks nothing either)
  await page.keyboard.press('Escape');
  await expect(page.locator('.page:not(.hidden) .sports-page')).toBeVisible();
  await push(page, { name: 'settings' });
  const after = s.calls();
  await page.waitForTimeout(12_000);
  expect(s.calls()).toBe(after);
});

test('Commentary: the panel says ALSO IN ESPAÑOL, HOLD offers Watch in Español, the player switches language in place', async ({ page }, info) => {
  const s = await setUp(page);
  test.skip(s === null, 'needs two live games with streams and two spare channels on the dev board');
  if (s === null) return;
  await openSports(page);
  await focusCard(page, s.both.id);
  await expect(page.locator('.hero-panel .watch-bar .also-feed')).toHaveText('ALSO IN ESPAÑOL');
  await page.waitForTimeout(300);
  await shot(page, info, 'commentary-panel');
  await holdOk(page);
  const menu = page.locator('.menu-panel');
  await expect(menu).toBeVisible();
  const labels = await menu.locator('.tally-row .label').allTextContents();
  expect(labels.slice(0, 2)).toEqual(['Watch', 'Watch in Español']);
  await page.keyboard.press('ArrowDown');
  await expect(menu.locator('.tally-row[data-focused] .label')).toHaveText('Watch in Español');
  await page.waitForTimeout(300);
  await shot(page, info, 'commentary-menu');
  await page.waitForTimeout(200);
  await page.keyboard.press('Enter');

  // the Spanish feed plays; the bar's Commentary button says so and switches to English in place
  await expect(page.locator('.page:not(.hidden) .player.live')).toBeVisible();
  await playing(page);
  await page.keyboard.press('ArrowLeft'); // the bar again (it hides after 5 s)
  await expect(page.locator('.live-bar .channel')).toHaveText((s.both.watch?.channelName ?? '').toUpperCase() + ' (ESPAÑOL)');
  const button = page.locator('.live-bar .btn', { hasText: 'Commentary' });
  await expect(button).toHaveText(/COMMENTARY: ESPAÑOL/i);
  await expect(page.locator('.live-bar .btn[data-focused]')).toHaveText(/COMMENTARY: ESPAÑOL/i);
  await page.waitForTimeout(300);
  await shot(page, info, 'commentary-player-es');
  await page.keyboard.press('Enter');
  await expect(page.locator('.live-bar .channel')).toHaveText((s.both.watch?.channelName ?? '').toUpperCase());
  await expect(page.locator('.live-bar .btn', { hasText: 'Commentary' })).toHaveText(/COMMENTARY: ENGLISH/i);
  await playing(page);
  await page.keyboard.press('ArrowLeft');
  await expect(page.locator('.live-bar .btn[data-focused]')).toHaveText(/COMMENTARY: ENGLISH/i);
  await page.waitForTimeout(300);
  await shot(page, info, 'commentary-player-en');
  // the game on screen is not "also on now" in its other language
  await page.keyboard.press('Escape');
  await page.keyboard.press('ArrowDown');
  await expect(page.locator('.game-switcher')).toBeVisible();
  await expect(page.locator(`.game-switcher .game-card[data-game="${s.both.id}"]`)).toHaveCount(0);
  await expect(page.locator(`.game-switcher .game-card[data-game="${s.spanish.id}"] .lang-tag`)).toHaveText('ES');
  await page.waitForTimeout(300);
  await shot(page, info, 'commentary-switcher-es-chip');
});

test('Settings: Commentary language switches English / Español, the board is fetched again; put back', async ({ page }, info) => {
  const s = await setUp(page);
  test.skip(s === null, 'needs two live games with streams and two spare channels on the dev board');
  await openSports(page);
  await page.keyboard.press('ArrowUp');
  for (let i = 0; i < 8; i++) {
    if ((await page.locator('.sports-tab[data-focused] .label').textContent()) === 'SETTINGS') break;
    await page.keyboard.press('ArrowRight');
  }
  await page.keyboard.press('Enter');
  const row = page.locator('.page:not(.hidden) .tally-row', { hasText: 'Commentary language' });
  await expect(row).toBeVisible();
  await expect(row.locator('.setting-value')).toHaveText(/^(ENGLISH|ESPAÑOL)$/);
  const was = (await row.locator('.setting-value').textContent()) ?? '';
  const other = was === 'ESPAÑOL' ? 'ENGLISH' : 'ESPAÑOL';
  const code = (label: string): string => (label === 'ESPAÑOL' ? 'es' : 'en');
  const put = (label: string) =>
    page.waitForRequest((r) => r.method() === 'PUT' && r.url().indexOf('/JellyTV/Client/v1/settings') >= 0 && (r.postDataJSON() as { streamLanguage?: string }).streamLanguage === code(label));
  for (let i = 0; i < 6; i++) {
    if (await row.evaluate((el) => el.hasAttribute('data-focused'))) break;
    await page.keyboard.press('ArrowDown');
  }
  const boards: string[] = [];
  page.on('request', (r) => {
    if (r.url().indexOf('/JellyTV/Client/v1/board') >= 0) boards.push(r.url());
  });
  const written = put(other);
  await page.keyboard.press('Enter');
  await expect(row.locator('.setting-value')).toHaveText(other);
  await written;
  await expect.poll(() => boards.length).toBeGreaterThan(0);
  await page.waitForTimeout(300);
  await shot(page, info, 'commentary-setting');
  const back = put(was);
  await page.keyboard.press('Enter');
  await expect(row.locator('.setting-value')).toHaveText(was);
  await back;
});
