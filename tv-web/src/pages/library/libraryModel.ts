/**
 * The library pages as data: which tabs a library has, how each grid asks Jellyfin for its items, the sort, filter
 * and view options and what they do. Ports of the Android app's TallyLibraryPage.kt / LibraryFormat.kt and of the
 * upstream pieces they reuse (SortAndDirection.kt, ItemFilterBy.kt, GetItemsFilter.kt, ViewOptions,
 * CollectionFolderViewModel's request). Pure: unit-tested in tests/library.test.ts.
 */
import type { BaseItemKind } from '@jellyfin/sdk/lib/generated-client/models/base-item-kind';
import { ItemSortBy } from '@jellyfin/sdk/lib/generated-client/models/item-sort-by';
import { SortOrder } from '@jellyfin/sdk/lib/generated-client/models/sort-order';
import { VideoType } from '@jellyfin/sdk/lib/generated-client/models/video-type';

// ---------------------------------------------------------------------------------------------------------------
// Sort
// ---------------------------------------------------------------------------------------------------------------

export interface SortAndDirection {
  sort: ItemSortBy;
  direction: SortOrder;
}

export const DEFAULT_SORT: SortAndDirection = { sort: ItemSortBy.SortName, direction: SortOrder.Ascending };

export const MOVIE_SORTS: readonly ItemSortBy[] = [
  ItemSortBy.SortName, ItemSortBy.PremiereDate, ItemSortBy.DateCreated, ItemSortBy.DatePlayed, ItemSortBy.CommunityRating,
  ItemSortBy.CriticRating, ItemSortBy.OfficialRating, ItemSortBy.Runtime, ItemSortBy.PlayCount, ItemSortBy.Random,
];
export const SERIES_SORTS: readonly ItemSortBy[] = [
  ItemSortBy.SortName, ItemSortBy.PremiereDate, ItemSortBy.DateCreated, ItemSortBy.DateLastContentAdded,
  ItemSortBy.SeriesDatePlayed, ItemSortBy.CommunityRating, ItemSortBy.CriticRating, ItemSortBy.OfficialRating, ItemSortBy.Random,
];
export const VIDEO_SORTS: readonly ItemSortBy[] = [
  ItemSortBy.SortName, ItemSortBy.PremiereDate, ItemSortBy.DateCreated, ItemSortBy.DatePlayed, ItemSortBy.CommunityRating,
  ItemSortBy.CriticRating, ItemSortBy.OfficialRating, ItemSortBy.Runtime, ItemSortBy.PlayCount, ItemSortBy.Random,
];
export const BOX_SET_SORTS: readonly ItemSortBy[] = [ItemSortBy.Default, ...VIDEO_SORTS];
export const ALBUM_SORTS: readonly ItemSortBy[] = [
  ItemSortBy.SortName, ItemSortBy.PremiereDate, ItemSortBy.DateCreated, ItemSortBy.DatePlayed, ItemSortBy.AlbumArtist,
  ItemSortBy.CommunityRating, ItemSortBy.CriticRating, ItemSortBy.Random,
];
export const ARTIST_SORTS: readonly ItemSortBy[] = [
  ItemSortBy.SortName, ItemSortBy.PremiereDate, ItemSortBy.DateCreated, ItemSortBy.DatePlayed, ItemSortBy.CommunityRating,
  ItemSortBy.CriticRating, ItemSortBy.Random,
];
export const SONG_SORTS: readonly ItemSortBy[] = [
  ItemSortBy.SortName, ItemSortBy.PremiereDate, ItemSortBy.Album, ItemSortBy.AlbumArtist, ItemSortBy.Artist,
  ItemSortBy.DateCreated, ItemSortBy.DatePlayed, ItemSortBy.CommunityRating, ItemSortBy.CriticRating, ItemSortBy.PlayCount,
  ItemSortBy.Runtime, ItemSortBy.Random,
];

/** Upstream's `rememberSortOptions(collectionType)` (genre and studio pages). */
export function sortsForCollection(collectionType: string | null): readonly ItemSortBy[] {
  switch (collectionType) {
    case 'movies':
      return MOVIE_SORTS;
    case 'tvshows':
      return SERIES_SORTS;
    case 'music':
      return ALBUM_SORTS;
    case 'boxsets':
      return BOX_SET_SORTS;
    default:
      return VIDEO_SORTS;
  }
}

