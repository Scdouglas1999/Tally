import type { BaseItemDto } from '@jellyfin/sdk/lib/generated-client/models/base-item-dto';
import type { MediaSegmentDto } from '@jellyfin/sdk/lib/generated-client/models/media-segment-dto';
import type { MediaStream } from '@jellyfin/sdk/lib/generated-client/models/media-stream';
import { readFileSync } from 'node:fs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import * as F from '../src/pages/player/playerFormat';
import { bindSleepTimer, sleep, sleepItemEnded, sleepRemaining, startSleep, startSleepUntilEnd } from '../src/pages/player/sleepTimer';

/** Items captured from the dev server (Jellyfin 10.10.6): a film, an episode, and the episodes that follow it. */
const film = JSON.parse(readFileSync(new URL('./fixtures/film-dev.json', import.meta.url), 'utf8')) as BaseItemDto;
const episode = JSON.parse(readFileSync(new URL('./fixtures/episode-dev.json', import.meta.url), 'utf8')) as BaseItemDto;
const episodes = (JSON.parse(readFileSync(new URL('./fixtures/episodes-dev.json', import.meta.url), 'utf8')) as { Items: BaseItemDto[] }).Items;
const streams = (episode.MediaSources?.[0]?.MediaStreams ?? []) as MediaStream[];
const stream = (index: number): MediaStream => streams.find((s) => s.Index === index) as MediaStream;

describe('player title block', () => {
  it('kicker, title and meta for a film and an episode', () => {
    expect(F.kicker(film)).toBe('FILM');
    expect(F.title(film)).toBe('The Matrix');
    expect(F.meta(film)).toBe('1999 · R');
    expect(F.kicker(episode)).toBe('BREAKING BAD · S1 E1');
    expect(F.title(episode)).toBe('Pilot');
    // the server's dates are UTC midnights: the calendar date it meant, whatever the TV's zone
    expect(F.meta(episode)).toBe('JAN 20, 2008');
    expect(F.kicker(null)).toBeNull();
  });

  it('episode codes', () => {
    expect(F.episodeCode(1, 3, null)).toBe('S1 E3');
    expect(F.episodeCode(2, 3, 4)).toBe('S2 E3–4');
    expect(F.episodeCode(0, 1, null)).toBe('SPECIAL');
    expect(F.episodeCode(null, 5, null)).toBe('E5');
    expect(F.episodeCode(null, null, null)).toBeNull();
  });
});

describe('times', () => {
  it('clock, remaining, end time at speed', () => {
    expect(F.clock(45_000)).toBe('0:45');
    expect(F.clock(754_000)).toBe('12:34');
    expect(F.clock(3_723_000)).toBe('1:02:03');
    expect(F.remaining(10_000, 2_078_000)).toBe('-34:28');
    expect(F.remainingRealMs(0, 60_000, 1.5)).toBe(40_000);
    expect(F.endsAt(new Date(2026, 8, 24, 21, 0, 0), 41 * 60_000)).toBe('ENDS 9:41 PM');
    expect(F.position(0)).toBe('00:00');
    expect(F.position(30_000)).toBe('00:30');
    expect(F.position(3_723_000)).toBe('1:02:03');
  });

  it('speed and subtitle delay labels', () => {
    expect(F.SPEEDS.map(F.speedLabel)).toEqual(['0.25×', '0.5×', '0.75×', '1×', '1.25×', '1.5×', '1.75×', '2×']);
    expect(F.subtitleDelay(0)).toBe('0s');
    expect(F.subtitleDelay(250)).toBe('+0.25s');
    expect(F.subtitleDelay(-1000)).toBe('-1s');
    expect(F.subtitleDelay(1300)).toBe('+1.3s');
  });
});

