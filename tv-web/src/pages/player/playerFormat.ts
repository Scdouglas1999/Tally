/**
 * Text and arithmetic for the player controls: a port of the Android app's PlayerFormat.kt (tally/ui/player/
 * controls), with the upstream numbers the controls use (seek steps, timeouts, the hold-to-seek profile, the
 * trickplay tile math). Pure, so the labels are checked against items captured from the dev server.
 */
import type { BaseItemDto } from '@jellyfin/sdk/lib/generated-client/models/base-item-dto';
import type { MediaSegmentDto } from '@jellyfin/sdk/lib/generated-client/models/media-segment-dto';
import type { MediaStream } from '@jellyfin/sdk/lib/generated-client/models/media-stream';
import type { TrickplayInfoDto } from '@jellyfin/sdk/lib/generated-client/models/trickplay-info-dto';
import { formatRuntime, formatTime, tallyUppercase } from '../../util/format';

/** Upstream's defaults (AppPreference.kt): skip back 10 s, skip forward 30 s, controls hide after 5 s. */
export const SEEK_BACK_MS = 10_000;
export const SEEK_FORWARD_MS = 30_000;
export const CONTROLS_HIDE_MS = 5000;
/** The seek bar seeks 750 ms after the last move (SeekBarState). */
export const SEEK_DEBOUNCE_MS = 750;
/** The minimal D-pad seek bar stays this long after the last press (TallyDpadSeekMinimal). */
export const MINIMAL_LINGER_MS = 800;
/** Next up: auto-play after 15 s, unless nothing was pressed for 2 hours (pass-out protection). */
export const AUTO_PLAY_DELAY_S = 15;
export const PASS_OUT_MS = 2 * 60 * 60 * 1000;
/** The skip prompt goes away by itself after 10 s. */
export const SKIP_PROMPT_MS = 10_000;
/** Previous restarts the item unless it is within its first 3 s (media3's maxSeekToPreviousPosition). */
export const PREVIOUS_RESTART_MS = 3000;
/** Held arrow keys start accelerating after this many repeats (SeekAcceleration.kt). */
export const HOLD_REPEAT_START = 8;

const TICKS_PER_MS = 10_000;

const pad2 = (n: number): string => (n < 10 ? '0' : '') + String(n);

/** A playback position as a clock: `0:45`, `12:34`, `1:02:03`. */
export function clock(ms: number): string {
  const total = Math.floor(Math.max(0, ms) / 1000);
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  return h > 0 ? `${h}:${pad2(m)}:${pad2(s)}` : `${m}:${pad2(s)}`;
}

/** Clock position for a chapter: `30:00`, `01:00`, `1:02:03` (DetailHeader.kt formatPosition). */
export function position(ms: number): string {
  if (ms <= 0) return '00:00';
  const total = Math.floor(ms / 1000);
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  return h > 0 ? `${h}:${pad2(m)}:${pad2(s)}` : `${pad2(m)}:${pad2(s)}`;
}

/** Time left, with a minus sign: `-34:28`. */
export function remaining(positionMs: number, durationMs: number): string {
  return '-' + clock(Math.max(0, durationMs - positionMs));
}

/** Real time left at the current speed, for the end time. */
export function remainingRealMs(positionMs: number, durationMs: number, speed: number): number {
  const left = Math.max(0, durationMs - positionMs);
  return speed > 0 ? Math.round(left / speed) : left;
}

/** `ENDS 9:41 PM`. */
export function endsAt(now: Date, remainingMs: number): string {
  return 'ENDS ' + formatTime(new Date(now.getTime() + remainingMs));
}

/** `1×`, `0.25×`, `1.5×`. */
export function speedLabel(value: number): string {
  return String(Math.round(value * 100) / 100) + '×';
}

/** Upstream's speed choices (PlaybackConstants.kt). */
export const SPEEDS: readonly number[] = [0.25, 0.5, 0.75, 1, 1.25, 1.5, 1.75, 2];

export type Kind = 'film' | 'episode' | 'live' | 'other';

export function kind(item: BaseItemDto | null): Kind {
  switch (item?.Type) {
    case 'Movie':
      return 'film';
    case 'Episode':
      return 'episode';
    case 'TvChannel':
    case 'LiveTvChannel':
    case 'Channel':
    case 'Program':
    case 'LiveTvProgram':
    case 'TvProgram':
      return 'live';
    default:
      return 'other';
  }
}

