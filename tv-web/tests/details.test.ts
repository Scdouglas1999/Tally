import { readFileSync } from 'node:fs';
import type { BaseItemDto } from '@jellyfin/sdk/lib/generated-client/models/base-item-dto';
import { describe, expect, it } from 'vitest';
import {
  airDate,
  chapterKicker,
  chaptersOf,
  collectionEntries,
  createdByLine,
  creditKicker,
  directorLine,
  endsLine,
  episodeCode,
  episodeMeta,
  episodeNumber,
  extrasOf,
  filmMeta,
  formatPosition,
  nextInCollection,
  nextUpLabel,
  nextUpText,
  personCardRole,
  personLifeLine,
  primaryRole,
  resolutionString,
  resumeShare,
  rowForDown,
  seasonLabel,
  seasonSummary,
  seasonSummaryText,
  seasonWatchedFraction,
  seriesMeta,
  seriesYears,
  shortLanguage,
  techBoxes,
} from '../src/pages/details/detailsFormat';
import { detailsRoute } from '../src/pages/details/navigate';

/** Items captured from the dev server (Jellyfin 10.10.6) for these tests. */
function fixture<T>(name: string): T {
  return JSON.parse(readFileSync(new URL(`./fixtures/${name}`, import.meta.url), 'utf8')) as T;
}
const dune = fixture<BaseItemDto>('details-film-dune.json');
const severance = fixture<BaseItemDto>('details-series-severance.json');
const seasons = fixture<{ Items: BaseItemDto[] }>('details-seasons-severance.json').Items;
const episodes = fixture<{ Items: BaseItemDto[] }>('details-episodes-severance-s1.json').Items;
const nextUp = fixture<{ Items: BaseItemDto[] }>('details-nextup-severance.json').Items[0] as BaseItemDto;
const movies = fixture<{ Items: BaseItemDto[] }>('details-movies-providers.json').Items;
const person = fixture<BaseItemDto>('details-person-chalamet.json');
const credits = fixture<{ Items: BaseItemDto[] }>('details-person-credits.json').Items;

describe('film header (Android DetailHeader)', () => {
  it('builds the meta line with the rating box', () => {
    expect(filmMeta(dune)).toEqual([{ text: '2021' }, { text: 'PG-13', boxed: true }, { text: '1m 30s' }, { text: '★ 7.8' }, { text: 'RT 83%' }]);
  });

  it('builds the tech boxes of the first source (as the Android capture of Dune shows them)', () => {
    expect(techBoxes(dune.MediaSources?.[0])).toEqual(['360p', 'H264', 'EN · AAC STEREO', 'CC · EN ES']);
  });

  it('names the director, not the writers', () => {
    expect(directorLine(dune)).toBe('Directed by Denis Villeneuve');
    expect(createdByLine(severance)).toBeNull(); // no Creator or Writer credits on the series
  });

  it('has no ENDS line once watched; counts what is left otherwise', () => {
    expect(endsLine(dune)).toBeNull();
    const unwatched: BaseItemDto = { ...dune, UserData: { Key: 'test', Played: false, PlaybackPositionTicks: 300_000_000 } };
    expect(endsLine(unwatched, new Date(2026, 8, 24, 21, 40, 0))).toBe('ENDS 9:41 PM');
  });

  it('resume share is at least 1% once there is a resume point', () => {
    expect(resumeShare(dune)).toBeNull();
    expect(resumeShare({ ...dune, UserData: { Key: 'test', PlaybackPositionTicks: 1000 } })).toBe(1);
    expect(resumeShare({ ...dune, UserData: { Key: 'test', PlaybackPositionTicks: 450_115_000 } })).toBe(50);
  });

  it('labels resolutions and languages like upstream', () => {
    expect(resolutionString(1920, 1080, false)).toBe('1080p');
    expect(resolutionString(3840, 2160, false)).toBe('4K');
    expect(resolutionString(1280, 720, true)).toBe('720i');
    expect(resolutionString(1080, 1920, false)).toBe('1080p');
    expect(shortLanguage('eng')).toBe('EN');
    expect(shortLanguage('und')).toBeNull();
    expect(shortLanguage('pt')).toBe('PT');
  });

  it('turns chapters into cards with their clock position', () => {
    const chapters = chaptersOf(dune);
    expect(chapters.map(chapterKicker)).toEqual(['Chapter 1 · 00:00', 'Chapter 2 · 00:30', 'Chapter 3 · 01:00']);
    expect(formatPosition(37_230_000_000)).toBe('1:02:03');
  });
});

