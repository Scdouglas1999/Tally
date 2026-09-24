/**
 * What a library remembers per user, as the Android app does (upstream's LibraryDisplayInfo and remembered tabs):
 * the sort, the filters and the view options of each grid, and the tab last opened. Kept in localStorage; a TV
 * that forgets (storage off) just starts from the defaults.
 */
import type { ItemSortBy } from '@jellyfin/sdk/lib/generated-client/models/item-sort-by';
import type { SortOrder } from '@jellyfin/sdk/lib/generated-client/models/sort-order';
import { session } from '../../api/jellyfin';
import { readJson, writeJson } from '../../util/storage';
import { DEFAULT_SORT, mergeFilter, type FolderSpec, type LibraryFilter, type SortAndDirection, type ViewOptions } from './libraryModel';

export interface DisplayInfo {
  sort: SortAndDirection;
  filter: LibraryFilter;
  view: ViewOptions;
}

interface Saved {
  sort?: ItemSortBy;
  direction?: SortOrder;
  filter?: LibraryFilter;
  view?: Partial<ViewOptions>;
}

function userKey(): string {
  return session.get()?.userId ?? 'anonymous';
}

function displayStorageKey(displayKey: string): string {
  return `tally.library.v1.${userKey()}.${displayKey}`;
}

/** The grid's remembered sort, filters (merged under its own fixed filter) and view options. */
export function loadDisplay(spec: FolderSpec): DisplayInfo {
  const saved = readJson<Saved>(displayStorageKey(spec.displayKey)) ?? {};
  const sortOk = saved.sort !== undefined && spec.sortOptions.indexOf(saved.sort) >= 0;
  return {
    sort: sortOk ? { sort: saved.sort as ItemSortBy, direction: saved.direction ?? DEFAULT_SORT.direction } : spec.sortOptions.indexOf(DEFAULT_SORT.sort) >= 0 ? DEFAULT_SORT : { ...DEFAULT_SORT, sort: spec.sortOptions[0] ?? DEFAULT_SORT.sort },
    filter: saved.filter !== undefined ? mergeFilter(spec.initialFilter, saved.filter) : spec.initialFilter,
    view: saved.view !== undefined ? { ...spec.defaultView, ...saved.view } : spec.defaultView,
  };
}

export function saveDisplay(spec: FolderSpec, info: DisplayInfo): void {
  // the grid's own fixed filter (item types, the genre of a genre page) is not the user's choice: not saved
  const filter: LibraryFilter = { ...info.filter };
  delete filter.includeItemTypes;
  if (spec.initialFilter.genres !== undefined) delete filter.genres;
  if (spec.initialFilter.studios !== undefined) delete filter.studios;
  const saved: Saved = { sort: info.sort.sort, direction: info.sort.direction, filter, view: info.view };
  writeJson(displayStorageKey(spec.displayKey), saved);
}

export function loadTab(libraryId: string, count: number): number {
  const t = readJson<number>(`tally.library.tab.v1.${userKey()}.${libraryId}`);
  return typeof t === 'number' && t >= 0 && t < count ? t : 0;
}

export function saveTab(libraryId: string, tab: number): void {
  writeJson(`tally.library.tab.v1.${userKey()}.${libraryId}`, tab);
}
