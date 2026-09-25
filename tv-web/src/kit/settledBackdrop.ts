import { useEffect, useState } from 'preact/hooks';

/** Focus rests this long on a card before its backdrop is shown (TallyHomePage's 100 ms + BackdropService's 500 ms). */
export const BACKDROP_SETTLE_MS = 600;

/**
 * The backdrop that follows focus, as Android's BackdropService draws it: the old picture goes at once, the new one
 * comes once focus has rested on the card. Moving along a row therefore does not fetch and decode a large picture
 * per card (the costliest thing on a TV's SoC while scrolling).
 */
export function useSettledBackdrop(url: string | null, settleMs = BACKDROP_SETTLE_MS): string | null {
  const [shown, setShown] = useState<string | null>(null);
  useEffect(() => {
    setShown(null);
    if (url === null) return undefined;
    const t = window.setTimeout(() => setShown(url), settleMs);
    return () => window.clearTimeout(t);
  }, [url]);
  return shown;
}
