/**
 * What the film, series, season, episode and person pages load, with the requests upstream's view models make
 * (MovieViewModel, SeriesViewModel, EpisodeViewModel, PersonViewModel, and the Tally extras view models).
 */
import type { BaseItemDto } from '@jellyfin/sdk/lib/generated-client/models/base-item-dto';
import { BaseItemKind } from '@jellyfin/sdk/lib/generated-client/models/base-item-kind';
import { ItemFields } from '@jellyfin/sdk/lib/generated-client/models/item-fields';
import { ItemSortBy } from '@jellyfin/sdk/lib/generated-client/models/item-sort-by';
import { SortOrder } from '@jellyfin/sdk/lib/generated-client/models/sort-order';
import { getLibraryApi } from '@jellyfin/sdk/lib/utils/api/library-api';
import { getPlaylistApi } from '@jellyfin/sdk/lib/utils/api/playlist-api';
import { getShowApi } from '@jellyfin/sdk/lib/utils/api/show-api';
import { getUserDataApi } from '@jellyfin/sdk/lib/utils/api/user-data-api';
import { currentApi, session } from '../../api/jellyfin';
import { collectionEntries, nextInCollection, primaryRole, type CollectionEntry } from './detailsFormat';

function userId(): string {
  return session.get()?.userId ?? '';
}

/** Fields for cards in rows (upstream's ItemRowFields plus what the Tally cards draw). */
const ROW_FIELDS = [ItemFields.PrimaryImageAspectRatio, ItemFields.ChildCount, ItemFields.Overview];

/** The item with everything a detail page shows (Jellyfin returns the full item: people, sources, chapters). */
export async function loadItem(itemId: string): Promise<BaseItemDto> {
  return (await getLibraryApi(currentApi()).getItem({ itemId, userId: userId() })).data;
}

/** "More like this" (upstream: getSimilarItems, 25). */
export async function loadSimilar(itemId: string): Promise<BaseItemDto[]> {
  const r = await getLibraryApi(currentApi()).getSimilarItems({ itemId, userId: userId(), limit: 25, fields: ROW_FIELDS });
  return r.data.Items ?? [];
}

/** Special features (the extras row) and local trailers. */
export async function loadSpecialFeatures(itemId: string): Promise<BaseItemDto[]> {
  return (await getLibraryApi(currentApi()).getSpecialFeatures({ itemId, userId: userId() })).data;
}

export async function loadLocalTrailers(itemId: string): Promise<BaseItemDto[]> {
  return (await getLibraryApi(currentApi()).getLocalTrailers({ itemId, userId: userId() })).data;
}

/** The server's Next Up episode of one series (what the series page's primary button plays). */
export async function loadNextUp(seriesId: string): Promise<BaseItemDto | null> {
  const r = await getShowApi(currentApi()).getNextUp({
    seriesId,
    userId: userId(),
    fields: [ItemFields.MediaSources],
    enableUserData: true,
    enableTotalRecordCount: false,
  });
  return r.data.Items?.[0] ?? null;
}

/** A series' seasons in order, with ChildCount so the server fills in the share watched. */
export async function loadSeasons(seriesId: string): Promise<BaseItemDto[]> {
  const r = await getLibraryApi(currentApi()).getItems({
    userId: userId(),
    parentId: seriesId,
    recursive: false,
    includeItemTypes: [BaseItemKind.Season],
    sortBy: [ItemSortBy.IndexNumber],
    sortOrder: [SortOrder.Ascending],
    fields: [ItemFields.ChildCount, ItemFields.PrimaryImageAspectRatio],
    enableUserData: true,
  });
  return r.data.Items ?? [];
}

/** A season's episodes (upstream's loadEpisodesInternal fields, plus People for the rundown's guest stars). */
export async function loadEpisodes(seriesId: string, seasonId: string): Promise<BaseItemDto[]> {
  const r = await getShowApi(currentApi()).getEpisodes({
    seriesId,
    seasonId,
    userId: userId(),
    fields: [ItemFields.MediaSources, ItemFields.MediaSourceCount, ItemFields.Overview, ItemFields.PrimaryImageAspectRatio, ItemFields.CanDelete, ItemFields.People],
    enableUserData: true,
    sortBy: ItemSortBy.IndexNumber,
  });
  return r.data.Items ?? [];
}

export async function setPlayed(itemId: string, played: boolean): Promise<void> {
  const api = getUserDataApi(currentApi());
  if (played) await api.markPlayedItem({ itemId, userId: userId() });
  else await api.markUnplayedItem({ itemId, userId: userId() });
}

