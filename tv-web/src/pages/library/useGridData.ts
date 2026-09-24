import type { BaseItemDto } from '@jellyfin/sdk/lib/generated-client/models/base-item-dto';
import { useEffect, useRef, useState } from 'preact/hooks';
import type { Page } from './libraryData';

/** Items per request: ten rows of a six-column grid. */
export const PAGE_SIZE = 60;

/** Where a grid's items come from. A new `key` starts the list over (a new sort or filter). */
export interface GridSource {
  key: string;
  load: (startIndex: number, limit: number) => Promise<Page>;
}

export type GridStatus = { kind: 'loading' } | { kind: 'error'; message: string } | { kind: 'ready'; total: number };

export interface GridData {
  status: GridStatus;
  /** The item at `index`, or null while its page loads. */
  item: (index: number) => BaseItemDto | null;
  /** Loads the pages covering [from, to]. */
  ensure: (from: number, to: number) => void;
  /** Reloads the pages loaded so far (after coming back to the page: progress, watched state). */
  refresh: () => void;
}

function message(e: unknown): string {
  return e instanceof Error && e.message !== '' ? e.message : 'Nothing came back from the server';
}

/**
 * A grid's items, a page at a time as the grid scrolls (upstream's ApiRequestPager): the first page brings the
 * count; the others load when their rows come near the screen.
 */
export function useGridData(source: GridSource | null): GridData {
  // the status belongs to one source: a newer source reads as loading until its first page is in
  const [state, setState] = useState<{ key: string | null; status: GridStatus }>({ key: null, status: { kind: 'loading' } });
  const status: GridStatus = source !== null && state.key === source.key ? state.status : { kind: 'loading' };
  const setStatus = (key: string, s: GridStatus): void => setState({ key, status: s });
  const [, setVersion] = useState(0);
  const pages = useRef(new Map<number, BaseItemDto[] | 'loading'>());
  const current = useRef<GridSource | null>(null);
  const total = useRef(0);

  const loadPage = (src: GridSource, page: number): void => {
    pages.current.set(page, 'loading');
    src
      .load(page * PAGE_SIZE, PAGE_SIZE)
      .then((r) => {
        if (current.current !== src) return;
        pages.current.set(page, r.items);
        setVersion((v) => v + 1);
      })
      .catch(() => {
        if (current.current === src) pages.current.delete(page);
      });
  };

  useEffect(() => {
    current.current = source;
    pages.current = new Map();
    total.current = 0;
    if (source === null) return;
    const src = source;
    pages.current.set(0, 'loading');
    src
      .load(0, PAGE_SIZE)
      .then((r) => {
        if (current.current !== src) return;
        pages.current.set(0, r.items);
        total.current = r.total;
        setStatus(src.key, { kind: 'ready', total: r.total });
      })
      .catch((e: unknown) => {
        if (current.current === src) setStatus(src.key, { kind: 'error', message: message(e) });
      });
  }, [source?.key]);

  return {
    status,
    item: (index) => {
      if (status.kind !== 'ready') return null;
      const p = pages.current.get(Math.floor(index / PAGE_SIZE));
      return p === undefined || p === 'loading' ? null : (p[index % PAGE_SIZE] ?? null);
    },
    ensure: (from, to) => {
      const src = current.current;
      if (src === null || status.kind !== 'ready') return;
      const last = Math.min(to, total.current - 1);
      for (let page = Math.floor(Math.max(0, from) / PAGE_SIZE); page <= Math.floor(last / PAGE_SIZE); page++) {
        if (!pages.current.has(page)) loadPage(src, page);
      }
    },
    refresh: () => {
      const src = current.current;
      if (src === null || status.kind !== 'ready') return;
      for (const page of Array.from(pages.current.keys())) {
        src
          .load(page * PAGE_SIZE, PAGE_SIZE)
          .then((r) => {
            if (current.current !== src) return;
            pages.current.set(page, r.items);
            setVersion((v) => v + 1);
          })
          .catch(() => undefined);
      }
    },
  };
}
