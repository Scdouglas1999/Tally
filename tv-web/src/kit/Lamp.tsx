import { useEffect, useRef } from 'preact/hooks';
import { CATCH_FULL_MS, CATCH_TOTAL_MS, LAMP_LIT, LAMP_OFF, catchAt, catchStart, sputterAt, type LampValue } from './lampTimeline';

/** Dark, trying (and failing) to switch on, or on. */
export type LampState = 'off' | 'sputtering' | 'lit';

const LAMP_OFF_RGB = [0x28, 0x23, 0x1a];
const ACCENT_RGB = [0xff, 0xb0, 0x00];
/** A frame never advances the lamp more than this: a stalled main thread makes it wait, not skip a blip. */
const MAX_FRAME_STEP_MS = 34;

function reducedMotion(): boolean {
  try {
    return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  } catch {
    return false;
  }
}

function paint(el: HTMLElement, v: LampValue, glow: boolean): void {
  const b = Math.min(1, Math.max(0, v.brightness));
  const mix = (i: number): number => Math.round((LAMP_OFF_RGB[i] as number) + ((ACCENT_RGB[i] as number) - (LAMP_OFF_RGB[i] as number)) * b);
  el.style.backgroundColor = `rgb(${mix(0)},${mix(1)},${mix(2)})`;
  // the halo: radius ~1.4x the lamp, peak alpha 0.30 x glow (a box-shadow, not a blur filter: cheap on a TV)
  el.style.boxShadow = glow && v.glow > 0 ? `0 0 ${Math.round(el.offsetWidth * 1.4)}px ${Math.round(el.offsetWidth * 0.2)}px rgba(255,176,0,${(0.3 * v.glow).toFixed(3)})` : 'none';
}

/**
 * The tally light (port of the Android TallyLamp): a square lamp that sputters while something connects and
 * catches when it really goes live. Driven by requestAnimationFrame writing styles directly (no Preact renders).
 */
export function Lamp(props: {
  state: LampState;
  size: number;
  glow?: boolean;
  onFullBrightness?: () => void;
  onSettled?: () => void;
  class?: string;
}) {
  const ref = useRef<HTMLSpanElement>(null);
  const clock = useRef({ time: 0, last: -1, sputtering: false, settled: props.state === 'lit' });
  const callbacks = useRef(props);
  callbacks.current = props;
  const glow = props.glow !== false;

  useEffect(() => {
    const el = ref.current;
    if (el === null) return undefined;
    const c = clock.current;
    let frame = 0;
    const advance = (now: number): void => {
      if (c.last >= 0) c.time += Math.min(MAX_FRAME_STEP_MS, Math.max(0, now - c.last));
      c.last = now;
    };
    if (props.state === 'off') {
      c.sputtering = false;
      c.settled = false;
      paint(el, LAMP_OFF, glow);
      return undefined;
    }
    if (props.state === 'sputtering') {
      c.settled = false;
      if (reducedMotion()) {
        paint(el, LAMP_OFF, glow);
        return undefined;
      }
      if (!c.sputtering) {
        c.time = 0;
        c.sputtering = true;
      }
      c.last = -1;
      const loop = (now: number): void => {
        advance(now);
        paint(el, sputterAt(c.time), glow);
        frame = requestAnimationFrame(loop);
      };
      frame = requestAnimationFrame(loop);
      return () => cancelAnimationFrame(frame);
    }
    // lit
    const finish = (): void => {
      c.settled = true;
      c.sputtering = false;
      paint(el, LAMP_LIT, glow);
      callbacks.current.onFullBrightness?.();
      callbacks.current.onSettled?.();
    };
    if (c.settled || reducedMotion()) {
      finish();
      return undefined;
    }
    const fromSputter = c.sputtering;
    if (!fromSputter) {
      c.time = 0;
      c.sputtering = true;
    }
    c.last = -1;
    let start = -1;
    let fired = false;
    const loop = (now: number): void => {
      advance(now);
      if (start < 0) start = fromSputter ? catchStart(c.time) : c.time;
      const into = c.time - start;
      paint(el, into < 0 ? sputterAt(c.time) : catchAt(into), glow);
      if (!fired && into >= CATCH_FULL_MS) {
        fired = true;
        callbacks.current.onFullBrightness?.();
      }
      if (into >= CATCH_TOTAL_MS) {
        finish();
        return;
      }
      frame = requestAnimationFrame(loop);
    };
    frame = requestAnimationFrame(loop);
    return () => cancelAnimationFrame(frame);
  }, [props.state, glow]);

  return (
    <span
      ref={ref}
      class={'lamp' + (props.class !== undefined ? ' ' + props.class : '')}
      style={{ display: 'inline-block', flex: 'none', width: `${props.size}px`, height: `${props.size}px`, backgroundColor: props.state === 'lit' ? '#ffb000' : '#28231a' }}
    />
  );
}