/** `S1 E3`, `S1 E3–4` for a double episode, `SPECIAL` for season 0 (SeriesFormat.kt episodeCode). */
export function episodeCode(season: number | null | undefined, episode: number | null | undefined, end?: number | null): string | null {
  if (season === 0) return 'SPECIAL';
  const e = episode != null ? (end != null && end > episode ? `E${episode}–${end}` : `E${episode}`) : null;
  const parts = [season != null ? `S${season}` : null, e].filter((x): x is string => x !== null);
  return parts.length > 0 ? parts.join(' ') : null;
}

/** The kicker over the title: `FILM`, `BREAKING BAD · S1 E3`, `LIVE`, or nothing. */
export function kicker(item: BaseItemDto | null): string | null {
  switch (kind(item)) {
    case 'film':
      return 'FILM';
    case 'live':
      return 'LIVE';
    case 'episode': {
      const parts = [item?.SeriesName ?? '', episodeCode(item?.ParentIndexNumber, item?.IndexNumber, item?.IndexNumberEnd) ?? ''].filter((x) => x.trim() !== '');
      return parts.length > 0 ? tallyUppercase(parts.join(' · ')) : null;
    }
    default:
      return null;
  }
}

export function title(item: BaseItemDto | null): string | null {
  const name = kind(item) === 'live' ? (item?.CurrentProgram?.Name ?? item?.Name) : item?.Name;
  return name != null && name.trim() !== '' ? name : null;
}

const MONTHS = ['JAN', 'FEB', 'MAR', 'APR', 'MAY', 'JUN', 'JUL', 'AUG', 'SEP', 'OCT', 'NOV', 'DEC'];

/** The calendar date the server meant (its dates are UTC midnights): `FEB 10, 2008`. */
export function airDate(iso: string | null | undefined): string | null {
  if (iso == null) return null;
  const t = Date.parse(iso);
  if (isNaN(t)) return null;
  const d = new Date(t);
  return `${MONTHS[d.getUTCMonth()] ?? ''} ${d.getUTCDate()}, ${d.getUTCFullYear()}`;
}

/** One mono line: `2008 · G` for a film, the air date for an episode, nothing for live. */
export function meta(item: BaseItemDto | null): string | null {
  if (item === null) return null;
  switch (kind(item)) {
    case 'episode':
      return airDate(item.PremiereDate);
    case 'live':
      return null;
    default: {
      const parts = [item.ProductionYear != null ? String(item.ProductionYear) : '', item.OfficialRating ?? ''].filter((x) => x.trim() !== '');
      return parts.length > 0 ? tallyUppercase(parts.join(' · ')) : null;
    }
  }
}

/** ISO 639-2 codes to 639-1 (DetailHeader.kt shortLanguage plus the common ones), for `EN · AAC STEREO`. */
const ISO_639_2: Record<string, [short: string, name: string]> = {
  eng: ['en', 'English'], spa: ['es', 'Spanish'], fra: ['fr', 'French'], fre: ['fr', 'French'], deu: ['de', 'German'],
  ger: ['de', 'German'], ita: ['it', 'Italian'], por: ['pt', 'Portuguese'], jpn: ['ja', 'Japanese'], kor: ['ko', 'Korean'],
  zho: ['zh', 'Chinese'], chi: ['zh', 'Chinese'], rus: ['ru', 'Russian'], nld: ['nl', 'Dutch'], dut: ['nl', 'Dutch'],
  swe: ['sv', 'Swedish'], nor: ['no', 'Norwegian'], nob: ['nb', 'Norwegian Bokmål'], dan: ['da', 'Danish'],
  fin: ['fi', 'Finnish'], pol: ['pl', 'Polish'], tur: ['tr', 'Turkish'], ara: ['ar', 'Arabic'], heb: ['he', 'Hebrew'],
  hin: ['hi', 'Hindi'], tha: ['th', 'Thai'], vie: ['vi', 'Vietnamese'], ces: ['cs', 'Czech'], cze: ['cs', 'Czech'],
  ell: ['el', 'Greek'], gre: ['el', 'Greek'], hun: ['hu', 'Hungarian'], ron: ['ro', 'Romanian'], rum: ['ro', 'Romanian'],
  ukr: ['uk', 'Ukrainian'], ind: ['id', 'Indonesian'], msa: ['ms', 'Malay'], may: ['ms', 'Malay'], cat: ['ca', 'Catalan'],
  tgl: ['tl', 'Tagalog'], fil: ['fil', 'Filipino'],
};

export function shortLanguage(code: string | null | undefined): string | null {
  if (code == null || code.trim() === '' || code.toLowerCase() === 'und') return null;
  if (code.length === 2) return code.toUpperCase();
  const known = ISO_639_2[code.toLowerCase()];
  return (known !== undefined ? known[0] : code.substring(0, 2)).toUpperCase();
}