const SORT_NAMES: Partial<Record<ItemSortBy, string>> = {
  [ItemSortBy.SortName]: 'Name',
  [ItemSortBy.SeriesSortName]: 'Name',
  [ItemSortBy.PremiereDate]: 'Date Released',
  [ItemSortBy.DateCreated]: 'Date Added',
  [ItemSortBy.DateLastContentAdded]: 'Date Episode Added',
  [ItemSortBy.DatePlayed]: 'Date Played',
  [ItemSortBy.SeriesDatePlayed]: 'Date Played',
  [ItemSortBy.Random]: 'Random',
  [ItemSortBy.CommunityRating]: 'Community Rating',
  [ItemSortBy.CriticRating]: 'Critic Rating',
  [ItemSortBy.OfficialRating]: 'Parental Rating',
  [ItemSortBy.PlayCount]: 'Play Count',
  [ItemSortBy.AiredEpisodeOrder]: 'Aired Episode Order',
  [ItemSortBy.Runtime]: 'Runtime',
  [ItemSortBy.Default]: 'Default',
  [ItemSortBy.Album]: 'Album',
  [ItemSortBy.AlbumArtist]: 'Album Artist',
  [ItemSortBy.Artist]: 'Artist',
};

export function sortName(sort: ItemSortBy): string {
  return SORT_NAMES[sort] ?? String(sort);
}

/** `↑` ascending, `↓` descending. */
export function directionArrow(direction: SortOrder): string {
  return direction === SortOrder.Ascending ? '↑' : '↓';
}

function flip(direction: SortOrder): SortOrder {
  return direction === SortOrder.Ascending ? SortOrder.Descending : SortOrder.Ascending;
}

/** Choosing `chosen` in the sort dialog: the current sort flips its direction, another keeps the direction. */
export function nextSort(current: SortAndDirection, chosen: ItemSortBy): SortAndDirection {
  return chosen === current.sort ? { sort: chosen, direction: flip(current.direction) } : { sort: chosen, direction: current.direction };
}

/**
 * The request's sortBy/sortOrder, as CollectionFolderViewModel builds them: the sort, then the name as a
 * tie-breaker, then (movie libraries) the production year.
 */
export function sortParams(s: SortAndDirection, movieLibrary: boolean): { sortBy: ItemSortBy[]; sortOrder: SortOrder[] } {
  const sortBy: ItemSortBy[] = [];
  const sortOrder: SortOrder[] = [];
  if (s.sort !== ItemSortBy.Default) {
    sortBy.push(s.sort);
    sortOrder.push(s.direction);
    if (s.sort !== ItemSortBy.SortName) {
      sortBy.push(ItemSortBy.SortName);
      sortOrder.push(SortOrder.Ascending);
    }
    if (movieLibrary) {
      sortBy.push(ItemSortBy.ProductionYear);
      sortOrder.push(SortOrder.Ascending);
    }
  }
  return { sortBy, sortOrder };
}

// ---------------------------------------------------------------------------------------------------------------
// Filters (upstream's GetItemsFilter and ItemFilterBy)
// ---------------------------------------------------------------------------------------------------------------

export type VideoKind = '4K' | 'HD' | 'SD' | '3D' | 'Blu-Ray' | 'DVD';
export const VIDEO_KINDS: readonly VideoKind[] = ['4K', 'HD', 'SD', '3D', 'Blu-Ray', 'DVD'];

export interface LibraryFilter {
  favorite?: boolean;
  genres?: string[];
  minCommunityRating?: number;
  officialRatings?: string[];
  played?: boolean;
  studios?: string[];
  includeItemTypes?: BaseItemKind[];
  videoTypes?: VideoKind[];
  years?: number[];
  decades?: number[];
}

export type FilterKind = 'played' | 'favorite' | 'genres' | 'studios' | 'communityRating' | 'officialRating' | 'video' | 'year' | 'decade';

export const FILTER_LABELS: Record<FilterKind, string> = {
  played: 'Played',
  favorite: 'Favorites',
  genres: 'Genres',
  studios: 'Studios',
  communityRating: 'Community Rating',
  officialRating: 'Parental Rating',
  video: 'Video',
  year: 'Year',
  decade: 'Decade',
};

export const DEFAULT_FILTERS: readonly FilterKind[] = ['played', 'favorite', 'genres', 'communityRating', 'officialRating', 'video', 'year', 'decade'];
export const TV_FILTERS: readonly FilterKind[] = ['played', 'favorite', 'genres', 'studios', 'communityRating', 'officialRating', 'video', 'year', 'decade'];
export const GENRE_PAGE_FILTERS: readonly FilterKind[] = ['played', 'favorite', 'studios', 'communityRating', 'officialRating', 'video', 'year', 'decade'];
export const STUDIO_PAGE_FILTERS: readonly FilterKind[] = DEFAULT_FILTERS;

