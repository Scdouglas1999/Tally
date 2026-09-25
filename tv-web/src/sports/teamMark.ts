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

/**
 * A team logo at the size it is drawn. The board's logos are ESPN's 500 px PNGs (some are 4096 px: 64 MB decoded,
 * for a 51 px mark); ESPN's image combiner scales them on its side, so a TV downloads and decodes a few KB instead.
 * Other addresses are kept as they are.
 */
export function sizedLogo(url: string, size: number): string {
  const m = /^https?:\/\/a\.espncdn\.com(\/i\/[^?#]+\.png)$/i.exec(url);
  if (m === null) return url;
  const px = Math.ceil(size);
  return `https://a.espncdn.com/combiner/i?img=${encodeURIComponent(m[1] as string)}&w=${px}&h=${px}`;
}
