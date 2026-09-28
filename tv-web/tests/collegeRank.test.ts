import { readFileSync } from 'node:fs';
import type { VNode } from 'preact';
import { describe, expect, it } from 'vitest';
import { decodeBoard, decodeGame, UPSET_ALERT } from '../src/api/tallyModels';
import { TeamRank, UpsetTag } from '../src/sports/SportsBits';

/** College games from the dev server's board (score sim: AP Top 25 ranks on NCAAF teams) and one NFL game. */
const board = decodeBoard(JSON.parse(readFileSync(new URL('./fixtures/board-ncaaf.json', import.meta.url), 'utf8')));
const byName = (name: string) => {
  const g = board.games.find((x) => x.name === name);
  if (g === undefined) throw new Error(name);
  return g;
};
const board23 = decodeBoard(JSON.parse(readFileSync(new URL('./fixtures/board-23.json', import.meta.url), 'utf8')));

describe('college poll rank (team.rank)', () => {
  it('reads the AP rank of ranked college teams and none for the rest', () => {
    expect(byName('TEX @ TENN').away.rank).toBe(1);
    expect(byName('TEX @ TENN').home.rank).toBe(14);
    expect(byName('ILL @ OSU').away.rank).toBeNull();
    expect(byName('ILL @ OSU').home.rank).toBe(7);
    expect(byName('LAC @ BUF').away.rank).toBeNull();
  });

  it('degrades silently: an older board (no rank key) and odd values are no rank', () => {
    for (const g of board23.games) expect([g.away.rank, g.home.rank]).toEqual([null, null]);
    const rankOf = (rank: unknown) => decodeGame({ home: { rank } }).home.rank;
    expect(rankOf(undefined)).toBeNull();
    expect(rankOf(null)).toBeNull();
    expect(rankOf(0)).toBeNull();
    expect(rankOf(-3)).toBeNull();
    expect(rankOf(2.5)).toBeNull();
    expect(rankOf('7')).toBeNull();
    expect(rankOf(25)).toBe(25);
    expect(decodeGame({}).home.rank).toBeNull();
  });

  it('draws "#7" before the name, nothing for an unranked team', () => {
    const rank = TeamRank({ team: byName('ILL @ OSU').home }) as VNode<{ class: string; children: unknown }>;
    expect(rank.props.class).toBe('team-rank');
    expect([rank.props.children].flat().join('')).toBe('#7');
    expect(TeamRank({ team: byName('ILL @ OSU').away })).toBeNull();
  });
});

describe('UPSET ALERT (game.tags)', () => {
  const live = (tags: unknown, state = 'in') => decodeGame({ id: 'g', league: 'NCAAF', state, tags });

  it('is read from the tags, among others and in any case; the other tags stay unmodeled', () => {
    expect(live(['ONE-SCORE GAME', UPSET_ALERT]).upsetAlert).toBe(true);
    expect(live(['upset alert']).upsetAlert).toBe(true);
    expect(byName('LAC @ BUF').upsetAlert).toBe(false); // RED ZONE, ONE-SCORE GAME
  });

  it('degrades silently: no tags, odd tags and older boards are no alert', () => {
    expect(live(undefined).upsetAlert).toBe(false);
    expect(live(null).upsetAlert).toBe(false);
    expect(live('UPSET ALERT').upsetAlert).toBe(false);
    expect(live([1, null, {}]).upsetAlert).toBe(false);
    for (const g of board23.games) expect(g.upsetAlert).toBe(false);
  });

  it('shows on a live game as a live-red tag, never while scores are hidden or once the game is over', () => {
    const tag = UpsetTag({ game: live([UPSET_ALERT]), hideScores: false }) as VNode<{ class: string; children: unknown }>;
    expect(tag.props.class).toBe('rec-tag live upset-tag');
    expect(tag.props.children).toBe('UPSET ALERT');
    expect(UpsetTag({ game: live([UPSET_ALERT]), hideScores: true })).toBeNull();
    expect(UpsetTag({ game: live([UPSET_ALERT], 'post'), hideScores: false })).toBeNull();
    expect(UpsetTag({ game: live([]), hideScores: false })).toBeNull();
  });
});
