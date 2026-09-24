import { expect, test, type Page } from '@playwright/test';
import { APP, AUTH_STATE, api, shot } from './env';

test.use({ storageState: AUTH_STATE });

interface BoardGame {
  id: string;
  state: string;
  league: string;
  home: { shortName: string; abbr: string };
  away: { shortName: string; abbr: string };
  watch?: { channelId: string; hlsPath: string; channelName: string };
}

async function openSports(page: Page): Promise<void> {
  await page.goto(APP);
  await expect(page.locator('.home')).toBeVisible();
  await page.evaluate(() => (window as unknown as { TallyDebug: { push: (r: unknown) => void } }).TallyDebug.push({ name: 'sports' }));
  await expect(page.locator('.page:not(.hidden) .sports-page')).toBeVisible();
}

/** OK held past the hold threshold (the remote's long press). */
async function holdOk(page: Page): Promise<void> {
  await page.keyboard.down('Enter');
  await page.waitForTimeout(700);
  await page.keyboard.up('Enter');
}

test('Sports: the games board, its tabs and the focused-game panel', async ({ page }, info) => {
  const board = await api<{ games: BoardGame[] }>('/JellyTV/Client/v1/board');
  test.skip(board.games.length === 0, 'no games on the board');
  await openSports(page);
  const tabs = page.locator('.sports-tab .label');
  await expect(tabs.first()).toHaveText('GAMES');
  expect(await tabs.allTextContents()).toEqual(['GAMES', 'CHANNELS', 'MULTIVIEW', 'RECORDINGS', 'SETTINGS']);
  // the first card of the first row takes focus; the panel describes it
  await expect(page.locator('.game-card[data-focused]')).toBeVisible();
  await expect(page.locator('.hero-panel .hero-team')).toHaveCount(2);
  await shot(page, info, 'sports-games');
  // RIGHT moves along the row and the panel follows
  const firstName = await page.locator('.hero-panel .hero-team .name').first().textContent();
  await page.keyboard.press('ArrowRight');
  await page.waitForTimeout(200);
  const second = await page.locator('.hero-panel .hero-team .name').first().textContent();
  if ((await page.locator('.page:not(.hidden) .board-rows .media-row').first().locator('.game-card').count()) > 1) expect(second).not.toBe(firstName);
  // DOWN to the next row: its header moves to the top of the list
  await page.keyboard.press('ArrowDown');
  await page.waitForTimeout(200);
  await shot(page, info, 'sports-games-row2');
  // UP: back to the first row, which moves back into view
  await page.keyboard.press('ArrowUp');
  await page.waitForTimeout(200);
  const firstRowTop = await page.locator('.board-rows .media-row').first().evaluate((el) => el.getBoundingClientRect().top);
  expect(firstRowTop).toBeGreaterThan(650);
  // along the longest row (the board's rows change through the day) the focused card always stays on screen and in
  // its row, to the row's end and back to its start, whichever way focus moves
  const counts = await page.locator('.board-rows .media-row').evaluateAll((rows) => rows.map((r) => r.querySelectorAll('.game-card').length));
  const longest = counts.indexOf(Math.max(...counts));
  await moveTo(page, 'ArrowDown', async () => (await focusedCard(page)).row === longest, counts.length);
  const length = counts[longest] ?? 0;
  const onScreen = async (): Promise<void> => {
    const f = await focusedCard(page);
    expect(f.row).toBe(longest);
    expect(f.left >= 144 && f.right <= 1920).toBe(true);
  };
  for (let i = (await focusedCard(page)).index; i < length - 1; i++) {
    await page.keyboard.press('ArrowRight');
    await page.waitForTimeout(120);
    await onScreen();
  }
  expect((await focusedCard(page)).index).toBe(length - 1);
  await shot(page, info, 'sports-row-end');
  for (let i = length - 1; i > 0; i--) {
    await page.keyboard.press('ArrowLeft');
    await page.waitForTimeout(120);
    await onScreen();
  }
  expect((await focusedCard(page)).index).toBe(0);
  // back to the first row, on its last card: UP reaches the selected tab (not the nearest one)
  await moveTo(page, 'ArrowUp', async () => (await focusedCard(page)).row === 0, counts.length);
  await moveTo(page, 'ArrowRight', async () => (await focusedCard(page)).index === (counts[0] ?? 1) - 1, counts[0] ?? 1);
  await page.keyboard.press('ArrowUp');
  await expect(page.locator('.sports-tab.selected[data-focused]')).toBeVisible();
  await shot(page, info, 'sports-tab-focused');
});

