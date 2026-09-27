import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { decodeFind, type FindResult } from '../src/api/tally';
import { canWatch, decodeBoard, decodeGame, hasStream, noStreamLabel, type TallyBoard, type TallyGame } from '../src/api/tallyModels';
import { boardRows } from '../src/sports/boardOrganizer';
import {
  cancelStreamSearch,
  keepLooking,
  MIN_SEARCHING_MS,
  POLL_MS,
  ROUND_MS,
  setSearchDeps,
  streamSearch,
  watchOrSearch,
} from '../src/pages/sports/streamSearch';

const WATCH = { channelId: 'ch1', channelName: 'Rays Phillies', liveTvItemId: null, hlsPath: '/JellyTV/Live/ch1.m3u8?s=x', cardPath: '/c', confidence: 'teams', language: 'en' };

function game(o: { id?: string; state?: string; watch?: unknown; search?: unknown } = {}): TallyGame {
  return decodeGame({
    id: o.id ?? 'g1',
    league: 'MLB',
    state: o.state ?? 'in',
    start: '2026-09-26T23:00:00Z',
    away: { abbr: 'TB', shortName: 'Rays' },
    home: { abbr: 'PHI', shortName: 'Phillies' },
    watch: o.watch,
    search: o.search,
  });
}

/** A fake server: `find` answers from a script (the last answer repeats), the board is whatever the test sets. */
function fakeServer(answers: Array<FindResult | 'throw'>) {
  let boardNow: TallyBoard | null = decodeBoard({ games: [{ id: 'g1', state: 'in' }] });
  const listeners = new Set<() => void>();
  const calls = { find: 0, refresh: 0 };
  setSearchDeps({
    find: () => {
      const a = answers[Math.min(calls.find, answers.length - 1)] ?? 'throw';
      calls.find++;
      return a === 'throw' ? Promise.reject(new Error('offline')) : Promise.resolve(a);
    },
    refreshBoard: () => {
      calls.refresh++;
      return Promise.resolve();
    },
    board: () => boardNow,
    subscribeBoard: (l) => {
      listeners.add(l);
      return () => listeners.delete(l);
    },
  });
  return {
    calls,
    setBoard(games: unknown[]) {
      boardNow = decodeBoard({ games });
      listeners.forEach((l) => l());
    },
    listeners,
  };
}

const searching: FindResult = { state: 'searching', watch: null };
const none: FindResult = { state: 'none', watch: null };
const unsupported: FindResult = { state: 'unsupported', watch: null };

/** Lets the fake answers (promises) settle between timer steps. */
async function flush(): Promise<void> {
  for (let i = 0; i < 5; i++) await Promise.resolve();
}

async function advance(ms: number): Promise<void> {
  await vi.advanceTimersByTimeAsync(Math.max(1, ms));
  await flush();
}

