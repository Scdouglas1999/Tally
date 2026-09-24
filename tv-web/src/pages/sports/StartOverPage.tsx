import { useEffect, useRef, useState } from 'preact/hooks';
import { absolute } from '../../api/tally';
import { useArrivalFocus, type PageProps } from '../../app/page';
import { FocusGroup, setFocus, useFocusable } from '../../focus/focus';
import { GlyphIcon, IndicatorSquare } from '../../kit/Bits';
import { Button } from '../../kit/Button';
import type { GlyphName } from '../../kit/glyphs';
import { useKeyHandler } from '../../platform/keyRouter';
import { back, type Route } from '../../router/router';
import { formatClock, tallyUppercase } from '../../util/format';
import { TuneIn, useEngine } from '../player/playerKit';
import './startOver.css';

/** Back and forward by these (the D-pad with the controls hidden, and the buttons). */
export const START_OVER_BACK_MS = 10_000;
export const START_OVER_FORWARD_MS = 30_000;
/** Closer than this to the newest segment counts as live. */
const LIVE_EDGE_MS = 30_000;
const CONTROLS_MS = 5000;

function SoButton(props: { focusKey: string; glyph: GlyphName; label: string; onPress: () => void; onFocus: (label: string) => void }) {
  const f = useFocusable<HTMLDivElement>({ focusKey: props.focusKey, onEnter: props.onPress, onFocus: () => props.onFocus(props.label) });
  return (
    <div ref={f.ref} class="so-btn" onClick={props.onPress}>
      <GlyphIcon name={props.glyph} />
    </div>
  );
}

/**
 * WATCH FROM THE START (StartOverPage.kt): a game being recorded, played from its first minute from the server's
 * start-over playlist (an HLS EVENT playlist that keeps growing). Played like a channel but seekable: the seek bar
 * spans what has been recorded so far and LIVE jumps to the newest minute. No score bug, no banners: this is a
 * recording, and nothing is reported to Jellyfin. With the controls hidden LEFT/RIGHT seek; BACK hides them first.
 */
export function StartOverPage(props: PageProps<Extract<Route, { name: 'startover' }>>) {
  const host = useRef<HTMLDivElement>(null);
  const player = useEngine(host);
  const [controls, setControls] = useState(true);
  const [lastKeyAt, setLastKeyAt] = useState(Date.now());
  const [pos, setPos] = useState(0);
  const [duration, setDuration] = useState(0);
  const [caption, setCaption] = useState('');
  const controlsGroup = useFocusable<HTMLDivElement>({ focusKey: 'so-controls' });

  useEffect(() => {
    const e = player.engine.current;
    if (e === null) return undefined;
    void e.load({ url: absolute(props.route.path), kind: 'hls', live: false, startMs: 0 }).catch(() => undefined);
    const t = window.setInterval(() => {
      const engine = player.engine.current;
      if (engine === null) return;
      setPos(engine.currentTime());
      setDuration(engine.duration());
    }, 250);
    return () => window.clearInterval(t);
  }, [props.route.path]);

  const playing = player.state === 'playing';
  useEffect(() => {
    if (!controls || !playing) return undefined;
    const t = window.setTimeout(() => setControls(false), CONTROLS_MS);
    return () => window.clearTimeout(t);
  }, [controls, lastKeyAt, playing]);

  useArrivalFocus(props, 'so-play', true);

  const atLive = duration <= 0 || duration - pos < LIVE_EDGE_MS;
  const seekBy = (delta: number): void => {
    const e = player.engine.current;
    if (e === null) return;
    const d = e.duration();
    e.seek(Math.max(0, Math.min(d > 0 ? d - 1000 : Infinity, e.currentTime() + delta)));
    setPos(e.currentTime());
  };
  const togglePlay = (): void => {
    const e = player.engine.current;
    if (e === null) return;
    if (playing) e.pause();
    else e.play();
  };
  const jumpToLive = (): void => {
    const e = player.engine.current;
    if (e === null) return;
    const d = e.duration();
    if (d > 0) e.seek(Math.max(0, d - 10_000));
    e.play();
    setFocus('so-play');
  };

  useKeyHandler((key) => {
    setLastKeyAt(Date.now());
    if (key === 'stop') {
      back();
      return true;
    }
    if (key === 'playPause') {
      togglePlay();
      return true;
    }
    if (!controls) {
      if (key === 'back') {
        back();
        return true;
      }
      if (key === 'left' || key === 'right') {
        seekBy(key === 'left' ? -START_OVER_BACK_MS : START_OVER_FORWARD_MS);
        setControls(true);
        return true;
      }
      // any other key only brings the controls up
      setControls(true);
      setFocus('so-play');
      return true;
    }
    if (key === 'back') {
      setControls(false);
      return true;
    }
    return false;
  }, props.active);

  const fraction = duration > 0 ? Math.min(1, pos / duration) : 0;
  return (
    <div class="startover">
      <div ref={host} class="so-video" />
      <TuneIn title={props.route.title} firstFrame={player.firstFrame} error={player.error !== null ? "This recording can't be played right now. If the game has ended, it is under Recorded." : null} />
      <div class={'so-controls' + (controls ? '' : ' hidden')}>
        <div class="so-top">
          <div class="kicker mono-label">
            <IndicatorSquare tone="live" />
            REC · FROM THE START
          </div>
          <div class="title ellipsis">{props.route.title}</div>
        </div>
        <div class="so-bottom">
          <div class="so-seek">
            <div class="track" />
            <div class="done" style={{ width: `${fraction * 100}%` }} />
            <div class="knob" style={{ left: `${fraction * 100}%` }} />
          </div>
          <div class="so-times">
            <span>{formatClock(pos)}</span>
            <span class={atLive ? 'live' : ''}>{atLive ? 'LIVE' : tallyUppercase(`${formatClock(duration - pos)} behind live`)}</span>
          </div>
          <div ref={controlsGroup.ref} class="so-buttons">
            <FocusGroup focusKey="so-controls">
              <div class="center">
                <SoButton focusKey="so-back" glyph="backward" label="Back 10 seconds" onPress={() => seekBy(-START_OVER_BACK_MS)} onFocus={setCaption} />
                <SoButton focusKey="so-play" glyph={playing ? 'pause' : 'play'} label={playing ? 'Pause' : 'Play'} onPress={togglePlay} onFocus={setCaption} />
                <SoButton focusKey="so-forward" glyph="forward" label="Forward 30 seconds" onPress={() => seekBy(START_OVER_FORWARD_MS)} onFocus={setCaption} />
              </div>
              {!atLive ? (
                <div class="right">
                  <Button focusKey="so-live" label="Live" onPress={jumpToLive} onFocus={() => setCaption('')} />
                </div>
              ) : null}
            </FocusGroup>
          </div>
          <div class="so-caption mono-label">{tallyUppercase(caption)}</div>
        </div>
      </div>
    </div>
  );
}
