import { execSync } from 'node:child_process';
import { expect, test, type Page, type Route } from '@playwright/test';
import { APP, AUTH_STATE, api, shot } from './env';

/**
 * Tally 2.2.1, "never block a game" (the Apps part of the 2.2.1 contract): a game without a stream is drawn at full
 * strength with NO STREAM YET / LOOKING FOR A STREAM, WATCH looks for a stream ("Looking for a stream…", CANCEL,
 * BACK), plays as soon as one appears, and after 45 s says so (OK, KEEP LOOKING); the switcher and multiview's rail
 * list live games without a stream and run the same search; "Only games with a stream" (renamed) is off by default.
 *
 * Where the server has no find endpoint yet (a plugin before 2.2.1: HTTP 404) the real flows see "no stream" after
 * one board refresh; the flows that need the server to answer "searching" or "found" script `find` with a route
 * (and, where noted, the board). The score simulator's parts run with TALLY_SIM=1.
 */
test.use({ storageState: AUTH_STATE });

interface Watch {
  channelId: string;
  channelName: string;
  hlsPath: string;
  liveTvItemId: string | null;
  cardPath: string;
  confidence: string;
}
interface BoardGame {
  id: string;
  state: string;
  league: string;
  home: { shortName: string; abbr: string };
  away: { shortName: string; abbr: string };
  watch?: Watch | null;
  search?: { state: string } | null;
}
type Board = { games: BoardGame[] } & Record<string, unknown>;

const SIM = process.env.TALLY_SIM === '1';
const FIND = '**/JellyTV/Client/v1/games/*/find';
const BOARD = '**/JellyTV/Client/v1/board*';

function sim(args: string): void {
  execSync(`docker exec tally-score-sim python /sim.py ${args}`, { stdio: 'ignore' });
}

const board = (): Promise<Board> => api<Board>('/JellyTV/Client/v1/board');

/** Waits until the dev server's board has game `id` (the plugin re-reads a live league every 12 s). */
async function boardGame(id: string, match: (g: BoardGame) => boolean = () => true): Promise<BoardGame> {
  for (let i = 0; i < 40; i++) {
    const g = (await board()).games.find((x) => x.id === id);
    if (g !== undefined && match(g)) return g;
    await new Promise((r) => setTimeout(r, 1500));
  }
  throw new Error(`game ${id} did not reach the board as expected`);
}

async function openSports(page: Page, withCards = true): Promise<void> {
  await page.goto(APP);
  await expect(page.locator('.home')).toBeVisible();
  await page.evaluate(() => (window as unknown as { TallyDebug: { push: (r: unknown) => void } }).TallyDebug.push({ name: 'sports' }));
  await expect(page.locator('.page:not(.hidden) .sports-page')).toBeVisible();
  if (withCards) await expect(page.locator('.game-card[data-focused]')).toBeVisible();
}

const push = (page: Page, route: unknown) => page.evaluate((r) => (window as unknown as { TallyDebug: { push: (r: unknown) => void } }).TallyDebug.push(r), route);

/** Moves focus on the board to the card of game `id`: DOWN/UP to its row, then along the row. */
async function focusGame(page: Page, id: string): Promise<void> {
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
    if (step === 'missing') throw new Error(`game ${id} is not on the board (or no card has focus)`);
    await page.keyboard.press(step);
    await page.waitForTimeout(120);
  }
  throw new Error(`could not reach game ${id}`);
}

const dialog = (page: Page) => page.locator('.page:not(.hidden) .stream-search');
const focusedButton = (page: Page) => page.locator('.page:not(.hidden) .stream-search .btn[data-focused]');
const card = (page: Page, id: string) => page.locator(`.page:not(.hidden) .game-card[data-game="${id}"]`);

/** `find` answered by a script: the n-th call gets `answers[n]` (the last repeats). Returns the call count. */
async function scriptFind(page: Page, answers: Array<Record<string, unknown>>): Promise<{ calls: number }> {
  const counter = { calls: 0 };
  await page.route(FIND, (route: Route) => {
    const body = answers[Math.min(counter.calls, answers.length - 1)] ?? { state: 'searching', watch: null };
    counter.calls++;
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
  });
  return counter;
}

/** The board as the server sends it, changed by `edit` (a game's watch taken away, a search field added). */
async function editBoard(page: Page, edit: (b: Board) => void): Promise<void> {
  await page.route(BOARD, async (route: Route) => {
    const response = await route.fetch();
    const json = (await response.json()) as Board;
    edit(json);
    await route.fulfill({ response, json });
  });
}

