import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import {
  decodeBoard,
  decodeChannel,
  decodeGame,
  decodeRedZone,
  decodeSettings,
  isSpanish,
  languageLabel,
  otherFeed,
  playingFeed,
  redZoneChannel,
  redZoneReasonLabel,
  redZoneTileShown,
  withFeed,
  type TallyBoard,
} from '../src/api/tallyModels';
import { redZoneReason, redZoneTitle } from '../src/pages/sports/RedZoneTile';
import { boardRows, redZoneRowKey } from '../src/sports/boardOrganizer';

/** Tally 2.3: commentary language (`language`, `feeds`, `streamLanguage`) and the RedZone channel (`kind`, GET redzone). */
const fixture = (name: string): unknown => JSON.parse(readFileSync(new URL('./fixtures/' + name, import.meta.url), 'utf8'));
const board23 = decodeBoard(fixture('board-23.json'));
const dev = decodeBoard(fixture('board-dev.json'));
const status = decodeRedZone(fixture('redzone-23.json'));

const game = (id: string) => {
  const g = board23.games.find((x) => x.id === id);
  if (g === undefined) throw new Error('no game ' + id);
  return g;
};

describe('decoding the 2.3 fields', () => {
  it('reads feeds English first, with their language on the watch; the watch and channels carry a language', () => {
    const g = game('401772001');
    expect(g.watch?.language).toBe('en');
    expect(g.feeds.map((f) => f.language)).toEqual(['en', 'es']);
    expect(g.feeds.map((f) => f.label)).toEqual(['English', 'Español']);
    expect(g.feeds[1]?.watch.channelId).toBe('c-colts-texans-es');
    expect(g.feeds[1]?.watch.language).toBe('es');
    const es = board23.channels.find((c) => c.id === 'c-colts-texans-es');
    expect(es?.language).toBe('es');
    expect(es?.kind).toBe('');
  });

  it('a single feed is no choice; a feed without a stream is no feed; labels default from the language', () => {
    expect(game('401772003').feeds).toEqual([]);
    const g = decodeGame({
      id: 'x',
      feeds: [
        { language: 'EN', watch: { channelId: 'a', hlsPath: '/a' } },
        { language: 'es', watch: { channelId: 'b', hlsPath: '' } },
      ],
    });
    expect(g.feeds).toEqual([]);
    const two = decodeGame({
      id: 'y',
      feeds: [
        { language: 'es', watch: { channelId: 'b', hlsPath: '/b' } },
        { language: 'EN', watch: { channelId: 'a', hlsPath: '/a' } },
      ],
    });
    expect(two.feeds.map((f) => [f.language, f.label, f.watch.language])).toEqual([
      ['en', 'English', 'en'],
      ['es', 'Español', 'es'],
    ]);
  });

  it('older servers: no language means English, no feeds, no kind, no streamLanguage', () => {
    for (const g of dev.games) {
      expect(g.feeds).toEqual([]);
      if (g.watch !== null) expect(g.watch.language).toBe('en');
    }
    for (const c of dev.channels) {
      expect(c.language).toBe('en');
      expect(c.kind).toBe('');
    }
    expect(redZoneChannel(dev)).toBeNull();
    expect(decodeSettings({ favorites: [] }).streamLanguage).toBe('en');
  });

  it('reads the commentary setting: only "es" is Spanish', () => {
    expect(decodeSettings({ streamLanguage: 'es' }).streamLanguage).toBe('es');
    expect(decodeSettings({ streamLanguage: 'en' }).streamLanguage).toBe('en');
    expect(decodeSettings({ streamLanguage: 'fr' }).streamLanguage).toBe('en');
    expect(decodeSettings({ streamLanguage: 7 }).streamLanguage).toBe('en');
  });

  it('reads the RedZone channel and its status, with safe defaults', () => {
    const rz = redZoneChannel(board23);
    expect(rz?.id).toBe('redzone');
    expect(rz?.kind).toBe('redzone');
    expect(status).toEqual({
      active: true,
      gameId: '401772001',
      title: 'Colts at Texans',
      reason: 'red zone',
      since: '2026-09-27T18:29:10+00:00',
      next: ['401772002', '401772003'],
      recent: [],
      serverTime: null,
    });
    expect(decodeRedZone({})).toEqual({ active: false, gameId: null, title: null, reason: null, since: null, next: [], recent: [], serverTime: null });
    expect(decodeRedZone(null).active).toBe(false);
    expect(decodeChannel({ id: 'redzone', kind: 'redzone', hlsPath: '' }).kind).toBe('redzone');
  });
});

