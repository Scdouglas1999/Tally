/*
 * The Tally Client API v1 contract (server: Jellyfin.Plugin.Tally, Client/BoardModels.cs; the Android app's
 * api/TallyModels.kt is the reference client). Decode leniently: unknown keys are ignored, missing keys take the
 * defaults below. `heat` and `tags` exist in the payload for other clients and are deliberately not modeled.
 * Never decide what the server already decided: which channel to watch is `watch`, full stop.
 */

export interface TallyInfo {
  apiVersion: number;
  pluginBuild: string | null;
  features: string[];
  pollSeconds: number;
  latestEventId: number;
}

export interface TallyTeam {
  id: string;
  abbr: string;
  name: string;
  shortName: string;
  location: string;
  logo: string;
  score: number | null;
  record: string | null;
  possession: boolean;
  winner: boolean;
  periods: number[];
  color: string;
  altColor: string;
}

export interface TallyWatch {
  channelId: string;
  channelName: string;
  liveTvItemId: string | null;
  /** Root-relative, signed, anonymous HLS (the plugin's continuous playlist). */
  hlsPath: string;
  cardPath: string;
  /** "teams" | "epg" | "network" */
  confidence: string;
  /** The commentary language of the channel: "en" (also when the server does not say) or "es" (2.3 contract). */
  language: string;
}

/**
 * One commentary language of a game (2.3 contract, `GameInfo.feeds`): present only when a game has more than one
 * playable language, English first. `watch` plays that language; the game's own `watch` is already the viewer's
 * preferred one (the server decides, from the `streamLanguage` setting).
 */
export interface TallyFeed {
  language: string;
  /** "English" | "Español" */
  label: string;
  watch: TallyWatch;
}

/**
 * A game's recording as the board shows it (the server DVR's most relevant job for the game, feature `dvr`). Never
 * carries a score. States: scheduled | waiting | recording | finishing | done | failed | canceled.
 */
export interface TallyGameRecording {
  state: string;
  jobId: string;
  /** Root-relative, signed HLS of everything recorded so far (a growing EVENT playlist), while recording. */
  startOverPath: string | null;
  /** The Jellyfin library item of the finished recording, once the server has scanned it. */
  itemId: string | null;
  /** Whether it plays from a library yet (plugin contract 3, see tallyDvr.ts libraryStateOf). */
  libraryState: 'ready' | 'adding' | 'noLibrary';
  /** Why it is waiting, why it failed, or why it stopped early. */
  reason: string | null;
}

/**
 * The server's stream search for a game (2.2.1 contract, board field `search`): present while the game is live or
 * starts within 30 minutes and has no `watch`. `searching`: a search that covers the game runs now; `waiting`: none
 * runs, the next is due at `nextAt`. Absent (null) on older plugins, for games with a `watch` and for finished games.
 */
export interface TallySearch {
  state: string;
  lastAt: string | null;
  nextAt: string | null;
}

export interface TallyGame {
  id: string;
  sport: string;
  league: string;
  name: string;
  start: string;
  /** pre | in | post */
  state: string;
  detail: string;
  period: number;
  clock: string;
  home: TallyTeam;
  away: TallyTeam;
  lastPlay: string | null;
  downDistance: string | null;
  redZone: boolean;
  balls: number | null;
  strikes: number | null;
  outs: number | null;
  onFirst: boolean;
  onSecond: boolean;
  onThird: boolean;
  broadcasts: string[];
  watch: TallyWatch | null;
  backdropPath: string | null;
  /** The server DVR's job for this game; null when there is none (or the server does not record). */
  recording: TallyGameRecording | null;
  /** The server's stream search for this game (see TallySearch); null when the board has none. */
  search: TallySearch | null;
  /** The game's commentary languages when it has more than one (see TallyFeed); empty otherwise and on older plugins. */
  feeds: TallyFeed[];
}

export interface TallyProgramme {
  title: string;
  start: string;
  end: string;
}

export interface TallyStreamStatus {
  label: string;
  firstChoice: boolean;
  candidates: number;
  live: boolean;
}

export interface TallyChannel {
  id: string;
  name: string;
  group: string;
  logo: string | null;
  liveTvItemId: string | null;
  hlsPath: string;
  cardPath: string;
  gameId: string | null;
  now: TallyProgramme | null;
  next: TallyProgramme | null;
  stream: TallyStreamStatus | null;
  /** "en" (also when the server does not say) or "es" (2.3 contract: a Spanish sibling is named "… (Español)"). */
  language: string;
  /** "redzone" for the server's RedZone channel (2.3 contract); empty for every other channel. */
  kind: string;
}