describe('series and season rundown (Android SeriesFormat)', () => {
  it('shows the years a series ran, seasons and episodes', () => {
    expect(seriesYears(2022, '2025-03-20T00:00:00.0000000Z', 'Continuing')).toBe('2022–');
    expect(seriesYears(2008, '2013-09-29T00:00:00.0000000Z', 'Ended')).toBe('2008–2013');
    expect(seriesYears(2019, null, 'Ended')).toBe('2019');
    expect(seriesMeta(severance, 0)).toEqual([
      { text: '2022–' },
      { text: 'TV-MA', boxed: true },
      { text: '2 seasons' },
      { text: '8 episodes' },
      { text: '★ 8.4' },
    ]);
  });

  it('codes episodes', () => {
    expect(episodeCode(1, 3)).toBe('S1 E3');
    expect(episodeCode(1, 3, 4)).toBe('S1 E3–4');
    expect(episodeCode(0, 2)).toBe('SPECIAL');
    expect(episodeCode(null, 5)).toBe('E5');
    expect(episodeCode(null, null)).toBeNull();
    expect(episodeNumber(2)).toBe('E02');
    expect(episodeNumber(12)).toBe('E12');
  });

  it('reads air dates in UTC (the server stores midnight UTC)', () => {
    expect(airDate(episodes[0]?.PremiereDate)).toBe('FEB 17, 2022');
    expect(episodeMeta(episodes[0] as BaseItemDto).map((p) => p.text)).toEqual(['FEB 17, 2022', 'TV-MA', '1m', '★ 8.2']);
  });

  it('sums up a season', () => {
    const summary = seasonSummary(episodes);
    expect(summary).toEqual({ episodes: 5, left: 4, remainingTicks: 4 * 600_230_000 });
    expect(seasonSummaryText(summary)).toBe('5 episodes · 4 left · 4m');
  });

  it('labels the primary button from Next Up', () => {
    expect(nextUpText(nextUpLabel(nextUp))).toBe('Next up · S1 E2');
    expect(nextUpText(nextUpLabel(null))).toBe('Play');
    const resuming: BaseItemDto = { ...nextUp, UserData: { Key: 'test', Played: false, PlaybackPositionTicks: 180_069_000 } };
    expect(nextUpText(nextUpLabel(resuming))).toBe('Resume S1 E2 · 30%');
  });

  it('works out the share of a season watched, and labels tabs', () => {
    expect(seasons.map(seasonWatchedFraction)).toEqual([0.2, 0]);
    expect(seasons.map(seasonLabel)).toEqual(['Season 1', 'Season 2']);
    expect(seasonLabel({ IndexNumber: 0, Name: 'Specials' })).toBe('Specials');
  });

  it('goes DOWN from the tabs to the last row, else next up, else in progress, else the first', () => {
    expect(rowForDown(episodes, 3, nextUp.Id ?? null)).toBe(3);
    expect(rowForDown(episodes, null, nextUp.Id ?? null)).toBe(1);
    const inProgress = episodes.map((e, i) => (i === 4 ? { ...e, UserData: { Key: 'test', Played: false, PlaybackPositionTicks: 10 } } : e));
    expect(rowForDown(inProgress, null, null)).toBe(4);
    expect(rowForDown(episodes, null, null)).toBe(0);
    expect(rowForDown([], null, null)).toBeNull();
  });
});

describe('where items open (Android BaseItem.destination)', () => {
  it('sends episodes to their season rundown, seasons to theirs, the rest to their page', () => {
    const ep = episodes[1] as BaseItemDto;
    expect(detailsRoute(ep)).toEqual({ name: 'season', seriesId: ep.SeriesId, seasonId: ep.SeasonId, episodeId: ep.Id });
    const season = seasons[0] as BaseItemDto;
    expect(detailsRoute(season)).toEqual({ name: 'season', seriesId: season.SeriesId, seasonId: season.Id });
    expect(detailsRoute(dune)).toEqual({ name: 'item', itemId: dune.Id });
    expect(detailsRoute({ Id: 'x', Type: 'Video', ExtraType: 'Featurette' })).toEqual({ name: 'player', itemId: 'x', startMs: 0 });
  });
});

describe('the next film of a collection (Android CollectionNext)', () => {
  const entries = collectionEntries(movies);
  const id = (name: string): string => movies.find((m) => m.Name === name)?.Id ?? '';

  it('follows the premiere order within a TMDb collection', () => {
    expect(nextInCollection(entries, id('Toy Story'))).toBe(id('Toy Story 2'));
    expect(nextInCollection(entries, id('Toy Story 2'))).toBe(id('Toy Story 3'));
    expect(nextInCollection(entries, id('Back to the Future'))).toBe(id('Back to the Future Part II'));
  });

  it('has none for the last film or one without a collection', () => {
    expect(nextInCollection(entries, id('Toy Story 3'))).toBeNull();
    expect(nextInCollection(entries, id('Inception'))).toBeNull();
    expect(nextInCollection(entries, id('Dune'))).toBeNull(); // alone in its collection on this server
  });
});

describe('extras and people', () => {
  it('drops theme media and trailers from extras and orders the kinds', () => {
    const extras = extrasOf([
      { Id: 'a', Name: 'Making of', ExtraType: 'BehindTheScenes' },
      { Id: 'b', Name: 'Theme', ExtraType: 'ThemeSong' },
      { Id: 'c', Name: '', ExtraType: 'Featurette' },
      { Id: 'd', Name: 'Teaser', ExtraType: 'Trailer' },
    ]);
    expect(extras.map((e) => [e.item.Id, e.kicker, e.title])).toEqual([
      ['c', 'Featurette', 'Featurette'],
      ['a', 'Behind the scenes', 'Making of'],
    ]);
  });

  it("writes a person's life line and usual credit", () => {
    expect(personLifeLine(person.PremiereDate, person.EndDate, person.ProductionLocations?.[0])).toBe('Born Dec 27, 1995 · Manhattan, New York City, New York, USA');
    expect(personLifeLine('1928-04-04T00:00:00.0000000Z', '2016-01-10T00:00:00.0000000Z', 'Chicago')).toBe('1928–2016 · Chicago');
    const roles = credits.flatMap((c) => (c.People ?? []).filter((p) => p.Id === person.Id).map((p) => p.Type ?? ''));
    expect(primaryRole(roles)).toBe('Actor');
    expect(primaryRole(['Writer', 'Director', 'Director', 'Writer'])).toBe('Director');
    expect(primaryRole([])).toBeNull();
  });

  it('puts the character under an actor and the credit under the crew', () => {
    const people = dune.People ?? [];
    expect(personCardRole(people[0] ?? {})).toBe('Paul Atreides');
    expect(personCardRole({ Type: 'Director', Role: '' })).toBe('Director');
    expect(creditKicker(episodes[0] as BaseItemDto)).toBe('2022 · Severance · S1 E1');
  });
});
