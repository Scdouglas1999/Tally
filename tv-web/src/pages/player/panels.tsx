/**
 * The player's panels. The settings side panel (TallyPlayerSettings.kt: a 360dp column at the right edge over a
 * horizontal scrim, one page at a time: the list, then Audio, Subtitles, Speed, Video scale or Subtitle delay in
 * the same panel), and the centered dialogs of the same family as the Android ones: Quality (QualityDialog.kt),
 * Sleep timer (SleepTimerUi.kt), and a notice for features this app does not have yet. Focus stays inside; BACK goes
 * up one page, then closes.
 */
import type { ComponentChildren } from 'preact';
import { useLayoutEffect, useRef } from 'preact/hooks';
import { FocusGroup, setFocus, useFocusable } from '../../focus/focus';
import { IndicatorSquare } from '../../kit/Bits';
import { offsetWithin, reveal } from '../../kit/scroll';
import { useKeyHandler } from '../../platform/keyRouter';
import { tallyUppercase } from '../../util/format';

export interface PanelRowModel {
  key: string;
  label: string;
  /** Mono value at the right (`OFF`, `1×`, `EN · AAC STEREO`). */
  value?: string | null;
  /** The current choice: an accent square at the right. */
  current?: boolean;
  /** Null: shown but cannot be chosen (grayed and skipped, as upstream's disabled entries). */
  onPress: (() => void) | null;
}

/**
 * TallyRow: full-width row, hairline frame; focused: raised fill and the amber frame. Trailing: the value then the
 * current square (settings), or with `tagged` the square then the value or NOW (the quality dialog's rows).
 */
function PanelRow(props: { row: PanelRowModel; focusKey: string; onFocus: (el: HTMLElement) => void; tagged: boolean }) {
  const { row } = props;
  const f = useFocusable<HTMLDivElement>({
    focusKey: props.focusKey,
    focusable: row.onPress !== null,
    onEnter: () => row.onPress?.(),
    onFocus: () => {
      if (f.ref.current !== null) props.onFocus(f.ref.current);
    },
  });
  const value = row.value ?? (props.tagged && row.current === true ? 'NOW' : null);
  const square = row.current === true ? <IndicatorSquare tone="accent" /> : null;
  const text = value !== null ? <span class="value mono-label ellipsis">{tallyUppercase(value)}</span> : null;
  return (
    <div ref={f.ref} class={'pc-prow' + (row.onPress === null ? ' disabled' : '')} onClick={() => row.onPress?.()}>
      <span class="label ellipsis">{row.label}</span>
      {value !== null || square !== null ? (
        <span class={'trail' + (props.tagged ? ' tagged' : '')}>
          {props.tagged ? square : text}
          {props.tagged ? text : square}
        </span>
      ) : null}
    </div>
  );
}

/** A scrolling column of rows: the focused row is kept in view whole (its frame included). */
function RowList(props: { rows: PanelRowModel[]; prefix: string; focusIndex: number; class: string; nowTags?: boolean }) {
  const scroller = useRef<HTMLDivElement>(null);
  // a layout effect: the rows registered in theirs (children first), and a quick key press lands on the new row
  useLayoutEffect(() => {
    const first = props.rows.findIndex((r) => r.onPress !== null);
    const target = props.rows[props.focusIndex]?.onPress != null ? props.focusIndex : first;
    if (target >= 0) setFocus(props.prefix + String(target));
  }, [props.prefix]);
  const onFocus = (el: HTMLElement): void => {
    const s = scroller.current;
    if (s === null) return;
    const top = offsetWithin(el, s).top + s.scrollTop;
    s.scrollTop = reveal(s.scrollTop, s.clientHeight, top, el.offsetHeight, 8, 8, s.scrollHeight - s.clientHeight);
  };
  return (
    <div ref={scroller} class={'pc-rows ' + props.class}>
      {props.rows.map((r, i) => (
        <PanelRow
          key={props.prefix + String(i)}
          row={r}
          focusKey={props.prefix + String(i)}
          onFocus={onFocus}
          tagged={props.nowTags === true}
        />
      ))}
    </div>
  );
}

function useBackAndTrap(onBack: () => void): void {
  useKeyHandler((key) => {
    if (key === 'back') {
      onBack();
      return true;
    }
    return false;
  });
}

/**
 * The settings side panel. `pageKey` names the page (focus keys and the restore of the list's row are per page);
 * `readout` is the value shown at the header's right (the subtitle delay).
 */
