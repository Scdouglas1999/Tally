/**
 * The Android TV player controls (tally/ui/player/controls: TallyPlaybackOverlay, TallySeekBar, TallyControls,
 * TallyTrickplay) on the 1080p canvas: the title block and clock on the picture, and the bottom band with the Tally
 * seek bar (trickplay preview while it is focused), the times, and the button row. Also the small pieces drawn while
 * the controls are hidden: the PAUSED label, the minimal D-pad seek bar and the sleep timer chip.
 */
import type { ChapterInfo } from '@jellyfin/sdk/lib/generated-client/models/chapter-info';
import type { TrickplayInfoDto } from '@jellyfin/sdk/lib/generated-client/models/trickplay-info-dto';
import { useLayoutEffect, useRef, useState } from 'preact/hooks';
import type { ComponentChildren } from 'preact';
import { FocusGroup, useFocusable } from '../../focus/focus';
import { GlyphIcon } from '../../kit/Bits';
import type { GlyphName } from '../../kit/glyphs';
import { formatTime, tallyUppercase } from '../../util/format';
import * as F from './playerFormat';

/** The band's inner width: the canvas less the page margins (48dp x 1.6 each side). */
export const TRACK_WIDTH = 1920 - 2 * 77;
const SCRUBBER = 19;
const SCRUBBER_FOCUSED = 26;
/** Trickplay thumbnail height (126dp). */
const THUMB_H = 202;
/** Gap between the preview and the track (14dp). */
const PREVIEW_GAP = 22;

/** Kicker, title and meta line top-left, the clock top-right, over a soft top-only scrim. */
export function TopBlock(props: { kicker: string | null; title: string | null; meta: string | null; now: Date }) {
  return (
    <div class="pc-top">
      <div class="pc-top-text">
        {props.kicker !== null ? <div class="kicker mono-label ellipsis">{props.kicker}</div> : null}
        {props.title !== null ? <div class="title ellipsis">{props.title}</div> : null}
        {props.meta !== null ? <div class="meta mono-label ellipsis">{props.meta}</div> : null}
      </div>
      <div class="pc-clock">{tallyUppercase(formatTime(props.now))}</div>
    </div>
  );
}

/** The bottom band: `ground` at 85% with a hairline top edge, content on the page margins. */
export function BottomBand(props: { children: ComponentChildren; class?: string }) {
  return <div class={'pc-band' + (props.class !== undefined ? ' ' + props.class : '')}>{props.children}</div>;
}

/** The track, buffered and played spans, chapter ticks and the square scrubber (TrackCanvas). */
export function Track(props: { progress: number; buffered: number; ticks: number[]; scrubber: number; height: number }) {
  const center = F.scrubberCenter(props.progress, TRACK_WIDTH, props.scrubber);
  const top = (props.height - 6) / 2;
  return (
    <div class="pc-track" style={{ height: `${props.height}px` }}>
      <div class="line" style={{ top: `${top}px` }} />
      <div class="buffered" style={{ top: `${top}px`, width: `${Math.round(TRACK_WIDTH * props.buffered)}px` }} />
      {props.progress > 0 ? <div class="played" style={{ top: `${top}px`, width: `${Math.round(center)}px` }} /> : null}
      {props.ticks
        .filter((t) => t > 0 && t < 1)
        .map((t) => (
          <div key={t} class="tick" style={{ left: `${Math.round(TRACK_WIDTH * t - 1)}px`, top: `${(props.height - 16) / 2}px` }} />
        ))}
      <div
        class="scrubber"
        style={{
          left: `${Math.round(center - props.scrubber / 2)}px`,
          top: `${(props.height - props.scrubber) / 2}px`,
          width: `${props.scrubber}px`,
          height: `${props.scrubber}px`,
        }}
      />
    </div>
  );
}

/**
 * Places `children` above the track, centered on `fraction` of the width and kept inside it, `gap` above it; takes
 * no height itself (PreviewAbove).
 */
