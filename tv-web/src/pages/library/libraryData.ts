/**
 * The library pages' Jellyfin requests (the same ones the Android app's view models make): a grid's items page by
 * page, its count, where a letter starts, a random item, the values a filter can take, the genres and studios with
 * their pictures, and the Recommended rows.
 */
import type { BaseItemDto } from '@jellyfin/sdk/lib/generated-client/models/base-item-dto';
import type { BaseItemKind } from '@jellyfin/sdk/lib/generated-client/models/base-item-kind';
import { ImageType } from '@jellyfin/sdk/lib/generated-client/models/image-type';
import { ItemFields } from '@jellyfin/sdk/lib/generated-client/models/item-fields';
import { ItemSortBy } from '@jellyfin/sdk/lib/generated-client/models/item-sort-by';
import { SortOrder } from '@jellyfin/sdk/lib/generated-client/models/sort-order';
import { getArtistApi } from '@jellyfin/sdk/lib/utils/api/artist-api';
import { getFilterApi } from '@jellyfin/sdk/lib/utils/api/filter-api';
import { getGenreApi } from '@jellyfin/sdk/lib/utils/api/genre-api';
import { getLibraryApi } from '@jellyfin/sdk/lib/utils/api/library-api';
import { getLocalizationApi } from '@jellyfin/sdk/lib/utils/api/localization-api';
import { getShowApi } from '@jellyfin/sdk/lib/utils/api/show-api';
import { getStudioApi } from '@jellyfin/sdk/lib/utils/api/studio-api';
import { getYearApi } from '@jellyfin/sdk/lib/utils/api/year-api';
import { itemImage } from '../../api/images';
import { currentApi, session } from '../../api/jellyfin';
import {
  VIDEO_KINDS,
  filterParams,
  letterIndex,
  letterQuery,
  mixSuggestions,
  sortParams,
  suggestionLimits,
  type FilterKind,
  type FilterValue,
  type FolderSpec,
  type LibraryFilter,
  type SortAndDirection,
} from './libraryModel';

/** Upstream's SlimItemFields, plus what the cards' details and the header need. */
const FIELDS = [
  ItemFields.Overview,
  ItemFields.SortName,
  ItemFields.MediaSourceCount,
  ItemFields.CanDelete,
  ItemFields.ChildCount,
  ItemFields.PrimaryImageAspectRatio,
];

const IMAGE_TYPES = [ImageType.Primary, ImageType.Thumb, ImageType.Backdrop, ImageType.Logo];

function userId(): string {
  return session.get()?.userId ?? '';
}

export interface Page {
  items: BaseItemDto[];
  total: number;
}

/** One page of a grid's items (and the list's length). */
export async function fetchPage(spec: FolderSpec, sort: SortAndDirection, filter: LibraryFilter, startIndex: number, limit: number): Promise<Page> {
  const order = sortParams(sort, spec.movieLibrary);
  const p = filterParams(filter);
  if (spec.artists === true) {
    const r = (
      await getArtistApi(currentApi()).getArtists({
        userId: userId(),
        parentId: spec.itemId,
        startIndex,
        limit,
        fields: FIELDS,
        enableImageTypes: IMAGE_TYPES,
        sortBy: order.sortBy,
        sortOrder: order.sortOrder,
        isFavorite: p.isFavorite,
        genreIds: p.genreIds,
        minCommunityRating: p.minCommunityRating,
        studioIds: p.studioIds,
        officialRatings: p.officialRatings,
        years: p.years,
        enableUserData: true,
        enableTotalRecordCount: true,
      })
    ).data;
    return { items: r.Items ?? [], total: r.TotalRecordCount ?? 0 };
  }
  const r = (
    await getLibraryApi(currentApi()).getItems({
      userId: userId(),
      parentId: spec.itemId,
      recursive: spec.recursive,
      excludeItemIds: [spec.itemId],
      startIndex,
      limit,
      fields: FIELDS,
      enableImageTypes: IMAGE_TYPES,
      enableUserData: true,
      enableTotalRecordCount: true,
      ...order,
      ...p,
    })
  ).data;
  return { items: r.Items ?? [], total: r.TotalRecordCount ?? 0 };
}