/** Where the focused game card is: its row and place on the board, and its left and right edges on screen. */
async function focusedCard(page: Page): Promise<{ row: number; index: number; left: number; right: number }> {
  return page.evaluate(() => {
    const card = document.querySelector('.page:not(.hidden) .board-rows .game-card[data-focused]');
    const row = card?.closest('.media-row') ?? null;
    if (card === null || row === null) return { row: -1, index: -1, left: 0, right: 0 };
    const rows = Array.from(document.querySelectorAll('.page:not(.hidden) .board-rows .media-row'));
    const r = card.getBoundingClientRect();
    return { row: rows.indexOf(row), index: Array.from(row.querySelectorAll('.game-card')).indexOf(card), left: r.left, right: r.right };
  });
}

/** Moves focus on the board to the card of game `id`: DOWN/UP to its row, then along the row. */
async function focusGame(page: Page, id: string): Promise<void> {
  for (let i = 0; i < 60; i++) {
    const step = await page.evaluate((gameId) => {
      const board = document.querySelector('.page:not(.hidden) .board-rows');
      const focused = board?.querySelector('.game-card[data-focused]') ?? null;
      const target = board?.querySelector(`.game-card[data-game="${gameId}"]`) ?? null;
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

/** Moves focus with `key` until `match` is focused (at most `max` presses). */
async function moveTo(page: Page, key: string, match: () => Promise<boolean>, max = 12): Promise<void> {
  for (let i = 0; i < max; i++) {
    if (await match()) return;
    await page.keyboard.press(key);
    await page.waitForTimeout(120);
  }
  expect(await match()).toBe(true);
}

const focusedRowLabel = async (page: Page): Promise<string> => ((await page.locator('.menu-panel .tally-row[data-focused] .label').textContent()) ?? '').trim();

test('Sports: HOLD OK opens the game menu; follow and hide scores toggle in place; BACK returns to the card', async ({ page }, info) => {
  // a game on one of the channels: its menu offers Add to multiview (tally/dev/score-sim.py add … makes one)
  const board = await api<{ games: BoardGame[] }>('/JellyTV/Client/v1/board');
  const game = board.games.find((g) => g.watch != null && g.watch.channelId !== '');
  test.skip(game === undefined, 'needs a game on one of the channels');
  if (game === undefined) return;
  await openSports(page);
  const card = page.locator('.game-card[data-focused]');
  await expect(card).toBeVisible();
  await focusGame(page, game.id);
  const cardId = await card.evaluate((el) => el.closest('.row-track') !== null);
  expect(cardId).toBe(true);
  await holdOk(page);
  const menu = page.locator('.menu-panel');
  await expect(menu).toBeVisible();
  await expect(menu.locator('.menu-title')).toContainText(' at ');
  const labels = await menu.locator('.tally-row .label').allTextContents();
  expect(labels).toContain('Hide scores');
  expect(labels.some((l) => /^Follow(ing)? the /.test(l))).toBe(true);
  await page.waitForTimeout(300);
  await shot(page, info, 'sports-game-menu');

  // Hide scores: the cards behind the menu turn to dashes, the row turns into Show scores
  await moveTo(page, 'ArrowDown', async () => (await focusedRowLabel(page)) === 'Hide scores');
  await page.keyboard.press('Enter');
  await expect(menu.locator('.tally-row[data-focused] .label')).toHaveText('Show scores');
  await expect(page.locator('.game-card .score').first()).toHaveText('–');
  await shot(page, info, 'sports-game-menu-scores-hidden');
  await page.keyboard.press('Enter');
  await expect(menu.locator('.tally-row[data-focused] .label')).toHaveText('Hide scores');

  // BACK closes the menu and focus is back on the card it was opened on
  await page.keyboard.press('Escape');
  await expect(menu).toHaveCount(0);
  await expect(page.locator(`.game-card[data-focused][data-game="${game.id}"]`)).toBeVisible();

  // HOLD again, Add to multiview: a toast says so
  await holdOk(page);
  await expect(menu).toBeVisible();
  await moveTo(page, 'ArrowDown', async () => (await focusedRowLabel(page)) === 'Add to multiview');
  await page.waitForTimeout(450);
  await page.keyboard.press('Enter');
  await expect(page.locator('.page:not(.hidden) .toast')).toHaveText(/multiview/);
});

test('Sports: CHANNELS grid (HOLD adds to multiview), MULTIVIEW queue, SETTINGS, RECORDINGS', async ({ page }, info) => {
  await openSports(page);
  await expect(page.locator('.game-card[data-focused]')).toBeVisible();
  // up to the tabs, right to CHANNELS, OK
  await page.keyboard.press('ArrowUp');
  await expect(page.locator('.sports-tab.selected[data-focused]')).toBeVisible();
  await page.keyboard.press('ArrowRight');
  await expect(page.locator('.sports-tab[data-focused] .label')).toHaveText('CHANNELS');
  // moving onto a tab does not select it
  await expect(page.locator('.sports-tab.selected .label')).toHaveText('GAMES');
  await page.keyboard.press('Enter');
  await expect(page.locator('.channel-card[data-focused]')).toBeVisible();
  await page.waitForTimeout(500);
  await shot(page, info, 'sports-channels');
  await holdOk(page);
  await expect(page.locator('.page:not(.hidden) .toast')).toHaveText('Added to multiview');
  await page.keyboard.press('ArrowRight');
  await holdOk(page);
  await expect(page.locator('.page:not(.hidden) .toast')).toHaveText('Added to multiview');

  // MULTIVIEW: Open multiview first, then the queued channels
  await page.keyboard.press('ArrowUp');
  await expect(page.locator('.sports-tab.selected[data-focused] .label')).toHaveText('CHANNELS');
  await page.keyboard.press('ArrowRight');
  await page.keyboard.press('Enter');
  await expect(page.locator('.tally-row.primary[data-focused] .label')).toHaveText('Open multiview');
  await expect(page.locator('.tab-list .tally-row')).toHaveCount(3);
  await shot(page, info, 'sports-multiview-queue');

  // SETTINGS
  await page.keyboard.press('ArrowUp');
  await moveTo(page, 'ArrowRight', async () => ((await page.locator('.sports-tab[data-focused] .label').textContent()) ?? '') === 'SETTINGS');
  await page.keyboard.press('Enter');
  await expect(page.locator('.tally-row[data-focused] .label')).toHaveText('My channels only');
  await expect(page.locator('.server-block .line').first()).toContainText('API v');
  await shot(page, info, 'sports-settings');

  // RECORDINGS (the dev server records)
  await page.keyboard.press('ArrowUp');
  await page.keyboard.press('ArrowLeft');
  await expect(page.locator('.sports-tab[data-focused] .label')).toHaveText('RECORDINGS');
  await page.keyboard.press('Enter');
  await expect(page.locator('.recordings-tab, .tab-empty')).toBeVisible();
  await page.waitForTimeout(800);
  await shot(page, info, 'sports-recordings');
});

test('Multiview: four live tiles, audio follows focus, OK toggles the layout, HOLD opens the tile menu', async ({ page }, info) => {
  const board = await api<{ channels: Array<{ id: string }> }>('/JellyTV/Client/v1/board');
  test.skip(board.channels.length < 4, 'needs four channels');
  await openSports(page);
  await expect(page.locator('.game-card[data-focused]')).toBeVisible();
  await page.keyboard.press('ArrowUp');
  await page.keyboard.press('ArrowRight');
  await page.keyboard.press('Enter');
  await expect(page.locator('.channel-card[data-focused]')).toBeVisible();
  for (let i = 0; i < 4; i++) {
    await holdOk(page);
    await expect(page.locator('.page:not(.hidden) .toast')).toHaveText('Added to multiview');
    await page.keyboard.press('ArrowRight');
  }
  await page.evaluate(() => (window as unknown as { TallyDebug: { push: (r: unknown) => void } }).TallyDebug.push({ name: 'multiview' }));
  await expect(page.locator('.multiview-page .mv-tile')).toHaveCount(4);
  // every tile plays (a browser decodes all four), the focused one with sound
  await expect
    .poll(() => page.evaluate(() => Array.from(document.querySelectorAll('.mv-tile video')).filter((v) => (v as HTMLVideoElement).currentTime > 1).length), { timeout: 60_000 })
    .toBe(4);
  const muted = await page.evaluate(() => Array.from(document.querySelectorAll('.mv-tile video')).map((v) => (v as HTMLVideoElement).muted));
  expect(muted.filter((m) => !m).length).toBe(1);
  await expect(page.locator('.mv-tile[data-focused] .audio-tag')).toHaveText('AUDIO');
  await shot(page, info, 'multiview-focus');
  // RIGHT: the next tile takes focus and the audio
  await page.keyboard.press('ArrowRight');
  await expect(page.locator('.mv-tile[data-focused] .audio-tag')).toHaveText('AUDIO');
  await expect(page.locator('.mv-tile .audio-tag.on')).toHaveCount(1);
  // OK on a small tile makes it the large one; OK on the large one returns to equal tiles
  await page.keyboard.press('Enter');
  await page.waitForTimeout(300);
  await expect(page.locator('.mv-hint')).toHaveText(/Equal tiles/);
  await page.keyboard.press('Enter');
  await page.waitForTimeout(500);
  await shot(page, info, 'multiview-equal');
  // HOLD: the tile menu
  await holdOk(page);
  await expect(page.locator('.menu-panel')).toBeVisible();
  const labels = await page.locator('.menu-panel .tally-row .label').allTextContents();
  expect(labels).toContain('Watch full screen');
  expect(labels).toContain('Remove from multiview');
  await page.waitForTimeout(300);
  await shot(page, info, 'multiview-menu');
  await page.keyboard.press('Escape');
  await expect(page.locator('.menu-panel')).toHaveCount(0);
  // the rail: RIGHT from a right-hand tile reaches it
  await moveTo(page, 'ArrowRight', async () => (await page.locator('.swap-row[data-focused]').count()) === 1, 3);
  await shot(page, info, 'multiview-rail');
  // BACK leaves multiview, its players stop
  await page.keyboard.press('Escape');
  await expect(page.locator('.page:not(.hidden) .sports-page')).toBeVisible();
  await expect.poll(() => page.evaluate(() => document.querySelectorAll('.mv-tile video').length)).toBe(0);
});

/** Runs the score simulator (tally/dev/score-sim.py in its container), when the run has it (TALLY_SIM=1). */
async function sim(args: string): Promise<void> {
  const { execSync } = await import('node:child_process');
  execSync(`docker exec tally-score-sim python /sim.py ${args}`, { stdio: 'ignore' });
}

test('Live: score bug, UP box score, DOWN switcher with HOLD for the game menu, scoring plays roll the bug and bring banners', async ({ page }, info) => {
  const board = await api<{ games: BoardGame[] }>('/JellyTV/Client/v1/board');
  const live = board.games.filter((g) => g.state === 'in' && g.watch != null);
  test.skip(live.length < 2, 'needs two live games on channels (tally/dev/score-sim.py makes them)');
  const game = live[0] as BoardGame;
  const other = live[1] as BoardGame;
  if (game.watch == null) return;
  await page.goto(APP);
  await expect(page.locator('.home')).toBeVisible();
  const route = { name: 'live', channelId: game.watch.channelId, hlsPath: game.watch.hlsPath, title: `${game.away.shortName} at ${game.home.shortName}`, gameId: game.id };
  await page.evaluate((r) => (window as unknown as { TallyDebug: { push: (r: unknown) => void } }).TallyDebug.push(r), route);
  await expect.poll(() => page.evaluate(() => document.querySelector('.player.live video')?.getBoundingClientRect() !== undefined && ((document.querySelector('.player.live video') as HTMLVideoElement | null)?.currentTime ?? 0) > 1), { timeout: 45_000 }).toBe(true);
  await expect(page.locator('.score-bug')).toBeVisible();
  await expect(page.locator('.live-bar .live-tag')).toHaveText('LIVE');
  await page.waitForTimeout(600);
  await shot(page, info, 'live-playing');

  // UP: the box score; any key closes it
  await page.keyboard.press('ArrowUp');
  await expect(page.locator('.box-score')).toBeVisible();
  await expect(page.locator('.box-score .box-title')).toContainText(' at ');
  await shot(page, info, 'live-box-score');
  await page.keyboard.press('ArrowUp');
  await expect(page.locator('.box-score')).toHaveCount(0);

  // DOWN: the other games; focus on the first card
  await page.keyboard.press('ArrowDown');
  await expect(page.locator('.game-switcher')).toBeVisible();
  await expect(page.locator('.game-switcher .game-card[data-focused]')).toBeVisible();
  await page.waitForTimeout(300);
  await shot(page, info, 'live-switcher');
  await holdOk(page);
  await expect(page.locator('.menu-panel')).toBeVisible();
  expect(await page.locator('.menu-panel .tally-row .label').allTextContents()).toContain('Watch');
  await page.waitForTimeout(300);
  await shot(page, info, 'live-switcher-menu');
  await page.keyboard.press('Escape');
  await expect(page.locator('.menu-panel')).toHaveCount(0);
  await expect(page.locator('.game-switcher .game-card[data-focused]')).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(page.locator('.game-switcher')).toHaveCount(0);

  if (process.env.TALLY_SIM === '1') {
    // a run in the other game: a banner (the plugin re-reads a live league every ~12 s, the app polls every 15 s)
    await sim(`bump ${other.away.abbr}`);
    await expect(page.locator('.event-banner .lt-panel.open')).toBeVisible({ timeout: 60_000 });
    await page.waitForTimeout(400);
    await shot(page, info, 'live-banner');
    // a run in this game: the bug comes back, the score rolls and flashes amber
    const before = await page.locator('.score-bug .line1').textContent();
    await sim(`bump ${game.away.abbr}`);
    await expect(page.locator('.score-bug .score-hot')).toBeVisible({ timeout: 60_000 });
    await shot(page, info, 'live-bug-scored');
    expect(await page.locator('.score-bug .line1').textContent()).not.toBe(before);
  }

  // OK on a switcher card switches to that game, in place
  await page.keyboard.press('ArrowDown');
  await expect(page.locator('.game-switcher .game-card[data-focused]')).toBeVisible();
  await page.keyboard.press('Enter');
  await expect(page.locator('.game-switcher')).toHaveCount(0);
  await expect(page.locator('.player.live .tune-in .what')).not.toHaveText(route.title);
});

interface DvrListJson {
  canManage: boolean;
  rules: Array<{ id: string; kind: string; teamId?: string; title: string }>;
  jobs: Array<{ id: string; state: string; ruleId: string; game: { id: string } }>;
}

/** Puts the server's recordings back: removes the rules this test made and the jobs they made. */
async function removeDvrState(teamId: string, gameId: string): Promise<void> {
  const { SERVER, adminToken } = await import('./env');
  const call = (method: string, path: string) => fetch(SERVER + path, { method, headers: { Authorization: `MediaBrowser Token="${adminToken()}"` } });
  const list = await api<DvrListJson>('/JellyTV/Client/v1/recordings');
  const rules = list.rules.filter((r) => r.teamId === teamId || r.kind === 'game');
  for (const job of list.jobs.filter((j) => j.game.id === gameId || rules.some((r) => r.id === j.ruleId))) {
    await call('DELETE', `/JellyTV/Client/v1/recordings/jobs/${job.id}`);
    await call('DELETE', `/JellyTV/Client/v1/recordings/jobs/${job.id}/recording`);
  }
  for (const rule of rules) await call('DELETE', `/JellyTV/Client/v1/recordings/rules/${rule.id}`);
}

test('DVR: record every game of a team; the game records (REC tag, watch from the start, stop); the rule under RECORDINGS; delete it', async ({ page }, info) => {
  const board = await api<{ games: Array<BoardGame & { away: { id: string; shortName: string } }> }>('/JellyTV/Client/v1/board');
  const target = board.games.find((g) => g.state === 'in' && g.watch != null && g.away.abbr === 'TOR');
  test.skip(process.env.TALLY_SIM !== '1' || target === undefined, 'needs the simulator game TOR at BAL (tally/dev/score-sim.py add --id 900001 …)');
  if (target === undefined) return;
  test.setTimeout(180_000);
  const before = await api<DvrListJson>('/JellyTV/Client/v1/recordings');
  expect(before.rules.filter((r) => r.teamId === target.away.id)).toEqual([]);
  const every = `Record every ${target.away.shortName} game`;
  try {
    await openSports(page);
    await expect(page.locator('.game-card[data-focused]')).toBeVisible();
    await focusGame(page, target.id);
    await holdOk(page);
    const menu = page.locator('.menu-panel').first();
    await expect(menu).toBeVisible();
    // RECORD is there (disabled with the server's reason when the estimate does not fit)
    await expect(menu.locator('.tally-row .label', { hasText: /^Record$/ })).toBeVisible();
    await moveTo(page, 'ArrowDown', async () => (await focusedRowLabel(page)) === every);
    await page.waitForTimeout(450);
    await page.keyboard.press('Enter');
    // keep-last: all / 5 / 10 / 20
    const keep = page.locator('.menu-panel').nth(1);
    await expect(keep.locator('.menu-title')).toHaveText(every);
    expect(await keep.locator('.tally-row .label').allTextContents()).toEqual(['Keep all', 'Keep the last 5', 'Keep the last 10', 'Keep the last 20']);
    await shot(page, info, 'sports-keep-last');
    await page.keyboard.press('ArrowDown');
    await page.waitForTimeout(450);
    await page.keyboard.press('Enter');
    await expect(page.locator('.menu-panel')).toHaveCount(1);
    await expect(menu.locator('.tally-row .label', { hasText: `Recording every ${target.away.shortName} game` })).toBeVisible({ timeout: 15_000 });
    // the rule makes a job for the live game: its state leads the DVR lines (recording, or failed with the server's
    // reason when the server has no room: the dev server keeps 10 GB free)
    const state = menu.locator('.menu-info.live');
    await expect(state).toBeVisible({ timeout: 60_000 });
    await page.waitForTimeout(300);
    await shot(page, info, 'sports-game-menu-recording');
    // a job can start recording and then fail for space a moment later: wait until it has settled on one
    const startRow = menu.locator('.tally-row .label', { hasText: 'Watch from the start' });
    await expect
      .poll(async () => (await startRow.count()) > 0 || /FAILED/.test((await state.first().textContent()) ?? ''), { timeout: 60_000 })
      .toBe(true);
    const recording = (await startRow.count()) > 0;
    if (recording) {
      await moveTo(page, 'ArrowUp', async () => (await focusedRowLabel(page)) === 'Watch from the start');
      await page.waitForTimeout(450);
      await page.keyboard.press('Enter');
      await expect(page.locator('.page:not(.hidden) .startover')).toBeVisible();
      await expect.poll(() => page.evaluate(() => (document.querySelector('.startover video') as HTMLVideoElement | null)?.currentTime ?? 0), { timeout: 45_000 }).toBeGreaterThan(0.5);
      await shot(page, info, 'startover-recording');
      await page.keyboard.press('Escape');
      await page.keyboard.press('Escape');
      await expect(page.locator('.page:not(.hidden) .sports-page')).toBeVisible();
      await expect(page.locator('.game-card[data-focused] .rec-tag.live')).toBeVisible({ timeout: 20_000 });
    } else {
      await page.keyboard.press('Escape');
      await expect(page.locator('.menu-panel')).toHaveCount(0);
    }

    // RECORDINGS: the job (recording now: HOLD to stop; failed: the reason) and the team rule (OK: change or stop)
    await page.keyboard.press('ArrowUp');
    await moveTo(page, 'ArrowRight', async () => ((await page.locator('.sports-tab[data-focused] .label').textContent()) ?? '') === 'RECORDINGS');
    await page.keyboard.press('Enter');
    const jobRow = page.locator('.dvr-row', { hasText: `${target.away.shortName} at` }).first();
    await expect(jobRow).toBeVisible({ timeout: 15_000 });
    await expect(page.locator('.dvr-row[data-focused]')).toBeVisible();
    await page.waitForTimeout(300);
    await shot(page, info, 'sports-recordings-job');
    if (recording) {
      await holdOk(page);
      await expect(page.locator('.menu-panel .tally-row .label', { hasText: 'Stop recording' })).toBeVisible();
      await shot(page, info, 'sports-job-menu');
      await moveTo(page, 'ArrowDown', async () => (await focusedRowLabel(page)) === 'Stop recording');
      await page.waitForTimeout(450);
      await page.keyboard.press('Enter');
      await expect(page.locator('.menu-panel')).toHaveCount(0);
    } else {
      await expect(page.locator('.dvr-section', { hasText: 'FAILED' })).toBeVisible();
    }
    const ruleRow = page.locator('.dvr-row', { hasText: 'KEEP THE LAST 5' });
    await expect(ruleRow).toBeVisible({ timeout: 15_000 });
    await moveTo(page, 'ArrowDown', async () => (await page.locator('.dvr-row[data-focused]', { hasText: 'KEEP THE LAST 5' }).count()) === 1, 10);
    await page.waitForTimeout(300);
    await shot(page, info, 'sports-recordings-rule');
    await page.keyboard.press('Enter');
    await expect(page.locator('.menu-panel .tally-row .marked')).toHaveCount(1);
    await shot(page, info, 'sports-rule-menu');
    await moveTo(page, 'ArrowDown', async () => /^Stop recording every /.test(await focusedRowLabel(page)));
    await page.waitForTimeout(450);
    await page.keyboard.press('Enter');
    await expect(ruleRow).toHaveCount(0, { timeout: 15_000 });
  } finally {
    await removeDvrState(target.away.id, target.id);
  }
  const after = await api<DvrListJson>('/JellyTV/Client/v1/recordings');
  expect(after.rules.filter((r) => r.teamId === target.away.id)).toEqual([]);
});

test('Start over: the page plays an HLS playlist from its first minute, LEFT/RIGHT seek with the controls hidden, BACK hides then leaves', async ({ page }, info) => {
  // A stand-in for a recording's EVENT playlist (the dev server keeps no room to record): a channel's playlist.
  const board = await api<{ channels: Array<{ hlsPath: string; name: string }> }>('/JellyTV/Client/v1/board');
  const channel = board.channels[0];
  test.skip(channel === undefined, 'no channel');
  if (channel === undefined) return;
  await openSports(page);
  await page.evaluate((path) => (window as unknown as { TallyDebug: { push: (r: unknown) => void } }).TallyDebug.push({ name: 'startover', path, title: 'Blue Jays at Orioles' }), channel.hlsPath);
  await expect(page.locator('.page:not(.hidden) .startover')).toBeVisible();
  await expect(page.locator('.startover .so-top .kicker')).toHaveText('REC · FROM THE START');
  await expect.poll(() => page.evaluate(() => (document.querySelector('.startover video') as HTMLVideoElement | null)?.currentTime ?? 0), { timeout: 45_000 }).toBeGreaterThan(0.5);
  await expect(page.locator('.so-btn[data-focused]')).toBeVisible();
  await page.waitForTimeout(500);
  await shot(page, info, 'startover');
  await page.keyboard.press('Escape');
  await expect(page.locator('.so-controls.hidden')).toHaveCount(1);
  await page.keyboard.press('ArrowRight'); // forward 30 s brings the controls back
  await expect(page.locator('.so-controls.hidden')).toHaveCount(0);
  await page.keyboard.press('Escape');
  await page.keyboard.press('Escape');
  await expect(page.locator('.page:not(.hidden) .sports-page')).toBeVisible();
});
