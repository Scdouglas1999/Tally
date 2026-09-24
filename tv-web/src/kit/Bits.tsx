import type { ComponentChildren } from 'preact';
import { Glyph, type GlyphName } from './glyphs';
import { tallyUppercase } from '../util/format';

export function IndicatorSquare(props: { tone?: 'idle' | 'accent' | 'live'; class?: string }) {
  const tone = props.tone ?? 'idle';
  return <span class={'indicator' + (tone === 'idle' ? '' : ' ' + tone) + (props.class !== undefined ? ' ' + props.class : '')} />;
}

export function GlyphIcon(props: { name: GlyphName; class?: string; style?: Record<string, string> }) {
  return (
    <span class={'glyph' + (props.class !== undefined ? ' ' + props.class : '')} style={props.style} aria-hidden="true">
      {Glyph[props.name]}
    </span>
  );
}

/** Mono uppercase title with a muted count ("CONTINUE WATCHING 2"). */
export function RowHeader(props: { title: string; count?: number | null }) {
  return (
    <div class="row-header">
      <span class="title mono-label-large">{tallyUppercase(props.title)}</span>
      {props.count != null ? <span class="count mono-label-large">{props.count}</span> : null}
    </div>
  );
}

/** Black label bar with an indicator square (red while live) and a mono name. */
export function LabelBar(props: { text: string; live?: boolean; children?: ComponentChildren }) {
  return (
    <div class="label-bar">
      <IndicatorSquare tone={props.live === true ? 'live' : 'idle'} />
      <span class="text mono-label ellipsis">{tallyUppercase(props.text)}</span>
      {props.children}
    </div>
  );
}
