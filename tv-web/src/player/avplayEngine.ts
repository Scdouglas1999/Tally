import type { AvPlayApi } from '../platform/tizen-types';
import '../platform/tizen-types';
import type { EngineEvents, NativeAudioTrack, PlayerEngine, Source } from './engine';

/**
 * Samsung AVPlay (developer.samsung.com AVPlay API). The decoder draws on a hardware plane behind the web page:
 * an <object type="application/avplayer"> marks where, and everything above it in the page must be transparent
 * (this engine sets `native-video` on <html> and <body>; player.css clears their backgrounds). Plays HLS (live and VOD), and MP4/MKV/TS files directly, with
 * HEVC, AV1 (2020+ sets), HDR10 and Dolby/DTS passthrough as the TV allows.
 *
 * Lifecycle: a Tizen app sent to the background must release the decoder (suspend) and take it back (restore).
 */
export function createAvPlayEngine(host: HTMLElement, events: EngineEvents): PlayerEngine {
  const avplay: AvPlayApi | undefined = window.webapis?.avplay;
  if (avplay === undefined) throw new Error('AVPlay is not available (webapis.js missing?)');
  const object = document.createElement('object');
  object.setAttribute('type', 'application/avplayer');
  object.className = 'engine-avplay';
  host.appendChild(object);
  document.documentElement.classList.add('native-video');
  document.body.classList.add('native-video');

  let source: Source | null = null;
  let position = 0;
  let firstFrameSent = false;
  let destroyed = false;

  const setState = (): void => {
    const s = avplay.getState();
    if (s === 'PLAYING') events.state('playing');
    else if (s === 'PAUSED') events.state('paused');
  };

  avplay.setListener({
    onbufferingstart: () => events.state('buffering'),
    onbufferingcomplete: () => setState(),
    oncurrentplaytime: (ms: number) => {
      position = ms;
      events.time(ms);
      if (!firstFrameSent && avplay.getState() === 'PLAYING') {
        firstFrameSent = true;
        events.firstFrame();
      }
    },
    onstreamcompleted: () => events.state('ended'),
    onerror: (type: string) => {
      events.error('Playback failed (' + type + ').');
      events.state('error');
    },
  });

  const onVisibility = (): void => {
    if (destroyed || source === null) return;
    try {
      if (document.hidden) avplay.suspend();
      else avplay.restore(source.url, source.live ? undefined : position, true);
    } catch {
      // restore failing leaves the error listener to report it
    }
  };
  document.addEventListener('visibilitychange', onVisibility);

  return {
    name: 'avplay',
    load(src: Source) {
      source = src;
      firstFrameSent = false;
      position = src.startMs;
      events.state('loading');
      return new Promise<void>((resolve, reject) => {
        try {
          avplay.open(src.url);
          avplay.setDisplayRect(0, 0, 1920, 1080);
          avplay.setDisplayMethod('PLAYER_DISPLAY_MODE_LETTER_BOX');
          if (src.live) {
            // start at the live edge minus a cushion rather than the playlist's first segment
            try {
              avplay.setBufferingParam?.('PLAYER_BUFFER_FOR_PLAY', 'PLAYER_BUFFER_SIZE_IN_SECOND', 6);
            } catch {
              // older firmware: defaults
            }
          }
          avplay.prepareAsync(
            () => {
              const begin = (): void => {
                avplay.play();
                setState();
                resolve();
              };
              if (!src.live && src.startMs > 0) avplay.seekTo(src.startMs, begin, begin);
              else begin();
            },
            (e: unknown) => {
              events.error('The TV could not open this stream.');
              events.state('error');
              reject(e instanceof Error ? e : new Error(String(e)));
            },
          );
        } catch (e) {
          reject(e instanceof Error ? e : new Error(String(e)));
        }
      });
    },
    play() {
      avplay.play();
      events.state('playing');
    },
    pause() {
      avplay.pause();
      events.state('paused');
    },
    seek(ms: number) {
      avplay.seekTo(Math.max(0, Math.floor(ms)));
    },
    stop() {
      try {
        avplay.stop();
      } catch {
        // already stopped
      }
    },
    destroy() {
      destroyed = true;
      document.removeEventListener('visibilitychange', onVisibility);
      try {
        avplay.stop();
        avplay.close();
      } catch {
        // closing an idle player throws on some firmware
      }
      object.remove();
      document.documentElement.classList.remove('native-video');
      document.body.classList.remove('native-video');
    },
    currentTime: () => position,
    duration: () => {
      try {
        return source?.live === true ? 0 : avplay.getDuration();
      } catch {
        return 0;
      }
    },
    nativeAudioTracks(): NativeAudioTrack[] {
      try {
        return avplay
          .getTotalTrackInfo()
          .filter((t) => t.type === 'AUDIO')
          .map((t) => {
            let language = '';
            try {
              language = String((JSON.parse(t.extra_info) as { language?: string }).language ?? '');
            } catch {
              // extra_info is not always JSON
            }
            return { index: t.index, language, label: language !== '' ? language.toUpperCase() : `Track ${t.index}` };
          });
      } catch {
        return [];
      }
    },
    selectNativeAudio(index: number) {
      avplay.setSelectTrack('AUDIO', index);
    },
    // no setSpeed: AVPlay's speeds are trick play (silent), not a watching speed
    setScale(scale) {
      try {
        avplay.setDisplayMethod(scale === 'fill' ? 'PLAYER_DISPLAY_MODE_FULL_SCREEN' : 'PLAYER_DISPLAY_MODE_LETTER_BOX');
      } catch {
        // not before open(): the next load starts letterboxed
      }
    },
    // AVPlay has no crop mode
    scales: () => ['fit', 'fill'],
    setDisplayArea(x, y, width, height) {
      try {
        avplay.setDisplayRect(Math.round(x), Math.round(y), Math.round(width), Math.round(height));
      } catch {
        // before open(): load() sets the full screen
      }
    },
  };
}
