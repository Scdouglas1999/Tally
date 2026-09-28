import { useEffect, useMemo, useState } from 'preact/hooks';
import { isFollowed, type TallyGame } from '../../api/tallyModels';
import { MediaRow } from '../../kit/MediaRow';
import { ScrollPage } from '../../kit/ScrollPage';
import { boardRows, POSTPONED, redZoneRowKey, type BoardRow } from '../../sports/boardOrganizer';
import { GameCard } from '../../sports/GameCard';
import { EmptyState } from '../../sports/SportsBits';
import { FocusedGamePanel } from './FocusedGamePanel';
import { RedZoneCard, RedZonePanel, REDZONE_TILE_ID, REDZONE_TILE_KEY, type RedZoneOnAir } from './RedZoneTile';
import { watchGame, watchRedZone } from './sportsState';
import { useTabArrival } from './tabArrival';
import { useStore, type Store } from '../../util/store';

function rowStateLabel(state: string): string {
  switch (state) {
    case 'in':
      return 'Live';
    case 'pre':
      return 'Upcoming';
    case 'post':
      return 'Final';
    case POSTPONED:
      return 'Postponed';
    default:
      return state;
  }
}

export const gameFocusKey = (game: TallyGame): string => 'sg-' + game.id;

export interface BoardInput {
  games: TallyGame[];
  loading: boolean;
  boardError: string | null;
  hasBoard: boolean;
  feedErrors: Record<string, string>;
  favorites: ReadonlySet<string>;
  teams: ReadonlySet<string>;
  hideScores: boolean;
  onlyWatchable: boolean;
  /** The RedZone channel while it is on the air (its tile leads the live row); null otherwise. */
  redZone: RedZoneOnAir | null;
  /** `redZone` is settled (no RedZone channel, or the server has answered what it shows). */
  redZoneKnown?: boolean;
}

/** How long a board opening waits for the RedZone answer before it places focus anyway (ms). */
const REDZONE_WAIT_MS = 2000;

/** The panel alone follows focus (the board itself does not re-render when a card is focused). */
function PanelHost(props: { rows: BoardRow[]; focusedGameId: Store<string | null>; hideScores: boolean; redZone: RedZoneOnAir | null; games: TallyGame[] }) {
  const id = useStore(props.focusedGameId);
  if (id === REDZONE_TILE_ID && props.redZone !== null) return <RedZonePanel onAir={props.redZone} games={props.games} hideScores={props.hideScores} />;
  const game = findGame(props.rows, id) ?? props.rows[0]?.games[0] ?? null;
  return <FocusedGamePanel game={game} hideScores={props.hideScores} />;
}

function findGame(rows: BoardRow[], id: string | null): TallyGame | null {
  if (id === null) return null;
  for (const r of rows) for (const g of r.games) if (g.id === id) return g;
  return null;
}

/**
 * The games board (GamesBoard.kt): the large focused-game panel on top, then vertically scrolling rows of game cards,
 * one per league + state ("MLB / LIVE"). The focused row's header moves to the top of the list; each row returns to
 * the card focused last; UP from the first row reaches the selected tab (the panel is not focusable). Opening the tab
 * (and coming back to it) lands on the card focused last, else the first card.
 */
