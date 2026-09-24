/** Pieces every detail page shares: backdrop, clock, top scrim, the action row group, states, the play key. */
import type { BaseItemDto } from '@jellyfin/sdk/lib/generated-client/models/base-item-dto';
import { ImageType } from '@jellyfin/sdk/lib/generated-client/models/image-type';
import { getImageApi } from '@jellyfin/sdk/lib/utils/api/image-api';
import type { ComponentChildren } from 'preact';
import { useEffect, useRef, useState } from 'preact/hooks';
import { itemImage } from '../../api/images';
import { currentApi } from '../../api/jellyfin';
import { app } from '../../app/context';
import { FocusGroup, currentFocusKey, useFocusable } from '../../focus/focus';
import { ScrollPage, usePageScroll } from '../../kit/ScrollPage';
import { useKeyHandler } from '../../platform/keyRouter';
import { formatTime, tallyUppercase } from '../../util/format';
import type { Trailer } from './DetailDialogs';
import type { Chapter } from './detailsFormat';
import './details.css';

/** The app-wide backdrop of the Tally look: the picture top-right, fading out to the left and the bottom. */
export function Backdrop(props: { url: string | null }) {
  if (props.url === null) return null;
  return (
    <div class="detail-backdrop">
      <img key={props.url} src={props.url} alt="" />
    </div>
  );
}

export function Clock() {
  const [now, setNow] = useState(new Date());
  useEffect(() => {
    const t = window.setInterval(() => setNow(new Date()), 10_000);
    return () => window.clearInterval(t);
  }, []);
  return <div class="detail-clock">{formatTime(now)}</div>;
}

/** Scrolled sections fade out under this band at the top (64dp). */
export const TOP_SCRIM = 102;

export function TopScrim(props: { visible: boolean }) {
  return props.visible ? <div class="detail-top-scrim" /> : null;
}

/** LOADING… / an error, centered (the page's focus stays on the page itself, so BACK and LEFT keep working). */
export function PageMessage(props: { title: string; body?: string | null; failure?: boolean }) {
  return (
    <div class="detail-message">
      <div class={props.failure === true ? 'empty-state' : 'loading mono-label'}>
        {props.failure === true ? [<div key="t" class="title">{props.title}</div>, <div key="b" class="body">{props.body ?? ''}</div>] : tallyUppercase(props.title)}
      </div>
    </div>
  );
}

/**
 * The row of TallyButtons under a header: one focus group; coming back to it restores the button focused last, the
 * primary one the first time (Android's focusRestorer(primary)).
 */
export function ActionRow(props: { focusKey: string; primaryKey: string; children: ComponentChildren }) {
  const f = useFocusable<HTMLDivElement>({ focusKey: props.focusKey, saveLastFocusedChild: true, preferredChildFocusKey: props.primaryKey });
  return (
    <div ref={f.ref} class="detail-actions">
      <FocusGroup focusKey={props.focusKey}>{props.children}</FocusGroup>
    </div>
  );
}

/**
 * Remote keys that act on the focused card or row: PLAY plays it (Android's onPlay), MENU / INFO opens its menu.
 * Register an action per focus key; the handler looks the focused key up.
 */
export function useCardKeys(active: boolean): {
  play: Map<string, () => void>;
  menu: Map<string, () => void>;
} {
  const maps = useRef({ play: new Map<string, () => void>(), menu: new Map<string, () => void>() });
  useKeyHandler((key) => {
    const focused = currentFocusKey();
    if (key === 'play' || key === 'playPause') {
      const action = maps.current.play.get(focused);
      if (action === undefined) return false;
      action();
      return true;
    }
    if (key === 'menu' || key === 'info') {
      const action = maps.current.menu.get(focused);
      if (action === undefined) return false;
      action();
      return true;
    }
    return false;
  }, active);
  return maps.current;
}

/** A person's square portrait (Primary), sized for the card. */
export function personImage(person: { Id?: string; PrimaryImageTag?: string | null }, size: number): string | null {
  if (person.Id == null || person.PrimaryImageTag == null) return null;
  return itemImage(person.Id, ImageType.Primary, person.PrimaryImageTag, size, size);
}

const CHAPTER_W = 371;
const CHAPTER_H = 209;

export function chapterImage(itemId: string, chapter: Chapter): string | null {
  if (chapter.imageTag === null) return null;
  return getImageApi(currentApi()).getItemImageUrlById(itemId, ImageType.Chapter, {
    tag: chapter.imageTag,
    imageIndex: chapter.index,
    fillWidth: CHAPTER_W,
    fillHeight: CHAPTER_H,
    quality: 90,
  });
}

/** The trailers a TRAILER button offers: local ones play in the player; remote (YouTube) ones only in a browser. */
export function trailerList(item: BaseItemDto, local: readonly BaseItemDto[]): Trailer[] {
  const out: Trailer[] = local.map((t) => ({ kind: 'local' as const, item: t }));
  if (app.platform.name === 'browser') {
    for (const url of item.RemoteTrailers ?? []) if (url.Url != null && url.Url !== '') out.push({ kind: 'remote', url });
  }
  return out;
}

/** The shared scroll area of a detail page: header at the top, rows revealed with the least movement. */
export function DetailScroll(props: { children: ComponentChildren; onScrolled: (scrolled: boolean) => void }) {
  return (
    <div class="detail-scroll">
      <ScrollPage revealMode="nearest" nearestBefore={TOP_SCRIM} nearestAfter={38} onScrollChange={(top) => props.onScrolled(top > 0)}>
        {props.children}
      </ScrollPage>
    </div>
  );
}

/** Scrolls the page back to its top while one of the header's buttons has focus. */
export function useHeaderReveal(): () => void {
  const page = usePageScroll();
  return () => page.toTop();
}