/** A game that is not over and has no stream, from the dev server's board (plenty of upcoming NFL games there). */
async function gameWithoutStream(): Promise<BoardGame | undefined> {
  const b = await board();
  return b.games.find((g) => g.state === 'in' && g.watch == null) ?? b.games.find((g) => g.state === 'pre' && g.watch == null);
}

test('No stream: full-strength card with NO STREAM YET, the panel offers OK Watch; a searching game says LOOKING FOR A STREAM', async ({ page }, info) => {
  const target = await gameWithoutStream();
  test.skip(target === undefined, 'needs a game without a stream on the board');
  if (target === undefined) return;
  // another game without a stream that the server is searching for right now (the board field, scripted here)
  const b = await board();
  const other = b.games.find((g) => g.id !== target.id && g.state !== 'post' && g.watch == null);
  await editBoard(page, (json) => {
    for (const g of json.games) if (other !== undefined && g.id === other.id) g.search = { state: 'searching' };
  });
  await openSports(page);
  await focusGame(page, target.id);
  const c = card(page, target.id);
  await expect(c.locator('.label-bar .text')).toHaveText(/^(NO STREAM YET|LOOKING FOR A STREAM)$/);
  // no dimming: the teams and scores at full strength
  expect(await c.locator('.game-body').evaluate((el) => getComputedStyle(el).opacity)).toBe('1');
  await expect(page.locator('.hero-panel .watch-bar .channel')).toHaveText(/^(NO STREAM YET|LOOKING FOR A STREAM)$/);
  await expect(page.locator('.hero-panel .watch-bar .key-hint .what')).toHaveText('Watch');
  await expect(page.locator('.page:not(.hidden) .game-card .label-bar .text', { hasText: 'NOT ON YOUR CHANNELS' })).toHaveCount(0);
  await page.waitForTimeout(400);
  await shot(page, info, 'streams-card-no-stream');
  if (other !== undefined) {
    await focusGame(page, other.id);
    await expect(card(page, other.id).locator('.label-bar .text')).toHaveText('LOOKING FOR A STREAM');
    await expect(page.locator('.hero-panel .watch-bar .channel')).toHaveText('LOOKING FOR A STREAM');
    await page.waitForTimeout(300);
    await shot(page, info, 'streams-card-looking');
  }
});

test('No stream (sim): a live game with no channel: the label, OK looks for a stream, then the no-stream message; OK / KEEP LOOKING / BACK', async ({ page }, info) => {
  test.skip(!SIM, 'needs the score simulator (TALLY_SIM=1)');
  sim('add --id 900221 --away ROT:Riverton:Otters --home LKH:Lakeside:Herons --start -20 --state in');
  try {
    await boardGame('900221', (g) => g.state === 'in' && g.watch == null);
    await openSports(page);
    await focusGame(page, '900221');
    const c = card(page, '900221');
    await expect(c.locator('.label-bar .text')).toHaveText(/^(NO STREAM YET|LOOKING FOR A STREAM)$/);
    await shot(page, info, 'streams-sim-card');

    // OK: "Looking for a stream…", CANCEL focused, the lamp sputtering
    await page.keyboard.press('Enter');
    await expect(dialog(page)).toHaveAttribute('data-phase', 'searching');
    await expect(dialog(page).locator('.kicker')).toHaveText('LOOKING FOR A STREAM…');
    await expect(dialog(page).locator('.title')).toHaveText('Otters at Herons');
    await expect(focusedButton(page)).toHaveText('CANCEL');
    await shot(page, info, 'streams-searching');

    // no stream: the message (at once from a server without find, after its search or 45 s from one with it)
    await expect(dialog(page)).toHaveAttribute('data-phase', 'none', { timeout: 55_000 });
    await expect(dialog(page).locator('.message')).toHaveText('No stream for this game yet. Tally keeps looking and will light it up when one appears.');
    await expect(focusedButton(page)).toHaveText('OK');
    await shot(page, info, 'streams-no-stream');

    // RIGHT: KEEP LOOKING; OK: searching again, CANCEL focused
    await page.keyboard.press('ArrowRight');
    await expect(focusedButton(page)).toHaveText('KEEP LOOKING');
    await page.waitForTimeout(450);
    await page.keyboard.press('Enter');
    await expect(dialog(page)).toHaveAttribute('data-phase', 'searching');
    await expect(focusedButton(page)).toHaveText('CANCEL');
    // arrows stay inside the dialog
    await page.keyboard.press('ArrowUp');
    await page.keyboard.press('ArrowLeft');
    await expect(focusedButton(page)).toHaveText('CANCEL');

    // BACK closes it, focus back on the card, nothing played
    await page.keyboard.press('Escape');
    await expect(dialog(page)).toHaveCount(0);
    await expect(c).toHaveAttribute('data-focused', /.*/);
    await expect(page.locator('.page:not(.hidden) .sports-page')).toBeVisible();

    // again, and OK on the message closes it
    await page.keyboard.press('Enter');
    await expect(dialog(page)).toBeVisible();
    await page.waitForTimeout(450);
    await page.keyboard.press('Enter'); // CANCEL
    await expect(dialog(page)).toHaveCount(0);
    await expect(c).toHaveAttribute('data-focused', /.*/);

    // HOLD OK: the menu has Watch under NO STREAM YET, and Add to multiview
    await page.keyboard.down('Enter');
    await page.waitForTimeout(700);
    await page.keyboard.up('Enter');
    await expect(page.locator('.menu-panel')).toBeVisible();
    const labels = await page.locator('.menu-panel .tally-row .label').allTextContents();
    expect(labels.slice(0, 2)).toEqual(['Watch', 'Add to multiview']);
    await expect(page.locator('.menu-panel .menu-info').first()).toHaveText(/^(NO STREAM YET|LOOKING FOR A STREAM)$/);
    await shot(page, info, 'streams-menu');
    await page.waitForTimeout(450);
    await page.keyboard.press('Enter'); // Watch
    await expect(dialog(page)).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(dialog(page)).toHaveCount(0);
  } finally {
    sim('remove 900221');
  }
});

