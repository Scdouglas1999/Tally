import type { BaseItemDto } from '@jellyfin/sdk/lib/generated-client/models/base-item-dto';
import { getLibraryApi } from '@jellyfin/sdk/lib/utils/api/library-api';
import { useEffect, useRef, useState } from 'preact/hooks';
import { currentApi, session } from '../../api/jellyfin';
import { app } from '../../app/context';
import type { PageProps } from '../../app/page';
import { FocusGroup, setFocus, useFocusable } from '../../focus/focus';
import { episodeCode } from '../../kit/ItemCard';
import { useKeyHandler } from '../../platform/keyRouter';
import { preparePlayback, reporter, type Prepared } from '../../player/playback';
import { qualityOptions, type QualityOption } from '../../player/qualityLadder';
import { back, type Route } from '../../router/router';
import { formatClock, formatTime, tallyUppercase } from '../../util/format';
import { IconButton, Menu, SubtitleLayer, TuneIn, useEngine, type MenuOption } from './playerKit';

const OSD_MS = 5000;
const SEEK_STEP_MS = 10_000;
const REPORT_MS = 10_000;

type MenuKind = 'audio' | 'subtitles' | 'quality' | null;

const ORIGINAL: QualityOption = { bitsPerSecond: null, maxWidth: null, maxHeight: null, label: 'Original' };

/**
 * The library player: an engine, text subtitles drawn by the app, Tally controls (seek bar, transport, CC / audio /
 * quality menus). Audio and subtitle choices and a quality rung restart the stream at the current position with a
 * new PlaybackInfo, so they work on every engine (AVPlay can also switch audio itself: later).
 */
