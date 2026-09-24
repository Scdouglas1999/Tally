import { spoilerGuarded } from '../../api/tallyDvr';
import { isFinal, isLive, isUpcoming, type TallyGame, type TallyTeam } from '../../api/tallyModels';
import { IndicatorSquare } from '../../kit/Bits';
import { TeamMark } from '../../sports/GameCard';
import { ScoreDigits } from '../../sports/ScoreDigits';
import { BaseballDiamond, baseballCount, KeyHint, LineScore } from '../../sports/SportsBits';
import { gameStatusLabel, hasNoResult, tallyUppercase } from '../../util/format';

function HeroTeamLine(props: { team: TallyTeam; game: TallyGame; home: boolean; hideScores: boolean }) {
  const { team, game, hideScores } = props;
  // dimming the loser names the winner: not while scores are hidden
  const loser = isFinal(game) && !team.winner && !hideScores;
  const record = [team.record, props.home ? 'HOME' : 'AWAY'].filter((x): x is string => x !== null && x !== '').join(' · ');
  const showScore = !isUpcoming(game) && !hasNoResult(game.state, game.detail) && (team.score !== null || hideScores);
  return (
    <div class={'hero-team' + (loser ? ' loser' : '')}>
      <TeamMark team={team} size={125} />
      <div class="who">
        <div class="name ellipsis">{team.shortName !== '' ? team.shortName : team.abbr}</div>
        <div class="record mono-label">{record}</div>
      </div>
      {team.possession && isLive(game) ? <IndicatorSquare tone="accent" class="possession" /> : null}
      {showScore ? <ScoreDigits gameId={game.id} score={team.score ?? 0} hidden={hideScores} class="hero-score" /> : null}
    </div>
  );
}

function Situation(props: { game: TallyGame }) {
  const { game } = props;
  if (game.sport === 'football') {
    return game.downDistance !== null ? <div class="situation ellipsis">{game.downDistance.toUpperCase()}</div> : null;
  }
  if (game.sport === 'baseball') {
    const line = baseballCount(game);
    return (
      <div class="situation baseball">
        <BaseballDiamond onFirst={game.onFirst} onSecond={game.onSecond} onThird={game.onThird} size={48} />
        {line !== '' ? <span>{line}</span> : null}
      </div>
    );
  }
  return game.detail !== '' ? <div class="situation ellipsis">{game.detail.toUpperCase()}</div> : null;
}

/** The black bar under the panel: the channel and key hints, or "not on your channels" with the broadcasters. */
function WatchBar(props: { game: TallyGame }) {
  const { game } = props;
  const w = game.watch;
  if (w !== null) {
    return (
      <div class="watch-bar">
        <IndicatorSquare tone={isLive(game) ? 'live' : 'idle'} />
        <span class="channel mono-label ellipsis">{tallyUppercase(w.channelName)}</span>
        <KeyHint keyName="OK" label="Watch" />
        <KeyHint keyName="HOLD" label="Add to multiview" />
      </div>
    );
  }
  return (
    <div class="watch-bar">
      <span class="channel mono-label muted ellipsis">NOT ON YOUR CHANNELS</span>
      {game.broadcasts.length > 0 ? <span class="broadcasters ellipsis">On {game.broadcasts.join(', ')}</span> : null}
    </div>
  );
}

/** The situation column's width at 1080p: what the line score has to fit. */
const SITUATION_WIDTH = 600;

/**
 * The large panel mirroring the focused card (FocusedGamePanel.kt): kicker, big mono scores, the situation (or when it
 * starts / how it ended), last play, the line score, and a black bar with the channel and key hints. `game` null
 * draws the empty panel at the same height so nothing jumps.
 */
export function FocusedGamePanel(props: { game: TallyGame | null; hideScores: boolean }) {
  const { game } = props;
  if (game === null) return <div class="hero-panel" />;
  const hidden = props.hideScores || spoilerGuarded(game);
  const live = isLive(game);
  const heading = live ? 'SITUATION' : hasNoResult(game.state, game.detail) ? 'STATUS' : isFinal(game) ? 'FINAL' : 'STARTS';
  return (
    <div class="hero-panel">
      <div class="hero-main">
        <div class="hero-left">
          <div class={'hero-kicker mono-label-large' + (live ? ' live' : '')}>
            <IndicatorSquare tone={live ? 'live' : 'idle'} />
            <span class="ellipsis">{tallyUppercase(`${game.league} · ${gameStatusLabel(game)}`)}</span>
          </div>
          <HeroTeamLine team={game.away} game={game} home={false} hideScores={hidden} />
          <HeroTeamLine team={game.home} game={game} home={true} hideScores={hidden} />
        </div>
        <div class="hero-right">
          <div class="hero-heading mono-label">{heading}</div>
          {hidden ? (
            <div class="hero-body muted">Scores hidden</div>
          ) : !live ? (
            <>
              <div class={'situation ellipsis' + (isFinal(game) ? ' final' : '')}>{gameStatusLabel(game).toUpperCase()}</div>
              {game.broadcasts.length > 0 ? <div class="hero-body clamp-2">On {game.broadcasts.join(', ')}</div> : null}
            </>
          ) : (
            <>
              <Situation game={game} />
              {game.lastPlay !== null ? <div class="hero-body clamp-2">{game.lastPlay}</div> : null}
            </>
          )}
          {!hidden && (live || isFinal(game)) ? (
            <div class="hero-lines">
              <LineScore game={game} compact={true} maxWidth={SITUATION_WIDTH} />
            </div>
          ) : null}
        </div>
      </div>
      <WatchBar game={game} />
    </div>
  );
}