/**
 * What the RedZone channel shows now (`GET /JellyTV/Client/v1/redzone`, 2.3 contract). `reason`: "red zone" |
 * "score" | "two-minute drill" | "overtime" | "close" | "hottest", or null.
 */
export interface TallyRedZone {
  active: boolean;
  gameId: string | null;
  title: string | null;
  reason: string | null;
  since: string | null;
  next: string[];
  /**
   * The channel's last cuts as its players got them, oldest first (2.3; empty from an older server and while nobody
   * watches): each `since` is when that cut entered the channel's playlist.
   */
  recent: TallyRedZoneCut[];
  /** The server's clock when it answered (ISO-8601; null from an older server). */
  serverTime: string | null;
}

/** One of `TallyRedZone.recent`: from `since` on the channel carries `gameId` (inactive: the "No games live" slate). */
export interface TallyRedZoneCut {
  active: boolean;
  gameId: string | null;
  title: string | null;
  reason: string | null;
  since: string | null;
}

export interface TallyEvent {
  id: number;
  source: string;
  kind: string;
  gameId: string | null;
  title: string;
  text: string;
  createdAt: string;
  watch: TallyWatch | null;
}

export interface TallyBoard {
  serverTime: string;
  games: TallyGame[];
  channels: TallyChannel[];
  events: TallyEvent[];
  errors: Record<string, string>;
}

/** Per-user settings shared with the web UI and the Android app. Unknown keys must round-trip (see TallyApi.putSettings). */
export interface TallySettings {
  favorites: string[];
  hideScores: boolean;
  lastChannel: string | null;
  onlyWatchable: boolean | null;
  favoriteTeams: string[];
  /** The commentary language the viewer prefers (2.3 contract): "en" unless the viewer chose "es". */
  streamLanguage: StreamLanguage;
}

export type StreamLanguage = 'en' | 'es';

type Json = Record<string, unknown>;

const obj = (v: unknown): Json => (v !== null && typeof v === 'object' && !Array.isArray(v) ? (v as Json) : {});
const str = (v: unknown, d = ''): string => (typeof v === 'string' ? v : d);
const strOrNull = (v: unknown): string | null => (typeof v === 'string' ? v : null);
const num = (v: unknown, d = 0): number => (typeof v === 'number' && isFinite(v) ? v : d);
const numOrNull = (v: unknown): number | null => (typeof v === 'number' && isFinite(v) ? v : null);
const bool = (v: unknown, d = false): boolean => (typeof v === 'boolean' ? v : d);
const arr = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);

export function decodeInfo(v: unknown): TallyInfo {
  const o = obj(v);
  return {
    apiVersion: num(o.apiVersion),
    pluginBuild: strOrNull(o.pluginBuild),
    features: arr(o.features).filter((f): f is string => typeof f === 'string'),
    pollSeconds: num(o.pollSeconds, 15),
    latestEventId: num(o.latestEventId),
  };
}

function decodeTeam(v: unknown): TallyTeam {
  const o = obj(v);
  return {
    id: str(o.id),
    abbr: str(o.abbr),
    name: str(o.name),
    shortName: str(o.shortName),
    location: str(o.location),
    logo: str(o.logo),
    score: numOrNull(o.score),
    record: strOrNull(o.record),
    possession: bool(o.possession),
    winner: bool(o.winner),
    periods: arr(o.periods).filter((p): p is number => typeof p === 'number'),
    color: str(o.color),
    altColor: str(o.altColor),
  };
}

export function decodeWatch(v: unknown): TallyWatch | null {
  if (v === null || v === undefined) return null;
  const o = obj(v);
  return {
    channelId: str(o.channelId),
    channelName: str(o.channelName),
    liveTvItemId: strOrNull(o.liveTvItemId),
    hlsPath: str(o.hlsPath),
    cardPath: str(o.cardPath),
    confidence: str(o.confidence),
    language: language(o.language),
  };
}

/** A language code as the apps use it: lowercase, "en" when missing. */
function language(v: unknown): string {
  const code = str(v).trim().toLowerCase();
  return code === '' ? 'en' : code;
}

