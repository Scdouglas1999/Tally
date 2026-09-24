/**
 * One interface over the three ways this app plays video:
 *  - `avplay`  Samsung Tizen AVPlay (native pipeline: HLS, MKV/MP4/TS files, HEVC/AV1, 4K/HDR, surround passthrough);
 *              video is drawn on a hardware plane under the page.
 *  - `webos`   LG webOS: an HTML5 <video> element on the platform's own media pipeline (native HLS, broad codecs);
 *              Luna services only for what the element cannot do (not needed yet).
 *  - `html5`   browsers: <video>, with hls.js (loaded on demand, only where the browser has no native HLS).
 * Screens talk to a `PlayerEngine` only; `createEngine` picks one for the platform.
 */
export type EngineName = 'avplay' | 'webos' | 'html5';

export interface Source {
  url: string;
  /** hls: an .m3u8 playlist; file: a progressive file (direct play). */
  kind: 'hls' | 'file';
  /** Live: no seeking outside the window, no duration. */
  live: boolean;
  startMs: number;
}

export type EngineState = 'idle' | 'loading' | 'playing' | 'paused' | 'buffering' | 'ended' | 'error';

export interface EngineEvents {
  state(state: EngineState): void;
  /** Position in ms, a few times a second while playing. */
  time(ms: number): void;
  /** The first video frame is on screen (the tune-in lamp catches). */
  firstFrame(): void;
  error(message: string): void;
}

export interface NativeAudioTrack {
  /** Engine-specific index to pass back to `selectAudio`. */
  index: number;
  language: string;
  label: string;
}

export interface PlayerEngine {
  readonly name: EngineName;
  load(source: Source): Promise<void>;
  play(): void;
  pause(): void;
  seek(ms: number): void;
  stop(): void;
  /** Frees the decoder and removes the video surface. */
  destroy(): void;
  currentTime(): number;
  /** ms; 0 when unknown or live. */
  duration(): number;
  /**
   * Audio tracks the engine can switch itself, without the server (AVPlay on a direct-played file with several
   * tracks). Empty: switching audio means asking the server for a stream with that track (playback.ts).
   */
  nativeAudioTracks(): NativeAudioTrack[];
  selectNativeAudio(index: number): void;
  /** Playback speed (1 = normal). Absent where the pipeline cannot change speed with sound (AVPlay). */
  setSpeed?(rate: number): void;
  /** How the picture fills the screen. Absent: always fit. */
  setScale?(scale: VideoScale): void;
  /** Scales this engine offers (the first is the default). */
  scales?(): VideoScale[];
  /** End of the buffered range around the current position, ms (0 when unknown). */
  bufferedMs?(): number;
  /**
   * Where a picture drawn outside the page goes, in canvas pixels (AVPlay's hardware plane). Engines that draw in
   * the page follow their host element instead and leave this out.
   */
  setDisplayArea?(x: number, y: number, width: number, height: number): void;
}

/** Fit: the whole picture, letterboxed. Crop: fill the screen, cutting edges. Fill: stretch. */
export type VideoScale = 'fit' | 'crop' | 'fill';

export type EngineFactory = (host: HTMLElement, events: EngineEvents) => PlayerEngine;
