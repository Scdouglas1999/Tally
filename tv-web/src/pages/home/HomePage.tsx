import { useEffect, useMemo, useState } from 'preact/hooks';
import { backdropUrl } from '../../api/images';
import { absolute } from '../../api/tally';
import type { TallyGame } from '../../api/tallyModels';
import { isFollowed, isLive } from '../../api/tallyModels';
import { useArrivalFocus, type PageProps } from '../../app/page';
import { ItemCard } from '../../kit/ItemCard';
import { MediaRow } from '../../kit/MediaRow';
import { ScrollPage } from '../../kit/ScrollPage';
import { push, type Route } from '../../router/router';
import { openDetails } from '../details/navigate';
import { GameCard } from '../../sports/GameCard';
import { selectHomeGames } from '../../sports/homeRow';
import { libraries, tally } from '../../state/nav';
import { board, tallyUserSettings, useBoardPolling } from '../../state/sportsData';
import { formatTime } from '../../util/format';
import { useStore } from '../../util/store';
import { HomeHeader, type HomeFocus } from './HomeHeader';
import { homeRows, type RowState } from './homeData';
import './home.css';

/** Card height used for LOADING… rows so nothing jumps when cards arrive (poster + label bar + focus room). */
const POSTER_ROW_HEIGHT = 317 + 64 + 14;

function Clock() {
  const [now, setNow] = useState(new Date());
  useEffect(() => {
    const t = window.setInterval(() => setNow(new Date()), 10_000);
    return () => window.clearInterval(t);
  }, []);
  return <div class="home-clock">{formatTime(now)}</div>;
}

function watchGame(game: TallyGame): void {
  if (game.watch === null) return;
  const away = game.away.shortName !== '' ? game.away.shortName : game.away.abbr;
  const home = game.home.shortName !== '' ? game.home.shortName : game.home.abbr;
  const route: Route = {
    name: 'live',
    channelId: game.watch.channelId,
    hlsPath: game.watch.hlsPath,
    title: away !== '' ? `${away} at ${home}` : game.watch.channelName,
    gameId: game.id,
  };
  push(route);
}

export function HomePage(props: PageProps<Extract<Route, { name: 'home' }>>) {
  const views = useStore(libraries);
  const plugin = useStore(tally);
  const currentBoard = useStore(board);
  const settings = useStore(tallyUserSettings);
  useBoardPolling(props.active);

  const specs = useMemo(() => (views === null ? [] : homeRows(views)), [views]);
  const [rows, setRows] = useState<Record<string, RowState>>({});
  const [focus, setFocusInfo] = useState<HomeFocus>(null);

  const load = (): void => {
    for (const spec of specs) {
      spec
        .load()
        .then((items) => setRows((r) => ({ ...r, [spec.key]: { kind: 'items', items } })))
        .catch(() => setRows((r) => ({ ...r, [spec.key]: { kind: 'error', message: 'Could not load this row.' } })));
    }
  };
  useEffect(load, [specs]);
  // back on Home (after playing something): progress and Next Up changed
  const [wasActive, setWasActive] = useState(props.active);
  useEffect(() => {
    if (props.active && !wasActive) load();
    setWasActive(props.active);
  }, [props.active]);

  const favorites = useMemo(() => new Set(settings?.favorites ?? []), [settings]);
  const teams = useMemo(() => new Set((settings?.favoriteTeams ?? []).map((t) => t.toUpperCase())), [settings]);
  const games = plugin.kind === 'available' ? selectHomeGames(currentBoard, favorites, teams, Date.now()) : [];
  const hideScores = settings?.hideScores === true;

  // the page settles (games known or no plugin, and the first library row loaded) before initial focus
  const gamesSettled = plugin.kind === 'absent' || plugin.kind === 'error' || (plugin.kind === 'available' && currentBoard !== null);
  const visibleRows = specs.filter((s) => {
    const r = rows[s.key];
    return r === undefined || r.kind !== 'items' || r.items.length > 0;
  });
  const firstRow = visibleRows[0];
  const firstRowReady = firstRow !== undefined && rows[firstRow.key]?.kind === 'items';
  const target = games.length > 0 ? 'home-game-0' : firstRow !== undefined ? `home-${firstRow.key}-0` : null;
  useArrivalFocus(props, target, gamesSettled && (games.length > 0 || firstRowReady));

  const backdrop = focus?.kind === 'item' ? backdropUrl(focus.item) : focus?.kind === 'game' && focus.game.backdropPath !== null ? absolute(focus.game.backdropPath) : null;

  return (
    <div class="home">
      {backdrop !== null ? (
        <div class="home-backdrop">
          <img key={backdrop} src={backdrop} alt="" />
        </div>
      ) : null}
      <HomeHeader focus={focus} />
      <Clock />
      <div class="home-rows">
        <ScrollPage>
          {games.length > 0 ? (
            <MediaRow title={games.some(isLive) ? 'Live now' : "Today's games"} count={games.length} focusKey="home-games">
              {games.map((g, i) => (
                <GameCard
                  key={g.id}
                  focusKey={'home-game-' + String(i)}
                  game={g}
                  hideScores={hideScores}
                  favorite={(g.watch !== null && favorites.has(g.watch.channelId)) || isFollowed(g, teams)}
                  followed={isFollowed(g, teams)}
                  onWatch={watchGame}
                  onFocus={(game) => setFocusInfo({ kind: 'game', game, hideScores })}
                />
              ))}
            </MediaRow>
          ) : null}
          {visibleRows.map((spec) => {
            const state = rows[spec.key] ?? { kind: 'loading' };
            return (
              <MediaRow
                key={spec.key}
                focusKey={'home-row-' + spec.key}
                title={spec.title}
                count={state.kind === 'items' ? state.items.length : null}
                message={state.kind === 'loading' ? 'LOADING…' : state.kind === 'error' ? state.message : null}
                height={POSTER_ROW_HEIGHT}
              >
                {state.kind === 'items'
                  ? state.items.map((item, i) => (
                      <ItemCard
                        key={item.Id}
                        focusKey={`home-${spec.key}-${i}`}
                        item={item}
                        shape={spec.shape}
                        watchingRow={spec.watching}
                        onPress={() => openDetails(item)}
                        onFocus={(it) => setFocusInfo({ kind: 'item', item: it, rowTitle: spec.title })}
                      />
                    ))
                  : null}
              </MediaRow>
            );
          })}
        </ScrollPage>
      </div>
    </div>
  );
}
