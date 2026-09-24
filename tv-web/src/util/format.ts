/** Text formats shared by every screen (ports of the Android app's TallyFormat.kt / DetailHeader.kt helpers). */

const UNIT_FIXES: Array<[RegExp, string]> = [
  [/(\d(?:[\d.]*\d)?) ?MBPS\b/g, '$1 Mbps'],
  [/(\d(?:[\d.]*\d)?) ?KBPS\b/g, '$1 kbps'],
  [/\b(\d{3,4})P\b/g, '$1p'],
  [/\b(\d{3,4})I\b/g, '$1i'],
  [/\b(\d+)H\b/g, '$1h'],
  [/\b(\d+)M\b/g, '$1m'],
  [/\b(\d+)S\b/g, '$1s'],
];

/**
 * Tally's uppercase label, except units keep their standard case: `14.8 Mbps`, `1080p`, `2h 35m`. Use it for any
 * label that can contain one (CSS text-transform would uppercase the units too).
 */
export function tallyUppercase(text: string): string {
  return UNIT_FIXES.reduce((t, [re, rep]) => t.replace(re, rep), text.toUpperCase());
}

const TICKS_PER_SECOND = 10_000_000;

/** `2h 28m`, `52m`, `1m 30s`, `45s`. Seconds are kept only when there are no hours. */
export function formatRuntime(ticks: number): string {
  if (ticks <= 0) return '0s';
  const total = Math.floor(ticks / TICKS_PER_SECOND);
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  if (h > 0 && m > 0) return `${h}h ${m}m`;
  if (h > 0) return `${h}h`;
  if (m > 0 && s > 0) return `${m}m ${s}s`;
  if (m > 0) return `${m}m`;
  return `${s}s`;
}

const pad2 = (n: number): string => (n < 10 ? '0' : '') + String(n);

/** Player clock: `1:02:03`, `04:05`. */
export function formatClock(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  return h > 0 ? `${h}:${pad2(m)}:${pad2(s)}` : `${pad2(m)}:${pad2(s)}`;
}

/** 0-100, zero when either value is not positive. */
export function resumePercent(positionTicks: number, runtimeTicks: number): number {
  if (positionTicks <= 0 || runtimeTicks <= 0) return 0;
  return Math.min(100, Math.max(0, Math.round((positionTicks * 100) / runtimeTicks)));
}

/** `831550` → `831 550`; anything that is not six characters is shown as it is. */
export function splitCode(code: string): string {
  return code.length === 6 ? code.substring(0, 3) + ' ' + code.substring(3) : code;
}

const DAYS = ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT'];

/** `7:30 PM` (12-hour, American). */
export function formatTime(date: Date): string {
  let h = date.getHours();
  const suffix = h >= 12 ? 'PM' : 'AM';
  h = h % 12;
  if (h === 0) h = 12;
  return `${h}:${pad2(date.getMinutes())} ${suffix}`;
}

/** Upcoming game start: today `7:30 PM`, another day `SUN 7:30 PM`; '' when unparseable. */
export function formatGameStart(start: string, now: Date = new Date()): string {
  const t = Date.parse(start);
  if (isNaN(t)) return '';
  const d = new Date(t);
  const sameDay = d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth() && d.getDate() === now.getDate();
  return sameDay ? formatTime(d) : `${DAYS[d.getDay()] ?? ''} ${formatTime(d)}`;
}

const NO_RESULT = /postponed|canceled|cancelled|suspended|delayed|forfeit/i;

/** A finished game that was never played to a result: its 0-0 is not a score. */
export function hasNoResult(state: string, detail: string): boolean {
  return state === 'post' && NO_RESULT.test(detail);
}

/** Right-hand status on a game card: the detail when live, start time when upcoming, FINAL when done. */
export function gameStatusLabel(game: { state: string; detail: string; start: string }, now?: Date): string {
  if (game.state === 'in') return game.detail;
  if (game.state === 'pre') return formatGameStart(game.start, now) || game.detail;
  return (game.detail || 'Final').toUpperCase();
}
