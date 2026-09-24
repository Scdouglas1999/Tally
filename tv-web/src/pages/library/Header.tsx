import type { ComponentChildren } from 'preact';
import { useEffect, useState } from 'preact/hooks';
import { FocusGroup, useFocusable } from '../../focus/focus';
import { GlyphIcon } from '../../kit/Bits';
import type { GlyphName } from '../../kit/glyphs';
import { formatTime, tallyUppercase } from '../../util/format';

/** The clock at the top right, as on every Android TV page. */
export function Clock() {
  const [now, setNow] = useState(new Date());
  useEffect(() => {
    const t = window.setInterval(() => setNow(new Date()), 10_000);
    return () => window.clearInterval(t);
  }, []);
  return <div class="lib-clock">{formatTime(now)}</div>;
}

/**
 * The fixed header over the content: the kicker (accent) with the count after it (`MOVIES · 23 FILMS`), then the
 * strip: tabs on the left, controls at the right end, a hairline under both.
 */
export function LibraryHeader(props: { kicker: string; count: string | null; tabs: ComponentChildren; controls: ComponentChildren }) {
  return (
    <div class="lib-head">
      <div class="lib-kicker mono-label">
        <span class="name ellipsis">{tallyUppercase(props.kicker)}</span>
        {props.count !== null ? <span class="count">{' · ' + tallyUppercase(props.count)}</span> : null}
      </div>
      <div class="lib-strip">
        <div class="lib-tabs-slot">{props.tabs}</div>
        <div class="lib-controls-slot">{props.controls}</div>
      </div>
    </div>
  );
}

function Tab(props: { label: string; current: boolean; focusKey: string; onPress: () => void }) {
  const f = useFocusable<HTMLDivElement>({ focusKey: props.focusKey, onEnter: props.onPress });
  return (
    <div ref={f.ref} class={'lib-tab mono-label' + (props.current ? ' current' : '')} onClick={props.onPress}>
      {tallyUppercase(props.label)}
    </div>
  );
}

/** The tab strip (the season rundown's tabs): OK opens a tab; entering the strip lands on the current tab. */
export function LibraryTabs(props: { pageKey: string; labels: string[]; selected: number; onSelect: (i: number) => void }) {
  const groupKey = props.pageKey + '-tabs';
  const tabKey = (i: number): string => `${props.pageKey}-tab-${i}`;
  const group = useFocusable<HTMLDivElement>({ focusKey: groupKey, saveLastFocusedChild: false, preferredChildFocusKey: tabKey(props.selected) });
  return (
    <div ref={group.ref} class="lib-tabs">
      <FocusGroup focusKey={groupKey}>
        {props.labels.map((label, i) => (
          <Tab key={label} label={label} current={i === props.selected} focusKey={tabKey(i)} onPress={() => i !== props.selected && props.onSelect(i)} />
        ))}
      </FocusGroup>
    </div>
  );
}

/** A secondary control in the strip: square, hairline border, mono label (the sort's arrow after it). */
export function ControlButton(props: { label: string; suffix?: string; focusKey: string; onPress: () => void; maxLabel?: number }) {
  const f = useFocusable<HTMLDivElement>({ focusKey: props.focusKey, onEnter: props.onPress });
  return (
    <div ref={f.ref} class="lib-control" onClick={props.onPress}>
      <span class="label ellipsis" style={props.maxLabel !== undefined ? { maxWidth: `${props.maxLabel}px` } : undefined}>
        {tallyUppercase(props.label)}
      </span>
      {props.suffix !== undefined ? <span class="suffix">{props.suffix}</span> : null}
    </div>
  );
}

/**
 * A square glyph control (random, play, shuffle). While focused its label hangs under it as a mono caption;
 * `captionAtEnd` lines the caption up with its right edge (the last control).
 */
export function IconControl(props: { glyph: GlyphName; label: string; focusKey: string; onPress: () => void; enabled: boolean; captionAtEnd?: boolean }) {
  const f = useFocusable<HTMLDivElement>({ focusKey: props.focusKey, onEnter: props.onPress, focusable: props.enabled, trackFocus: true });
  return (
    <div class="lib-icon-slot">
      <div ref={f.ref} class={'lib-icon' + (props.enabled ? '' : ' disabled')} onClick={props.enabled ? props.onPress : undefined}>
        <GlyphIcon name={props.glyph} />
      </div>
      {f.focused ? <div class={'lib-caption mono-label' + (props.captionAtEnd === true ? ' end' : '')}>{tallyUppercase(props.label)}</div> : null}
    </div>
  );
}

/** The controls' focus group (entering it from the grid lands on the nearest control). */
export function ControlsGroup(props: { pageKey: string; children: ComponentChildren }) {
  const key = props.pageKey + '-controls';
  const group = useFocusable<HTMLDivElement>({ focusKey: key, saveLastFocusedChild: false });
  return (
    <div ref={group.ref} class="lib-controls">
      <FocusGroup focusKey={key}>{props.children}</FocusGroup>
    </div>
  );
}

/** Mono `LOADING…` in muted, centered in the content area. */
export function LoadingMark() {
  return <div class="lib-loading mono-label">LOADING…</div>;
}

/** An empty or failed content area: a dashed frame with a title and a muted line (focusable, as on Android). */
export function EmptyState(props: { focusKey: string; title: string; subtitle: string }) {
  const f = useFocusable<HTMLDivElement>({ focusKey: props.focusKey });
  return (
    <div class="lib-empty-area">
      <div ref={f.ref} class="lib-empty">
        <div class="title">{props.title}</div>
        <div class="subtitle">{props.subtitle}</div>
      </div>
    </div>
  );
}
