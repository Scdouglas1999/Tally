import type { ComponentChildren } from 'preact';
import { useEffect, useImperativeHandle, useRef, useState } from 'preact/hooks';
import type { Ref } from 'preact';
import { FocusGroup, setFocus, useFocusable } from '../../focus/focus';
import { reveal } from '../../kit/scroll';

/** Rows kept rendered above and below the visible ones, so the D-pad always has a next row to move to. */
const BUFFER_ROWS = 2;

export interface GridGeometry {
  count: number;
  columns: number;
  cellWidth: number;
  cellHeight: number;
  /** Space between cells, both ways. */
  gap: number;
  /** Room above the first row (the fade under the header strip lives in it). */
  topPad: number;
  bottomPad: number;
  /** Room on the left and right of the cells (the focus frame's room). */
  sidePad: number;
}

export interface GridHandle {
  /** Scrolls `index` to the top of the grid and focuses it (letter jump, back to the top, paging). */
  jumpTo(index: number): void;
  focusedIndex(): number;
}

/** Visible row range for a scroll position (inclusive), with the buffer. Exported for the tests. */
export function visibleRows(scrollTop: number, viewHeight: number, g: Pick<GridGeometry, 'count' | 'columns' | 'cellHeight' | 'gap' | 'topPad'>): [number, number] {
  const rows = Math.ceil(g.count / Math.max(1, g.columns));
  const pitch = g.cellHeight + g.gap;
  const first = Math.floor((scrollTop - g.topPad) / pitch);
  const last = Math.floor((scrollTop + viewHeight - g.topPad) / pitch);
  return [Math.max(0, first - BUFFER_ROWS), Math.min(rows - 1, last + BUFFER_ROWS)];
}

/**
 * A focus-safe vertical grid that renders only the rows near the view (a library can hold thousands of items; a
 * TV draws a few dozen cards quickly). Cells sit at absolute positions in a full-height track moved by scrollTop,
 * so the focus system measures them where they are. Focus re-entering the grid returns to the last focused cell.
 * The focused cell is revealed with `topPad` above it (it never sits under the header's fade).
 */
export function VirtualGrid(props: {
  geometry: GridGeometry;
  focusKeyFor: (index: number) => string;
  groupKey: string;
  initialIndex?: number;
  /** Draws cell `index` (its focus key and onFocus must be used by the focusable card). */
  cell: (index: number, focusKey: string, onFocus: () => void, width: number, height: number) => ComponentChildren;
  onFocusIndex?: (index: number) => void;
  /** The rows on screen changed: load their items. */
  onRange?: (fromIndex: number, toIndex: number) => void;
  handle?: Ref<GridHandle>;
  class?: string;
}) {
  const g = props.geometry;
  const scroller = useRef<HTMLDivElement>(null);
  const [scrollTop, setScrollTop] = useState(0);
  const [viewHeight, setViewHeight] = useState(900);
  const [focused, setFocused] = useState(Math.min(props.initialIndex ?? 0, Math.max(0, g.count - 1)));
  const focusedRef = useRef(focused);
  focusedRef.current = focused;
  const pendingFocus = useRef<number | null>(null);
  const group = useFocusable<HTMLDivElement>({
    focusKey: props.groupKey,
    saveLastFocusedChild: false,
    preferredChildFocusKey: g.count > 0 ? props.focusKeyFor(Math.min(focused, g.count - 1)) : undefined,
  });

  const pitch = g.cellHeight + g.gap;
  const rows = Math.ceil(g.count / Math.max(1, g.columns));
  const trackHeight = g.topPad + Math.max(0, rows * pitch - g.gap) + g.bottomPad;
  const rowTop = (row: number): number => g.topPad + row * pitch;

  const setScroll = (top: number): void => {
    const el = scroller.current;
    if (el === null) return;
    const max = Math.max(0, trackHeight - el.clientHeight);
    const next = Math.max(0, Math.min(max, top));
    el.scrollTop = next;
    setScrollTop(next);
  };

  useEffect(() => {
    const el = scroller.current;
    if (el !== null && el.clientHeight > 0) setViewHeight(el.clientHeight);
  });

  const [firstRow, lastRow] = visibleRows(scrollTop, viewHeight, g);
  const fromIndex = firstRow * g.columns;
  const toIndex = Math.min(g.count - 1, (lastRow + 1) * g.columns - 1);

  useEffect(() => {
    if (g.count > 0) props.onRange?.(fromIndex, toIndex);
  }, [fromIndex, toIndex, g.count]);

  // a jump lands after the new rows are drawn
  useEffect(() => {
    const target = pendingFocus.current;
    if (target === null) return;
    pendingFocus.current = null;
    setFocus(props.focusKeyFor(target));
  });

  // the list got shorter (a filter): keep the remembered cell inside it
  useEffect(() => {
    if (g.count > 0 && focused > g.count - 1) setFocused(g.count - 1);
  }, [g.count]);

  const onCellFocus = (index: number): void => {
    if (index !== focusedRef.current) {
      focusedRef.current = index;
      setFocused(index);
    }
    props.onFocusIndex?.(index);
    const el = scroller.current;
    if (el === null) return;
    const top = rowTop(Math.floor(index / g.columns));
    const max = Math.max(0, trackHeight - el.clientHeight);
    // the row with the header gap above it, or the least movement that shows it whole with room for the frame
    const next = reveal(el.scrollTop, el.clientHeight, top, g.cellHeight, g.topPad, g.sidePad, max);
    if (next !== el.scrollTop) setScroll(next);
  };

  useImperativeHandle(props.handle as Ref<GridHandle>, () => ({
    jumpTo(index: number) {
      if (g.count === 0) return;
      const i = Math.max(0, Math.min(g.count - 1, index));
      focusedRef.current = i;
      setFocused(i);
      setScroll(rowTop(Math.floor(i / g.columns)) - g.topPad);
      pendingFocus.current = i;
    },
    focusedIndex: () => focusedRef.current,
  }));

  const cells: ComponentChildren[] = [];
  for (let i = Math.max(0, fromIndex); i <= toIndex; i++) {
    const row = Math.floor(i / g.columns);
    const col = i % g.columns;
    const key = props.focusKeyFor(i);
    cells.push(
      <div key={key} class="vgrid-cell" style={{ left: `${g.sidePad + col * (g.cellWidth + g.gap)}px`, top: `${rowTop(row)}px`, width: `${g.cellWidth}px` }}>
        {props.cell(i, key, () => onCellFocus(i), g.cellWidth, g.cellHeight)}
      </div>,
    );
  }

  return (
    <div class={'vgrid' + (props.class !== undefined ? ' ' + props.class : '')}>
      <div ref={(el) => {
        scroller.current = el;
        group.ref.current = el;
      }} class="vgrid-scroller">
        <div class="vgrid-track" style={{ height: `${trackHeight}px` }}>
          <FocusGroup focusKey={props.groupKey}>{cells}</FocusGroup>
        </div>
      </div>
      {/* rows scrolled up fade out under the header strip instead of being cut */}
      {scrollTop > 0 ? <div class="vgrid-fade" style={{ height: `${g.topPad}px` }} /> : null}
    </div>
  );
}
