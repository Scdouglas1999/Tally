import { createContext, type ComponentChildren } from 'preact';
import { useContext, useRef } from 'preact/hooks';
import { FocusGroup, useFocusable } from '../focus/focus';
import { RowHeader } from './Bits';
import { usePageScroll } from './ScrollPage';
import { offsetWithin, reveal } from './scroll';

/** Room kept beside a focused card inside the scroller: the page margin on both sides. */
const MARGIN_X = 77;

const RowFocus = createContext<(card: HTMLElement) => void>(() => undefined);

/** For cards inside a MediaRow: call with the card element when it gains focus. */
export function useRowReveal(): (card: HTMLElement) => void {
  return useContext(RowFocus);
}

/**
 * A titled row of cards that scrolls sideways (tally/UI.md MediaRow). Re-entering the row returns to the card
 * focused last. The row brings itself into view in the page when focus enters it.
 */
export function MediaRow(props: {
  title: string;
  count?: number | null;
  focusKey?: string;
  children?: ComponentChildren;
  /** Shown instead of cards (LOADING…, an error), at `height` so nothing jumps when cards arrive. */
  message?: string | null;
  height?: number;
}) {
  const page = usePageScroll();
  const rowEl = useRef<HTMLDivElement>(null);
  const group = useFocusable<HTMLDivElement>({ focusKey: props.focusKey, saveLastFocusedChild: true });

  const onCardFocus = (card: HTMLElement): void => {
    const s = group.ref.current;
    if (s !== null) {
      const absolute = offsetWithin(card, s).left + s.scrollLeft;
      s.scrollLeft = reveal(s.scrollLeft, s.clientWidth, absolute, card.offsetWidth, MARGIN_X, MARGIN_X, s.scrollWidth - s.clientWidth);
    }
    if (rowEl.current !== null) page.reveal(rowEl.current);
  };

  return (
    <div ref={rowEl} class="media-row">
      <RowHeader title={props.title} count={props.count} />
      {/* the scroller is always there (the focus group registers once), with a message in place of cards */}
      <div ref={group.ref} class="row-scroller">
        {props.message != null ? (
          <div class="row-message mono-label" style={{ height: props.height !== undefined ? `${props.height}px` : undefined }}>
            {props.message}
          </div>
        ) : (
          <FocusGroup focusKey={group.focusKey}>
            <RowFocus.Provider value={onCardFocus}>
              <div class="row-track">{props.children}</div>
            </RowFocus.Provider>
          </FocusGroup>
        )}
      </div>
    </div>
  );
}
