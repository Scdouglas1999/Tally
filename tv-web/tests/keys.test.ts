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
  });

  it('prefers the codes the TV reported for registered keys', () => {
    expect(mapKey({ keyCode: 9999, key: '' }, 'tizen', { 9999: 'info' })).toBe('info');
  });
});
