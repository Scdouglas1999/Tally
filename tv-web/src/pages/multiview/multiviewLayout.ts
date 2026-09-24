/**
 * Multiview tile geometry and D-pad map (port of TallyMultiviewPage.kt's multiviewTileSlots / tileFocusMap; same
 * rules, unit-tested). Sizes in canvas px (Android dp × 1.6).
 */
export type MultiviewLayout = 'equal' | 'focus';

export interface TileSlot {
  x: number;
  y: number;
  width: number;
}

/** The channel label bar under each picture (LabelBar's 30dp). */
export const TILE_LABEL_HEIGHT = 48;
/** 12dp between tiles. */
export const TILE_GAP = 19;
/** The large tile's share of the stage width in focus layout. */
export const FOCUS_WIDTH_FRACTION = 0.68;

/** 16:9 picture plus the label bar. */
export const tileBlockHeight = (width: number): number => (width * 9) / 16 + TILE_LABEL_HEIGHT;

/** Before the viewer presses OK: focus when there are enough tiles to stack (3, 4), equal for one or two. */
export function defaultLayout(count: number): MultiviewLayout {
  return count >= 3 ? 'focus' : 'equal';
}

function equalSlots(count: number, areaW: number, areaH: number): TileSlot[] {
  const columns = count === 1 ? 1 : 2;
  const rows = count <= 2 ? 1 : 2;
  const maxW = columns === 1 ? areaW : (areaW - TILE_GAP) / 2;
  let tileW = maxW;
  let tileH = tileBlockHeight(tileW);
  const rowGap = rows > 1 ? TILE_GAP : 0;
  if (tileH * rows + rowGap > areaH) {
    const budget = areaH - TILE_LABEL_HEIGHT * rows - rowGap;
    tileW = Math.min(maxW, (budget / rows) * (16 / 9));
    tileH = tileBlockHeight(tileW);
  }
  const gridW = tileW * columns + (columns > 1 ? TILE_GAP : 0);
  const gridH = tileH * rows + rowGap;
  const left = Math.max(0, (areaW - gridW) / 2);
  const top = Math.max(0, (areaH - gridH) / 2);
  const out: TileSlot[] = [];
  for (let i = 0; i < count; i++) {
    const col = count === 1 ? 0 : i % 2;
    const row = count <= 2 ? 0 : Math.floor(i / 2);
    out.push({ x: left + (tileW + TILE_GAP) * col, y: top + (tileH + TILE_GAP) * row, width: tileW });
  }
  return out;
}

function focusSlots(count: number, bigIndex: number, areaW: number, areaH: number): TileSlot[] {
  const big = Math.min(Math.max(bigIndex, 0), count - 1);
  let bigW = areaW * FOCUS_WIDTH_FRACTION;
  let smallW = areaW - TILE_GAP - bigW;
  const nSmall = count - 1;
  if (nSmall > 0 && smallW > 0) {
    const stack = tileBlockHeight(smallW) * nSmall + TILE_GAP * (nSmall - 1);
    if (stack > areaH) {
      const budget = areaH - TILE_LABEL_HEIGHT * nSmall - TILE_GAP * (nSmall - 1);
      smallW = (budget / nSmall) * (16 / 9);
      bigW = Math.max(smallW, areaW - TILE_GAP - smallW);
    }
  }
  if (tileBlockHeight(bigW) > areaH) bigW = (areaH - TILE_LABEL_HEIGHT) * (16 / 9);
  const smallH = tileBlockHeight(smallW);
  const bigH = tileBlockHeight(bigW);
  const stackH = nSmall === 0 ? bigH : smallH * nSmall + TILE_GAP * (nSmall - 1);
  // three and four tiles are top-aligned; two share that top edge and sit in the vertical middle
  const top = count >= 3 ? 0 : Math.max(0, (areaH - Math.max(bigH, stackH)) / 2);
  const out: TileSlot[] = [];
  let stackIndex = 0;
  for (let i = 0; i < count; i++) {
    if (i === big) {
      out.push({ x: 0, y: top, width: bigW });
    } else {
      out.push({ x: bigW + TILE_GAP, y: top + (smallH + TILE_GAP) * stackIndex, width: smallW });
      stackIndex++;
    }
  }
  return out;
}

