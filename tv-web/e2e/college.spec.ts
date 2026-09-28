import { expect, test, type Page, type Route } from '@playwright/test';
import { APP, AUTH_STATE, api, shot } from './env';

/**
 * College football on the board: the AP rank ("#7") before a team's name on the game card, the focused-game panel,
 * the in-player score bug and the switcher, and the server's UPSET ALERT tag. The dev server's college games are
 * finished by the time its live NFL games play, so two live games with streams get what a college game carries
 * (league NCAAF, `rank`, the tag) on their way to the app.
 */
test.use({ storageState: AUTH_STATE });

interface Team {
  shortName: string;
  abbr: string;
  score?: number | null;
  rank?: number | null;
}
interface BoardGame {
  id: string;
  sport: string;
  state: string;
  league: string;
  home: Team;
  away: Team;
  tags?: string[];
  watch?: { hlsPath: string } | null;
}
type Board = { games: BoardGame[] } & Record<string, unknown>;

const BOARD = '**/JellyTV/Client/v1/board*';
const push = (page: Page, route: unknown) => page.evaluate((r) => (window as unknown as { TallyDebug: { push: (r: unknown) => void } }).TallyDebug.push(r), route);

/** The side that trails (away on a tie): the ranked favorite an upset alert is about. */
const trailing = (g: BoardGame): 'home' | 'away' => ((g.home.score ?? 0) < (g.away.score ?? 0) ? 'home' : 'away');

interface Setup {
  upset: BoardGame;
  /** The upset game's ranked side (#7), trailing an unranked team. */
  favorite: 'home' | 'away';
  /** Another live game, its home team ranked #12. */
  other: BoardGame;
}

async function setUp(page: Page): Promise<Setup | null> {
  const real = await api<Board>('/JellyTV/Client/v1/board');
  const live = real.games
    .filter((g) => g.state === 'in' && g.watch != null && g.watch.hlsPath !== '')
    .sort((a, b) => (a.sport === 'football' ? 0 : 1) - (b.sport === 'football' ? 0 : 1)); // football first
  const [upset, other] = live;
  if (upset === undefined || other === undefined) return null;
  await page.route(BOARD, async (route: Route) => {
    let response;
    let json: Board;
    try {
      response = await route.fetch();
      json = (await response.json()) as Board;
    } catch {
      return; // the page went away while the board was on its way
    }
    for (const g of json.games) {
      if (g.id === upset.id) {
        if (g.sport === 'football') g.league = 'NCAAF';
        const fav = trailing(g);
        g[fav].rank = 7;
        g[fav === 'home' ? 'away' : 'home'].rank = null;
        g.tags = [...(g.tags ?? []), 'UPSET ALERT'];
      }
      if (g.id === other.id) {
        if (g.sport === 'football') g.league = 'NCAAF';
        g.home.rank = 12;
        g.away.rank = null;
      }
    }
    await route.fulfill({ response, json }).catch(() => undefined);
  });
  return { upset, other, favorite: trailing(upset) };
}

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

test('College football: "#7" before the name on the card, the panel, the score bug and the switcher; UPSET ALERT tag', async ({ page }, info) => {
  const s = await setUp(page);
  test.skip(s === null, 'needs two live games with streams on the dev board');
  if (s === null) return;
  await page.goto(APP);
  await expect(page.locator('.home')).toBeVisible();
  await push(page, { name: 'sports' });
  await expect(page.locator('.page:not(.hidden) .game-card[data-focused]')).toBeVisible();
  await focusCard(page, s.upset.id);

  // the card: the rank before the ranked team's name only, the tag in the strip
  const fi = s.favorite === 'away' ? 0 : 1;
  const fav = s.upset[s.favorite];
  const card = page.locator(`.page:not(.hidden) .board-rows .game-card[data-game="${s.upset.id}"]`);
  await expect(card.locator('.team .team-rank')).toHaveCount(1);
  await expect(card.locator('.team').nth(fi).locator('.team-rank')).toHaveText('#7');
  await expect(card.locator('.team').nth(fi).locator('.name')).toHaveText(`#7${fav.shortName || fav.abbr}`);
  await expect(card.locator('.strip .upset-tag')).toHaveText('UPSET ALERT');
  await expect(page.locator(`.page:not(.hidden) .board-rows .game-card[data-game="${s.other.id}"] .team`).nth(1).locator('.team-rank')).toHaveText('#12');
  // the focused-game panel
  const panel = page.locator('.page:not(.hidden) .hero-panel');
  await expect(panel.locator('.hero-team').nth(fi).locator('.team-rank')).toHaveText('#7');
  await expect(panel.locator('.hero-team').nth(1 - fi).locator('.team-rank')).toHaveCount(0);
  await expect(panel.locator('.hero-tags .upset-tag')).toHaveText('UPSET ALERT');
  // the name still fits beside the rank (no ellipsis cut on the card)
  const clipped = await card.locator('.team').nth(fi).locator('.name').evaluate((el) => el.scrollWidth > el.clientWidth);
  expect(clipped).toBe(false);
  await page.waitForTimeout(400);
  await shot(page, info, 'college-board');

  // the player: the score bug ranks the favorite
  await page.keyboard.press('Enter');
  await expect(page.locator('.page:not(.hidden) .player.live')).toBeVisible();
  const bug = page.locator('.player.live .score-bug');
  await expect(bug).toBeVisible({ timeout: 30_000 });
  await expect(bug.locator('.team-rank')).toHaveText('#7');
  await expect(bug.locator('.line1')).toContainText(`#7${fav.abbr || fav.shortName} `);
  await page.waitForTimeout(600);
  await shot(page, info, 'college-score-bug');

  // the switcher: the other college game's card, its home team ranked
  await page.keyboard.press('Escape');
  await page.keyboard.press('ArrowDown');
  await expect(page.locator('.game-switcher')).toBeVisible();
  const sw = page.locator(`.game-switcher .game-card[data-game="${s.other.id}"]`);
  await expect(sw.locator('.team').nth(1).locator('.team-rank')).toHaveText('#12');
  await page.waitForTimeout(400);
  await shot(page, info, 'college-switcher');
});
