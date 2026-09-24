import type { ComponentChildren } from 'preact';
import { useLayoutEffect, useRef, useState } from 'preact/hooks';
import { useFocusable } from '../focus/focus';
import { tallyUppercase } from '../util/format';
import './detailHeader.css';

/** One part of the mono meta line; `boxed` is the official rating in its hairline box. */
export interface DetailMetaPart {
  text: string;
  boxed?: boolean;
}

/**
 * The meta line, one line. When it does not fit, the critic score (`RT 87%`) is dropped first, as on Android; the
 * rest is cut at the column's edge.
 */
function MetaLine(props: { parts: DetailMetaPart[] }) {
  const ref = useRef<HTMLDivElement>(null);
  const [dropCritic, setDropCritic] = useState(false);
  const key = props.parts.map((p) => p.text).join('|');
  useLayoutEffect(() => {
    setDropCritic(false);
  }, [key]);
  useLayoutEffect(() => {
    const el = ref.current;
    if (el === null || dropCritic) return;
    if (el.scrollWidth > el.clientWidth + 1 && props.parts.some((p) => p.text.indexOf('RT ') === 0)) setDropCritic(true);
  });
  const shown = dropCritic ? props.parts.filter((p) => p.text.indexOf('RT ') !== 0) : props.parts;
  return (
    <div ref={ref} class="dh-meta mono-label">
      {shown.map((p, i) => [
        i > 0 ? (
          <span key={'s' + String(i)} class="sep">
            ·
          </span>
        ) : null,
        <span key={'p' + String(i)} class={p.boxed === true ? 'boxed' : undefined}>
          {tallyUppercase(p.text)}
        </span>,
      ])}
    </div>
  );
}

/**
 * The overview, 4 lines. Focusable (the amber frame) only when it is cut, and OK then opens all of it
 * (`onOpen`), as on Android.
 */
function Overview(props: { text: string; focusKey: string; onOpen: () => void; onFocus?: () => void }) {
  const [cut, setCut] = useState(false);
  const f = useFocusable<HTMLDivElement>({ focusKey: props.focusKey, focusable: cut, onEnter: props.onOpen, onFocus: props.onFocus });
  useLayoutEffect(() => {
    const el = f.ref.current;
    if (el !== null) setCut(el.scrollHeight > el.clientHeight + 1);
  }, [props.text]);
  return (
    <div ref={f.ref} class="dh-overview" onClick={cut ? props.onOpen : undefined}>
      {props.text}
    </div>
  );
}

/**
 * The top of every detail page (tally/UI.md DetailHeader): kicker, logo or title, the mono meta line with the
 * rating box, ENDS, genres, tagline, overview, director, the tech boxes, then the action row (children).
 * The backdrop is drawn by the page; this draws only the scrim that keeps the column readable on it.
 */
export function DetailHeader(props: {
  kicker: string;
  /** Episode pages: the series and code in accent. */
  kickerAccent?: boolean;
  title: string;
  logoUrl?: string | null;
  meta: DetailMetaPart[];
  ends?: string | null;
  genres?: string[];
  tagline?: string | null;
  overview?: string | null;
  /** Focus key of the overview (focusable only when cut). */
  overviewKey: string;
  onOverview: () => void;
  onOverviewFocus?: () => void;
  director?: string | null;
  tech?: string[];
  /** Width of the text column (480dp; series 560dp). */
  textWidth?: number;
  children?: ComponentChildren;
}) {
  const [logoFailed, setLogoFailed] = useState<string | null>(null);
  const logo = props.logoUrl != null && logoFailed !== props.logoUrl ? props.logoUrl : null;
  const genres = props.genres ?? [];
  const tech = props.tech ?? [];
  return (
    <div class="detail-header">
      <div class="dh-text" style={{ maxWidth: `${props.textWidth ?? 768}px` }}>
        <div class={'dh-kicker mono-label ellipsis' + (props.kickerAccent === true ? ' accent' : '')}>{tallyUppercase(props.kicker)}</div>
        {logo !== null ? (
          <div class="dh-logo">
            <img src={logo} alt={props.title} onError={() => setLogoFailed(logo)} />
          </div>
        ) : (
          <div class="dh-title clamp-2">{props.title}</div>
        )}
        {props.meta.length > 0 ? <MetaLine parts={props.meta} /> : null}
        {props.ends != null && props.ends !== '' ? <div class="dh-ends mono-label">{tallyUppercase(props.ends)}</div> : null}
        {genres.length > 0 ? <div class="dh-genres clamp-2">{genres.join(' / ')}</div> : null}
        {props.tagline != null && props.tagline.trim() !== '' ? <div class="dh-tagline clamp-2">{props.tagline}</div> : null}
        {props.overview != null && props.overview.trim() !== '' ? (
          <Overview key={props.overviewKey} text={props.overview} focusKey={props.overviewKey} onOpen={props.onOverview} onFocus={props.onOverviewFocus} />
        ) : null}
        {props.director != null && props.director !== '' ? <div class="dh-director clamp-2">{props.director}</div> : null}
        {tech.length > 0 ? (
          <div class="dh-tech">
            {tech.map((t, i) => (
              <span key={String(i)} class="box">
                {t}
              </span>
            ))}
          </div>
        ) : null}
      </div>
      {props.children !== undefined ? <div class="dh-actions">{props.children}</div> : null}
    </div>
  );
}