/** English first; a feed without a stream is no feed; one language alone is no choice (empty). */
function decodeFeeds(v: unknown): TallyFeed[] {
  const feeds: TallyFeed[] = [];
  for (const f of arr(v)) {
    const o = obj(f);
    const lang = language(o.language);
    const watch = decodeWatch(o.watch);
    if (watch === null || watch.hlsPath === '' || feeds.some((x) => x.language === lang)) continue;
    feeds.push({ language: lang, label: str(o.label) !== '' ? str(o.label) : languageLabel(lang), watch: { ...watch, language: lang } });
  }
  feeds.sort((a, b) => (a.language === 'en' ? 0 : 1) - (b.language === 'en' ? 0 : 1));
  return feeds.length > 1 ? feeds : [];
}

export function decodeGame(v: unknown): TallyGame {
  const o = obj(v);
  return {
    id: str(o.id),
    sport: str(o.sport),
    league: str(o.league),
    name: str(o.name),
    start: str(o.start),
    state: str(o.state, 'pre'),
    detail: str(o.detail),
    period: num(o.period),
    clock: str(o.clock),
    home: decodeTeam(o.home),
    away: decodeTeam(o.away),
    lastPlay: strOrNull(o.lastPlay),
    downDistance: strOrNull(o.downDistance),
    redZone: bool(o.redZone),
    balls: numOrNull(o.balls),
    strikes: numOrNull(o.strikes),
    outs: numOrNull(o.outs),
    onFirst: bool(o.onFirst),
    onSecond: bool(o.onSecond),
    onThird: bool(o.onThird),
    broadcasts: arr(o.broadcasts).filter((b): b is string => typeof b === 'string'),
    watch: decodeWatch(o.watch),
    backdropPath: strOrNull(o.backdropPath),
    recording: decodeRecording(o.recording),
    search: decodeSearch(o.search),
    feeds: decodeFeeds(o.feeds),
  };
}

function decodeSearch(v: unknown): TallySearch | null {
  if (v === null || v === undefined) return null;
  const o = obj(v);
  return { state: str(o.state), lastAt: strOrNull(o.lastAt), nextAt: strOrNull(o.nextAt) };
}

function decodeRecording(v: unknown): TallyGameRecording | null {
  if (v === null || v === undefined) return null;
  const o = obj(v);
  return {
    state: str(o.state),
    jobId: str(o.jobId),
    startOverPath: strOrNull(o.startOverPath),
    itemId: strOrNull(o.itemId),
    libraryState: strOrNull(o.itemId) !== null && o.itemId !== '' ? 'ready' : o.libraryState === 'noLibrary' ? 'noLibrary' : 'adding',
    reason: strOrNull(o.reason),
  };
}

function decodeProgramme(v: unknown): TallyProgramme | null {
  if (v === null || v === undefined) return null;
  const o = obj(v);
  return { title: str(o.title), start: str(o.start), end: str(o.end) };
}

export function decodeChannel(v: unknown): TallyChannel {
  const o = obj(v);
  const stream = o.stream === null || o.stream === undefined ? null : obj(o.stream);
  return {
    id: str(o.id),
    name: str(o.name),
    group: str(o.group),
    logo: strOrNull(o.logo),
    liveTvItemId: strOrNull(o.liveTvItemId),
    hlsPath: str(o.hlsPath),
    cardPath: str(o.cardPath),
    gameId: strOrNull(o.gameId),
    now: decodeProgramme(o.now),
    next: decodeProgramme(o.next),
    stream:
      stream === null
        ? null
        : {
            label: str(stream.label),
            firstChoice: bool(stream.firstChoice),
            candidates: num(stream.candidates),
            live: bool(stream.live),
          },
    language: language(o.language),
    kind: str(o.kind),
  };
}

export function decodeRedZone(v: unknown): TallyRedZone {
  const o = obj(v);
  return {
    active: bool(o.active),
    gameId: strOrNull(o.gameId),
    title: strOrNull(o.title),
    reason: strOrNull(o.reason),
    since: strOrNull(o.since),
    next: arr(o.next).filter((x): x is string => typeof x === 'string'),
    recent: arr(o.recent).map((c) => {
      const x = obj(c);
      return { active: bool(x.active), gameId: strOrNull(x.gameId), title: strOrNull(x.title), reason: strOrNull(x.reason), since: strOrNull(x.since) };
    }),
    serverTime: strOrNull(o.serverTime),
  };
}

export function decodeBoard(v: unknown): TallyBoard {
  const o = obj(v);
  const errors: Record<string, string> = {};
  const rawErrors = obj(o.errors);
  for (const k of Object.keys(rawErrors)) errors[k] = str(rawErrors[k]);
  return {
    serverTime: str(o.serverTime),
    games: arr(o.games).map(decodeGame),
    channels: arr(o.channels).map(decodeChannel),
    events: arr(o.events).map((e) => {
      const x = obj(e);
      return {
        id: num(x.id),
        source: str(x.source),
        kind: str(x.kind),
        gameId: strOrNull(x.gameId),
        title: str(x.title),
        text: str(x.text),
        createdAt: str(x.createdAt),
        watch: decodeWatch(x.watch),
      };
    }),
    errors,
  };
}

