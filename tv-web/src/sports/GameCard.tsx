import { useEffect, useState } from 'preact/hooks';
import { isFinal, isLive, isUpcoming, type TallyGame, type TallyTeam } from '../api/tallyModels';
import { spoilerGuarded } from '../api/tallyDvr';
import { useFocusable } from '../focus/focus';
import { IndicatorSquare, LabelBar } from '../kit/Bits';
import { useRowReveal } from '../kit/MediaRow';
import { gameStatusLabel, hasNoResult } from '../util/format';
import { ScoreDigits } from './ScoreDigits';
import { RecTag } from './SportsBits';
import { startsIn, startsInText } from './startsIn';
import { markFont } from './teamMark';
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
  const abbr = props.team.abbr.toUpperCase();
  const font = markFont(abbr, props.size);
  return (
    <span class="team-mark fallback" style={style}>
      <span style={{ fontSize: `${font.fontSize}px`, letterSpacing: `${font.letterSpacing}px` }}>{abbr}</span>
    </span>
  );
}

function TeamLine(props: { team: TallyTeam; game: TallyGame; hideScores: boolean; rolling: boolean }) {
  const { team, game, hideScores } = props;
  const loser = isFinal(game) && !team.winner && !hideScores;
  const showScore = !isUpcoming(game) && !hasNoResult(game.state, game.detail) && (team.score !== null || hideScores);
  return (
    <div class={'team' + (loser ? ' loser' : '')}>
      <TeamMark team={team} size={51} />
      <span class="name ellipsis">{team.shortName !== '' ? team.shortName : team.abbr}</span>
      {team.possession && isLive(game) ? <IndicatorSquare tone="accent" class="possession" /> : null}
      {showScore ? (
        props.rolling ? (
          <ScoreDigits gameId={game.id} score={team.score ?? 0} hidden={hideScores} class="score" />
        ) : (
          <span class="score">{hideScores ? '–' : String(team.score ?? 0)}</span>
        )
      ) : null}
    </div>
  );
}

const blankTeam = (t: TallyTeam): boolean => t.abbr === '' && t.shortName === '' && t.name === '';

/** Ticks every 10 s while `active` (a followed team's game counting down to its start). */
function useCardNow(active: boolean): number {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    if (!active) return undefined;
    setNow(Date.now());
    const t = window.setInterval(() => setNow(Date.now()), 10_000);
    return () => window.clearInterval(t);
  }, [active]);
  return now;
}

/**
 * A game card: league + status strip, two team lines, a black channel label bar. A game with no channel yet stays
 * focusable (the header describes it) but is dimmed and OK does nothing.
 *
 * `sportsExtras` turns on the rest of the Android GameCard: scores roll (and flash amber) when they change, the REC
 * tag, a followed team's start counting down (IN 23 MIN, STARTING), and no spoilers for a finished game that has a
 * recording. Off by default so existing screens keep their card until they opt in.
 */
export function GameCard(props: {
  game: TallyGame;
  hideScores: boolean;
  favorite: boolean;
  followed: boolean;
  onWatch: (game: TallyGame) => void;
  onFocus?: (game: TallyGame) => void;
  focusKey?: string;
  sportsExtras?: boolean;
  onArrow?: (direction: string) => boolean;
}) {
  const { game } = props;
  const extras = props.sportsExtras === true;
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
    onArrow: props.onArrow,
  });
  const hideScores = props.hideScores || (extras && spoilerGuarded(game));
  const counting = extras && props.followed && isUpcoming(game);
  const now = useCardNow(counting);
  const soonStart = counting ? Date.parse(game.start) : NaN;
  const soon = counting && !isNaN(soonStart) ? startsIn(soonStart, now) : null;
  const statusColor = soon !== null || isLive(game) ? 'var(--accent)' : isUpcoming(game) ? 'var(--text-secondary)' : 'var(--muted)';
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
          {extras ? <RecTag recording={game.recording} /> : null}
          <span class="status mono-label" style={{ color: statusColor }}>
            {soon !== null ? startsInText(soon) : gameStatusLabel(game)}
          </span>
        </div>
        {blankTeam(game.away) && blankTeam(game.home) ? (
          <div class="channel-only clamp-2">{game.watch?.channelName ?? game.name}</div>
        ) : (
          <>
            <TeamLine team={game.away} game={game} hideScores={hideScores} rolling={extras} />
            <TeamLine team={game.home} game={game} hideScores={hideScores} rolling={extras} />
          </>
        )}
      </div>
      <LabelBar text={game.watch !== null ? game.watch.channelName : 'Not on your channels'} live={isLive(game) && watchable} />
    </div>
  );
}
