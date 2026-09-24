/**
 * The season rundown (media/series/TallySeasonRundown.kt): a fixed head (the series name, season tabs, the
 * season's summary) over the numbered list of its episodes with their state (NEXT UP, progress, WATCHED), then
 * the focused episode's guest stars and the season's extras. OK plays an episode; MENU opens its menu (Go to opens
 * the episode's page). A tab takes over the list once focus has rested on it for 300 ms.
 */
import type { BaseItemDto } from '@jellyfin/sdk/lib/generated-client/models/base-item-dto';
import { ImageType } from '@jellyfin/sdk/lib/generated-client/models/image-type';
import type { ComponentChildren } from 'preact';
import { useEffect, useRef, useState } from 'preact/hooks';
import { backdropUrl, itemImage, wideUrl } from '../../api/images';
import { useArrivalFocus, type PageProps } from '../../app/page';
import { FocusGroup, setFocus, useFocusable } from '../../focus/focus';
import { EpisodeRow, type EpisodeStatus } from '../../kit/EpisodeRow';
import { FrameCard, PersonCard, PERSON_CARD } from '../../kit/FrameCard';
import { MediaRow } from '../../kit/MediaRow';
import { ScrollPage, usePageScroll } from '../../kit/ScrollPage';
import { push, type Route } from '../../router/router';
import { formatRuntime, formatTime, resumePercent, tallyUppercase } from '../../util/format';
import { Clock, PageMessage, personImage, useCardKeys } from './common';
import { DetailDialogs, cardMenu, type Dialog } from './DetailDialogs';
import { loadEpisodes, loadItem, loadNextUp, loadSeasons, loadSpecialFeatures } from './detailsData';
import { airDate, episodeNumber, extrasOf, personCardRole, rowForDown, seasonLabel, seasonSummary, seasonSummaryText, type Extra } from './detailsFormat';
import { openItemPage, playItem } from './navigate';
import './season.css';

const TAB_SETTLE_MS = 300;
const STILL_W = 256;
const STILL_H = 144;

type Episodes = { kind: 'loading' } | { kind: 'error'; message: string } | { kind: 'ready'; seasonId: string; list: BaseItemDto[] };

function SeasonTab(props: { label: string; current: boolean; focusKey: string; onSettle: () => void; onPress: () => void; onDown: () => void }) {
  const timer = useRef(0);
  const f = useFocusable<HTMLDivElement>({
    focusKey: props.focusKey,
    onEnter: props.onPress,
    onFocus: () => {
      window.clearTimeout(timer.current);
      timer.current = window.setTimeout(props.onSettle, TAB_SETTLE_MS);
    },
    onBlur: () => window.clearTimeout(timer.current),
    onArrow: (direction) => {
      if (direction !== 'down') return true;
      props.onDown();
      return false;
    },
  });
  useEffect(() => () => window.clearTimeout(timer.current), []);
  return (
    <div ref={f.ref} class={'season-tab mono-label' + (props.current ? ' current' : '')} onClick={props.onPress}>
      {tallyUppercase(props.label)}
    </div>
  );
}

/** Keeps a rundown row in view (with the fade's room above it) when it takes focus. */
function RowSlot(props: { children: (onFocus: () => void) => ComponentChildren }) {
  const ref = useRef<HTMLDivElement>(null);
  const page = usePageScroll();
  return <div ref={ref}>{props.children(() => ref.current !== null && page.reveal(ref.current, 'nearest'))}</div>;
}

function rowMeta(ep: BaseItemDto): string {
  const runtime = ep.RunTimeTicks ?? 0;
  return [runtime > 0 ? formatRuntime(runtime) : null, airDate(ep.PremiereDate), ep.CommunityRating != null ? `★ ${ep.CommunityRating.toFixed(1)}` : null]
    .filter((x) => x !== null)
    .join(' · ');
}

