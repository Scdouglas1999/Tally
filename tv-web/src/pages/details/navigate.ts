/**
 * Where an item opens (Android BaseItem.destination()): films, series, people and the rest their detail page;
 * episodes the season rundown on that episode; seasons the rundown on that season; extras and trailers play.
 * Home, the libraries and every row of the detail pages open items through here.
 */
import type { BaseItemDto } from '@jellyfin/sdk/lib/generated-client/models/base-item-dto';
import { push, type Route } from '../../router/router';
import { positionMs } from './detailsFormat';

export function detailsRoute(item: BaseItemDto): Route | null {
  if (item.Id == null) return null;
  if (item.ExtraType != null || item.Type === 'Trailer') return { name: 'player', itemId: item.Id, startMs: 0 };
  if (item.Type === 'Episode' && item.SeriesId != null && item.SeasonId != null) {
    return { name: 'season', seriesId: item.SeriesId, seasonId: item.SeasonId, episodeId: item.Id };
  }
  if (item.Type === 'Season' && item.SeriesId != null) return { name: 'season', seriesId: item.SeriesId, seasonId: item.Id };
  return { name: 'item', itemId: item.Id };
}

/** OK on a card: the item's page (or the rundown, or playback for extras). */
export function openDetails(item: BaseItemDto): void {
  const route = detailsRoute(item);
  if (route !== null) push(route);
}

/** An item's own page, even for an episode (the rundown's Go to). */
export function openItemPage(itemId: string): void {
  push({ name: 'item', itemId });
}

/** Plays from the resume point (`fromStart` false) or from the beginning. */
export function playItem(item: BaseItemDto, fromStart = false): void {
  if (item.Id == null) return;
  push({ name: 'player', itemId: item.Id, startMs: fromStart ? 0 : positionMs(item) });
}

/** Playable kinds (the PLAY key on a card, the Play entries of a menu). */
export function isPlayable(item: BaseItemDto): boolean {
  return item.Type === 'Movie' || item.Type === 'Episode' || item.Type === 'Video' || item.Type === 'MusicVideo' || item.Type === 'Trailer' || item.ExtraType != null;
}
