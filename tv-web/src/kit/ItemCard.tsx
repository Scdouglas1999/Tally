import type { BaseItemDto } from '@jellyfin/sdk/lib/generated-client/models/base-item-dto';
import { useState } from 'preact/hooks';
import { posterUrl, wideUrl } from '../api/images';
import { useFocusable } from '../focus/focus';
import { resumePercent, tallyUppercase } from '../util/format';
import { useRowReveal } from './MediaRow';

export type CardShape = 'poster' | 'landscape';

/** How the picture fills the card (the Android content scales): crop by default. */
export type CardFit = 'cover' | 'contain' | 'none' | 'fill-width' | 'fill-height';

const SIZE: Record<CardShape, [number, number]> = { poster: [211, 317], landscape: [371, 209] };

/** `S1 E3`, or null for anything that is not an episode. */
export function episodeCode(item: BaseItemDto): string | null {
  if (item.Type !== 'Episode') return null;
  const s = item.ParentIndexNumber;
  const e = item.IndexNumber;
  if (s == null && e == null) return null;
  return [s != null ? `S${s}` : null, e != null ? `E${e}` : null].filter((x) => x !== null).join(' ');
}

/**
 * The kicker that replaces a badge: `S1 E3` for an episode and, in Continue Watching / Next Up, the share
 * watched: `S1 E3 · 42%` or `42%` (films).
 */
export function cardKicker(item: BaseItemDto, watchingRow: boolean): string | null {
  const code = episodeCode(item);
  const percent = watchingRow ? resumePercent(item.UserData?.PlaybackPositionTicks ?? 0, item.RunTimeTicks ?? 0) : 0;
  if (code !== null && percent > 0) return `${code} · ${percent}%`;
  if (code !== null) return code;
  if (percent > 0) return `${percent}%`;
  return null;
}

/** Year for films; "3 seasons" for series. */
export function posterDetail(item: BaseItemDto): string | null {
  if (item.Type === 'Series') {
    const n = item.ChildCount;
    if (n != null && n > 0) return n === 1 ? '1 season' : `${n} seasons`;
  }
  return item.ProductionYear != null ? String(item.ProductionYear) : null;
}

function cardTitle(item: BaseItemDto): string {
  if (item.Type === 'Episode') return item.SeriesName ?? item.Name ?? '';
  return item.Name ?? '';
}

/**
 * PosterCard / LandscapeCard for a Jellyfin item: artwork, the black label bar (title + detail or kicker), progress
 * along the image bottom, SEEN / N NEW tags, the favorite square. Focus = the amber frame.
 */
export function ItemCard(props: {
  item: BaseItemDto;
  shape: CardShape;
  watchingRow?: boolean;
  onPress: () => void;
  onFocus?: (item: BaseItemDto) => void;
  focusKey?: string;
  /** Card width in canvas px (default: the shape's width). */
  width?: number;
  /** Picture height in canvas px (default: the shape's height). */
  artHeight?: number;
  /** The picture to show instead of the shape's (null: none). */
  imageUrl?: string | null;
  /** The black label bar under the picture (default on). Without it an episode code / progress kicker becomes a tag. */
  showLabel?: boolean;
  fit?: CardFit;
}) {
  const { item, shape } = props;
  const reveal = useRowReveal();
  const f = useFocusable<HTMLDivElement>({
    focusKey: props.focusKey,
    onEnter: props.onPress,
    onFocus: () => {
      if (f.ref.current !== null) reveal(f.ref.current);
      props.onFocus?.(item);
    },
  });
  const [shapeW, shapeH] = SIZE[shape];
  const w = props.width ?? shapeW;
  const h = props.artHeight ?? shapeH;
  const url = props.imageUrl !== undefined ? props.imageUrl : shape === 'poster' ? posterUrl(item, w, h) : wideUrl(item, w, h);
  const showLabel = props.showLabel !== false;
  const fit = props.fit ?? 'cover';
  const [failed, setFailed] = useState(false);
  const kicker = cardKicker(item, props.watchingRow === true);
  const detail = kicker === null ? posterDetail(item) : null;
  const percent = resumePercent(item.UserData?.PlaybackPositionTicks ?? 0, item.RunTimeTicks ?? 0);
  const played = item.UserData?.Played === true;
  const unplayed = item.UserData?.UnplayedItemCount ?? 0;
  const stateTag = played ? 'SEEN' : unplayed > 0 && (item.Type === 'Series' || item.Type === 'Season') ? `${unplayed} NEW` : null;
  const kickerTag = !showLabel && kicker !== null;
  const tag = kickerTag ? tallyUppercase(kicker) : stateTag;
  return (
    <div
      ref={f.ref}
      class={'card ' + shape}
      style={props.width !== undefined ? { width: `${w}px` } : undefined}
      onClick={props.onPress}
    >
      <div class="art" style={props.artHeight !== undefined ? { height: `${h}px` } : undefined}>
        {url !== null && !failed ? (
          <img class={fit === 'cover' ? undefined : 'fit-' + fit} src={url} alt="" onError={() => setFailed(true)} />
        ) : (
          <div class="art-fallback">{cardTitle(item)}</div>
        )}
        {tag !== null ? <span class={'tag' + (tag === 'SEEN' && !kickerTag ? '' : ' accent')}>{tag}</span> : null}
        {item.UserData?.IsFavorite === true ? <span class="favorite" /> : null}
        {percent > 0 && !played ? (
          <div class="progress">
            <div style={{ width: `${percent}%` }} />
          </div>
        ) : null}
      </div>
      {showLabel ? (
        <div class="bar">
          {kicker !== null ? <div class="kicker ellipsis">{tallyUppercase(kicker)}</div> : null}
          <div class="title ellipsis">{kicker !== null && item.Type === 'Episode' ? (item.Name ?? '') : cardTitle(item)}</div>
          {detail !== null ? <div class="detail ellipsis">{tallyUppercase(detail)}</div> : null}
        </div>
      ) : null}
    </div>
  );
}