describe('the board contract of 2.2.1', () => {
  it('decodes the search field (absent on older plugins and for games with a stream)', () => {
    expect(game().search).toBeNull();
    const g = game({ search: { state: 'searching', lastAt: null, nextAt: '2026-09-27T00:05:00Z' } });
    expect(g.search).toEqual({ state: 'searching', lastAt: null, nextAt: '2026-09-27T00:05:00Z' });
  });

  it('labels a game without a stream, never "not on your channels"', () => {
    expect(noStreamLabel(game({ search: { state: 'searching' } }))).toBe('Looking for a stream');
    expect(noStreamLabel(game({ search: { state: 'waiting', nextAt: '2026-09-27T00:05:00Z' } }))).toBe('No stream yet');
    expect(noStreamLabel(game())).toBe('No stream yet');
    expect(noStreamLabel(game({ state: 'pre' }))).toBe('No stream yet');
    expect(noStreamLabel(game({ state: 'post' }))).toBe('No stream');
  });

  it('offers WATCH for every game that is not final, and a finished one still on a channel', () => {
    expect(canWatch(game())).toBe(true);
    expect(canWatch(game({ state: 'pre' }))).toBe(true);
    expect(canWatch(game({ state: 'post' }))).toBe(false);
    expect(canWatch(game({ state: 'post', watch: WATCH }))).toBe(true);
    // a watch whose Jellyfin item is not registered yet plays through its playlist: not a reason to search
    expect(hasStream(game({ watch: { ...WATCH, liveTvItemId: null } }))).toBe(true);
    expect(hasStream(game({ watch: { ...WATCH, hlsPath: '' } }))).toBe(false);
  });

  it('"Only games with a stream" leaves out the games without one (off: every game)', () => {
    const games = [game({ id: 'a', watch: WATCH }), game({ id: 'b' })];
    expect(boardRows(games, new Set(), false)[0]?.games.map((g) => g.id)).toEqual(['a', 'b']);
    expect(boardRows(games, new Set(), true)[0]?.games.map((g) => g.id)).toEqual(['a']);
  });

  it('decodes find answers leniently', () => {
    expect(decodeFind({ state: 'found', watch: WATCH })).toEqual({ state: 'found', watch: { ...WATCH } });
    expect(decodeFind({ state: 'searching', watch: null })).toEqual(searching);
    expect(decodeFind({ state: 'none' })).toEqual(none);
    // a stream is a stream whatever the state says; "found" without one keeps looking
    expect(decodeFind({ state: 'searching', watch: WATCH }).state).toBe('found');
    expect(decodeFind({ state: 'found', watch: null })).toEqual(searching);
    expect(decodeFind({ state: 'found', watch: { ...WATCH, hlsPath: '' } })).toEqual(searching);
    expect(decodeFind('nonsense')).toEqual(searching);
  });
});

