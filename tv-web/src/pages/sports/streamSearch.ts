/**
 * "Looking for a stream…" (2.2.1 contract, never block a game): WATCH on a game without a stream asks the server to
 * search for one now (`POST games/{id}/find`), keeps asking every 3 s and plays as soon as a stream appears (from the
 * answer or from the board). After 45 s without one it says so, with "Keep looking" (another 45 s) and "OK".
 *
 * One search at a time for the whole app. The state is a store (`streamSearch`) that `<StreamSearchHost/>` draws on
 * the page on top; what happens once a stream is found is the caller's (`onFound`: play, switch in place, add to
 * multiview), called with the game carrying its new `watch`.
 *
 * Server answers: `found` plays; `searching` keeps polling; `none` (a search for it finished within the last minute
 * and found nothing) ends the first round at once; a 404 (a plugin older than 2.2.1) refreshes the board once and then
 * counts as `none`. In a "Keep looking" round the server's `none` does not end the round (it is the search the viewer
 * just heard about): the app keeps polling for the whole 45 s. The message never replaces "Looking for a stream…"
 * sooner than MIN_SEARCHING_MS after it came up, so a quick answer does not flash the dialog.
 */
import { findGameStream, type FindResult } from '../../api/tally';
import { hasStream, type TallyBoard, type TallyGame } from '../../api/tallyModels';
import { board, refreshBoard } from '../../state/sportsData';
import { createStore } from '../../util/store';

export const POLL_MS = 3000;
export const ROUND_MS = 45_000;
export const MIN_SEARCHING_MS = 1500;

export type SearchPhase = 'searching' | 'none';

export interface StreamSearchView {
  /** Identifies one search (a new search for the same game is another). */
  id: number;
  game: TallyGame;
  phase: SearchPhase;
}

/** The search on screen, or null. */
export const streamSearch = createStore<StreamSearchView | null>(null);

/** What the controller talks to (the real API and board by default; fakes in the tests). */
export interface SearchDeps {
  find: (gameId: string) => Promise<FindResult>;
  refreshBoard: () => Promise<void>;
  board: () => TallyBoard | null;
  subscribeBoard: (listener: () => void) => () => void;
}

const realDeps: SearchDeps = {
  find: findGameStream,
  refreshBoard,
  board: () => board.get(),
  subscribeBoard: (listener) => board.subscribe(listener),
};

interface Run {
  id: number;
  game: TallyGame;
  onFound: (game: TallyGame) => void;
  phase: SearchPhase;
  /** The first round (the one WATCH started): the server's `none` ends it. */
  first: boolean;
  openedAt: number;
  roundTimer: number;
  pollTimer: number;
  noneTimer: number;
  /** The server has no find endpoint: rounds poll the board instead. */
  unsupported: boolean;
  unsubscribe: () => void;
}

let deps: SearchDeps = realDeps;
let run: Run | null = null;
let seq = 0;

/** Swaps what the controller talks to (tests). `null` puts the real ones back. */
export function setSearchDeps(next: SearchDeps | null): void {
  cancelStreamSearch();
  deps = next ?? realDeps;
}

function publish(r: Run): void {
  if (run !== r) return;
  streamSearch.set({ id: r.id, game: r.game, phase: r.phase });
}

function clearTimers(r: Run): void {
  window.clearTimeout(r.roundTimer);
  window.clearTimeout(r.pollTimer);
  window.clearTimeout(r.noneTimer);
  r.roundTimer = 0;
  r.pollTimer = 0;
  r.noneTimer = 0;
}

function end(r: Run): void {
  clearTimers(r);
  r.unsubscribe();
  if (run === r) {
    run = null;
    streamSearch.set(null);
  }
}

/** The game as the board has it now, when it has a stream. */
function streamedOnBoard(gameId: string): TallyGame | null {
  const g = deps.board()?.games.find((x) => x.id === gameId) ?? null;
  return g !== null && hasStream(g) ? g : null;
}

