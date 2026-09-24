import { useState } from 'preact/hooks';
import { isFinal, isLive, isUpcoming, type TallyGame, type TallyTeam } from '../api/tallyModels';
import { useFocusable } from '../focus/focus';
import { IndicatorSquare, LabelBar } from '../kit/Bits';
import { useRowReveal } from '../kit/MediaRow';
import { gameStatusLabel, hasNoResult } from '../util/format';
import './sports.css';

/** A team's logo, never cropped; a bordered square with the abbreviation when there is none or it fails. */
export function TeamMark(props: { team: TallyTeam; size: number }) {
  const [failed, setFailed] = useState(false);
  const style = { width: `${props.size}px`, height: `${props.size}px` };
  if (props.team.logo !== '' && !failed) {
    return (
      <span class="team-mark" style={style}>
        <img src={props.team.logo} alt="" onError={() => setFailed(true)} />
      </span>
    );
  }
  return (
    <span class="team-mark fallback" style={style}>
      <span>{props.team.abbr.toUpperCase()}</span>
    </span>
  );
}

function TeamLine(props: { team: TallyTeam; game: TallyGame; hideScores: boolean }) {
  const { team, game, hideScores } = props;
  const loser = isFinal(game) && !team.winner && !hideScores;
  const showScore = !isUpcoming(game) && !hasNoResult(game.state, game.detail) && (team.score !== null || hideScores);
  return (
    <div class={'team' + (loser ? ' loser' : '')}>
      <TeamMark team={team} size={51} />
      <span class="name ellipsis">{team.shortName !== '' ? team.shortName : team.abbr}</span>
      {team.possession && isLive(game) ? <IndicatorSquare tone="accent" class="possession" /> : null}
      {showScore ? <span class="score">{hideScores ? '–' : String(team.score ?? 0)}</span> : null}
    </div>
  );
}

const blankTeam = (t: TallyTeam): boolean => t.abbr === '' && t.shortName === '' && t.name === '';

/**
 * A game card: league + status strip, two team lines, a black channel label bar. A game with no channel yet stays
 * focusable (the header describes it) but is dimmed and OK does nothing.
 */
export function GameCard(props: {
  game: TallyGame;
  hideScores: boolean;
  favorite: boolean;
  followed: boolean;
  onWatch: (game: TallyGame) => void;
  onFocus?: (game: TallyGame) => void;
  focusKey?: string;
}) {
  const { game } = props;
  const reveal = useRowReveal();
  const watchable = game.watch !== null;
  const f = useFocusable<HTMLDivElement>({
    focusKey: props.focusKey,
    onEnter: () => {
      if (watchable) props.onWatch(game);
    },
    onFocus: () => {
      if (f.ref.current !== null) reveal(f.ref.current);
      props.onFocus?.(game);
    },
  });
  const statusColor = isLive(game) ? 'var(--accent)' : isUpcoming(game) ? 'var(--text-secondary)' : 'var(--muted)';
  return (
    <div ref={f.ref} class={'game-card' + (watchable ? '' : ' dark')} onClick={() => watchable && props.onWatch(game)}>
      <div class="game-body">
        <div class="strip">
          <span class={'league mono-label ellipsis' + (props.favorite ? ' favorite' : '')}>{game.league.toUpperCase()}</span>
          {props.followed ? (
            <span class="following mono-label">
              <IndicatorSquare tone="accent" />
              FOLLOWING
            </span>
          ) : null}
          <span class="status mono-label" style={{ color: statusColor }}>
            {gameStatusLabel(game)}
          </span>
        </div>
        {blankTeam(game.away) && blankTeam(game.home) ? (
          <div class="channel-only clamp-2">{game.watch?.channelName ?? game.name}</div>
        ) : (
          <>
            <TeamLine team={game.away} game={game} hideScores={props.hideScores} />
            <TeamLine team={game.home} game={game} hideScores={props.hideScores} />
          </>
        )}
      </div>
      <LabelBar text={game.watch !== null ? game.watch.channelName : 'Not on your channels'} live={isLive(game) && watchable} />
    </div>
  );
}
