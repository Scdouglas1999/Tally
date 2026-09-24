import { expect, test, type Page } from '@playwright/test';
import { APP, AUTH_STATE, SERVER, adminToken, api, shot } from './env';

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
  Type: string;
  SeriesId?: string;
  IndexNumber?: number;
  RunTimeTicks?: number;
  Chapters?: unknown[];
  Trickplay?: Record<string, unknown>;
  MediaSources?: Array<{ MediaStreams: Stream[] }>;
}
interface UserData {
  PlaybackPositionTicks: number;
  PlayCount: number;
  Played: boolean;
  LastPlayedDate?: string;
  IsFavorite: boolean;
}

const debug = (page: Page, route: unknown) =>
  page.evaluate((r) => (window as unknown as { TallyDebug: { push: (r: unknown) => void } }).TallyDebug.push(r), route);

async function videoTime(page: Page): Promise<number> {
  return page.evaluate(() => document.querySelector('video')?.currentTime ?? 0);
}

/** The caption under the focused control button ('' when none is focused). */
async function focusedCaption(page: Page): Promise<string> {
  return page.evaluate(() => document.querySelector('.pc-btn[data-focused] + .pc-cap')?.textContent ?? '');
}

/** With the controls up, moves along the button row until the button with this caption is focused. */
async function focusButton(page: Page, caption: string): Promise<void> {
  for (let i = 0; i < 12 && (await focusedCaption(page)) !== caption; i++) await page.keyboard.press('ArrowRight');
  for (let i = 0; i < 12 && (await focusedCaption(page)) !== caption; i++) await page.keyboard.press('ArrowLeft');
  expect(await focusedCaption(page)).toBe(caption);
}

/** Shows the controls (UP with them hidden) and presses the button with this caption. */
async function pressButton(page: Page, caption: string): Promise<void> {
  if ((await page.locator('.pc-band').count()) === 0) await page.keyboard.press('ArrowUp');
  await expect(page.locator('.pc-row')).toBeVisible();
  await focusButton(page, caption);
  await page.keyboard.press('Enter');
}

/** Moves focus in the open panel to the row whose label matches, then OK. */
async function pickRow(page: Page, match: RegExp): Promise<void> {
  const focused = page.locator('.pc-prow[data-focused] .label');
  await expect(focused).toBeVisible();
  for (let i = 0; i < 16; i++) await page.keyboard.press('ArrowUp');
  for (let i = 0; i < 16; i++) {
    if (match.test((await focused.textContent()) ?? '')) {
      await page.keyboard.press('Enter');
      return;
    }
    await page.keyboard.press('ArrowDown');
  }
  throw new Error('no panel row matches ' + String(match));
}

/** A remote media key as a browser reports it (Playwright's keyboard has no MediaFastForward). */
async function mediaKey(page: Page, key: string): Promise<void> {
  await page.evaluate((k) => window.dispatchEvent(new KeyboardEvent('keydown', { key: k, bubbles: true })), key);
}

async function userData(userId: string, itemId: string): Promise<UserData> {
  return api<UserData>(`/UserItems/${itemId}/UserData?userId=${userId}`);
}

interface Defaults {
  audio: number | null;
  subtitle: number | null;
}

/** The tracks the server picks for the viewer (it remembers the last ones played: RememberAudio/SubtitleSelections). */
async function trackDefaults(userId: string, itemId: string): Promise<Defaults> {
  const r = await fetch(`${SERVER}/Items/${itemId}/PlaybackInfo?userId=${userId}`, {
    method: 'POST',
    headers: { Authorization: `MediaBrowser Token="${adminToken()}"`, 'Content-Type': 'application/json' },
    body: '{}',
  });
  const b = (await r.json()) as { MediaSources: Array<{ DefaultAudioStreamIndex?: number; DefaultSubtitleStreamIndex?: number }> };
  return { audio: b.MediaSources[0]?.DefaultAudioStreamIndex ?? null, subtitle: b.MediaSources[0]?.DefaultSubtitleStreamIndex ?? null };
}