function PreviewAbove(props: { fraction: number; gap: number; children: ComponentChildren }) {
  const ref = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(0);
  useLayoutEffect(() => {
    const w = ref.current?.offsetWidth ?? 0;
    if (w !== width) setWidth(w);
  });
  const x = Math.max(0, Math.min(TRACK_WIDTH - width, Math.round(TRACK_WIDTH * props.fraction - width / 2)));
  return (
    <div class="pc-above">
      <div ref={ref} class="pc-above-content" style={{ left: `${x}px`, bottom: `${props.gap}px` }}>
        {props.children}
      </div>
    </div>
  );
}

/**
 * The seek preview: the trickplay thumbnail for the position in a hairline frame, the time in a black mono label bar
 * under it and, inside a chapter, the chapter's title in muted. Without trickplay only the label bar.
 */
export function TrickplayPreview(props: { positionMs: number; info: TrickplayInfoDto | null; sheetUrl: (sheet: number) => string; chapterName: string | null }) {
  const tile = props.info !== null ? F.trickplayTile(props.positionMs, props.info) : null;
  const info = props.info;
  let thumb = null;
  if (tile !== null && info !== null) {
    const w = info.Width ?? 1;
    const h = info.Height ?? 1;
    const scale = THUMB_H / h;
    const thumbW = Math.round(w * scale);
    thumb = (
      <div
        class="thumb"
        style={{
          width: `${thumbW}px`,
          height: `${THUMB_H}px`,
          backgroundImage: `url("${props.sheetUrl(tile.sheet)}")`,
          backgroundSize: `${Math.round(w * (info.TileWidth ?? 1) * scale)}px ${Math.round(h * (info.TileHeight ?? 1) * scale)}px`,
          backgroundPosition: `${-Math.round(tile.column * w * scale)}px ${-Math.round(tile.row * h * scale)}px`,
        }}
      />
    );
  }
  return (
    <div class="pc-preview">
      {thumb}
      <div class="bar">
        <span class="time">{F.clock(props.positionMs)}</span>
        {props.chapterName !== null && props.chapterName.trim() !== '' ? <span class="chapter ellipsis">{props.chapterName}</span> : null}
      </div>
    </div>
  );
}

export interface SeekBarProps {
  positionMs: number;
  durationMs: number;
  bufferedMs: number;
  /** The viewer's target while moving the scrubber (null: following playback). */
  targetMs: number | null;
  chapters: readonly ChapterInfo[];
  trickplay: TrickplayInfoDto | null;
  sheetUrl: (sheet: number) => string;
  focused: boolean;
  onArrow: (direction: 'left' | 'right') => void;
  onFocusChange: (focused: boolean) => void;
}

/**
 * The Tally seek bar: 4dp track (`rule`), buffered in `muted`, played in `text`, chapter ticks, a 12dp accent square
 * scrubber (16dp while focused). LEFT/RIGHT while focused move the target (the page seeks 750 ms after the last
 * move, as upstream's seek bar); the trickplay preview rides above the scrubber.
 */
export function SeekBar(props: SeekBarProps) {
  const f = useFocusable<HTMLDivElement>({
    focusKey: 'pc-seek',
    onArrow: (d) => {
      if (d === 'left' || d === 'right') {
        props.onArrow(d);
        return false;
      }
      return true;
    },
    onFocus: () => props.onFocusChange(true),
    onBlur: () => props.onFocusChange(false),
  });
  const shown = props.targetMs ?? props.positionMs;
  const starts = props.chapters.map((c) => F.ticksToMs(c.StartPositionTicks));
  const chapterIndex = F.chapterAt(starts, shown);
  const chapterName = chapterIndex !== null ? (props.chapters[chapterIndex]?.Name ?? null) : null;
  return (
    <div class="pc-seekwrap">
      {props.focused ? (
        <PreviewAbove fraction={F.fraction(shown, props.durationMs)} gap={PREVIEW_GAP}>
          <TrickplayPreview positionMs={shown} info={props.trickplay} sheetUrl={props.sheetUrl} chapterName={chapterName} />
        </PreviewAbove>
      ) : null}
      <div ref={f.ref} class="pc-seek">
        <Track
          progress={F.fraction(shown, props.durationMs)}
          buffered={F.fraction(props.bufferedMs, props.durationMs)}
          ticks={starts.map((s) => F.fraction(s, props.durationMs))}
          scrubber={props.focused ? SCRUBBER_FOCUSED : SCRUBBER}
          height={32}
        />
      </div>
    </div>
  );
}

