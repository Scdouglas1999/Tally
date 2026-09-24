import { useState } from 'preact/hooks';
import { useFocusable, type FocusableOptions } from '../focus/focus';
import { GlyphIcon } from './Bits';
import './episodeRow.css';

/** Status at the right end of a rundown row. */
export type EpisodeStatus = { kind: 'none' } | { kind: 'nextUp' } | { kind: 'progress'; percent: number };

/**
 * One line of a season rundown (media/series/EpisodeRow.kt): 16:9 still (progress at its foot, a WATCHED tick),
 * the number (accent when next up), title, mono meta (with `ENDS …` while focused), a 2-line overview, and the
 * status (NEXT UP box, percent). Idle rows have no fill; the focused row is groundRaised with the amber frame.
 */
export function EpisodeRow(props: {
  number: string | null;
  title: string;
  meta: string;
  focusedMeta?: string | null;
  overview?: string | null;
  imageUrl: string | null;
  /** 0–1, drawn only between 0 and 1. */
  progress: number | null;
  played: boolean;
  nextUp: boolean;
  status: EpisodeStatus;
  onPress: () => void;
  onFocus?: () => void;
} & Pick<FocusableOptions, 'focusKey' | 'onArrow'>) {
  const f = useFocusable<HTMLDivElement>({
    focusKey: props.focusKey,
    onEnter: props.onPress,
    onArrow: props.onArrow,
    onFocus: props.onFocus,
    trackFocus: true,
  });
  const [failed, setFailed] = useState<string | null>(null);
  const meta = f.focused && props.focusedMeta != null && props.focusedMeta !== '' ? [props.meta, props.focusedMeta].filter((m) => m !== '').join(' · ') : props.meta;
  const showNextUp = props.status.kind === 'nextUp' || (props.nextUp && props.status.kind === 'progress');
  return (
    <div ref={f.ref} class="episode-row" onClick={props.onPress}>
      <div class="still">
        {props.imageUrl !== null && failed !== props.imageUrl ? <img src={props.imageUrl} alt="" onError={() => setFailed(props.imageUrl)} /> : null}
        {props.progress !== null && props.progress > 0 && props.progress < 1 ? (
          <div class="progress">
            <div style={{ width: `${Math.round(props.progress * 1000) / 10}%` }} />
          </div>
        ) : null}
        {props.played ? (
          <span class="watched">
            <GlyphIcon name="check" />
            <span>WATCHED</span>
          </span>
        ) : null}
      </div>
      <div class={'number' + (props.nextUp ? ' next' : '')}>{props.number ?? ''}</div>
      <div class="texts">
        <div class="title ellipsis">{props.title}</div>
        {meta !== '' ? <div class="meta ellipsis">{meta}</div> : null}
        {props.overview != null && props.overview.trim() !== '' ? <div class="overview clamp-2">{props.overview}</div> : null}
      </div>
      <div class="status">
        {showNextUp ? <span class="next-up">NEXT UP</span> : null}
        {props.status.kind === 'progress' ? <span class="percent">{`${props.status.percent}%`}</span> : null}
      </div>
    </div>
  );
}