/** Single-choice kinds: choosing a value goes back to the filter list. */
export function supportsMultiple(kind: FilterKind): boolean {
  return kind !== 'played' && kind !== 'favorite';
}

type FilterValueType = boolean | number | string[] | number[] | VideoKind[];

export function getFilter(kind: FilterKind, f: LibraryFilter): FilterValueType | undefined {
  switch (kind) {
    case 'played':
      return f.played;
    case 'favorite':
      return f.favorite;
    case 'genres':
      return f.genres;
    case 'studios':
      return f.studios;
    case 'communityRating':
      return f.minCommunityRating === undefined ? undefined : Math.trunc(f.minCommunityRating);
    case 'officialRating':
      return f.officialRatings;
    case 'video':
      return f.videoTypes;
    case 'year':
      return f.years;
    case 'decade':
      return f.decades;
  }
}

function withKey<K extends keyof LibraryFilter>(f: LibraryFilter, key: K, value: LibraryFilter[K] | undefined): LibraryFilter {
  const next: LibraryFilter = { ...f };
  if (value === undefined) delete next[key];
  else next[key] = value;
  return next;
}

/** `f` with filter `kind` cleared. */
export function clearFilter(kind: FilterKind, f: LibraryFilter): LibraryFilter {
  switch (kind) {
    case 'played':
      return withKey(f, 'played', undefined);
    case 'favorite':
      return withKey(f, 'favorite', undefined);
    case 'genres':
      return withKey(f, 'genres', undefined);
    case 'studios':
      return withKey(f, 'studios', undefined);
    case 'communityRating':
      return withKey(f, 'minCommunityRating', undefined);
    case 'officialRating':
      return withKey(f, 'officialRatings', undefined);
    case 'video':
      return withKey(f, 'videoTypes', undefined);
    case 'year':
      return withKey(f, 'years', undefined);
    case 'decade':
      return withKey(f, 'decades', undefined);
  }
}

export function countFilters(f: LibraryFilter, kinds: readonly FilterKind[]): number {
  return kinds.filter((k) => getFilter(k, f) !== undefined).length;
}

export function clearFilters(f: LibraryFilter, kinds: readonly FilterKind[]): LibraryFilter {
  return kinds.reduce((acc, k) => clearFilter(k, acc), f);
}

/** Upstream's `merge`: `base` keeps what it sets, the rest comes from `saved`. */
export function mergeFilter(base: LibraryFilter, saved: LibraryFilter): LibraryFilter {
  return {
    ...saved,
    ...Object.fromEntries(Object.entries(base).filter(([, v]) => v !== undefined)),
  } as LibraryFilter;
}

/** One value a filter can take (a genre, `PG-13`, a year, Yes/No). */
export interface FilterValue {
  name: string;
  value: string | number | boolean;
}

export function isFilterValueOn(kind: FilterKind, f: LibraryFilter, v: FilterValue): boolean {
  const current = getFilter(kind, f);
  switch (kind) {
    case 'played':
    case 'favorite':
      return current === v.value;
    case 'communityRating':
      return current === v.value;
    default:
      return Array.isArray(current) && (current as Array<string | number>).indexOf(v.value as string | number) >= 0;
  }
}

function toggled<T>(list: readonly T[] | undefined, value: T, on: boolean): T[] | undefined {
  const base = list ?? [];
  const next = on ? base.filter((x) => x !== value) : base.concat([value]);
  return next.length > 0 ? next : undefined;
}

/** `f` with value `v` of `kind` switched (lists add/remove it, Yes/No and the minimum rating are set). */
export function toggleFilterValue(kind: FilterKind, f: LibraryFilter, v: FilterValue): LibraryFilter {
  const on = isFilterValueOn(kind, f, v);
  switch (kind) {
    case 'played':
      return withKey(f, 'played', v.value as boolean);
    case 'favorite':
      return withKey(f, 'favorite', v.value as boolean);
    case 'communityRating':
      return withKey(f, 'minCommunityRating', v.value as number);
    case 'genres':
      return withKey(f, 'genres', toggled(f.genres, v.value as string, on));
    case 'studios':
      return withKey(f, 'studios', toggled(f.studios, v.value as string, on));
    case 'officialRating':
      return withKey(f, 'officialRatings', toggled(f.officialRatings, v.name, on));
    case 'video':
      return withKey(f, 'videoTypes', toggled(f.videoTypes, v.value as VideoKind, on));
    case 'year':
      return withKey(f, 'years', toggled(f.years, v.value as number, on));
    case 'decade':
      return withKey(f, 'decades', toggled(f.decades, v.value as number, on));
  }
}

