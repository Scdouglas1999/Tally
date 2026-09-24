import { useEffect, useRef, useState } from 'preact/hooks';
import { app } from '../../app/context';
import { Lamp, type LampState } from '../../kit/Lamp';
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
