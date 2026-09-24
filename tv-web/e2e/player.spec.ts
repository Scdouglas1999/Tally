import { expect, test, type Page } from '@playwright/test';
import { APP, AUTH_STATE, api, shot } from './env';

test.use({ storageState: AUTH_STATE });

interface Stream {
  Type: string;
  Index: number;
  Language?: string;
  IsExternal?: boolean;
}
interface Item {
  Id: string;
  Name: string;
  MediaSources?: Array<{ MediaStreams: Stream[] }>;
}

/** Moves focus with DOWN/UP through the open menu until the focused option matches, then presses OK. */
async function pickOption(page: Page, match: RegExp): Promise<void> {
  const options = page.locator('.player .menu .option');
  await expect(page.locator('.player .menu .option[data-focused]')).toBeVisible();
  const count = await options.count();
  for (let i = 0; i < count; i++) await page.keyboard.press('ArrowUp');
  for (let i = 0; i < count; i++) {
    const text = (await page.locator('.player .menu .option[data-focused]').textContent()) ?? '';
    if (match.test(text)) {
      await page.keyboard.press('Enter');
      return;
    }
    await page.keyboard.press('ArrowDown');
  }
  throw new Error('no menu option matches ' + String(match));
}

/** Shows the controls and moves RIGHT from play/pause until the button with this caption is focused, then OK. */
async function openControl(page: Page, caption: string): Promise<void> {
  await page.keyboard.press('ArrowUp');
  await expect(page.locator('.icon-btn[data-focused]')).toBeVisible();
  for (let i = 0; i < 6; i++) {
    if (((await page.locator('.player .caption').textContent()) ?? '') === caption) break;
    await page.keyboard.press('ArrowRight');
  }
  await expect(page.locator('.player .caption')).toHaveText(caption);
  await page.keyboard.press('Enter');
}

async function videoTime(page: Page): Promise<number> {
  return page.evaluate(() => document.querySelector('video')?.currentTime ?? 0);
}

test('Film: plays (hls.js), subtitles drawn by the app, audio switch restarts with the other track', async ({ page }, info) => {
  const me = await api<{ Id: string }>('/Users/Me');
  const films = await api<{ Items: Item[] }>(`/Items?userId=${me.Id}&recursive=true&includeItemTypes=Movie&fields=MediaSources&limit=100`);
  // a test film with two audio tracks and an external text subtitle (the dev library's test patterns)
  const film = films.Items.find((f) => {
    const s = f.MediaSources?.[0]?.MediaStreams ?? [];
    return s.filter((x) => x.Type === 'Audio').length >= 2 && s.some((x) => x.Type === 'Subtitle' && x.IsExternal === true);
  });
  test.skip(film === undefined, 'no film with two audio tracks and an external subtitle on this server');
  if (film === undefined) return;
  const streams = film.MediaSources?.[0]?.MediaStreams ?? [];
  const secondAudio = streams.filter((x) => x.Type === 'Audio')[1] as Stream;

  const transcodingUrls: string[] = [];
  page.on('response', (r) => {
    if (r.url().includes('/PlaybackInfo')) {
      void r.json().then((b: { MediaSources?: Array<{ TranscodingUrl?: string }> }) => transcodingUrls.push(b.MediaSources?.[0]?.TranscodingUrl ?? ''));
    }
  });
  await page.goto(APP);
  await expect(page.locator('.home')).toBeVisible();
  await page.evaluate((id) => (window as unknown as { TallyDebug: { push: (r: unknown) => void } }).TallyDebug.push({ name: 'player', itemId: id, startMs: 0 }), film.Id);
  await expect.poll(() => videoTime(page), { timeout: 30_000 }).toBeGreaterThan(1);
  await expect(page.locator('.player .osd-top .title')).toHaveText(film.Name);
  await shot(page, info, 'player-osd');

  // Subtitles: the external track, drawn by the app from the server's WebVTT
  await openControl(page, 'SUBTITLES');
  await expect(page.locator('.player .menu .menu-title')).toHaveText('SUBTITLES');
  await pickOption(page, /External/); // Jellyfin's display title of the external track
  await expect(page.locator('.player .subtitle-layer span')).toBeVisible({ timeout: 30_000 });
  await shot(page, info, 'player-subtitles');

  // Audio: the second track; the stream restarts at the same position with AudioStreamIndex set
  const before = await videoTime(page);
  await openControl(page, 'AUDIO');
  await expect(page.locator('.player .menu .menu-title')).toHaveText('AUDIO');
  // the menu opens on the current (first) track; move to the second
  await expect(page.locator('.player .menu .option.selected[data-focused]')).toBeVisible();
  await page.keyboard.press('ArrowDown');
  await expect(page.locator('.player .menu .option').nth(1)).toHaveAttribute('data-focused', 'true');
  await page.keyboard.press('Enter');
  // the server's stream really carries the other track (not just our request)
  await expect.poll(() => transcodingUrls.some((u) => u.includes(`AudioStreamIndex=${secondAudio.Index}`)), { timeout: 20_000 }).toBe(true);
  // and it resumed where it was, not from the start
  await expect.poll(() => videoTime(page), { timeout: 30_000 }).toBeGreaterThan(before + 0.5);
  expect(await videoTime(page)).toBeLessThan(before + 20);
  await shot(page, info, 'player-audio-switched');
});

test('Live: a channel from the plugin continuous playlist, with the score bug', async ({ page }, info) => {
  const board = await api<{ games: Array<{ id: string; state: string; home: { shortName: string }; away: { shortName: string }; watch?: { channelId: string; hlsPath: string } }> }>('/JellyTV/Client/v1/board');
  const game = board.games.find((g) => g.watch != null && g.state === 'in') ?? board.games.find((g) => g.watch != null);
  test.skip(game === undefined || game.watch == null, 'no game with a channel on the board right now');
  if (game === undefined || game.watch == null) return;
  const hls: string[] = [];
  page.on('request', (r) => {
    if (r.url().includes('/JellyTV/Live/')) hls.push(r.url());
  });
  await page.goto(APP);
  await expect(page.locator('.home')).toBeVisible();
  const route = { name: 'live', channelId: game.watch.channelId, hlsPath: game.watch.hlsPath, title: `${game.away.shortName} at ${game.home.shortName}`, gameId: game.id };
  await page.evaluate((r) => (window as unknown as { TallyDebug: { push: (r: unknown) => void } }).TallyDebug.push(r), route);
  await expect(page.locator('.tune-in .label')).toHaveText('TUNING IN');
  await shot(page, info, 'live-tuning-in');
  await expect.poll(() => videoTime(page), { timeout: 45_000 }).toBeGreaterThan(1);
  expect(hls.some((u) => u.includes(`/JellyTV/Live/${game.watch?.channelId}`))).toBe(true);
  await expect(page.locator('.live-bar .live-tag')).toHaveText('LIVE');
  if (game.state === 'in') await expect(page.locator('.score-bug')).toBeVisible();
  await shot(page, info, 'live-playing');
});