/** Puts the remembered tracks back: a progress report with them is how Jellyfin learns a viewer's choice. */
async function restoreTracks(itemId: string, d: Defaults): Promise<void> {
  const post = (path: string, body: unknown) =>
    fetch(`${SERVER}${path}`, {
      method: 'POST',
      headers: { Authorization: `MediaBrowser Token="${adminToken()}"`, 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
  const common = { ItemId: itemId, MediaSourceId: itemId, PlaySessionId: 'e2e-restore', PositionTicks: 0 };
  await post('/Sessions/Playing/Progress', { ...common, AudioStreamIndex: d.audio, SubtitleStreamIndex: d.subtitle ?? -1, PlayMethod: 'DirectPlay' });
  await post('/Sessions/Playing/Stopped', common);
}

/**
 * Puts an item's played state and resume point back as they were before the test. The server applies stop reports
 * in the background a few seconds after answering them (and marks items under 5 minutes played on any stop), so the
 * restore is written again until it has stayed put for 3 s.
 */
async function restoreUserData(userId: string, itemId: string, data: UserData): Promise<void> {
  const same = (now: UserData): boolean =>
    now.Played === data.Played && now.PlayCount === data.PlayCount && now.PlaybackPositionTicks === data.PlaybackPositionTicks;
  for (let attempt = 0; attempt < 6; attempt++) {
    await writeUserData(userId, itemId, data);
    let stayed = true;
    for (let check = 0; check < 3 && stayed; check++) {
      await new Promise((resolve) => setTimeout(resolve, 1000));
      stayed = same(await userData(userId, itemId));
    }
    if (stayed) return;
  }
  throw new Error('the user data of ' + itemId + ' did not stay restored');
}

async function writeUserData(userId: string, itemId: string, data: UserData): Promise<void> {
  const r = await fetch(`${SERVER}/UserItems/${itemId}/UserData?userId=${userId}`, {
    method: 'POST',
    headers: { Authorization: `MediaBrowser Token="${adminToken()}"`, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      PlaybackPositionTicks: data.PlaybackPositionTicks,
      PlayCount: data.PlayCount,
      Played: data.Played,
      LastPlayedDate: data.LastPlayedDate ?? null,
      IsFavorite: data.IsFavorite,
    }),
  });
  if (!r.ok) throw new Error('restoring user data failed: HTTP ' + String(r.status));
}

test('Film: controls, seek bar with trickplay, chapters, D-pad seek, settings panel, quality, sleep timer, BACK', async ({ page }, info) => {
  test.setTimeout(240_000);
  const me = await api<{ Id: string }>('/Users/Me');
  const films = await api<{ Items: Item[] }>(`/Items?userId=${me.Id}&recursive=true&includeItemTypes=Movie&fields=MediaSources,Chapters,Trickplay&limit=200`);
  const film = films.Items.find((f) => {
    const s = f.MediaSources?.[0]?.MediaStreams ?? [];
    return (f.Chapters?.length ?? 0) >= 3 && f.Trickplay != null && s.filter((x) => x.Type === 'Audio').length >= 2 && s.some((x) => x.Type === 'Subtitle' && x.IsExternal !== true);
  });
  test.skip(film === undefined, 'no film with chapters, trickplay, two audio tracks and an embedded subtitle on this server');
  if (film === undefined) return;
  const before = await userData(me.Id, film.Id);
  const tracksBefore = await trackDefaults(me.Id, film.Id);
  const reports: string[] = [];
  const transcodingUrls: string[] = [];
  page.on('request', (r) => {
    const m = /\/Sessions\/Playing(\/Progress|\/Stopped)?$/.exec(new URL(r.url()).pathname);
    if (m !== null && r.method() === 'POST') reports.push(m[1] ?? '/start');
  });
  page.on('response', (r) => {
    if (r.url().includes('/PlaybackInfo')) {
      void r.json().then((b: { MediaSources?: Array<{ TranscodingUrl?: string }> }) => transcodingUrls.push(b.MediaSources?.[0]?.TranscodingUrl ?? ''));
    }
  });
  try {
    await page.goto(APP);
    await expect(page.locator('.home')).toBeVisible();
    await debug(page, { name: 'player', itemId: film.Id, startMs: 0 });
    await expect.poll(() => videoTime(page), { timeout: 30_000 }).toBeGreaterThan(1.5);

    // playback starts with the controls down; UP brings them, focus on play/pause (PAUSE caption)
    await expect(page.locator('.pc-band')).toHaveCount(0);
    await page.keyboard.press('ArrowUp');
    await expect(page.locator('.pc-top .title')).toHaveText(film.Name);
    await expect(page.locator('.pc-top .kicker')).toHaveText('FILM');
    await expect(page.locator('.pc-btn.primary[data-focused]')).toBeVisible();
    expect(await focusedCaption(page)).toBe('PAUSE');
    await expect(page.locator('.pc-group.left .pc-btn')).toHaveCount(1); // chapters (a film has no queue)
    await shot(page, info, 'player-controls');

    // UP: the seek bar; the trickplay preview rides above it; RIGHT twice moves the target 60 s, the seek follows
    // 750 ms after the last move
    await page.keyboard.press('ArrowUp');
    await expect(page.locator('.pc-seek[data-focused]')).toBeVisible();
    await expect(page.locator('.pc-preview .thumb')).toBeVisible();
    const from = await videoTime(page);
    await page.keyboard.press('ArrowRight');
    await page.keyboard.press('ArrowRight');
    await expect(page.locator('.pc-preview .bar .chapter')).toHaveText('Chapter 3');
    await page.waitForTimeout(300);
    await shot(page, info, 'player-seek-trickplay');
    await expect.poll(() => videoTime(page), { timeout: 10_000 }).toBeGreaterThan(from + 50);

    // DOWN back to the buttons, DOWN again: the chapter row, focused on the chapter playing now
    await page.keyboard.press('ArrowDown');
    await expect(page.locator('.pc-btn[data-focused]')).toBeVisible();
    await page.keyboard.press('ArrowDown');
    await expect(page.locator('.pc-cards .row-header .title')).toHaveText('CHAPTERS');
    await expect(page.locator('.pc-card[data-focused] .pc-current')).toBeVisible();
    await expect(page.locator('.pc-card[data-focused] .kicker')).toHaveText('CHAPTER 3 · 01:00');
    await shot(page, info, 'player-chapters');
    // UP returns to the controls; DOWN, LEFT, OK: chapter 2 from its start, controls hidden
    await page.keyboard.press('ArrowUp');
    await expect(page.locator('.pc-row')).toBeVisible();
    await page.keyboard.press('ArrowDown');
    await expect(page.locator('.pc-card[data-focused] .kicker')).toHaveText(/^CHAPTER 3/);
    await page.keyboard.press('ArrowLeft');
    await expect(page.locator('.pc-card[data-focused] .kicker')).toHaveText(/^CHAPTER 2/);
    await page.keyboard.press('Enter');
    await expect(page.locator('.pc-band')).toHaveCount(0);
    await expect.poll(() => videoTime(page), { timeout: 10_000 }).toBeLessThan(45);
    expect(await videoTime(page)).toBeGreaterThan(29);

    // controls hidden: RIGHT seeks 30 s at once and shows the minimal seek bar; LEFT twice goes back 20 s
    const hiddenFrom = await videoTime(page);
    await page.keyboard.press('ArrowRight');
    await expect(page.locator('.pc-minimal')).toBeVisible();
    await shot(page, info, 'player-dpad-seek');
    await expect.poll(() => videoTime(page), { timeout: 10_000 }).toBeGreaterThan(hiddenFrom + 20);
    await page.keyboard.press('ArrowLeft');
    await page.keyboard.press('ArrowLeft');
    await expect.poll(() => videoTime(page), { timeout: 10_000 }).toBeLessThan(hiddenFrom + 20);
    await expect(page.locator('.pc-minimal')).toHaveCount(0, { timeout: 5000 });

    // play/pause key with the controls hidden: PAUSED label at the top (and paused through the panels below, so
    // the 90 s test film does not end meanwhile)
    await page.keyboard.press(' ');
    await expect(page.locator('.pc-paused')).toBeVisible();
    await shot(page, info, 'player-paused');

    // BACK with the controls up hides them and nothing else
    await page.keyboard.press('ArrowUp');
    await expect(page.locator('.pc-band')).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(page.locator('.pc-band')).toHaveCount(0);
    await expect(page.locator('.player.pc')).toBeVisible();

    // Settings: the side panel
    await pressButton(page, 'SETTINGS');
    await expect(page.locator('.pc-sidepanel .kicker')).toHaveText('SETTINGS');
    await expect(page.locator('.pc-sidepanel .pc-prow .label')).toHaveText([
      'Audio', 'Subtitles', 'Speed', 'Video scale', 'Subtitle delay', 'Quality', 'Sleep timer', 'Watch together', 'Send to…',
    ]);
    await shot(page, info, 'player-settings');
    // Subtitles → English (embedded, drawn by the app); the panel closes
    await pickRow(page, /^Subtitles$/);
    await expect(page.locator('.pc-sidepanel .kicker')).toHaveText('SUBTITLES');
    await shot(page, info, 'player-settings-subtitles');
    await pickRow(page, /^English$/);
    await expect(page.locator('.pc-sidepanel')).toHaveCount(0);
    await page.keyboard.press(' ');
    await expect(page.locator('.player .subtitle-layer span')).toHaveText(/Subtitle/, { timeout: 30_000 });
    await page.keyboard.press(' ');
    // Subtitle delay: +1s, the readout follows; BACK to the list, BACK closes; focus back on the gear
    await pressButton(page, 'SETTINGS');
    await pickRow(page, /^Subtitle delay$/);
    await expect(page.locator('.pc-sidepanel .readout')).toHaveText('0s');
    await pickRow(page, /^\+1s$/);
    await expect(page.locator('.pc-sidepanel .readout')).toHaveText('+1s');
    await shot(page, info, 'player-subtitle-delay');
    await page.keyboard.press('Escape');
    await expect(page.locator('.pc-sidepanel .kicker')).toHaveText('SETTINGS');
    await expect(page.locator('.pc-prow[data-focused] .label')).toHaveText('Subtitle delay');
    await expect(page.locator('.pc-prow[data-focused] .value')).toHaveText('+1s');
    await page.keyboard.press('Escape');
    await expect(page.locator('.pc-sidepanel')).toHaveCount(0);
    await expect.poll(() => focusedCaption(page)).toBe('SETTINGS');
    // Speed 1.5×
    await pressButton(page, 'SETTINGS');
    await pickRow(page, /^Speed$/);
    await pickRow(page, /^1\.5×$/);
    await expect.poll(() => page.evaluate(() => document.querySelector('video')?.playbackRate)).toBe(1.5);
    // a speed goes back to the list (upstream), on the Speed row
    await expect(page.locator('.pc-prow[data-focused] .value')).toHaveText('1.5×');
    await page.keyboard.press('Escape');

    // Quality: what is playing, the ladder, the default toggle
    await pressButton(page, 'SETTINGS');
    await pickRow(page, /^Quality$/);
    await expect(page.locator('.pc-dialog .kicker')).toHaveText('QUALITY');
    await expect(page.locator('.pc-prow[data-focused] .label')).toHaveText('Original');
    await shot(page, info, 'player-quality');
    await page.keyboard.press('Escape');
    await expect(page.locator('.pc-dialog')).toHaveCount(0);

    // Sleep timer: 15 minutes → the chip; then cancel it
    await pressButton(page, 'SETTINGS');
    await pickRow(page, /^Sleep timer$/);
    await expect(page.locator('.pc-dialog .title')).toHaveText('Sleep timer');
    await expect(page.locator('.pc-prow[data-focused] .label')).toHaveText('15 minutes');
    await shot(page, info, 'player-sleep-dialog');
    await pickRow(page, /^15 minutes$/);
    await expect(page.locator('.pc-sleep')).toHaveText(/^SLEEP 1[45]:\d\d$/);
    expect(await page.locator('.pc-sleep').textContent()).not.toBe('SLEEP 15:01');
    await shot(page, info, 'player-sleep-chip');
    await pressButton(page, 'SETTINGS');
    await pickRow(page, /^Sleep timer$/);
    await pickRow(page, /^Cancel \(/);
    await expect(page.locator('.pc-sleep')).toHaveCount(0);

    // Watch together: not here yet, said plainly
    await pressButton(page, 'SETTINGS');
    await pickRow(page, /^Watch together$/);
    await expect(page.locator('.pc-dialog .title')).toHaveText('Watch together');
    await shot(page, info, 'player-watch-together');
    await page.keyboard.press('Escape');

    // Audio button: the second track; the stream restarts at the same position with it (playing again)
    await page.keyboard.press(' ');
    const at = await videoTime(page);
    await pressButton(page, 'AUDIO');
    await expect(page.locator('.pc-sidepanel .kicker')).toHaveText('AUDIO');
    await pickRow(page, /Español/);
    await expect.poll(() => transcodingUrls.some((u) => /AudioStreamIndex=3\b/.test(u)), { timeout: 20_000 }).toBe(true);
    await expect.poll(() => videoTime(page), { timeout: 30_000 }).toBeGreaterThan(at + 0.5);
    expect(await videoTime(page)).toBeLessThan(at + 25);

    // BACK: controls up → hidden; hidden → leaves to Home (never out of the app)
    await page.keyboard.press('ArrowUp');
    await expect(page.locator('.pc-band')).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(page.locator('.pc-band')).toHaveCount(0);
    await page.keyboard.press('Escape');
    await expect(page.locator('.home')).toBeVisible();
    await expect.poll(() => reports.includes('/Stopped')).toBe(true);
    expect(reports[0]).toBe('/start');
    expect(reports).toContain('/Progress');
    // the server remembers what was seen last: English subtitles (drawn by the app, reported as the choice)
    expect(await trackDefaults(me.Id, film.Id)).toEqual({ audio: 3, subtitle: 4 });
  } finally {
    await page.close();
    await restoreTracks(film.Id, tracksBefore);
    await restoreUserData(me.Id, film.Id, before);
  }
});

test('Episode: skip intro (media segment), queue row, next up with countdown, then the next episode', async ({ page }, info) => {
  test.setTimeout(180_000);
  const me = await api<{ Id: string }>('/Users/Me');
  const episodes = await api<{ Items: Item[] }>(`/Items?userId=${me.Id}&recursive=true&includeItemTypes=Episode&fields=Chapters&limit=200&sortBy=SeriesSortName,ParentIndexNumber,IndexNumber`);
  const first = episodes.Items.find((e) => e.IndexNumber === 1 && (e.RunTimeTicks ?? 0) > 0);
  test.skip(first === undefined, 'no first episode on this server');
  if (first === undefined) return;
  const series = await api<{ Items: Item[] }>(`/Shows/${first.SeriesId ?? ''}/Episodes?userId=${me.Id}&startItemId=${first.Id}&limit=3`);
  const second = series.Items[1];
  const third = series.Items[2];
  test.skip(second === undefined, 'the series has one episode');
  if (second === undefined) return;
  const touched = [first, second].concat(third !== undefined ? [third] : []);
  const saved = await Promise.all(touched.map((e) => userData(me.Id, e.Id)));
  // The dev server has no segment provider plugin: the segment list comes from the test, in the server's own
  // shape (MediaSegmentDto), for the first episode only (5 s to 20 s, an intro).
  await page.route('**/MediaSegments/**', async (route) => {
    const forFirst = route.request().url().includes(first.Id);
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        Items: forFirst ? [{ Id: 'e2e0000000000000000000000000intro', ItemId: first.Id, Type: 'Intro', StartTicks: 50_000_000, EndTicks: 200_000_000 }] : [],
        TotalRecordCount: forFirst ? 1 : 0,
        StartIndex: 0,
      }),
    });
  });
  try {
    await page.goto(APP);
    await expect(page.locator('.home')).toBeVisible();
    await debug(page, { name: 'player', itemId: first.Id, startMs: 0 });
    await expect.poll(() => videoTime(page), { timeout: 30_000 }).toBeGreaterThan(0.5);
    await page.keyboard.press('ArrowUp');
    await expect(page.locator('.pc-top .kicker')).toHaveText(/ · S\d+ E1$/);
    await shot(page, info, 'player-episode-controls');
    await page.keyboard.press('Escape'); // controls down

    // inside the intro: SKIP INTRO, focused; OK jumps past it
    await expect(page.locator('.pc-skipprompt .btn[data-focused]')).toHaveText('SKIP INTRO', { timeout: 20_000 });
    await page.waitForTimeout(400);
    await shot(page, info, 'player-skip-intro');
    await page.keyboard.press('Enter');
    await expect.poll(() => videoTime(page), { timeout: 10_000 }).toBeGreaterThan(19.5);
    await expect(page.locator('.pc-skipprompt')).toHaveCount(0);

    // the remote's media keys go through the key router: fast-forward 30 s, rewind 10 s
    const beforeKeys = await videoTime(page);
    await mediaKey(page, 'MediaFastForward');
    await expect(page.locator('.pc-minimal')).toBeVisible();
    await expect.poll(() => videoTime(page), { timeout: 10_000 }).toBeGreaterThan(beforeKeys + 25);
    await mediaKey(page, 'MediaRewind');
    await expect.poll(() => videoTime(page), { timeout: 10_000 }).toBeLessThan(beforeKeys + 25);

    // the queue: the button, and DOWN from the chapters row
    await page.keyboard.press('ArrowUp');
    await expect(page.locator('.pc-group.left .pc-btn')).toHaveCount(2);
    await page.keyboard.press('ArrowDown');
    await expect(page.locator('.pc-cards .row-header .title')).toHaveText('CHAPTERS');
    await page.keyboard.press('ArrowDown');
    await expect(page.locator('.pc-cards .row-header .title')).toHaveText('QUEUE');
    await expect(page.locator('.pc-card[data-focused] .kicker')).toHaveText('NEXT');
    await expect(page.locator('.pc-card[data-focused] .title')).toHaveText(second.Name);
    await expect.poll(() => page.evaluate(() => [...document.querySelectorAll('.pc-card img')].every((i) => (i as HTMLImageElement).complete))).toBe(true);
    await shot(page, info, 'player-queue');
    await page.keyboard.press('Escape');

    // the end: next up, the picture shrinks, the countdown runs; BACK stops the countdown (the card stays)
    await page.evaluate(() => {
      const v = document.querySelector('video');
      if (v !== null) v.currentTime = Math.max(0, v.duration - 2);
    });
    await expect(page.locator('.pc-nextup')).toBeVisible({ timeout: 20_000 });
    await expect(page.locator('.pc-nextup .line')).toHaveText(new RegExp(second.Name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '$'));
    await expect(page.locator('.pc-nextup .btn[data-focused]')).toHaveText('PLAY NOW');
    await page.waitForTimeout(1500);
    await shot(page, info, 'player-next-up');
    await page.keyboard.press('Escape');
    await expect(page.locator('.pc-nextup .countdown .fill')).toHaveCount(0);
    await expect(page.locator('.pc-nextup')).toBeVisible();
    // PLAY NOW: the next episode in the same player
    await page.keyboard.press('Enter');
    await expect(page.locator('.pc-nextup')).toHaveCount(0);
    await page.keyboard.press('ArrowUp');
    await expect(page.locator('.pc-top .title')).toHaveText(second.Name, { timeout: 20_000 });
    // the new stream from its start (not the old one's last position)
    await expect
      .poll(() => page.evaluate(() => {
        const v = document.querySelector('video');
        return v !== null && isFinite(v.duration) && v.currentTime > 0.5 && v.currentTime < 20;
      }), { timeout: 30_000 })
      .toBe(true);
    await page.keyboard.press('Escape');

    // left alone at the end of the second episode, the countdown runs out and the third one starts by itself
    if (third !== undefined) {
      await page.evaluate(() => {
        const v = document.querySelector('video');
        if (v !== null) v.currentTime = Math.max(0, v.duration - 2);
      });
      await expect(page.locator('.pc-nextup .countdown .fill')).toBeVisible({ timeout: 20_000 });
      await expect(page.locator('.pc-nextup')).toHaveCount(0, { timeout: 25_000 });
      await page.keyboard.press('ArrowUp');
      await expect(page.locator('.pc-top .title')).toHaveText(third.Name, { timeout: 20_000 });
      await page.keyboard.press('Escape');
    }
    await page.keyboard.press('Escape');
    await expect(page.locator('.home')).toBeVisible();
  } finally {
    await page.close();
    for (let i = 0; i < touched.length; i++) await restoreUserData(me.Id, (touched[i] as Item).Id, saved[i] as UserData);
  }
});

