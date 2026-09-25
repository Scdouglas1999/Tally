import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { decodeBoard, decodeGame, type TallyGame } from '../src/api/tallyModels';
import { boardRows, gameForChannel, POSTPONED, rowState } from '../src/sports/boardOrganizer';
import { fittedColumnWidth, COMPACT, FULL, isLeading, periodColumnCount, periodLabels, periodValue } from '../src/sports/lineScore';
import { easeOut, rollIncomingOffset, rollOutgoingOffset, rollRestart, scoreWentUp } from '../src/sports/scoreRoll';
import { startsIn, startsInText } from '../src/sports/startsIn';
import { markFont, sizedLogo } from '../src/sports/teamMark';

const periods = decodeBoard(JSON.parse(readFileSync(new URL('../../app/src/test/resources/tally/board-sample-periods.json', import.meta.url), 'utf8')));
const dev = decodeBoard(JSON.parse(readFileSync(new URL('./fixtures/board-dev.json', import.meta.url), 'utf8')));

function game(id: string, o: { league?: string; state?: string; start?: string; channelId?: string; detail?: string } = {}): TallyGame {
  return decodeGame({
    id,
    league: o.league ?? 'NFL',
    state: o.state ?? 'in',
    start: o.start ?? '2026-09-21T00:00:00+00:00',
    detail: o.detail ?? '',
    watch: o.channelId !== undefined ? { channelId: o.channelId } : undefined,
  });
}

const rows = (games: TallyGame[], favorites: string[] = [], onlyWatchable = false) => boardRows(games, new Set(favorites), onlyWatchable);

describe('games board rows (Android BoardOrganizer, same cases as BoardOrganizerTest)', () => {
  it('orders live, then upcoming, then final', () => {
    const r = rows([game('post', { state: 'post' }), game('pre', { state: 'pre' }), game('in', { state: 'in' })]);
    expect(r.map((x) => x.state)).toEqual(['in', 'pre', 'post']);
    expect(r.map((x) => x.key)).toEqual(['NFL|in', 'NFL|pre', 'NFL|post']);
  });

  it('gives postponed and canceled games their own row after the finals', () => {
    const r = rows([
      game('ppd', { league: 'MLB', state: 'post', detail: 'Postponed' }),
      game('final', { league: 'MLB', state: 'post', detail: 'Final' }),
      game('canceled', { league: 'MLB', state: 'post', detail: 'Canceled' }),
      game('in', { league: 'NFL', state: 'in' }),
      game('extra', { league: 'MLB', state: 'post', detail: 'Final/10' }),
    ]);
    expect(r.map((x) => x.key)).toEqual(['NFL|in', 'MLB|post', 'MLB|postponed']);
    expect(new Set(r[1]?.games.map((g) => g.id))).toEqual(new Set(['final', 'extra']));
    expect(new Set(r[2]?.games.map((g) => g.id))).toEqual(new Set(['ppd', 'canceled']));
    expect(rowState(game('pre', { state: 'pre', detail: 'Postponed' }))).toBe('pre');
    expect(rowState(game('c', { state: 'post', detail: 'Cancelled' }))).toBe(POSTPONED);
  });

  it('sorts leagues alphabetically, favorite rows first within a state, favorites first in a row', () => {
    expect(rows([game('1', { league: 'NFL' }), game('2', { league: 'MLB' }), game('3', { league: 'NBA' })]).map((x) => x.league)).toEqual(['MLB', 'NBA', 'NFL']);
    expect(rows([game('nfl', { league: 'NFL', channelId: 'fav' }), game('mlb', { league: 'MLB', channelId: 'other' })], ['fav']).map((x) => x.league)).toEqual(['NFL', 'MLB']);
    expect(rows([game('nfl-post', { league: 'NFL', state: 'post', channelId: 'fav' }), game('mlb-in', { league: 'MLB', state: 'in', channelId: 'o' })], ['fav']).map((x) => x.key)).toEqual(['MLB|in', 'NFL|post']);
    expect(rows([game('a', { channelId: 'x' }), game('b', { channelId: 'fav' }), game('c', { channelId: 'y' })], ['fav'])[0]?.games[0]?.id).toBe('b');
  });

  it('orders by start time (offsets, bad strings), finals newest first', () => {
    const one = rows([game('late', { start: '2026-09-21T03:00:00+00:00' }), game('early', { start: '2026-09-21T01:00:00+00:00' }), game('mid', { start: '2026-09-21T02:00:00+00:00' })]);
    expect(one[0]?.games.map((g) => g.id)).toEqual(['early', 'mid', 'late']);
    const odd = rows([game('z', { start: 'not-a-date' }), game('b', { start: '2026-09-21T02:30:00+00:00' }), game('a', { start: '2026-09-21T01:30:00+02:00' })]);
    expect(odd[0]?.games[0]?.id).toBe('a');
    const post = rows([game('early', { state: 'post', start: '2026-09-21T01:00:00+00:00' }), game('late', { state: 'post', start: '2026-09-21T03:00:00+00:00' })]);
    expect(post[0]?.games.map((g) => g.id)).toEqual(['late', 'early']);
  });

  it('drops dark games with "My channels only", never returns empty rows, finds the live game on a channel', () => {
    expect(rows([game('watchable', { channelId: 'x' }), game('dark')], [], true)[0]?.games.map((g) => g.id)).toEqual(['watchable']);
    expect(rows([game('dark')], [], true)).toEqual([]);
    const g = [game('pre', { state: 'pre', channelId: 'ch' }), game('live', { state: 'in', channelId: 'ch' }), game('other', { state: 'in', channelId: 'nope' })];
    expect(gameForChannel('ch', g)?.id).toBe('live');
    expect(gameForChannel('missing', g)).toBeNull();
  });

  it('puts followed teams first, and groups the dev server board by league and state', () => {
    const followed = dev.games.find((g) => g.state === 'pre');
    if (followed === undefined) throw new Error('fixture changed');
    const key = `${followed.league.toUpperCase()}:${followed.home.abbr.toUpperCase()}`;
    const r = boardRows(dev.games, new Set(), false, new Set([key]));
    const row = r.find((x) => x.key === `${followed.league}|pre`);
    expect(row?.games[0]?.id).toBe(followed.id);
    for (const x of r) for (const g of x.games) expect(`${g.league}|${rowState(g)}`).toBe(x.key);
  });
});