/** `English`, `Spanish` for a stream's language code, or null (Android: Locale display name). */
export function languageName(code: string | null | undefined): string | null {
  if (code == null || code.trim() === '' || code.toLowerCase() === 'und') return null;
  const known = ISO_639_2[code.toLowerCase()];
  if (known !== undefined) return known[1];
  const byShort = Object.keys(ISO_639_2).find((k) => ISO_639_2[k]?.[0] === code.toLowerCase());
  return byShort !== undefined ? (ISO_639_2[byShort]?.[1] ?? null) : null;
}

/** A track's name in the panel: its own title, else its language, else the server's display title. */
export function trackName(stream: MediaStream): string {
  const own = stream.Title;
  if (own != null && own.trim() !== '') return own;
  return languageName(stream.Language) ?? stream.DisplayTitle ?? `Track ${String(stream.Index ?? '')}`;
}

/** An audio track in one line: `EN · AC3 5.1`, `JA · OPUS STEREO`. */
export function audioTrack(stream: MediaStream): string | null {
  const language = shortLanguage(stream.Language);
  const codec = stream.Codec != null && stream.Codec.trim() !== '' ? stream.Codec.toUpperCase() : null;
  const channels =
    stream.ChannelLayout != null && stream.ChannelLayout.trim() !== ''
      ? stream.ChannelLayout.toUpperCase()
      : stream.Channels != null && stream.Channels > 0
        ? `${stream.Channels}CH`
        : null;
  const codecAndChannels = [codec, channels].filter((x): x is string => x !== null).join(' ');
  const parts = [language, codecAndChannels !== '' ? codecAndChannels : null].filter((x): x is string => x !== null);
  return parts.length > 0 ? parts.join(' · ') : null;
}

/** A subtitle track in one line: `EN`, `EN · FORCED`, `ES · EXTERNAL`. */
export function subtitleTrack(stream: MediaStream): string | null {
  const parts: string[] = [];
  const language = shortLanguage(stream.Language);
  if (language !== null) parts.push(language);
  if (stream.IsForced === true) parts.push('FORCED');
  if (stream.IsHearingImpaired === true) parts.push('SDH');
  if (stream.IsExternal === true) parts.push('EXTERNAL');
  if (parts.length === 0) return stream.Codec != null && stream.Codec.trim() !== '' ? stream.Codec.toUpperCase() : null;
  return parts.join(' · ');
}

/** A subtitle offset: `0s`, `+0.25s`, `-1s`. */
export function subtitleDelay(ms: number): string {
  if (ms === 0) return '0s';
  return (ms > 0 ? '+' : '-') + String(Math.abs(ms) / 1000) + 's';
}

/** Upstream's subtitle delay steps (SubtitleDelay), largest first. */
export const DELAY_STEPS_MS: readonly number[] = [1000, 250, 50];

/** Kicker of a chapter card: `CHAPTER 3 · 12:30` (numbered from 1). */
export function chapterKicker(index: number, startMs: number): string {
  return `CHAPTER ${index + 1} · ${position(startMs)}`;
}

/** Index of the chapter playing at `positionMs` (starts sorted ascending), or null before the first / with none. */
export function chapterAt(startsMs: readonly number[], positionMs: number): number | null {
  for (let i = startsMs.length - 1; i >= 0; i--) {
    if ((startsMs[i] as number) <= positionMs) return i;
  }
  return null;
}

/** `E03` (SeriesFormat.kt episodeNumber). */
export function episodeNumber(n: number | null | undefined): string | null {
  return n == null ? null : 'E' + pad2(n);
}

/** Kicker of a queue card: `NEXT` for the first, then `E04 · 47m`. */
export function queueKicker(index: number, item: BaseItemDto): string | null {
  if (index === 0) return 'NEXT';
  const number = item.Type === 'Episode' ? episodeNumber(item.IndexNumber) : null;
  const runtime = item.RunTimeTicks != null && item.RunTimeTicks > 0 ? formatRuntime(item.RunTimeTicks) : null;
  const parts = [number, runtime].filter((x): x is string => x !== null);
  return parts.length > 0 ? tallyUppercase(parts.join(' · ')) : null;
}

/** The next-up line: `S1 E4 · Cancer Man`, or the name alone. */
export function nextUpLine(item: BaseItemDto): string | null {
  const parts = [episodeCode(item.ParentIndexNumber, item.IndexNumber, item.IndexNumberEnd), item.Name != null && item.Name.trim() !== '' ? item.Name : null].filter(
    (x): x is string => x !== null,
  );
  return parts.length > 0 ? parts.join(' · ') : null;
}

