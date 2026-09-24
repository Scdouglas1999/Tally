import { createContext, type ComponentChildren } from 'preact';
import { useContext, useMemo, useRef } from 'preact/hooks';
import { offsetWithin, reveal } from './scroll';

interface PageScroll {
  /**
   * Brings `el` (a row, a section) into view. 'start' (Home's rows, as on Android): the focused row moves to the
   * top of the area, so no half-cut row sits above it. 'nearest': the least movement that shows it whole.
   */
  reveal(el: HTMLElement, mode?: 'start' | 'nearest'): void;
}

const Ctx = createContext<PageScroll>({ reveal: () => undefined });

export function usePageScroll(): PageScroll {
  return useContext(Ctx);
}

/**
 * A vertically scrolling page area (overflow hidden, moved by scrollTop). Rows inside call usePageScroll().reveal
 * when focus enters them.
 */
export function ScrollPage(props: { children: ComponentChildren; class?: string; topInset?: number }) {
  const ref = useRef<HTMLDivElement>(null);
  const api = useMemo<PageScroll>(
    () => ({
      reveal(el, mode = 'start') {
        const page = ref.current;
        if (page === null) return;
        const absoluteTop = offsetWithin(el, page).top + page.scrollTop;
        const max = page.scrollHeight - page.clientHeight;
        page.scrollTop =
          mode === 'start'
            ? Math.max(0, Math.min(max, absoluteTop))
            : reveal(page.scrollTop, page.clientHeight, absoluteTop, el.offsetHeight, 40, 40, max);
      },
    }),
    [],
  );
  return (
    <Ctx.Provider value={api}>
      <div ref={ref} class={'scroll-page' + (props.class !== undefined ? ' ' + props.class : '')} style={{ position: 'relative', overflow: 'hidden', height: '100%' }}>
        {props.children}
      </div>
    </Ctx.Provider>
  );
}
