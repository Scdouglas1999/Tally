import { describe, expect, it } from 'vitest';
import { decodeRedZone, type TallyRedZone, type TallyRedZoneCut } from '../src/api/tallyModels';
import { LOADED_EDGE_ALLOWANCE_MS, videoLiveLatencyMs } from '../src/player/engine';
import { parseTime, RedZoneSync } from '../src/sports/redZoneSync';

// The RedZone overlay follows the picture: a cut shows once the player's own playback has reached it (RedZoneSyncTest.kt).
const t0 = Date.parse('2026-09-27T21:00:00Z');
const iso = (ms: number): string => new Date(ms).toISOString();
const latency = 20_000;

function cut(game: string | null, sinceMs: number, reason = 'hottest'): TallyRedZoneCut {
  return { active: game !== null, gameId: game, title: game !== null ? game + ' title' : null, reason: game !== null ? reason : null, since: iso(sinceMs) };
}

/** An answer at `serverMs` (server clock): on now is the last of `recent`, unless `on` says otherwise. */
function status(serverMs: number, recent: TallyRedZoneCut[], on: TallyRedZoneCut = recent[recent.length - 1]!): TallyRedZone {
  return { ...on, next: [], recent, serverTime: iso(serverMs) };
}

describe('RedZone overlay sync', () => {
  it('shows the first answer at once', () => {
    const sync = new RedZoneSync();
    sync.offer(status(t0, [cut('A', t0 - 3000)]), t0);
    expect(sync.at(t0, latency)?.gameId).toBe('A');
  });

  it('a first answer that knows the cut before shows the game the picture is still on', () => {
    const sync = new RedZoneSync();
    sync.offer(status(t0, [cut('A', t0 - 90_000), cut('B', t0 - 5000)]), t0);
    expect(sync.at(t0, latency)?.gameId).toBe('A');
    expect(sync.at(t0 + 14_000, latency)?.gameId).toBe('A');
    expect(sync.at(t0 + 15_000, latency)?.gameId).toBe('B');
  });

  it('holds a cut until the player has reached it', () => {
    const sync = new RedZoneSync();
    sync.offer(status(t0, [cut('A', t0 - 60_000)]), t0);
    expect(sync.at(t0, latency)?.gameId).toBe('A');
    sync.offer(status(t0 + 10_000, [cut('A', t0 - 60_000), cut('B', t0 + 2000, 'score')]), t0 + 10_000);
    expect(sync.at(t0 + 10_000, latency)?.gameId).toBe('A');
    expect(sync.at(t0 + 21_999, latency)?.gameId).toBe('A');
    const b = sync.at(t0 + 22_000, latency);
    expect([b?.gameId, b?.reason, b?.title]).toEqual(['B', 'score', 'B title']);
  });

  it('gives several cuts inside one latency window each their turn, the slate too', () => {
    const sync = new RedZoneSync();
    sync.offer(status(t0, [cut('A', t0 - 60_000)]), t0);
    sync.at(t0, latency);
    sync.offer(status(t0 + 10_000, [cut('A', t0 - 60_000), cut('B', t0 + 1000), cut('C', t0 + 6000, 'red zone')]), t0 + 10_000);
    sync.offer(status(t0 + 20_000, [cut('A', t0 - 60_000), cut('B', t0 + 1000), cut('C', t0 + 6000, 'red zone'), cut(null, t0 + 15_000)]), t0 + 20_000);
    const seen = Array.from({ length: 41 }, (_, i) => sync.at(t0 + 10_000 + i * 1000, latency));
    expect(seen[0]?.gameId).toBe('A');
    expect(seen[10]?.gameId).toBe('A');
    expect(seen[11]?.gameId).toBe('B');
    expect(seen[16]?.gameId).toBe('C');
    expect(seen[16]?.reason).toBe('red zone');
    expect(seen[25]?.active).toBe(false);
    expect(seen[25]?.gameId).toBeNull();
  });

  it('follows an older server (no recent) by since alone', () => {
    const sync = new RedZoneSync();
    const a: TallyRedZone = { active: true, gameId: 'A', title: 'A', reason: 'hottest', since: iso(t0 - 60_000), next: [], recent: [], serverTime: null };
    sync.offer(a, t0);
    expect(sync.at(t0, latency)?.gameId).toBe('A');
    sync.offer({ ...a, gameId: 'B', title: 'B', since: iso(t0 + 4000) }, t0 + 10_000);
    expect(sync.at(t0 + 23_000, latency)?.gameId).toBe('A');
    expect(sync.at(t0 + 24_000, latency)?.gameId).toBe('B');
  });

  it('shows a status with no since (the channel not running yet) at once', () => {
    const sync = new RedZoneSync();
    sync.offer({ active: true, gameId: 'A', title: 'A', reason: 'hottest', since: null, next: [], recent: [], serverTime: null }, t0);
    expect(sync.at(t0, latency)?.gameId).toBe('A');
  });

  it('waits for a cut decided but not in the playlist yet, then goes by the playlist time', () => {
    const sync = new RedZoneSync();
    sync.offer(status(t0, [cut('A', t0 - 60_000)]), t0);
    sync.at(t0, latency);
    sync.offer(status(t0 + 8000, [cut('A', t0 - 60_000)], cut('B', t0 + 5000)), t0 + 8000);
    expect(sync.at(t0 + 20_000, latency)?.gameId).toBe('A');
    sync.offer(status(t0 + 18_000, [cut('A', t0 - 60_000), cut('B', t0 + 9000)]), t0 + 18_000);
    expect(sync.at(t0 + 28_999, latency)?.gameId).toBe('A');
    expect(sync.at(t0 + 29_000, latency)?.gameId).toBe('B');
  });

  it('never steps back when the latency estimate jumps', () => {
    const sync = new RedZoneSync();
    sync.offer(status(t0, [cut('A', t0 - 60_000), cut('B', t0 - 25_000)]), t0);
    expect(sync.at(t0, latency)?.gameId).toBe('B');
    expect(sync.at(t0 + 1000, 40_000)?.gameId).toBe('B');
  });

  it("reads server times on this device's clock", () => {
    const sync = new RedZoneSync();
    sync.offer(status(t0, [cut('A', t0 - 60_000)]), t0);
    sync.at(t0, latency);
    // the server's clock runs 30 s ahead: B's cut at server t0 + 32 s is device t0 + 2 s
    sync.offer(status(t0 + 40_000, [cut('A', t0 - 30_000), cut('B', t0 + 32_000)]), t0 + 10_000);
    expect(sync.at(t0 + 21_999, latency)?.gameId).toBe('A');
    expect(sync.at(t0 + 22_000, latency)?.gameId).toBe('B');
  });

  it('ignores an unreadable answer and shows a new reason for the same cut at once', () => {
    const sync = new RedZoneSync();
    sync.offer(status(t0, [cut('A', t0 - 60_000)]), t0);
    sync.offer(null, t0 + 10_000);
    expect(sync.at(t0 + 10_000, latency)?.reason).toBe('hottest');
    sync.offer(status(t0 + 20_000, [cut('A', t0 - 60_000)], cut('A', t0 - 60_000, 'red zone')), t0 + 20_000);
    expect(sync.at(t0 + 20_000, latency)?.reason).toBe('red zone');
  });

  it('keeps the same cut asked again as the same object (nothing on screen changes)', () => {
    const sync = new RedZoneSync();
    sync.offer(status(t0, [cut('A', t0 - 60_000)]), t0);
    const first = sync.at(t0, latency);
    sync.offer(status(t0 + 10_000, [cut('A', t0 - 60_000)]), t0 + 10_000);
    expect(sync.at(t0 + 10_000, latency)).toEqual(first);
    expect(first?.recent).toEqual([]);
  });

  it("decodes recent and serverTime, .NET's seven fraction digits included", () => {
    const s = decodeRedZone({
      active: true,
      gameId: 'B',
      since: '2026-09-27T21:00:01.5+00:00',
      serverTime: '2026-09-27T21:00:09.1234567+00:00',
      recent: [{ active: true, gameId: 'A', title: 'A', reason: 'hottest', since: '2026-09-27T20:58:00+00:00' }, { active: true, gameId: 'B', since: '2026-09-27T21:00:02+00:00' }, 'junk'],
    });
    expect(s.recent.map((c) => c.gameId)).toEqual(['A', 'B', null]);
    expect(parseTime(s.serverTime)).toBe(t0 + 9123);
    expect(parseTime(s.recent[1]!.since)).toBe(t0 + 2000);
    expect(parseTime(null)).toBeNull();
    expect(parseTime('soon')).toBeNull();
  });
});

