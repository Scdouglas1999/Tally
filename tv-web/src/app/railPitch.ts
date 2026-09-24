/**
 * The rail's row pitch: every entry on screen at once when it fits (40dp = 80px at most, 30dp = 60px at least;
 * below that the list is cut, as on Android where it scrolls). The fixed part: wordmark band, user row, dividers,
 * the Settings footer and the focus-border room.
 */
export function railPitch(rows: number, dividers: number): number {
  const fixed = 32 + 32 + 16 + 80 + 16 + dividers * 26 + 40 + 26 + 16 + 14;
  return Math.max(60, Math.min(80, Math.floor((1080 - fixed) / Math.max(1, rows))));
}
