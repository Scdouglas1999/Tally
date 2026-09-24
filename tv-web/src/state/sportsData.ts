/**
 * The Tally board and the user's Tally settings, polled while a screen that shows them is open (Home's games row,
 * Sports, the live player). One poll for the whole app; screens subscribe with `useBoard()`.
 *
 * Events (scoring plays for the live player's banners) arrive with the board: the poll passes the highest event id
 * seen as `since` (seeded from /info's latestEventId, so old events are never replayed) and hands every newer event
 * to `onBoardEvent` listeners, as the Android app's TallyRepository does.
 */
import { useEffect } from 'preact/hooks';
import { tallyBoard, tallySettings, updateTallySettings } from '../api/tally';
import { decodeSettings, type TallyBoard, type TallyEvent, type TallySettings } from '../api/tallyModels';
import { createStore, useStore } from '../util/store';
import { tally } from './nav';

export const board = createStore<TallyBoard | null>(null);
export const boardError = createStore<string | null>(null);
export const tallyUserSettings = createStore<TallySettings | null>(null);

let users = 0;
let timer = 0;
/** Highest event id seen; null until /info is known (then its latestEventId). */
let cursor: number | null = null;
const eventListeners = new Set<(event: TallyEvent) => void>();

/** Called for every new board event, oldest first. Returns the unsubscribe function. */
export function onBoardEvent(listener: (event: TallyEvent) => void): () => void {
  eventListeners.add(listener);
  return () => eventListeners.delete(listener);
}

async function refresh(): Promise<void> {
  try {
    if (cursor === null) {
      const t = tally.get();
      if (t.kind === 'available') cursor = t.info.latestEventId;
    }
    const next = await tallyBoard(cursor ?? undefined);
    board.set(next);
    boardError.set(null);
    const events = next.events.slice().sort((a, b) => a.id - b.id);
    for (const event of events) {
      if (cursor !== null && event.id <= cursor) continue;
      cursor = event.id;
      eventListeners.forEach((l) => l(event));
    }
  } catch (e) {
    boardError.set(e instanceof Error ? e.message : 'Could not load games');
  }
}

/** Fetches the board once now (after a change the board reflects: a recording, a followed team). */
export function refreshBoard(): Promise<void> {
  return refresh();
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

const EMPTY_SETTINGS: TallySettings = { favorites: [], hideScores: false, lastChannel: null, onlyWatchable: null, favoriteTeams: [] };

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
          .catch(() => tallyUserSettings.set(EMPTY_SETTINGS));
      }
      schedule();
    }
    return () => {
      users--;
      if (users === 0) window.clearTimeout(timer);
    };
  }, [enabled]);
}

let settingsQueue: Promise<void> = Promise.resolve();

/**
 * Changes the shared settings document (read-modify-write, unknown keys kept), showing the change at once and
 * putting the old settings back if the server refuses it. Changes run one after another.
 */
export function changeTallySettings(change: (doc: Record<string, unknown>) => Record<string, unknown>): Promise<void> {
  const run = async (): Promise<void> => {
    const before = tallyUserSettings.get();
    // shown at once from what is known; the server's document (with other clients' keys) is written below
    if (before !== null) tallyUserSettings.set(decodeSettings(change({ ...before })));
    try {
      await updateTallySettings((doc) => {
        const written = change(doc);
        tallyUserSettings.set(decodeSettings(written));
        return written;
      });
    } catch {
      if (before !== null) tallyUserSettings.set(before);
      else await tallySettings().then((s) => tallyUserSettings.set(s)).catch(() => undefined);
    }
  };
  settingsQueue = settingsQueue.then(run, run);
  return settingsQueue;
}

const strings = (v: unknown): string[] => (Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : []);

/** Follows or unfollows a team ("NFL:KC"): followed teams' games come first and carry FOLLOWING. */
export function toggleFollowTeam(teamKey: string): Promise<void> {
  return changeTallySettings((doc) => {
    const current = strings(doc.favoriteTeams);
    const next = current.indexOf(teamKey) >= 0 ? current.filter((t) => t !== teamKey) : current.concat(teamKey);
    return { ...doc, favoriteTeams: next };
  });
}

export function setHideScores(hide: boolean): Promise<void> {
  return changeTallySettings((doc) => ({ ...doc, hideScores: hide }));
}

export function setOnlyWatchable(only: boolean): Promise<void> {
  return changeTallySettings((doc) => ({ ...doc, onlyWatchable: only }));
}

export function setLastChannel(channelId: string): Promise<void> {
  return changeTallySettings((doc) => ({ ...doc, lastChannel: channelId }));
}