export function GamesBoard(props: {
  data: BoardInput;
  /** The card focused last (kept by the page: coming back to the tab lands on it). */
  focusedGameId: Store<string | null>;
  takeFocus: boolean;
  active: boolean;
}) {
  const d = props.data;
  const rows: BoardRow[] = useMemo(() => boardRows(d.games, d.favorites, d.onlyWatchable, d.teams), [d.games, d.favorites, d.onlyWatchable, d.teams]);
  // the card focused last before this visit (focus that lands while the board waits for RedZone is not a choice)
  const [lastVisit] = useState(() => props.focusedGameId.get());
  const remembered = findGame(rows, lastVisit);
  const focused = remembered ?? rows[0]?.games[0] ?? null;
  const rz = d.redZone;
  const rzRow = rz !== null ? redZoneRowKey(rows) : null;
  const onRedZone = rz !== null && lastVisit === REDZONE_TILE_ID;
  // with nothing focused before, the first card of the first row: the RedZone tile when it leads that row
  const tileLeads = rz !== null && (rzRow === null || rzRow === rows[0]?.key);
  const target =
    d.loading || (rows.length === 0 && rz === null)
      ? 'sg-empty'
      : onRedZone || (remembered === null && tileLeads)
        ? REDZONE_TILE_KEY
        : focused === null
          ? rz !== null
            ? REDZONE_TILE_KEY
            : 'sg-empty'
          : gameFocusKey(focused);
  // the RedZone answer comes a moment after the board: wait (briefly) for it, so a first visit lands on its tile
  // rather than on the game the tile then pushes aside
  const [waited, setWaited] = useState(false);
  const hasCards = rows.length > 0 || rz !== null;
  useEffect(() => {
    if (!hasCards) return undefined;
    const t = window.setTimeout(() => setWaited(true), REDZONE_WAIT_MS);
    return () => window.clearTimeout(t);
  }, [hasCards]);
  useTabArrival(props.takeFocus && props.active, target, d.redZoneKnown !== false || waited);
  const errorLeagues = Object.keys(d.feedErrors);

  const redZoneCard = (onAir: RedZoneOnAir) => (
    <RedZoneCard
      key={REDZONE_TILE_ID}
      onAir={onAir}
      games={d.games}
      hideScores={d.hideScores}
      onWatch={() => watchRedZone(onAir.channel)}
      onFocus={() => props.focusedGameId.set(REDZONE_TILE_ID)}
    />
  );

  let body;
  if (d.loading) body = <EmptyState focusKey="sg-empty" class="board-empty" title="Loading games…" subtitle="" />;
  else if (d.boardError !== null && !d.hasBoard) body = <EmptyState focusKey="sg-empty" class="board-empty" title="Board unavailable" subtitle={d.boardError} />;
  else if (rows.length === 0 && d.games.length > 0 && rz === null)
    body = (
      <EmptyState
        focusKey="sg-empty"
        class="board-empty"
        title="No game has a stream right now."
        subtitle="Turn off “Only games with a stream” to see them all."
      />
    );
  else if (rows.length === 0 && rz === null) body = <EmptyState focusKey="sg-empty" class="board-empty" title="No games today" subtitle="There are no games on the board right now" />;
  else
    body = (
      <div class="board-rows">
        <ScrollPage>
          {rz !== null && rzRow === null ? (
            <MediaRow key="redzone" focusKey="sgr-redzone" title="Pulse / Live">
              {redZoneCard(rz)}
            </MediaRow>
          ) : null}
          {rows.map((row) => (
            <MediaRow key={row.key} focusKey={'sgr-' + row.key} title={`${row.league} / ${rowStateLabel(row.state)}`} count={row.games.length}>
              {rz !== null && row.key === rzRow ? redZoneCard(rz) : null}
              {row.games.map((g) => (
                <GameCard
                  key={g.id}
                  focusKey={gameFocusKey(g)}
                  game={g}
                  hideScores={d.hideScores}
                  favorite={(g.watch !== null && d.favorites.has(g.watch.channelId)) || isFollowed(g, d.teams)}
                  followed={isFollowed(g, d.teams)}
                  sportsExtras={true}
                  onWatch={(game) => watchGame(game)}
                  onFocus={(game) => props.focusedGameId.set(game.id)}
                />
              ))}
            </MediaRow>
          ))}
        </ScrollPage>
      </div>
    );

  return (
    <div class="games-board">
      <PanelHost rows={rows} focusedGameId={props.focusedGameId} hideScores={d.hideScores} redZone={rz} games={d.games} />
      {errorLeagues.length > 0 ? <div class="feed-errors mono-label ellipsis">Some feeds failed: {errorLeagues.join(', ')}</div> : null}
      {body}
    </div>
  );
}
