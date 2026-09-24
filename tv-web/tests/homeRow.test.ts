import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { decodeBoard } from '../src/api/tallyModels';
import { MAX_GAMES, selectHomeGames } from '../src/sports/homeRow';

/** Real payloads: the Android app's captured sample, and one captured from the dev server for these tests. */
const android = decodeBoard(JSON.parse(readFileSync(new URL('../../app/src/test/resources/tally/board-sample.json', import.meta.url), 'utf8')));
const dev = decodeBoard(JSON.parse(readFileSync(new URL('./fixtures/board-dev.json', import.meta.url), 'utf8')));

describe('home games row (Android HomeRowSelection)', () => {
  it('puts live games first, watchable before dark, then games starting soon', () => {
    const now = Date.parse(android.serverTime);
    const games = selectHomeGames(android, new Set(), new Set(), now);
    expect(games.map((g) => [g.league, g.state, g.watch !== null])).toEqual([
      ['NFL', 'in', true],
      ['MLB', 'in', false],
    ]);
  });

  it('includes an upcoming game within 12 hours', () => {
    const upcoming = android.games.find((g) => g.state === 'pre');
    if (upcoming === undefined) throw new Error('fixture changed');
    const now = Date.parse(upcoming.start) - 3 * 3600_000;
    expect(selectHomeGames(android, new Set(), new Set(), now).map((g) => g.id)).toContain(upcoming.id);
  });

  it('pins followed teams first within the live group', () => {
    const now = Date.parse(android.serverTime);
    const mlb = android.games.find((g) => g.league === 'MLB');
    if (mlb === undefined) throw new Error('fixture changed');
    const key = `MLB:${mlb.home.abbr.toUpperCase()}`;
    expect(selectHomeGames(android, new Set(), new Set([key]), now)[0]?.id).toBe(mlb.id);
  });

  it('never shows more than ten cards, and decodes the dev server board', () => {
    expect(dev.games.length).toBeGreaterThan(0);
    const now = Date.parse(dev.serverTime);
    expect(selectHomeGames(dev, new Set(), new Set(), now).length).toBeLessThanOrEqual(MAX_GAMES);
    for (const g of dev.games) {
      expect(typeof g.home.abbr).toBe('string');
      expect(['pre', 'in', 'post']).toContain(g.state);
    }
  });
});
