import { useState } from 'preact/hooks';
import { useFocusable } from '../../focus/focus';
import type { NameCell } from './libraryData';
import { BODY } from './FolderBody';
import { gridCardWidth } from './libraryModel';
import { VirtualGrid, type GridGeometry } from './VirtualGrid';

/** Upstream's genre and studio grids: four 16:9 cards across, 16dp apart. */
export const NAME_COLUMNS = 4;
const NAME_GAP = 26;

export function nameCardWidth(): number {
  return gridCardWidth(BODY.gridRight - BODY.left, NAME_COLUMNS, NAME_GAP);
}

/** A genre or studio: a picture from it (or the studio's thumb) with its name in the label bar. */
function NameCard(props: { cell: NameCell; focusKey: string; width: number; onFocus: () => void; onPress: () => void }) {
  const f = useFocusable<HTMLDivElement>({ focusKey: props.focusKey, onFocus: props.onFocus, onEnter: props.onPress });
  const [failed, setFailed] = useState<string | null>(null);
  const url = props.cell.imageUrl;
  const art = Math.round((props.width * 9) / 16);
  return (
    <div ref={f.ref} class="card landscape lib-name-card" style={{ width: `${props.width}px` }} onClick={props.onPress}>
      <div class="art" style={{ height: `${art}px` }}>
        {url !== null && failed !== url ? <img key={url} src={url} alt="" onError={() => setFailed(url)} /> : null}
      </div>
      <div class="bar">
        <div class="title ellipsis">{props.cell.name}</div>
      </div>
    </div>
  );
}

/** The Genres and Studios tabs: a grid of name cards; OK opens the genre's or studio's items. */
export function NameGrid(props: { pageKey: string; cells: NameCell[]; onOpen: (cell: NameCell) => void }) {
  const width = nameCardWidth();
  const geometry: GridGeometry = {
    count: props.cells.length,
    columns: NAME_COLUMNS,
    cellWidth: width,
    cellHeight: Math.round((width * 9) / 16) + 64,
    gap: NAME_GAP,
    topPad: BODY.topGap + BODY.edge,
    bottomPad: 43 + BODY.edge,
    sidePad: BODY.edge,
  };
  return (
    <div class="lib-grid-area">
      <VirtualGrid
        geometry={geometry}
        groupKey={props.pageKey + '-names'}
        focusKeyFor={(i) => `${props.pageKey}-n-${i}`}
        cell={(i, key, onFocus, w) => {
          const cell = props.cells[i];
          return cell === undefined ? null : <NameCard cell={cell} focusKey={key} width={w} onFocus={onFocus} onPress={() => props.onOpen(cell)} />;
        }}
      />
    </div>
  );
}
