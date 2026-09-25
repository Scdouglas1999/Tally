/**
 * The parts of Samsung's Tizen TV APIs this app uses (developer.samsung.com/smarttv/develop/api-references).
 * `tizen` and `webapis` exist only inside an installed Tizen app (webapis needs the $WEBAPIS/webapis/webapis.js
 * script, which shell/index.html loads).
 */
export interface TizenKey {
  name: string;
  code: number;
}

export interface TizenApi {
  tvinputdevice: {
    getSupportedKeys(): TizenKey[];
    getKey(name: string): TizenKey | null;
    registerKey(name: string): void;
    registerKeyBatch?(names: string[], onSuccess?: () => void, onError?: (e: unknown) => void): void;
  };
  application: {
    getCurrentApplication(): { exit(): void; hide(): void };
  };
  systeminfo?: {
    getCapability(key: string): unknown;
  };
}

export type AvPlayState = 'NONE' | 'IDLE' | 'READY' | 'PLAYING' | 'PAUSED';

export interface AvPlayTrack {
  index: number;
  type: 'VIDEO' | 'AUDIO' | 'TEXT';
  extra_info: string;
}

export interface AvPlayListener {
  onbufferingstart?(): void;
  onbufferingprogress?(percent: number): void;
  onbufferingcomplete?(): void;
  oncurrentplaytime?(ms: number): void;
  onstreamcompleted?(): void;
  onevent?(type: string, data: string): void;
  onerror?(type: string): void;
  onsubtitlechange?(duration: number, text: string, type: number, attributes: unknown): void;
  ondrmevent?(type: string, data: unknown): void;
}

export interface AvPlayApi {
  open(url: string): void;
  close(): void;
  prepareAsync(onSuccess: () => void, onError: (e: unknown) => void): void;
  play(): void;
  pause(): void;
  stop(): void;
  seekTo(ms: number, onSuccess?: () => void, onError?: (e: unknown) => void): void;
  jumpForward(ms: number): void;
  jumpBackward(ms: number): void;
  getState(): AvPlayState;
  getDuration(): number;
  getCurrentTime(): number;
  setListener(listener: AvPlayListener): void;
  setDisplayRect(x: number, y: number, width: number, height: number): void;
  setDisplayMethod(method: 'PLAYER_DISPLAY_MODE_LETTER_BOX' | 'PLAYER_DISPLAY_MODE_FULL_SCREEN' | 'PLAYER_DISPLAY_MODE_AUTO_ASPECT_RATIO'): void;
  getTotalTrackInfo(): AvPlayTrack[];
  setSelectTrack(type: 'AUDIO' | 'TEXT', index: number): void;
  setSilentSubtitle(silent: boolean): void;
  setExternalSubtitlePath?(path: string): void;
  setStreamingProperty(property: string, value: string): void;
  setBufferingParam?(option: string, unit: string, amount: number): void;
  suspend(): void;
  restore(url: string, resumeMs?: number, prepare?: boolean): void;
}

export interface WebApis {
  avplay: AvPlayApi;
  appcommon?: {
    setScreenSaver(state: number, onSuccess?: () => void, onError?: () => void): void;
    AppCommonScreenSaverState: { SCREEN_SAVER_OFF: number; SCREEN_SAVER_ON: number };
  };
  productinfo?: {
    getRealModel(): string;
    getDuid(): string;
    getFirmware(): string;
    is8KPanelSupported?(): boolean;
    isUdPanelSupported?(): boolean;
  };
}

declare global {
  interface Window {
    tizen?: TizenApi;
    webapis?: WebApis;
  }
}