describe('tracks (the dev episode: two audio tracks, an external and an embedded subtitle)', () => {
  it('audio: own title first, then EN · AAC STEREO', () => {
    expect(F.trackName(stream(2))).toBe('English Stereo');
    expect(F.audioTrack(stream(2))).toBe('EN · AAC STEREO');
    expect(F.audioTrack(stream(3))).toBe('ES · AAC STEREO');
  });

  it('subtitles: language name when untitled; ES · EXTERNAL', () => {
    expect(F.trackName(stream(0))).toBe('Spanish');
    expect(F.subtitleTrack(stream(0))).toBe('ES · EXTERNAL');
    expect(F.trackName(stream(4))).toBe('English');
    expect(F.subtitleTrack(stream(4))).toBe('EN');
    expect(F.subtitleTrack({ Language: 'eng', IsForced: true, IsHearingImpaired: true })).toBe('EN · FORCED · SDH');
    expect(F.subtitleTrack({ Codec: 'pgssub' })).toBe('PGSSUB');
  });

  it('languages', () => {
    expect(F.shortLanguage('und')).toBeNull();
    expect(F.shortLanguage('fre')).toBe('FR');
    expect(F.shortLanguage('pt')).toBe('PT');
    expect(F.languageName('ger')).toBe('German');
    expect(F.languageName('ja')).toBe('Japanese');
    expect(F.languageName('xyz')).toBeNull();
  });
});

describe('chapters and queue', () => {
  const starts = (film.Chapters ?? []).map((c) => F.ticksToMs(c.StartPositionTicks));

  it('chapter playing now and the card kicker', () => {
    expect(starts).toEqual([0, 30_000, 60_000]);
    expect(F.chapterAt(starts, 29_999)).toBe(0);
    expect(F.chapterAt(starts, 30_000)).toBe(1);
    expect(F.chapterAt(starts, 89_000)).toBe(2);
    expect(F.chapterAt([], 5000)).toBeNull();
    expect(F.chapterAt([10_000], 5000)).toBeNull();
    expect(F.chapterKicker(2, 60_000)).toBe('CHAPTER 3 · 01:00');
  });

  it('queue kickers and the next-up line from the episodes the server lists after the pilot', () => {
    expect(episodes[0]?.Name).toBe('Pilot');
    const queue = episodes.slice(1);
    expect(F.queueKicker(0, queue[0] as BaseItemDto)).toBe('NEXT');
    expect(F.queueKicker(1, queue[1] as BaseItemDto)).toBe('E03 · 1m');
    expect(F.nextUpLine(queue[0] as BaseItemDto)).toBe("S1 E2 · Cat's in the Bag...");
  });

  it('countdown', () => {
    expect(F.countdownFraction(15, 15)).toBe(1);
    expect(F.countdownFraction(3, 15)).toBeCloseTo(0.2);
    expect(F.countdownFraction(0, 15)).toBe(0);
    expect(F.countdownFraction(-1, 15)).toBe(0);
  });
});

describe('trickplay and the seek bar', () => {
  it('picks the source resolution and finds the tile', () => {
    const info = F.pickTrickplay(film, film.MediaSources?.[0]?.Id ?? null);
    expect(info?.Width).toBe(320);
    expect(F.pickTrickplay(film, 'another-source')?.Width).toBe(320);
    expect(F.pickTrickplay(null, null)).toBeNull();
    if (info == null) return;
    expect(F.trickplayTile(0, info)).toEqual({ sheet: 0, column: 0, row: 0 });
    expect(F.trickplayTile(35_000, info)).toEqual({ sheet: 0, column: 3, row: 0 });
    // past the last thumbnail: the last one (9 thumbnails)
    expect(F.trickplayTile(500_000, info)).toEqual({ sheet: 0, column: 8, row: 0 });
    // a 10x10 sheet holds 100 thumbnails: the 123rd is on sheet 1, row 2, column 3
    expect(F.trickplayTile(1_230_000, { ...info, ThumbnailCount: 500 })).toEqual({ sheet: 1, column: 3, row: 2 });
    expect(F.trickplayTile(1000, { Width: 0 })).toBeNull();
  });

  it('keeps the scrubber whole inside the track', () => {
    expect(F.scrubberCenter(0, 1766, 19)).toBe(9.5);
    expect(F.scrubberCenter(1, 1766, 19)).toBe(1756.5);
    expect(F.scrubberCenter(2, 1766, 19)).toBe(1756.5);
    expect(F.fraction(45_000, 90_000)).toBe(0.5);
    expect(F.fraction(1, 0)).toBe(0);
  });

  it('hold-to-seek acceleration (upstream numbers)', () => {
    expect(F.seekMultiplier(0, 7_200_000)).toBe(1);
    expect(F.seekMultiplier(2, 7_200_000)).toBe(1);
    expect(F.seekMultiplier(60, 60 * 60_000)).toBe(2);
    expect(F.seekMultiplier(300, 60 * 60_000)).toBe(4);
    expect(F.seekMultiplier(300, 3 * 60 * 60_000)).toBe(10);
    expect(F.seekMultiplier(300, 10 * 60_000)).toBe(2);
  });
});

