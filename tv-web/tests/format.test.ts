import { describe, expect, it } from 'vitest';
import { formatClock, formatGameStart, formatRuntime, gameStatusLabel, resumePercent, splitCode, tallyUppercase } from '../src/util/format';

describe('formats', () => {
  it('uppercases labels but keeps units', () => {
    expect(tallyUppercase('Original · 14.8 Mbps')).toBe('ORIGINAL · 14.8 Mbps');
    expect(tallyUppercase('1080p · 2h 35m')).toBe('1080p · 2h 35m');
    expect(tallyUppercase('Direct play')).toBe('DIRECT PLAY');
  });

  it('formats runtimes like the Android app', () => {
    expect(formatRuntime(0)).toBe('0s');
    expect(formatRuntime(90 * 10_000_000)).toBe('1m 30s');
    expect(formatRuntime((2 * 3600 + 35 * 60) * 10_000_000)).toBe('2h 35m');
    expect(formatRuntime(2 * 3600 * 10_000_000)).toBe('2h');
  });

  it('formats clocks, codes and percentages', () => {
    expect(formatClock(3_723_000)).toBe('1:02:03');
    expect(formatClock(245_000)).toBe('04:05');
    expect(splitCode('831550')).toBe('831 550');
    expect(splitCode('12345')).toBe('12345');
    expect(resumePercent(42, 100)).toBe(42);
    expect(resumePercent(0, 100)).toBe(0);
  });

  it('labels game status', () => {
    const now = new Date(2026, 8, 24, 12, 0);
    const today = new Date(2026, 8, 24, 19, 30).toISOString();
    const sunday = new Date(2026, 8, 27, 13, 5).toISOString();
    expect(formatGameStart(today, now)).toBe('7:30 PM');
    expect(formatGameStart(sunday, now)).toBe('SUN 1:05 PM');
    expect(gameStatusLabel({ state: 'in', detail: 'Bot 7th', start: today })).toBe('Bot 7th');
    expect(gameStatusLabel({ state: 'post', detail: 'Final/12', start: today })).toBe('FINAL/12');
  });
});