function found(r: Run, game: TallyGame): void {
  if (run !== r) return;
  end(r);
  // after the dialog has closed and put focus back where it was: an action that opens a page leaves this one with
  // its focus where the viewer expects it on BACK
  window.setTimeout(() => r.onFound(game), 0);
}

function showNone(r: Run): void {
  if (run !== r || r.phase === 'none') return;
  clearTimers(r);
  const wait = MIN_SEARCHING_MS - (Date.now() - r.openedAt);
  if (wait > 0) {
    r.noneTimer = window.setTimeout(() => showNone(r), wait);
    return;
  }
  r.phase = 'none';
  publish(r);
}

function schedulePoll(r: Run): void {
  if (run !== r || r.phase !== 'searching') return;
  window.clearTimeout(r.pollTimer);
  r.pollTimer = window.setTimeout(() => void poll(r), POLL_MS);
}

/** One step of a round: ask the server (or, without the endpoint, the board) and act on the answer. */
async function poll(r: Run): Promise<void> {
  if (run !== r || r.phase !== 'searching') return;
  if (r.unsupported) {
    await deps.refreshBoard().catch(() => undefined);
    if (run !== r) return;
    const g = streamedOnBoard(r.game.id);
    if (g !== null) found(r, g);
    else schedulePoll(r);
    return;
  }
  let answer: FindResult | null;
  try {
    answer = await deps.find(r.game.id);
  } catch {
    answer = null; // the server did not answer: try again at the next step, until the round ends
  }
  if (run !== r || r.phase !== 'searching') return;
  if (answer !== null && answer.state === 'found' && answer.watch !== null) {
    found(r, { ...r.game, watch: answer.watch, search: null });
    return;
  }
  if (answer !== null && answer.state === 'unsupported') {
    r.unsupported = true;
    await deps.refreshBoard().catch(() => undefined);
    if (run !== r) return;
    const g = streamedOnBoard(r.game.id);
    if (g !== null) found(r, g);
    else if (r.first) showNone(r);
    else schedulePoll(r);
    return;
  }
  if (answer !== null && answer.state === 'none' && r.first) {
    const g = streamedOnBoard(r.game.id);
    if (g !== null) found(r, g);
    else showNone(r);
    return;
  }
  schedulePoll(r);
}

function startRound(r: Run): void {
  r.phase = 'searching';
  r.openedAt = Date.now();
  publish(r);
  clearTimers(r);
  r.roundTimer = window.setTimeout(() => showNone(r), ROUND_MS);
  void poll(r);
}

/**
 * WATCH (or pick) `game`: `onFound` at once when it has a stream, else after a search finds one. A search already
 * on screen is replaced.
 */
export function watchOrSearch(game: TallyGame, onFound: (game: TallyGame) => void): void {
  if (hasStream(game)) {
    onFound(game);
    return;
  }
  const onBoard = streamedOnBoard(game.id);
  if (onBoard !== null) {
    onFound(onBoard);
    return;
  }
  cancelStreamSearch();
  const r: Run = {
    id: ++seq,
    game,
    onFound,
    phase: 'searching',
    first: true,
    openedAt: Date.now(),
    roundTimer: 0,
    pollTimer: 0,
    noneTimer: 0,
    unsupported: false,
    unsubscribe: () => undefined,
  };
  run = r;
  // a stream that reaches the board (its own poll, or ours) plays at once, whatever the dialog shows
  r.unsubscribe = deps.subscribeBoard(() => {
    if (run !== r) return;
    const g = deps.board()?.games.find((x) => x.id === r.game.id) ?? null;
    if (g === null) return;
    if (hasStream(g)) found(r, g);
    else {
      r.game = g; // the dialog follows the game (its status, the server's search state)
      publish(r);
    }
  });
  startRound(r);
}

/** "Keep looking": another 45 s. */
export function keepLooking(): void {
  const r = run;
  if (r === null || r.phase !== 'none') return;
  r.first = false;
  startRound(r);
}

/** Cancel, BACK, OK on the message: nothing plays. */
export function cancelStreamSearch(): void {
  if (run !== null) end(run);
}
