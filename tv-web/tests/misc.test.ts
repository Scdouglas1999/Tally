import { describe, expect, it } from 'vitest';
import { reveal } from '../src/kit/scroll';
import { activeCues, parseVtt } from '../src/player/subtitles';
import { drawerModel } from '../src/app/drawer';
import { railPitch } from '../src/app/railPitch';

describe('scroll reveal', () => {
  it('moves only as much as needed and clamps', () => {
    expect(reveal(0, 1000, 200, 300, 77, 77, 5000)).toBe(0);
    expect(reveal(0, 1000, 900, 300, 77, 77, 5000)).toBe(277);
    expect(reveal(500, 1000, 300, 300, 77, 77, 5000)).toBe(223);
    expect(reveal(0, 1000, 4900, 300, 77, 77, 4000)).toBe(4000);
  });
});

describe('subtitles', () => {
  const vtt = 'WEBVTT\n\n1\n00:00:01.000 --> 00:00:02.500 align:center\n<i>Hello</i> &amp; welcome\n\n00:01:00,000 --> 00:01:01,000\nTwo\nlines\n';
  it('parses WebVTT and finds active cues', () => {
    const cues = parseVtt(vtt);
    expect(cues).toEqual([
      { start: 1000, end: 2500, text: 'Hello & welcome' },
      { start: 60000, end: 61000, text: 'Two\nlines' },
    ]);
    expect(activeCues(cues, 1500).map((c) => c.text)).toEqual(['Hello & welcome']);
    expect(activeCues(cues, 3000)).toEqual([]);
  });
});

describe('drawer', () => {
  it('orders Movies, TV, Sports; hides Live TV while Sports is there', () => {
    const m = drawerModel(
      [
        { Id: 'mu', Name: 'Music', CollectionType: 'music' },
        { Id: 'tv', Name: 'Shows', CollectionType: 'tvshows' },
        { Id: 'lt', Name: 'Live TV', CollectionType: 'livetv' },
        { Id: 'mo', Name: 'Movies', CollectionType: 'movies' },
      ],
      true,
    );
    expect(m.primary.map((i) => i.label)).toEqual(['Movies', 'Shows', 'Sports']);
    expect(m.libraries.map((i) => i.label)).toEqual(['Music']);
    const noSports = drawerModel([{ Id: 'lt', Name: 'Live TV', CollectionType: 'livetv' }], false);
    expect(noSports.libraries.map((i) => i.label)).toEqual(['Live TV']);
  });

  it('fits the rail rows between 60 and 80 px', () => {
    expect(railPitch(8, 3)).toBe(80);
    expect(railPitch(30, 3)).toBe(60);
  });
});
