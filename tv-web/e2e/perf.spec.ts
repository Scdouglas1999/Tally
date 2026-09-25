/**
 * Performance on a TV-class budget (tvweb-tizen), in desktop Chromium at 1920x1080 with the CPU slowed down 4x
 * (Chrome DevTools' CPU throttling: roughly a 2020 TV SoC for script and layout). Runs only with TALLY_PERF=1:
 *   TALLY_PERF=1 npx playwright test e2e/perf.spec.ts
 * TALLY_PERF_CYCLES sets the length of the long-session run (default 6 cycles of browsing + 20 s of playback).
 * Results: test-results/perf/*.json and the console. What it measures:
 *  - startup: page load → Home drawn with its first focus, and → every picture on screen loaded;
 *  - remote-key latency (Event Timing: keydown → next paint) and long frames while moving through Home, a library
 *    grid and the Sports board at a remote's pace (a press every 150 ms, then held: 50 ms repeats);
 *  - pictures: every <img> and background picture on each screen, drawn size vs. the size the server was asked for;
 *  - a long session: JS heap, DOM nodes and event listeners after each cycle, after a forced garbage collection.
 */
import { expect, test, type CDPSession, type Page } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';
import { APP, AUTH_STATE, api } from './env';

test.use({ storageState: AUTH_STATE });
test.skip(process.env.TALLY_PERF !== '1', 'performance runs only with TALLY_PERF=1');

const THROTTLE = 4;
const OUT = 'test-results/perf';

interface View {
  Id: string;
  Name: string;
  CollectionType?: string;
}

const debug = (page: Page, route: unknown) =>
  page.evaluate((r) => (window as unknown as { TallyDebug: { push: (r: unknown) => void } }).TallyDebug.push(r), route);

async function throttle(page: Page): Promise<CDPSession> {
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Emulation.setCPUThrottlingRate', { rate: THROTTLE });
  return cdp;
}

/** Starts collecting key → paint latencies and long frames in the page. */
async function record(page: Page): Promise<void> {
  await page.evaluate(() => {
    const w = window as unknown as { __perf: { keys: number[]; frames: number[]; long: number } };
    w.__perf = { keys: [], frames: [], long: 0 };
    new PerformanceObserver((list) => {
      for (const e of list.getEntries()) if (e.name === 'keydown') w.__perf.keys.push(e.duration);
    }).observe({ type: 'event', buffered: false, durationThreshold: 16 } as PerformanceObserverInit);
    let last = performance.now();
    const tick = (t: number) => {
      w.__perf.frames.push(t - last);
      if (t - last > 50) w.__perf.long++;
      last = t;
      requestAnimationFrame(tick);
    };
    requestAnimationFrame(tick);
  });
}

interface Summary {
  keys: number;
  keyP50: number;
  keyP95: number;
  keyMax: number;
  frames: number;
  frameP95: number;
  frameMax: number;
  longFrames: number;
}

const pct = (xs: number[], p: number): number => {
  if (xs.length === 0) return 0;
  const s = xs.slice().sort((a, b) => a - b);
  return Math.round(s[Math.min(s.length - 1, Math.floor((p / 100) * s.length))] ?? 0);
};

async function summary(page: Page): Promise<Summary> {
  const p = await page.evaluate(() => (window as unknown as { __perf: { keys: number[]; frames: number[]; long: number } }).__perf);
  // Event Timing reports only events over 16 ms; the others count as a frame (16)
  return {
    keys: p.keys.length,
    keyP50: pct(p.keys, 50),
    keyP95: pct(p.keys, 95),
    keyMax: Math.round(Math.max(0, ...p.keys)),
    frames: p.frames.length,
    frameP95: pct(p.frames, 95),
    frameMax: Math.round(Math.max(0, ...p.frames)),
    longFrames: p.long,
  };
}

/** Presses `key` `times` at a remote's pace, then holds it (auto-repeat every 50 ms) for `held` more. */
async function drive(page: Page, key: string, times: number, held = 0): Promise<void> {
  for (let i = 0; i < times; i++) {
    await page.keyboard.press(key);
    await page.waitForTimeout(150);
  }
  if (held > 0) {
    for (let i = 0; i < held; i++) {
      await page.keyboard.down(key);
      await page.waitForTimeout(50);
    }
    await page.keyboard.up(key);
  }
  await page.waitForTimeout(400);
}

interface Picture {
  src: string;
  drawnW: number;
  drawnH: number;
  askedW: number | null;
  askedH: number | null;
  naturalW: number;
  naturalH: number;
}

