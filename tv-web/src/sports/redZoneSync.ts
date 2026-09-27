import type { TallyRedZone } from '../api/tallyModels';

/**
 * Keeps what the RedZone overlay says (and the score bug and box score that follow the RedZone game) in step with the
 * picture (RedZoneSync.kt). The server reports a cut the moment its playlist carries it, but a player sits behind the
 * live edge (hls.js keeps five segments back, native players about three), so its picture cuts that much later.
 *
 * Every status answer is `offer`ed; `at` then says which of the cuts the player has reached: the last one that entered
 * the playlist at least `latency` ago. The first answer shows at once (nothing earlier is known to show), several cuts
 * inside one latency window each get their turn, and the overlay never steps back to an earlier cut.
 *
 * Times are read on this device's clock: the server's `since` times are shifted by the difference between its
 * `serverTime` and the device's clock when the answer arrived. Pure logic, one instance per player.
 */
export class RedZoneSync {
  private cuts: Cut[] = [];
  private shown: Cut | null = null;

  /** What the overlay shows now: the last result of `at`. */
  get current(): TallyRedZone | null {
    return this.shown?.status ?? null;
  }

  /** Takes a status answer that arrived at `nowMs` (device clock); null (unreadable) changes nothing. */
  offer(status: TallyRedZone | null, nowMs: number): void {
    if (status === null) return;
    // what the overlay keeps: the answer without its history and clock (the same cut stays the same object)
    const kept: TallyRedZone = { ...status, recent: [], serverTime: null };
    const server = parseTime(status.serverTime);
    const skew = server !== null ? server - nowMs : 0;
    const local = (since: string | null): number | null => {
      const t = parseTime(since);
      return t === null ? null : t - skew;
    };

    const fresh: Cut[] = [];
    for (const c of status.recent) {
      const since = local(c.since);
      if (since === null) continue;
      fresh.push({ status: { ...c, next: status.next, recent: [], serverTime: null }, sinceMs: since });
    }
    const last = fresh.length > 0 ? fresh[fresh.length - 1] : undefined;
    if (last !== undefined && sameCut(last.status, status)) {
      // what is on now: the latest answer's words (its reason, its next) at the time the playlist got it
      fresh[fresh.length - 1] = { status: kept, sinceMs: last.sinceMs };
    } else {
      // an older server (no recent), or a cut decided that has not reached the playlist yet: at its own time, or as
      // on already when it has none (the channel is not running yet)
      const floor = last?.sinceMs ?? -Infinity;
      const since = local(status.since);
      fresh.push({ status: kept, sinceMs: since === null ? floor : Math.max(since, floor) });
    }
    fresh.sort((a, b) => a.sinceMs - b.sinceMs);

    // the answer is the truth from its first cut on; what came before it stays as known
    const from = fresh[0]?.sinceMs ?? -Infinity;
    this.cuts = this.cuts.filter((c) => c.sinceMs < from).concat(fresh);
    this.cuts.sort((a, b) => a.sinceMs - b.sinceMs);
    // the cut on screen, as the latest answer words it (a new reason shows at once: it is the same picture)
    const on = this.shown;
    if (on !== null) this.shown = this.cuts.find((c) => c.sinceMs === on.sinceMs && sameCut(c.status, on.status)) ?? on;
    if (this.cuts.length > MAX_CUTS) this.cuts = this.cuts.slice(this.cuts.length - MAX_CUTS);
  }

  /**
   * What to show at `nowMs` (device clock) for a player `latencyMs` behind the live edge: the last cut that entered the
   * playlist at or before `nowMs - latencyMs`; before any has, the first one known (a player that just tuned in).
   */
  at(nowMs: number, latencyMs: number): TallyRedZone | null {
    const reached = nowMs - latencyMs;
    const on = this.shown;
    let candidate: Cut | null = null;
    for (const c of this.cuts) if (c.sinceMs <= reached) candidate = c;
    if (candidate !== null && (on === null || candidate.sinceMs >= on.sinceMs)) this.shown = candidate;
    else if (on === null) this.shown = this.cuts[0] ?? null;
    // what came before the cut on screen is never shown again
    const s = this.shown;
    if (s !== null) this.cuts = this.cuts.filter((c) => c.sinceMs >= s.sinceMs);
    return this.shown?.status ?? null;
  }

  /** Forgets everything (another channel, or RedZone tuned in again). */
  reset(): void {
    this.cuts = [];
    this.shown = null;
  }
}

interface Cut {
  status: TallyRedZone;
  /** When the playlist carried it, on this device's clock; -Infinity: on before anything we know of. */
  sinceMs: number;
}

const MAX_CUTS = 16;

/**
 * A player whose distance from the live edge cannot be read (Samsung's AVPlay) is taken to sit this far behind (ms):
 * where native HLS players start, three segments from the end of the playlist, with 3-second segments, plus the one
 * being listed.
 */
export const UNKNOWN_LATENCY_MS = 12_000;

const sameCut = (a: TallyRedZone, b: TallyRedZone): boolean => a.active === b.active && a.gameId === b.gameId;

/** An ISO-8601 time in epoch ms, or null (.NET writes seven fraction digits; older engines parse three at most). */
export function parseTime(iso: string | null): number | null {
  if (iso === null || iso === '') return null;
  const t = Date.parse(iso.replace(/(\.\d{3})\d+/, '$1'));
  return isNaN(t) ? null : t;
}