/** A value's name in the dialog: Yes/No, `7 and up`, else its own name. */
export function filterValueLabel(kind: FilterKind, v: FilterValue): string {
  if (kind === 'played' || kind === 'favorite') return v.value === true ? 'Yes' : 'No';
  if (kind === 'communityRating') return `${v.name} and up`;
  return v.name;
}

/** What is on for `kind`, for the right column of the filter list: `YES`, `7 AND UP`, or how many values. */
export function filterSummary(kind: FilterKind, f: LibraryFilter): string | null {
  const v = getFilter(kind, f);
  if (v === undefined) return null;
  if (typeof v === 'boolean') return v ? 'Yes' : 'No';
  if (Array.isArray(v)) return String(v.length);
  if (kind === 'communityRating') return `${v} and up`;
  return String(v);
}

/** The /Items query parameters for a filter (upstream's `GetItemsFilter.applyTo`). */
export interface FilterParams {
  includeItemTypes?: BaseItemKind[];
  isFavorite?: boolean;
  genreIds?: string[];
  minCommunityRating?: number;
  isPlayed?: boolean;
  studioIds?: string[];
  officialRatings?: string[];
  years?: number[];
  is4K?: boolean;
  isHd?: boolean;
  is3D?: boolean;
  videoTypes?: VideoType[];
}

export function filterParams(f: LibraryFilter): FilterParams {
  const p: FilterParams = {};
  if (f.includeItemTypes !== undefined) p.includeItemTypes = f.includeItemTypes;
  if (f.favorite !== undefined) p.isFavorite = f.favorite;
  if (f.genres !== undefined) p.genreIds = f.genres;
  if (f.minCommunityRating !== undefined) p.minCommunityRating = f.minCommunityRating;
  if (f.played !== undefined) p.isPlayed = f.played;
  if (f.studios !== undefined) p.studioIds = f.studios;
  if (f.officialRatings !== undefined) p.officialRatings = f.officialRatings;
  const years: number[] = [];
  for (const y of f.years ?? []) if (years.indexOf(y) < 0) years.push(y);
  for (const d of f.decades ?? []) for (let y = d; y < d + 10; y++) if (years.indexOf(y) < 0) years.push(y);
  if (years.length > 0) p.years = years;
  const kinds = f.videoTypes ?? [];
  if (kinds.length > 0) {
    if (kinds.indexOf('4K') >= 0) p.is4K = true;
    if (kinds.indexOf('HD') >= 0) p.isHd = true;
    else if (kinds.indexOf('SD') >= 0) p.isHd = false;
    if (kinds.indexOf('3D') >= 0) p.is3D = true;
    const types: VideoType[] = [];
    if (kinds.indexOf('Blu-Ray') >= 0) types.push(VideoType.BluRay);
    if (kinds.indexOf('DVD') >= 0) types.push(VideoType.Dvd);
    if (types.length > 0) p.videoTypes = types;
  }
  return p;
}

// ---------------------------------------------------------------------------------------------------------------
// View options (upstream's ViewOptions and its dialog's preferences)
// ---------------------------------------------------------------------------------------------------------------

export type ContentScale = 'fit' | 'none' | 'crop' | 'fill' | 'fillWidth' | 'fillHeight';
export type AspectRatio = 'tall' | 'wide' | 'fourThree' | 'square';
export type ImageKind = 'primary' | 'thumb';
export type LayoutKind = 'grid' | 'list' | 'denseList';

export interface ViewOptions {
  columns: number;
  spacing: number;
  contentScale: ContentScale;
  aspectRatio: AspectRatio;
  showDetails: boolean;
  showBackdrop: boolean;
  imageType: ImageKind;
  showTitles: boolean;
  type: LayoutKind;
}

export const VIEW_DEFAULT: ViewOptions = {
  columns: 6, spacing: 16, contentScale: 'fit', aspectRatio: 'tall', showDetails: false, showBackdrop: false,
  imageType: 'primary', showTitles: true, type: 'grid',
};
export const VIEW_POSTER: ViewOptions = { ...VIEW_DEFAULT, columns: 6, spacing: 16, contentScale: 'fill' };
export const VIEW_WIDE: ViewOptions = { ...VIEW_DEFAULT, columns: 4, spacing: 24, contentScale: 'crop', aspectRatio: 'wide' };
export const VIEW_SQUARE: ViewOptions = { ...VIEW_DEFAULT, columns: 6, spacing: 16, contentScale: 'fill', aspectRatio: 'square' };