/** Elapsed at the left; time left and the end time at the right (`-34:28 · ENDS 9:41 PM`). */
export function Times(props: { positionMs: number; durationMs: number; speed: number; now: Date }) {
  return (
    <div class="pc-times">
      <span>{F.clock(props.positionMs)}</span>
      {props.durationMs > 0 ? (
        <span class="right">
          {F.remaining(props.positionMs, props.durationMs) + ' · ' + F.endsAt(props.now, F.remainingRealMs(props.positionMs, props.durationMs, props.speed))}
        </span>
      ) : null}
    </div>
  );
}

/**
 * A square glyph button (PlayerIconButton): hairline border, focused 4px accent; `primary` is accent-filled with a
 * `text` frame when focused. Its label shows under it while focused, centered, or lined up with its left or right
 * edge for the first and last button of the row (`caption`).
 */
export function PlayerIconButton(props: {
  focusKey: string;
  glyph: GlyphName;
  label: string;
  onPress: () => void;
  primary?: boolean;
  caption?: 'center' | 'start' | 'end';
  onArrow?: (direction: string) => boolean;
  onFocus?: () => void;
}) {
  const f = useFocusable<HTMLDivElement>({
    focusKey: props.focusKey,
    onEnter: props.onPress,
    onArrow: props.onArrow,
    onFocus: props.onFocus,
  });
  return (
    <div class="pc-btnwrap">
      <div ref={f.ref} class={'pc-btn' + (props.primary === true ? ' primary' : '')} onClick={props.onPress}>
        <GlyphIcon name={props.glyph} />
      </div>
      <div class={'pc-cap ' + (props.caption ?? 'center')}>{tallyUppercase(props.label)}</div>
    </div>
  );
}

export interface ControlsRowProps {
  playing: boolean;
  hasChapters: boolean;
  hasNext: boolean;
  skipLabel: string | null;
  onChapters: () => void;
  onQueue: () => void;
  onPrevious: () => void;
  onRewind: () => void;
  onPlayPause: () => void;
  onForward: () => void;
  onNext: () => void;
  onSkip: () => void;
  onSubtitles: () => void;
  onAudio: () => void;
  onSettings: () => void;
  /** DOWN from any button: the chapter row, else the queue row; false when there is neither. */
  onDown: () => boolean;
  onFocus: () => void;
}

function Group(props: { focusKey: string; class: string; children: ComponentChildren }) {
  const g = useFocusable<HTMLDivElement>({ focusKey: props.focusKey, saveLastFocusedChild: true });
  return (
    <div ref={g.ref} class={'pc-group ' + props.class}>
      <FocusGroup focusKey={g.focusKey}>{props.children}</FocusGroup>
    </div>
  );
}

/**
 * The row under the seek bar: chapters and queue at the left; previous, rewind, play/pause, fast-forward, next in
 * the center; the skip-segment button, subtitles, audio and settings at the right.
 */