describe('feed selection', () => {
  it('offers the other language: Español when watch is English, English when watch is Spanish', () => {
    const g = game('401772001');
    expect(otherFeed(g)?.language).toBe('es');
    const spanishFirst = withFeed(g, g.feeds[1] ?? g.feeds[0]!);
    expect(spanishFirst.watch?.channelId).toBe('c-colts-texans-es');
    expect(otherFeed(spanishFirst)?.language).toBe('en');
    expect(otherFeed(spanishFirst)?.label).toBe('English');
  });

  it('in the player: the feed playing is the channel on screen; the switch goes to the other one', () => {
    const g = game('401772001');
    expect(playingFeed(g, 'c-colts-texans-es')?.language).toBe('es');
    expect(otherFeed(g, 'c-colts-texans-es')?.watch.channelId).toBe('c-colts-texans');
    expect(otherFeed(g, 'c-colts-texans')?.watch.channelId).toBe('c-colts-texans-es');
    expect(playingFeed(g, 'redzone')).toBeNull();
  });

  it('no choice for a game with one language; the ES chip marks a Spanish stream only', () => {
    expect(otherFeed(game('401772002'))).toBeNull();
    expect(otherFeed(game('401772003'))).toBeNull();
    expect(isSpanish(game('401772002').watch)).toBe(true);
    expect(isSpanish(game('401772001').watch)).toBe(false);
    expect(isSpanish(null)).toBe(false);
    expect(languageLabel('es')).toBe('Español');
    expect(languageLabel('en')).toBe('English');
  });
});

describe('RedZone tile', () => {
  const noChannel: TallyBoard = { ...board23, channels: board23.channels.filter((c) => c.kind !== 'redzone') };
  const noStream: TallyBoard = { ...board23, channels: board23.channels.map((c) => (c.kind === 'redzone' ? { ...c, hlsPath: '' } : c)) };

  it('shows only with the channel on the board and the status active', () => {
    expect(redZoneTileShown(board23, status)).toBe(true);
    expect(redZoneTileShown(board23, { ...status, active: false })).toBe(false);
    expect(redZoneTileShown(board23, null)).toBe(false);
    expect(redZoneTileShown(noChannel, status)).toBe(false);
    expect(redZoneTileShown(noStream, status)).toBe(false);
    expect(redZoneTileShown(null, status)).toBe(false);
    expect(redZoneTileShown(dev, status)).toBe(false);
  });

  it('goes first in the first live row, or in a row of its own when nothing is live', () => {
    const rows = boardRows(board23.games, new Set(), false);
    expect(redZoneRowKey(rows)).toBe('NFL|in');
    const upcoming = boardRows(
      board23.games.filter((g) => g.state !== 'in'),
      new Set(),
      false,
    );
    expect(redZoneRowKey(upcoming)).toBeNull();
  });

  it('names the game on RedZone as the board does, why (not while scores are hidden), and falls back to the server title', () => {
    expect(redZoneTitle(status, board23.games)).toBe('Colts at Texans');
    expect(redZoneTitle({ ...status, gameId: 'gone', title: 'Rams at 49ers' }, board23.games)).toBe('Rams at 49ers');
    expect(redZoneTitle({ ...status, gameId: null, title: null }, board23.games)).toBe('');
    expect(redZoneReason(status, false)).toBe('RED ZONE');
    expect(redZoneReason({ ...status, reason: 'score' }, true)).toBe('');
    expect(redZoneReasonLabel('two-minute drill')).toBe('TWO-MINUTE DRILL');
    expect(redZoneReasonLabel(null)).toBe('');
  });
});