/**
 * Equal tiles share a centered grid. Focus puts `bigIndex` on the left at 68% of the stage and stacks the rest on the
 * right, top-aligned with the large tile, 16:9.
 */
export function tileSlots(count: number, layout: MultiviewLayout, bigIndex: number, areaW: number, areaH: number): TileSlot[] {
  if (count <= 0 || areaW <= 0 || areaH <= 0) return [];
  return count === 1 || layout === 'equal' ? equalSlots(count, areaW, areaH) : focusSlots(count, bigIndex, areaW, areaH);
}

/** Where an arrow goes from a tile: another tile, the swap-in rail, nowhere ('block'), or let the D-pad decide ('free'). */
export type FocusTarget = { tile: number } | 'rail' | 'block' | 'free';

export interface TileFocus {
  left: FocusTarget;
  right: FocusTarget;
  up: FocusTarget;
  down: FocusTarget;
}

function equalFocus(index: number, count: number, hasRail: boolean): TileFocus {
  const rail: FocusTarget = hasRail ? 'rail' : 'block';
  if (count <= 1) return { left: 'free', right: rail, up: 'block', down: 'block' };
  const col = index % 2;
  const row = count <= 2 ? 0 : Math.floor(index / 2);
  const right: FocusTarget = col === 0 && index + 1 < count ? { tile: index + 1 } : rail;
  const up: FocusTarget = row === 0 ? 'block' : count === 3 && index === 2 ? { tile: 0 } : { tile: index - 2 };
  const down: FocusTarget =
    count <= 2 ? 'block' : index + 2 < count ? { tile: index + 2 } : count === 3 && index === 1 ? { tile: 2 } : 'block';
  return { left: col === 0 ? 'free' : { tile: index - 1 }, right, up, down };
}

/** The D-pad map of the tiles (TallyMultiviewPage.kt tileFocusMap). */
export function tileFocusMap(index: number, count: number, layout: MultiviewLayout, bigIndex: number, hasRail: boolean): TileFocus {
  if (count <= 1 || layout === 'equal') return equalFocus(index, count, hasRail);
  const big = Math.min(Math.max(bigIndex, 0), count - 1);
  const smalls: number[] = [];
  for (let i = 0; i < count; i++) if (i !== big) smalls.push(i);
  const rail: FocusTarget = hasRail ? 'rail' : 'block';
  if (index === big) {
    return { left: 'free', right: smalls.length > 0 ? { tile: smalls[0] as number } : rail, up: 'block', down: 'block' };
  }
  const pos = smalls.indexOf(index);
  return {
    left: { tile: big },
    right: rail,
    up: pos > 0 ? { tile: smalls[pos - 1] as number } : 'block',
    down: pos >= 0 && pos < smalls.length - 1 ? { tile: smalls[pos + 1] as number } : 'block',
  };
}

/**
 * How many tiles can play video at once. Browsers decode in software: all four, as on Android. TVs have a fixed
 * number of hardware decoders: one is guaranteed (2020+ Samsung and LG sets); more only where a real set has been
 * probed (ARCHITECTURE.md, multiview). Tiles beyond this show the channel's live card and the audio tile plays.
 */
export function multiviewDecoders(platform: 'tizen' | 'webos' | 'browser'): number {
  return platform === 'browser' ? 4 : 1;
}

/**
 * Which tiles get a decoder: all while there are enough; otherwise the audio tile first, then the others in order.
 */
export function playingTiles(count: number, audioIndex: number, decoders: number): boolean[] {
  const out: boolean[] = [];
  for (let i = 0; i < count; i++) out.push(false);
  if (count === 0 || decoders <= 0) return out;
  let left = decoders;
  const audio = Math.min(Math.max(audioIndex, 0), count - 1);
  out[audio] = true;
  left--;
  for (let i = 0; i < count && left > 0; i++) {
    if (!out[i]) {
      out[i] = true;
      left--;
    }
  }
  return out;
}