describe('line score (Android LineScoreLabelsTest)', () => {
  it('labels quarters, innings, hockey periods and overtimes', () => {
    expect(periodLabels('football', 7)).toEqual(['1', '2', '3', '4', 'OT', '2OT', '3OT']);
    expect(periodLabels('Football', 5)).toEqual(['1', '2', '3', '4', 'OT']);
    expect(periodLabels('baseball', 11)).toEqual(['1', '2', '3', '4', '5', '6', '7', '8', '9', '10', '11']);
    expect(periodLabels('hockey', 6)).toEqual(['1', '2', '3', 'OT', 'SO', '6']);
    expect(periodLabels('soccer', 2)).toEqual(['1', '2']);
    expect(periodLabels('football', 0)).toEqual([]);
  });

  it('keeps regulation columns reserved, dashes unplayed periods, accents only a strict lead', () => {
    expect([periodColumnCount('football', 1), periodColumnCount('football', 5), periodColumnCount('baseball', 3), periodColumnCount('baseball', 12), periodColumnCount('hockey', 1), periodColumnCount('soccer', 0)]).toEqual([4, 5, 9, 12, 3, 0]);
    expect(periodValue([7], 1)).toBe('–');
    expect(periodValue([0, 10], 0)).toBe('0');
    expect([isLeading(41, 31), isLeading(17, 17), isLeading(3, 4), isLeading(null, 4)]).toEqual([true, false, false, false]);
  });

  it('narrows columns (never widens) to fit the panel, total included', () => {
    expect(fittedColumnWidth(COMPACT, 9, null)).toBe(COMPACT.columnWidth);
    const w = fittedColumnWidth(COMPACT, 9, 600);
    expect(w).toBeLessThan(COMPACT.columnWidth);
    const gutter = COMPACT.mark + 2 * COMPACT.gap + COMPACT.abbrWidth;
    expect(gutter + w * 10 + COMPACT.totalGap).toBeLessThanOrEqual(600);
    expect(fittedColumnWidth(FULL, 4, 2000)).toBe(FULL.columnWidth);
  });

  it('labels every game of the Android periods sample', () => {
    for (const g of periods.games) {
      const played = Math.max(g.away.periods.length, g.home.periods.length);
      const labels = periodLabels(g.sport, periodColumnCount(g.sport, played));
      if (g.sport === 'football') expect(labels.slice(0, 4)).toEqual(['1', '2', '3', '4']);
      else expect(labels.length).toBeGreaterThanOrEqual(9);
    }
  });
});