/** The index of the first item filed under `letter` (the A-Z bar), or -1. */
export async function positionOfLetter(spec: FolderSpec, sort: SortAndDirection, filter: LibraryFilter, letter: string, total: number): Promise<number> {
  const q = letterQuery(letter, sort.direction);
  if (q === null) return 0;
  const p = filterParams(filter);
  let count: number;
  if (spec.artists === true) {
    count =
      (
        await getArtistApi(currentApi()).getArtists({
          userId: userId(), parentId: spec.itemId, nameLessThan: q.nameLessThan, limit: 0, enableTotalRecordCount: true,
          isFavorite: p.isFavorite, genreIds: p.genreIds, studioIds: p.studioIds, years: p.years,
        })
      ).data.TotalRecordCount ?? 0;
  } else {
    count =
      (
        await getLibraryApi(currentApi()).getItems({
          userId: userId(), parentId: spec.itemId, recursive: spec.recursive, excludeItemIds: [spec.itemId],
          nameLessThan: q.nameLessThan, limit: 0, enableTotalRecordCount: true, enableUserData: false,
          ...sortParams(sort, spec.movieLibrary), ...p,
        })
      ).data.TotalRecordCount ?? 0;
  }
  return total > 0 ? letterIndex(count, total, q.fromEnd) : -1;
}

/** A random item of the grid, as it is filtered (the dice control). */
export async function randomItem(spec: FolderSpec, filter: LibraryFilter): Promise<BaseItemDto | null> {
  const r = await fetchPage(spec, { sort: ItemSortBy.Random, direction: SortOrder.Ascending }, filter, 0, 1);
  return r.items[0] ?? null;
}

/** Upstream's Playlist.MAX_SIZE: Play all and Shuffle queue at most this many. */
export const PLAY_ALL_MAX = 100;

/** What Play all (the grid's order) or Shuffle (a random order) plays: the first PLAY_ALL_MAX items. */
export async function playAllItems(spec: FolderSpec, sort: SortAndDirection, filter: LibraryFilter, shuffle: boolean): Promise<BaseItemDto[]> {
  const order = shuffle ? { sort: ItemSortBy.Random, direction: SortOrder.Ascending } : sort;
  return (await fetchPage(spec, order, filter, 0, PLAY_ALL_MAX)).items;
}

// ---------------------------------------------------------------------------------------------------------------
// Filter values (upstream's FilterOptionCache: an hour)
// ---------------------------------------------------------------------------------------------------------------

const valueCache = new Map<string, { at: number; values: FilterValue[] }>();
const HOUR = 3600_000;

async function loadFilterValues(kind: FilterKind, parentId: string): Promise<FilterValue[]> {
  const api = currentApi();
  switch (kind) {
    case 'genres':
      return ((await getGenreApi(api).getGenres({ parentId, userId: userId() })).data.Items ?? []).map((g) => ({ name: g.Name ?? '', value: g.Id ?? '' }));
    case 'studios':
      return ((await getStudioApi(api).getStudios({ parentId, userId: userId(), includeItemTypes: ['Series' as BaseItemKind] })).data.Items ?? []).map((s) => ({
        name: s.Name ?? '',
        value: s.Id ?? '',
      }));
    case 'played':
    case 'favorite':
      return [
        { name: 'True', value: true },
        { name: 'False', value: false },
      ];
    case 'officialRating': {
      const [ratings, filters] = await Promise.all([
        getLocalizationApi(api).getParentalRatings(),
        getFilterApi(api).getQueryFiltersLegacy({ userId: userId(), parentId }),
      ]);
      const value = new Map<string, number>();
      for (const r of ratings.data) if (r.Name != null) value.set(r.Name, r.Value ?? 0);
      const list = (filters.data.OfficialRatings ?? []).map((name) => ({ name, rank: value.get(name) ?? 0 }));
      // stable by rank (Array#sort is stable only from Chromium 70)
      return list
        .map((x, i) => ({ ...x, i }))
        .sort((a, b) => a.rank - b.rank || a.i - b.i)
        .map((x) => ({ name: x.name, value: x.name }));
    }
    case 'video':
      return VIDEO_KINDS.map((k) => ({ name: k, value: k }));
    case 'year':
    case 'decade': {
      const years = ((await getYearApi(api).getYears({ parentId, userId: userId(), sortBy: [ItemSortBy.SortName], sortOrder: [SortOrder.Ascending] })).data.Items ?? [])
        .map((y) => parseInt(y.Name ?? '', 10))
        .filter((y) => !isNaN(y));
      if (kind === 'year') return years.map((y) => ({ name: String(y), value: y }));
      const decades: number[] = [];
      for (const y of years) {
        const d = Math.floor(y / 10) * 10;
        if (decades.indexOf(d) < 0) decades.push(d);
      }
      decades.sort((a, b) => a - b);
      return decades.map((d) => ({ name: `${d}'s`, value: d }));
    }
    case 'communityRating':
      return [1, 2, 3, 4, 5, 6, 7, 8, 9, 10].map((n) => ({ name: String(n), value: n }));
  }
}

