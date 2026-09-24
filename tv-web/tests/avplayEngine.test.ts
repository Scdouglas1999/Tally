import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createAvPlayEngine } from '../src/player/avplayEngine';
import type { AvPlayListener } from '../src/platform/tizen-types';

/**
 * The AVPlay engine against a recording fake of webapis.avplay (the call order Samsung's AVPlay guide requires:
 * open → setDisplayRect → prepareAsync → seek → play; suspend/restore on visibility; stop/close on destroy).
 * The real thing needs a TV or the emulator (ARCHITECTURE.md, Testing).
 */
class FakeElement {
  children: FakeElement[] = [];
  attrs: Record<string, string> = {};
  className = '';
  classes = new Set<string>();
  classList = { add: (c: string) => this.classes.add(c), remove: (c: string) => this.classes.delete(c) };
  setAttribute(k: string, v: string): void {
    this.attrs[k] = v;
  }
  appendChild(c: FakeElement): FakeElement {
    this.children.push(c);
    return c;
  }
  remove(): void {
    this.attrs.removed = 'yes';
  }
}

let calls: string[];
let listener: AvPlayListener;
let state: string;
const visibility: Array<() => void> = [];
const doc = {
  hidden: false,
  documentElement: new FakeElement(),
  body: new FakeElement(),
  createElement: () => new FakeElement(),
  addEventListener: (_t: string, fn: () => void) => visibility.push(fn),
  removeEventListener: () => undefined,
};

beforeEach(() => {
  calls = [];
  state = 'NONE';
  visibility.length = 0;
  const avplay = {
    open: (url: string) => calls.push('open ' + url),
    close: () => calls.push('close'),
    setDisplayRect: (...a: number[]) => calls.push('rect ' + a.join(',')),
    setDisplayMethod: (m: string) => calls.push('method ' + m),
    setBufferingParam: () => calls.push('buffering'),
    prepareAsync: (ok: () => void) => {
      calls.push('prepare');
      state = 'READY';
      ok();
    },
    seekTo: (ms: number, ok?: () => void) => {
      calls.push('seek ' + String(ms));
      ok?.();
    },
    play: () => {
      calls.push('play');
      state = 'PLAYING';
    },
    pause: () => {
      calls.push('pause');
      state = 'PAUSED';
    },
    stop: () => calls.push('stop'),
    suspend: () => calls.push('suspend'),
    restore: (url: string, ms?: number) => calls.push(`restore ${url} ${String(ms)}`),
    getState: () => state,
    getDuration: () => 90_000,
    setListener: (l: AvPlayListener) => (listener = l),
    getTotalTrackInfo: () => [
      { index: 0, type: 'VIDEO', extra_info: '{}' },
      { index: 1, type: 'AUDIO', extra_info: '{"language":"eng"}' },
      { index: 2, type: 'AUDIO', extra_info: '{"language":"spa"}' },
    ],
    setSelectTrack: (t: string, i: number) => calls.push(`track ${t} ${i}`),
  };
  (globalThis as Record<string, unknown>).window = { webapis: { avplay } };
  (globalThis as Record<string, unknown>).document = doc;
});

afterEach(() => {
  delete (globalThis as Record<string, unknown>).window;
  delete (globalThis as Record<string, unknown>).document;
});

const noEvents = { state: () => undefined, time: () => undefined, firstFrame: () => undefined, error: () => undefined };

describe('AVPlay engine', () => {
  it('opens, sizes the video plane, prepares, seeks to the resume point, plays', async () => {
    const host = new FakeElement();
    const engine = createAvPlayEngine(host as unknown as HTMLElement, noEvents);
    expect(host.children[0]?.attrs.type).toBe('application/avplayer');
    expect(doc.body.classes.has('native-video')).toBe(true);
    await engine.load({ url: 'http://s/v.m3u8', kind: 'hls', live: false, startMs: 42_000 });
    expect(calls).toEqual(['open http://s/v.m3u8', 'rect 0,0,1920,1080', 'method PLAYER_DISPLAY_MODE_LETTER_BOX', 'prepare', 'seek 42000', 'play']);
  });

  it('never seeks a live stream and asks for a start buffer', async () => {
    const engine = createAvPlayEngine(new FakeElement() as unknown as HTMLElement, noEvents);
    await engine.load({ url: 'http://s/live.m3u8', kind: 'hls', live: true, startMs: 0 });
    expect(calls).toContain('buffering');
    expect(calls.some((c) => c.startsWith('seek'))).toBe(false);
  });

  it('reports the first frame once the clock runs while playing, and the position', async () => {
    let first = 0;
    let at = 0;
    const engine = createAvPlayEngine(new FakeElement() as unknown as HTMLElement, { ...noEvents, firstFrame: () => first++, time: (ms) => (at = ms) });
    await engine.load({ url: 'u', kind: 'file', live: false, startMs: 0 });
    listener.oncurrentplaytime?.(1200);
    listener.oncurrentplaytime?.(1700);
    expect(first).toBe(1);
    expect(at).toBe(1700);
    expect(engine.currentTime()).toBe(1700);
  });

  it('gives the decoder back when the app is hidden and restores at the position', async () => {
    const engine = createAvPlayEngine(new FakeElement() as unknown as HTMLElement, noEvents);
    await engine.load({ url: 'u', kind: 'file', live: false, startMs: 0 });
    listener.oncurrentplaytime?.(5000);
    doc.hidden = true;
    visibility.forEach((f) => f());
    doc.hidden = false;
    visibility.forEach((f) => f());
    expect(calls.slice(-2)).toEqual(['suspend', 'restore u 5000']);
  });

  it('lists and switches the file’s own audio tracks, and cleans up', () => {
    const engine = createAvPlayEngine(new FakeElement() as unknown as HTMLElement, noEvents);
    expect(engine.nativeAudioTracks().map((t) => t.label)).toEqual(['ENG', 'SPA']);
    engine.selectNativeAudio(2);
    engine.destroy();
    expect(calls.slice(-3)).toEqual(['track AUDIO 2', 'stop', 'close']);
    expect(doc.body.classes.has('native-video')).toBe(false);
  });
});
