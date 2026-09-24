/**
 * The score roll (port of the Android app's RollingText.kt; same numbers, unit-tested): scoreboard digits drawn one
 * character cell at a time; a changed character rolls inside its own clipped cell, the old glyph moving up and out
 * while the new one comes up from below, ROLL_MS ease-out. A score that went up is drawn in the accent color for
 * SCORE_HIGHLIGHT_HOLD_MS, then eases back over SCORE_HIGHLIGHT_FADE_MS.
 */

/** How long one character cell takes to roll to its new glyph. */
export const ROLL_MS = 220;

/** How long a score that just went up stays drawn in the accent color. */
export const SCORE_HIGHLIGHT_HOLD_MS = 2500;

/** How long the accent takes to ease back to the score's normal color. */
export const SCORE_HIGHLIGHT_FADE_MS = 400;

/** Compose's EaseOut: cubic-bezier(0, 0, 0.58, 1). */
export function easeOut(t: number): number {
  const x = Math.min(1, Math.max(0, t));
  // solve bezier x(u) = x for u (Newton), then y(u)
  const p1x = 0;
  const p2x = 0.58;
  const bx = (u: number): number => 3 * (1 - u) * (1 - u) * u * p1x + 3 * (1 - u) * u * u * p2x + u * u * u;
  const by = (u: number): number => 3 * (1 - u) * u * u * 1 + u * u * u; // p1y = 0, p2y = 1
  let u = x;
  for (let i = 0; i < 8; i++) {
    const d = (bx(u + 1e-4) - bx(u - 1e-4)) / 2e-4;
    if (d === 0) break;
    u = Math.min(1, Math.max(0, u - (bx(u) - x) / d));
  }
  return by(u);
}

/** Offset of the incoming glyph, in cell heights (1 = one cell below, 0 = in place). */
export function rollIncomingOffset(progress: number): number {
  return 1 - Math.min(1, Math.max(0, progress));
}

/**
 * Offset of the outgoing glyph, in cell heights: from where it was when the roll started (`from`, 0 at rest) up to
 * one cell above (-1).
 */
export function rollOutgoingOffset(from: number, progress: number): number {
  const p = Math.min(1, Math.max(0, progress));
  return from + (-1 - from) * p;
}

/**
 * An interrupted roll. keepPrevious: the glyph already leaving is still more in view than the one coming in, so it
 * keeps leaving and the new glyph replaces the incoming one (`from` unchanged). Otherwise the incoming glyph becomes
 * the leaving one, from where it is (`from`), and the roll restarts. A cell at rest restarts with its glyph leaving
 * from 0.
 */
export function rollRestart(previousFrom: number, progress: number): { keepPrevious: boolean; from: number } {
  const incoming = rollIncomingOffset(progress);
  const outgoing = rollOutgoingOffset(previousFrom, progress);
  return Math.abs(incoming) <= Math.abs(outgoing) ? { keepPrevious: false, from: incoming } : { keepPrevious: true, from: previousFrom };
}

/** True when a score went up between two board updates (not on the first value seen). */
export function scoreWentUp(previous: number | null, next: number | null): boolean {
  return previous !== null && next !== null && next > previous;
}