export function ControlsRow(p: ControlsRowProps) {
  const row = useFocusable<HTMLDivElement>({ focusKey: 'pc-row', saveLastFocusedChild: true, preferredChildFocusKey: 'pc-center' });
  const arrow = (d: string): boolean => (d === 'down' ? !p.onDown() : true);
  const common = { onArrow: arrow, onFocus: p.onFocus };
  const skipBtn = useFocusable<HTMLDivElement>({ focusKey: 'pc-skip', onEnter: p.onSkip, onArrow: arrow, onFocus: p.onFocus, focusable: p.skipLabel !== null });
  return (
    <div ref={row.ref} class="pc-row">
      <FocusGroup focusKey={row.focusKey}>
        <Group focusKey="pc-left" class="left">
          {p.hasChapters ? <PlayerIconButton focusKey="pc-chapters" glyph="bookmark" label="Chapters" caption="start" onPress={p.onChapters} {...common} /> : null}
          {p.hasNext ? (
            <PlayerIconButton focusKey="pc-queue" glyph="list" label="Queue" caption={p.hasChapters ? 'center' : 'start'} onPress={p.onQueue} {...common} />
          ) : null}
        </Group>
        <Group focusKey="pc-center" class="center">
          <PlayerIconButton focusKey="pc-previous" glyph="stepBackward" label="Previous" onPress={p.onPrevious} {...common} />
          <PlayerIconButton focusKey="pc-rewind" glyph="backward" label="Rewind" onPress={p.onRewind} {...common} />
          <PlayerIconButton focusKey="pc-play" glyph={p.playing ? 'pause' : 'play'} label={p.playing ? 'Pause' : 'Play'} primary onPress={p.onPlayPause} {...common} />
          <PlayerIconButton focusKey="pc-forward" glyph="forward" label="Fast-forward" onPress={p.onForward} {...common} />
          {p.hasNext ? <PlayerIconButton focusKey="pc-next" glyph="stepForward" label="Next" onPress={p.onNext} {...common} /> : null}
        </Group>
        <Group focusKey="pc-right" class="right">
          {/* the skip button's element stays mounted (focus registration); it is shown inside a segment only */}
          <div ref={skipBtn.ref} class="btn pc-skipbtn" style={{ display: p.skipLabel !== null ? '' : 'none' }} onClick={p.onSkip}>
            <span>{tallyUppercase(p.skipLabel ?? '')}</span>
          </div>
          <PlayerIconButton focusKey="pc-subtitles" glyph="captions" label="Subtitles" onPress={p.onSubtitles} {...common} />
          <PlayerIconButton focusKey="pc-audio" glyph="volume" label="Audio" onPress={p.onAudio} {...common} />
          <PlayerIconButton focusKey="pc-settings" glyph="gear" label="Settings" caption="end" onPress={p.onSettings} {...common} />
        </Group>
      </FocusGroup>
    </div>
  );
}

/** While paused with the controls hidden: a small PAUSED label bar at the top center. */
export function PausedLabel() {
  return (
    <div class="pc-paused">
      <span class="mono-label">PAUSED</span>
    </div>
  );
}

/**
 * D-pad seeking with the controls hidden (TallyDpadSeekMinimal): the track with the scrubber at the target and the
 * target time on a black label bar riding above it, at the bottom of the picture.
 */
export function DpadSeekMinimal(props: { positionMs: number; durationMs: number }) {
  const fraction = F.fraction(props.positionMs, props.durationMs);
  return (
    <div class="pc-minimal">
      <PreviewAbove fraction={fraction} gap={6}>
        <div class="pc-minimal-time">{F.clock(props.positionMs)}</div>
      </PreviewAbove>
      <Track progress={fraction} buffered={0} ticks={[]} scrubber={SCRUBBER} height={SCRUBBER} />
    </div>
  );
}

/** `SLEEP 23:10` (amber in the last minute), or `SLEEP END` while waiting for the end. */
export function SleepChip(props: { remainingMs: number }) {
  const urgent = F.sleepUrgent(props.remainingMs);
  const label = isFinite(props.remainingMs) ? 'SLEEP ' + F.sleepClock(props.remainingMs) : 'SLEEP END';
  return <div class={'pc-sleep' + (urgent ? ' urgent' : '')}>{label}</div>;
}