export async function filterValues(kind: FilterKind, parentId: string): Promise<FilterValue[]> {
  const key = `${userId()}|${parentId}|${kind}`;
  const hit = valueCache.get(key);
  if (hit !== undefined && Date.now() - hit.at < HOUR) return hit.values;
  const values = await loadFilterValues(kind, parentId);
  valueCache.set(key, { at: Date.now(), values });
  return values;
}

// ---------------------------------------------------------------------------------------------------------------
// Genres and studios
// ---------------------------------------------------------------------------------------------------------------

export interface NameCell {
  id: string;
  name: string;
  imageUrl: string | null;
}

/** A library's genres (its films, shows or albums). Pictures come separately: see genreImages. */
export async function fetchGenres(libraryId: string, includeItemTypes: BaseItemKind[] | undefined): Promise<NameCell[]> {
  const r = await getGenreApi(currentApi()).getGenres({ userId: userId(), parentId: libraryId, includeItemTypes, fields: [ItemFields.SortName] });
  return (r.data.Items ?? []).map((g) => ({ id: g.Id ?? '', name: g.Name ?? '', imageUrl: null }));
}

const genreImageCache = new Map<string, { at: number; urls: Record<string, string | null> }>();

/** A picture per genre: the backdrop of a random item of it (upstream's getGenreImageMap, two hours, 4 at a time). */
export async function genreImages(libraryId: string, includeItemTypes: BaseItemKind[] | undefined, genreIds: readonly string[], width: number): Promise<Record<string, string | null>> {
  const key = `${userId()}|${libraryId}`;
  const hit = genreImageCache.get(key);
  if (hit !== undefined && Date.now() - hit.at < 2 * HOUR) return hit.urls;
  const urls: Record<string, string | null> = {};
  const queue = genreIds.slice();
  const worker = async (): Promise<void> => {
    for (let id = queue.shift(); id !== undefined; id = queue.shift()) {
      try {
        const r = await getLibraryApi(currentApi()).getItems({
          userId: userId(), parentId: libraryId, recursive: true, limit: 1, sortBy: [ItemSortBy.Random], fields: [ItemFields.Genres],
          imageTypes: [ImageType.Backdrop], imageTypeLimit: 1, includeItemTypes, genreIds: [id], enableTotalRecordCount: false,
        });
        const item = r.data.Items?.[0];
        const tag = item?.BackdropImageTags?.[0];
        urls[id] = item?.Id != null && tag !== undefined ? itemImage(item.Id, ImageType.Backdrop, tag, width) : null;
      } catch {
        urls[id] = null;
      }
    }
  };
  await Promise.all([worker(), worker(), worker(), worker()]);
  genreImageCache.set(key, { at: Date.now(), urls });
  return urls;
}

/** A TV library's studios (networks) with their thumbs. */
export async function fetchStudios(libraryId: string, includeItemTypes: BaseItemKind[] | undefined, width: number): Promise<NameCell[]> {
  const r = await getStudioApi(currentApi()).getStudios({ userId: userId(), parentId: libraryId, includeItemTypes, fields: [ItemFields.SortName] });
  return (r.data.Items ?? []).map((s) => ({
    id: s.Id ?? '',
    name: s.Name ?? '',
    imageUrl: s.Id != null && s.ImageTags?.Thumb != null ? itemImage(s.Id, ImageType.Thumb, s.ImageTags.Thumb, width) : null,
  }));
}

// ---------------------------------------------------------------------------------------------------------------
// Recommended
// ---------------------------------------------------------------------------------------------------------------

/** Upstream's items per home/recommended row (maxItemsPerRow default). */
export const ROW_LIMIT = 25;

