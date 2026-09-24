import { isFollowed, isLive, isUpcoming, type TallyBoard, type TallyGame } from '../api/tallyModels';

/**
 * What the Tally row on Home shows, in order (port of the Android app's HomeRowSelection +
 * withFollowedTeamsFirst; keep the two in step):
 *  - live games, then games starting within 12 hours (or due up to 1 hour ago: feeds flip to "in" late);
 *  - games with no channel yet still get a card ("not on your channels");
 *  - in each group: watchable first, then favorite channels, then league, then start time;
 *  - at most 10 cards, never cutting a watchable game for one that is not;
 *  - followed teams first inside the live and the later group.
 */
export const UPCOMING_WINDOW_MS = 12 * 3600_000;
export const START_GRACE_MS = 3600_000;
export const MAX_GAMES = 10;

function startMs(start: string): number | null {
  const t = Date.parse(start);
  return isNaN(t) ? null : t;
}

function compareStart(a: string, b: string): number {
  const l = startMs(a);
  const r = startMs(b);
  if (l !== null && r !== null) return l - r;
  return a < b ? -1 : a > b ? 1 : 0;
}

function order(favorites: ReadonlySet<string>): (a: TallyGame, b: TallyGame) => number {
  return (a, b) => {
    const wa = a.watch !== null ? 1 : 0;
    const wb = b.watch !== null ? 1 : 0;
    if (wa !== wb) return wb - wa;
    const fa = a.watch !== null && favorites.has(a.watch.channelId) ? 1 : 0;
    const fb = b.watch !== null && favorites.has(b.watch.channelId) ? 1 : 0;
    if (fa !== fb) return fb - fa;
    if (a.league !== b.league) return a.league < b.league ? -1 : 1;
    return compareStart(a.start, b.start);
  };
}

/** Array.prototype.sort is stable from Chrome 70; TVs run 68-69, so sort with the index as the last key. */
export function stableSort<T>(items: readonly T[], compare: (a: T, b: T) => number): T[] {
  return items
    .map((item, index) => ({ item, index }))
    .sort((a, b) => compare(a.item, b.item) || a.index - b.index)
    .map((x) => x.item);
}

export function selectHomeGames(
  board: TallyBoard | null,
  favorites: ReadonlySet<string>,
  followedTeams: ReadonlySet<string>,
  now: number,
): TallyGame[] {
  const games = board?.games ?? [];
  if (games.length === 0) return [];
  const cmp = order(favorites);
  const soon = (g: TallyGame): boolean => {
    const t = startMs(g.start);
    return t !== null && t <= now + UPCOMING_WINDOW_MS && t >= now - START_GRACE_MS;
  };
  const live = stableSort(games.filter(isLive), cmp);
  const later = stableSort(games.filter((g) => isUpcoming(g) && soon(g)), cmp);
  const candidates = live.concat(later);
  const watchable = candidates.filter((g) => g.watch !== null);
  const dark = candidates.filter((g) => g.watch === null);
  const kept = new Set(watchable.concat(dark).slice(0, MAX_GAMES));
  const selected = candidates.filter((g) => kept.has(g));
  if (followedTeams.size === 0) return selected;
  const teams = new Set(Array.from(followedTeams, (t) => t.toUpperCase()));
  const pin = (list: TallyGame[]): TallyGame[] =>
    stableSort(list, (a, b) => (isFollowed(b, teams) ? 1 : 0) - (isFollowed(a, teams) ? 1 : 0));
  return pin(selected.filter(isLive)).concat(pin(selected.filter((g) => !isLive(g))));
}
