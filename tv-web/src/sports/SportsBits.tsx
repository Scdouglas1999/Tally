import type { ComponentChildren } from 'preact';
import type { TallyGame, TallyGameRecording } from '../api/tallyModels';
import { useFocusable, type FocusableOptions } from '../focus/focus';
import { IndicatorSquare } from '../kit/Bits';
import { TeamMark } from './GameCard';
import { COMPACT, FULL, fittedColumnWidth, isLeading, periodColumnCount, periodLabels, periodValue } from './lineScore';
import './sportsKit.css';

/** A bordered mono key cap ("OK", "HOLD") followed by a label (KeyHint.kt). */
export function KeyHint(props: { keyName: string; label: string }) {
  return (
    <span class="key-hint">
      <span class="cap">{props.keyName.toUpperCase()}</span>
      {props.label !== '' ? <span class="what">{props.label}</span> : null}
    </span>
  );
}

/** `1 OUT`, `2 OUTS`. */
export function outsLabel(outs: number): string {
  return `${outs} ${outs === 1 ? 'OUT' : 'OUTS'}`;
}

/** Baseball count and outs: `2-1 · 1 OUT`; '' when the feed has neither. */
export function baseballCount(game: TallyGame): string {
  const parts: string[] = [];
  if (game.balls !== null && game.strikes !== null) parts.push(`${game.balls}-${game.strikes}`);
  if (game.outs !== null) parts.push(outsLabel(game.outs));
  return parts.join(' · ');
}

/** The score bug's small line: the status detail plus down & distance (football) or count & outs (baseball). */
export function situationLine(game: TallyGame): string {
  const situation = game.sport === 'football' ? (game.downDistance ?? '') : game.sport === 'baseball' ? baseballCount(game) : '';
  return [game.detail, situation].filter((x) => x !== '').join(' · ');
}

/** The name a line uses for a team: the short name ("Otters"), else the abbreviation, else the full name. */
export function teamName(team: { shortName: string; abbr: string; name: string }): string {
  return team.shortName !== '' ? team.shortName : team.abbr !== '' ? team.abbr : team.name;
}

/** "Otters at Herons". */
export function matchupTitle(game: TallyGame): string {
  return `${teamName(game.away)} at ${teamName(game.home)}`;
}

/**
 * The baseball bases: three 45°-rotated squares, second on top, third left, first right. Occupied = accent fill,
 * empty = 1dp muted outline (BaseballDiamond.kt).
 */
export function BaseballDiamond(props: { onFirst: boolean; onSecond: boolean; onThird: boolean; size: number }) {
  const s = props.size;
  const side = s / 3.4;
  const diag = side * Math.SQRT2;
  const base = (cx: number, cy: number, on: boolean, key: string) => (
    <rect
      key={key}
      x={cx - side / 2}
      y={cy - side / 2}
      width={side}
      height={side}
      transform={`rotate(45 ${cx} ${cy})`}
      fill={on ? 'var(--accent)' : 'none'}
      stroke={on ? 'none' : 'var(--muted)'}
      stroke-width={on ? 0 : 1.6}
    />
  );
  return (
    <svg class="diamond" width={s} height={s} viewBox={`0 0 ${s} ${s}`}>
      {base(s / 2, diag / 2, props.onSecond, '2')}
      {base(diag / 2, s - diag / 2, props.onThird, '3')}
      {base(s - diag / 2, s - diag / 2, props.onFirst, '1')}
    </svg>
  );
}

/**
 * A game's recording tag: `■ REC` in live red, framed in red, while it is being recorded; `REC` outlined while it is
 * scheduled or waiting for a stream; nothing otherwise (RecTag.kt).
 */
export function RecTag(props: { recording: TallyGameRecording | null }) {
  const state = props.recording?.state;
  if (state === undefined) return null;
  const live = state === 'recording';
  if (!live && state !== 'scheduled' && state !== 'waiting') return null;
  return (
    <span class={'rec-tag' + (live ? ' live' : '')}>
      {live ? <IndicatorSquare tone="live" /> : null}
      REC
    </span>
  );
}

/**
 * The line score: a column per period, a row per team, the total at the end. Nothing when neither team has periods.
 * `compact` is the focused-game panel's size; otherwise the box score's. Columns have a fixed width (narrowed to fit
 * `maxWidth`) so the table does not jump as a number goes from 7 to 14.
 */
