/**
 * Pure formatting for the film, series, season and episode pages and the person page: ports of the Android app's
 * DetailHeader.kt (tech boxes, runtime, ends), SeriesFormat.kt (years, codes, air dates, season summary, the Next up
 * label), PagesFormat.kt (life line, primary role) and CollectionNext.kt (the next film of a collection).
 * Unit-tested against items captured from the dev server (tests/fixtures/details-*.json).
 */
import type { BaseItemDto } from '@jellyfin/sdk/lib/generated-client/models/base-item-dto';
import type { MediaSourceInfo } from '@jellyfin/sdk/lib/generated-client/models/media-source-info';
import type { MediaStream } from '@jellyfin/sdk/lib/generated-client/models/media-stream';
import { formatRuntime, formatTime, resumePercent } from '../../util/format';

// ---- the meta line -------------------------------------------------------------------------------------------

/** One part of a detail page's mono meta line; `boxed` draws the hairline box (the official rating). */
export interface MetaPart {
  text: string;
  boxed?: boolean;
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/**
 * The calendar date the server meant: it stores air dates, birthdays and end dates as midnight UTC, so the day is
 * read in UTC (the local zone could move it a day back).
 */
function serverDate(iso: string | null | undefined): Date | null {
  if (iso == null || iso === '') return null;
  const d = new Date(iso);
  return isNaN(d.getTime()) ? null : d;
}

/** Month-first date: `Jul 30, 1970`. */
export function monthFirstDate(iso: string | null | undefined): string | null {
  const d = serverDate(iso);
  if (d === null) return null;
  return `${MONTHS[d.getUTCMonth()] ?? ''} ${d.getUTCDate()}, ${d.getUTCFullYear()}`;
}

/** Air date as `FEB 17, 2022`. */
export function airDate(iso: string | null | undefined): string | null {
  const d = monthFirstDate(iso);
  return d === null ? null : d.toUpperCase();
}

const community = (rating: number): string => `★ ${rating.toFixed(1)}`;
const critic = (rating: number): string => `RT ${Math.round(rating)}%`;

/** A film: `2021 · [PG-13] · 2h 35m · ★ 7.8 · RT 83%`. */
export function filmMeta(item: BaseItemDto): MetaPart[] {
  const parts: MetaPart[] = [];
  if (item.ProductionYear != null) parts.push({ text: String(item.ProductionYear) });
  if (item.OfficialRating != null && item.OfficialRating.trim() !== '') parts.push({ text: item.OfficialRating, boxed: true });
  const runtime = item.RunTimeTicks ?? 0;
  if (runtime > 0) parts.push({ text: formatRuntime(runtime) });
  if (item.CommunityRating != null) parts.push({ text: community(item.CommunityRating) });
  if (item.CriticRating != null) parts.push({ text: critic(item.CriticRating) });
  return parts;
}

/** An episode: `JAN 20, 2008 · [TV-14] · 47m · ★ 8.2`. */
export function episodeMeta(item: BaseItemDto): MetaPart[] {
  const parts: MetaPart[] = [];
  const aired = airDate(item.PremiereDate);
  if (aired !== null) parts.push({ text: aired });
  if (item.OfficialRating != null && item.OfficialRating.trim() !== '') parts.push({ text: item.OfficialRating, boxed: true });
  const runtime = item.RunTimeTicks ?? 0;
  if (runtime > 0) parts.push({ text: formatRuntime(runtime) });
  if (item.CommunityRating != null) parts.push({ text: community(item.CommunityRating) });
  if (item.CriticRating != null) parts.push({ text: critic(item.CriticRating) });
  return parts;
}

/**
 * Years a series ran: `2008–2013` once it has ended, `2022–` while it continues, `2019` for a single year (or when
 * only the first year is known).
 */
export function seriesYears(productionYear: number | null | undefined, endDate: string | null | undefined, status: string | null | undefined): string | null {
  if (productionYear == null) return null;
  if (status != null && status.toLowerCase() === 'continuing') return `${productionYear}–`;
  const end = serverDate(endDate);
  const endYear = end !== null ? end.getUTCFullYear() : null;
  return endYear !== null && endYear > productionYear ? `${productionYear}–${endYear}` : String(productionYear);
}

export function plural(n: number, one: string, many: string): string {
  return `${n} ${n === 1 ? one : many}`;
}

/** A series: `2022– · [TV-MA] · 2 SEASONS · 8 EPISODES · ★ 8.4`. */
export function seriesMeta(series: BaseItemDto, loadedSeasons: number): MetaPart[] {
  const parts: MetaPart[] = [];
  const years = seriesYears(series.ProductionYear, series.EndDate, series.Status);
  if (years !== null) parts.push({ text: years });
  if (series.OfficialRating != null && series.OfficialRating.trim() !== '') parts.push({ text: series.OfficialRating, boxed: true });
  const seasons = series.ChildCount ?? loadedSeasons;
  if (seasons > 0) parts.push({ text: plural(seasons, 'season', 'seasons') });
  const episodes = series.RecursiveItemCount;
  if (episodes != null && episodes > 0) parts.push({ text: plural(episodes, 'episode', 'episodes') });
  if (series.CommunityRating != null) parts.push({ text: community(series.CommunityRating) });
  return parts;
}

/** `ENDS 9:41 PM` if playback of what is left starts `now`; null once watched or with nothing left. */
export function endsLine(item: BaseItemDto, now: Date = new Date()): string | null {
  if (item.UserData?.Played === true) return null;
  const left = (item.RunTimeTicks ?? 0) - (item.UserData?.PlaybackPositionTicks ?? 0);
  if (left <= 0) return null;
  return 'ENDS ' + formatTime(new Date(now.getTime() + left / 10_000));
}

function credited(item: BaseItemDto, type: string): string[] {
  const names: string[] = [];
  for (const p of item.People ?? []) {
    if (p.Type === type && p.Name != null && p.Name.trim() !== '' && names.indexOf(p.Name) < 0) names.push(p.Name);
  }
  return names;
}

/** `Directed by Denis Villeneuve`, or null. */
export function directorLine(item: BaseItemDto): string | null {
  const names = credited(item, 'Director');
  return names.length > 0 ? 'Directed by ' + names.join(', ') : null;
}

/** `Created by …` from the Creator credits, else the Writer credits; null when there are none. */
export function createdByLine(series: BaseItemDto): string | null {
  let names = credited(series, 'Creator');
  if (names.length === 0) names = credited(series, 'Writer');
  return names.length > 0 ? 'Created by ' + names.join(', ') : null;
}

// ---- tech boxes ----------------------------------------------------------------------------------------------

/** Upstream's resolution label (after Jellyfin's MediaStream.cs): `1080p`, `720i`, `4K`. */
export function resolutionString(width: number, height: number, interlaced: boolean): string {
  if (height > width) return resolutionString(height, width, interlaced);
  const s = interlaced ? 'i' : 'p';
  if (width > 5120 || height > 4320) return '8K';
  if (width > 2560 || height > 1440) return '4K';
  if (width > 1920 || height > 1080) return '1440' + s;
  if (width > 1280 || height > 962) return '1080' + s;
  if (width > 1024 || height > 576) return '720' + s;
  if (width > 960 || height > 544) return '576' + s;
  if (width > 845 || height > 480) return '540' + s;
  if (width > 720 || height > 404) return '480' + s;
  if (width > 682 || height > 384) return '404' + s;
  if (width > 640 || height > 360) return '384' + s;
  if (width > 426 || height > 240) return '360' + s;
  if (width > 256 || height > 144) return '240' + s;
  return String(height) + s;
}

/** ISO 639-2 codes to 639-1 (what Jellyfin reports for most tracks); anything else keeps its first two letters. */
const ISO_639_2: Record<string, string> = {
  eng: 'en', spa: 'es', fra: 'fr', fre: 'fr', deu: 'de', ger: 'de', ita: 'it', por: 'pt', jpn: 'ja', kor: 'ko',
  zho: 'zh', chi: 'zh', rus: 'ru', nld: 'nl', dut: 'nl', swe: 'sv', nor: 'no', nob: 'nb', dan: 'da', fin: 'fi',
  pol: 'pl', tur: 'tr', ara: 'ar', heb: 'he', hin: 'hi', ces: 'cs', cze: 'cs', ell: 'el', gre: 'el', hun: 'hu',
  ukr: 'uk', tha: 'th', vie: 'vi', ind: 'id', msa: 'ms', may: 'ms', ron: 'ro', rum: 'ro', cat: 'ca', isl: 'is',
  ice: 'is', hrv: 'hr', srp: 'sr', slk: 'sk', slo: 'sk', slv: 'sl', bul: 'bg', est: 'et', lav: 'lv', lit: 'lt',
  fas: 'fa', per: 'fa', tam: 'ta', tel: 'te', fil: 'fil', tgl: 'tl',
};

/** `EN` for `eng` or `en`; null for none or `und`. */
export function shortLanguage(code: string | null | undefined): string | null {
  if (code == null || code.trim() === '' || code.toLowerCase() === 'und') return null;
  const c = code.trim().toLowerCase();
  if (c.length === 2) return c.toUpperCase();
  return (ISO_639_2[c] ?? c.substring(0, 2)).toUpperCase();
}

function hdrLabel(stream: MediaStream): string | null {
  const type = stream.VideoRangeType ?? '';
  const hdrType = type === 'HDR10' || type === 'HDR10Plus' || type === 'HLG' || type.indexOf('DOVI') === 0;
  if (stream.VideoRange !== 'HDR' && !hdrType) return null;
  if (stream.VideoDoViTitle != null && stream.VideoDoViTitle.trim() !== '') return 'DOLBY VISION';
  if (type === 'HDR10') return 'HDR10';
  if (type === 'HDR10Plus') return 'HDR10+';
  if (type === 'HLG') return 'HLG';
  if (type.indexOf('DOVI') === 0) return 'DOLBY VISION';
  return stream.VideoRange === 'HDR' ? 'HDR' : null;
}

function audioLabel(stream: MediaStream): string | null {
  const language = shortLanguage(stream.Language);
  const codec = stream.Codec != null && stream.Codec.trim() !== '' ? stream.Codec.toUpperCase() : null;
  const channels =
    stream.ChannelLayout != null && stream.ChannelLayout.trim() !== ''
      ? stream.ChannelLayout.toUpperCase()
      : stream.Channels != null && stream.Channels > 0
        ? String(stream.Channels)
        : null;
  const codecAndChannels = [codec, channels].filter((x) => x !== null).join(' ');
  const out = [language, codecAndChannels === '' ? null : codecAndChannels].filter((x) => x !== null).join(' · ');
  return out === '' ? null : out;
}

const SUBTITLE_LANGS = 3;

function subtitleLabel(streams: readonly MediaStream[]): string | null {
  const ordered = streams
    .filter((s) => s.Type === 'Subtitle')
    .map((s, i) => ({ s, i }))
    // embedded before external, then by index (a stable sort by hand: Array#sort is stable only from Chromium 70)
    .sort((a, b) => Number(a.s.IsExternal === true) - Number(b.s.IsExternal === true) || (a.s.Index ?? 0) - (b.s.Index ?? 0) || a.i - b.i)
    .map((x) => x.s);
  const codes: string[] = [];
  for (const s of ordered) {
    const code = shortLanguage(s.Language);
    if (code !== null && codes.indexOf(code) < 0) codes.push(code);
  }
  if (codes.length === 0) return null;
  const shown = codes.slice(0, SUBTITLE_LANGS);
  const extra = codes.length - shown.length;
  return 'CC · ' + shown.join(' ') + (extra > 0 ? ` +${extra}` : '');
}

/**
 * Tech boxes for one source, in order: resolution, video codec, HDR type, `EN · AAC STEREO`, `CC · EN ES` (up to
 * three languages, then `+n`).
 */
export function techBoxes(source: MediaSourceInfo | null | undefined): string[] {
  const streams = source?.MediaStreams ?? [];
  const video = streams.find((s) => s.Type === 'Video');
  const audio = streams.find((s) => s.Type === 'Audio' && s.IsDefault === true) ?? streams.find((s) => s.Type === 'Audio');
  const out: string[] = [];
  if (video !== undefined) {
    const w = video.Width ?? 0;
    const h = video.Height ?? 0;
    if (w > 0 && h > 0) out.push(resolutionString(w, h, video.IsInterlaced === true));
    if (video.Codec != null && video.Codec.trim() !== '') out.push(video.Codec.toUpperCase());
    const hdr = hdrLabel(video);
    if (hdr !== null) out.push(hdr);
  }
  if (audio !== undefined) {
    const a = audioLabel(audio);
    if (a !== null) out.push(a);
  }
  const cc = subtitleLabel(streams);
  if (cc !== null) out.push(cc);
  return out;
}

// ---- resume --------------------------------------------------------------------------------------------------

/** The share watched for a RESUME label: at least 1 once there is a resume point. */
export function resumeShare(item: BaseItemDto): number | null {
  const position = item.UserData?.PlaybackPositionTicks ?? 0;
  if (position <= 0) return null;
  return Math.max(1, resumePercent(position, item.RunTimeTicks ?? 0));
}

export function positionMs(item: BaseItemDto): number {
  return Math.floor((item.UserData?.PlaybackPositionTicks ?? 0) / 10_000);
}

/** Clock position for a chapter: `30:00`, `01:00`, `1:02:03`. */
export function formatPosition(ticks: number): string {
  const total = Math.max(0, Math.floor(ticks / 10_000_000));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const p = (n: number): string => (n < 10 ? '0' : '') + String(n);
  return h > 0 ? `${h}:${p(m)}:${p(s)}` : `${p(m)}:${p(s)}`;
}

// ---- series --------------------------------------------------------------------------------------------------

/** `S1 E3`, `S1 E3–4` for a double episode, `SPECIAL` for season 0; missing numbers left out; null when none. */
export function episodeCode(season: number | null | undefined, episode: number | null | undefined, indexNumberEnd?: number | null): string | null {
  if (season === 0) return 'SPECIAL';
  const e = episode != null ? (indexNumberEnd != null && indexNumberEnd > episode ? `E${episode}–${indexNumberEnd}` : `E${episode}`) : null;
  const out = [season != null ? `S${season}` : null, e].filter((x) => x !== null).join(' ');
  return out === '' ? null : out;
}

/** `E03` for the rundown's number column (a double episode shows its first number). */
export function episodeNumber(episode: number | null | undefined): string | null {
  if (episode == null) return null;
  return 'E' + (episode < 10 ? '0' : '') + String(episode);
}

/** `Season 2`, `Specials` for season 0, else the season's own name. */
export function seasonLabel(season: BaseItemDto): string {
  if (season.IndexNumber === 0) return 'Specials';
  if (season.IndexNumber != null) return `Season ${season.IndexNumber}`;
  return season.Name ?? '';
}

export interface SeasonSummary {
  episodes: number;
  left: number;
  remainingTicks: number;
}

/** How many episodes, how many not yet played, and the runtime still to watch (less what is watched of any). */
export function seasonSummary(episodes: readonly BaseItemDto[]): SeasonSummary {
  let left = 0;
  let remaining = 0;
  for (const e of episodes) {
    if (e.UserData?.Played !== true) {
      left++;
      remaining += Math.max(0, (e.RunTimeTicks ?? 0) - (e.UserData?.PlaybackPositionTicks ?? 0));
    }
  }
  return { episodes: episodes.length, left, remainingTicks: remaining };
}

/** `7 EPISODES · 5 LEFT · 4m 35s` (before uppercasing). */
export function seasonSummaryText(summary: SeasonSummary): string {
  const parts = [plural(summary.episodes, 'episode', 'episodes'), `${summary.left} left`];
  if (summary.left > 0 && summary.remainingTicks > 0) parts.push(formatRuntime(summary.remainingTicks));
  return parts.join(' · ');
}

/** What the series page's primary button says. */
export type NextUpLabel = { kind: 'play' } | { kind: 'nextUp'; code: string } | { kind: 'resume'; code: string; percent: number };

export function nextUpLabel(nextUp: BaseItemDto | null): NextUpLabel {
  if (nextUp === null) return { kind: 'play' };
  const code = episodeCode(nextUp.ParentIndexNumber, nextUp.IndexNumber, nextUp.IndexNumberEnd);
  if (code === null) return { kind: 'play' };
  const position = nextUp.UserData?.PlaybackPositionTicks ?? 0;
  if (nextUp.UserData?.Played !== true && position > 0) {
    return { kind: 'resume', code, percent: Math.max(1, resumePercent(position, nextUp.RunTimeTicks ?? 0)) };
  }
  return { kind: 'nextUp', code };
}

export function nextUpText(label: NextUpLabel): string {
  switch (label.kind) {
    case 'play':
      return 'Play';
    case 'nextUp':
      return `Next up · ${label.code}`;
    case 'resume':
      return `Resume ${label.code} · ${label.percent}%`;
  }
}

/**
 * Share of a season watched, 0–1: the server's PlayedPercentage when it sent one, else from ChildCount (episodes)
 * and UnplayedItemCount; null when neither is known.
 */
export function seasonWatchedFraction(season: BaseItemDto): number | null {
  const percent = season.UserData?.PlayedPercentage;
  if (percent != null) return Math.min(1, Math.max(0, percent / 100));
  const episodes = season.ChildCount;
  const unplayed = season.UserData?.UnplayedItemCount;
  if (episodes == null || episodes <= 0 || unplayed == null) return null;
  return Math.min(1, Math.max(0, (episodes - unplayed) / episodes));
}

/**
 * The rundown row to focus coming DOWN from the tabs: the one last focused in this season, else next up, else the
 * first in progress, else the first.
 */
export function rowForDown(episodes: readonly BaseItemDto[], last: number | null, nextUpId: string | null): number | null {
  if (episodes.length === 0) return null;
  if (last !== null && last >= 0 && last < episodes.length) return last;
  if (nextUpId !== null) {
    const i = episodes.findIndex((e) => e.Id === nextUpId);
    if (i >= 0) return i;
  }
  const inProgress = episodes.findIndex((e) => e.UserData?.Played !== true && (e.UserData?.PlaybackPositionTicks ?? 0) > 0);
  return inProgress >= 0 ? inProgress : 0;
}

/** The season card's detail kicker on the episode page's season row: `E04 · 47m`. */
export function seasonCardKicker(item: BaseItemDto): string | null {
  const n = episodeNumber(item.IndexNumber);
  const runtime = (item.RunTimeTicks ?? 0) > 0 ? formatRuntime(item.RunTimeTicks ?? 0) : null;
  if (n !== null && runtime !== null) return `${n} · ${runtime}`;
  return n ?? runtime;
}

// ---- chapters and extras -------------------------------------------------------------------------------------

export interface Chapter {
  index: number;
  name: string;
  positionTicks: number;
  imageTag: string | null;
}

export function chaptersOf(item: BaseItemDto): Chapter[] {
  return (item.Chapters ?? []).map((c, index) => ({
    index,
    name: c.Name ?? '',
    positionTicks: c.StartPositionTicks ?? 0,
    imageTag: c.ImageTag ?? null,
  }));
}

/** `CHAPTER 3 · 01:00` (before uppercasing). */
export function chapterKicker(chapter: Chapter): string {
  return `Chapter ${chapter.index + 1} · ${formatPosition(chapter.positionTicks)}`;
}

const EXTRA_ORDER = ['Trailer', 'Featurette', 'Short', 'Clip', 'Scene', 'Sample', 'DeletedScene', 'Interview', 'BehindTheScenes', 'ThemeSong', 'ThemeVideo', 'Unknown'];
const EXTRA_ONE: Record<string, string> = {
  Trailer: 'Trailer', Featurette: 'Featurette', Short: 'Short', Clip: 'Clip', Scene: 'Scene', Sample: 'Sample',
  DeletedScene: 'Deleted scene', Interview: 'Interview', BehindTheScenes: 'Behind the scenes', Unknown: 'Other',
};

export interface Extra {
  item: BaseItemDto;
  /** The card's accent kicker: the kind of extra. */
  kicker: string;
  title: string;
}

/**
 * The extras row (upstream's ExtrasService): special features minus theme songs, theme videos and trailers (the
 * TRAILER button has those), in upstream's order of kinds. Each extra is its own card (upstream folds several of a
 * kind into one card that opens a grid; a TV web app has no such grid page, so they are listed).
 */
export function extrasOf(features: readonly BaseItemDto[]): Extra[] {
  const kept = features.filter((f) => f.ExtraType !== 'ThemeSong' && f.ExtraType !== 'ThemeVideo' && f.ExtraType !== 'Trailer');
  const rank = (t: string | null | undefined): number => {
    const i = EXTRA_ORDER.indexOf(t ?? 'Unknown');
    return i < 0 ? EXTRA_ORDER.length : i;
  };
  return kept
    .map((item, i) => ({ item, i }))
    .sort((a, b) => rank(a.item.ExtraType) - rank(b.item.ExtraType) || a.i - b.i)
    .map(({ item }) => {
      const kind = EXTRA_ONE[item.ExtraType ?? 'Unknown'] ?? 'Other';
      const name = item.Name != null && item.Name.trim() !== '' ? item.Name : kind;
      return { item, kicker: kind, title: name };
    });
}

// ---- people --------------------------------------------------------------------------------------------------

/**
 * A person's life line: `Born Jul 30, 1970 · Westminster, London` while living, `1928–2016 · Chicago` after. Null
 * when nothing is known.
 */
export function personLifeLine(born: string | null | undefined, died: string | null | undefined, place: string | null | undefined): string | null {
  const b = serverDate(born);
  const d = serverDate(died);
  let dates: string | null = null;
  if (d !== null && b !== null) dates = b.getUTCFullYear() === d.getUTCFullYear() ? String(b.getUTCFullYear()) : `${b.getUTCFullYear()}–${d.getUTCFullYear()}`;
  else if (d !== null) dates = `–${d.getUTCFullYear()}`;
  else if (b !== null) dates = 'Born ' + (monthFirstDate(born) ?? '');
  const out = [dates, place != null && place.trim() !== '' ? place.trim() : null].filter((x) => x !== null).join(' · ');
  return out === '' ? null : out;
}

const ROLE_ORDER = ['Actor', 'Director', 'Writer', 'Producer', 'Composer', 'GuestStar'];
const ROLE_LABEL: Record<string, string> = {
  Actor: 'Actor', Director: 'Director', Writer: 'Writer', Producer: 'Producer', Composer: 'Composer', GuestStar: 'Guest star',
};

/** The credit a person holds most often (one entry per credit); ties go to ROLE_ORDER. Null when none is known. */
export function primaryRole(credits: readonly string[]): string | null {
  const counts: Record<string, number> = {};
  for (const c of credits) if (c !== 'Unknown' && c !== '') counts[c] = (counts[c] ?? 0) + 1;
  const kinds = Object.keys(counts);
  if (kinds.length === 0) return null;
  const rank = (k: string): number => {
    const i = ROLE_ORDER.indexOf(k);
    return i < 0 ? ROLE_ORDER.length : i;
  };
  kinds.sort((a, b) => (counts[b] ?? 0) - (counts[a] ?? 0) || rank(a) - rank(b) || (a < b ? -1 : a > b ? 1 : 0));
  return kinds[0] ?? null;
}

/** The person page kicker: `ACTOR`, `DIRECTOR`, or `PERSON` when unknown. */
export function roleLabel(role: string | null): string {
  return role !== null ? (ROLE_LABEL[role] ?? 'Person') : 'Person';
}

/** The role under a name on a person card: the character for actors, else the kind of credit. */
export function personCardRole(person: { Role?: string | null; Type?: string }): string | null {
  if (person.Role != null && person.Role.trim() !== '') return person.Role;
  if (person.Type != null && person.Type !== 'Actor' && person.Type !== 'Unknown') return ROLE_LABEL[person.Type] ?? person.Type;
  return null;
}

/** An episode's kicker in a person's rows: `2008 · BREAKING BAD · S1 E3`. */
export function creditKicker(item: BaseItemDto): string | null {
  if (item.Type !== 'Episode') return null;
  const aired = serverDate(item.PremiereDate);
  const year = item.ProductionYear != null ? String(item.ProductionYear) : aired !== null ? String(aired.getUTCFullYear()) : null;
  const out = [year, item.SeriesName ?? null, episodeCode(item.ParentIndexNumber, item.IndexNumber, item.IndexNumberEnd)]
    .filter((x) => x !== null && x !== '')
    .join(' · ');
  return out === '' ? null : out;
}

// ---- the next film of a collection ---------------------------------------------------------------------------

export interface CollectionEntry {
  id: string;
  collectionId: string;
  premiereDate: string | null;
  productionYear: number | null;
  sortName: string;
}

/** Films carrying a TMDb collection id (`ProviderIds.TmdbCollection`), as the Android app indexes them. */
export function collectionEntries(movies: readonly BaseItemDto[]): CollectionEntry[] {
  const out: CollectionEntry[] = [];
  for (const m of movies) {
    const collectionId = m.ProviderIds?.['TmdbCollection'];
    if (m.Id == null || collectionId == null || collectionId.trim() === '') continue;
    out.push({
      id: m.Id,
      collectionId,
      premiereDate: m.PremiereDate ?? null,
      productionYear: m.ProductionYear ?? null,
      sortName: m.SortName != null && m.SortName.trim() !== '' ? m.SortName : (m.Name ?? ''),
    });
  }
  return out;
}

function compareNullsLast<T>(a: T | null, b: T | null, cmp: (x: T, y: T) => number): number {
  if (a === null && b === null) return 0;
  if (a === null) return 1;
  if (b === null) return -1;
  return cmp(a, b);
}

/**
 * The film after `currentId` among the films of its collection, by premiere date, then production year (unknowns
 * last), then sort name (the Android CollectionNext order: Toy Story → Toy Story 2). Null when last or alone.
 */
export function nextInCollection(entries: readonly CollectionEntry[], currentId: string): string | null {
  const current = entries.find((e) => e.id === currentId);
  if (current === undefined) return null;
  const siblings = entries
    .filter((e) => e.collectionId === current.collectionId)
    .map((e, i) => ({ e, i }))
    .sort(
      (a, b) =>
        compareNullsLast(a.e.premiereDate === null ? null : Date.parse(a.e.premiereDate), b.e.premiereDate === null ? null : Date.parse(b.e.premiereDate), (x, y) => x - y) ||
        compareNullsLast(a.e.productionYear, b.e.productionYear, (x, y) => x - y) ||
        (a.e.sortName < b.e.sortName ? -1 : a.e.sortName > b.e.sortName ? 1 : 0) ||
        a.i - b.i,
    )
    .map((x) => x.e);
  const index = siblings.findIndex((e) => e.id === currentId);
  return siblings[index + 1]?.id ?? null;
}
