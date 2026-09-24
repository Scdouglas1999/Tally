import { useEffect, useRef, useState } from 'preact/hooks';
import { FocusGroup, setFocus, useFocusable } from '../../focus/focus';
import { IndicatorSquare } from '../../kit/Bits';
import { reveal } from '../../kit/scroll';
import { useKeyHandler } from '../../platform/keyRouter';
import { tallyUppercase } from '../../util/format';
import { filterValues } from './libraryData';
import {
  FILTER_LABELS,
  clearFilter,
  clearFilters,
  countFilters,
  directionArrow,
  filterSummary,
  filterValueLabel,
  getFilter,
  isFilterValueOn,
  nextSort,
  setViewOption,
  sliderValues,
  sortName,
  supportsMultiple,
  toggleFilterValue,
  viewPrefs,
  type FilterKind,
  type FilterValue,
  type LibraryFilter,
  type SortAndDirection,
  type ViewOptions,
  type ViewPref,
} from './libraryModel';
import type { ItemSortBy } from '@jellyfin/sdk/lib/generated-client/models/item-sort-by';

/** One row of a panel: a label, the 8dp accent square when marked, a mono value at the right. */
export interface PanelRow {
  label: string;
  onPress: () => void;
  marked?: boolean;
  value?: string | null;
  valueAccent?: boolean;
}

/** Room a focused row's frame needs inside the scrolling list (focus 3dp + 1dp). */
const FOCUS_ROOM = 6;

function Row(props: { row: PanelRow; focusKey: string; onFocus: (el: HTMLElement) => void }) {
  const f = useFocusable<HTMLDivElement>({
    focusKey: props.focusKey,
    onEnter: props.row.onPress,
    onFocus: () => {
      if (f.ref.current !== null) props.onFocus(f.ref.current);
    },
  });
  const r = props.row;
  return (
    <div class="lib-panel-slot">
      <div ref={f.ref} class="lib-panel-row" onClick={r.onPress}>
        <span class="label ellipsis">{r.label}</span>
        {r.value != null ? <span class={'value mono-label ellipsis' + (r.valueAccent === true ? ' accent' : '')}>{tallyUppercase(r.value)}</span> : null}
        {r.marked === true ? <IndicatorSquare tone="accent" class="mark" /> : null}
      </div>
    </div>
  );
}

/**
 * The library's dialogs (the Quality dialog's panel family on Android): a centered panel over a 60% scrim, accent
 * kicker, hairline, a scrolling list of rows and a key bar. Focus starts on `initialIndex` and stays inside; BACK
 * calls `onBack` (`backLabel` names what it does). `level` names the panel's content: a new level starts on its own
 * row.
 */
export function LibraryPanel(props: {
  pageKey: string;
  kicker: string;
  rows: PanelRow[];
  initialIndex: number;
  onBack: () => void;
  backLabel: string;
  message?: string | null;
}) {
  const groupKey = props.pageKey + '-panel';
  const group = useFocusable<HTMLDivElement>({ focusKey: groupKey, isFocusBoundary: true, saveLastFocusedChild: false });
  const list = useRef<HTMLDivElement>(null);
  const level = props.kicker;
  // rows are keyed by their label: a row that stays (a value toggled on, a "clear" row appearing above it) keeps focus
  const keyFor = (i: number): string => `${groupKey}-${level}-${props.rows[i]?.label ?? String(i)}`;
  const messageKey = `${groupKey}-${level}-message`;
  const message = useFocusable<HTMLDivElement>({ focusKey: messageKey, focusable: props.message != null });
  useKeyHandler((key) => {
    if (key !== 'back') return false;
    props.onBack();
    return true;
  });
  // a new level (or the panel opening) focuses its row; rows arriving later (filter values) too
  const focusedLevel = useRef<string | null>(null);
  useEffect(() => {
    const ready = props.rows.length > 0 || props.message != null;
    const state = level + (props.rows.length > 0 ? '|rows' : '|message');
    if (!ready || focusedLevel.current === state) return;
    focusedLevel.current = state;
    if (list.current !== null) list.current.scrollTop = 0;
    if (props.rows.length === 0) setFocus(messageKey);
    else setFocus(keyFor(Math.max(0, Math.min(props.rows.length - 1, props.initialIndex))));
  });
  const onRowFocus = (el: HTMLElement): void => {
    const l = list.current;
    if (l === null) return;
    const slot = el.parentElement as HTMLElement;
    l.scrollTop = reveal(l.scrollTop, l.clientHeight, slot.offsetTop, slot.offsetHeight, 0, 0, l.scrollHeight - l.clientHeight);
  };
  return (
    <div class="lib-panel-scrim">
      <div ref={group.ref} class="lib-panel">
        <FocusGroup focusKey={groupKey}>
          <div class="kicker mono-label ellipsis">{tallyUppercase(props.kicker)}</div>
          <div class="rule" />
          {props.message != null ? (
            <div ref={message.ref} class="message mono-label">
              {tallyUppercase(props.message)}
            </div>
          ) : (
            <div ref={message.ref} />
          )}
          <div ref={list} class="list" style={{ padding: `0 ${FOCUS_ROOM}px` }}>
            {props.rows.map((row, i) => (
              <Row key={keyFor(i)} row={row} focusKey={keyFor(i)} onFocus={onRowFocus} />
            ))}
          </div>
          <div class="keybar">
            <span class="keycap mono-label">BACK</span>
            <span class="hint">{props.backLabel}</span>
          </div>
        </FocusGroup>
      </div>
    </div>
  );
}