export function PlayerPage(props: PageProps<Extract<Route, { name: 'player' }>>) {
  const host = useRef<HTMLDivElement>(null);
  const player = useEngine(host);
  const [item, setItem] = useState<BaseItemDto | null>(null);
  const [prepared, setPrepared] = useState<Prepared | null>(null);
  const preparedRef = useRef<Prepared | null>(null);
  preparedRef.current = prepared;
  const [loadError, setLoadError] = useState<string | null>(null);
  const [osd, setOsd] = useState(true);
  const [menu, setMenu] = useState<MenuKind>(null);
  const [caption, setCaption] = useState('');
  const [quality, setQuality] = useState<QualityOption>(ORIGINAL);
  const [subtitleIndex, setSubtitleIndex] = useState<number | null>(null);
  const osdTimer = useRef(0);
  const report = useRef(reporter(props.route.itemId, () => preparedRef.current));
  // the last position the engine reported: the engine is already gone when the page's cleanup sends "stopped"
  const lastMs = useRef(props.route.startMs ?? 0);
  if (player.timeMs > 0) lastMs.current = player.timeMs;
  const controls = useFocusable<HTMLDivElement>({ focusKey: 'player-controls' });

  const showOsd = (): void => {
    setOsd(true);
    window.clearTimeout(osdTimer.current);
    osdTimer.current = window.setTimeout(() => {
      if (menu === null) setOsd(false);
    }, OSD_MS);
  };

  /** (Re)starts the stream at `startMs` with the given choices. */
  const start = async (startMs: number, choice: { audio?: number; subtitle?: number; quality: QualityOption }): Promise<void> => {
    // a restart names the source it had, so Jellyfin honors the track choice
    const engine = player.engine.current;
    if (engine === null) return;
    try {
      const p = await preparePlayback(app.platform, {
        itemId: props.route.itemId,
        mediaSourceId: preparedRef.current?.mediaSource.Id ?? undefined,
        startMs,
        audioIndex: choice.audio,
        subtitleIndex: choice.subtitle,
        quality: choice.quality,
      });
      setPrepared(p);
      if (choice.subtitle === undefined) {
        // the server's default only if it is a text track the app can draw
        const def = p.subtitles.find((t) => t.index === p.subtitleIndex && t.text);
        setSubtitleIndex(def !== undefined ? def.index : null);
      }
      engine.stop();
      await engine.load(p.source);
    } catch (e) {
      setLoadError(e instanceof Error ? e.message : 'Could not play this.');
    }
  };

  useEffect(() => {
    const s = session.get();
    if (s !== null) {
      getLibraryApi(currentApi())
        .getItem({ itemId: props.route.itemId, userId: s.userId })
        .then((r) => setItem(r.data))
        .catch(() => undefined);
    }
    void start(props.route.startMs ?? 0, { quality: ORIGINAL }).then(() => report.current.start(props.route.startMs ?? 0));
    showOsd();
    setFocus('player-playpause');
    const t = window.setInterval(() => {
      const e = player.engine.current;
      if (e !== null) report.current.progress(e.currentTime(), false);
    }, REPORT_MS);
    return () => {
      window.clearInterval(t);
      window.clearTimeout(osdTimer.current);
      report.current.stop(lastMs.current);
    };
  }, []);

  useEffect(() => {
    if (player.state === 'ended') back();
  }, [player.state]);

  const togglePlay = (): void => {
    const e = player.engine.current;
    if (e === null) return;
    if (player.state === 'playing') e.pause();
    else e.play();
    showOsd();
  };
  const seekBy = (delta: number): void => {
    const e = player.engine.current;
    if (e === null) return;
    const duration = e.duration();
    const target = Math.max(0, Math.min(duration > 0 ? duration - 1000 : Infinity, e.currentTime() + delta));
    e.seek(target);
    showOsd();
  };

  useKeyHandler((key) => {
    if (menu !== null) return false; // the menu's own handler (registered later) runs first
    switch (key) {
      case 'back':
        if (osd) {
          setOsd(false);
          return true;
        }
        back();
        return true;
      case 'playPause':
        togglePlay();
        return true;
      case 'play':
        player.engine.current?.play();
        showOsd();
        return true;
      case 'pause':
        player.engine.current?.pause();
        showOsd();
        return true;
      case 'fastForward':
        seekBy(SEEK_STEP_MS * 3);
        return true;
      case 'rewind':
        seekBy(-SEEK_STEP_MS * 3);
        return true;
      case 'stop':
        back();
        return true;
      default:
        if (!osd) {
          // hidden controls: LEFT/RIGHT seek, anything else brings them back
          if (key === 'left' || key === 'right') {
            seekBy(key === 'left' ? -SEEK_STEP_MS : SEEK_STEP_MS);
            return true;
          }
          showOsd();
          setFocus('player-playpause');
          return true;
        }
        showOsd();
        return false;
    }
  }, props.active);

  const now = player.timeMs;
  const duration = player.engine.current?.duration() ?? 0;
  const pct = duration > 0 ? Math.min(100, (now / duration) * 100) : 0;
  const subtitleTrack = prepared?.subtitles.find((t) => t.index === subtitleIndex) ?? null;

  const menuOptions = (): MenuOption[] => {
    if (prepared === null) return [];
    if (menu === 'audio') return prepared.audio.map((a) => ({ key: String(a.index), label: a.label, selected: a.index === prepared.audioIndex }));
    if (menu === 'subtitles') {
      return [{ key: '-1', label: 'Off', selected: subtitleIndex === null }].concat(
        prepared.subtitles.map((t) => ({ key: String(t.index), label: t.label + (t.text ? '' : ' (burned in)'), selected: t.index === subtitleIndex })),
      );
    }
    if (menu === 'quality') {
      return qualityOptions(prepared.sourceHeight, prepared.sourceBitrate).map((q) => ({
        key: String(q.bitsPerSecond ?? 0),
        label: q.label,
        selected: (q.bitsPerSecond ?? 0) === (quality.bitsPerSecond ?? 0),
      }));
    }
    return [];
  };

  const pick = (key: string): void => {
    const kind = menu;
    setMenu(null);
    setFocus('player-playpause');
    showOsd();
    if (prepared === null) return;
    const at = player.engine.current?.currentTime() ?? 0;
    if (kind === 'audio') {
      void start(at, { audio: Number(key), subtitle: subtitleIndex ?? -1, quality });
    } else if (kind === 'subtitles') {
      const index = Number(key);
      const track = prepared.subtitles.find((t) => t.index === index);
      if (index < 0 || track === undefined) setSubtitleIndex(null);
      else if (track.text) setSubtitleIndex(index);
      else void start(at, { audio: prepared.audioIndex ?? undefined, subtitle: index, quality }); // burned in by the server
    } else if (kind === 'quality') {
      const option = qualityOptions(prepared.sourceHeight, prepared.sourceBitrate).find((q) => String(q.bitsPerSecond ?? 0) === key) ?? ORIGINAL;
      setQuality(option);
      void start(at, { audio: prepared.audioIndex ?? undefined, subtitle: subtitleIndex ?? -1, quality: option });
    }
  };

  const code = item !== null ? episodeCode(item) : null;
  const kicker = item === null ? '' : item.Type === 'Episode' ? [item.SeriesName ?? '', code ?? ''].filter((x) => x !== '').join(' · ') : 'Film';
  const remaining = duration > 0 ? duration - now : 0;

  return (
    <div class={'player' + (osd ? ' osd-visible' : '')}>
      <div ref={host} />
      <SubtitleLayer url={subtitleTrack?.vttUrl ?? null} timeMs={now} />
      <TuneIn label="Loading" title={item?.Name ?? ''} firstFrame={player.firstFrame} error={loadError ?? player.error} />
      <div style={{ display: osd ? 'block' : 'none' }}>
        <div class="osd-top">
          <div class="kicker mono-label">{tallyUppercase(kicker)}</div>
          <div class="title ellipsis">{item?.Name ?? ''}</div>
          {prepared !== null ? <div class="sub mono-label">{tallyUppercase(prepared.delivery === 'direct' ? 'Direct play' : prepared.delivery === 'remux' ? 'Remux' : 'Converting') + ' · ' + tallyUppercase(quality.label)}</div> : null}
          <div class="clock">{formatTime(new Date())}</div>
        </div>
        <div class="osd-bottom">
          <div class="seek">
            <div class="track" />
            <div class="done" style={{ width: `${pct}%` }} />
            <div class="knob" style={{ left: `${pct}%` }} />
          </div>
          <div class="times">
            <span>{formatClock(now)}</span>
            <span class="right">{duration > 0 ? `-${formatClock(remaining)} · ENDS ${formatTime(new Date(Date.now() + remaining))}` : ''}</span>
          </div>
          <div ref={controls.ref} class="buttons">
            <FocusGroup focusKey="player-controls">
              <div class="group center">
                <IconButton focusKey="player-rewind" glyph="backward" label="Back 10s" onPress={() => seekBy(-SEEK_STEP_MS)} onFocus={setCaption} />
                <IconButton focusKey="player-playpause" glyph={player.state === 'playing' ? 'pause' : 'play'} label={player.state === 'playing' ? 'Pause' : 'Play'} onPress={togglePlay} onFocus={setCaption} />
                <IconButton focusKey="player-forward" glyph="forward" label="Forward 10s" onPress={() => seekBy(SEEK_STEP_MS)} onFocus={setCaption} />
              </div>
              <div class="group right">
                <IconButton focusKey="player-subtitles" glyph="captions" label="Subtitles" onPress={() => setMenu('subtitles')} onFocus={setCaption} />
                <IconButton focusKey="player-audio" glyph="volume" label="Audio" onPress={() => setMenu('audio')} onFocus={setCaption} />
                <IconButton focusKey="player-quality" glyph="gear" label="Quality" onPress={() => setMenu('quality')} onFocus={setCaption} />
              </div>
            </FocusGroup>
          </div>
          <div class="caption mono-label">{tallyUppercase(caption)}</div>
        </div>
      </div>
      {menu !== null ? (
        <Menu
          title={menu === 'audio' ? 'Audio' : menu === 'subtitles' ? 'Subtitles' : 'Quality'}
          options={menuOptions()}
          onPick={pick}
          onClose={() => {
            const from = menu;
            setMenu(null);
            setFocus('player-' + (from ?? 'playpause'));
          }}
        />
      ) : null}
    </div>
  );
}