export interface RecommendedRowSpec {
  key: string;
  title: string;
  /** Cards say how much is watched (Continue watching, Next up). */
  watching: boolean;
  /** A page of the row's list (the row shows the first ROW_LIMIT; VIEW ALL pages through all of it). */
  load: (startIndex: number, limit: number) => Promise<Page>;
}

function page(r: { Items?: BaseItemDto[] | null; TotalRecordCount?: number | null }): Page {
  const list = r.Items ?? [];
  return { items: list, total: r.TotalRecordCount ?? list.length };
}

function items(parentId: string, extra: Parameters<ReturnType<typeof getLibraryApi>['getItems']>[0]) {
  return async (startIndex: number, limit: number): Promise<Page> =>
    page(
      (
        await getLibraryApi(currentApi()).getItems({
          userId: userId(), parentId, fields: FIELDS, recursive: true, enableUserData: true, enableTotalRecordCount: true, startIndex, limit, ...extra,
        })
      ).data,
    );
}

function resume(parentId: string, type: BaseItemKind) {
  return async (startIndex: number, limit: number): Promise<Page> =>
    page(
      (
        await getLibraryApi(currentApi()).getResumeItems({
          userId: userId(), parentId, fields: FIELDS, includeItemTypes: [type], enableUserData: true, enableTotalRecordCount: true, startIndex, limit,
        })
      ).data,
    );
}

function today(): string {
  return new Date().toISOString();
}

/** The rows of a library's Recommended tab (upstream's RecommendedMovie / RecommendedTvShow / RecommendedMusic), then Suggestions. */
export function recommendedRows(parentId: string, collectionType: string | null): RecommendedRowSpec[] {
  const rows: RecommendedRowSpec[] = [];
  if (collectionType === 'movies') {
    const movie = ['Movie' as BaseItemKind];
    rows.push(
      { key: 'resume', title: 'Continue watching', watching: true, load: resume(parentId, 'Movie' as BaseItemKind) },
      {
        key: 'released', title: 'Recently Released', watching: false,
        load: items(parentId, { includeItemTypes: movie, sortBy: [ItemSortBy.PremiereDate, ItemSortBy.SortName], sortOrder: [SortOrder.Descending, SortOrder.Ascending], maxPremiereDate: today() }),
      },
      { key: 'added', title: 'Recently added', watching: false, load: items(parentId, { includeItemTypes: movie, sortBy: [ItemSortBy.DateCreated], sortOrder: [SortOrder.Descending] }) },
      {
        key: 'top', title: 'Top Rated Unwatched', watching: false,
        load: items(parentId, { includeItemTypes: movie, isPlayed: false, sortBy: [ItemSortBy.CommunityRating], sortOrder: [SortOrder.Descending] }),
      },
    );
  } else if (collectionType === 'tvshows') {
    const episode = ['Episode' as BaseItemKind];
    rows.push(
      { key: 'resume', title: 'Continue watching', watching: true, load: resume(parentId, 'Episode' as BaseItemKind) },
      {
        key: 'nextup', title: 'Next Up', watching: true,
        load: async (startIndex, limit) =>
          page(
            (
              await getShowApi(currentApi()).getNextUp({
                userId: userId(), parentId, fields: FIELDS, imageTypeLimit: 1, enableResumable: false, enableUserData: true,
                enableRewatching: false, enableTotalRecordCount: true, startIndex, limit,
              })
            ).data,
          ),
      },
      {
        key: 'released', title: 'Recently Released', watching: false,
        // upstream also sorts by aired episode order, which Jellyfin 10.10 answers with HTTP 500 (see the Android app)
        load: items(parentId, {
          includeItemTypes: episode, sortBy: [ItemSortBy.PremiereDate, ItemSortBy.SeriesSortName], sortOrder: [SortOrder.Descending, SortOrder.Ascending],
          maxPremiereDate: today(), isUnaired: false,
        }),
      },
      { key: 'added', title: 'Recently added', watching: false, load: items(parentId, { includeItemTypes: episode, sortBy: [ItemSortBy.DateCreated], sortOrder: [SortOrder.Descending] }) },
      {
        key: 'top', title: 'Top Rated Unwatched', watching: false,
        load: items(parentId, { includeItemTypes: ['Series' as BaseItemKind], isPlayed: false, sortBy: [ItemSortBy.CommunityRating], sortOrder: [SortOrder.Descending] }),
      },
    );
  } else if (collectionType === 'music') {
    const album = ['MusicAlbum' as BaseItemKind];
    rows.push(
      {
        key: 'released', title: 'Recently Released', watching: false,
        load: items(parentId, { includeItemTypes: album, sortBy: [ItemSortBy.PremiereDate, ItemSortBy.SortName], sortOrder: [SortOrder.Descending, SortOrder.Ascending], maxPremiereDate: today() }),
      },
      { key: 'added', title: 'Recently added', watching: false, load: items(parentId, { includeItemTypes: album, sortBy: [ItemSortBy.DateCreated], sortOrder: [SortOrder.Descending] }) },
      {
        key: 'top', title: 'Top Rated Unwatched', watching: false,
        load: items(parentId, { includeItemTypes: album, isPlayed: false, sortBy: [ItemSortBy.CommunityRating], sortOrder: [SortOrder.Descending] }),
      },
    );
  }
  const kind: BaseItemKind = (collectionType === 'tvshows' ? 'Series' : collectionType === 'music' ? 'MusicAlbum' : 'Movie') as BaseItemKind;
  rows.push({
    key: 'suggestions',
    title: 'Suggestions',
    watching: false,
    load: async (startIndex, limit) => {
      const all = await suggestions(parentId, kind, ROW_LIMIT);
      return { items: all.slice(startIndex, startIndex + limit), total: all.length };
    },
  });
  return rows;
}

