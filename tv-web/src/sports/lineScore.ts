/** Line score columns (port of the Android app's LineScore.kt helpers; same rules, unit-tested). */

function periodLabel(sport: string, n: number): string {
  if (sport === 'football') {
    if (n <= 4) return String(n);
    if (n === 5) return 'OT';
    return `${n - 4}OT`;
  }
  if (sport === 'hockey') {
    if (n <= 3) return String(n);
    if (n === 4) return 'OT';
    if (n === 5) return 'SO';
    return String(n);
  }
  return String(n);
}

/**
 * Column headers for the first `count` periods (not the total column). Football is quarters 1 2 3 4, then OT, 2OT…;
 * hockey is periods 1 2 3, then OT, then SO; baseball and every other sport are 1..n (innings 10, 11… stay numbers).
 */
export function periodLabels(sport: string, count: number): string[] {
  const kind = sport.toLowerCase();
  const out: string[] = [];
  for (let i = 1; i <= count; i++) out.push(periodLabel(kind, i));
  return out;
}

/**
 * How many period columns to reserve. Regulation length is always shown, so a quarter or inning filling in does not
 * add a column and shift the total. Extra time grows the table.
 */
export function periodColumnCount(sport: string, played: number): number {
  const kind = sport.toLowerCase();
  const regulation = kind === 'football' ? 4 : kind === 'baseball' ? 9 : kind === 'hockey' ? 3 : 0;
  return Math.max(regulation, Math.max(0, played));
}

/** The cell text for one period: the points, or `unplayed` when that period has not been played. */
export function periodValue(periods: readonly number[], index: number, unplayed = '–'): string {
  const v = periods[index];
  return v === undefined ? unplayed : String(v);
}

/** True when `score` is strictly ahead of `other`. Ties and missing scores accent nobody. */
export function isLeading(score: number | null, other: number | null): boolean {
  return score !== null && other !== null && score > other;
}

/** Sizes at the 1080p canvas (Android dp × 1.6). */
export interface LineScoreMetrics {
  mark: number;
  gap: number;
  abbrWidth: number;
  columnWidth: number;
  totalGap: number;
  rowGap: number;
  ruleGap: number;
  /** font sizes (px) */
  number: number;
  header: number;
}

/** The focused-game panel's size (20dp marks). */
export const COMPACT: LineScoreMetrics = { mark: 32, gap: 10, abbrWidth: 64, columnWidth: 51, totalGap: 13, rowGap: 6, ruleGap: 5, number: 29, header: 22 };

/** The live player's box score (28dp marks). */
export const FULL: LineScoreMetrics = { mark: 45, gap: 16, abbrWidth: 90, columnWidth: 77, totalGap: 19, rowGap: 10, ruleGap: 6, number: 32, header: 26 };

/**
 * Period and total columns narrow (never widen) so the whole table, total included, fits `maxWidth`: the panel's
 * situation column is narrower than a nine-inning line at full column width, and extra innings add columns.
 */
export function fittedColumnWidth(m: LineScoreMetrics, columns: number, maxWidth: number | null): number {
  if (maxWidth === null) return m.columnWidth;
  const gutter = m.mark + m.gap + m.abbrWidth + m.gap;
  const fitted = Math.floor((maxWidth - gutter - m.totalGap) / (columns + 1));
  return Math.min(m.columnWidth, fitted);
}
