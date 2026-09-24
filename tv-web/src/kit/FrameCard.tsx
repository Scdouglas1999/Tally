import { useState } from 'preact/hooks';
import { useFocusable, type FocusableOptions } from '../focus/focus';
import { tallyUppercase } from '../util/format';
import { GlyphIcon } from './Bits';
import type { GlyphName } from './glyphs';
import { useRowReveal } from './MediaRow';
import './frameCard.css';

/**
 * The card frame (Android CardFrame) for anything that is not a plain library item: chapters, extras, seasons,
 * people, episodes of a season. Picture on `screen`, optional progress along its foot, a corner tag (with a glyph),
 * the favorite square, and the black label bar: accent kicker, title, muted detail. Focus = the amber frame.
 */
export function FrameCard(props: {
  width: number;
  height: number;
  imageUrl: string | null;
  title: string;
  kicker?: string | null;
  detail?: string | null;
  /** 0–1; drawn only between 0 and 1. */
  progress?: number | null;
  tag?: string | null;
  tagAccent?: boolean;
  tagGlyph?: GlyphName;
  favorite?: boolean;
  /** Extra class on the card ('person' for the smaller name). */
  class?: string;
  /** The title in the picture's place when there is no picture (default); false leaves it blank. */
  fallbackTitle?: boolean;
  onPress: () => void;
  onFocus?: () => void;
} & Pick<FocusableOptions, 'focusKey' | 'onArrow'>) {
  const reveal = useRowReveal();
  const f = useFocusable<HTMLDivElement>({
    focusKey: props.focusKey,
    onEnter: props.onPress,
    onArrow: props.onArrow,
    onFocus: () => {
      if (f.ref.current !== null) reveal(f.ref.current);
      props.onFocus?.();
    },
  });
  const [failed, setFailed] = useState<string | null>(null);
  const progress = props.progress ?? null;
  return (
    <div ref={f.ref} class={'card frame-card' + (props.class !== undefined ? ' ' + props.class : '')} style={{ width: `${props.width}px` }} onClick={props.onPress}>
      <div class="art" style={{ height: `${props.height}px` }}>
        {props.imageUrl !== null && failed !== props.imageUrl ? (
          <img src={props.imageUrl} alt="" onError={() => setFailed(props.imageUrl)} />
        ) : props.fallbackTitle !== false ? (
          <div class="art-fallback">{props.title}</div>
        ) : null}
        {progress !== null && progress > 0 && progress < 1 ? (
          <div class="progress">
            <div style={{ width: `${Math.round(progress * 1000) / 10}%` }} />
          </div>
        ) : null}
        {props.tag != null ? (
          <span class={'tag' + (props.tagAccent === true ? ' accent' : '')}>
            {props.tagGlyph !== undefined ? <GlyphIcon name={props.tagGlyph} class="tag-glyph" /> : null}
            {tallyUppercase(props.tag)}
          </span>
        ) : null}
        {props.favorite === true ? <span class="favorite" /> : null}
      </div>
      <div class="bar">
        {props.kicker != null && props.kicker !== '' ? <div class="kicker ellipsis">{tallyUppercase(props.kicker)}</div> : null}
        {props.title !== '' ? <div class="title ellipsis">{props.title}</div> : null}
        {props.detail != null && props.detail !== '' ? <div class="detail ellipsis">{tallyUppercase(props.detail)}</div> : null}
      </div>
    </div>
  );
}

/** Person card size: 104dp square (Android PersonCard). */
export const PERSON_CARD = 166;

/** PersonCard: a square portrait, the name over a muted role. No circles. */
export function PersonCard(props: {
  name: string;
  role: string | null;
  imageUrl: string | null;
  onPress: () => void;
  onFocus?: () => void;
} & Pick<FocusableOptions, 'focusKey' | 'onArrow'>) {
  return (
    <FrameCard
      focusKey={props.focusKey}
      onArrow={props.onArrow}
      width={PERSON_CARD}
      height={PERSON_CARD}
      imageUrl={props.imageUrl}
      title={props.name}
      detail={props.role}
      class="person"
      fallbackTitle={false}
      onPress={props.onPress}
      onFocus={props.onFocus}
    />
  );
}
