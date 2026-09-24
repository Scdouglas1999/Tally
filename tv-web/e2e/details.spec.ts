import { expect, test, type Page } from '@playwright/test';
import { APP, AUTH_STATE, SERVER, adminToken, api, shot } from './env';

test.use({ storageState: AUTH_STATE });

interface Item {
  Id: string;
  Name: string;
  Type: string;
  SeriesId?: string;
  SeasonId?: string;
  UserData?: { Played?: boolean; IsFavorite?: boolean };
  People?: Array<{ Id: string; Name: string; Type: string }>;
}

async function me(): Promise<string> {
  return (await api<{ Id: string }>('/Users/Me')).Id;
}

async function find(type: string, name: string): Promise<Item> {
  const user = await me();
  const r = await api<{ Items: Item[] }>(`/Items?userId=${user}&recursive=true&includeItemTypes=${type}&searchTerm=${encodeURIComponent(name)}&fields=People`);
  const item = r.Items.find((i) => i.Name === name);
  if (item === undefined) throw new Error(`${type} "${name}" is not on this server`);
  return item;
}

interface UserData {
  Played?: boolean;
  PlaybackPositionTicks?: number;
  PlayCount?: number;
  LastPlayedDate?: string | null;
}

/** Sets an item's watched state and resume point for the admin (the test puts the original back). */
async function setUserData(itemId: string, data: UserData): Promise<void> {
  const user = await me();
  const r = await fetch(`${SERVER}/UserItems/${itemId}/UserData?userId=${user}`, {
    method: 'POST',
    headers: { Authorization: `MediaBrowser Token="${adminToken()}"`, 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
  if (!r.ok) throw new Error('UserData: HTTP ' + String(r.status));
}

async function userData(itemId: string): Promise<UserData> {
  const user = await me();
  const d = await api<UserData>(`/UserItems/${itemId}/UserData?userId=${user}`);
  // everything a watched toggle or a resume point changes, so it can all be put back
  return { Played: d.Played ?? false, PlaybackPositionTicks: d.PlaybackPositionTicks ?? 0, PlayCount: d.PlayCount ?? 0, LastPlayedDate: d.LastPlayedDate ?? null };
}

async function open(page: Page, route: Record<string, string>): Promise<void> {
  await page.evaluate((r) => (window as unknown as { TallyDebug: { push: (r: unknown) => void } }).TallyDebug.push(r), route);
}

const focused = (page: Page) => page.locator('.page:not(.hidden) [data-focused]').last();
/** The focused button's label (without its glyph). */
const button = (page: Page) => page.locator('.page:not(.hidden) .btn[data-focused] > span:not(.glyph):not(.btn-trailing)');
const primary = (page: Page) => page.locator('.page:not(.hidden) .btn.primary[data-focused] > span:not(.glyph)');

/** Presses `key` `times` times at remote speed (a real key repeat is ~50 ms apart, not back to back). */
async function press(page: Page, key: string, times = 1): Promise<void> {
  for (let i = 0; i < times; i++) {
    await page.keyboard.press(key);
    await page.waitForTimeout(60);
  }
}

/** Moves RIGHT along the action row until the focused button reads `label`. */
async function toButton(page: Page, label: RegExp): Promise<void> {
  for (let i = 0; i < 8; i++) {
    if (label.test((await button(page).textContent()) ?? '')) return;
    await page.keyboard.press('ArrowRight');
  }
  throw new Error('no button ' + String(label));
}

test('Film: header, action row, rows, menu, person page and back (Dune)', async ({ page }, info) => {
  const dune = await find('Movie', 'Dune');
  await page.goto(APP);
  await expect(page.locator('.home')).toBeVisible();
  await open(page, { name: 'item', itemId: dune.Id });
  const detail = page.locator('.page:not(.hidden) .detail-page');
  await expect(detail.locator('.dh-kicker')).toHaveText('FILM');
  // the primary button has focus on arrival
  await expect(primary(page)).toHaveText(/^(PLAY|RESUME \d+%)$/);
  await expect(detail.locator('.dh-meta')).toContainText('PG-13');
  await expect(detail.locator('.dh-meta')).toContainText('1m 30s');
  await expect(detail.locator('.dh-tech .box').first()).toHaveText('360p');
  await expect(detail.locator('.row-header .title').first()).toHaveText('CAST & CREW');
  await expect(detail.locator('.dh-logo img')).toBeVisible();
  await page.waitForTimeout(600); // images
  await shot(page, info, 'film');
  // LEFT from the first button opens the rail; RIGHT comes back to it
  await page.keyboard.press('ArrowLeft');
  await expect(page.locator('.rail.open')).toBeVisible();
  await page.keyboard.press('ArrowRight');
  await expect(page.locator('.rail.open')).toHaveCount(0);
  await expect(primary(page)).toBeVisible();

  // WATCHED toggles (and the server's play count and date are put back afterwards)
  const played = dune.UserData?.Played === true;
  const original = await userData(dune.Id);
  try {
    await toButton(page, played ? /^WATCHED$/ : /^UNWATCHED$/);
    await page.keyboard.press('Enter');
    await expect(button(page)).toHaveText(played ? 'UNWATCHED' : 'WATCHED');
    await page.keyboard.press('Enter');
    await expect(button(page)).toHaveText(played ? 'WATCHED' : 'UNWATCHED');
  } finally {
    await setUserData(dune.Id, original);
  }

  // MORE: the item menu; BACK closes it and MORE has focus again
  await toButton(page, /^MORE$/);
  await page.keyboard.press('Enter');
  await expect(page.locator('.panel-window .panel-kicker')).toHaveText('DUNE');
  await expect(page.locator('.panel-row[data-focused] .headline')).toHaveText(/^(Play|Resume)$/);
  await shot(page, info, 'film-menu');
  await page.keyboard.press('Escape');
  await expect(page.locator('.panel-window')).toHaveCount(0);
  await expect(button(page)).toHaveText('MORE');

  // DOWN: cast & crew (the row takes focus at its first card), then chapters, then more like this
  await page.keyboard.press('ArrowDown');
  await expect(focused(page)).toHaveClass(/person/);
  await expect(focused(page).locator('.title')).toHaveText(dune.People?.[0]?.Name ?? '');
  await shot(page, info, 'film-cast');
  await page.keyboard.press('ArrowDown');
  await expect(focused(page).locator('.kicker')).toHaveText('CHAPTER 1 · 00:00');
  await page.waitForTimeout(400);
  await shot(page, info, 'film-chapters');
  await page.keyboard.press('ArrowDown');
  await expect(detail.locator('.detail-top-scrim')).toBeVisible();
  await page.waitForTimeout(500);
  await shot(page, info, 'film-similar');

  // UP twice: back to the cast row; RIGHT twice and OK opens that person's page
  await press(page, 'ArrowUp', 2);
  await expect(focused(page)).toHaveClass(/person/);
  await press(page, 'ArrowRight', 2);
  const person = dune.People?.[2];
  await expect(focused(page).locator('.title')).toHaveText(person?.Name ?? '');
  await page.keyboard.press('Enter');
  const personPage = page.locator('.page:not(.hidden) .person-page');
  await expect(personPage.locator('.name')).toHaveText(person?.Name ?? '');
  await expect(button(page)).toHaveText(/^FAVORITE(D)?$/);
  await expect(personPage.locator('.row-header .title').first()).toHaveText('FILMS');
  await page.waitForTimeout(600);
  await shot(page, info, 'person');
  await page.keyboard.press('ArrowDown');
  await expect(focused(page)).toHaveClass(/card/);
  await shot(page, info, 'person-films');

  // BACK: the film page again, on the same person card
  await page.keyboard.press('Escape');
  await expect(focused(page).locator('.title')).toHaveText(person?.Name ?? '');
  // UP: the action row, on the button focused last (MORE)
  await page.keyboard.press('ArrowUp');
  await expect(button(page)).toHaveText('MORE');
  await expect(detail.locator('.detail-top-scrim')).toHaveCount(0);
  // BACK: Home
  await page.keyboard.press('Escape');
  await expect(page.locator('.page:not(.hidden) .home')).toBeVisible();
});

test('Film: a resume point (RESUME n%, FROM THE START, ENDS) and the cut overview opens in full', async ({ page }, info) => {
  const dune = await find('Movie', 'Dune');
  const original = await userData(dune.Id);
  try {
    await setUserData(dune.Id, { Played: false, PlaybackPositionTicks: 450_115_000 }); // 45 s of 90 s
    await page.goto(APP);
    await expect(page.locator('.home')).toBeVisible();
    await open(page, { name: 'item', itemId: dune.Id });
    await expect(primary(page)).toHaveText('RESUME 50%');
    await page.keyboard.press('ArrowRight');
    await expect(button(page)).toHaveText('FROM THE START');
    await expect(page.locator('.page:not(.hidden) .dh-ends')).toHaveText(/^ENDS \d{1,2}:\d\d (AM|PM)$/);
    await page.waitForTimeout(500);
    await shot(page, info, 'film-resume');
    // UP: the overview (cut at 4 lines, so it takes focus); OK opens all of it
    await page.keyboard.press('ArrowUp');
    await expect(page.locator('.page:not(.hidden) .dh-overview[data-focused]')).toBeVisible();
    await shot(page, info, 'film-overview-focused');
    await page.keyboard.press('Enter');
    await expect(page.locator('.panel-window .panel-body')).toContainText('Paul Atreides');
    await shot(page, info, 'film-overview-open');
    await page.keyboard.press('Escape');
    await expect(page.locator('.page:not(.hidden) .dh-overview[data-focused]')).toBeVisible();
    await page.keyboard.press('ArrowDown');
    await expect(button(page)).toHaveText('FROM THE START');
  } finally {
    await setUserData(dune.Id, original);
  }
});

test("Film: the collection's next film (Toy Story → Toy Story 2)", async ({ page }, info) => {
  const toyStory = await find('Movie', 'Toy Story');
  await page.goto(APP);
  await expect(page.locator('.home')).toBeVisible();
  await open(page, { name: 'item', itemId: toyStory.Id });
  const detail = page.locator('.page:not(.hidden) .detail-page');
  await expect(detail.locator('.row-header .title', { hasText: 'NEXT IN THE COLLECTION' })).toBeVisible();
  const row = detail.locator('.media-row', { has: page.locator('.row-header .title', { hasText: 'NEXT IN THE COLLECTION' }) });
  await expect(row.locator('.card .bar .title')).toHaveText('Toy Story 2');
  // walk down to it: cast, chapters, then the collection row
  await press(page, 'ArrowDown', 3);
  await expect(focused(page).locator('.bar .title')).toHaveText('Toy Story 2');
  await page.waitForTimeout(500);
  await shot(page, info, 'film-collection-next');
  await page.keyboard.press('Enter');
  await expect(page.locator('.page:not(.hidden) .detail-page .dh-logo img, .page:not(.hidden) .detail-page .dh-title').first()).toBeVisible();
  await expect(page.locator('.page:not(.hidden) .dh-meta')).toContainText('1999');
  await page.keyboard.press('Escape');
  await expect(focused(page).locator('.bar .title')).toHaveText('Toy Story 2');
});

test('Series: header, seasons, rundown with tabs, NEXT UP, the episode page (Severance)', async ({ page }, info) => {
  const severance = await find('Series', 'Severance');
  const user = await me();
  const nextUp = (await api<{ Items: Item[] }>(`/Shows/NextUp?userId=${user}&seriesId=${severance.Id}`)).Items[0];
  await page.goto(APP);
  await expect(page.locator('.home')).toBeVisible();
  await open(page, { name: 'item', itemId: severance.Id });
  const detail = page.locator('.page:not(.hidden) .detail-page');
  await expect(detail.locator('.dh-kicker')).toHaveText('SERIES');
  await expect(detail.locator('.dh-meta')).toContainText('SEASONS');
  await expect(primary(page)).toHaveText(nextUp !== undefined ? /^(NEXT UP · S\d+ E\d+|RESUME S\d+ E\d+ · \d+%)$/ : /^PLAY$/);
  await expect(detail.locator('.row-header .title').first()).toHaveText('SEASONS');
  await page.waitForTimeout(600);
  await shot(page, info, 'series');

  await page.keyboard.press('ArrowDown');
  await expect(focused(page).locator('.bar .title')).toHaveText('Season 1');
  await shot(page, info, 'series-seasons');

  // SEASONS opens the rundown on the Next Up episode
  await page.keyboard.press('ArrowUp');
  await toButton(page, /^SEASONS$/);
  await page.keyboard.press('Enter');
  const rundown = page.locator('.page:not(.hidden) .rundown');
  await expect(rundown.locator('.season-tab.current')).toBeVisible();
  await expect(rundown.locator('.rundown-head .kicker')).toHaveText('SEVERANCE');
  if (nextUp !== undefined) {
    await expect(page.locator('.page:not(.hidden) .episode-row[data-focused] .title')).toHaveText(nextUp.Name);
    await expect(page.locator('.page:not(.hidden) .episode-row[data-focused] .meta')).toContainText('ENDS');
    await expect(rundown.locator('.episode-row .next-up')).toHaveCount(1);
  }
  await expect(rundown.locator('.summary')).toContainText('EPISODES');
  await page.waitForTimeout(600);
  await shot(page, info, 'rundown');

  // UP from the first row reaches the current tab; RIGHT to the next season, which takes over after resting
  await press(page, 'ArrowUp', 6);
  await expect(page.locator('.page:not(.hidden) .season-tab[data-focused]')).toHaveText('SEASON 1');
  await page.keyboard.press('ArrowRight');
  await expect(page.locator('.page:not(.hidden) .season-tab[data-focused]')).toHaveText('SEASON 2');
  await expect(rundown.locator('.season-tab.current')).toHaveText('SEASON 2');
  await expect(rundown.locator('.episode-row').first().locator('.number')).toHaveText('E01');
  await page.keyboard.press('ArrowDown');
  await expect(page.locator('.page:not(.hidden) .episode-row[data-focused] .number')).toHaveText(/^E0\d$/);
  await page.waitForTimeout(500);
  await shot(page, info, 'rundown-season-2');

  // MENU on a row: the episode's menu; Go to opens the episode page
  await page.keyboard.press('ContextMenu');
  await expect(page.locator('.panel-row[data-focused] .headline')).toHaveText('Go to');
  await shot(page, info, 'rundown-menu');
  await page.keyboard.press('Enter');
  const episode = page.locator('.page:not(.hidden) .detail-page');
  await expect(episode.locator('.dh-kicker')).toHaveText(/^SEVERANCE · S2 E\d$/);
  await expect(primary(page)).toHaveText(/^(PLAY|RESUME \d+%)$/);
  await expect(episode.locator('.dh-tech .box').first()).toBeVisible();
  await page.waitForTimeout(600);
  await shot(page, info, 'episode');
  await page.keyboard.press('ArrowDown');
  await expect(focused(page)).toHaveClass(/person/);
  await press(page, 'ArrowDown', 2);
  await page.waitForTimeout(500);
  await shot(page, info, 'episode-rows');

  // BACK: the rundown, on the same row; BACK: the series page
  await page.keyboard.press('Escape');
  await expect(page.locator('.page:not(.hidden) .episode-row[data-focused]')).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(page.locator('.page:not(.hidden) .detail-page .dh-kicker')).toHaveText('SERIES');
});

test('Home: a card opens its page (an episode its season rundown, a series its series page)', async ({ page }, info) => {
  await page.goto(APP);
  await expect(page.locator('.home')).toBeVisible();
  const kicker = page.locator('.home-header .kicker');
  const card = page.locator('.home .card[data-focused] .bar .title');
  /** DOWN until the header names a row matching `row` (the header follows focus on the next render). */
  const toRow = async (row: RegExp): Promise<boolean> => {
    for (let i = 0; i < 8; i++) {
      if (row.test((await kicker.textContent()) ?? '')) return true;
      await page.keyboard.press('ArrowDown');
      await page.waitForTimeout(250);
    }
    return row.test((await kicker.textContent()) ?? '');
  };

  // Next Up: an episode card opens the rundown on that episode
  if (await toRow(/^NEXT UP/)) {
    const episode = (await card.textContent()) ?? '';
    await page.keyboard.press('Enter');
    await expect(page.locator('.page:not(.hidden) .rundown .episode-row[data-focused] .title')).toHaveText(episode);
    await page.waitForTimeout(500);
    await shot(page, info, 'home-to-rundown');
    await page.keyboard.press('Escape');
    await expect(card).toHaveText(episode);
  }

  // a series card opens the series page
  expect(await toRow(/^RECENTLY ADDED IN SHOWS/)).toBe(true);
  const series = (await card.textContent()) ?? '';
  await page.keyboard.press('Enter');
  await expect(page.locator('.page:not(.hidden) .detail-page .dh-kicker')).toHaveText('SERIES');
  await expect(primary(page)).toBeVisible();
  await page.waitForTimeout(500);
  await shot(page, info, 'home-to-series');
  await page.keyboard.press('Escape');
  await expect(card).toHaveText(series);
});

test('Rundown: an episode in progress shows its percent and progress bar', async ({ page }, info) => {
  const severance = await find('Series', 'Severance');
  const user = await me();
  const episodes = (await api<{ Items: Item[] }>(`/Shows/${severance.Id}/Episodes?userId=${user}`)).Items;
  const third = episodes.find((e) => e.Name === 'In Perpetuity');
  if (third === undefined) throw new Error('fixture changed');
  const original = await userData(third.Id);
  try {
    await setUserData(third.Id, { Played: false, PlaybackPositionTicks: 180_069_000 }); // 30%
    await page.goto(APP);
    await expect(page.locator('.home')).toBeVisible();
    await open(page, { name: 'season', seriesId: severance.Id, seasonId: third.SeasonId ?? '', episodeId: third.Id });
    const row = page.locator('.page:not(.hidden) .episode-row[data-focused]');
    await expect(row.locator('.title')).toHaveText('In Perpetuity');
    await expect(row.locator('.percent')).toHaveText('30%');
    await expect(row.locator('.still .progress')).toBeVisible();
    await page.waitForTimeout(500);
    await shot(page, info, 'rundown-progress');
    // EPISODES on the episode page comes back here on the same episode
    await page.keyboard.press('ContextMenu');
    await page.keyboard.press('Enter'); // Go to
    await expect(page.locator('.page:not(.hidden) .detail-page .dh-kicker')).toHaveText('SEVERANCE · S1 E3');
    await expect(primary(page)).toHaveText('RESUME 30%');
    await toButton(page, /^EPISODES$/);
    await page.keyboard.press('Enter');
    await expect(page.locator('.page:not(.hidden) .episode-row[data-focused] .title')).toHaveText('In Perpetuity');
  } finally {
    await setUserData(third.Id, original);
  }
});
