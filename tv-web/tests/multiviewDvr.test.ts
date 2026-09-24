import { describe, expect, it } from 'vitest';
import { decodeDvrList, jobForGame, recordingView, spoilerGuarded, teamRuleFor } from '../src/api/tallyDvr';
import { decodeGame } from '../src/api/tallyModels';
import { dvrElapsed, dvrLength, dvrSize, estimateLine, keepLastText, recordingStateText, recordingsSections, ruleMeta, storageLine } from '../src/pages/sports/dvrFormat';
import { defaultLayout, multiviewDecoders, playingTiles, TILE_GAP, TILE_LABEL_HEIGHT, tileFocusMap, tileSlots } from '../src/pages/multiview/multiviewLayout';

/** The plugin's RecordingsController payload, as the Android app's DvrModelsTest has it. */
const list = decodeDvrList({
  canManage: true,
  rules: [
    { id: 'eae14ff5', kind: 'game', title: 'Delta Cranes at Summit Elks', leaguePath: 'baseball/mlb', gameId: '900003', keepLast: 0 },
    { id: '77aa', kind: 'team', title: 'Every Harbor Hawks game', leaguePath: 'baseball/mlb', teamId: '900450', teamName: 'Harbor Hawks', keepLast: 5 },
  ],
  jobs: [
    {
      id: 'c353', ruleId: 'x', state: 'recording', title: 'Riverton Otters at Lakeside Herons',
      game: {
        id: '900001', league: 'MLB', leaguePath: 'baseball/mlb', start: '2026-09-24T14:49:00+00:00',
        away: { id: '900446', abbr: 'ROT', name: 'Riverton Otters', shortName: 'Otters' },
        home: { id: '900447', abbr: 'LKH', name: 'Lakeside Herons', shortName: 'Herons' },
      },
      createdAt: '2026-09-24T17:40:30Z', startedAt: '2026-09-24T17:40:31Z', bytes: 20657816, seconds: 42,
      startOverPath: '/JellyTV/Recordings/c353/playlist.m3u8?s=abc',
    },
    { id: 'old1', ruleId: 'x', state: 'failed', reason: 'No stream found for this game', title: 'Riverton Otters at Lakeside Herons', game: { id: '900001', league: 'MLB', leaguePath: 'baseball/mlb' }, createdAt: '2026-09-24T17:30:00Z' },
    { id: '75a4', ruleId: 'eae14ff5', state: 'failed', reason: 'No stream found for this game', title: 'Delta Cranes at Summit Elks', game: { id: '900003', league: 'MLB', leaguePath: 'baseball/mlb' }, createdAt: '2026-09-24T17:40:10Z', unknownKey: 1 },
  ],
});

describe('DVR models (Android DvrModelsTest)', () => {
  it('decodes the list; a running job wins over an older failed one of the same game', () => {
    expect(list.canManage).toBe(true);
    expect(list.jobs[0]?.game.away.shortName).toBe('Otters');
    expect(jobForGame(list.jobs, '900001')?.id).toBe('c353');
    expect(jobForGame(list.jobs, '900003')?.id).toBe('75a4');
    expect(jobForGame(list.jobs, 'nope')).toBeNull();
  });

  it("prefers the list's job over the board's recording, and hides a recorded final's score", () => {
    const g = decodeGame({ id: '900001', state: 'in', recording: { state: 'scheduled', jobId: 'c353' } });
    expect(recordingView(g, list)?.state).toBe('recording');
    expect(recordingView(g, list)?.startedAt).toBe('2026-09-24T17:40:31Z');
    expect(recordingView(g, null)?.state).toBe('scheduled');
    expect(recordingView(decodeGame({ id: '1', state: 'pre' }), null)).toBeNull();
    expect(spoilerGuarded(decodeGame({ id: '1', state: 'post', recording: { state: 'done', jobId: 'a', itemId: 'b' } }))).toBe(true);
    expect(spoilerGuarded(decodeGame({ id: '1', state: 'post', recording: { state: 'finishing', jobId: 'a' } }))).toBe(true);
    expect(spoilerGuarded(decodeGame({ id: '1', state: 'post', recording: { state: 'failed', jobId: 'a' } }))).toBe(false);
    expect(spoilerGuarded(decodeGame({ id: '1', state: 'in', recording: { state: 'recording', jobId: 'a' } }))).toBe(false);
  });

  it("matches team rules by team id within the game's league", () => {
    const hawks = decodeGame({ id: '900002', sport: 'baseball', league: 'MLB', state: 'pre', away: { id: '900450', abbr: 'HBH' }, home: { id: '900451', abbr: 'MSO' } });
    expect(teamRuleFor(list, hawks, hawks.away)?.id).toBe('77aa');
    expect(teamRuleFor(list, hawks, hawks.home)).toBeNull();
    const nfl = { ...hawks, sport: 'football', league: 'NFL' };
    expect(teamRuleFor(list, nfl, nfl.away)).toBeNull();
  });

  it('reads sizes, lengths and states like the Android app', () => {
    const gb = 1024 * 1024 * 1024;
    expect([dvrSize(350 * 1024 * 1024), dvrSize(4.2 * gb), dvrSize(5 * gb), dvrSize(12 * gb), dvrSize(1.2 * 1024 * gb)]).toEqual(['350 MB', '4.2 GB', '5 GB', '12 GB', '1.2 TB']);
    expect(dvrLength(2 * 3600 + 58 * 60)).toBe('2h 58m');
    expect(dvrElapsed(0, 42 * 60_000 + 10_000)).toBe('42:10');
    expect(dvrElapsed(0, 3723_000)).toBe('1:02:03');
    expect(recordingStateText({ state: 'waiting', startedAt: null, reason: null })).toBe('Waiting for a stream');
    expect(recordingStateText({ state: 'waiting', startedAt: null, reason: 'Waiting for a free slot (2 recording)' })).toBe('Waiting for a free slot (2 recording)');
    expect(recordingStateText({ state: 'failed', startedAt: null, reason: 'No stream found for this game' })).toBe('Failed: No stream found for this game');
    expect(keepLastText(0)).toBe('Keep all');
    expect(keepLastText(5)).toBe('Keep the last 5');
    expect(estimateLine({ freeBytes: 1.2 * 1024 * gb, totalBytes: null, usedBytes: 0, reserveBytes: 0, estimate: { gameId: 'g', bytes: 9 * gb, fits: true, message: null } })).toBe('~9 GB · 1.2 TB free on the server');
    expect(storageLine({ freeBytes: 1.2 * 1024 * gb, totalBytes: null, usedBytes: 14 * gb, reserveBytes: 0, estimate: null })).toBe('1.2 TB free on the server · 14 GB used by recordings');
  });

  it('sorts the Recordings tab into its sections; canceled jobs are left out', () => {
    const s = recordingsSections(list);
    expect(s.recordingNow.map((j) => j.id)).toEqual(['c353']);
    expect(s.failed.map((j) => j.id)).toEqual(['old1', '75a4']);
    expect(s.rules.map((r) => r.id)).toEqual(['77aa']);
    expect(ruleMeta(s.rules[0] as (typeof s.rules)[0], list)).toBe('MLB · Keep the last 5');
  });
});