export function decodeSettings(v: unknown): TallySettings {
  const o = obj(v);
  return {
    favorites: arr(o.favorites).filter((f): f is string => typeof f === 'string'),
    hideScores: bool(o.hideScores),
    lastChannel: strOrNull(o.lastChannel),
    onlyWatchable: typeof o.onlyWatchable === 'boolean' ? o.onlyWatchable : null,
    favoriteTeams: arr(o.favoriteTeams).filter((f): f is string => typeof f === 'string'),
    streamLanguage: o.streamLanguage === 'es' ? 'es' : 'en',
  };
}

export const isLive = (g: TallyGame): boolean => g.state === 'in';
export const isUpcoming = (g: TallyGame): boolean => g.state === 'pre';
export const isFinal = (g: TallyGame): boolean => g.state === 'post';

/** The game has a stream this server can play now (a `watch` with its continuous playlist). */
export const hasStream = (g: TallyGame): boolean => g.watch !== null && g.watch.hlsPath !== '';

/**
 * WATCH is offered: every game that is not final (one without a stream searches for one first), and a finished game
 * still on a channel. Never blocked by a missing stream (2.2.1 contract, "never block").
 */
export const canWatch = (g: TallyGame): boolean => hasStream(g) || !isFinal(g);

/** What a game's black label bar says when it has no stream: the server is searching now, or not yet found. */
export function noStreamLabel(g: TallyGame): string {
  if (g.search !== null && g.search.state === 'searching') return 'Looking for a stream';
  return isFinal(g) ? 'No stream' : 'No stream yet';
}

/** The settings key under which a team is followed ("NFL:KC"). */
export const teamKey = (g: TallyGame, t: TallyTeam): string => g.league.toUpperCase() + ':' + t.abbr.toUpperCase();

export const isFollowed = (g: TallyGame, teams: ReadonlySet<string>): boolean =>
  teams.has(teamKey(g, g.away)) || teams.has(teamKey(g, g.home));

/** How a language is named in the apps ("Español" on purpose: it is what a Spanish-speaking viewer looks for). */
export function languageLabel(code: string): string {
  switch (code) {
    case 'en':
      return 'English';
    case 'es':
      return 'Español';
    default:
      return code.toUpperCase();
  }
}

/** A Spanish stream: the card's "ES" chip. */
export const isSpanish = (w: TallyWatch | null): boolean => w !== null && w.language === 'es';

/** The feed `channelId` plays (the game's own `watch` when the channel is none of its feeds). */
export function playingFeed(game: TallyGame, channelId: string): TallyFeed | null {
  return game.feeds.find((f) => f.watch.channelId === channelId) ?? null;
}

/**
 * The game's other commentary language: the feed that is not the one playing (`channelId`), or not the game's
 * `watch` when nothing plays. Null when the game has one language.
 */
export function otherFeed(game: TallyGame, channelId?: string): TallyFeed | null {
  if (game.feeds.length < 2) return null;
  const current = channelId !== undefined ? playingFeed(game, channelId) : null;
  const lang = current !== null ? current.language : (game.watch?.language ?? 'en');
  return game.feeds.find((f) => f.language !== lang) ?? null;
}

/** The game as it plays in `feed` (its `watch` swapped: the route, the title and the switcher follow). */
export const withFeed = (game: TallyGame, feed: TallyFeed): TallyGame => ({ ...game, watch: feed.watch });

/** The server's RedZone channel on the board (2.3 contract, `kind: "redzone"`), if it has one with a stream. */
export function redZoneChannel(board: TallyBoard | null): TallyChannel | null {
  return board?.channels.find((c) => c.kind === 'redzone' && c.hlsPath !== '') ?? null;
}

/** The games board's RedZone tile shows while the board has the channel and the server says it is on the air. */
export function redZoneTileShown(board: TallyBoard | null, status: TallyRedZone | null): boolean {
  return redZoneChannel(board) !== null && status !== null && status.active;
}

/** How a RedZone cut's reason is written on screen ("RED ZONE", "TWO-MINUTE DRILL"; nothing for none). */
export function redZoneReasonLabel(reason: string | null): string {
  return reason !== null ? reason.trim().toUpperCase() : '';
}