export const ASPECT_RATIOS: Record<AspectRatio, number> = { tall: 2 / 3, wide: 16 / 9, fourThree: 4 / 3, square: 1 };

/** A row of the view dialog: a switch, a choice with named values, a slider, or Reset. */
export type ViewPref =
  | { kind: 'switch'; key: 'showDetails' | 'showBackdrop' | 'showTitles'; title: string }
  | { kind: 'choice'; key: 'type' | 'imageType' | 'aspectRatio' | 'contentScale'; title: string; values: ReadonlyArray<[string, string]> }
  | { kind: 'slider'; key: 'columns' | 'spacing'; title: string; min: number; max: number; step: number }
  | { kind: 'reset'; title: string };

const LAYOUT: ViewPref = { kind: 'choice', key: 'type', title: 'Layout', values: [['grid', 'Grid'], ['list', 'List'], ['denseList', 'Compact List']] };
const IMAGE_TYPE: ViewPref = { kind: 'choice', key: 'imageType', title: 'Image type', values: [['primary', 'Primary'], ['thumb', 'Thumb']] };
const ASPECT: ViewPref = {
  kind: 'choice', key: 'aspectRatio', title: 'Aspect Ratio',
  values: [['tall', 'Poster (2:3)'], ['wide', '16:9'], ['fourThree', '4:3'], ['square', 'Square (1:1)']],
};
const DETAILS: ViewPref = { kind: 'switch', key: 'showDetails', title: 'Show details' };
const BACKDROP: ViewPref = { kind: 'switch', key: 'showBackdrop', title: 'Show backdrop' };
const TITLES: ViewPref = { kind: 'switch', key: 'showTitles', title: 'Show titles' };
const COLUMNS: ViewPref = { kind: 'slider', key: 'columns', title: 'Columns', min: 1, max: 12, step: 1 };
const SPACING: ViewPref = { kind: 'slider', key: 'spacing', title: 'Spacing', min: 0, max: 32, step: 2 };
const SCALE: ViewPref = {
  kind: 'choice', key: 'contentScale', title: 'Default content scale',
  values: [['fit', 'Fit'], ['none', 'None'], ['crop', 'Crop'], ['fill', 'Fill'], ['fillWidth', 'Fill width'], ['fillHeight', 'Fill height']],
};
const RESET: ViewPref = { kind: 'reset', title: 'Reset' };

/** The dialog's rows for the current layout (upstream's GRID_OPTIONS / LIST_OPTIONS). */
export function viewPrefs(v: ViewOptions): readonly ViewPref[] {
  return v.type === 'grid'
    ? [LAYOUT, IMAGE_TYPE, ASPECT, DETAILS, BACKDROP, TITLES, COLUMNS, SPACING, SCALE, RESET]
    : [LAYOUT, DETAILS, BACKDROP, SPACING, RESET];
}

/** Sets one option; the layout and image type also set what upstream's setters set with them. */
export function setViewOption(v: ViewOptions, key: string, value: string | number | boolean): ViewOptions {
  switch (key) {
    case 'type': {
      const type = value as LayoutKind;
      return { ...v, type, spacing: type === 'grid' ? 16 : type === 'list' ? 4 : 2 };
    }
    case 'imageType': {
      const imageType = value as ImageKind;
      return { ...v, imageType, aspectRatio: imageType === 'primary' ? 'tall' : 'wide' };
    }
    default:
      return { ...v, [key]: value } as ViewOptions;
  }
}

/** The values a slider offers, from min to max. */
export function sliderValues(p: { min: number; max: number; step: number }): number[] {
  const out: number[] = [];
  for (let x = p.min; x <= p.max; x += p.step) out.push(x);
  return out;
}

// ---------------------------------------------------------------------------------------------------------------
// Grid geometry and the A-Z bar
// ---------------------------------------------------------------------------------------------------------------

/** 1 Tally dp in canvas pixels (the Android app lays Tally out on a 1200x675 canvas; this app on 1920x1080). */
export const DP = 1.6;

/** Card width for `columns` across `available` pixels with `gap` between them (whole pixels, at least 1). */
export function gridCardWidth(available: number, columns: number, gap: number): number {
  const n = Math.max(1, columns);
  return Math.max(1, Math.floor((available - gap * (n - 1)) / n));
}

/** The jump bar's letters: `#` (digits and the rest), then A to Z. */
export const JUMP_LETTERS = '#ABCDEFGHIJKLMNOPQRSTUVWXYZ';

