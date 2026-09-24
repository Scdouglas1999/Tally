import type { BaseItemDto } from '@jellyfin/sdk/lib/generated-client/models/base-item-dto';
import type { Ref } from 'preact';
import { memo } from 'preact/compat';
import { useFocusable, FocusGroup } from '../../focus/focus';
import { posterUrl, wideUrl } from '../../api/images';
import { ItemCard, episodeCode, posterDetail, type CardFit } from '../../kit/ItemCard';
import { IndicatorSquare } from '../../kit/Bits';
import { offsetWithin, reveal } from '../../kit/scroll';
import { formatRuntime, tallyUppercase } from '../../util/format';
import { HomeHeader } from '../home/HomeHeader';
import { ASPECT_RATIOS, DP, JUMP_LETTERS, gridCardWidth, jumpLetterFor, type ContentScale, type ViewOptions } from './libraryModel';
import { VirtualGrid, type GridGeometry, type GridHandle } from './VirtualGrid';

/** Canvas sizes of the content area (the page area is 1776 wide: the screen less the rail). */
export const BODY = {
  /** The page's left margin (48dp). */
  left: 77,
  /** Where the grid ends: 12dp from the edge, the A-Z bar, 12dp between. */
  gridRight: 1776 - 19 - 58 - 19,
  /** The jump bar's column. */
  barLeft: 1776 - 19 - 58,
  barWidth: 58,
  /** Focus frame room (3dp + 1dp) around cells. */
  edge: 6,
  /** Between the header strip and the first row (24dp): the fade lives in it. */
  topGap: 38,
  /** Height of the "show details" band (the home header). */
  detailsHeight: 371,
};

const LABEL_BAR = 64;

function fitFor(scale: ContentScale): CardFit {
  switch (scale) {
    case 'fit':
      return 'contain';
    case 'none':
      return 'none';
    case 'fillWidth':
      return 'fill-width';
    case 'fillHeight':
      return 'fill-height';
    default:
      return 'cover';
  }
}

/** The grid's geometry for the view options (columns, spacing, picture ratio, titles). */
export function folderGeometry(view: ViewOptions, count: number, top = 0): GridGeometry {
  const gap = Math.round(view.spacing * DP);
  const available = BODY.gridRight - BODY.left;
  if (view.type !== 'grid') {
    const height = view.type === 'list' ? 144 : 72;
    return { count, columns: 1, cellWidth: available, cellHeight: height, gap, topPad: BODY.topGap + BODY.edge + top, bottomPad: 43 + BODY.edge, sidePad: BODY.edge };
  }
  const width = gridCardWidth(available, view.columns, gap);
  const art = Math.round(width / ASPECT_RATIOS[view.aspectRatio]);
  return {
    count,
    columns: Math.max(1, view.columns),
    cellWidth: width,
    cellHeight: art + (view.showTitles ? LABEL_BAR : 0),
    gap,
    topPad: BODY.topGap + BODY.edge + top,
    bottomPad: 43 + BODY.edge,
    sidePad: BODY.edge,
  };
}

/** Stands in for an item whose page is still loading: an empty frame of the same size (the same focusable). */
const PENDING: BaseItemDto = {};

/** A library item in the grid: the kit's card at the view options' size, picture type and fit. */
function LibraryCardView(props: {
  /** The item, or PENDING while it loads. */
  item: BaseItemDto;
  view: ViewOptions;
  width: number;
  height: number;
  focusKey: string;
  onFocus: () => void;
  onPress: () => void;
}) {
  const { item, view, width } = props;
  const art = props.height - (view.showTitles ? LABEL_BAR : 0);
  const url = item === PENDING ? null : view.imageType === 'thumb' ? wideUrl(item, width, art) : posterUrl(item, width, art);
  return (
    <div class={view.showTitles ? 'lib-card titled' : 'lib-card'}>
      <ItemCard
      item={item}
      shape={view.aspectRatio === 'tall' ? 'poster' : 'landscape'}
      focusKey={props.focusKey}
      width={width}
      artHeight={art}
      imageUrl={url}
      showLabel={view.showTitles}
      fit={fitFor(view.contentScale)}
      onPress={props.onPress}
      onFocus={props.onFocus}
      />
    </div>
  );
}

/**
 * Cards draw again only when what they show changes (a D-pad move re-renders the grid; on a TV, re-drawing every
 * card each time is what makes a grid slow). Callbacks are read through the focus binding's latest-options ref.
 */
export const LibraryCard = memo(
  LibraryCardView,
  (a, b) => a.item === b.item && a.view === b.view && a.width === b.width && a.height === b.height && a.focusKey === b.focusKey,
);

