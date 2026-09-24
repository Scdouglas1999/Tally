import type { BaseItemDto } from '@jellyfin/sdk/lib/generated-client/models/base-item-dto';
import { useState } from 'preact/hooks';
import { posterUrl, wideUrl } from '../api/images';
import { useFocusable } from '../focus/focus';
import { resumePercent, tallyUppercase } from '../util/format';
import { useRowReveal } from './MediaRow';

export type CardShape = 'poster' | 'landscape';

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
  const [w, h] = SIZE[shape];
  const url = shape === 'poster' ? posterUrl(item, w, h) : wideUrl(item, w, h);
  const [failed, setFailed] = useState(false);
  const kicker = cardKicker(item, props.watchingRow === true);
  const detail = kicker === null ? posterDetail(item) : null;
  const percent = resumePercent(item.UserData?.PlaybackPositionTicks ?? 0, item.RunTimeTicks ?? 0);
  const played = item.UserData?.Played === true;
  const unplayed = item.UserData?.UnplayedItemCount ?? 0;
  const tag = played ? 'SEEN' : unplayed > 0 && (item.Type === 'Series' || item.Type === 'Season') ? `${unplayed} NEW` : null;
  return (
    <div ref={f.ref} class={'card ' + shape} onClick={props.onPress}>
      <div class="art">
        {url !== null && !failed ? (
          <img src={url} alt="" onError={() => setFailed(true)} />
        ) : (
          <div class="art-fallback">{cardTitle(item)}</div>
        )}
        {tag !== null ? <span class={'tag' + (tag === 'SEEN' ? '' : ' accent')}>{tag}</span> : null}
        {item.UserData?.IsFavorite === true ? <span class="favorite" /> : null}
        {percent > 0 && !played ? (
          <div class="progress">
            <div style={{ width: `${percent}%` }} />
          </div>
        ) : null}
      </div>
      <div class="bar">
        {kicker !== null ? <div class="kicker ellipsis">{tallyUppercase(kicker)}</div> : null}
        <div class="title ellipsis">{kicker !== null && item.Type === 'Episode' ? (item.Name ?? '') : cardTitle(item)}</div>
        {detail !== null ? <div class="detail ellipsis">{tallyUppercase(detail)}</div> : null}
      </div>
    </div>
  );
}