/** Every picture on the visible page: drawn size vs. asked size (fillWidth/maxWidth…) and the size that came back. */
async function pictures(page: Page): Promise<Picture[]> {
  return page.evaluate(() => {
    const out: Array<{ src: string; drawnW: number; drawnH: number; askedW: number | null; askedH: number | null; naturalW: number; naturalH: number }> = [];
    const param = (u: URL, names: string[]): number | null => {
      for (const n of names) {
        for (const [k, v] of u.searchParams) if (k.toLowerCase() === n.toLowerCase()) return Number(v);
      }
      return null;
    };
    for (const img of Array.from(document.querySelectorAll('img'))) {
      const r = img.getBoundingClientRect();
      if (r.width === 0 || img.closest('.hidden') !== null || img.src === '') continue;
      const u = new URL(img.src, location.href);
      out.push({
        src: u.pathname + (u.search.length > 80 ? u.search.substring(0, 80) + '…' : u.search),
        drawnW: Math.round(r.width),
        drawnH: Math.round(r.height),
        askedW: param(u, ['fillWidth', 'maxWidth', 'width']),
        askedH: param(u, ['fillHeight', 'maxHeight', 'height']),
        naturalW: img.naturalWidth,
        naturalH: img.naturalHeight,
      });
    }
    return out;
  });
}

/** Pictures that came back more than 1.5x (by area) the drawn size, or without a size in the request. */
const oversized = (ps: Picture[]) =>
  ps.filter((p) => p.naturalW * p.naturalH > 1.5 * 1.5 * p.drawnW * p.drawnH || (p.askedW === null && p.src.indexOf('/Images/') >= 0));

async function settle(page: Page): Promise<void> {
  await page.waitForFunction(() => Array.from(document.querySelectorAll('img')).every((i) => i.complete), undefined, { timeout: 30_000 }).catch(() => undefined);
}

function save(name: string, data: unknown): void {
  mkdirSync(OUT, { recursive: true });
  writeFileSync(`${OUT}/${name}.json`, JSON.stringify(data, null, 2));
  console.log(`\n[perf] ${name}\n` + JSON.stringify(data, null, 2).substring(0, 4000));
}

test('startup: launch to Home, 4x CPU', async ({ page }) => {
  await throttle(page);
  const runs: Array<{ homeFocusedMs: number; picturesMs: number; scriptMs: number }> = [];
  for (let i = 0; i < 3; i++) {
    // a cold start each time: nothing cached (a TV's shell fetches the bundle once per plugin version, then it is cached)
    const cdp = await page.context().newCDPSession(page);
    await cdp.send('Network.enable');
    await cdp.send('Network.setCacheDisabled', { cacheDisabled: i === 0 });
    await page.goto(APP);
    await expect(page.locator('.home [data-focused]')).toHaveCount(1, { timeout: 60_000 });
    const homeFocusedMs = await page.evaluate(() => Math.round(performance.now()));
    await settle(page);
    const picturesMs = await page.evaluate(() => Math.round(performance.now()));
    const scriptMs = await page.evaluate(() => {
      const js = performance.getEntriesByType('resource').find((e) => /app\.[^/]*\.js/.test(e.name)) as PerformanceResourceTiming | undefined;
      return js !== undefined ? Math.round(js.responseEnd) : -1;
    });
    runs.push({ homeFocusedMs, picturesMs, scriptMs });
  }
  save('startup', { throttle: THROTTLE, runs });
});

test('Home, library grid, Sports board: key latency, frames and pictures, 4x CPU', async ({ page }) => {
  test.setTimeout(240_000);
  await page.goto(APP);
  await expect(page.locator('.home [data-focused]')).toHaveCount(1, { timeout: 60_000 });
  await settle(page);
  await throttle(page);
  const result: Record<string, unknown> = {};

  // Home: along the first library row, then down through every row and back up
  const homePictures = await pictures(page);
  await record(page);
  await page.keyboard.press('ArrowDown');
  await drive(page, 'ArrowRight', 8, 10);
  await drive(page, 'ArrowDown', 6);
  await drive(page, 'ArrowUp', 6);
  result.home = { ...(await summary(page)), pictures: homePictures.length, oversized: oversized(homePictures) };

  // a library grid
  const views = await api<{ Items: View[] }>('/UserViews');
  const movies = views.Items.find((v) => v.CollectionType === 'movies');
  if (movies !== undefined) {
    await debug(page, { name: 'library', libraryId: movies.Id, title: movies.Name, collectionType: 'movies' });
    await expect(page.locator('.page:not(.hidden) .lib [data-focused]')).toHaveCount(1, { timeout: 60_000 });
    // the grid tab
    await page.keyboard.press('ArrowUp');
    await page.keyboard.press('ArrowRight');
    await page.keyboard.press('Enter');
    await page.waitForTimeout(1500);
    await page.keyboard.press('ArrowDown');
    await settle(page);
    const grid = await pictures(page);
    await record(page);
    await drive(page, 'ArrowRight', 4, 6);
    await drive(page, 'ArrowDown', 4, 8);
    await drive(page, 'ArrowUp', 4);
    result.library = { ...(await summary(page)), pictures: grid.length, oversized: oversized(grid) };
  }

  // a film page
  const film = (await api<{ Items: View[] }>('/Items?Recursive=true&IncludeItemTypes=Movie&Limit=1&SortBy=SortName')).Items[0];
  if (film !== undefined) {
    await debug(page, { name: 'item', itemId: film.Id });
    await expect(page.locator('.page:not(.hidden) [data-focused]')).toHaveCount(1, { timeout: 60_000 });
    await settle(page);
    const details = await pictures(page);
    await record(page);
    await drive(page, 'ArrowDown', 4);
    await drive(page, 'ArrowRight', 5);
    result.film = { ...(await summary(page)), pictures: details.length, oversized: oversized(details) };
  }

  // the Sports board
  await debug(page, { name: 'sports' });
  await expect(page.locator('.page:not(.hidden) .sports-page')).toBeVisible();
  await page.waitForTimeout(1500);
  await settle(page);
  const board = await pictures(page);
  await record(page);
  await drive(page, 'ArrowRight', 5, 6);
  await drive(page, 'ArrowDown', 4);
  await drive(page, 'ArrowUp', 4);
  result.sports = { ...(await summary(page)), pictures: board.length, oversized: oversized(board) };
  save('navigation', { throttle: THROTTLE, ...result });
});

