/**
 * The sleep timer (the Android app's SleepTimerService, reduced to what a TV web app has): stop after N minutes, or
 * when the item playing now ends. Firing pauses the player and leaves it. It belongs to the player: leaving the
 * player (or a new player replacing it) clears it without firing, as on Android when the player is released.
 */
import { createStore } from '../../util/store';

export type SleepState = null | { endsAt: number } | { untilEnd: true };

export const sleep = createStore<SleepState>(null);

let timer = 0;
let onFire: () => void = () => undefined;

/** The player registers what firing does (pause and leave); returns the unregister. */
export function bindSleepTimer(fire: () => void): () => void {
  onFire = fire;
  return () => {
    onFire = () => undefined;
    cancelSleep();
  };
}

export function startSleep(minutes: number, now: number = Date.now()): void {
  window.clearTimeout(timer);
  const endsAt = now + minutes * 60_000;
  sleep.set({ endsAt });
  timer = window.setTimeout(fireSleep, endsAt - now);
}

export function startSleepUntilEnd(): void {
  window.clearTimeout(timer);
  sleep.set({ untilEnd: true });
}

export function cancelSleep(): void {
  window.clearTimeout(timer);
  sleep.set(null);
}

/** The item ended: a timer waiting for that fires now. */
export function sleepItemEnded(): boolean {
  const s = sleep.get();
  if (s !== null && 'untilEnd' in s) {
    fireSleep();
    return true;
  }
  return false;
}

function fireSleep(): void {
  window.clearTimeout(timer);
  sleep.set(null);
  onFire();
}

/** Time left in ms, Infinity while waiting for the end, null without a timer. */
export function sleepRemaining(s: SleepState, now: number = Date.now()): number | null {
  if (s === null) return null;
  if ('untilEnd' in s) return Infinity;
  return Math.max(0, s.endsAt - now);
}
