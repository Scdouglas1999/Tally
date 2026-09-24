/**
 * The tally light's timing as data (port of the Android app's TallyLampTimeline; the numbers must stay identical so
 * the two apps light the same way). Times are milliseconds since the lamp began sputtering; everything is pure.
 */
export interface LampValue {
  brightness: number;
  glow: number;
}

export interface LampStep extends LampValue {
  durationMs: number;
}

export const LAMP_OFF: LampValue = { brightness: 0, glow: 0 };
export const LAMP_LIT: LampValue = { brightness: 1, glow: 1 };

const on = (v: LampValue): boolean => v.brightness > 0;

/** One failing-bulb cycle, looped while sputtering: three blips, a near-catch, long dark gaps. */
export const SPUTTER: readonly LampStep[] = [
  { brightness: 0, glow: 0, durationMs: 400 },
  { brightness: 0.35, glow: 0, durationMs: 50 },
  { brightness: 0, glow: 0, durationMs: 120 },
  { brightness: 0.5, glow: 0.05, durationMs: 40 },
  { brightness: 0, glow: 0, durationMs: 700 },
  { brightness: 0.25, glow: 0, durationMs: 60 },
  { brightness: 0, glow: 0, durationMs: 300 },
  { brightness: 0.7, glow: 0.2, durationMs: 110 },
  { brightness: 0.15, glow: 0, durationMs: 40 },
  { brightness: 0, glow: 0, durationMs: 900 },
];

export const SPUTTER_CYCLE_MS = SPUTTER.reduce((t, s) => t + s.durationMs, 0);

/** The catch opens with one more flicker, then goes dark before the ramp. */
export const CATCH_FLICKER: readonly LampStep[] = [
  { brightness: 0.6, glow: 0.1, durationMs: 50 },
  { brightness: 0, glow: 0, durationMs: 60 },
];
export const CATCH_RAMP_MS = 200;
export const CATCH_RAMP_GLOW = 0.7;
export const CATCH_GLOW_MS = 260;
const CATCH_FLICKER_MS = CATCH_FLICKER.reduce((t, s) => t + s.durationMs, 0);
export const CATCH_FULL_MS = CATCH_FLICKER_MS + CATCH_RAMP_MS;
export const CATCH_TOTAL_MS = CATCH_FULL_MS + CATCH_GLOW_MS;
/** At least this much sputtering before a catch, and at least the first blip. */
export const MIN_SPUTTER_MS = 350;
export const FIRST_BLIP_END_MS = (() => {
  let t = 0;
  for (const step of SPUTTER) {
    t += step.durationMs;
    if (on(step)) break;
  }
  return t;
})();
/** Photosensitivity: never more than this many off → on switches in any one-second window. */
export const MAX_ONSETS_PER_SECOND = 3;
const WINDOW_MS = 1000;

export function easeOutCubic(t: number): number {
  const u = 1 - Math.min(1, Math.max(0, t));
  return 1 - u * u * u;
}

const mod = (a: number, n: number): number => ((a % n) + n) % n;

function stepIndexAt(ms: number): number {
  let t = mod(ms, SPUTTER_CYCLE_MS);
  for (let i = 0; i < SPUTTER.length; i++) {
    const d = (SPUTTER[i] as LampStep).durationMs;
    if (t < d) return i;
    t -= d;
  }
  return SPUTTER.length - 1;
}

function stepStart(ms: number): number {
  let at = ms - mod(ms, SPUTTER_CYCLE_MS);
  const index = stepIndexAt(ms);
  for (let i = 0; i < index; i++) at += (SPUTTER[i] as LampStep).durationMs;
  return at;
}

export function sputterAt(ms: number): LampValue {
  if (ms < 0) return LAMP_OFF;
  const step = SPUTTER[stepIndexAt(ms)] as LampStep;
  return { brightness: step.brightness, glow: step.glow };
}

export function catchAt(ms: number): LampValue {
  if (ms < 0) return LAMP_OFF;
  let t = ms;
  for (const step of CATCH_FLICKER) {
    if (t < step.durationMs) return { brightness: step.brightness, glow: step.glow };
    t -= step.durationMs;
  }
  if (t < CATCH_RAMP_MS) {
    const e = easeOutCubic(t / CATCH_RAMP_MS);
    return { brightness: e, glow: CATCH_RAMP_GLOW * e };
  }
  t -= CATCH_RAMP_MS;
  if (t < CATCH_GLOW_MS) {
    return { brightness: 1, glow: CATCH_RAMP_GLOW + (1 - CATCH_RAMP_GLOW) * easeOutCubic(t / CATCH_GLOW_MS) };
  }
  return LAMP_LIT;
}

export function withinOnsetLimit(onsets: readonly number[]): boolean {
  const sorted = onsets.slice().sort((a, b) => a - b);
  for (let i = 0; i < sorted.length - MAX_ONSETS_PER_SECOND; i++) {
    if ((sorted[i + MAX_ONSETS_PER_SECOND] as number) - (sorted[i] as number) < WINDOW_MS) return false;
  }
  return true;
}

function sputterOnsetsBefore(t: number): number[] {
  const result: number[] = [];
  let at = 0;
  let previousOn = false;
  while (at < t) {
    for (const step of SPUTTER) {
      if (at >= t) break;
      if (on(step) && !previousOn) result.push(at);
      previousOn = on(step);
      at += step.durationMs;
    }
  }
  return result;
}

function canCatchAt(t: number): boolean {
  const index = stepIndexAt(t);
  if (on(SPUTTER[index] as LampStep) && stepStart(t) < t) return false;
  const onsets = sputterOnsetsBefore(t);
  if (!on(sputterAt(t - 1))) onsets.push(t);
  let offset = 0;
  let previousOn = true;
  for (const step of CATCH_FLICKER) {
    if (on(step) && !previousOn) onsets.push(t + offset);
    previousOn = on(step);
    offset += step.durationMs;
  }
  if (!previousOn) onsets.push(t + offset);
  return withinOnsetLimit(onsets);
}

/** When the catch begins if Lit arrives `litAtMs` into the sputter (never cutting a blip, never too many flashes). */
export function catchStart(litAtMs: number): number {
  let t = Math.max(litAtMs, MIN_SPUTTER_MS, FIRST_BLIP_END_MS);
  const limit = t + 2 * SPUTTER_CYCLE_MS;
  while (t < limit && !canCatchAt(t)) t++;
  return t;
}

/** The whole lamp: sputtering since 0, Lit arriving at `litAtMs` (null: still sputtering). */
export function lampValueAt(ms: number, litAtMs: number | null): LampValue {
  if (litAtMs === null) return sputterAt(ms);
  const start = catchStart(litAtMs);
  return ms < start ? sputterAt(ms) : catchAt(ms - start);
}