/** The letter an item files under, from its sort name: A-Z under themselves, anything else under `#`. */
export function jumpLetterFor(sortNameValue: string | null | undefined): string {
  const first = (sortNameValue ?? '').charAt(0).toUpperCase();
  return first >= 'A' && first <= 'Z' && first.length === 1 ? first : '#';
}

/** The bar shows only while the list is sorted by name and has items. */
export function jumpBarShown(sort: ItemSortBy, count: number): boolean {
  return sort === ItemSortBy.SortName && count > 0;
}

/**
 * The name the server compares against to count the items before `letter`'s first item (`nameLessThan`), and
 * whether that count is from the start (ascending) or the end (descending) of the list. Descending, the items
 * before the letter are the ones at or after the next letter.
 */
export function letterQuery(letter: string, direction: SortOrder): { nameLessThan: string; fromEnd: boolean } | null {
  if (direction === SortOrder.Ascending) return { nameLessThan: letter === '#' ? '#' : letter, fromEnd: false };
  if (letter === 'Z') return null; // Z comes first
  const next = letter === '#' ? 'A' : String.fromCharCode(letter.charCodeAt(0) + 1);
  return { nameLessThan: next, fromEnd: true };
}

/** The index of a letter's first item from the server's count (see letterQuery). */
export function letterIndex(count: number, total: number, fromEnd: boolean): number {
  const i = fromEnd ? total - count : count;
  return Math.max(0, Math.min(Math.max(0, total - 1), i));
}

/** Fast-forward / rewind paging: six rows, more for very large libraries (upstream's sizes). */
export function pageJump(itemCount: number, columns: number): number {
  if (itemCount >= 25_000) return columns * 500;
  if (itemCount >= 7_000) return columns * 50;
  if (itemCount >= 2_000) return columns * 15;
  return columns * 6;
}

// ---------------------------------------------------------------------------------------------------------------
// Counts
// ---------------------------------------------------------------------------------------------------------------

export type Noun = 'film' | 'show' | 'collection' | 'episode' | 'video' | 'item' | 'genre' | 'studio' | 'album' | 'artist' | 'song';

/** `1 film`, `23 films`. */
export function countText(count: number, noun: Noun): string {
  return `${count} ${noun}${count === 1 ? '' : 's'}`;
}

function nounFor(kind: string): Noun {
  switch (kind) {
    case 'Movie':
      return 'film';
    case 'Series':
      return 'show';
    case 'BoxSet':
      return 'collection';
    case 'Episode':
      return 'episode';
    case 'Video':
    case 'MusicVideo':
      return 'video';
    default:
      return 'item';
  }
}

/** The noun for a grid's count: from the item types it asks for, else the library's type, else items. */
export function libraryNoun(includeItemTypes: readonly string[] | undefined, collectionType: string | null): Noun {
  if (includeItemTypes !== undefined && includeItemTypes.length === 1) return nounFor(includeItemTypes[0] as string);
  if (includeItemTypes !== undefined && includeItemTypes.length > 1) return 'item';
  switch (collectionType) {
    case 'movies':
      return 'film';
    case 'tvshows':
      return 'show';
    case 'boxsets':
      return 'collection';
    case 'homevideos':
    case 'musicvideos':
      return 'video';
    default:
      return 'item';
  }
}

// ---------------------------------------------------------------------------------------------------------------
// Tabs and grids
// ---------------------------------------------------------------------------------------------------------------

export type TabKind = 'recommended' | 'library' | 'collections' | 'genres' | 'studios' | 'albums' | 'artists' | 'songs';

export const TAB_LABELS: Record<TabKind, string> = {
  recommended: 'Recommended',
  library: 'Library',
  collections: 'Collections',
  genres: 'Genres',
  studios: 'Studios',
  albums: 'Albums',
  artists: 'Artists',
  songs: 'Songs',
};

/** A library's tabs in upstream's order; none for libraries shown as one grid. */
export function libraryTabs(collectionType: string | null): TabKind[] {
  switch (collectionType) {
    case 'movies':
      return ['recommended', 'library', 'collections', 'genres'];
    case 'tvshows':
      return ['recommended', 'library', 'genres', 'studios'];
    case 'music':
      return ['recommended', 'albums', 'artists', 'genres', 'songs'];
    default:
      return [];
  }
}

/** What opening an item from a grid does. */
export type ClickKind = 'destination' | 'indexed' | 'photos';

