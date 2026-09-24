import { describe, expect, it } from 'vitest';
import type { BaseItemDto } from '@jellyfin/sdk/lib/generated-client/models/base-item-dto';
import { ItemSortBy } from '@jellyfin/sdk/lib/generated-client/models/item-sort-by';
import { SortOrder } from '@jellyfin/sdk/lib/generated-client/models/sort-order';
import { VideoType } from '@jellyfin/sdk/lib/generated-client/models/video-type';
import { offsetWithin } from '../src/kit/scroll';
import {
  DEFAULT_FILTERS,
  MOVIE_SORTS,
  SERIES_SORTS,
  VIEW_POSTER,
  VIEW_WIDE,
  clearFilter,
  clearFilters,
  countFilters,
  countText,
  filterParams,
  filterSummary,
  filterValueLabel,
  filteredSpec,
  gridCardWidth,
  isFilterValueOn,
  jumpBarShown,
  jumpLetterFor,
  letterIndex,
  letterQuery,
  libraryNoun,
  libraryTabs,
  mergeFilter,
  mixSuggestions,
  nextSort,
  pageJump,
  setViewOption,
  singleSpec,
  sliderValues,
  sortParams,
  suggestionLimits,
  tabSpec,
  toggleFilterValue,
  viewPrefs,
  type LibraryFilter,
} from '../src/pages/library/libraryModel';
import { visibleRows } from '../src/pages/library/VirtualGrid';
import movies from './fixtures/library-movies.json';
import episodes from './fixtures/library-episodes.json';

describe('library tabs and grids (TallyLibraryPage.kt)', () => {
  it('has upstream tabs per library type', () => {
    expect(libraryTabs('movies')).toEqual(['recommended', 'library', 'collections', 'genres']);
    expect(libraryTabs('tvshows')).toEqual(['recommended', 'library', 'genres', 'studios']);
    expect(libraryTabs('music')).toEqual(['recommended', 'albums', 'artists', 'genres', 'songs']);
    expect(libraryTabs('boxsets')).toEqual([]);
    expect(libraryTabs(null)).toEqual([]);
  });

  it('builds the grid specs as the Android page does', () => {
    const lib = tabSpec('m', 'library', 'movies');
    expect(lib).toMatchObject({ key: 'm_library', displayKey: 'm', recursive: true, playEnabled: true, jumpBar: true, sortOptions: MOVIE_SORTS });
    expect(lib?.initialFilter.includeItemTypes).toEqual(['Movie']);
    expect(tabSpec('m', 'collections', 'movies')).toMatchObject({ key: 'm_collection', playEnabled: false, jumpBar: false });
    expect(tabSpec('t', 'library', 'tvshows')).toMatchObject({ key: 't', sortOptions: SERIES_SORTS, playEnabled: false });
    expect(tabSpec('t', 'library', 'tvshows')?.filterOptions).toContain('studios');
    expect(tabSpec('m', 'recommended', 'movies')).toBeNull();
    expect(singleSpec('b', 'boxsets')).toMatchObject({ recursive: false, defaultView: VIEW_POSTER, click: 'indexed' });
    expect(singleSpec('h', 'homevideos')).toMatchObject({ defaultView: VIEW_WIDE, click: 'photos', playEnabled: true });
    expect(tabSpec('mu', 'artists', 'music')).toMatchObject({ artists: true, noun: 'artist' });
  });

  it('opens a genre or a studio as one grid of the library with the filter fixed', () => {
    const genre = filteredSpec('m', 'movies', { kind: 'genre', id: 'g1' });
    expect(genre.initialFilter).toEqual({ genres: ['g1'], includeItemTypes: ['Movie'] });
    expect(genre.displayKey).toBe('m_genres');
    expect(genre.filterOptions).not.toContain('genres');
    const studio = filteredSpec('t', 'tvshows', { kind: 'studio', id: 's1' });
    expect(studio.initialFilter).toEqual({ studios: ['s1'], includeItemTypes: ['Series'] });
    expect(studio.collectionType).toBeNull(); // upstream types a studio page UNKNOWN: video sorts
    expect(studio.displayKey).toBe('t_studios');
  });
});

describe('sort', () => {
  it('flips the current sort, keeps the direction for another', () => {
    const s = { sort: ItemSortBy.SortName, direction: SortOrder.Ascending };
    expect(nextSort(s, ItemSortBy.SortName)).toEqual({ sort: ItemSortBy.SortName, direction: SortOrder.Descending });
    expect(nextSort(s, ItemSortBy.PremiereDate)).toEqual({ sort: ItemSortBy.PremiereDate, direction: SortOrder.Ascending });
  });

  it('adds the name and (movie libraries) the year as tie-breakers', () => {
    expect(sortParams({ sort: ItemSortBy.SortName, direction: SortOrder.Descending }, false)).toEqual({
      sortBy: [ItemSortBy.SortName],
      sortOrder: [SortOrder.Descending],
    });
    expect(sortParams({ sort: ItemSortBy.PremiereDate, direction: SortOrder.Descending }, true)).toEqual({
      sortBy: [ItemSortBy.PremiereDate, ItemSortBy.SortName, ItemSortBy.ProductionYear],
      sortOrder: [SortOrder.Descending, SortOrder.Ascending, SortOrder.Ascending],
    });
    expect(sortParams({ sort: ItemSortBy.Default, direction: SortOrder.Ascending }, true)).toEqual({ sortBy: [], sortOrder: [] });
  });
});

