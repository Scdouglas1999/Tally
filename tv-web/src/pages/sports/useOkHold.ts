import { useEffect, useRef } from 'preact/hooks';
import { navigate } from '../../focus/focus';
import { isRepeat, useKeyHandler } from '../../platform/keyRouter';

/** How long OK must be held to count as a hold (the Android TV long press is ~500 ms). */
export const HOLD_MS = 500;

/**
 * A key-down arriving this soon after the last one while OK is down is the remote's auto-repeat (Tizen does not
 * flag repeats reliably); a later one is a new press whose key-up was lost.
 */
const REPEAT_GAP_MS = 700;

/**
 * After a hold has fired, OK's events are swallowed until they have been quiet this long. The Tizen emulator (and
 * X-style remotes) repeat a held key as key-up/key-down pairs a few ms apart, which otherwise read as new short
 * presses: one of them picked the first row of the menu the hold had just opened (measured: pairs every ~40 ms, the
 * first key-up 660 ms after the key-down).
 */
const QUIET_MS = 300;

/**
 * HOLD OK, as on the Android TV app (a long press opens a game's actions, adds a channel to multiview). OK is
 * delivered on key-up instead of key-down while this is enabled: a short press becomes the normal OK (the focused
 * element's onEnter), a press held for HOLD_MS calls `onHold` (return false when the focused thing has no hold: the
 * press then acts as OK). The remote keeps repeating the key while it is held; those repeats are swallowed until the
 * key goes up, so a menu that opened under the finger does not pick its first row. MENU and INFO also call `onHold`
 * (remotes and keyboards that have them). `active`: the page is on screen (a hidden page never takes keys);
 * `enabled`: holds start (off while a dialog is open, whose rows take OK at once).
 */
export function useOkHold(onHold: () => boolean, enabled: boolean, active: boolean): void {
  const cb = useRef(onHold);
  cb.current = onHold;
  const s = useRef({ down: false, lastDownAt: 0, timer: 0, held: false, swallow: false, lastEnterAt: 0 });

  useKeyHandler((key, event) => {
    const st = s.current;
    if (key === 'menu' || key === 'info') return enabled ? cb.current() : false;
    if (key !== 'enter') return false;
    const now = Date.now();
    if (st.swallow) {
      if (now - st.lastEnterAt < QUIET_MS) {
        st.lastEnterAt = now;
        return true; // still the held press repeating
      }
      st.swallow = false;
    }
    if (st.down && (isRepeat(event) || now - st.lastDownAt < REPEAT_GAP_MS)) {
      st.lastDownAt = now;
      return true; // auto-repeat while held
    }
    if (!enabled) {
      st.down = false;
      return false;
    }
    st.down = true;
    st.held = false;
    st.lastDownAt = now;
    window.clearTimeout(st.timer);
    st.timer = window.setTimeout(() => {
      st.timer = 0;
      st.held = true;
      st.swallow = true;
      st.lastEnterAt = Date.now();
      if (!cb.current()) navigate('enter', event);
    }, HOLD_MS);
    return true;
  }, active);

  useEffect(() => {
    const up = (event: KeyboardEvent): void => {
      const st = s.current;
      if (event.keyCode !== 13 && event.key !== 'Enter') return;
      if (st.swallow) st.lastEnterAt = Date.now();
      if (!st.down) return;
      st.down = false;
      if (st.timer !== 0) {
        window.clearTimeout(st.timer);
        st.timer = 0;
        navigate('enter', event);
      }
    };
    window.addEventListener('keyup', up, true);
    return () => {
      window.removeEventListener('keyup', up, true);
      window.clearTimeout(s.current.timer);
    };
  }, []);
}
