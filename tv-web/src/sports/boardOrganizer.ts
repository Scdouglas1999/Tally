import { isFollowed, isLive, type TallyGame } from '../api/tallyModels';
import { stableSort } from './homeRow';

/** One horizontally scrolling row on the games board: a league + state group ("NFL / LIVE"), keyed `league|state`. */
export interface BoardRow {
  key: string;
  league: string;
  state: string;
  games: TallyGame[];
}

/** The row state of postponed and canceled games. */
export const POSTPONED = 'postponed';

const POSTPONED_DETAIL = /postponed|canceled|cancelled/i;

/** The row `game` is listed in: its own state, or POSTPONED for a postponed or canceled game. */
export function rowState(game: TallyGame): string {
  return game.state === 'post' && POSTPONED_DETAIL.test(game.detail) ? POSTPONED : game.state;
}

function stateOrder(state: string): number {
  switch (state) {
    case 'in':
      return 0;
    case 'pre':
      return 1;
    case 'post':
      return 2;
    case POSTPONED:
      return 3;
    default:
      return 4;
  }
}

function compareStart(a: string, b: string): number {
  const l = Date.parse(a);
  const r = Date.parse(b);
  if (!isNaN(l) && !isNaN(r)) return l - r;
  return a < b ? -1 : a > b ? 1 : 0;
}

/**
 * Groups and orders the board's games (port of the Android app's BoardOrganizer; keep the two in step).
 *
 * Board order: live before upcoming before final before postponed; within a state, rows that contain a favorite game
 * first, then leagues alphabetically. Within a row: favorites (followed teams, favorite channels) first, then by start
 * time (newest first for final and postponed games). Postponed and canceled games (the feed reports them as `post`)
 * get their own rows ("MLB / POSTPONED") after the finals: they are not results.
 */
export function boardRows(
  games: readonly TallyGame[],
  favoriteChannelIds: ReadonlySet<string>,
  onlyWatchable: boolean,
  favoriteTeams: ReadonlySet<string> = new Set(),
): BoardRow[] {
  const favorite = (g: TallyGame): boolean => isFollowed(g, favoriteTeams) || (g.watch !== null && favoriteChannelIds.has(g.watch.channelId));
  const visible = onlyWatchable ? games.filter((g) => g.watch !== null) : games.slice();
  const groups: BoardRow[] = [];
  const byKey: Record<string, BoardRow> = {};
  for (const g of visible) {
    const state = rowState(g);
    const key = g.league + '|' + state;
    let row = byKey[key];
    if (row === undefined) {
      row = { key, league: g.league, state, games: [] };
      byKey[key] = row;
      groups.push(row);
    }
    row.games.push(g);
  }
  for (const row of groups) {
    const newestFirst = row.state === 'post' || row.state === POSTPONED;
    row.games = stableSort(row.games, (a, b) => {
      const fa = favorite(a) ? 1 : 0;
      const fb = favorite(b) ? 1 : 0;
      if (fa !== fb) return fb - fa;
      const byStart = compareStart(a.start, b.start);
      return newestFirst ? -byStart : byStart;
    });
  }
  return stableSort(groups, (a, b) => {
    const so = stateOrder(a.state) - stateOrder(b.state);
    if (so !== 0) return so;
    const fa = a.games.some(favorite) ? 1 : 0;
    const fb = b.games.some(favorite) ? 1 : 0;
    if (fa !== fb) return fb - fa;
    return a.league < b.league ? -1 : a.league > b.league ? 1 : 0;
  });
}

/** The first live game shown on the given channel, if any. */
export function gameForChannel(channelId: string, games: readonly TallyGame[]): TallyGame | null {
  return games.find((g) => isLive(g) && g.watch !== null && g.watch.channelId === channelId) ?? null;
}
