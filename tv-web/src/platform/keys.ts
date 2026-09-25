import type { ShellPlatform } from '../shell-contract/shell';

/** What a remote key means to the app, whatever platform sent it. */
export type Key =
  | 'up' | 'down' | 'left' | 'right' | 'enter' | 'back'
  | 'play' | 'pause' | 'playPause' | 'stop' | 'fastForward' | 'rewind' | 'next' | 'previous'
  | 'red' | 'green' | 'yellow' | 'blue'
  | 'channelUp' | 'channelDown' | 'info' | 'menu'
  | '0' | '1' | '2' | '3' | '4' | '5' | '6' | '7' | '8' | '9';

/**
 * Key codes per platform. Tizen: Samsung's TVInputDevice codes (media, color and channel keys must be registered
 * first, see platform/tizen.ts); webOS: LG's codes (BACK is 461 once appinfo.json sets disableBackHistoryAPI).
 * Arrow keys and OK are the standard 37-40 and 13 everywhere.
 */
const COMMON: Record<number, Key> = {
  37: 'left', 38: 'up', 39: 'right', 40: 'down', 13: 'enter',
  415: 'play', 19: 'pause', 413: 'stop', 417: 'fastForward', 412: 'rewind',
  403: 'red', 404: 'green', 405: 'yellow', 406: 'blue', 457: 'info',
  48: '0', 49: '1', 50: '2', 51: '3', 52: '4', 53: '5', 54: '6', 55: '7', 56: '8', 57: '9',
};

const TIZEN: Record<number, Key> = {
  10009: 'back', 10252: 'playPause', 427: 'channelUp', 428: 'channelDown',
  10232: 'previous', 10233: 'next',
  // the on-screen keyboard's Done and Cancel
  65376: 'enter', 65385: 'back',
};

const WEBOS: Record<number, Key> = {
  461: 'back', 33: 'channelUp', 34: 'channelDown', 18: 'menu',
};

/** Desktop keyboards (and Playwright): Escape or Backspace is BACK, media keys where the keyboard has them. */
const BROWSER_KEYS: Record<string, Key> = {
  Escape: 'back', Backspace: 'back', BrowserBack: 'back', GoBack: 'back',
  MediaPlayPause: 'playPause', MediaPlay: 'play', MediaPause: 'pause', MediaStop: 'stop',
  MediaFastForward: 'fastForward', MediaRewind: 'rewind', MediaTrackNext: 'next', MediaTrackPrevious: 'previous',
  PageUp: 'channelUp', PageDown: 'channelDown', ContextMenu: 'menu',
  ColorF0Red: 'red', ColorF1Green: 'green', ColorF2Yellow: 'yellow', ColorF3Blue: 'blue',
};

export interface KeyInput {
  keyCode: number;
  key: string;
}

/** Maps a keydown to an app key, or null for keys the app does not use (typing into a field, for one). */
export function mapKey(event: KeyInput, platform: ShellPlatform, runtime?: Readonly<Record<number, Key>>): Key | null {
  // codes the TV reported for registered keys (tizen.tvinputdevice.getKey) win over the table
  const reported = runtime?.[event.keyCode];
  if (reported !== undefined) return reported;
  if (platform === 'tizen') {
    const k = TIZEN[event.keyCode];
    if (k !== undefined) return k;
  } else if (platform === 'webos') {
    const k = WEBOS[event.keyCode];
    if (k !== undefined) return k;
  } else {
    const k = BROWSER_KEYS[event.key];
    if (k !== undefined) return k;
    // a desktop keyboard's space is play/pause in the player (fields see it first: see keyRouter)
    if (event.key === ' ') return 'playPause';
  }
  return COMMON[event.keyCode] ?? null;
}

/** The Tizen key names to register so the TV delivers them to the app (arrows, OK and BACK need no registration). */
export const TIZEN_KEY_NAMES: Record<string, Key> = {
  MediaPlayPause: 'playPause', MediaPlay: 'play', MediaPause: 'pause', MediaStop: 'stop',
  MediaFastForward: 'fastForward', MediaRewind: 'rewind', MediaTrackPrevious: 'previous', MediaTrackNext: 'next',
  ColorF0Red: 'red', ColorF1Green: 'green', ColorF2Yellow: 'yellow', ColorF3Blue: 'blue',
  ChannelUp: 'channelUp', ChannelDown: 'channelDown', Info: 'info',
  // TOOLS on Samsung's standard remotes: the item menu (as MENU elsewhere); MENU itself stays the TV's settings
  Tools: 'menu',
  '0': '0', '1': '1', '2': '2', '3': '3', '4': '4', '5': '5', '6': '6', '7': '7', '8': '8', '9': '9',
};

export const TIZEN_KEYS_TO_REGISTER: readonly string[] = Object.keys(TIZEN_KEY_NAMES);