/** Sort choices; the current one is marked with its direction. Choosing it again reverses the order. */
export function SortPanel(props: { pageKey: string; options: readonly ItemSortBy[]; current: SortAndDirection; onChange: (s: SortAndDirection) => void; onClose: () => void }) {
  const arrow = directionArrow(props.current.direction);
  const rows = props.options.map((o) => ({
    label: sortName(o),
    marked: o === props.current.sort,
    value: o === props.current.sort ? arrow : null,
    onPress: () => {
      props.onClose();
      props.onChange(nextSort(props.current, o));
    },
  }));
  return (
    <LibraryPanel
      pageKey={props.pageKey}
      kicker="Sort by"
      rows={rows}
      initialIndex={Math.max(0, props.options.indexOf(props.current.sort))}
      onBack={props.onClose}
      backLabel="Close"
    />
  );
}

/**
 * Filters, two levels: the filter kinds (marked when on, with what is on), then a kind's values as ON/OFF toggles.
 * Changes apply at once. A single-choice kind (played, favorites) goes back after a choice.
 */
export function FilterPanel(props: {
  pageKey: string;
  kinds: readonly FilterKind[];
  current: LibraryFilter;
  parentId: string;
  onChange: (f: LibraryFilter) => void;
  onClose: () => void;
}) {
  const [open, setOpen] = useState<FilterKind | null>(null);
  const [lastOpened, setLastOpened] = useState<FilterKind | null>(null);
  const [values, setValues] = useState<FilterValue[] | null>(null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    if (open === null) return undefined;
    let live = true;
    setValues(null);
    setFailed(false);
    filterValues(open, props.parentId)
      .then((v) => live && setValues(v))
      .catch(() => {
        if (live) {
          setFailed(true);
          setValues([]);
        }
      });
    return () => {
      live = false;
    };
  }, [open]);

  if (open === null) {
    const count = countFilters(props.current, props.kinds);
    const rows: PanelRow[] = [];
    if (count > 0) {
      rows.push({
        label: 'Clear all filters',
        onPress: () => {
          props.onChange(clearFilters(props.current, props.kinds));
          props.onClose();
        },
      });
    }
    for (const kind of props.kinds) {
      const on = getFilter(kind, props.current) !== undefined;
      rows.push({
        label: FILTER_LABELS[kind],
        marked: on,
        value: on ? filterSummary(kind, props.current) : null,
        onPress: () => {
          setLastOpened(kind);
          setOpen(kind);
        },
      });
    }
    const offset = count > 0 ? 1 : 0;
    const last = lastOpened !== null ? props.kinds.indexOf(lastOpened) : -1;
    return <LibraryPanel pageKey={props.pageKey} kicker="Filter" rows={rows} initialIndex={Math.max(0, last) + offset} onBack={props.onClose} backLabel="Close" />;
  }

  const kind = open;
  const on = getFilter(kind, props.current) !== undefined;
  const rows: PanelRow[] = [];
  if (values !== null && on) {
    rows.push({
      label: 'Clear this filter',
      onPress: () => {
        props.onChange(clearFilter(kind, props.current));
        setOpen(null);
      },
    });
  }
  for (const v of values ?? []) {
    const selected = isFilterValueOn(kind, props.current, v);
    rows.push({
      label: filterValueLabel(kind, v),
      value: selected ? 'On' : 'Off',
      valueAccent: selected,
      onPress: () => {
        props.onChange(toggleFilterValue(kind, props.current, v));
        if (!supportsMultiple(kind)) setOpen(null);
      },
    });
  }
  const firstOn = (values ?? []).findIndex((v) => isFilterValueOn(kind, props.current, v));
  const clearOffset = values !== null && on ? 1 : 0;
  return (
    <LibraryPanel
      pageKey={props.pageKey}
      kicker={`Filter · ${FILTER_LABELS[kind]}`}
      rows={rows}
      initialIndex={firstOn >= 0 ? firstOn + clearOffset : 0}
      onBack={() => setOpen(null)}
      backLabel="Back"
      message={values === null ? 'Loading…' : values.length === 0 ? (failed ? 'Could not load these' : 'Nothing to filter by') : null}
    />
  );
}