describe('live latency of a <video>', () => {
  const ranges = (list: Array<[number, number]>) => ({ length: list.length, start: (i: number) => list[i]![0], end: (i: number) => list[i]![1] });
  const video = (currentTime: number, seekable: Array<[number, number]>, buffered: Array<[number, number]> = []) =>
    ({ currentTime, seekable: ranges(seekable), buffered: ranges(buffered) }) as unknown as HTMLVideoElement;

  it("takes hls.js's own estimate first, else the end of the seekable range, else what a native player has loaded", () => {
    expect(videoLiveLatencyMs(video(100, [[0, 118]]), { latency: 16.5 })).toBe(16_500);
    expect(videoLiveLatencyMs(video(100, [[0, 118]]), null)).toBe(18_000);
    expect(videoLiveLatencyMs(video(100, [[0, 118]]), { latency: NaN })).toBe(18_000);
    // Chromium's own HLS: no seekable range, loaded up to the newest segment
    expect(videoLiveLatencyMs(video(100, [], [[0, 106]]), null)).toBe(6000 + LOADED_EDGE_ALLOWANCE_MS);
    expect(videoLiveLatencyMs(video(100, [[0, Infinity]], [[0, 107]]), { latency: 0 })).toBe(7000 + LOADED_EDGE_ALLOWANCE_MS);
    expect(videoLiveLatencyMs(video(100, []), null)).toBeNull();
  });
});
