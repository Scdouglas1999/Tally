import { useEffect, useLayoutEffect, useRef, useState } from 'preact/hooks';
import { ROLL_MS, SCORE_HIGHLIGHT_HOLD_MS, easeOut, rollIncomingOffset, rollOutgoingOffset, rollRestart, scoreWentUp } from './scoreRoll';

interface CellState {
  current: string;
  previous: string;
  /** Where the leaving glyph started, in cell heights. */
  from: number;
  /** Eased progress 0..1 of the running roll (1 = at rest). */
  progress: number;
  startedAt: number;
  raf: number;
}

/**
 * One character cell. Writes transforms from requestAnimationFrame while it rolls (no Preact renders per frame,
 * as the lamp does); a change that arrives mid-roll restarts the cell from where it is (rollRestart).
 */
function RollCell(props: { char: string; rollIn: boolean }) {
  const outEl = useRef<HTMLSpanElement>(null);
  const inEl = useRef<HTMLSpanElement>(null);
  const s = useRef<CellState>({
    current: props.char,
    previous: props.rollIn ? ' ' : props.char,
    from: 0,
    progress: props.rollIn ? 0 : 1,
    startedAt: 0,
    raf: 0,
  });

  const paint = (): void => {
    const st = s.current;
    const o = outEl.current;
    const i = inEl.current;
    if (o === null || i === null) return;
    const showOut = st.progress < 1 && st.previous !== ' ';
    o.style.visibility = showOut ? 'visible' : 'hidden';
    o.textContent = st.previous;
    o.style.transform = `translateY(${(rollOutgoingOffset(st.from, st.progress) * 100).toFixed(2)}%)`;
    i.textContent = st.current;
    i.style.transform = st.progress >= 1 ? '' : `translateY(${(rollIncomingOffset(st.progress) * 100).toFixed(2)}%)`;
  };

  const run = (): void => {
    const st = s.current;
    window.cancelAnimationFrame(st.raf);
    const frame = (): void => {
      const t = (Date.now() - st.startedAt) / ROLL_MS;
      st.progress = t >= 1 ? 1 : easeOut(t);
      paint();
      if (st.progress < 1) st.raf = window.requestAnimationFrame(frame);
    };
    st.raf = window.requestAnimationFrame(frame);
  };

  useLayoutEffect(() => {
    paint();
    if (props.rollIn) {
      s.current.startedAt = Date.now();
      run();
    }
    return () => window.cancelAnimationFrame(s.current.raf);
  }, []);

  useLayoutEffect(() => {
    const st = s.current;
    if (props.char === st.current) return;
    const restart = rollRestart(st.from, st.progress);
    if (!restart.keepPrevious) {
      st.previous = st.current;
      st.from = restart.from;
      st.progress = 0;
      st.startedAt = Date.now();
      run();
    }
    st.current = props.char;
    paint();
  }, [props.char]);

  return (
    <span class="roll-cell">
      <span ref={outEl} class="roll-out" />
      <span ref={inEl} class="roll-in" />
    </span>
  );
}

/**
 * Scoreboard digits (monospaced fonts only, so a cell never changes width): every character that changes rolls in
 * its own clipped cell. Cells are keyed from the right, so "9" → "10" keeps the ones cell and the new tens cell
 * rolls in. Nothing animates on the first render.
 */
export function RollingText(props: { text: string; class?: string }) {
  const composed = useRef(false);
  useEffect(() => {
    composed.current = true;
  }, []);
  const n = props.text.length;
  const cells = [];
  for (let i = 0; i < n; i++) {
    cells.push(<RollCell key={n - 1 - i} char={props.text.charAt(i)} rollIn={composed.current} />);
  }
  return <span class={'rolling' + (props.class !== undefined ? ' ' + props.class : '')}>{cells}</span>;
}

/**
 * True for SCORE_HIGHLIGHT_HOLD_MS after `score` went up while on screen (the page draws it in the accent color, and
 * the `.score-hot` class eases back over SCORE_HIGHLIGHT_FADE_MS when it goes).
 */
export function useScoreHighlight(score: number | null): boolean {
  const last = useRef(score);
  const [hot, setHot] = useState(false);
  useEffect(() => {
    const wentUp = scoreWentUp(last.current, score);
    last.current = score;
    if (!wentUp) return undefined;
    setHot(true);
    const t = window.setTimeout(() => setHot(false), SCORE_HIGHLIGHT_HOLD_MS);
    return () => window.clearTimeout(t);
  }, [score]);
  return hot;
}

function LiveScore(props: { score: number | null; class?: string; placeholder: string }) {
  const hot = useScoreHighlight(props.score);
  return (
    <RollingText
      text={props.score === null ? props.placeholder : String(props.score)}
      class={'score-digits' + (hot ? ' score-hot' : '') + (props.class !== undefined ? ' ' + props.class : '')}
    />
  );
}

/**
 * A team's score as scoreboard digits: a score that goes up rolls and shows in the accent color for a moment.
 * `hidden` (spoiler mode) draws a plain dash that never rolls. Keyed by game, so a panel that switches to another
 * game does not roll.
 */
export function ScoreDigits(props: { gameId: string; score: number | null; hidden: boolean; class?: string; placeholder?: string }) {
  if (props.hidden) return <span class={'score-digits' + (props.class !== undefined ? ' ' + props.class : '')}>–</span>;
  return <LiveScore key={props.gameId} score={props.score} class={props.class} placeholder={props.placeholder ?? '0'} />;
}
