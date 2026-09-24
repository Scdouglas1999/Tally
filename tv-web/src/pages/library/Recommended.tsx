import type { BaseItemDto } from '@jellyfin/sdk/lib/generated-client/models/base-item-dto';
import { memo } from 'preact/compat';
import { useEffect, useMemo, useRef, useState } from 'preact/hooks';
import { useFocusable } from '../../focus/focus';
import { ItemCard } from '../../kit/ItemCard';
import { MediaRow, useRowReveal } from '../../kit/MediaRow';
import { ScrollPage } from '../../kit/ScrollPage';
import { HomeHeader } from '../home/HomeHeader';
import { ROW_LIMIT, recommendedRows, type RecommendedRowSpec } from './libraryData';
import { LoadingMark } from './Header';

type RowState = { kind: 'loading' } | { kind: 'error'; message: string } | { kind: 'items'; items: BaseItemDto[] };

const LOADING: RowState = { kind: 'loading' };

/** Poster row height while a row loads (picture + focus room), so nothing jumps when cards arrive. */
const ROW_HEIGHT = 317 + 14;

/** Upstream's "view more" card at the end of a full row: groundRaised, a mono `VIEW ALL →`. */
function ViewAllCard(props: { focusKey: string; onPress: () => void; onFocus: () => void }) {
  const reveal = useRowReveal();
  const f = useFocusable<HTMLDivElement>({
    focusKey: props.focusKey,
    onEnter: props.onPress,
    onFocus: () => {
      if (f.ref.current !== null) reveal(f.ref.current);
      props.onFocus();
    },
  });
  return (
    <div ref={f.ref} class="card poster lib-view-all" onClick={props.onPress}>
      <span class="mono-label">VIEW ALL →</span>
    </div>
  );
}

interface RowHandlers {
  focus: (item: BaseItemDto, rowTitle: string) => void;
  open: (item: BaseItemDto) => void;
  viewAll: (row: RecommendedRowSpec) => void;
}

/**
 * One row. Drawn again only when its items change, not on every focus move (the header band above re-renders then);
 * the callbacks come through a ref that always holds the latest ones.
 */
const RecRow = memo(
  function RecRow(props: { pageKey: string; spec: RecommendedRowSpec; state: RowState; handlers: { current: RowHandlers } }) {
    const { spec, state } = props;
    const cardKey = (i: number): string => `${props.pageKey}-rec-${spec.key}-${i}`;
    const full = state.kind === 'items' && state.items.length >= ROW_LIMIT;
    return (
      <MediaRow
        focusKey={`${props.pageKey}-rec-${spec.key}`}
        title={spec.title}
        count={state.kind === 'items' ? state.items.length : null}
        message={state.kind === 'loading' ? 'LOADING…' : state.kind === 'error' ? state.message : null}
        height={state.kind === 'loading' ? ROW_HEIGHT : 40}
      >
        {state.kind === 'items'
          ? state.items
              .map((item, i) => (
                <ItemCard
                  key={item.Id}
                  focusKey={cardKey(i)}
                  item={item}
                  shape="poster"
                  showLabel={false}
                  watchingRow={spec.watching}
                  onPress={() => props.handlers.current.open(item)}
                  onFocus={(it) => props.handlers.current.focus(it, spec.title)}
                />
              ))
              .concat(
                full
                  ? [<ViewAllCard key="all" focusKey={cardKey(state.items.length)} onPress={() => props.handlers.current.viewAll(spec)} onFocus={() => undefined} />]
                  : [],
              )
          : null}
      </MediaRow>
    );
  },
  (a, b) => a.spec === b.spec && a.state === b.state && a.pageKey === b.pageKey,
);

/** The first row's first card is ready to take focus (the rows before it are known to be empty). */
function firstReady(specs: RecommendedRowSpec[], rows: Record<string, RowState>): { settled: boolean; key: string | null } {
  for (const spec of specs) {
    const r = rows[spec.key];
    if (r === undefined || r.kind === 'loading') return { settled: false, key: null };
    if (r.kind === 'items' && r.items.length > 0) return { settled: true, key: spec.key };
  }
  return { settled: true, key: null };
}

/**
 * The Recommended tab: the home page's look for the library's rows (upstream's RecommendedContent). The focused
 * item's header band, then one row per list with its mono header and count; a full row ends in a VIEW ALL card.
 * OK opens an item, PLAY plays it (the page's key handler), Continue watching and Next up say how much is watched.
 */
export function Recommended(props: {
  pageKey: string;
  libraryId: string;
  collectionType: string | null;
  onFocusItem: (item: BaseItemDto) => void;
  onReady: (focusKey: string | null) => void;
  onOpen: (item: BaseItemDto) => void;
  onViewAll: (row: RecommendedRowSpec) => void;
  refreshToken: number;
  /** Filled with the rows' items by focus key (the page's item menu and PLAY key read it). */
  cardItems?: Map<string, BaseItemDto>;
}) {
  const specs = useMemo(() => recommendedRows(props.libraryId, props.collectionType), [props.libraryId, props.collectionType]);
  const [rows, setRows] = useState<Record<string, RowState>>({});
  const [focus, setFocusInfo] = useState<{ item: BaseItemDto; rowTitle: string } | null>(null);

  useEffect(() => {
    let live = true;
    for (const spec of specs) {
      spec
        .load(0, ROW_LIMIT)
        .then((p) => live && setRows((r) => ({ ...r, [spec.key]: { kind: 'items', items: p.items } })))
        .catch((e: unknown) => live && setRows((r) => ({ ...r, [spec.key]: { kind: 'error', message: e instanceof Error ? e.message : 'Could not load this row.' } })));
    }
    return () => {
      live = false;
    };
  }, [specs, props.refreshToken]);

  const handlers = useRef<RowHandlers>({ focus: () => undefined, open: () => undefined, viewAll: () => undefined });
  handlers.current = {
    focus: (item, rowTitle) => {
      setFocusInfo({ item, rowTitle });
      props.onFocusItem(item);
    },
    open: props.onOpen,
    viewAll: props.onViewAll,
  };
  const ready = firstReady(specs, rows);
  const cardKey = (row: string, i: number): string => `${props.pageKey}-rec-${row}-${i}`;
  const cards = props.cardItems;
  if (cards !== undefined) {
    cards.clear();
    for (const spec of specs) {
      const state = rows[spec.key];
      if (state?.kind === 'items') state.items.forEach((item, i) => cards.set(cardKey(spec.key, i), item));
    }
  }
  useEffect(() => {
    if (ready.settled) props.onReady(ready.key !== null ? cardKey(ready.key, 0) : null);
  }, [ready.settled]);

  if (!ready.settled) return <LoadingMark />;

  return (
    <div class="lib-rec">
      <div class="lib-details">{focus !== null ? <HomeHeader focus={{ kind: 'item', item: focus.item, rowTitle: focus.rowTitle }} /> : null}</div>
      <div class="lib-rec-rows">
        <ScrollPage>
          {specs.map((spec) => {
            const state = rows[spec.key] ?? LOADING;
            if (state.kind === 'items' && state.items.length === 0) return null;
            return <RecRow key={spec.key} pageKey={props.pageKey} spec={spec} state={state} handlers={handlers} />;
          })}
        </ScrollPage>
      </div>
    </div>
  );
}
