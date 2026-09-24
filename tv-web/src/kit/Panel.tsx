import { useLayoutEffect, useRef } from 'preact/hooks';
import { FocusGroup, setFocus, useFocusable } from '../focus/focus';
import { useKeyHandler } from '../platform/keyRouter';
import { tallyUppercase } from '../util/format';
import { IndicatorSquare } from './Bits';
import { offsetWithin, reveal } from './scroll';
import './panel.css';

export interface PanelEntry {
  key: string;
  label: string;
  onPress: () => void;
  /** The current choice: the accent square at the right. */
  marked?: boolean;
  /** Delete and the like: the label in red. */
  destructive?: boolean;
  supporting?: string | null;
}

function Row(props: { entry: PanelEntry; focusKey: string; list: { current: HTMLDivElement | null } }) {
  const f = useFocusable<HTMLDivElement>({
    focusKey: props.focusKey,
    onEnter: props.entry.onPress,
    onFocus: () => {
      const list = props.list.current;
      const el = f.ref.current;
      if (list === null || el === null) return;
      // the slot around the row (focus room) is what is kept in view, so the whole frame shows
      const top = offsetWithin(el, list).top + list.scrollTop;
      list.scrollTop = reveal(list.scrollTop, list.clientHeight, top - 7, el.offsetHeight + 14, 0, 0, list.scrollHeight - list.clientHeight);
    },
  });
  const e = props.entry;
  return (
    <div class="panel-slot">
      <div ref={f.ref} class={'panel-row' + (e.destructive === true ? ' destructive' : '')} onClick={e.onPress}>
        <div class="texts">
          <div class="headline ellipsis">{e.label}</div>
          {e.supporting != null && e.supporting !== '' ? <div class="supporting clamp-2">{e.supporting}</div> : null}
        </div>
        {e.marked === true ? <IndicatorSquare tone="accent" /> : null}
      </div>
    </div>
  );
}

/**
 * A Tally panel over the whole screen (ui/settings/TallyDialogs.kt TallyListPanel): 60% scrim, a hairline frame
 * on ground, the accent kicker, a rule, optional body text, the rows, and the black key bar (BACK Close). Focus
 * stays inside; BACK closes. Fixed to the stage so it also covers the rail.
 */
export function Panel(props: {
  title: string;
  entries: PanelEntry[];
  onClose: () => void;
  focusKey: string;
  /** Text above the rows (a confirmation's question). */
  body?: string | null;
  initialIndex?: number;
}) {
  const list = useRef<HTMLDivElement>(null);
  const group = useFocusable<HTMLDivElement>({ focusKey: props.focusKey, isFocusBoundary: true, saveLastFocusedChild: false });
  useKeyHandler((key) => {
    if (key !== 'back') return false;
    props.onClose();
    return true;
  });
  // before the first paint (the rows registered in their own layout effects): an OK pressed the moment the panel
  // shows must reach its row, not the card it was opened on
  useLayoutEffect(() => {
    const start = Math.min(Math.max(0, props.initialIndex ?? 0), Math.max(0, props.entries.length - 1));
    setFocus(props.entries.length > 0 ? `${props.focusKey}-${start}` : props.focusKey);
  }, []);
  return (
    <div class="panel-window">
      <div ref={group.ref} class="panel">
        <div class="panel-kicker mono-label ellipsis">{tallyUppercase(props.title)}</div>
        <div class="panel-rule" />
        {props.body != null && props.body !== '' ? <div class="panel-body">{props.body}</div> : null}
        <div ref={list} class="panel-list">
          <FocusGroup focusKey={props.focusKey}>
            {props.entries.map((e, i) => (
              <Row key={e.key} entry={e} focusKey={`${props.focusKey}-${i}`} list={list} />
            ))}
          </FocusGroup>
        </div>
        <div class="panel-keys">
          <span class="key mono-label">BACK</span>
          <span class="hint">Close</span>
        </div>
      </div>
    </div>
  );
}
