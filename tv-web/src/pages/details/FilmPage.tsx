/**
 * The film page (media/movie/TallyMoviePage.kt): DetailHeader with the action row (PLAY / RESUME, FROM THE START,
 * TRAILER, WATCHED, FAVORITE, MORE), then Cast & crew, Chapters, Extras, the collection's next film and More like
 * this. Also videos (kicker VIDEO).
 */
import type { BaseItemDto } from '@jellyfin/sdk/lib/generated-client/models/base-item-dto';
import { useEffect, useState } from 'preact/hooks';
import { backdropUrl, logoUrl, wideUrl } from '../../api/images';
import { Button } from '../../kit/Button';
import { IndicatorSquare } from '../../kit/Bits';
import { DetailHeader } from '../../kit/DetailHeader';
import { FrameCard, PersonCard, PERSON_CARD } from '../../kit/FrameCard';
import { ItemCard } from '../../kit/ItemCard';
import { MediaRow } from '../../kit/MediaRow';
import { push } from '../../router/router';
import { resumePercent } from '../../util/format';
import { ActionRow, Backdrop, Clock, DetailScroll, TopScrim, chapterImage, personImage, trailerList, useCardKeys, useHeaderReveal } from './common';
import { DetailDialogs, cardMenu, onTrailer, type Dialog } from './DetailDialogs';
import { loadCollectionNext, loadLocalTrailers, loadSimilar, loadSpecialFeatures, setFavorite, setPlayed } from './detailsData';
import { chapterKicker, chaptersOf, directorLine, endsLine, extrasOf, filmMeta, personCardRole, resumeShare, techBoxes, type Extra } from './detailsFormat';
import { openDetails, playItem } from './navigate';

const LANDSCAPE_W = 371;
const LANDSCAPE_H = 209;