test('No stream (sim): the game gets a channel while Tally is looking, and it plays', async ({ page }, info) => {
  test.skip(!SIM, 'needs the score simulator (TALLY_SIM=1)');
  sim('add --id 900222 --away ROT:Riverton:Otters --home LKH:Lakeside:Herons --start -20 --state in');
  try {
    await boardGame('900222', (g) => g.state === 'in' && g.watch == null);
    await openSports(page);
    await focusGame(page, '900222');
    await page.keyboard.press('Enter');
    await expect(dialog(page)).toBeVisible();
    // a server without find says "no stream" at once: KEEP LOOKING keeps the dialog looking
    await page.waitForTimeout(2500);
    if ((await dialog(page).getAttribute('data-phase')) === 'none') {
      await page.keyboard.press('ArrowRight');
      await page.keyboard.press('Enter');
    }
    await expect(dialog(page)).toHaveAttribute('data-phase', 'searching');
    // the game now names teams one of the dev channels shows: the plugin matches it to that channel
    sim('add --id 900222 --away "CIN:Cincinnati:Reds" --home "TOR:Toronto:Blue Jays" --start -20 --state in');
    await expect(page.locator('.page:not(.hidden) .player.live')).toBeVisible({ timeout: 45_000 });
    await expect(dialog(page)).toHaveCount(0);
    await expect(page.locator('.player.live .live-top .title')).toHaveText('Reds at Blue Jays');
    await expect.poll(() => page.evaluate(() => (document.querySelector('.player.live video') as HTMLVideoElement | null)?.currentTime ?? 0), { timeout: 45_000 }).toBeGreaterThan(1);
    await shot(page, info, 'streams-found-plays');
    // BACK (bar, then page): the board, focus on the game
    await page.keyboard.press('Escape');
    await page.keyboard.press('Escape');
    await expect(page.locator('.page:not(.hidden) .sports-page')).toBeVisible();
    await expect(card(page, '900222')).toHaveAttribute('data-focused', /.*/);
  } finally {
    sim('remove 900222');
  }
});

