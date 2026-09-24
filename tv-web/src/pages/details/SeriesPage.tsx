/**
 * The series page (media/series/TallySeriesPage.kt): DetailHeader (SERIES, years, seasons, episodes, created by)
 * with NEXT UP · S1 E2 / RESUME … / PLAY, SEASONS, TRAILER, WATCHED (confirmed: it marks every episode), FAVORITE,
 * MORE; then Seasons, Cast & crew, Extras, More like this. SEASONS and the season cards open the rundown.
 */
import type { BaseItemDto } from '@jellyfin/sdk/lib/generated-client/models/base-item-dto';
import { useEffect, useState } from 'preact/hooks';
import { backdropUrl, logoUrl, posterUrl, wideUrl } from '../../api/images';
import { Button } from '../../kit/Button';
import { IndicatorSquare } from '../../kit/Bits';
import { DetailHeader } from '../../kit/DetailHeader';
import { FrameCard, PersonCard, PERSON_CARD } from '../../kit/FrameCard';
import { ItemCard } from '../../kit/ItemCard';
import { MediaRow } from '../../kit/MediaRow';
import { push } from '../../router/router';
import { resumePercent } from '../../util/format';
import { ActionRow, Backdrop, Clock, DetailScroll, TopScrim, personImage, useCardKeys, useHeaderReveal, trailerList } from './common';
import { DetailDialogs, cardMenu, onTrailer, type Dialog } from './DetailDialogs';
import { loadEpisodes, loadLocalTrailers, loadNextUp, loadSeasons, loadSimilar, loadSpecialFeatures, setFavorite, setPlayed } from './detailsData';
import { createdByLine, extrasOf, nextUpLabel, nextUpText, personCardRole, seasonWatchedFraction, seriesMeta, type Extra } from './detailsFormat';
import { openDetails, playItem } from './navigate';

const POSTER_W = 211;
const POSTER_H = 317;
const LANDSCAPE_W = 371;
const LANDSCAPE_H = 209;

/** A season's poster: `4 LEFT` in accent while episodes are unplayed, a WATCHED tick once all are, the share watched. */
export function SeasonCard(props: { season: BaseItemDto; focusKey: string; onPress: () => void }) {
  const s = props.season;
  const played = s.UserData?.Played === true;
  const unplayed = s.UserData?.UnplayedItemCount ?? 0;
  const watched = seasonWatchedFraction(s);
  return (
    <FrameCard
      focusKey={props.focusKey}
      width={POSTER_W}
      height={POSTER_H}
      imageUrl={posterUrl(s, POSTER_W, POSTER_H)}
      title={s.Name ?? ''}
      detail={s.ProductionYear != null ? String(s.ProductionYear) : null}
      tag={played ? 'Watched' : unplayed > 0 ? `${unplayed} left` : null}
      tagAccent={!played && unplayed > 0}
      tagGlyph={played ? 'check' : undefined}
      progress={!played ? watched : null}
      favorite={s.UserData?.IsFavorite === true}
      onPress={props.onPress}
    />
  );
}

