import { useEffect, useRef } from 'preact/hooks';
import { currentFocusKey, focusExists, setFocus } from '../focus/focus';
import type { Route } from '../router/router';

/** What every page component receives. */
export interface PageProps<R extends Route = Route> {
  route: R;
  /** The page is the top of the stack (visible). Hidden pages stay mounted and keep their state. */
  active: boolean;
  /** The page's focus group key (focus this to restore the page's last focus). */
  pageKey: string;
}

/**
 * Initial focus when a page opens: once `ready`, focus `target` (the first card, the primary button) unless the
 * user already moved focus inside the page. Runs once per page.
 */
let firstFocusMarked = false;

export function useArrivalFocus(props: PageProps, target: string | null, ready: boolean): void {
  const done = useRef(false);
  useEffect(() => {
    if (done.current || !ready || !props.active || target === null || !focusExists(target)) return;
    done.current = true;
    const current = currentFocusKey();
    if (current === props.pageKey || current === '' || current === 'SN:ROOT' || !document.querySelector('[data-focused]')) {
      setFocus(target);
    }
    // startup time on a TV (launch → the first page ready for the remote), read over the web inspector
    if (!firstFocusMarked) {
      firstFocusMarked = true;
      try {
        performance.mark('tally-first-focus');
      } catch {
        // no User Timing: nothing to measure
      }
    }
  });
}