test('No stream: find answers searching, then found: it plays that stream (scripted find)', async ({ page }, info) => {
  const target = await gameWithoutStream();
  const b = await board();
  const streamed = b.games.find((g) => g.state === 'in' && g.watch != null && g.watch.hlsPath !== '');
  test.skip(target === undefined || streamed?.watch == null, 'needs a game without a stream and a live game on a channel');
  if (target === undefined || streamed?.watch == null) return;
  const found = { ...streamed.watch };
  const counter = await scriptFind(page, [{ state: 'searching', watch: null }, { state: 'searching', watch: null }, { state: 'found', watch: found }]);
  await openSports(page);
  await focusGame(page, target.id);
  await page.keyboard.press('Enter');
  await expect(dialog(page)).toHaveAttribute('data-phase', 'searching');
  await page.waitForTimeout(1000);
  await shot(page, info, 'streams-searching-scripted');
  await expect(page.locator('.page:not(.hidden) .player.live')).toBeVisible({ timeout: 15_000 });
  expect(counter.calls).toBe(3);
  await expect(page.locator('.player.live .live-top .title')).toHaveText(`${target.away.shortName} at ${target.home.shortName}`);
  await expect(page.locator('.player.live .live-bar .channel')).toHaveText(found.channelName.toUpperCase());
  await expect.poll(() => page.evaluate(() => (document.querySelector('.player.live video') as HTMLVideoElement | null)?.currentTime ?? 0), { timeout: 45_000 }).toBeGreaterThan(1);
  await shot(page, info, 'streams-found-scripted');
});

test('No stream: 45 s of searching ends in the message (scripted find that keeps searching)', async ({ page }, info) => {
  test.setTimeout(120_000);
  const target = await gameWithoutStream();
  test.skip(target === undefined, 'needs a game without a stream on the board');
  if (target === undefined) return;
  const counter = await scriptFind(page, [{ state: 'searching', watch: null }]);
  await openSports(page);
  await focusGame(page, target.id);
  const started = Date.now();
  await page.keyboard.press('Enter');
  await expect(dialog(page)).toHaveAttribute('data-phase', 'searching');
  await expect(dialog(page)).toHaveAttribute('data-phase', 'none', { timeout: 60_000 });
  const took = Date.now() - started;
  expect(took).toBeGreaterThan(44_000);
  expect(took).toBeLessThan(50_000);
  // polled every 3 s: 15 or 16 calls in 45 s
  expect(counter.calls).toBeGreaterThanOrEqual(14);
  expect(counter.calls).toBeLessThanOrEqual(17);
  await shot(page, info, 'streams-no-stream-45s');
  await page.waitForTimeout(450);
  await page.keyboard.press('Enter'); // OK
  await expect(dialog(page)).toHaveCount(0);
});

test('Switcher and multiview: a live game without a stream is listed; picking it looks for the stream and then switches / tiles it', async ({ page }, info) => {
  const b = await board();
  const live = b.games.filter((g) => g.state === 'in' && g.watch != null && g.watch.hlsPath !== '');
  test.skip(live.length < 2, 'needs two live games on channels (tally/dev/score-sim.py makes them)');
  const playing = live[0] as BoardGame;
  const hidden = live[live.length - 1] as BoardGame;
  const hiddenWatch = { ...(hidden.watch as Watch) };
  // the board without the second game's stream; find finds it again
  await editBoard(page, (json) => {
    for (const g of json.games) if (g.id === hidden.id) g.watch = null;
  });
  await scriptFind(page, [{ state: 'searching', watch: null }, { state: 'found', watch: hiddenWatch }]);

  await page.goto(APP);
  await expect(page.locator('.home')).toBeVisible();
  const w = playing.watch as Watch;
  await push(page, { name: 'live', channelId: w.channelId, hlsPath: w.hlsPath, title: `${playing.away.shortName} at ${playing.home.shortName}`, gameId: playing.id });
  await expect(page.locator('.page:not(.hidden) .player.live')).toBeVisible();
  await page.waitForTimeout(1500);
  await page.keyboard.press('ArrowDown');
  const switcher = page.locator('.game-switcher');
  await expect(switcher).toBeVisible();
  const entry = switcher.locator(`.game-card[data-game="${hidden.id}"]`);
  await expect(entry.locator('.label-bar .text')).toHaveText(/^(NO STREAM YET|LOOKING FOR A STREAM)$/);
  for (let i = 0; i < 14 && (await entry.getAttribute('data-focused')) === null; i++) {
    await page.keyboard.press('ArrowRight');
    await page.waitForTimeout(120);
  }
  await expect(entry).toHaveAttribute('data-focused', /.*/);
  await shot(page, info, 'streams-switcher');
  await page.keyboard.press('Enter');
  await expect(dialog(page)).toBeVisible();
  // found: the player switches in place to the game's channel
  await expect(page.locator('.player.live .live-bar .channel')).toHaveText(hiddenWatch.channelName.toUpperCase(), { timeout: 15_000 });
  await expect(dialog(page)).toHaveCount(0);
  await expect(page.locator('.player.live .live-top .title')).toHaveText(`${hidden.away.shortName} at ${hidden.home.shortName}`);
  await shot(page, info, 'streams-switched');

  // multiview: the rail lists the game (no stream yet); OK looks for it and it becomes a tile
  await page.keyboard.press('Escape');
  await page.keyboard.press('Escape');
  await page.unroute(FIND);
  await scriptFind(page, [{ state: 'searching', watch: null }, { state: 'found', watch: hiddenWatch }]);
  await push(page, { name: 'multiview' });
  await expect(page.locator('.page:not(.hidden) .multiview-page')).toBeVisible();
  const row = page.locator('.page:not(.hidden) .swap-row', { has: page.locator('.no-stream') });
  await expect(row.first()).toBeVisible();
  await page.waitForTimeout(800);
  const tilesBefore = await page.locator('.page:not(.hidden) .mv-tile').count();
  // from the tiles (or the empty stage) to the rail
  if ((await page.locator('.page:not(.hidden) .swap-row[data-focused]').count()) === 0) await page.keyboard.press('ArrowRight');
  await expect(page.locator('.page:not(.hidden) .swap-row[data-focused]')).toBeVisible();
  for (let i = 0; i < 12 && (await row.first().getAttribute('data-focused')) === null; i++) {
    await page.keyboard.press('ArrowDown');
    await page.waitForTimeout(150);
  }
  await expect(row.first()).toHaveAttribute('data-focused', /.*/);
  await shot(page, info, 'streams-multiview-rail');
  await page.keyboard.press('Enter');
  await expect(dialog(page)).toBeVisible();
  await expect(page.locator('.page:not(.hidden) .mv-tile')).toHaveCount(tilesBefore + 1, { timeout: 15_000 });
  await expect(page.locator('.page:not(.hidden) .mv-tile .label-bar .text').last()).toHaveText(hiddenWatch.channelName.toUpperCase());
  await page.waitForTimeout(1500);
  await shot(page, info, 'streams-multiview-tiled');
});