function choiceValues(p: ViewPref): Array<[string | number, string]> {
  if (p.kind === 'choice') return p.values.map(([v, name]) => [v, name]);
  if (p.kind === 'slider') return sliderValues(p).map((v) => [v, String(v)]);
  return [];
}

/**
 * View options, two levels: every option of the current layout (choices show their value, switches ON/OFF, Reset),
 * then a choice's values with the current one marked. Changes apply at once.
 */
export function ViewPanel(props: { pageKey: string; current: ViewOptions; defaults: ViewOptions; onChange: (v: ViewOptions) => void; onClose: () => void }) {
  const [open, setOpen] = useState<ViewPref | null>(null);
  const [lastOpened, setLastOpened] = useState<string | null>(null);
  const v = props.current;
  const prefs = viewPrefs(v);
  if (open === null) {
    const rows: PanelRow[] = prefs.map((p) => {
      switch (p.kind) {
        case 'switch':
          return { label: p.title, value: v[p.key] ? 'On' : 'Off', valueAccent: v[p.key], onPress: () => props.onChange(setViewOption(v, p.key, !v[p.key])) };
        case 'choice':
          return {
            label: p.title,
            value: p.values.find(([value]) => value === v[p.key])?.[1] ?? null,
            onPress: () => {
              setLastOpened(p.title);
              setOpen(p);
            },
          };
        case 'slider':
          return {
            label: p.title,
            value: String(v[p.key]),
            onPress: () => {
              setLastOpened(p.title);
              setOpen(p);
            },
          };
        case 'reset':
          return { label: p.title, onPress: () => props.onChange(props.defaults) };
      }
    });
    const last = prefs.findIndex((p) => p.title === lastOpened);
    return <LibraryPanel pageKey={props.pageKey} kicker="View" rows={rows} initialIndex={Math.max(0, last)} onBack={props.onClose} backLabel="Close" />;
  }
  const pref = open;
  const current = pref.kind === 'choice' || pref.kind === 'slider' ? v[pref.key] : null;
  const choices = choiceValues(pref);
  const currentIndex = choices.findIndex(([value]) => value === current);
  const rows: PanelRow[] = choices.map(([value, name]) => ({
    label: name,
    marked: value === current,
    onPress: () => {
      if (pref.kind === 'choice' || pref.kind === 'slider') props.onChange(setViewOption(v, pref.key, value));
      setOpen(null);
    },
  }));
  return (
    <LibraryPanel
      pageKey={props.pageKey}
      kicker={`View · ${pref.title}`}
      rows={rows}
      initialIndex={Math.max(0, currentIndex)}
      onBack={() => setOpen(null)}
      backLabel="Back"
    />
  );
}