test('long session: browsing and playback cycles, no growth', async ({ page }) => {
  const cycles = Number(process.env.TALLY_PERF_CYCLES ?? '6');
  test.setTimeout(120_000 + cycles * 90_000);
  // the dev server's resume points stay as they are
  await page.route('**/Sessions/Playing**', (route) => route.fulfill({ status: 204 }));
  await page.goto(APP);
  await expect(page.locator('.home [data-focused]')).toHaveCount(1, { timeout: 60_000 });
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Performance.enable');
  const views = await api<{ Items: View[] }>('/UserViews');
  const shows = views.Items.find((v) => v.CollectionType === 'tvshows');
  const films = (await api<{ Items: View[] }>('/Items?Recursive=true&IncludeItemTypes=Movie&Limit=12&SortBy=SortName')).Items;
  const samples: Array<{ cycle: number; heapMB: number; nodes: number; listeners: number; documents: number; minutes: number }> = [];
  const t0 = Date.now();
  const measure = async (cycle: number) => {
    await cdp.send('HeapProfiler.collectGarbage');
    await page.waitForTimeout(500);
    await cdp.send('HeapProfiler.collectGarbage');
    const m = (await cdp.send('Performance.getMetrics')).metrics;
    const get = (n: string) => m.find((x) => x.name === n)?.value ?? 0;
    samples.push({
      cycle,
      heapMB: Math.round(get('JSHeapUsedSize') / 1e5) / 10,
      nodes: get('Nodes'),
      listeners: get('JSEventListeners'),
      documents: get('Documents'),
      minutes: Math.round((Date.now() - t0) / 6000) / 10,
    });
  };
  await measure(0);
  for (let c = 1; c <= cycles; c++) {
    // Home: along a row and down
    await drive(page, 'ArrowDown', 1);
    await drive(page, 'ArrowRight', 6);
    await drive(page, 'ArrowDown', 3);
    // a library and back
    if (shows !== undefined) {
      await debug(page, { name: 'library', libraryId: shows.Id, title: shows.Name, collectionType: 'tvshows' });
      await page.waitForTimeout(2500);
      await drive(page, 'ArrowDown', 3);
      await drive(page, 'ArrowRight', 3);
      await page.keyboard.press('Escape');
      await page.waitForTimeout(800);
    }
    // a film page, its playback for 20 s, back twice
    const film = films[c % films.length];
    if (film !== undefined) {
      await debug(page, { name: 'item', itemId: film.Id });
      await page.waitForTimeout(2500);
      await debug(page, { name: 'player', itemId: film.Id, startMs: 0 });
      await page.waitForTimeout(20_000);
      await page.keyboard.press('Escape');
      await page.waitForTimeout(800);
      await page.keyboard.press('Escape');
      await page.waitForTimeout(800);
    }
    // the Sports board and back
    await debug(page, { name: 'sports' });
    await page.waitForTimeout(3000);
    await drive(page, 'ArrowRight', 3);
    await page.keyboard.press('Escape');
    await page.waitForTimeout(1000);
    await expect(page.locator('.page:not(.hidden) .home')).toBeVisible();
    await measure(c);
  }
  save('long-session', { cycles, samples });
  // after the first cycle warmed the caches, a cycle must not keep adding: nodes within 10%, heap within 25%
  const first = samples[1];
  const last = samples[samples.length - 1];
  if (first !== undefined && last !== undefined && cycles >= 3) {
    expect(last.nodes).toBeLessThan(first.nodes * 1.1 + 200);
    expect(last.heapMB).toBeLessThan(first.heapMB * 1.25 + 2);
  }
});
