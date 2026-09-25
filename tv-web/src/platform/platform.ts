import type { ShellPlatform, TallyShell } from '../shell-contract/shell';
import { TIZEN_KEY_NAMES, type Key } from './keys';
import './tizen-types';

/**
 * What differs between Samsung, LG and a browser, behind one small interface. Screens never test the platform
 * themselves; they ask this.
 */
export interface Platform {
  readonly name: ShellPlatform;
  /** Key codes the TV reported at startup (Tizen registered keys); merged into mapKey. */
  readonly runtimeKeys: Readonly<Record<number, Key>>;
  /** Human device name for Jellyfin's session list ("Samsung QN65Q80T"). */
  deviceName(): string;
  /** Keeps the TV from starting its screensaver (playback) or lets it (everything else). */
  keepAwake(awake: boolean): void;
  /** Leaves the app. */
  exit(): void;
  /** The TV model (Samsung's model code, e.g. "QN55Q80AAFXZA"), '' where unknown. */
  model(): string;
  /** Tizen's version as a number (6.5), 0 elsewhere. */
  tizenVersion(): number;
}

function tizenPlatform(shell: TallyShell): Platform {
  const runtimeKeys: Record<number, Key> = {};
  const tizen = window.tizen;
  if (tizen !== undefined) {
    for (const name of Object.keys(TIZEN_KEY_NAMES)) {
      try {
        tizen.tvinputdevice.registerKey(name);
        const key = tizen.tvinputdevice.getKey(name);
        const meaning = TIZEN_KEY_NAMES[name];
        if (key !== null && meaning !== undefined) runtimeKeys[key.code] = meaning;
      } catch {
        // a key this model does not have (no color keys on the Smart Remote): skip it
      }
    }
  }
  return {
    name: 'tizen',
    runtimeKeys,
    deviceName() {
      try {
        const model = window.webapis?.productinfo?.getRealModel();
        if (model !== undefined && model !== '') return 'Samsung ' + model;
      } catch {
        // productinfo needs its privilege; the name is cosmetic
      }
      return 'Samsung TV';
    },
    keepAwake(awake) {
      const common = window.webapis?.appcommon;
      if (common === undefined) return;
      const states = common.AppCommonScreenSaverState;
      try {
        common.setScreenSaver(awake ? states.SCREEN_SAVER_OFF : states.SCREEN_SAVER_ON);
      } catch {
        // not fatal: the TV may dim during a long film
      }
    },
    exit() {
      shell.exit();
    },
    model() {
      try {
        return window.webapis?.productinfo?.getRealModel() ?? '';
      } catch {
        return '';
      }
    },
    tizenVersion() {
      try {
        const v = window.tizen?.systeminfo?.getCapability('http://tizen.org/feature/platform.version');
        return typeof v === 'string' ? parseFloat(v) || 0 : 0;
      } catch {
        return 0;
      }
    },
  };
}

function webosPlatform(shell: TallyShell): Platform {
  return {
    name: 'webos',
    runtimeKeys: {},
    deviceName() {
      return 'LG TV';
    },
    keepAwake() {
      // webOS keeps the screen on while a <video> plays; a Luna call is needed only for music (later)
    },
    exit() {
      shell.exit();
    },
    model: () => '',
    tizenVersion: () => 0,
  };
}

function browserPlatform(shell: TallyShell): Platform {
  return {
    name: 'browser',
    runtimeKeys: {},
    deviceName() {
      const ua = navigator.userAgent;
      if (ua.indexOf('Firefox') >= 0) return 'Firefox';
      if (ua.indexOf('Edg/') >= 0) return 'Edge';
      if (ua.indexOf('Chrome') >= 0) return 'Chrome';
      return 'Browser';
    },
    keepAwake() {
      // the Screen Wake Lock API is Chrome 84+: later
    },
    exit() {
      shell.exit();
    },
    model: () => '',
    tizenVersion: () => 0,
  };
}

export function createPlatform(shell: TallyShell): Platform {
  switch (shell.platform) {
    case 'tizen':
      return tizenPlatform(shell);
    case 'webos':
      return webosPlatform(shell);
    default:
      return browserPlatform(shell);
  }
}