describe('Looking for a stream (watchOrSearch)', () => {
  beforeEach(() => {
    (globalThis as Record<string, unknown>).window = globalThis;
    vi.useFakeTimers();
  });
  afterEach(() => {
    cancelStreamSearch();
    setSearchDeps(null);
    vi.useRealTimers();
    delete (globalThis as Record<string, unknown>).window;
  });

  it('plays at once when the game has a stream (no dialog, no find)', () => {
    const server = fakeServer([searching]);
    const played: TallyGame[] = [];
    watchOrSearch(game({ watch: WATCH }), (g) => played.push(g));
    expect(played).toHaveLength(1);
    expect(streamSearch.get()).toBeNull();
    expect(server.calls.find).toBe(0);
  });

  it('searches, polls every 3 s, and plays the stream the server finds', async () => {
    const server = fakeServer([searching, searching, { state: 'found', watch: { ...WATCH } }]);
    const played: TallyGame[] = [];
    watchOrSearch(game(), (g) => played.push(g));
    await flush();
    expect(streamSearch.get()?.phase).toBe('searching');
    expect(server.calls.find).toBe(1);
    await advance(POLL_MS);
    expect(server.calls.find).toBe(2);
    await advance(POLL_MS);
    expect(server.calls.find).toBe(3);
    expect(streamSearch.get()).toBeNull(); // the dialog goes first…
    await advance(0);
    expect(played.map((g) => g.watch?.channelId)).toEqual(['ch1']); // …then it plays
    await advance(POLL_MS * 3);
    expect(server.calls.find).toBe(3); // and polling stopped
  });

  it('plays as soon as the stream reaches the board, whatever find says', async () => {
    const server = fakeServer([searching]);
    const played: TallyGame[] = [];
    watchOrSearch(game(), (g) => played.push(g));
    await flush();
    server.setBoard([{ id: 'g1', state: 'in', watch: WATCH }]);
    await advance(0);
    expect(played).toHaveLength(1);
    expect(streamSearch.get()).toBeNull();
    expect(server.listeners.size).toBe(0);
  });

  it('after 45 s without a stream says so; Keep looking gives another 45 s; OK closes', async () => {
    const server = fakeServer([searching]);
    const played: TallyGame[] = [];
    watchOrSearch(game(), (g) => played.push(g));
    await advance(ROUND_MS - 1000);
    expect(streamSearch.get()?.phase).toBe('searching');
    await advance(1000);
    expect(streamSearch.get()?.phase).toBe('none');
    const calls = server.calls.find;
    await advance(POLL_MS * 2);
    expect(server.calls.find).toBe(calls); // no polling behind the message
    keepLooking();
    await flush();
    expect(streamSearch.get()?.phase).toBe('searching');
    expect(server.calls.find).toBe(calls + 1);
    await advance(ROUND_MS);
    expect(streamSearch.get()?.phase).toBe('none');
    cancelStreamSearch();
    expect(streamSearch.get()).toBeNull();
    expect(played).toHaveLength(0);
  });

  it("the server's none ends the first round (not before the searching state has shown), not a Keep looking round", async () => {
    const server = fakeServer([none]);
    watchOrSearch(game(), () => undefined);
    await flush();
    expect(streamSearch.get()?.phase).toBe('searching');
    await advance(MIN_SEARCHING_MS);
    expect(streamSearch.get()?.phase).toBe('none');
    keepLooking();
    await advance(MIN_SEARCHING_MS + POLL_MS * 2);
    expect(streamSearch.get()?.phase).toBe('searching'); // none again: keeps looking the whole round
    expect(server.calls.find).toBe(4);
    await advance(ROUND_MS);
    expect(streamSearch.get()?.phase).toBe('none');
  });

  it('a server without find (404) refreshes the board once, then says no stream; Keep looking polls the board', async () => {
    const server = fakeServer([unsupported]);
    const played: TallyGame[] = [];
    watchOrSearch(game(), (g) => played.push(g));
    await flush();
    expect(server.calls.refresh).toBe(1);
    await advance(MIN_SEARCHING_MS);
    expect(streamSearch.get()?.phase).toBe('none');
    keepLooking();
    await advance(POLL_MS);
    expect(server.calls.find).toBe(1); // never asked again
    expect(server.calls.refresh).toBe(3); // the round's start and one poll
    server.setBoard([{ id: 'g1', state: 'in', watch: WATCH }]);
    await advance(0);
    expect(played).toHaveLength(1);
  });

  it('a 404 whose board refresh brings the stream plays it', async () => {
    const server = fakeServer([unsupported]);
    const played: TallyGame[] = [];
    // the refresh brings the stream to the board
    setSearchDeps({
      find: () => Promise.resolve(unsupported),
      refreshBoard: () => {
        server.setBoard([{ id: 'g1', state: 'in', watch: WATCH }]);
        return Promise.resolve();
      },
      board: () => decodeBoard({ games: [{ id: 'g1', state: 'in', watch: WATCH }] }),
      subscribeBoard: () => () => undefined,
    });
    watchOrSearch(game(), (g) => played.push(g));
    await advance(0);
    expect(played).toHaveLength(1);
  });

  it('keeps asking through a server that does not answer, until the round ends', async () => {
    const server = fakeServer(['throw']);
    watchOrSearch(game(), () => undefined);
    await advance(POLL_MS * 3);
    expect(server.calls.find).toBe(4);
    expect(streamSearch.get()?.phase).toBe('searching');
    await advance(ROUND_MS);
    expect(streamSearch.get()?.phase).toBe('none');
  });

  it('Cancel stops everything: nothing plays when the stream arrives later', async () => {
    const server = fakeServer([searching, { state: 'found', watch: WATCH }]);
    const played: TallyGame[] = [];
    watchOrSearch(game(), (g) => played.push(g));
    await flush();
    cancelStreamSearch();
    await advance(POLL_MS * 2);
    server.setBoard([{ id: 'g1', state: 'in', watch: WATCH }]);
    await advance(0);
    expect(played).toHaveLength(0);
    expect(server.calls.find).toBe(1);
  });

  it('a new search replaces the one on screen', async () => {
    fakeServer([searching]);
    const played: string[] = [];
    watchOrSearch(game({ id: 'g1' }), () => played.push('g1'));
    await flush();
    const first = streamSearch.get()?.id;
    watchOrSearch(game({ id: 'g2' }), () => played.push('g2'));
    await flush();
    expect(streamSearch.get()?.game.id).toBe('g2');
    expect(streamSearch.get()?.id).not.toBe(first);
  });
});
