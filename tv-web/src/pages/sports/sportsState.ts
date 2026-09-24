/**
 * What the Sports screens share: the multiview queue (fed by the games board, the channels grid and the live
 * player's switcher, read by the multiview page) and "watch this" for games and channels.
 */
import { session } from '../../api/jellyfin';
import type { TallyChannel, TallyGame } from '../../api/tallyModels';
import { showToast } from '../../kit/Toast';
import { push, replace, type Route } from '../../router/router';
import { setLastChannel } from '../../state/sportsData';
import { createStore } from '../../util/store';
import { matchupTitle } from '../../sports/SportsBits';

/** At most four pictures at once (TallyMultiviewState.MAX). */
export const MULTIVIEW_MAX = 4;

/** Queued channel ids, in tile order. App-wide (survives leaving Sports); a new session starts empty. */
export const multiviewQueue = createStore<string[]>([]);
session.subscribe(() => multiviewQueue.set([]));

const REGISTERING = 'That channel is still registering with Jellyfin — try again in a minute';

export type AddResult = 'added' | 'already' | 'full';

export function addToMultiview(channelId: string): AddResult {
  const ids = multiviewQueue.get();
  if (ids.indexOf(channelId) >= 0) return 'already';
  if (ids.length >= MULTIVIEW_MAX) return 'full';
  multiviewQueue.set(ids.concat(channelId));
  return 'added';
}

/** Adds and says what happened ("Added to multiview", "Already in multiview", "Multiview is full"). */
export function addToMultiviewWithNotice(channelId: string): void {
  const result = addToMultiview(channelId);
  showToast(result === 'added' ? 'Added to multiview' : result === 'already' ? 'Already in multiview' : 'Multiview is full');
}

export function removeFromMultiview(channelId: string): void {
  multiviewQueue.set(multiviewQueue.get().filter((id) => id !== channelId));
}

/** Puts `channelId` in tile `index` (when it is not queued already). */
export function replaceInMultiview(index: number, channelId: string): void {
  const ids = multiviewQueue.get();
  if (index < 0 || index >= ids.length || ids.indexOf(channelId) >= 0) return;
  const next = ids.slice();
  next[index] = channelId;
  multiviewQueue.set(next);
}

/** The live route for a game (its channel's continuous playlist), titled "Away at Home" as the tune-in card says. */
export function gameRoute(game: TallyGame): Extract<Route, { name: 'live' }> | null {
  const w = game.watch;
  if (w === null || w.hlsPath === '') return null;
  const blank = game.away.abbr === '' && game.away.shortName === '' && game.home.abbr === '' && game.home.shortName === '';
  return { name: 'live', channelId: w.channelId, hlsPath: w.hlsPath, title: blank ? w.channelName : matchupTitle(game), gameId: blank ? undefined : game.id };
}

export function channelRoute(channel: TallyChannel): Extract<Route, { name: 'live' }> | null {
  if (channel.hlsPath === '') return null;
  return { name: 'live', channelId: channel.id, hlsPath: channel.hlsPath, title: channel.now?.title ?? channel.name, gameId: channel.gameId ?? undefined };
}

/** Plays `game` on its channel (and remembers the channel, as the Android app does). `inPlace`: the player switches. */
export function watchGame(game: TallyGame, inPlace = false): void {
  const route = gameRoute(game);
  if (route === null) {
    showToast(REGISTERING);
    return;
  }
  if (inPlace) replace(route);
  else push(route);
  void setLastChannel(route.channelId);
}

export function watchChannel(channel: TallyChannel): void {
  const route = channelRoute(channel);
  if (route === null) {
    showToast(REGISTERING);
    return;
  }
  push(route);
  void setLastChannel(channel.id);
}
