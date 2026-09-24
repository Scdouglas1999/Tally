import { useMemo } from 'preact/hooks';
import { isFollowed, type TallyGame } from '../../api/tallyModels';
import { MediaRow } from '../../kit/MediaRow';
import { ScrollPage } from '../../kit/ScrollPage';
import { boardRows, POSTPONED, type BoardRow } from '../../sports/boardOrganizer';
import { GameCard } from '../../sports/GameCard';
import { EmptyState } from '../../sports/SportsBits';
import { FocusedGamePanel } from './FocusedGamePanel';
import { watchGame } from './sportsState';
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
}

/** The panel alone follows focus (the board itself does not re-render when a card is focused). */
function PanelHost(props: { rows: BoardRow[]; focusedGameId: Store<string | null>; hideScores: boolean }) {
  const id = useStore(props.focusedGameId);
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
  const focused = findGame(rows, props.focusedGameId.get()) ?? rows[0]?.games[0] ?? null;
  const target = d.loading || rows.length === 0 || focused === null ? 'sg-empty' : gameFocusKey(focused);
  useTabArrival(props.takeFocus && props.active, target, true);
  const errorLeagues = Object.keys(d.feedErrors);

  let body;
  if (d.loading) body = <EmptyState focusKey="sg-empty" class="board-empty" title="Loading games…" subtitle="" />;
  else if (d.boardError !== null && !d.hasBoard) body = <EmptyState focusKey="sg-empty" class="board-empty" title="Board unavailable" subtitle={d.boardError} />;
  else if (rows.length === 0 && d.games.length > 0)
    body = (
      <EmptyState
        focusKey="sg-empty"
        class="board-empty"
        title="Nothing on your channels"
        subtitle="None of today’s games are on your channels. Turn off “My channels only” in Settings to see every game."
      />
    );
  else if (rows.length === 0) body = <EmptyState focusKey="sg-empty" class="board-empty" title="No games today" subtitle="There are no games on the board right now" />;
  else
    body = (
      <div class="board-rows">
        <ScrollPage>
          {rows.map((row) => (
            <MediaRow key={row.key} focusKey={'sgr-' + row.key} title={`${row.league} / ${rowStateLabel(row.state)}`} count={row.games.length}>
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
      <PanelHost rows={rows} focusedGameId={props.focusedGameId} hideScores={d.hideScores} />
      {errorLeagues.length > 0 ? <div class="feed-errors mono-label ellipsis">Some feeds failed: {errorLeagues.join(', ')}</div> : null}
      {body}
    </div>
  );
}
