/**
 * The live player's Tally overlays (ui/player/*.kt): the score bug, the box score (UP), the "also on now" game
 * switcher (DOWN) and event banners for scoring plays in other games.
 */
import { useEffect, useState } from 'preact/hooks';
import { isFollowed, isLive, type TallyEvent, type TallyGame } from '../../api/tallyModels';
import { FocusGroup, useFocusable } from '../../focus/focus';
import { IndicatorSquare } from '../../kit/Bits';
import { MediaRow } from '../../kit/MediaRow';
import { GameCard } from '../../sports/GameCard';
import { ScoreDigits } from '../../sports/ScoreDigits';
import { BaseballDiamond, baseballCount, KeyHint, LineScore, situationLine, teamName } from '../../sports/SportsBits';
import { gameStatusLabel, tallyUppercase } from '../../util/format';
import './live.css';

/**
 * The score bug (ScoreBug.kt): "IND 7 · KC 0" over the clock and situation, top-end of the picture. Nothing unless
 * the game is live and scores are shown. Always drawn while it has a game and faded with opacity, so a score change
 * that brings it back can roll the digits it already showed.
 */
export function ScoreBug(props: { game: TallyGame | null; hideScores: boolean; visible: boolean }) {
  const g = props.game;
  if (g === null || !isLive(g) || props.hideScores) return null;
  const situation = situationLine(g);
  const abbr = (t: TallyGame['away']): string => (t.abbr !== '' ? t.abbr : t.shortName);
  return (
    <div class={'score-bug' + (props.visible ? '' : ' faded')}>
      <div class="line1">
        {g.away.possession ? <IndicatorSquare tone="accent" class="possession" /> : null}
        <span>{abbr(g.away)} </span>
        <ScoreDigits gameId={g.id} score={g.away.score} hidden={false} placeholder="–" class="bug-score" />
        <span> · {abbr(g.home)} </span>
        <ScoreDigits gameId={g.id} score={g.home.score} hidden={false} placeholder="–" class="bug-score" />
      </div>
      {situation !== '' ? <div class="line2 ellipsis">{situation.toUpperCase()}</div> : null}
    </div>
  );
}

function BoxSituation(props: { game: TallyGame }) {
  const { game } = props;
  if (game.sport === 'football') {
    return game.downDistance !== null && game.downDistance !== '' ? <div class="box-situation ellipsis">{game.downDistance.toUpperCase()}</div> : null;
  }
  if (game.sport === 'baseball') {
    const line = baseballCount(game);
    if (line === '' && !game.onFirst && !game.onSecond && !game.onThird) return null;
    return (
      <div class="box-situation baseball">
        <BaseballDiamond onFirst={game.onFirst} onSecond={game.onSecond} onThird={game.onThird} size={48} />
        {line !== '' ? <span>{line}</span> : null}
      </div>
    );
  }
  return null;
}

/**
 * The box score (BoxScoreOverlay.kt), opened with UP: the line score, situation and last play over a scrim at the
 * top of the picture. Not focusable; the page closes it on the next key or after a while.
 */
export function BoxScoreOverlay(props: { game: TallyGame; hideScores: boolean }) {
  const { game } = props;
  const kicker = [game.league, gameStatusLabel(game), game.broadcasts.join(', ')].filter((x) => x !== '').join(' · ');
  const lastPlay = game.lastPlay !== null && game.lastPlay !== '' ? game.lastPlay : null;
  return (
    <div class="box-score">
      <div class="box-title ellipsis">
        {teamName(game.away)} at {teamName(game.home)}
      </div>
      <div class={'box-kicker mono-label-large clamp-2' + (isLive(game) ? ' live' : '')}>{tallyUppercase(kicker)}</div>
      {props.hideScores ? (
        <div class="box-body muted">Scores hidden</div>
      ) : (
        <>
          <div class="box-lines">
            <LineScore game={game} />
          </div>
          <BoxSituation game={game} />
          {lastPlay !== null ? <div class="box-body clamp-2">{lastPlay}</div> : null}
        </>
      )}
    </div>
  );
}

/**
 * A transient banner for a notable event in another game (EventBanner.kt), top-start of the picture, black with a
 * live-red hairline; it enters and leaves as a lower third (an amber bar, then the panel revealed left to right).
 */
export function EventBanner(props: { event: TallyEvent; visible: boolean; onGone: () => void }) {
  const [shown, setShown] = useState(false);
  useEffect(() => {
    if (props.visible) {
      // the bar stands alone for a moment, then the panel opens (LowerThird.kt: 60 ms lead, 200 ms reveal)
      const t = window.setTimeout(() => setShown(true), 60);
      return () => window.clearTimeout(t);
    }
    setShown(false);
    const t = window.setTimeout(props.onGone, 150);
    return () => window.clearTimeout(t);
  }, [props.visible, props.event.id]);
  const e = props.event;
  return (
    <div class="event-banner">
      <div class={'lt-bar' + (props.visible || shown ? '' : ' off')} />
      <div class={'lt-panel' + (shown ? ' open' : '')}>
        {e.title !== '' ? <div class="ev-title mono-label ellipsis">{e.title.toUpperCase()}</div> : null}
        {e.text !== '' ? <div class="ev-text clamp-2">{e.text}</div> : null}
        <div class="ev-hint">Press DOWN for games</div>
      </div>
    </div>
  );
}

export const switcherKey = (game: TallyGame): string => 'lsw-' + game.id;

/**
 * The in-player "also on now" switcher (GameSwitcher.kt): a bottom-anchored scrim with a header and a row of live
 * game cards over the picture; focus lands on the first card. OK switches channel, HOLD opens the game's actions.
 */
export function GameSwitcher(props: {
  games: TallyGame[];
  hideScores: boolean;
  favorites: ReadonlySet<string>;
  teams: ReadonlySet<string>;
  onSwitch: (game: TallyGame) => void;
}) {
  const group = useFocusable<HTMLDivElement>({ focusKey: 'live-switcher', isFocusBoundary: true, focusBoundaryDirections: ['left', 'right', 'down'] });
  return (
    <div ref={group.ref} class="game-switcher">
      <div class="switcher-head">
        <div class="row-header">
          <span class="title mono-label-large">ALSO ON NOW</span>
          <span class="count mono-label-large">{props.games.length}</span>
        </div>
        <div class="hints">
          <KeyHint keyName="OK" label="Switch" />
          <KeyHint keyName="HOLD" label="More" />
        </div>
      </div>
      <FocusGroup focusKey="live-switcher">
        <MediaRow focusKey="live-switcher-row" title="">
          {props.games.map((g) => (
            <GameCard
              key={g.id}
              focusKey={switcherKey(g)}
              game={g}
              hideScores={props.hideScores}
              favorite={(g.watch !== null && props.favorites.has(g.watch.channelId)) || isFollowed(g, props.teams)}
              followed={isFollowed(g, props.teams)}
              sportsExtras={true}
              onWatch={props.onSwitch}
            />
          ))}
        </MediaRow>
      </FocusGroup>
    </div>
  );
}