test('Settings: "Only games with a stream" is off by default (null), on it hides games without one; the empty state', async ({ page }, info) => {
  await openSports(page);
  // the viewer's settings document, as the app reads it (restored at the end)
  const call = (method: 'GET' | 'PUT', body?: unknown) =>
    page.evaluate(
      async ([m, b]) => {
        const s = JSON.parse(localStorage.getItem('tally.session.v1') ?? '{}') as { serverUrl: string; token: string };
        const r = await fetch(s.serverUrl + '/JellyTV/Client/v1/settings', {
          method: m as string,
          headers: { Authorization: `MediaBrowser Token="${s.token}"`, 'Content-Type': 'application/json' },
          body: m === 'PUT' ? JSON.stringify(b) : undefined,
        });
        return m === 'GET' ? ((await r.json()) as Record<string, unknown>) : { status: r.status };
      },
      [method, body ?? null] as const,
    );
  const saved = (await call('GET')) as Record<string, unknown>;
  try {
    const withoutChoice = { ...saved };
    delete withoutChoice.onlyWatchable;
    await call('PUT', withoutChoice);
    await openSports(page);
    const all = await page.locator('.page:not(.hidden) .board-rows .game-card').count();
    const b = await board();
    expect(all).toBe(b.games.length); // every game shows
    await page.keyboard.press('ArrowUp');
    for (let i = 0; i < 6 && ((await page.locator('.sports-tab[data-focused] .label').textContent()) ?? '') !== 'SETTINGS'; i++) await page.keyboard.press('ArrowRight');
    await page.keyboard.press('Enter');
    const rowEl = page.locator('.tally-row[data-focused]');
    await expect(rowEl.locator('.label')).toHaveText('Only games with a stream');
    await expect(rowEl.locator('.description')).toHaveText('Hide games that have no stream yet');
    await expect(rowEl.locator('.tally-switch')).not.toHaveClass(/\bon\b|checked/);
    await shot(page, info, 'streams-setting-off');
    await page.keyboard.press('Enter');
    await expect(rowEl.locator('.tally-switch')).toHaveClass(/\bon\b|checked/);
    await shot(page, info, 'streams-setting-on');
    expect(((await call('GET')) as Record<string, unknown>).onlyWatchable).toBe(true);
    // on: only games with a stream on the board
    await page.keyboard.press('ArrowUp');
    for (let i = 0; i < 6 && ((await page.locator('.sports-tab[data-focused] .label').textContent()) ?? '') !== 'GAMES'; i++) await page.keyboard.press('ArrowLeft');
    await page.keyboard.press('Enter');
    const streamed = b.games.filter((g) => g.watch != null).length;
    if (streamed > 0) await expect(page.locator('.page:not(.hidden) .board-rows .game-card')).toHaveCount(streamed);
  } finally {
    await call('PUT', saved);
  }
  // a board where nothing has a stream, with the setting on: the empty state says what to do
  const onDoc = { ...saved, onlyWatchable: true };
  await call('PUT', onDoc);
  try {
    await editBoard(page, (json) => {
      for (const g of json.games) g.watch = null;
    });
    await openSports(page, false);
    await expect(page.locator('.page:not(.hidden) .board-empty .title')).toHaveText('No game has a stream right now.');
    await expect(page.locator('.page:not(.hidden) .board-empty .subtitle')).toHaveText('Turn off “Only games with a stream” to see them all.');
    await shot(page, info, 'streams-empty-state');
  } finally {
    await call('PUT', saved);
  }
});

