import { describe, expect, it } from 'vitest';
import { mapKey } from '../src/platform/keys';

describe('remote keys', () => {
  it('maps BACK per platform', () => {
    expect(mapKey({ keyCode: 10009, key: 'XF86Back' }, 'tizen')).toBe('back');
    expect(mapKey({ keyCode: 461, key: 'GoBack' }, 'webos')).toBe('back');
    expect(mapKey({ keyCode: 27, key: 'Escape' }, 'browser')).toBe('back');
    expect(mapKey({ keyCode: 461, key: '' }, 'tizen')).toBeNull();
  });

  it('maps arrows, OK and media keys everywhere', () => {
    for (const p of ['tizen', 'webos', 'browser'] as const) {
      expect(mapKey({ keyCode: 37, key: 'ArrowLeft' }, p)).toBe('left');
      expect(mapKey({ keyCode: 13, key: 'Enter' }, p)).toBe('enter');
      expect(mapKey({ keyCode: 415, key: 'MediaPlay' }, p)).toBe('play');
      expect(mapKey({ keyCode: 403, key: 'ColorF0Red' }, p)).toBe('red');
    }
    expect(mapKey({ keyCode: 10252, key: 'MediaPlayPause' }, 'tizen')).toBe('playPause');
    expect(mapKey({ keyCode: 427, key: 'XF86RaiseChannel' }, 'tizen')).toBe('channelUp');
    expect(mapKey({ keyCode: 33, key: 'PageUp' }, 'webos')).toBe('channelUp');
    expect(mapKey({ keyCode: 65376, key: '' }, 'tizen')).toBe('enter');
    expect(mapKey({ keyCode: 65385, key: '' }, 'tizen')).toBe('back');
  });

  it('prefers the codes the TV reported for registered keys', () => {
    expect(mapKey({ keyCode: 9999, key: '' }, 'tizen', { 9999: 'info' })).toBe('info');
  });
});

describe('held keys (isRepeat)', () => {
  const ev = (keyCode: number, repeat = false) => ({ keyCode, repeat }) as unknown as KeyboardEvent;
  it('takes the Tizen runtime’s up/down pairs as repeats, not new presses; a double tap stays two presses', async () => {
    const { isRepeat, trackRepeat } = await import('../src/platform/keyRouter');
    // what the emulator delivered for OK held 1.2 s: down, up after 660 ms, then pairs ~40 ms apart (gaps up to 92 ms)
    const first = ev(13);
    trackRepeat('down', first, 1000);
    expect(isRepeat(first)).toBe(false);
    trackRepeat('up', ev(13), 1660);
    const again = ev(13);
    trackRepeat('down', again, 1661);
    expect(isRepeat(again)).toBe(true);
    trackRepeat('up', ev(13), 1700);
    const late = ev(13);
    trackRepeat('down', late, 1792);
    expect(isRepeat(late)).toBe(true);
    trackRepeat('up', ev(13), 1800);
    // a new press after a real pause
    const next = ev(13);
    trackRepeat('down', next, 2300);
    expect(isRepeat(next)).toBe(false);
    trackRepeat('up', ev(13), 2380);
    // a quick double tap of LEFT (Playwright's press, a person tapping): two presses
    const tap1 = ev(37);
    trackRepeat('down', tap1, 3000);
    trackRepeat('up', ev(37), 3010);
    const tap2 = ev(37);
    trackRepeat('down', tap2, 3030);
    expect(isRepeat(tap1)).toBe(false);
    expect(isRepeat(tap2)).toBe(false);
    trackRepeat('up', ev(37), 3040);
    // browsers flag repeats themselves; a key-down while the key is still down is a repeat
    expect(isRepeat(ev(39, true))).toBe(true);
    const r1 = ev(39);
    trackRepeat('down', r1, 4000);
    const r2 = ev(39);
    trackRepeat('down', r2, 4050);
    expect(isRepeat(r1)).toBe(false);
    expect(isRepeat(r2)).toBe(true);
  });
  it('registers TOOLS as the item menu on Tizen', async () => {
    const { TIZEN_KEY_NAMES } = await import('../src/platform/keys');
    expect(TIZEN_KEY_NAMES.Tools).toBe('menu');
    expect(mapKey({ keyCode: 10135, key: '' }, 'tizen', { 10135: 'menu' })).toBe('menu');
  });
});