describe('multiview layout (TallyMultiviewPage.kt)', () => {
  const W = 1206;
  const H = 934;

  it('opens three and four tiles in focus, one and two equal', () => {
    expect([1, 2, 3, 4].map(defaultLayout)).toEqual(['equal', 'equal', 'focus', 'focus']);
  });

  it('puts the large tile at 68% on the left and stacks the rest on the right, top-aligned', () => {
    const s = tileSlots(4, 'focus', 0, W, H);
    expect(s[0]?.x).toBe(0);
    expect(s[0]?.y).toBe(0);
    expect(s[0]?.width).toBeCloseTo(W * 0.68);
    for (const i of [1, 2, 3]) expect(s[i]?.x).toBeCloseTo(W * 0.68 + TILE_GAP);
    expect(s[2]?.y).toBeCloseTo((s[1]?.y ?? 0) + ((s[1]?.width ?? 0) * 9) / 16 + TILE_LABEL_HEIGHT + TILE_GAP);
    // the promoted tile moves, the others keep their order in the stack
    const t = tileSlots(4, 'focus', 2, W, H);
    expect(t[2]?.x).toBe(0);
    expect(t[0]?.y).toBe(0);
    expect(t[0]?.x).toBeGreaterThan(0);
  });

  it('centers equal tiles and fits four inside the stage', () => {
    const s = tileSlots(4, 'equal', 0, W, H);
    for (const slot of s) {
      expect(slot.x + slot.width).toBeLessThanOrEqual(W + 0.01);
      expect(slot.y + (slot.width * 9) / 16 + TILE_LABEL_HEIGHT).toBeLessThanOrEqual(H + 0.01);
    }
    const one = tileSlots(1, 'focus', 0, W, H)[0];
    expect(one?.width).toBeLessThanOrEqual(W);
  });

  it('maps the D-pad between tiles and the rail', () => {
    expect(tileFocusMap(0, 4, 'focus', 0, true)).toEqual({ left: 'free', right: { tile: 1 }, up: 'block', down: 'block' });
    expect(tileFocusMap(2, 4, 'focus', 0, true)).toEqual({ left: { tile: 0 }, right: 'rail', up: { tile: 1 }, down: { tile: 3 } });
    expect(tileFocusMap(1, 3, 'equal', 0, false)).toEqual({ left: { tile: 0 }, right: 'block', up: 'block', down: { tile: 2 } });
    expect(tileFocusMap(2, 3, 'equal', 0, true)).toEqual({ left: 'free', right: 'rail', up: { tile: 0 }, down: 'block' });
  });

  it('gives decoders to the audio tile first when the TV has fewer than tiles', () => {
    expect(multiviewDecoders('browser')).toBe(4);
    expect(multiviewDecoders('tizen')).toBe(1);
    expect(playingTiles(4, 2, 4)).toEqual([true, true, true, true]);
    expect(playingTiles(4, 2, 1)).toEqual([false, false, true, false]);
    expect(playingTiles(3, 2, 2)).toEqual([true, false, true]);
    expect(playingTiles(2, 0, 0)).toEqual([false, false]);
  });
});