function SeriesContent(props: {
  item: BaseItemDto;
  pageKey: string;
  refresh: () => void;
  setDialog: (d: Dialog | null) => void;
  keys: ReturnType<typeof useCardKeys>;
}) {
  const { item, pageKey, keys } = props;
  const id = item.Id ?? '';
  const k = (name: string): string => `${pageKey}-${name}`;
  const toTop = useHeaderReveal();
  const [seasons, setSeasons] = useState<BaseItemDto[]>([]);
  const [nextUp, setNextUp] = useState<BaseItemDto | null>(null);
  const [extras, setExtras] = useState<Extra[]>([]);
  const [similar, setSimilar] = useState<BaseItemDto[]>([]);
  const [local, setLocal] = useState<BaseItemDto[]>([]);

  useEffect(() => {
    loadSpecialFeatures(id).then((f) => setExtras(extrasOf(f))).catch(() => undefined);
    loadSimilar(id).then(setSimilar).catch(() => undefined);
    loadLocalTrailers(id).then(setLocal).catch(() => undefined);
  }, [id]);
  // watched and favorite changes reload the series: Next Up and the seasons' share watched with it
  useEffect(() => {
    loadSeasons(id).then(setSeasons).catch(() => undefined);
    loadNextUp(id).then(setNextUp).catch(() => undefined);
  }, [item]);

  const played = item.UserData?.Played === true;
  const favorite = item.UserData?.IsFavorite === true;
  const trailers = trailerList(item, local);
  const people = item.People ?? [];
  const label = nextUpLabel(nextUp);

  const play = (): void => {
    if (nextUp !== null) {
      playItem(nextUp);
      return;
    }
    // nothing to continue: the first episode of the first season
    const first = seasons[0];
    if (first?.Id == null) return;
    loadEpisodes(id, first.Id)
      .then((eps) => {
        const e = eps[0];
        if (e !== undefined) playItem(e, true);
      })
      .catch(() => undefined);
  };
  const openSeasons = (): void => {
    if (nextUp?.SeasonId != null) push({ name: 'season', seriesId: id, seasonId: nextUp.SeasonId, episodeId: nextUp.Id ?? undefined });
    else push({ name: 'season', seriesId: id, seasonId: seasons[0]?.Id ?? undefined });
  };
  const confirmWatched = (): void =>
    props.setDialog({
      kind: 'confirm',
      title: item.Name ?? '',
      body: played ? 'Mark entire series as unplayed?' : 'Mark entire series as played?',
      confirmLabel: played ? 'Mark unwatched' : 'Mark watched',
      onConfirm: () => {
        setPlayed(id, !played).then(props.refresh).catch(() => undefined);
      },
      returnKey: k('watched'),
    });

  keys.play.clear();
  keys.menu.clear();
  people.forEach((p, i) => keys.menu.set(k('person-' + String(i)), () => props.setDialog({ kind: 'person', personId: p.Id ?? '', name: p.Name ?? '', returnKey: k('person-' + String(i)) })));
  seasons.forEach((s, i) => keys.menu.set(k('season-' + String(i)), () => props.setDialog(cardMenu(s, k('season-' + String(i))))));
  extras.forEach((e, i) => {
    keys.play.set(k('extra-' + String(i)), () => playItem(e.item, true));
    keys.menu.set(k('extra-' + String(i)), () => props.setDialog(cardMenu(e.item, k('extra-' + String(i)))));
  });
  similar.forEach((s, i) => keys.menu.set(k('similar-' + String(i)), () => props.setDialog(cardMenu(s, k('similar-' + String(i))))));

  return (
    <>
      <DetailHeader
        kicker="Series"
        title={item.Name ?? ''}
        logoUrl={logoUrl(item)}
        meta={seriesMeta(item, seasons.length)}
        genres={item.Genres ?? []}
        overview={item.Overview}
        overviewKey={k('overview')}
        onOverview={() => props.setDialog({ kind: 'overview', title: item.Name ?? '', text: item.Overview ?? '', returnKey: k('overview') })}
        onOverviewFocus={toTop}
        director={createdByLine(item)}
        textWidth={896}
      >
        <ActionRow focusKey={k('actions')} primaryKey={k('play')}>
          <Button focusKey={k('play')} primary glyph="play" label={nextUpText(label)} onPress={play} onFocus={toTop} />
          <Button focusKey={k('seasons')} glyph="listUl" label="Seasons" onPress={openSeasons} onFocus={toTop} />
          {trailers.length > 0 ? (
            <Button
              focusKey={k('trailer')}
              glyph="film"
              label="Trailer"
              onPress={() => onTrailer(trailers, () => props.setDialog({ kind: 'trailers', title: 'Trailers', trailers, returnKey: k('trailer') }))}
              onFocus={toTop}
            />
          ) : null}
          <Button focusKey={k('watched')} glyph={played ? 'eye' : 'eyeSlash'} label={played ? 'Watched' : 'Unwatched'} onPress={confirmWatched} onFocus={toTop} />
          <Button
            focusKey={k('favorite')}
            glyph="heart"
            label={favorite ? 'Favorited' : 'Favorite'}
            trailing={favorite ? <IndicatorSquare tone="accent" /> : undefined}
            onPress={() => {
              setFavorite(id, !favorite).then(props.refresh).catch(() => undefined);
            }}
            onFocus={toTop}
          />
          <Button focusKey={k('more')} glyph="ellipsis" label="More" onPress={() => props.setDialog({ kind: 'menu', item, returnKey: k('more') })} onFocus={toTop} />
        </ActionRow>
      </DetailHeader>
      <div class="detail-rows">
        {seasons.length > 0 ? (
          <MediaRow title="Seasons" count={seasons.length} focusKey={k('row-seasons')}>
            {seasons.map((s, i) => (
              <SeasonCard key={s.Id} focusKey={k('season-' + String(i))} season={s} onPress={() => openDetails(s)} />
            ))}
          </MediaRow>
        ) : null}
        {people.length > 0 ? (
          <MediaRow title="Cast & crew" count={people.length} focusKey={k('row-people')}>
            {people.map((p, i) => (
              <PersonCard
                key={String(i) + (p.Id ?? '')}
                focusKey={k('person-' + String(i))}
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
                width={LANDSCAPE_W}
                height={LANDSCAPE_H}
                imageUrl={wideUrl(e.item, LANDSCAPE_W, LANDSCAPE_H)}
                kicker={e.kicker}
                title={e.title}
                progress={e.item.UserData?.Played === true ? null : resumePercent(e.item.UserData?.PlaybackPositionTicks ?? 0, e.item.RunTimeTicks ?? 0) / 100}
                favorite={e.item.UserData?.IsFavorite === true}
                onPress={() => playItem(e.item, true)}
              />
            ))}
          </MediaRow>
        ) : null}
        {similar.length > 0 ? (
          <MediaRow title="More like this" count={similar.length} focusKey={k('row-similar')}>
            {similar.map((s, i) => (
              <ItemCard key={s.Id} focusKey={k('similar-' + String(i))} item={s} shape="poster" onPress={() => openDetails(s)} />
            ))}
          </MediaRow>
        ) : null}
      </div>
    </>
  );
}

export function SeriesPage(props: { item: BaseItemDto; pageKey: string; active: boolean; refresh: () => void }) {
  const [scrolled, setScrolled] = useState(false);
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const keys = useCardKeys(props.active && dialog === null);
  return (
    <div class="detail-page">
      <Backdrop url={backdropUrl(props.item)} />
      <DetailScroll onScrolled={setScrolled}>
        <SeriesContent item={props.item} pageKey={props.pageKey} refresh={props.refresh} setDialog={setDialog} keys={keys} />
      </DetailScroll>
      <TopScrim visible={scrolled} />
      <Clock />
      <DetailDialogs dialog={dialog} setDialog={setDialog} pageKey={props.pageKey} onChanged={props.refresh} />
    </div>
  );
}
