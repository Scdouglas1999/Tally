/** Mono cap width per character (IBM Plex Mono advance ≈ 0.6 em). */
const MONO_ADVANCE = 0.6;

/**
 * The fallback square's abbreviation, whole (TeamMark.kt MarkAbbreviation): at its own size when it fits the square;
 * in a smaller square the letter spacing goes first, then the size shrinks until it fits inside the border.
 */
export function markFont(abbr: string, size: number): { fontSize: number; letterSpacing: number } {
  const base = size >= 90 ? 29 : 22;
  const spacing = 1;
  const chars = Math.max(1, abbr.length);
  const full = chars * base * MONO_ADVANCE + chars * spacing;
  if (full <= size - 6) return { fontSize: base, letterSpacing: spacing };
  const inner = size - 2 * 5; // the 3dp border and air on each side (1 dp = 1.6 px)
  const scale = Math.min(1, inner / (chars * base * MONO_ADVANCE));
  return { fontSize: Math.floor(base * scale * 10) / 10, letterSpacing: 0 };
}