export function SidePanel(props: { pageKey: string; kicker: string; readout?: string | null; rows: PanelRowModel[]; focusIndex: number; onBack: () => void }) {
  const group = useFocusable<HTMLDivElement>({ focusKey: 'pc-panel', isFocusBoundary: true });
  useBackAndTrap(props.onBack);
  return (
    <div class="pc-sidepanel-scrim">
      <div ref={group.ref} class="pc-sidepanel">
        <div class="head">
          <span class="kicker mono-label">{tallyUppercase(props.kicker)}</span>
          {props.readout != null ? <span class="readout mono-label">{tallyUppercase(props.readout)}</span> : null}
        </div>
        <FocusGroup focusKey="pc-panel">
          <RowList key={props.pageKey} rows={props.rows} prefix={`pc-panel-${props.pageKey}-`} focusIndex={props.focusIndex} class="side" />
        </FocusGroup>
      </div>
    </div>
  );
}

/** BACK Close, on the black key bar at the foot of a dialog. */
function KeyBar() {
  return (
    <div class="pc-keybar">
      <span class="keycap mono-label">BACK</span>
      <span class="keylabel">Close</span>
    </div>
  );
}

/** A centered dialog over a dimmed picture, focus trapped, BACK closes. */
function CenterDialog(props: { width: number; dim: number; onBack: () => void; children: ComponentChildren; focusKey: string }) {
  const group = useFocusable<HTMLDivElement>({ focusKey: props.focusKey, isFocusBoundary: true });
  useBackAndTrap(props.onBack);
  return (
    <div class="pc-dim" style={{ background: `rgba(0, 0, 0, ${props.dim})` }}>
      <div ref={group.ref} class="pc-dialog" style={{ width: `${props.width}px` }}>
        <FocusGroup focusKey={props.focusKey}>{props.children}</FocusGroup>
        <KeyBar />
      </div>
    </div>
  );
}

/**
 * QUALITY: what is playing now (`TRANSCODING · 720p · 0.3 Mbps` and why), the ladder with the current rung marked
 * NOW, and "Use as my default on this TV".
 */
export function QualityDialog(props: {
  nowLine: string;
  reasons: string;
  rows: PanelRowModel[];
  saveAsDefault: boolean;
  onToggleDefault: () => void;
  onBack: () => void;
}) {
  const selected = Math.max(0, props.rows.findIndex((r) => r.current === true));
  const rows = props.rows.concat({
    key: 'default',
    label: 'Use as my default on this TV',
    value: props.saveAsDefault ? 'ON' : 'OFF',
    current: props.saveAsDefault,
    onPress: props.onToggleDefault,
  });
  return (
    <CenterDialog width={1024} dim={0.6} onBack={props.onBack} focusKey="pc-quality">
      <div class="pc-dhead quality">
        <div class="kicker mono-label">QUALITY</div>
        <div class="now mono-label">{props.nowLine}</div>
        {props.reasons !== '' ? <div class="reasons">{props.reasons}</div> : null}
        <div class="rule" />
      </div>
      <RowList rows={rows} prefix="pc-quality-" focusIndex={selected} class="quality" nowTags />
    </CenterDialog>
  );
}

/** Sleep timer: a running timer's Cancel first, then 15 to 90 minutes and "When this ends". */
export function SleepDialog(props: { rows: PanelRowModel[]; onBack: () => void }) {
  return (
    <CenterDialog width={960} dim={0.62} onBack={props.onBack} focusKey="pc-sleepdlg">
      <div class="pc-dhead sleep">
        <div class="title">Sleep timer</div>
        <div class="rule" />
      </div>
      <RowList rows={props.rows} prefix="pc-sleep-" focusIndex={0} class="sleep" />
    </CenterDialog>
  );
}

/** A feature the Android app has and this one does not yet: says so plainly, BACK or OK closes. */
export function NoticeDialog(props: { title: string; text: string; onBack: () => void }) {
  const rows: PanelRowModel[] = [{ key: 'ok', label: 'OK', onPress: props.onBack }];
  return (
    <CenterDialog width={960} dim={0.62} onBack={props.onBack} focusKey="pc-notice">
      <div class="pc-dhead sleep">
        <div class="title">{props.title}</div>
        <div class="rule" />
        <div class="text">{props.text}</div>
      </div>
      <RowList rows={rows} prefix="pc-notice-" focusIndex={0} class="sleep" />
    </CenterDialog>
  );
}