describe('media segments', () => {
  const intro: MediaSegmentDto = { Id: 'a', Type: 'Intro', StartTicks: 50_000_000, EndTicks: 200_000_000 };
  const unknown: MediaSegmentDto = { Id: 'b', Type: 'Unknown', StartTicks: 0, EndTicks: 900_000_000 };
  it('finds the segment playing now; unknown ones never count', () => {
    expect(F.segmentAt([unknown, intro], 4_999)).toBeNull();
    expect(F.segmentAt([unknown, intro], 5_000)?.Id).toBe('a');
    expect(F.segmentAt([unknown, intro], 20_000)).toBeNull();
  });
  it('labels and upstream default behaviors', () => {
    expect(F.skipLabel('Intro')).toBe('Skip Intro');
    expect(F.skipLabel('Outro')).toBe('Skip Outro');
    expect(F.skipLabel('Commercial')).toBe('Skip Ads');
    expect(F.skipBehavior('Intro')).toBe('ask');
    expect(F.skipBehavior('Outro')).toBe('ask');
    expect(F.skipBehavior('Recap')).toBe('ignore');
    expect(F.skipBehavior('Preview')).toBe('ignore');
  });
});

describe('sleep timer', () => {
  it('chip clock rounds up, amber in the last minute, until-the-end is infinite', () => {
    expect(F.sleepClock(900_000)).toBe('15:00');
    expect(F.sleepClock(899_001)).toBe('15:00');
    expect(F.sleepClock(59_000)).toBe('0:59');
    expect(F.sleepClock(3_900_000)).toBe('1:05:00');
    expect(F.sleepUrgent(59_000)).toBe(true);
    expect(F.sleepUrgent(Infinity)).toBe(false);
    expect(sleepRemaining(null)).toBeNull();
    expect(sleepRemaining({ untilEnd: true })).toBe(Infinity);
    expect(sleepRemaining({ endsAt: 1000 }, 400)).toBe(600);
    expect(sleepRemaining({ endsAt: 1000 }, 4000)).toBe(0);
  });
});

describe('sleep timer firing', () => {
  afterEach(() => {
    vi.useRealTimers();
  });
  it('fires once after the minutes, pauses and leaves (the player bound it); leaving the player clears it', () => {
    vi.useFakeTimers();
    (globalThis as Record<string, unknown>).window = globalThis;
    let fired = 0;
    const unbind = bindSleepTimer(() => fired++);
    startSleep(15);
    vi.advanceTimersByTime(15 * 60_000 - 1);
    expect(fired).toBe(0);
    vi.advanceTimersByTime(1);
    expect(fired).toBe(1);
    expect(sleep.get()).toBeNull();
    // "when this ends": waits for the item's end
    startSleepUntilEnd();
    vi.advanceTimersByTime(24 * 60 * 60_000);
    expect(fired).toBe(1);
    expect(sleepItemEnded()).toBe(true);
    expect(fired).toBe(2);
    expect(sleepItemEnded()).toBe(false);
    // the player goes away: the timer is cleared without firing
    startSleep(30);
    unbind();
    expect(sleep.get()).toBeNull();
    vi.advanceTimersByTime(31 * 60_000);
    expect(fired).toBe(2);
  });
});

describe('quality panel', () => {
  it('resolution names and transcode reasons in plain words', () => {
    expect(F.resolutionLabel(360)).toBe('360p');
    expect(F.resolutionLabel(1080)).toBe('1080p');
    expect(F.resolutionLabel(1600, 3840)).toBe('4K');
    expect(F.resolutionLabel(null)).toBeNull();
    expect(F.transcodeReasons('/videos/x/master.m3u8?TranscodeReasons=ContainerNotSupported%2C%20AudioCodecNotSupported&a=1')).toBe(
      'Container not supported, Audio format not supported by this TV',
    );
    expect(F.transcodeReasons('/videos/x/master.m3u8?TranscodeReasons=SomethingNew')).toBe('Something new');
    expect(F.transcodeReasons('/videos/x/stream.mp4')).toBe('');
  });
});
