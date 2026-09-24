/**
 * The chapter and queue rows that replace the controls when DOWN is pressed on them (TallyChapterQueueRows.kt): the
 * kit's media row of landscape cards, the playing chapter marked with an accent square and focused first; the queue
 * with `NEXT` on its first card and `E04 · 47m` after it.
 */
import type { BaseItemDto } from '@jellyfin/sdk/lib/generated-client/models/base-item-dto';
import type { ChapterInfo } from '@jellyfin/sdk/lib/generated-client/models/chapter-info';
import { useLayoutEffect, useState } from 'preact/hooks';
import { wideUrl } from '../../api/images';
import { setFocus, useFocusable } from '../../focus/focus';
import { MediaRow, useRowReveal } from '../../kit/MediaRow';
import { tallyUppercase } from '../../util/format';
import { chapterImageUrl } from './playerData';
import * as F from './playerFormat';

const CARD_W = 371;
const CARD_H = 209;

/** A LandscapeCard with an explicit picture and kicker (chapters are not items). */
function PlayerCard(props: {
  focusKey: string;
  imageUrl: string | null;
  kicker: string | null;
  title: string;
  current?: boolean;
  favorite?: boolean;
  onPress: () => void;
  onArrow: (direction: string) => boolean;
  onFocus: () => void;
}) {
  const reveal = useRowReveal();
  const f = useFocusable<HTMLDivElement>({
    focusKey: props.focusKey,
    onEnter: props.onPress,
    onArrow: props.onArrow,
    onFocus: () => {
      if (f.ref.current !== null) reveal(f.ref.current);
      props.onFocus();
    },
  });
  const [failed, setFailed] = useState(false);
  return (
    <div ref={f.ref} class="card landscape pc-card" onClick={props.onPress}>
      <div class="art">
        {props.imageUrl !== null && !failed ? <img src={props.imageUrl} alt="" onError={() => setFailed(true)} /> : <div class="art-fallback">{props.title}</div>}
        {props.favorite === true ? <span class="favorite" /> : null}
        {props.current === true ? <span class="pc-current" /> : null}
      </div>
      <div class="bar">
        {props.kicker !== null ? <div class="kicker ellipsis">{tallyUppercase(props.kicker)}</div> : null}
        <div class="title ellipsis">{props.title}</div>
      </div>
    </div>
  );
}

/**
 * Chapters: OK seeks to the chapter and hides the controls; UP returns to the controls; DOWN goes to the queue when
 * there is one; LEFT on the first card goes nowhere.
 */
export function ChapterRow(props: {
  itemId: string;
  chapters: readonly ChapterInfo[];
  positionMs: number;
  onSeek: (ms: number) => void;
  onUp: () => void;
  onDown: (() => void) | null;
  onFocus: () => void;
}) {
  const [current] = useState(() => {
    const at = F.chapterAt(
      props.chapters.map((c) => F.ticksToMs(c.StartPositionTicks)),
      props.positionMs,
    );
    return Math.min(Math.max(0, at ?? 0), props.chapters.length - 1);
  });
  useLayoutEffect(() => setFocus('pc-chapter-' + String(current)), []);
  const arrow = (d: string): boolean => {
    if (d === 'up') {
      props.onUp();
      return false;
    }
    if (d === 'down') {
      if (props.onDown !== null) props.onDown();
      return false;
    }
    return true;
  };
  return (
    <div class="pc-cards">
      <MediaRow title="Chapters" count={props.chapters.length} focusKey="pc-chapters-row">
        {props.chapters.map((c, i) => {
          const start = F.ticksToMs(c.StartPositionTicks);
          return (
            <PlayerCard
              key={String(i)}
              focusKey={'pc-chapter-' + String(i)}
              imageUrl={chapterImageUrl(props.itemId, i, c.ImageTag, CARD_W, CARD_H)}
              kicker={F.chapterKicker(i, start)}
              title={c.Name ?? ''}
              current={i === current}
              onPress={() => props.onSeek(start)}
              onArrow={arrow}
              onFocus={props.onFocus}
            />
          );
        })}
      </MediaRow>
    </div>
  );
}

/** The items after this one: OK plays that item; UP returns to the chapters (or the controls). */
export function QueueRow(props: { queue: readonly BaseItemDto[]; onPlay: (index: number) => void; onUp: () => void; onFocus: () => void }) {
  useLayoutEffect(() => setFocus('pc-queue-0'), []);
  const arrow = (d: string): boolean => {
    if (d === 'up') {
      props.onUp();
      return false;
    }
    return d !== 'down';
  };
  return (
    <div class="pc-cards">
      <MediaRow title="Queue" count={props.queue.length} focusKey="pc-queue-row">
        {props.queue.map((item, i) => (
          <PlayerCard
            key={item.Id ?? String(i)}
            focusKey={'pc-queue-' + String(i)}
            imageUrl={wideUrl(item, CARD_W, CARD_H)}
            kicker={F.queueKicker(i, item)}
            title={item.Name ?? ''}
            favorite={item.UserData?.IsFavorite === true}
            onPress={() => props.onPlay(i)}
            onArrow={arrow}
            onFocus={props.onFocus}
          />
        ))}
      </MediaRow>
    </div>
  );
}