export async function setFavorite(itemId: string, favorite: boolean): Promise<void> {
  const api = getUserDataApi(currentApi());
  if (favorite) await api.markFavoriteItem({ itemId, userId: userId() });
  else await api.unmarkFavoriteItem({ itemId, userId: userId() });
}

export async function deleteItem(itemId: string): Promise<void> {
  await getLibraryApi(currentApi()).deleteItem({ itemId });
}

// ---- the next film of a collection (Android CollectionNext: an index of films by TMDb collection, 10 minutes) ----

const INDEX_TTL_MS = 10 * 60 * 1000;
const PAGE = 1000;
let index: { key: string; at: number; entries: CollectionEntry[] } | null = null;

async function collectionIndex(): Promise<CollectionEntry[]> {
  const s = session.get();
  const key = (s?.serverUrl ?? '') + '|' + (s?.userId ?? '');
  if (index !== null && index.key === key && Date.now() - index.at < INDEX_TTL_MS) return index.entries;
  const all: BaseItemDto[] = [];
  for (let start = 0; ; start += PAGE) {
    const r = await getLibraryApi(currentApi()).getItems({
      userId: userId(),
      startIndex: start,
      limit: PAGE,
      recursive: true,
      includeItemTypes: [BaseItemKind.Movie],
      fields: [ItemFields.ProviderIds, ItemFields.SortName],
      enableImages: false,
      enableUserData: false,
    });
    const items = r.data.Items ?? [];
    for (const i of items) all.push(i);
    if (items.length < PAGE) break;
    if ((r.data.TotalRecordCount ?? 0) > 0 && start + items.length >= (r.data.TotalRecordCount ?? 0)) break;
  }
  const entries = collectionEntries(all);
  index = { key, at: Date.now(), entries };
  return entries;
}

/** The film that follows `film` in its TMDb collection, or null (not in one, or the last of it). */
export async function loadCollectionNext(film: BaseItemDto): Promise<BaseItemDto | null> {
  if (film.Type !== 'Movie' || film.Id == null) return null;
  let entries = await collectionIndex();
  // the film's own collection id wins over the index (it may be newer)
  const own = film.ProviderIds?.['TmdbCollection'];
  if (own != null && own.trim() !== '' && !entries.some((e) => e.id === film.Id && e.collectionId === own)) {
    entries = entries.filter((e) => e.id !== film.Id).concat(collectionEntries([film]));
  }
  const nextId = nextInCollection(entries, film.Id);
  return nextId === null ? null : loadItem(nextId);
}

// ---- people ---------------------------------------------------------------------------------------------------

/** One row of a person's credits, newest first (upstream's PersonViewModel). */
export async function loadCredits(personId: string, type: 'Movie' | 'Series' | 'Episode'): Promise<BaseItemDto[]> {
  const r = await getLibraryApi(currentApi()).getItems({
    userId: userId(),
    personIds: [personId],
    includeItemTypes: [type as BaseItemKind],
    recursive: true,
    sortBy: [ItemSortBy.PremiereDate, ItemSortBy.ProductionYear, ItemSortBy.SortName],
    sortOrder: [SortOrder.Descending, SortOrder.Descending, SortOrder.Ascending],
    fields: ROW_FIELDS,
    enableUserData: true,
  });
  return r.data.Items ?? [];
}

const ROLE_SAMPLE = 40;

/** The person's usual credit (the page kicker), read from the People of up to 40 items they appear in. */
export async function loadPrimaryRole(personId: string): Promise<string | null> {
  const r = await getLibraryApi(currentApi()).getItems({
    userId: userId(),
    personIds: [personId],
    includeItemTypes: [BaseItemKind.Movie, BaseItemKind.Series, BaseItemKind.Episode],
    recursive: true,
    fields: [ItemFields.People],
    limit: ROLE_SAMPLE,
    enableTotalRecordCount: false,
  });
  const credits: string[] = [];
  for (const item of r.data.Items ?? []) {
    for (const p of item.People ?? []) if (p.Id === personId && p.Type !== undefined) credits.push(p.Type);
  }
  return primaryRole(credits);
}

// ---- playlists (the menu's Add to playlist) --------------------------------------------------------------------

/** The user's playlists, by name. */
export async function loadPlaylists(): Promise<BaseItemDto[]> {
  const r = await getLibraryApi(currentApi()).getItems({
    userId: userId(),
    recursive: true,
    includeItemTypes: [BaseItemKind.Playlist],
    sortBy: [ItemSortBy.SortName],
    sortOrder: [SortOrder.Ascending],
    enableImages: false,
  });
  return r.data.Items ?? [];
}

export async function addToPlaylist(playlistId: string, itemId: string): Promise<void> {
  await getPlaylistApi(currentApi()).addItemToPlaylist({ playlistId, ids: [itemId], userId: userId() });
}