function FilmContent(props: {
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
  const [extras, setExtras] = useState<Extra[]>([]);
  const [similar, setSimilar] = useState<BaseItemDto[]>([]);
  const [local, setLocal] = useState<BaseItemDto[]>([]);
  const [next, setNext] = useState<BaseItemDto | null>(null);

  useEffect(() => {
    loadSpecialFeatures(id).then((f) => setExtras(extrasOf(f))).catch(() => undefined);
    loadSimilar(id).then(setSimilar).catch(() => undefined);
    loadLocalTrailers(id).then(setLocal).catch(() => undefined);
    loadCollectionNext(item).then(setNext).catch(() => undefined);
  }, [id]);

  const resume = resumeShare(item);
  const played = item.UserData?.Played === true;
  const favorite = item.UserData?.IsFavorite === true;
  const trailers = trailerList(item, local);
  const people = item.People ?? [];
  const chapters = chaptersOf(item);
  const menu = (): void => props.setDialog({ kind: 'menu', item, returnKey: k('more') });
  const toggle = (action: Promise<void>): void => {
    action.then(props.refresh).catch(() => undefined);
  };

  // PLAY / MENU on the cards
  keys.play.clear();
  keys.menu.clear();
  chapters.forEach((c, i) => {
    keys.play.set(k('chapter-' + String(i)), () => push({ name: 'player', itemId: id, startMs: Math.floor(c.positionTicks / 10_000) }));
    keys.menu.set(k('chapter-' + String(i)), () => props.setDialog({ kind: 'menu', item, returnKey: k('chapter-' + String(i)) }));
  });
  people.forEach((p, i) => keys.menu.set(k('person-' + String(i)), () => props.setDialog({ kind: 'person', personId: p.Id ?? '', name: p.Name ?? '', returnKey: k('person-' + String(i)) })));
  extras.forEach((e, i) => {
    keys.play.set(k('extra-' + String(i)), () => playItem(e.item, true));
    keys.menu.set(k('extra-' + String(i)), () => props.setDialog(cardMenu(e.item, k('extra-' + String(i)))));
  });
  const posters = (prefix: string, items: BaseItemDto[]): void =>
    items.forEach((it, i) => {
      keys.play.set(k(prefix + String(i)), () => playItem(it));
      keys.menu.set(k(prefix + String(i)), () => props.setDialog(cardMenu(it, k(prefix + String(i)))));
    });
  posters('next-', next !== null ? [next] : []);
  posters('similar-', similar);

  return (
    <>
      <DetailHeader
        kicker={item.Type === 'Video' ? 'Video' : 'Film'}
        title={item.Name ?? ''}
        logoUrl={logoUrl(item)}
        meta={filmMeta(item)}
        ends={endsLine(item)}
        genres={item.Genres ?? []}
        tagline={item.Taglines?.[0] ?? null}
        overview={item.Overview}
        overviewKey={k('overview')}
        onOverview={() => props.setDialog({ kind: 'overview', title: item.Name ?? '', text: item.Overview ?? '', returnKey: k('overview') })}
        onOverviewFocus={toTop}
        director={directorLine(item)}
        tech={techBoxes(item.MediaSources?.[0])}
      >
        <ActionRow focusKey={k('actions')} primaryKey={k('play')}>
          <Button
            focusKey={k('play')}
            primary
            glyph="play"
            label={resume !== null ? `Resume ${resume}%` : 'Play'}
            onPress={() => playItem(item, resume === null)}
            onFocus={toTop}
          />
          {resume !== null ? <Button focusKey={k('restart')} glyph="rotateLeft" label="From the start" onPress={() => playItem(item, true)} onFocus={toTop} /> : null}
          {trailers.length > 0 ? (
            <Button
              focusKey={k('trailer')}
              glyph="film"
              label="Trailer"
              onPress={() => onTrailer(trailers, () => props.setDialog({ kind: 'trailers', title: 'Trailers', trailers, returnKey: k('trailer') }))}
              onFocus={toTop}
            />
          ) : null}
          <Button focusKey={k('watched')} glyph={played ? 'eye' : 'eyeSlash'} label={played ? 'Watched' : 'Unwatched'} onPress={() => toggle(setPlayed(id, !played))} onFocus={toTop} />
          <Button
            focusKey={k('favorite')}
            glyph="heart"
            label={favorite ? 'Favorited' : 'Favorite'}
            trailing={favorite ? <IndicatorSquare tone="accent" /> : undefined}
            onPress={() => toggle(setFavorite(id, !favorite))}
            onFocus={toTop}
          />
          <Button focusKey={k('more')} glyph="ellipsis" label="More" onPress={menu} onFocus={toTop} />
        </ActionRow>
      </DetailHeader>
      <div class="detail-rows">
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
        {chapters.length > 0 ? (
          <MediaRow title="Chapters" count={chapters.length} focusKey={k('row-chapters')}>
            {chapters.map((c, i) => (
              <FrameCard
                key={String(i)}
                focusKey={k('chapter-' + String(i))}
                width={LANDSCAPE_W}
                height={LANDSCAPE_H}
                imageUrl={chapterImage(id, c)}
                kicker={chapterKicker(c)}
                title={c.name}
                onPress={() => push({ name: 'player', itemId: id, startMs: Math.floor(c.positionTicks / 10_000) })}
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
        {next !== null ? (
          <MediaRow title="Next in the collection" count={1} focusKey={k('row-next')}>
            <ItemCard focusKey={k('next-0')} item={next} shape="poster" onPress={() => openDetails(next)} />
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

export function FilmPage(props: { item: BaseItemDto; pageKey: string; active: boolean; refresh: () => void }) {
  const [scrolled, setScrolled] = useState(false);
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const keys = useCardKeys(props.active && dialog === null);
  return (
    <div class="detail-page">
      <Backdrop url={backdropUrl(props.item)} />
      <DetailScroll onScrolled={setScrolled}>
        <FilmContent item={props.item} pageKey={props.pageKey} refresh={props.refresh} setDialog={setDialog} keys={keys} />
      </DetailScroll>
      <TopScrim visible={scrolled} />
      <Clock />
      <DetailDialogs dialog={dialog} setDialog={setDialog} pageKey={props.pageKey} onChanged={props.refresh} />
    </div>
  );
}