describe('filters (ItemFilterBy / GetItemsFilter)', () => {
  const base: LibraryFilter = { includeItemTypes: ['Movie' as never] };

  it('toggles list values and clears an emptied list', () => {
    const on = toggleFilterValue('genres', base, { name: 'Action', value: 'g1' });
    expect(on.genres).toEqual(['g1']);
    expect(isFilterValueOn('genres', on, { name: 'Action', value: 'g1' })).toBe(true);
    const off = toggleFilterValue('genres', on, { name: 'Action', value: 'g1' });
    expect(off.genres).toBeUndefined();
    expect(off.includeItemTypes).toEqual(['Movie']);
    // parental ratings are matched by name
    expect(toggleFilterValue('officialRating', base, { name: 'PG-13', value: 'PG-13' }).officialRatings).toEqual(['PG-13']);
  });

  it('sets Yes/No and the minimum rating', () => {
    const played = toggleFilterValue('played', base, { name: 'False', value: false });
    expect(played.played).toBe(false);
    expect(isFilterValueOn('played', played, { name: 'False', value: false })).toBe(true);
    expect(isFilterValueOn('played', played, { name: 'True', value: true })).toBe(false);
    const rated = toggleFilterValue('communityRating', base, { name: '7', value: 7 });
    expect(rated.minCommunityRating).toBe(7);
    expect(filterSummary('communityRating', rated)).toBe('7 and up');
    expect(filterSummary('played', played)).toBe('No');
    expect(filterValueLabel('favorite', { name: 'True', value: true })).toBe('Yes');
    expect(filterValueLabel('communityRating', { name: '7', value: 7 })).toBe('7 and up');
  });

  it('counts and clears only the dialog kinds, never the grid own types', () => {
    const f: LibraryFilter = { ...base, played: true, genres: ['g1', 'g2'], years: [1999] };
    expect(countFilters(f, DEFAULT_FILTERS)).toBe(3);
    expect(filterSummary('genres', f)).toBe('2');
    expect(clearFilters(f, DEFAULT_FILTERS)).toEqual(base);
    expect(clearFilter('year', f).years).toBeUndefined();
  });

  it('keeps the grid own filter over a remembered one (merge)', () => {
    expect(mergeFilter({ includeItemTypes: ['BoxSet' as never] }, { includeItemTypes: ['Movie' as never], played: true })).toEqual({
      includeItemTypes: ['BoxSet'],
      played: true,
    });
  });

  it('turns into /Items parameters: decades become years, video kinds become flags and video types', () => {
    const p = filterParams({ years: [1985], decades: [1990], videoTypes: ['SD', '4K', 'Blu-Ray'], favorite: true, genres: ['g'] });
    expect(p.years).toEqual([1985, 1990, 1991, 1992, 1993, 1994, 1995, 1996, 1997, 1998, 1999]);
    expect(p.is4K).toBe(true);
    expect(p.isHd).toBe(false);
    expect(p.videoTypes).toEqual([VideoType.BluRay]);
    expect(p.isFavorite).toBe(true);
    expect(p.genreIds).toEqual(['g']);
    expect(filterParams({})).toEqual({});
  });
});

describe('view options', () => {
  it('sets what upstream sets with the layout and the image type', () => {
    expect(setViewOption(VIEW_POSTER, 'type', 'list')).toMatchObject({ type: 'list', spacing: 4 });
    expect(setViewOption(VIEW_POSTER, 'type', 'denseList')).toMatchObject({ spacing: 2 });
    expect(setViewOption(VIEW_POSTER, 'imageType', 'thumb')).toMatchObject({ imageType: 'thumb', aspectRatio: 'wide' });
    expect(setViewOption(VIEW_POSTER, 'columns', 8).columns).toBe(8);
  });

  it('offers the grid options, fewer for the lists', () => {
    expect(viewPrefs(VIEW_POSTER).map((p) => p.title)).toEqual([
      'Layout', 'Image type', 'Aspect Ratio', 'Show details', 'Show backdrop', 'Show titles', 'Columns', 'Spacing', 'Default content scale', 'Reset',
    ]);
    expect(viewPrefs({ ...VIEW_POSTER, type: 'list' }).map((p) => p.title)).toEqual(['Layout', 'Show details', 'Show backdrop', 'Spacing', 'Reset']);
    expect(sliderValues({ min: 0, max: 32, step: 2 })).toHaveLength(17);
  });

  it('sizes the cards as the Android grid (6 across 1603 px, 16dp apart: 245 px, Android 246)', () => {
    expect(gridCardWidth(1603, 6, 26)).toBe(245);
    expect(gridCardWidth(1603, 4, 26)).toBe(381);
    expect(gridCardWidth(10, 20, 26)).toBe(1);
  });
});