test('Film end: the post-play page; resume from the saved position when no start is given', async ({ page }, info) => {
  test.setTimeout(120_000);
  const me = await api<{ Id: string }>('/Users/Me');
  const films = await api<{ Items: Item[] }>(`/Items?userId=${me.Id}&recursive=true&includeItemTypes=Movie&limit=200&sortBy=SortName`);
  const film = films.Items.find((f) => (f.RunTimeTicks ?? 0) > 600_000_000);
  test.skip(film === undefined, 'no film on this server');
  if (film === undefined) return;
  const before = await userData(me.Id, film.Id);
  try {
    // a saved resume point of 40 s: opening the player without a start position resumes there
    await writeUserData(me.Id, film.Id, { ...before, PlaybackPositionTicks: 400_000_000, Played: false });
    await page.goto(APP);
    await expect(page.locator('.home')).toBeVisible();
    await debug(page, { name: 'player', itemId: film.Id });
    await expect.poll(() => videoTime(page), { timeout: 30_000 }).toBeGreaterThan(39);
    expect(await videoTime(page)).toBeLessThan(55);

    await page.evaluate(() => {
      const v = document.querySelector('video');
      if (v !== null) v.currentTime = Math.max(0, v.duration - 2);
    });
    await expect(page.locator('.postplay')).toBeVisible({ timeout: 20_000 });
    await expect(page.locator('.postplay-column .kicker')).toHaveText('YOU JUST WATCHED');
    await page.waitForTimeout(1500);
    const posters = await page.locator('.postplay-poster').count();
    if (posters > 0) await expect(page.locator('.postplay-poster').first()).toHaveAttribute('data-focused', 'true');
    else await expect(page.locator('.postplay .btn.primary')).toHaveAttribute('data-focused', 'true');
    await shot(page, info, 'postplay');
    if (posters > 0) {
      await page.keyboard.press('ArrowUp');
      await expect(page.locator('.postplay .btn.primary')).toHaveAttribute('data-focused', 'true');
      await shot(page, info, 'postplay-buttons');
    }
    // BACK = Done: where the film was started (Home here)
    await page.keyboard.press('Escape');
    await expect(page.locator('.home')).toBeVisible();
  } finally {
    await page.close();
    await restoreUserData(me.Id, film.Id, before);
  }
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
