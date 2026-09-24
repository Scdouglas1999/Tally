/**
 * The Tally board and the user's Tally settings, polled while a screen that shows them is open (Home's games row,
 * Sports). One poll for the whole app; screens subscribe with `useBoard()`.
 */
import { useEffect } from 'preact/hooks';
import { tallyBoard, tallySettings } from '../api/tally';
import type { TallyBoard, TallySettings } from '../api/tallyModels';
import { createStore, useStore } from '../util/store';
import { tally } from './nav';

export const board = createStore<TallyBoard | null>(null);
export const boardError = createStore<string | null>(null);
export const tallyUserSettings = createStore<TallySettings | null>(null);

let users = 0;
let timer = 0;

async function refresh(): Promise<void> {
  try {
    board.set(await tallyBoard());
    boardError.set(null);
  } catch (e) {
    boardError.set(e instanceof Error ? e.message : 'Could not load games');
  }
}

function pollSeconds(): number {
  const t = tally.get();
  return t.kind === 'available' ? Math.max(5, t.info.pollSeconds) : 15;
}

function schedule(): void {
  window.clearTimeout(timer);
  timer = window.setTimeout(() => {
    void refresh().then(() => {
      if (users > 0) schedule();
    });
  }, pollSeconds() * 1000);
}

/** Keeps the board fresh while `active` (the calling page is on screen and the plugin is there). */
export function useBoardPolling(active: boolean): void {
  const plugin = useStore(tally);
  const enabled = active && plugin.kind === 'available';
  useEffect(() => {
    if (!enabled) return undefined;
    users++;
    if (users === 1) {
      void refresh();
      if (tallyUserSettings.get() === null) {
        tallySettings()
          .then((s) => tallyUserSettings.set(s))
          .catch(() => tallyUserSettings.set({ favorites: [], hideScores: false, lastChannel: null, onlyWatchable: null, favoriteTeams: [] }));
      }
      schedule();
    }
    return () => {
      users--;
      if (users === 0) window.clearTimeout(timer);
    };
  }, [enabled]);
}