/** How full the countdown bar is with `secondsLeft` of `totalSeconds` to go. */
export function countdownFraction(secondsLeft: number, totalSeconds: number): number {
  if (totalSeconds <= 0 || secondsLeft <= 0) return 0;
  return Math.min(1, Math.max(0, secondsLeft / totalSeconds));
}

/** Center of a `square` scrubber at `progress` on a track `width` wide, kept whole inside the track. */
export function scrubberCenter(progress: number, width: number, square: number): number {
  return square / 2 + Math.max(0, width - square) * Math.min(1, Math.max(0, progress));
}

export function fraction(ms: number, durationMs: number): number {
  return durationMs > 0 ? Math.min(1, Math.max(0, ms / durationMs)) : 0;
}

/**
 * Where a trickplay thumbnail is: the tile sheet (`/Videos/{id}/Trickplay/{width}/{sheet}.jpg`) and the column and
 * row inside it (upstream's SeekPreviewImage arithmetic). Null when the info is incomplete.
 */
export function trickplayTile(positionMs: number, info: TrickplayInfoDto): { sheet: number; column: number; row: number } | null {
  const w = info.Width ?? 0;
  const h = info.Height ?? 0;
  const cols = info.TileWidth ?? 0;
  const rows = info.TileHeight ?? 0;
  if (w <= 0 || h <= 0 || cols <= 0 || rows <= 0) return null;
  const perSheet = cols * rows;
  let index = Math.floor(Math.max(0, positionMs) / Math.max(1, info.Interval ?? 0));
  const count = info.ThumbnailCount ?? 0;
  if (count > 0 && index >= count) index = count - 1;
  const inSheet = index % perSheet;
  return { sheet: Math.floor(index / perSheet), column: inSheet % cols, row: Math.floor(inSheet / cols) };
}

/**
 * The trickplay resolution to use for a media source: Jellyfin keys the item's `Trickplay` by source id, then by
 * width. The widest one up to 320 (what the server makes by default), else the narrowest.
 */
export function pickTrickplay(item: BaseItemDto | null, mediaSourceId: string | null): TrickplayInfoDto | null {
  const all = item?.Trickplay;
  if (all == null) return null;
  const bySource = (mediaSourceId !== null ? all[mediaSourceId] : undefined) ?? all[Object.keys(all)[0] ?? ''];
  if (bySource == null) return null;
  const widths = Object.keys(bySource)
    .map(Number)
    .filter((n) => n > 0);
  if (widths.length === 0) return null;
  const small = widths.filter((n) => n <= 320);
  const width = small.length > 0 ? Math.max.apply(null, small) : Math.min.apply(null, widths);
  return bySource[String(width)] ?? null;
}

/**
 * Hold-to-seek acceleration (upstream's calculateSeekAccelerationMultiplier): how many steps a repeated arrow moves,
 * growing with how long the key has been held and how long the item is.
 */
export function seekMultiplier(repeatCount: number, durationMs: number): number {
  if (repeatCount <= 0 || durationMs <= 0) return 1;
  const scaled = Math.floor(repeatCount / 3);
  if (scaled <= 0) return 1;
  const minutes = Math.floor(durationMs / 60_000);
  if (minutes < 30) return scaled < 30 ? 1 : 2;
  if (minutes < 90) return scaled < 13 ? 1 : scaled < 50 ? 2 : scaled < 75 ? 3 : 4;
  if (minutes < 150) return scaled < 20 ? 1 : scaled < 40 ? 2 : scaled < 60 ? 4 : 6;
  return scaled < 20 ? 1 : scaled < 40 ? 3 : scaled < 60 ? 6 : 10;
}

export type SkipBehavior = 'ignore' | 'auto' | 'ask';

/** Upstream's defaults: ask for intros, credits and ads; ignore previews and recaps. */
export function skipBehavior(type: MediaSegmentDto['Type']): SkipBehavior {
  switch (type) {
    case 'Intro':
    case 'Outro':
    case 'Commercial':
      return 'ask';
    default:
      return 'ignore';
  }
}

/** The button's label (upstream's skip_segment_* strings). */
export function skipLabel(type: MediaSegmentDto['Type']): string {
  switch (type) {
    case 'Intro':
      return 'Skip Intro';
    case 'Outro':
      return 'Skip Outro';
    case 'Commercial':
      return 'Skip Ads';
    case 'Preview':
      return 'Skip Preview';
    case 'Recap':
      return 'Skip Recap';
    default:
      return 'Skip Segment';
  }
}

