import { createContext, type ComponentChildren } from 'preact';
import { useContext, useMemo, useRef } from 'preact/hooks';
import { offsetWithin, reveal } from './scroll';

interface PageScroll {
  /**
   * Brings `el` (a row, a section) into view. 'start' (Home's rows, as on Android): the focused row moves to the
   * top of the area, so no half-cut row sits above it. 'nearest': the least movement that shows it whole.
   */
  reveal(el: HTMLElement, mode?: 'start' | 'nearest'): void;
  /** Scrolls to the very top (a detail page's header when its buttons take focus). */
  toTop(): void;
}

const Ctx = createContext<PageScroll>({ reveal: () => undefined, toTop: () => undefined });

export function usePageScroll(): PageScroll {
  return useContext(Ctx);
}

/**
 * A vertically scrolling page area (overflow hidden, moved by scrollTop). Rows inside call usePageScroll().reveal
 * when focus enters them.
 */
export function ScrollPage(props: {
  children: ComponentChildren;
  class?: string;
  topInset?: number;
  /** How a reveal without a mode moves the page (MediaRow's): 'start' (Home, the default) or 'nearest' (detail pages). */
  revealMode?: 'start' | 'nearest';
  /** Room kept above / below a section revealed with 'nearest' (default 40 px each). */
  nearestBefore?: number;
  nearestAfter?: number;
  /** Called with the new scrollTop whenever a reveal moves the page. */
  onScrollChange?: (scrollTop: number) => void;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const latest = useRef(props);
  latest.current = props;
  const api = useMemo<PageScroll>(() => {
    const set = (page: HTMLElement, top: number): void => {
      if (page.scrollTop === top) return;
      page.scrollTop = top;
      latest.current.onScrollChange?.(page.scrollTop);
    };
    return {
      reveal(el, mode) {
        const page = ref.current;
        if (page === null) return;
        const how = mode ?? latest.current.revealMode ?? 'start';
        const absoluteTop = offsetWithin(el, page).top + page.scrollTop;
        const max = page.scrollHeight - page.clientHeight;
        set(
          page,
          how === 'start'
            ? Math.max(0, Math.min(max, absoluteTop))
            : reveal(page.scrollTop, page.clientHeight, absoluteTop, el.offsetHeight, latest.current.nearestBefore ?? 40, latest.current.nearestAfter ?? 40, max),
        );
      },
      toTop() {
        const page = ref.current;
        if (page !== null) set(page, 0);
      },
    };
  }, []);
  return (
    <Ctx.Provider value={api}>
      <div ref={ref} class={'scroll-page' + (props.class !== undefined ? ' ' + props.class : '')} style={{ position: 'relative', overflow: 'hidden', height: '100%' }}>
        {props.children}
      </div>
    </Ctx.Provider>
  );
}