export function LineScore(props: { game: TallyGame; compact?: boolean; maxWidth?: number }) {
  const { game } = props;
  const played = Math.max(game.away.periods.length, game.home.periods.length);
  if (played === 0) return null;
  const m = props.compact === true ? COMPACT : FULL;
  const labels = periodLabels(game.sport, periodColumnCount(game.sport, played));
  const col = fittedColumnWidth(m, labels.length, props.maxWidth ?? null);
  const gutter = m.mark + m.gap + m.abbrWidth + m.gap;
  const tableWidth = gutter + col * labels.length + m.totalGap + col;
  const cell = (text: string, cls: string, key: string, extra?: Record<string, string>) => (
    <span key={key} class={'ls-cell ' + cls} style={{ width: `${col}px`, ...extra }}>
      {text}
    </span>
  );
  const teamRow = (team: TallyGame['away'], other: TallyGame['away'], key: string) => (
    <div key={key} class="ls-row" style={{ marginTop: `${m.rowGap}px` }}>
      <TeamMark team={team} size={m.mark} />
      <span class="ls-abbr" style={{ width: `${m.abbrWidth}px`, marginLeft: `${m.gap}px`, marginRight: `${m.gap}px` }}>
        {team.abbr.toUpperCase()}
      </span>
      {labels.map((_, i) => cell(periodValue(team.periods, i), i < team.periods.length ? 'played' : 'unplayed', 'p' + String(i)))}
      <span style={{ width: `${m.totalGap}px`, flex: 'none' }} />
      {cell(team.score === null ? '–' : String(team.score), 'total' + (isLeading(team.score, other.score) ? ' leading' : ''), 't')}
    </div>
  );
  return (
    <div class={'line-score' + (props.compact === true ? ' compact' : ' full')} style={{ fontSize: `${m.number}px` }}>
      <div class="ls-head" style={{ fontSize: `${m.header}px` }}>
        <span style={{ width: `${gutter}px`, flex: 'none' }} />
        {labels.map((l, i) => cell(l, 'head', 'h' + String(i)))}
        <span style={{ width: `${m.totalGap}px`, flex: 'none' }} />
        {cell('T', 'head', 'ht')}
      </div>
      <div class="ls-rule" style={{ width: `${tableWidth}px`, marginTop: `${m.ruleGap}px` }} />
      {teamRow(game.away, game.home, 'a')}
      {teamRow(game.home, game.away, 'h')}
    </div>
  );
}

/**
 * Centered title + muted subtitle inside a dashed hairline frame; focusable (it usually replaces the only focusable
 * content), focused = the solid amber frame (EmptyState.kt).
 */
export function EmptyState(props: { title: string; subtitle: string; focusKey?: string; class?: string; onArrow?: FocusableOptions['onArrow'] }) {
  const f = useFocusable<HTMLDivElement>({ focusKey: props.focusKey, onArrow: props.onArrow });
  return (
    <div ref={f.ref} class={'empty-state' + (props.class !== undefined ? ' ' + props.class : '')}>
      <div class="title">{props.title}</div>
      {props.subtitle !== '' ? <div class="subtitle">{props.subtitle}</div> : null}
    </div>
  );
}

/**
 * A full-width focusable row: a label, an optional muted description and a trailing slot (TallyRow.kt). Focused =
 * amber frame on groundRaised. `primary` paints the label amber for the screen's main action.
 */
export function TallyRow(props: {
  label: string;
  onPress: () => void;
  description?: string | null;
  enabled?: boolean;
  primary?: boolean;
  focusKey?: string;
  onFocus?: () => void;
  onArrow?: FocusableOptions['onArrow'];
  children?: ComponentChildren;
}) {
  const enabled = props.enabled !== false;
  const f = useFocusable<HTMLDivElement>({
    focusKey: props.focusKey,
    focusable: enabled,
    onEnter: () => {
      if (enabled) props.onPress();
    },
    onFocus: () => props.onFocus?.(),
    onArrow: props.onArrow,
  });
  return (
    <div
      ref={f.ref}
      class={'tally-row' + (enabled ? '' : ' disabled') + (props.primary === true ? ' primary' : '')}
      onClick={() => enabled && props.onPress()}
    >
      <div class="text">
        <div class="label ellipsis">{props.label}</div>
        {props.description != null && props.description !== '' ? <div class="description clamp-2">{props.description}</div> : null}
      </div>
      {props.children}
    </div>
  );
}

/** A square two-state switch: a hairline square, a filled amber square inside when on (TallySettingsContent.kt). */
export function TallySwitch(props: { checked: boolean }) {
  return <span class={'tally-switch' + (props.checked ? ' on' : '')}>{props.checked ? <span class="dot" /> : null}</span>;
}