describe('A-Z bar', () => {
  it('files items by their sort name', () => {
    const letters = (movies.Items as BaseItemDto[]).map((m) => jumpLetterFor(m.SortName));
    expect(letters.slice(0, 7)).toEqual(['A', 'B', 'B', 'B', 'B', 'C', 'D']);
    expect(jumpLetterFor('2001: a space odyssey')).toBe('#');
    expect(jumpLetterFor('')).toBe('#');
    expect(jumpLetterFor(null)).toBe('#');
    expect(jumpLetterFor('élan')).toBe('#');
  });

  it('shows only while sorted by name', () => {
    expect(jumpBarShown(ItemSortBy.SortName, 23)).toBe(true);
    expect(jumpBarShown(ItemSortBy.DateCreated, 23)).toBe(false);
    expect(jumpBarShown(ItemSortBy.SortName, 0)).toBe(false);
  });

  it('finds a letter from the server count of the names before it, both directions', () => {
    // ascending: the count of names before F is F's index (the dev library: 8)
    expect(letterQuery('F', SortOrder.Ascending)).toEqual({ nameLessThan: 'F', fromEnd: false });
    expect(letterIndex(8, 23, false)).toBe(8);
    // descending: the names at or after G come first
    expect(letterQuery('F', SortOrder.Descending)).toEqual({ nameLessThan: 'G', fromEnd: true });
    expect(letterIndex(9, 23, true)).toBe(14);
    expect(letterQuery('#', SortOrder.Descending)).toEqual({ nameLessThan: 'A', fromEnd: true });
    expect(letterQuery('Z', SortOrder.Descending)).toBeNull();
    // a letter past the end lands on the last item
    expect(letterIndex(23, 23, false)).toBe(22);
  });

  it('pages by six rows, more for very large libraries', () => {
    expect(pageJump(23, 6)).toBe(36);
    expect(pageJump(3000, 6)).toBe(90);
    expect(pageJump(30_000, 6)).toBe(3000);
  });
});

describe('counts', () => {
  it('names what the grid holds', () => {
    expect(countText(1, libraryNoun(['Movie'], 'movies'))).toBe('1 film');
    expect(countText(23, libraryNoun(undefined, 'movies'))).toBe('23 films');
    expect(countText(2, libraryNoun(undefined, 'boxsets'))).toBe('2 collections');
    expect(libraryNoun(['Series', 'Episode'], 'tvshows')).toBe('item');
    expect(libraryNoun(['Episode'], null)).toBe('episode');
    expect(libraryNoun(undefined, null)).toBe('item');
  });
});

describe('suggestions (SuggestionsWorker)', () => {
  it('splits a row 40 / 30 / 30', () => {
    expect(suggestionLimits(25)).toEqual({ contextual: 10, random: 7, fresh: 7 });
    expect(suggestionLimits(2)).toEqual({ contextual: 1, random: 1, fresh: 1 });
  });

  it('mixes without duplicates or what was just watched, at most the limit', () => {
    const items = episodes.Items as BaseItemDto[];
    const watchedSeries = items[0]?.SeriesId ?? '';
    const mixed = mixSuggestions(items.slice(0, 6), items.slice(4, 10), items.slice(8, 12), new Set([watchedSeries]), 5, () => 0.5);
    expect(mixed.length).toBeLessThanOrEqual(5);
    expect(new Set(mixed.map((i) => i.Id)).size).toBe(mixed.length);
    expect(mixed.some((i) => i.SeriesId === watchedSeries)).toBe(false);
  });
});

describe('grid windowing', () => {
  const g = { count: 100, columns: 6, cellHeight: 433, gap: 26, topPad: 44 };
  it('draws the rows on screen plus two on each side', () => {
    expect(visibleRows(0, 932, g)).toEqual([0, 3]);
    expect(visibleRows(459 * 5, 932, g)).toEqual([2, 8]);
    expect(visibleRows(6938, 932, g)).toEqual([13, 16]); // the bottom of 17 rows
  });
});

describe('offsetWithin (kit)', () => {
  it('is where the element is on screen: the ancestor own scroll moves it too', () => {
    const ancestor = { scrollLeft: 0, scrollTop: 400, offsetLeft: 0, offsetTop: 0, offsetParent: null } as unknown as HTMLElement;
    const row = { offsetLeft: 0, offsetTop: 900, offsetParent: ancestor, scrollLeft: 120, scrollTop: 0 } as unknown as HTMLElement;
    const card = { offsetLeft: 300, offsetTop: 7, offsetParent: row } as unknown as HTMLElement;
    (ancestor as unknown as { contains: (n: unknown) => boolean }).contains = (n) => n === ancestor || n === row;
    (row as unknown as { contains: (n: unknown) => boolean }).contains = (n) => n === row;
    // the page scrolled 400 down: the row sits 500 below the page's top edge, its card 507
    expect(offsetWithin(row, ancestor)).toEqual({ left: 0, top: 500 });
    expect(offsetWithin(card, ancestor)).toEqual({ left: 180, top: 507 });
    // within the row (scrolled 120 left) the card is 180 from its left edge
    expect(offsetWithin(card, row)).toEqual({ left: 180, top: 7 });
  });
});
