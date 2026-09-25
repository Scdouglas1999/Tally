import { describe, expect, it } from 'vitest';
import { nativeAudioFor, type Prepared } from '../src/player/playback';

// the dev films' streams (captured from the dev server): video 0, English 1, Spanish 2, embedded English subtitle 3,
// external Spanish subtitle 4
const streams = [
  { Type: 'Video', Index: 0 },
  { Type: 'Audio', Index: 1, Language: 'eng' },
  { Type: 'Audio', Index: 2, Language: 'spa' },
  { Type: 'Subtitle', Index: 3, Language: 'eng' },
  { Type: 'Subtitle', Index: 4, Language: 'spa', IsExternal: true },
];
// what AVPlay's getTotalTrackInfo reported for that file on the Tizen emulator
const avplayTracks = [
  { index: 1, language: 'eng', label: 'ENG' },
  { index: 2, language: 'spa', label: 'SPA' },
];
const prepared = (method: Prepared['method'], audioIndex: number | null) =>
  ({ method, audioIndex, mediaSource: { MediaStreams: streams } }) as unknown as Prepared;

describe('nativeAudioFor (AVPlay plays a file’s first audio unless told)', () => {
  it('maps the chosen stream to the engine’s track of a direct-played file', () => {
    expect(nativeAudioFor(prepared('DirectPlay', 2), avplayTracks)).toBe(2);
    expect(nativeAudioFor(prepared('DirectPlay', 1), avplayTracks)).toBe(1);
    // by order, whatever numbers the engine uses
    expect(nativeAudioFor(prepared('DirectPlay', 2), [{ index: 7, language: '', label: '' }, { index: 9, language: '', label: '' }])).toBe(9);
  });
  it('leaves streams the server made, unknown choices and odd track lists alone', () => {
    expect(nativeAudioFor(prepared('Transcode', 2), avplayTracks)).toBeNull();
    expect(nativeAudioFor(prepared('DirectPlay', null), avplayTracks)).toBeNull();
    expect(nativeAudioFor(prepared('DirectPlay', 2), avplayTracks.slice(0, 1))).toBeNull();
    expect(nativeAudioFor(prepared('DirectPlay', 2), avplayTracks.concat({ index: 5, language: '', label: '' }))).toBeNull();
  });
});