/** Everything one grid needs: where its items come from and which controls it has. */
export interface FolderSpec {
  /** Distinct per grid (state and focus keys). */
  key: string;
  /** The folder whose items are listed (the library, a folder in it). */
  itemId: string;
  /** Where the sort, filter and view options are remembered (shared by a library's grids, as upstream). */
  displayKey: string;
  initialFilter: LibraryFilter;
  recursive: boolean;
  sortOptions: readonly ItemSortBy[];
  filterOptions: readonly FilterKind[];
  playEnabled: boolean;
  defaultView: ViewOptions;
  click: ClickKind;
  jumpBar: boolean;
  collectionType: string | null;
  /** The library is a movie library (the year breaks ties in the sort). */
  movieLibrary: boolean;
  /** Lists artists (/Artists) instead of items. */
  artists?: boolean;
  /** The count's noun when the item types do not say it. */
  noun?: Noun;
}

const base = (itemId: string, collectionType: string | null): Pick<FolderSpec, 'itemId' | 'collectionType' | 'movieLibrary'> => ({
  itemId,
  collectionType,
  movieLibrary: collectionType === 'movies',
});

/** A grid tab of a library (Movies: Library, Collections; TV: Library; music: Albums, Artists, Songs). */
export function tabSpec(libraryId: string, tab: TabKind, collectionType: string | null): FolderSpec | null {
  const b = base(libraryId, collectionType);
  if (collectionType === 'movies' && tab === 'library') {
    return {
      ...b, key: `${libraryId}_library`, displayKey: libraryId, initialFilter: { includeItemTypes: ['Movie' as BaseItemKind] },
      recursive: true, sortOptions: MOVIE_SORTS, filterOptions: DEFAULT_FILTERS, playEnabled: true, defaultView: VIEW_POSTER,
      click: 'destination', jumpBar: true,
    };
  }
  if (collectionType === 'movies' && tab === 'collections') {
    return {
      ...b, key: `${libraryId}_collection`, displayKey: libraryId, initialFilter: { includeItemTypes: ['BoxSet' as BaseItemKind] },
      recursive: true, sortOptions: VIDEO_SORTS, filterOptions: DEFAULT_FILTERS, playEnabled: false, defaultView: VIEW_POSTER,
      click: 'destination', jumpBar: false,
    };
  }
  if (collectionType === 'tvshows' && tab === 'library') {
    return {
      ...b, key: libraryId, displayKey: libraryId, initialFilter: { includeItemTypes: ['Series' as BaseItemKind] },
      recursive: true, sortOptions: SERIES_SORTS, filterOptions: TV_FILTERS, playEnabled: false, defaultView: VIEW_POSTER,
      click: 'destination', jumpBar: true,
    };
  }
  if (collectionType === 'music' && (tab === 'albums' || tab === 'artists' || tab === 'songs')) {
    // Play all / shuffle and a song's play key go to the music queue on Android: not in this app yet.
    const music = { ...b, recursive: true, filterOptions: DEFAULT_FILTERS, playEnabled: false, defaultView: VIEW_SQUARE, click: 'destination' as const, jumpBar: true };
    if (tab === 'albums') {
      return { ...music, key: `${libraryId}_albums`, displayKey: libraryId, initialFilter: { includeItemTypes: ['MusicAlbum' as BaseItemKind] }, sortOptions: ALBUM_SORTS, noun: 'album' };
    }
    if (tab === 'artists') {
      return { ...music, key: `${libraryId}_artists`, displayKey: libraryId, initialFilter: {}, sortOptions: ARTIST_SORTS, artists: true, noun: 'artist' };
    }
    return { ...music, key: `${libraryId}_songs`, displayKey: libraryId, initialFilter: { includeItemTypes: ['Audio' as BaseItemKind] }, sortOptions: SONG_SORTS, noun: 'song' };
  }
  return null;
}

/** A library without tabs (Collections, home videos, other libraries) or a folder inside one. */
export function singleSpec(itemId: string, collectionType: string | null): FolderSpec {
  const b = base(itemId, collectionType);
  if (collectionType === 'boxsets') {
    return {
      ...b, key: itemId, displayKey: itemId, initialFilter: {}, recursive: false, sortOptions: MOVIE_SORTS,
      filterOptions: DEFAULT_FILTERS, playEnabled: false, defaultView: VIEW_POSTER, click: 'indexed', jumpBar: true,
    };
  }
  if (collectionType === 'homevideos') {
    return {
      ...b, key: itemId, displayKey: itemId, initialFilter: {}, recursive: false, sortOptions: VIDEO_SORTS,
      filterOptions: DEFAULT_FILTERS, playEnabled: true, defaultView: VIEW_WIDE, click: 'photos', jumpBar: true,
    };
  }
  return {
    ...b, key: itemId, displayKey: itemId, initialFilter: {}, recursive: false, sortOptions: VIDEO_SORTS,
    filterOptions: DEFAULT_FILTERS, playEnabled: false, defaultView: VIEW_WIDE, click: 'indexed', jumpBar: true,
  };
}