describe('score roll (RollingText.kt)', () => {
  it('moves the glyphs as on Android', () => {
    expect(rollIncomingOffset(0)).toBe(1);
    expect(rollIncomingOffset(1)).toBe(0);
    expect(rollOutgoingOffset(0, 1)).toBe(-1);
    expect(rollOutgoingOffset(0.5, 0.5)).toBeCloseTo(-0.25);
    expect(easeOut(0)).toBe(0);
    expect(easeOut(1)).toBeCloseTo(1);
    expect(easeOut(0.5)).toBeGreaterThan(0.5);
  });

  it('restarts an interrupted roll from where the cells are', () => {
    // at rest: the glyph leaves from 0
    expect(rollRestart(0, 1)).toEqual({ keepPrevious: false, from: 0 });
    // barely started: the new glyph replaces the incoming one, the leaving one keeps going
    expect(rollRestart(0, 0.1).keepPrevious).toBe(true);
    // mostly in: the incoming glyph becomes the leaving one from where it is
    const r = rollRestart(0, 0.8);
    expect(r.keepPrevious).toBe(false);
    expect(r.from).toBeCloseTo(0.2);
  });

  it('flashes only a score that went up', () => {
    expect([scoreWentUp(3, 4), scoreWentUp(4, 4), scoreWentUp(null, 4), scoreWentUp(4, 3)]).toEqual([true, false, false, false]);
  });
});

describe('followed start countdown (GameCard.kt startsInLabel)', () => {
  const start = Date.parse('2026-09-24T23:05:00Z');
  it('counts down inside 90 minutes', () => {
    expect(startsIn(start, start - 91 * 60_000)).toBeNull();
    expect(startsIn(start, start - 90 * 60_000)).toEqual({ kind: 'minutes', minutes: 90 });
    expect(startsInText(startsIn(start, start - 23 * 60_000 - 30_000) ?? { kind: 'starting' })).toBe('IN 23 MIN');
    expect(startsIn(start, start - 59_000)).toEqual({ kind: 'starting' });
    expect(startsIn(start, start + 59_000)).toEqual({ kind: 'starting' });
    expect(startsIn(start, start + 60_000)).toBeNull();
  });
});

describe('team mark fallback', () => {
  it('keeps the card size as it was and fits long abbreviations into small squares', () => {
    expect(markFont('RDG', 51)).toEqual({ fontSize: 22, letterSpacing: 1 });
    expect(markFont('RDG', 125).fontSize).toBe(29);
    const small = markFont('RDG', 32);
    expect(small.letterSpacing).toBe(0);
    expect(3 * small.fontSize * 0.6).toBeLessThanOrEqual(32 - 10);
  });
});

describe('sizedLogo (team logos at their drawn size)', () => {
  it('asks ESPN for the drawn size', () => {
    expect(sizedLogo('https://a.espncdn.com/i/teamlogos/nfl/500/scoreboard/gb.png', 51)).toBe(
      'https://a.espncdn.com/combiner/i?img=%2Fi%2Fteamlogos%2Fnfl%2F500%2Fscoreboard%2Fgb.png&w=51&h=51',
    );
    expect(sizedLogo('https://a.espncdn.com/i/teamlogos/mlb/500/tb.png', 124.8)).toContain('&w=125&h=125');
  });
  it('keeps other addresses', () => {
    expect(sizedLogo('http://server/JellyTV/Logo/1.png', 51)).toBe('http://server/JellyTV/Logo/1.png');
    expect(sizedLogo('https://a.espncdn.com/combiner/i?img=/x.png&w=500', 51)).toBe('https://a.espncdn.com/combiner/i?img=/x.png&w=500');
  });
});