test('No stream on LG: the dialog with the Magic Remote pointer (hover, click) and BACK 461', async ({ page }, info) => {
  const target = await gameWithoutStream();
  test.skip(target === undefined, 'needs a game without a stream on the board');
  if (target === undefined) return;
  await page.addInitScript(() => {
    const w = window as unknown as Record<string, unknown>;
    w.webOSSystem = {
      deviceInfo: JSON.stringify({ modelName: 'OLED55CX9LA', platformVersion: '04.20.55', platformVersionMajor: 4, sdkVersion: '5.2.0', screenWidth: 1920, screenHeight: 1080 }),
      launchParams: '{}',
      activate: () => undefined,
      platformBack: () => undefined,
    };
    function Bridge(this: { onservicecallback: ((m: string) => void) | null }) {
      this.onservicecallback = null;
    }
    Bridge.prototype.call = function (this: { onservicecallback: ((m: string) => void) | null }) {
      setTimeout(() => this.onservicecallback?.(JSON.stringify({ returnValue: true })));
    };
    Bridge.prototype.cancel = () => undefined;
    w.PalmServiceBridge = Bridge;
  });
  await scriptFind(page, [{ state: 'none', watch: null }]);
  await openSports(page);
  await focusGame(page, target.id);
  await page.keyboard.press('Enter');
  await expect(dialog(page)).toHaveAttribute('data-phase', 'none');
  const center = async (text: string): Promise<{ x: number; y: number }> => {
    const box = await dialog(page).locator('.btn', { hasText: text }).boundingBox();
    if (box === null) throw new Error(text + ' is not on screen');
    return { x: box.x + box.width / 2, y: box.y + box.height / 2 };
  };
  // the pointer over KEEP LOOKING takes the frame; a click presses it
  let at = await center('KEEP LOOKING');
  await page.mouse.move(at.x, at.y, { steps: 4 });
  await expect(focusedButton(page)).toHaveText('KEEP LOOKING');
  await shot(page, info, 'streams-lg-hover');
  await page.waitForTimeout(450);
  await page.mouse.click(at.x, at.y);
  await expect(dialog(page)).toHaveAttribute('data-phase', 'searching');
  // the pointer on the game behind the scrim does not take the focus from the dialog
  const behind = await card(page, target.id).boundingBox();
  if (behind !== null) await page.mouse.move(behind.x + 20, behind.y + 20, { steps: 3 });
  await expect(focusedButton(page)).toHaveText('CANCEL');
  // CANCEL with the pointer
  at = await center('CANCEL');
  await page.mouse.move(at.x, at.y, { steps: 3 });
  await page.waitForTimeout(450);
  await page.mouse.click(at.x, at.y);
  await expect(dialog(page)).toHaveCount(0);
  // again, and LG's BACK (461) closes it
  await focusGame(page, target.id);
  await page.keyboard.press('Enter');
  await expect(dialog(page)).toBeVisible();
  await page.evaluate(() => {
    for (const type of ['keydown', 'keyup']) {
      const e = new KeyboardEvent(type, { bubbles: true, cancelable: true });
      Object.defineProperty(e, 'keyCode', { get: () => 461 });
      window.dispatchEvent(e);
    }
  });
  await expect(dialog(page)).toHaveCount(0);
  await expect(page.locator('.page:not(.hidden) .sports-page')).toBeVisible();
});