/** A row of the list layouts (upstream's list, in the Tally look): picture, title, a mono meta line. */
function ListRow(props: { item: BaseItemDto | null; dense: boolean; focusKey: string; onFocus: () => void; onPress: () => void }) {
  const f = useFocusable<HTMLDivElement>({ focusKey: props.focusKey, onFocus: props.onFocus, onEnter: props.onPress });
  const item = props.item;
  const meta: string[] = [];
  if (item !== null) {
    const code = episodeCode(item);
    if (code !== null) meta.push(code);
    const detail = posterDetail(item);
    if (detail !== null) meta.push(detail);
    if (item.OfficialRating != null && item.OfficialRating !== '') meta.push(item.OfficialRating);
    if ((item.RunTimeTicks ?? 0) > 0) meta.push(formatRuntime(item.RunTimeTicks ?? 0));
    if (item.CommunityRating != null) meta.push(`★ ${item.CommunityRating.toFixed(1)}`);
  }
  const url = item !== null && !props.dense ? posterUrl(item, 86, 128) : null;
  return (
    <div ref={f.ref} class={'lib-list-row' + (props.dense ? ' dense' : '')} onClick={props.onPress}>
      {props.dense ? null : <div class="thumb">{url !== null ? <img src={url} alt="" /> : null}</div>}
      <div class="text">
        <div class="title ellipsis">{item?.Name ?? ''}</div>
        <div class="meta mono-label ellipsis">{tallyUppercase(meta.join(' · '))}</div>
      </div>
      {item?.UserData?.Played === true ? <span class="seen mono-label">SEEN</span> : null}
      {item?.UserData?.IsFavorite === true ? <IndicatorSquare tone="accent" /> : null}
    </div>
  );
}

function JumpLetter(props: { letter: string; current: boolean; focusKey: string; onPress: () => void }) {
  const f = useFocusable<HTMLDivElement>({
    focusKey: props.focusKey,
    onEnter: props.onPress,
    trackFocus: true,
    onFocus: () => {
      // the bar scrolls when it is taller than its room (the details band above the grid)
      const el = f.ref.current;
      const bar = el?.closest('.lib-jump') as HTMLElement | null;
      if (el == null || bar === null) return;
      bar.scrollTop = reveal(bar.scrollTop, bar.clientHeight, offsetWithin(el, bar).top + bar.scrollTop, el.offsetHeight, 6, 6, bar.scrollHeight - bar.clientHeight);
    },
  });
  return (
    <div ref={f.ref} class={'lib-letter' + (props.current || f.focused ? ' lit' : '')} onClick={props.onPress}>
      <span class="sq">{props.current ? <IndicatorSquare tone="accent" /> : null}</span>
      <span class="ch">{props.letter}</span>
      <span class="sq" />
    </div>
  );
}

/**
 * `#`, `A`…`Z` in mono muted; the focused card's letter in text with a small accent square. Entering the bar
 * lands on that letter; OK jumps to the first item filed under it; LEFT goes back to the grid.
 */
export function JumpBar(props: { pageKey: string; current: string; onLetter: (letter: string) => void; top: number }) {
  const key = props.pageKey + '-jump';
  const letterKey = (l: string): string => `${props.pageKey}-j-${l === '#' ? 'num' : l}`;
  const group = useFocusable<HTMLDivElement>({ focusKey: key, saveLastFocusedChild: false, preferredChildFocusKey: letterKey(props.current) });
  return (
    <div class="lib-jump" style={{ top: `${props.top}px` }}>
      <div ref={group.ref} class="letters">
        <FocusGroup focusKey={key}>
          {JUMP_LETTERS.split('').map((l) => (
            <JumpLetter key={l} letter={l} current={l === props.current} focusKey={letterKey(l)} onPress={() => props.onLetter(l)} />
          ))}
        </FocusGroup>
      </div>
    </div>
  );
}

/**
 * A folder's items (a library grid): the optional details band, the grid (or a list) and the A-Z bar while the
 * list is sorted by name.
 */
export function FolderBody(props: {
  pageKey: string;
  view: ViewOptions;
  total: number;
  item: (index: number) => BaseItemDto | null;
  ensure: (from: number, to: number) => void;
  jumpBar: boolean;
  kicker: string;
  focusedIndex: number;
  initialIndex: number;
  onFocusIndex: (index: number) => void;
  onOpen: (index: number, item: BaseItemDto) => void;
  onLetter: (letter: string) => void;
  handle: Ref<GridHandle>;
}) {
  const view = props.view;
  const details = view.showDetails;
  const geometry = folderGeometry(view, props.total);
  const focusedItem = props.item(props.focusedIndex);
  const cellKey = (i: number): string => `${props.pageKey}-c-${i}`;
  return (
    <div class="lib-folder">
      {details ? (
        <div class="lib-details">{focusedItem !== null ? <HomeHeader focus={{ kind: 'item', item: focusedItem, rowTitle: props.kicker }} /> : null}</div>
      ) : null}
      <div class="lib-grid-area" style={{ top: details ? `${BODY.detailsHeight}px` : '0' }}>
        <VirtualGrid
          geometry={geometry}
          groupKey={props.pageKey + '-grid'}
          focusKeyFor={cellKey}
          initialIndex={props.initialIndex}
          handle={props.handle}
          onFocusIndex={props.onFocusIndex}
          onRange={props.ensure}
          cell={(i, key, onFocus, width, height) => {
            const item = props.item(i);
            if (view.type !== 'grid') {
              return <ListRow item={item} dense={view.type === 'denseList'} focusKey={key} onFocus={onFocus} onPress={() => item !== null && props.onOpen(i, item)} />;
            }
            return (
              <LibraryCard
                item={item ?? PENDING}
                view={view}
                width={width}
                height={height}
                focusKey={key}
                onFocus={onFocus}
                onPress={() => item !== null && props.onOpen(i, item)}
              />
            );
          }}
        />
        {props.jumpBar ? <JumpBar pageKey={props.pageKey} current={jumpLetterFor(focusedItem?.SortName ?? focusedItem?.Name)} onLetter={props.onLetter} top={BODY.topGap} /> : null}
      </div>
    </div>
  );
}