const suggestionCache = new Map<string, BaseItemDto[]>();

/**
 * Upstream's suggestions: seeds from the last things watched, then unwatched items sharing their genres, random
 * unwatched ones and the newest unwatched ones, mixed. Computed once per library per app start (upstream caches
 * them and refreshes in the background).
 */
export async function suggestions(parentId: string, kind: BaseItemKind, itemsPerRow: number): Promise<BaseItemDto[]> {
  const cacheKey = `${userId()}|${parentId}|${kind}`;
  const hit = suggestionCache.get(cacheKey);
  if (hit !== undefined) return hit;
  const api = getLibraryApi(currentApi());
  const historyKind = (kind === 'Series' ? 'Episode' : kind) as BaseItemKind;
  const limits = suggestionLimits(itemsPerRow);
  const fetch = async (extra: Parameters<typeof api.getItems>[0]): Promise<BaseItemDto[]> =>
    (
      await api.getItems({
        userId: userId(), parentId, recursive: true, enableTotalRecordCount: false, enableUserData: true, fields: FIELDS, imageTypeLimit: 1, ...extra,
      })
    ).data.Items ?? [];
  const history = await fetch({ includeItemTypes: [historyKind], sortBy: [ItemSortBy.DatePlayed], isPlayed: true, limit: 20, fields: [ItemFields.Genres] });
  const seeds: BaseItemDto[] = [];
  const seedIds = new Set<string>();
  for (const h of history) {
    const id = h.SeriesId ?? h.Id ?? '';
    if (id === '' || seedIds.has(id)) continue;
    seedIds.add(id);
    seeds.push(h);
    if (seeds.length === 3) break;
  }
  const genreIds: string[] = [];
  for (const s of seeds) for (const g of s.GenreItems ?? []) if (g.Id != null && genreIds.indexOf(g.Id) < 0) genreIds.push(g.Id);
  const exclude = new Set(seedIds);
  const [contextual, random, fresh] = await Promise.all([
    genreIds.length === 0
      ? Promise.resolve([] as BaseItemDto[])
      : fetch({ includeItemTypes: [kind], sortBy: [ItemSortBy.Random], isPlayed: false, limit: limits.contextual, genreIds, excludeItemIds: Array.from(exclude) }),
    fetch({ includeItemTypes: [kind], sortBy: [ItemSortBy.Random], isPlayed: false, limit: limits.random }),
    fetch({ includeItemTypes: [kind], sortBy: [ItemSortBy.DateCreated], sortOrder: [SortOrder.Descending], isPlayed: false, limit: limits.fresh }),
  ]);
  const mixed = mixSuggestions(contextual, fresh, random, exclude, itemsPerRow);
  suggestionCache.set(cacheKey, mixed);
  return mixed;
}
