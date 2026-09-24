import type { BaseItemDto } from '@jellyfin/sdk/lib/generated-client/models/base-item-dto';
import { useEffect, useMemo, useRef, useState } from 'preact/hooks';
import { backdropUrl } from '../../api/images';
import { absolute } from '../../api/tally';
import type { TallyGame } from '../../api/tallyModels';
import { isFollowed, isLive } from '../../api/tallyModels';
import { useArrivalFocus, type PageProps } from '../../app/page';
import { currentFocusKey, focusExists, setFocus } from '../../focus/focus';
import { ItemCard } from '../../kit/ItemCard';
import { MediaRow } from '../../kit/MediaRow';
import { ScrollPage } from '../../kit/ScrollPage';
import { ToastHost } from '../../kit/Toast';
import { useKeyHandler } from '../../platform/keyRouter';
import type { Route } from '../../router/router';
import { DetailDialogs, cardMenu, type Dialog } from '../details/DetailDialogs';
import { isPlayable, openDetails, playItem } from '../details/navigate';
import { GameActionsDialog } from '../sports/GameActionsDialog';
import { addToMultiviewWithNotice, gameRoute, watchGame } from '../sports/sportsState';
import { useOkHold } from '../sports/useOkHold';
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

/** What a card on Home is, by focus key: the item menu, the game menu and the PLAY key look it up. */
type HomeCard = { kind: 'item'; item: BaseItemDto } | { kind: 'game'; game: TallyGame };

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

  // --- the cards' menus (HOLD OK or MENU, as Android's long press) and the PLAY key ------------------------------
  const cards = useRef(new Map<string, HomeCard>());
  cards.current.clear();
  games.forEach((game, i) => cards.current.set('home-game-' + String(i), { kind: 'game', game }));
  for (const spec of visibleRows) {
    const r = rows[spec.key];
    if (r?.kind === 'items') r.items.forEach((item, i) => cards.current.set(`home-${spec.key}-${i}`, { kind: 'item', item }));
  }
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const [gameMenu, setGameMenu] = useState<{ gameId: string; returnKey: string } | null>(null);
  const menuOpen = dialog !== null || gameMenu !== null;
  useOkHold(
    () => {
      const key = currentFocusKey();
      const card = cards.current.get(key);
      if (card === undefined) return false;
      if (card.kind === 'game') setGameMenu({ gameId: card.game.id, returnKey: key });
      else setDialog(cardMenu(card.item, key));
      return true;
    },
    !menuOpen,
    props.active,
  );
  useKeyHandler((key) => {
    if ((key !== 'play' && key !== 'playPause') || menuOpen) return false;
    const card = cards.current.get(currentFocusKey());
    if (card?.kind !== 'item' || !isPlayable(card.item)) return false;
    playItem(card.item);
    return true;
  }, props.active);
  const menuGame = gameMenu !== null ? (games.find((g) => g.id === gameMenu.gameId) ?? null) : null;
  const closeGameMenu = (): void => {
    const back = gameMenu?.returnKey;
    setGameMenu(null);
    if (back !== undefined && focusExists(back)) setFocus(back);
  };
  // the game left the row while its menu was open
  useEffect(() => {
    if (gameMenu !== null && menuGame === null) closeGameMenu();
  }, [gameMenu !== null && menuGame === null]);

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
                  sportsExtras={true}
                  onWatch={(game) => watchGame(game)}
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
      {menuGame !== null ? (
        <GameActionsDialog
          game={menuGame}
          actions={{
            watch: gameRoute(menuGame) !== null ? () => watchGame(menuGame) : undefined,
            addToMultiview: menuGame.watch !== null && menuGame.watch.channelId !== '' ? () => addToMultiviewWithNotice(menuGame.watch?.channelId ?? '') : undefined,
            follow: true,
          }}
          hideScores={hideScores}
          favoriteTeams={teams}
          onDismiss={closeGameMenu}
        />
      ) : null}
      <DetailDialogs dialog={dialog} setDialog={setDialog} pageKey={props.pageKey} onChanged={load} />
      <ToastHost />
    </div>
  );
}
