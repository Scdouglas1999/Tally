/**
 * The RedZone channel on the games board (2.3 contract): a tile first in the live row while the server's RedZone
 * channel is on the air, and the large panel the tile brings up when focused. OK plays the channel full screen, as any
 * channel; the panel says what it is: one stream that cuts to the hottest game, lighter than multiview.
 */
import { redZoneReasonLabel, type TallyChannel, type TallyGame, type TallyRedZone } from '../../api/tallyModels';
import { useFocusable } from '../../focus/focus';
import { IndicatorSquare, LabelBar } from '../../kit/Bits';
import { useRowReveal } from '../../kit/MediaRow';
import { KeyHint, matchupTitle } from '../../sports/SportsBits';
import { tallyUppercase } from '../../util/format';

/** The tile's id where the board keeps the focused game's (a game id is never this). */
export const REDZONE_TILE_ID = 'redzone';
export const REDZONE_TILE_KEY = 'sg-' + REDZONE_TILE_ID;

/** The channel and what it shows now: the board's RedZone tile and panel draw from it. */
export interface RedZoneOnAir {
  channel: TallyChannel;
  status: TallyRedZone;
}

/** The game on RedZone now, as the board names it ("Chiefs at Bills"), else as the server does. */
export function redZoneTitle(status: TallyRedZone, games: readonly TallyGame[]): string {
  const game = status.gameId !== null ? games.find((g) => g.id === status.gameId) : undefined;
  if (game !== undefined && (game.away.shortName !== '' || game.away.abbr !== '')) return matchupTitle(game);
  return status.title ?? '';
}

/** Why RedZone is on that game ("RED ZONE", "SCORE"), never while scores are hidden (a cut for a score tells one). */
export function redZoneReason(status: TallyRedZone, hideScores: boolean): string {
  return hideScores ? '' : redZoneReasonLabel(status.reason);
}

const NAME = 'Tally Pulse';

/** The tile (a game card's size and frame): TALLY PULSE · LIVE, what is on now and why, the channel's bar. */
export function RedZoneCard(props: { onAir: RedZoneOnAir; games: readonly TallyGame[]; hideScores: boolean; onWatch: () => void; onFocus: () => void }) {
  const reveal = useRowReveal();
  const f = useFocusable<HTMLDivElement>({
    focusKey: REDZONE_TILE_KEY,
    onEnter: props.onWatch,
    onFocus: () => {
      if (f.ref.current !== null) reveal(f.ref.current);
      props.onFocus();
    },
  });
  const title = redZoneTitle(props.onAir.status, props.games);
  const reason = redZoneReason(props.onAir.status, props.hideScores);
  return (
    <div ref={f.ref} class="game-card redzone-card" data-game={REDZONE_TILE_ID} onClick={props.onWatch}>
      <div class="game-body">
        <div class="strip">
          <span class="league mono-label ellipsis rz-name">{tallyUppercase(NAME)}</span>
          <span class="status mono-label" style={{ color: 'var(--accent)' }}>
            LIVE
          </span>
        </div>
        <div class="rz-now">
          <div class="rz-kicker mono-label ellipsis">ON NOW</div>
          <div class="rz-title clamp-2">{title !== '' ? title : 'The hottest game'}</div>
          {reason !== '' ? <span class="rec-tag live rz-reason">{reason}</span> : null}
        </div>
      </div>
      <LabelBar text={props.onAir.channel.name !== '' ? props.onAir.channel.name : NAME} live={true}>
        <span class="trailing mono-label">ONE STREAM</span>
      </LabelBar>
    </div>
  );
}

/** The focused tile's panel: what RedZone shows now and why, what comes next, and why it is the lighter option. */
export function RedZonePanel(props: { onAir: RedZoneOnAir; games: readonly TallyGame[]; hideScores: boolean }) {
  const { status } = props.onAir;
  const title = redZoneTitle(status, props.games);
  const reason = redZoneReason(status, props.hideScores);
  const next = status.next
    .map((id) => props.games.find((g) => g.id === id))
    .filter((g): g is TallyGame => g !== undefined)
    .slice(0, 2)
    .map(matchupTitle);
  return (
    <div class="hero-panel redzone-panel">
      <div class="hero-main">
        <div class="hero-left">
          <div class="hero-kicker mono-label-large live">
            <IndicatorSquare tone="live" />
            <span class="ellipsis">{tallyUppercase(NAME + ' · Live')}</span>
          </div>
          <div class="rz-hero-on mono-label">ON NOW</div>
          <div class="rz-hero-title clamp-2">{title !== '' ? title : 'The hottest game'}</div>
          {reason !== '' ? <div class="rz-hero-reason">{reason}</div> : null}
        </div>
        <div class="hero-right">
          <div class="hero-heading mono-label">ONE STREAM, EVERY BIG MOMENT</div>
          <div class="hero-body">
            Cuts full screen to the hottest game: red zones, scores, two-minute drills, overtime. One stream instead of several, so it is
            lighter than multiview.
          </div>
          {next.length > 0 ? <div class="hero-body muted ellipsis">Up next: {next.join(' · ')}</div> : null}
        </div>
      </div>
      <div class="watch-bar">
        <IndicatorSquare tone="live" />
        <span class="channel mono-label ellipsis">{tallyUppercase(props.onAir.channel.name !== '' ? props.onAir.channel.name : NAME)}</span>
        <KeyHint keyName="OK" label="Watch" />
      </div>
    </div>
  );
}
