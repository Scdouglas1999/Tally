/** How soon a followed team's game starts, for the card clock (GameCard.kt startsInLabel; same thresholds). */
export type StartsIn = { kind: 'starting' } | { kind: 'minutes'; minutes: number };

const WINDOW_S = 90 * 60;
const STARTING_S = 60;

/**
 * - more than 90 minutes away, or 60 seconds or more past `now`: null (show the start time);
 * - under 60 seconds until start, including up to 59 seconds past: starting;
 * - otherwise, within 90 minutes: whole minutes, truncated ("IN 23 MIN").
 */
export function startsIn(startMs: number, nowMs: number): StartsIn | null {
  const seconds = Math.trunc((startMs - nowMs) / 1000);
  if (seconds > WINDOW_S) return null;
  if (seconds <= -STARTING_S) return null;
  if (seconds < STARTING_S) return { kind: 'starting' };
  return { kind: 'minutes', minutes: Math.floor(seconds / 60) };
}

/** "STARTING", "IN 23 MIN". */
export function startsInText(s: StartsIn): string {
  return s.kind === 'starting' ? 'STARTING' : `IN ${s.minutes} MIN`;
}
