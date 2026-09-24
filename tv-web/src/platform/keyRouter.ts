/**
 * One keydown listener for the whole app. A key goes, in order, to:
 *  1. the text field being typed in (characters, Backspace, left/right inside the text);
 *  2. key handlers registered with `useKeyHandler`, newest first (a dialog, the player, a page's own BACK);
 *     a handler returns true when it used the key;
 *  3. arrows and OK: the focus system; BACK: the router (previous page, then the app's root behavior).
 */
import { useEffect, useRef } from 'preact/hooks';
import { navigate, navigateRelease } from '../focus/focus';
import { mapKey, type Key } from './keys';
import type { Platform } from './platform';

export type KeyHandler = (key: Key, event: KeyboardEvent) => boolean;

const handlers: Array<{ handler: { current: KeyHandler } }> = [];
let fallbackBack: () => void = () => undefined;

const NAV: Partial<Record<Key, 'left' | 'right' | 'up' | 'down' | 'enter'>> = {
  left: 'left', right: 'right', up: 'up', down: 'down', enter: 'enter',
};

function typingIn(target: EventTarget | null): HTMLInputElement | null {
  return target instanceof HTMLInputElement ? target : null;
}

/** Keys a focused text field keeps for itself. */
function fieldKeeps(input: HTMLInputElement, key: Key | null, event: KeyboardEvent): boolean {
  if (event.key === 'Backspace' || event.key === 'Delete') return true;
  if (key === null) return true; // characters
  if (/^[0-9]$/.test(key) && event.key.length === 1) return true;
  const start = input.selectionStart ?? 0;
  const end = input.selectionEnd ?? 0;
  if (key === 'left') return start > 0 || end > 0;
  if (key === 'right') return end < input.value.length;
  return false;
}

export function installKeyRouter(platform: Platform, onRootBack: () => void): void {
  fallbackBack = onRootBack;
  window.addEventListener(
    'keydown',
    (event) => {
      const key = mapKey({ keyCode: event.keyCode, key: event.key }, platform.name, platform.runtimeKeys);
      const input = typingIn(event.target);
      if (input !== null && fieldKeeps(input, key, event)) return;
      if (key === null) return;
      for (let i = handlers.length - 1; i >= 0; i--) {
        const entry = handlers[i];
        if (entry !== undefined && entry.handler.current(key, event)) {
          event.preventDefault();
          return;
        }
      }
      const nav = NAV[key];
      if (nav !== undefined) {
        event.preventDefault();
        navigate(nav, event);
        return;
      }
      if (key === 'back') {
        event.preventDefault();
        fallbackBack();
      }
    },
    true,
  );
  window.addEventListener(
    'keyup',
    (event) => {
      const key = mapKey({ keyCode: event.keyCode, key: event.key }, platform.name, platform.runtimeKeys);
      const nav = key === null ? undefined : NAV[key];
      if (nav !== undefined) navigateRelease(nav);
    },
    true,
  );
}

/** Registers `handler` while `enabled` (newest registration first). */
export function useKeyHandler(handler: KeyHandler, enabled = true): void {
  const ref = useRef(handler);
  ref.current = handler;
  useEffect(() => {
    if (!enabled) return undefined;
    const entry = { handler: ref };
    handlers.push(entry);
    return () => {
      const i = handlers.indexOf(entry);
      if (i >= 0) handlers.splice(i, 1);
    };
  }, [enabled]);
}

/** Shorthand: BACK handled by `onBack` while `enabled`. */
export function useBack(onBack: () => void, enabled = true): void {
  useKeyHandler((key) => {
    if (key !== 'back') return false;
    onBack();
    return true;
  }, enabled);
}