/** The segment playing at `positionMs` (Unknown segments never count), or null. */
export function segmentAt(segments: readonly MediaSegmentDto[], positionMs: number): MediaSegmentDto | null {
  const ticks = positionMs * TICKS_PER_MS;
  for (const s of segments) {
    if (s.Type !== 'Unknown' && ticks >= (s.StartTicks ?? 0) && ticks < (s.EndTicks ?? 0)) return s;
  }
  return null;
}

export const ticksToMs = (ticks: number | null | undefined): number => Math.floor((ticks ?? 0) / TICKS_PER_MS);

/** Sleep timer choices in minutes (SleepTimerUi.kt). */
export const SLEEP_MINUTES: readonly number[] = [15, 30, 45, 60, 90];

/** `23:10`, `1:05:00` (rounded up to the second, like the Android chip). */
export function sleepClock(ms: number): string {
  if (!isFinite(ms) || ms < 0) return '';
  const total = Math.floor((ms + 999) / 1000);
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  return h > 0 ? `${h}:${pad2(m)}:${pad2(s)}` : `${m}:${pad2(s)}`;
}

/** The chip turns amber in the last minute. */
export function sleepUrgent(ms: number): boolean {
  return isFinite(ms) && ms < 60_000;
}

/** `1080p`, `4K`, `720p` (QualityStatus.resolutionLabel). */
export function resolutionLabel(height: number | null | undefined, width?: number | null): string | null {
  const h = height != null && height > 0 ? height : null;
  const w = width != null && width > 0 ? width : null;
  if (h === null && w === null) return null;
  if ((h ?? 0) > 1440 || (w ?? 0) > 2560) return '4K';
  if (h === null) return null;
  if (h >= 900) return '1080p';
  if (h >= 600) return '720p';
  if (h >= 400) return '480p';
  if (h >= 240) return '360p';
  return `${h}p`;
}

/** The server's transcode reasons in plain words (QualityStatus.REASON_RES, strings_tally_quality.xml). */
const REASONS: Record<string, string> = {
  ContainerNotSupported: 'Container not supported',
  VideoCodecNotSupported: 'Video codec not supported',
  AudioCodecNotSupported: 'Audio format not supported by this TV',
  SubtitleCodecNotSupported: 'Subtitles burned in',
  AudioIsExternal: 'External audio',
  SecondaryAudioNotSupported: 'Secondary audio not supported',
  VideoProfileNotSupported: 'Video profile not supported',
  VideoLevelNotSupported: 'Video level not supported',
  VideoResolutionNotSupported: 'Resolution not supported',
  VideoBitDepthNotSupported: 'Video bit depth not supported',
  VideoFramerateNotSupported: 'Frame rate not supported',
  RefFramesNotSupported: 'Reference frames not supported',
  AnamorphicVideoNotSupported: 'Anamorphic video not supported',
  InterlacedVideoNotSupported: 'Interlaced video not supported',
  AudioChannelsNotSupported: 'Audio channels not supported',
  AudioProfileNotSupported: 'Audio profile not supported',
  AudioSampleRateNotSupported: 'Audio sample rate not supported',
  AudioBitDepthNotSupported: 'Audio bit depth not supported',
  ContainerBitrateExceedsLimit: 'Over the quality limit',
  VideoBitrateNotSupported: 'Video bitrate not supported',
  AudioBitrateNotSupported: 'Audio bitrate not supported',
  UnknownVideoStreamInfo: 'Video stream unknown',
  UnknownAudioStreamInfo: 'Audio stream unknown',
  DirectPlayError: 'Direct play failed',
  VideoRangeTypeNotSupported: 'HDR format not supported',
  VideoCodecTagNotSupported: 'Video codec tag not supported',
};

/** `Container not supported, Audio format not supported by this TV` from a transcoding URL's TranscodeReasons. */
export function transcodeReasons(url: string): string {
  const raw = /[?&]TranscodeReasons=([^&]*)/i.exec(url)?.[1];
  if (raw === undefined) return '';
  const out: string[] = [];
  for (const r of decodeURIComponent(raw).split(',')) {
    const name = r.trim();
    if (name === '') continue;
    const words = REASONS[name] ?? name.replace(/([a-z])([A-Z])/g, '$1 $2').toLowerCase().replace(/^./, (c) => c.toUpperCase());
    if (out.indexOf(words) < 0) out.push(words);
  }
  return out.join(', ');
}
