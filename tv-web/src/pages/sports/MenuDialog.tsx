import { useEffect, useRef } from 'preact/hooks';
import { FocusGroup, setFocus, useFocusable } from '../../focus/focus';
import { IndicatorSquare } from '../../kit/Bits';
import { offsetWithin, reveal } from '../../kit/scroll';
import { useKeyHandler } from '../../platform/keyRouter';
import { KeyHint, TallyRow } from '../../sports/SportsBits';

/**
 * One line of a TV menu (DvrMenuLine): a row (`label` with `onPress`; `marked` carries the amber square; a disabled
 * row shows its `description` under the label) and, above it, an optional mono `info` line (a job's state, the
 * storage estimate), red when `infoLive`. A line without `onPress` is the info line alone. `dismiss` closes the menu
 * after the row runs.
 */
export interface MenuLine {
  /** Stable identity (rows keep focus when lines appear or change around them). */
  id: string;
  label?: string;
  info?: string | null;
  infoLive?: boolean;
  enabled?: boolean;
  description?: string | null;
  marked?: boolean;
  dismiss?: boolean;
  onPress?: () => void;
}

/** The long OK that opened the menu ends with a key-up; clicks are ignored until the hold is surely over. */
const CLICK_ARM_MS = 400;

function MenuLineView(props: { line: MenuLine; focusKey: string; armed: () => boolean; onDismiss: () => void; onRowFocus: (el: HTMLElement) => void; first: boolean; last: boolean }) {
  const { line } = props;
  const enabled = line.enabled !== false;
  const press = line.onPress;
  const wrap = useRef<HTMLDivElement>(null);
  return (
    <div ref={wrap} class="menu-line">
      {line.info != null && line.info !== '' ? (
        <div class={'menu-info mono-label' + (line.infoLive === true ? ' live' : '')}>
          {line.infoLive === true ? <IndicatorSquare tone="live" /> : null}
          <span class="clamp-2">{line.info}</span>
        </div>
      ) : null}
      {line.label !== undefined && press !== undefined ? (
        <TallyRow
          focusKey={props.focusKey}
          label={line.label}
          enabled={enabled}
          description={line.description ?? null}
          onFocus={() => {
            if (wrap.current !== null) props.onRowFocus(wrap.current);
          }}
          onArrow={(direction) => {
            // focus stays in the column: nothing to the sides, nothing above the first row or below the last
            if (direction === 'left' || direction === 'right') return false;
            if (direction === 'up' && props.first) return false;
            if (direction === 'down' && props.last) return false;
            return true;
          }}
          onPress={() => {
            if (!props.armed()) return;
            if (line.dismiss === true) {
              // close first (focus goes back to what the menu was opened on), then act: an action that opens another
              // page leaves this one with its focus where the viewer will expect it on BACK
              props.onDismiss();
              window.setTimeout(press, 30);
            } else {
              press();
            }
          }}
        >
          {line.marked === true ? <IndicatorSquare tone="accent" class="marked" /> : null}
          {enabled ? <KeyHint keyName="OK" label="" /> : null}
        </TallyRow>
      ) : null}
    </div>
  );
}

/**
 * A Tally TV menu (GameActionsDialog / DvrTvMenuDialog): a centered panel over a 60% black scrim, a kicker and title
 * over a column of rows. Focus starts on the first row and stays inside; BACK closes it. Scrolls when taller than
 * the screen.
 */
export function MenuDialog(props: {
  focusKey: string;
  kicker?: string | null;
  kickerLive?: boolean;
  title: string;
  subtitle?: string | null;
  lines: MenuLine[];
  onDismiss: () => void;
}) {
  const group = useFocusable<HTMLDivElement>({ focusKey: props.focusKey, isFocusBoundary: true, saveLastFocusedChild: true });
  const scroller = useRef<HTMLDivElement>(null);
  const openedAt = useRef(Date.now());
  useKeyHandler((key) => {
    if (key !== 'back') return false;
    props.onDismiss();
    return true;
  });
  const rows = props.lines.map((l, i) => ({ l, i })).filter((x) => x.l.label !== undefined && x.l.onPress !== undefined && x.l.enabled !== false);
  const firstRow = rows[0]?.l.id ?? null;
  const lastRow = rows[rows.length - 1]?.l.id ?? null;
  useEffect(() => {
    if (firstRow !== null) setFocus(`${props.focusKey}-${firstRow}`);
    else setFocus(props.focusKey);
  }, []);
  const onRowFocus = (el: HTMLElement): void => {
    const s = scroller.current;
    if (s === null) return;
    const top = offsetWithin(el, s).top + s.scrollTop;
    s.scrollTop = reveal(s.scrollTop, s.clientHeight, top, el.offsetHeight, 8, 8, s.scrollHeight - s.clientHeight);
  };
  return (
    <div class="menu-scrim">
      <div ref={group.ref} class="menu-panel">
        <div class="menu-head">
          {props.kicker != null && props.kicker !== '' ? (
            <div class={'menu-kicker mono-label ellipsis' + (props.kickerLive === true ? ' live' : '')}>{props.kicker}</div>
          ) : null}
          <div class="menu-title clamp-2">{props.title}</div>
          {props.subtitle != null && props.subtitle !== '' ? <div class="menu-subtitle mono-label">{props.subtitle}</div> : null}
        </div>
        <div ref={scroller} class="menu-rows">
          <FocusGroup focusKey={props.focusKey}>
            {props.lines.map((line) => (
              <MenuLineView
                key={line.id}
                line={line}
                focusKey={`${props.focusKey}-${line.id}`}
                armed={() => Date.now() - openedAt.current >= CLICK_ARM_MS}
                onDismiss={props.onDismiss}
                onRowFocus={onRowFocus}
                first={line.id === firstRow}
                last={line.id === lastRow}
              />
            ))}
          </FocusGroup>
        </div>
      </div>
    </div>
  );
}