/**
 * A box set's items (Android's TallyCollectionPage lists them under the collection's header; here a poster grid until
 * tv-web has that page): not recursive, the film sorts, Play all and Shuffle, no A-Z bar (a collection is short).
 */
export function collectionSpec(itemId: string): FolderSpec {
  return {
    ...base(itemId, null), key: `${itemId}_boxset`, displayKey: `${itemId}_boxset`, initialFilter: {}, recursive: false,
    sortOptions: MOVIE_SORTS, filterOptions: DEFAULT_FILTERS, playEnabled: true, defaultView: VIEW_POSTER, click: 'destination', jumpBar: false,
  };
}

/** The item types a library's genres and studios are counted over (films, shows, albums). */
export function nameGridTypes(collectionType: string | null): BaseItemKind[] | undefined {
  switch (collectionType) {
    case 'movies':
      return ['Movie' as BaseItemKind];
    case 'tvshows':
      return ['Series' as BaseItemKind];
    case 'music':
      return ['MusicAlbum' as BaseItemKind];
    default:
      return undefined;
  }
}

/** A genre or a studio of a library: one grid of the library's films or shows with it (upstream's FilteredCollection). */
export function filteredSpec(
  libraryId: string,
  collectionType: string | null,
  by: { kind: 'genre' | 'studio'; id: string },
): FolderSpec {
  const types = nameGridTypes(collectionType);
  const genre = by.kind === 'genre';
  // a studio page is typed UNKNOWN upstream: video sort options
  const pageType = genre ? collectionType : null;
  return {
    itemId: libraryId,
    collectionType: pageType,
    movieLibrary: collectionType === 'movies',
    key: `${libraryId}_${by.kind}_${by.id}`,
    displayKey: `${libraryId}_${genre ? 'genres' : 'studios'}`,
    initialFilter: genre ? { genres: [by.id], includeItemTypes: types } : { studios: [by.id], includeItemTypes: types },
    recursive: true,
    sortOptions: sortsForCollection(pageType),
    filterOptions: genre ? GENRE_PAGE_FILTERS : STUDIO_PAGE_FILTERS,
    playEnabled: true,
    defaultView: VIEW_POSTER,
    click: 'indexed',
    jumpBar: true,
  };
}

/** Where a grid's item opens: a folder opens as a grid of its own, anything else its page. */
export function isFolder(type: string | null | undefined): boolean {
  return type === 'Folder' || type === 'CollectionFolder' || type === 'UserView';
}

/** Items the PLAY key (and Play all) can start in the player. */
export function isPlayable(type: string | null | undefined): boolean {
  return type === 'Movie' || type === 'Episode' || type === 'Video' || type === 'MusicVideo';
}

// ---------------------------------------------------------------------------------------------------------------
// Recommended suggestions (upstream's SuggestionsWorker)
// ---------------------------------------------------------------------------------------------------------------

/** How many of each kind make up the Suggestions row: 40% like what was watched, 30% random, 30% new. */
export function suggestionLimits(itemsPerRow: number): { contextual: number; random: number; fresh: number } {
  return {
    contextual: Math.max(1, Math.floor(itemsPerRow * 0.4)),
    random: Math.max(1, Math.floor(itemsPerRow * 0.3)),
    fresh: Math.max(1, Math.floor(itemsPerRow * 0.3)),
  };
}

/**
 * The Suggestions row from its three sources: without duplicates and without what was just watched, shuffled,
 * at most `limit`. `random` is the shuffle's random source (0 <= x < 1).
 */
export function mixSuggestions<T extends { Id?: string | null; SeriesId?: string | null }>(
  contextual: readonly T[],
  fresh: readonly T[],
  randomItems: readonly T[],
  exclude: ReadonlySet<string>,
  limit: number,
  random: () => number = Math.random,
): T[] {
  const seen = new Set<string>();
  const out: T[] = [];
  for (const item of contextual.concat(fresh, randomItems)) {
    const id = item.Id ?? '';
    if (id === '' || seen.has(id)) continue;
    seen.add(id);
    if (exclude.has(item.SeriesId ?? id)) continue;
    out.push(item);
  }
  for (let i = out.length - 1; i > 0; i--) {
    const j = Math.floor(random() * (i + 1));
    const t = out[i] as T;
    out[i] = out[j] as T;
    out[j] = t;
  }
  return out.slice(0, limit);
}