function rowEnds(ep: BaseItemDto): string | null {
  const left = (ep.RunTimeTicks ?? 0) - (ep.UserData?.PlaybackPositionTicks ?? 0);
  return left > 0 ? 'ENDS ' + formatTime(new Date(Date.now() + left / 10_000)) : null;
}

export function SeasonView(props: { page: PageProps; seriesId: string; seasonId?: string; episodeId?: string }) {
  const pk = props.page.pageKey;
  const k = (name: string): string => `${pk}-${name}`;
  const [series, setSeries] = useState<BaseItemDto | null>(null);
  const [failed, setFailed] = useState<string | null>(null);
  const [seasons, setSeasons] = useState<BaseItemDto[]>([]);
  const [tab, setTab] = useState(-1);
  const [episodes, setEpisodes] = useState<Episodes>({ kind: 'loading' });
  const [nextUp, setNextUp] = useState<BaseItemDto | null>(null);
  const [extras, setExtras] = useState<Extra[]>([]);
  const [focusedRow, setFocusedRow] = useState<number | null>(null);
  const [fade, setFade] = useState(false);
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const lastRow = useRef<{ seasonId: string; index: number } | null>(null);
  const initialEpisode = useRef(props.episodeId ?? null);
  const keys = useCardKeys(props.page.active && dialog === null);

  const showSeason = (list: BaseItemDto[], index: number): void => {
    const season = list[index];
    if (season?.Id == null) return;
    const seasonId = season.Id;
    setTab(index);
    setEpisodes({ kind: 'loading' });
    setExtras([]);
    setFocusedRow(null);
    setFade(false);
    loadEpisodes(props.seriesId, seasonId)
      .then((eps) => setEpisodes({ kind: 'ready', seasonId, list: eps }))
      .catch((e: unknown) => setEpisodes({ kind: 'error', message: e instanceof Error ? e.message : 'Nothing came back from the server' }));
    loadSpecialFeatures(seasonId).then((f) => setExtras(extrasOf(f))).catch(() => undefined);
  };

  useEffect(() => {
    loadItem(props.seriesId)
      .then(setSeries)
      .catch((e: unknown) => setFailed(e instanceof Error ? e.message : 'Nothing came back from the server'));
    loadNextUp(props.seriesId).then(setNextUp).catch(() => undefined);
    loadSeasons(props.seriesId)
      .then((list) => {
        setSeasons(list);
        const i = props.seasonId !== undefined ? list.findIndex((s) => s.Id === props.seasonId) : 0;
        showSeason(list, Math.max(0, i));
      })
      .catch((e: unknown) => setFailed(e instanceof Error ? e.message : 'Nothing came back from the server'));
  }, [props.seriesId]);

  /** The shown season's episodes and Next Up again, in place (focus stays on its row). */
  const reload = (): void => {
    if (episodes.kind !== 'ready') return;
    const shown = episodes.seasonId;
    loadEpisodes(props.seriesId, shown)
      .then((eps) => setEpisodes((cur) => (cur.kind === 'ready' && cur.seasonId === shown ? { kind: 'ready', seasonId: shown, list: eps } : cur)))
      .catch(() => undefined);
    loadNextUp(props.seriesId).then(setNextUp).catch(() => undefined);
    loadSeasons(props.seriesId).then(setSeasons).catch(() => undefined);
  };

  // back from playback: progress and Next Up moved
  const wasActive = useRef(props.page.active);
  useEffect(() => {
    if (props.page.active && !wasActive.current) reload();
    wasActive.current = props.page.active;
  }, [props.page.active]);

  // the guest stars follow the focused episode, once focus rests (no page render on every step down the list)
  const focusTimer = useRef(0);
  useEffect(() => () => window.clearTimeout(focusTimer.current), []);

  const list = episodes.kind === 'ready' ? episodes.list : [];
  const seasonId = episodes.kind === 'ready' ? episodes.seasonId : '';
  const rowKey = (i: number): string => k(`ep-${seasonId}-${i}`);

  // arrival: the episode the page was opened on, else the first row
  const initialIndex = (): number => {
    const i = initialEpisode.current !== null ? list.findIndex((e) => e.Id === initialEpisode.current) : -1;
    return Math.max(0, i);
  };
  useArrivalFocus(props.page, list.length > 0 ? rowKey(initialIndex()) : tab >= 0 && episodes.kind !== 'loading' ? k(`tab-${tab}`) : null, episodes.kind !== 'loading' && seasons.length > 0);

  const focusDown = (): void => {
    const last = lastRow.current !== null && lastRow.current.seasonId === seasonId ? lastRow.current.index : null;
    const row = rowForDown(list, last, nextUp?.Id ?? null);
    if (row !== null) setFocus(rowKey(row));
  };
  const onTab = (i: number): void => {
    if (i === tab) focusDown();
    else showSeason(seasons, i);
  };

  keys.play.clear();
  keys.menu.clear();
  list.forEach((ep, i) => {
    keys.play.set(rowKey(i), () => playItem(ep));
    keys.menu.set(rowKey(i), () => setDialog(cardMenu(ep, rowKey(i), () => ep.Id != null && openItemPage(ep.Id))));
  });
  seasons.forEach((s, i) => keys.menu.set(k(`tab-${i}`), () => setDialog({ kind: 'menu', item: s, returnKey: k(`tab-${i}`) })));

  const focusedEpisode = focusedRow !== null ? list[focusedRow] : undefined;
  const guests = (focusedEpisode?.People ?? []).filter((p) => p.Type === 'GuestStar');
  guests.forEach((p, i) => keys.menu.set(k('guest-' + String(i)), () => setDialog({ kind: 'person', personId: p.Id ?? '', name: p.Name ?? '', returnKey: k('guest-' + String(i)) })));
  extras.forEach((e, i) => {
    keys.play.set(k('extra-' + String(i)), () => playItem(e.item, true));
    keys.menu.set(k('extra-' + String(i)), () => setDialog(cardMenu(e.item, k('extra-' + String(i)))));
  });

  // empty groups must not take focus themselves (the page's arrival focus waits for a row)
  const tabs = useFocusable<HTMLDivElement>({
    focusKey: k('tabs'),
    saveLastFocusedChild: false,
    preferredChildFocusKey: tab >= 0 ? k(`tab-${tab}`) : undefined,
    focusable: seasons.length > 0,
  });
  const rows = useFocusable<HTMLDivElement>({ focusKey: k('rows'), saveLastFocusedChild: true, focusable: list.length > 0 });

  if (failed !== null) return <PageMessage title="Couldn't load this" body={failed} failure />;
  const allIn = list.length > 0;
  const backdrop = series !== null ? backdropUrl(series) : null;
  return (
    <div class="detail-page rundown">
      {backdrop !== null ? (
        <div class="detail-backdrop">
          <img key={backdrop} src={backdrop} alt="" />
        </div>
      ) : null}
      <div class="rundown-scrim" />
      <div class="rundown-head">
        <div class="kicker mono-label ellipsis">{tallyUppercase(series?.Name ?? '')}</div>
        <div class="tab-line">
          <div ref={tabs.ref} class="tabs">
            <FocusGroup focusKey={k('tabs')}>
              {seasons.map((s, i) => (
                <SeasonTab key={s.Id} focusKey={k(`tab-${i}`)} label={seasonLabel(s)} current={i === tab} onSettle={() => i !== tab && showSeason(seasons, i)} onPress={() => onTab(i)} onDown={focusDown} />
              ))}
            </FocusGroup>
          </div>
          {allIn ? <div class="summary mono-label">{tallyUppercase(seasonSummaryText(seasonSummary(list)))}</div> : null}
        </div>
      </div>
      <div class="rundown-list">
        <ScrollPage revealMode="nearest" nearestBefore={45} nearestAfter={43} onScrollChange={(top) => setFade(top > 0)}>
          <div class="rundown-inner">
            {episodes.kind === 'error' ? <PageMessage title="Couldn't load this" body={episodes.message} failure /> : null}
            {episodes.kind === 'ready' && list.length === 0 ? <div class="rundown-empty mono-label">NO EPISODES</div> : null}
            <div ref={rows.ref} class="rundown-rows">
              <FocusGroup focusKey={k('rows')}>
                {list.map((ep, i) => {
                  const runtime = ep.RunTimeTicks ?? 0;
                  const position = ep.UserData?.PlaybackPositionTicks ?? 0;
                  const percent = resumePercent(position, runtime);
                  const inProgress = ep.UserData?.Played !== true && position > 0;
                  const isNextUp = nextUp !== null && nextUp.Id === ep.Id;
                  const status: EpisodeStatus = inProgress ? { kind: 'progress', percent: Math.max(1, percent) } : isNextUp ? { kind: 'nextUp' } : { kind: 'none' };
                  const still = ep.Id != null && ep.ImageTags?.Primary != null ? itemImage(ep.Id, ImageType.Primary, ep.ImageTags.Primary, STILL_W, STILL_H) : wideUrl(ep, STILL_W, STILL_H);
                  return (
                    <RowSlot key={ep.Id ?? String(i)}>
                      {(reveal) => (
                        <EpisodeRow
                          focusKey={rowKey(i)}
                          number={ep.ParentIndexNumber === 0 ? 'SPECIAL' : episodeNumber(ep.IndexNumber)}
                          title={ep.Name ?? ''}
                          meta={rowMeta(ep)}
                          focusedMeta={rowEnds(ep)}
                          overview={ep.Overview}
                          imageUrl={still}
                          progress={inProgress && percent >= 1 && percent <= 99 ? percent / 100 : null}
                          played={ep.UserData?.Played === true}
                          nextUp={isNextUp}
                          status={status}
                          onPress={() => playItem(ep)}
                          onFocus={() => {
                            lastRow.current = { seasonId, index: i };
                            reveal();
                            window.clearTimeout(focusTimer.current);
                            focusTimer.current = window.setTimeout(() => setFocusedRow(i), 250);
                          }}
                        />
                      )}
                    </RowSlot>
                  );
                })}
              </FocusGroup>
            </div>
            {guests.length > 0 ? (
              <MediaRow title="Guest stars" count={guests.length} focusKey={k('row-guests')}>
                {guests.map((p, i) => (
                  <PersonCard
                    key={String(i) + (p.Id ?? '')}
                    focusKey={k('guest-' + String(i))}
                    name={p.Name ?? ''}
                    role={personCardRole(p)}
                    imageUrl={personImage(p, PERSON_CARD)}
                    onPress={() => p.Id != null && push({ name: 'item', itemId: p.Id })}
                  />
                ))}
              </MediaRow>
            ) : null}
            {extras.length > 0 ? (
              <MediaRow title="Extras" count={extras.length} focusKey={k('row-extras')}>
                {extras.map((e, i) => (
                  <FrameCard
                    key={e.item.Id ?? String(i)}
                    focusKey={k('extra-' + String(i))}
                    width={371}
                    height={209}
                    imageUrl={wideUrl(e.item, 371, 209)}
                    kicker={e.kicker}
                    title={e.title}
                    onPress={() => playItem(e.item, true)}
                  />
                ))}
              </MediaRow>
            ) : null}
          </div>
        </ScrollPage>
        {fade ? <div class="rundown-fade" /> : null}
      </div>
      <Clock />
      <DetailDialogs dialog={dialog} setDialog={setDialog} pageKey={pk} onChanged={reload} />
    </div>
  );
}

export function SeasonPage(props: PageProps<Extract<Route, { name: 'season' }>>) {
  return <SeasonView page={props} seriesId={props.route.seriesId} seasonId={props.route.seasonId} episodeId={props.route.episodeId} />;
}
