import { useEffect, useRef } from 'preact/hooks';
import { currentFocusKey, focusExists, setFocus } from '../../focus/focus';

/**
 * A Sports tab's first focus (the Android tabs request focus when their content appears): once `ready`, focus
 * `target`. When the target changes while focus is still on the previous one (LOADING… replaced by the first card)
 * or focus was lost, it moves to the new target; once the viewer has moved on, it stays where they put it.
 */
export function useTabArrival(enabled: boolean, target: string | null, ready: boolean): void {
  const placed = useRef<string | null>(null);
  useEffect(() => {
    if (!enabled || !ready || target === null || placed.current === target || !focusExists(target)) return;
    const current = currentFocusKey();
    // on our previous target, or that target is gone (LOADING… replaced by the cards, as Android refocuses then)
    const stillOnOurs = placed.current !== null && (current === placed.current || !focusExists(placed.current));
    const lost = current === '' || !focusExists(current) || document.querySelector('[data-focused]') === null;
    if (placed.current === null || stillOnOurs || lost) {
      placed.current = target;
      setFocus(target);
    }
  });
}
