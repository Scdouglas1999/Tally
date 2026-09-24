import { describe, expect, it } from 'vitest';
import {
  CATCH_FULL_MS,
  CATCH_TOTAL_MS,
  FIRST_BLIP_END_MS,
  MIN_SPUTTER_MS,
  SPUTTER,
  SPUTTER_CYCLE_MS,
  catchAt,
  catchStart,
  lampValueAt,
  sputterAt,
  withinOnsetLimit,
} from '../src/kit/lampTimeline';

describe('lamp timeline (same numbers as the Android TallyLampTimeline)', () => {
  it('has the Android cycle length and catch timings', () => {
    expect(SPUTTER_CYCLE_MS).toBe(2720);
    expect(CATCH_FULL_MS).toBe(310);
    expect(CATCH_TOTAL_MS).toBe(570);
    expect(FIRST_BLIP_END_MS).toBe(450);
  });

  it('is dark at the start of the sputter, blips at 400 ms and loops', () => {
    expect(sputterAt(0).brightness).toBe(0);
    expect(sputterAt(410).brightness).toBe(0.35);
    expect(sputterAt(410 + SPUTTER_CYCLE_MS).brightness).toBe(0.35);
  });

  it('never flashes more than three times in any second while sputtering', () => {
    const onsets: number[] = [];
    let prev = false;
    for (let t = 0; t < SPUTTER_CYCLE_MS * 3; t++) {
      const on = sputterAt(t).brightness > 0;
      if (on && !prev) onsets.push(t);
      prev = on;
    }
    expect(withinOnsetLimit(onsets)).toBe(true);
  });

  it('catches no earlier than the minimum sputter and the first blip, and ends lit', () => {
    const start = catchStart(0);
    expect(start).toBeGreaterThanOrEqual(Math.max(MIN_SPUTTER_MS, FIRST_BLIP_END_MS));
    expect(lampValueAt(start + CATCH_TOTAL_MS + 1, 0)).toEqual({ brightness: 1, glow: 1 });
    expect(catchAt(CATCH_FULL_MS).brightness).toBe(1);
  });

  it('never starts a catch part-way through a lit step', () => {
    // the lit steps' start times within a cycle
    const litStarts: number[] = [];
    let t = 0;
    for (const step of SPUTTER) {
      if (step.brightness > 0) litStarts.push(t);
      t += step.durationMs;
    }
    for (let lit = 0; lit < SPUTTER_CYCLE_MS; lit += 7) {
      const start = catchStart(lit);
      if (sputterAt(start).brightness > 0) expect(litStarts).toContain(start % SPUTTER_CYCLE_MS);
    }
  });

  it('keeps the steps the Android app has', () => {
    expect(SPUTTER.map((s) => s.durationMs)).toEqual([400, 50, 120, 40, 700, 60, 300, 110, 40, 900]);
  });
});
