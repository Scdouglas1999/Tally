import { useEffect, useRef, useState } from 'preact/hooks';
import { app } from '../../app/context';
import { FocusGroup, setFocus, useFocusable } from '../../focus/focus';
import { GlyphIcon, IndicatorSquare } from '../../kit/Bits';
import type { GlyphName } from '../../kit/glyphs';
import { Lamp, type LampState } from '../../kit/Lamp';
import { useKeyHandler } from '../../platform/keyRouter';
import { createEngine } from '../../player/createEngine';
import type { EngineEvents, EngineState, PlayerEngine } from '../../player/engine';
import { activeCues, parseVtt, type Cue } from '../../player/subtitles';
import { tallyUppercase } from '../../util/format';
import './player.css';

export interface EngineHandle {
  /** Set once the page has mounted (effects declared after useEngine see it). */
  engine: { current: PlayerEngine | null };
  state: EngineState;
  timeMs: number;
  firstFrame: boolean;
  error: string | null;
}

/** Creates the platform's engine in `host` for the life of the page; keeps the TV awake meanwhile. */
export function useEngine(host: { current: HTMLDivElement | null }): EngineHandle {
  const [state, setState] = useState<EngineState>('idle');
  const [timeMs, setTime] = useState(0);
  const [firstFrame, setFirstFrame] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const engine = useRef<PlayerEngine | null>(null);
  useEffect(() => {
    if (host.current === null) return undefined;
    const events: EngineEvents = {
      state: (next) => {
        setState(next);
        if (next === 'loading') setFirstFrame(false); // a new stream: tune in again
      },
      time: setTime,
      firstFrame: () => setFirstFrame(true),
      error: setError,
    };
    engine.current = createEngine(app.platform, host.current, events, app.shell.bundleBase);
    app.platform.keepAwake(true);
    return () => {
      engine.current?.destroy();
      engine.current = null;
      app.platform.keepAwake(false);
    };
  }, []);
  return { engine, state, timeMs, firstFrame, error };
}

/** The tune-in card: the lamp sputters until the first frame, catches, then the card fades out. */
export function TuneIn(props: { title: string; firstFrame: boolean; error: string | null; label?: string }) {
  const [gone, setGone] = useState(false);
  const [hidden, setHidden] = useState(false);
  const lamp: LampState = props.error !== null ? 'off' : props.firstFrame ? 'lit' : 'sputtering';
  useEffect(() => {
    if (gone) {
      const t = window.setTimeout(() => setHidden(true), 260);
      return () => window.clearTimeout(t);
    }
    return undefined;
  }, [gone]);
  if (hidden) return null;
  return (
    <div class={'tune-in' + (gone ? ' gone' : '')}>
      <Lamp state={lamp} size={32} onSettled={() => window.setTimeout(() => setGone(true), 300)} />
      <div class="label mono-label">{tallyUppercase(props.label ?? 'Tuning in')}</div>
      {props.title !== '' ? <div class="what">{props.title}</div> : null}
      {props.error !== null ? <div class="failed">{props.error}</div> : null}
    </div>
  );
}

export function IconButton(props: { glyph: GlyphName; label: string; onPress: () => void; focusKey: string; onFocus?: (label: string) => void }) {
  const f = useFocusable<HTMLDivElement>({ focusKey: props.focusKey, onEnter: props.onPress, onFocus: () => props.onFocus?.(props.label) });
  return (
    <div ref={f.ref} class="icon-btn" onClick={props.onPress}>
      <GlyphIcon name={props.glyph} />
    </div>
  );
}

export interface MenuOption {
  key: string;
  label: string;
  selected: boolean;
}

function MenuRow(props: { option: MenuOption; focusKey: string; onPick: () => void }) {
  const f = useFocusable<HTMLDivElement>({ focusKey: props.focusKey, onEnter: props.onPick });
  return (
    <div ref={f.ref} class={'option' + (props.option.selected ? ' selected' : '')} onClick={props.onPick}>
      <span class="slot">{props.option.selected ? <IndicatorSquare tone="accent" /> : null}</span>
      <span class="ellipsis">{props.option.label}</span>
    </div>
  );
}

/** A choice list over the player (Audio, Subtitles, Quality). BACK closes it. */
export function Menu(props: { title: string; options: MenuOption[]; onPick: (key: string) => void; onClose: () => void }) {
  const group = useFocusable<HTMLDivElement>({ focusKey: 'player-menu', isFocusBoundary: true });
  useKeyHandler((key) => {
    if (key !== 'back') return false;
    props.onClose();
    return true;
  });
  useEffect(() => {
    const selected = props.options.findIndex((o) => o.selected);
    setFocus('player-menu-' + String(Math.max(0, selected)));
  }, []);
  return (
    <div ref={group.ref} class="menu">
      <div class="menu-title mono-label">{tallyUppercase(props.title)}</div>
      <FocusGroup focusKey="player-menu">
        {props.options.map((o, i) => (
          <MenuRow key={o.key} option={o} focusKey={'player-menu-' + String(i)} onPick={() => props.onPick(o.key)} />
        ))}
      </FocusGroup>
    </div>
  );
}

/** Loads a WebVTT file and draws the cues for the engine's position. */
export function SubtitleLayer(props: { url: string | null; timeMs: number }) {
  const [cues, setCues] = useState<Cue[]>([]);
  useEffect(() => {
    setCues([]);
    if (props.url === null) return undefined;
    let alive = true;
    fetch(props.url)
      .then((r) => (r.ok ? r.text() : ''))
      .then((text) => {
        if (alive) setCues(parseVtt(text));
      })
      .catch(() => undefined);
    return () => {
      alive = false;
    };
  }, [props.url]);
  const now = activeCues(cues, props.timeMs);
  if (now.length === 0) return null;
  return (
    <div class="subtitle-layer">
      <span>{now.map((c) => c.text).join('\n')}</span>
    </div>
  );
}
