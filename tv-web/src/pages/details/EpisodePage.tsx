/**
 * The episode page (media/episode/TallyEpisodePage.kt): DetailHeader (the series and code as an accent kicker,
 * air date, runtime, ENDS, director, tech boxes) with PLAY / RESUME, FROM THE START, WATCHED, FAVORITE, EPISODES
 * (the rundown on this episode), MORE; then Cast & crew, Chapters and More from Season N.
 */
import type { BaseItemDto } from '@jellyfin/sdk/lib/generated-client/models/base-item-dto';
import { useEffect, useState } from 'preact/hooks';
import { backdropUrl, wideUrl } from '../../api/images';
import { Button } from '../../kit/Button';
import { IndicatorSquare } from '../../kit/Bits';
import { DetailHeader } from '../../kit/DetailHeader';
import { FrameCard, PersonCard, PERSON_CARD } from '../../kit/FrameCard';
import { MediaRow } from '../../kit/MediaRow';
import { push } from '../../router/router';
import { resumePercent } from '../../util/format';
import { ActionRow, Backdrop, Clock, DetailScroll, TopScrim, personImage, useCardKeys, useHeaderReveal, chapterImage } from './common';
import { DetailDialogs, cardMenu, type Dialog } from './DetailDialogs';
import { loadEpisodes, setFavorite, setPlayed } from './detailsData';
import { chapterKicker, chaptersOf, directorLine, endsLine, episodeCode, episodeMeta, personCardRole, resumeShare, seasonCardKicker, techBoxes } from './detailsFormat';
import { openItemPage, playItem } from './navigate';

const LANDSCAPE_W = 371;
const LANDSCAPE_H = 209;

/** `Season 2`, `Specials`, or the season's name. */
function seasonName(episode: BaseItemDto): string {
  const n = episode.ParentIndexNumber;
  if (n === 0) return 'Specials';
  if (n == null) return episode.SeasonName ?? '';
  return `Season ${n}`;
}

function EpisodeContent(props: {
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
  const [seasonEpisodes, setSeasonEpisodes] = useState<BaseItemDto[]>([]);

  useEffect(() => {
    if (item.SeriesId == null || item.SeasonId == null) return;
    loadEpisodes(item.SeriesId, item.SeasonId).then(setSeasonEpisodes).catch(() => undefined);
  }, [item]);

  const resume = resumeShare(item);
  const played = item.UserData?.Played === true;
  const favorite = item.UserData?.IsFavorite === true;
  const people = item.People ?? [];
  const chapters = chaptersOf(item);
  const index = seasonEpisodes.findIndex((e) => e.Id === id);
  const moreFromSeason = index >= 0 ? seasonEpisodes.slice(index + 1) : [];
  const code = episodeCode(item.ParentIndexNumber, item.IndexNumber, item.IndexNumberEnd);
  const toggle = (action: Promise<void>): void => {
    action.then(props.refresh).catch(() => undefined);
  };

  keys.play.clear();
  keys.menu.clear();
  people.forEach((p, i) => keys.menu.set(k('person-' + String(i)), () => props.setDialog({ kind: 'person', personId: p.Id ?? '', name: p.Name ?? '', returnKey: k('person-' + String(i)) })));
  chapters.forEach((c, i) => {
    keys.play.set(k('chapter-' + String(i)), () => push({ name: 'player', itemId: id, startMs: Math.floor(c.positionTicks / 10_000) }));
    keys.menu.set(k('chapter-' + String(i)), () => props.setDialog({ kind: 'menu', item, returnKey: k('chapter-' + String(i)) }));
  });
  moreFromSeason.forEach((e, i) => {
    keys.play.set(k('season-' + String(i)), () => playItem(e));
    keys.menu.set(k('season-' + String(i)), () => props.setDialog(cardMenu(e, k('season-' + String(i)), () => e.Id != null && openItemPage(e.Id))));
  });

  return (
    <>
      <DetailHeader
        kicker={[item.SeriesName ?? null, code].filter((x) => x !== null && x !== '').join(' · ')}
        kickerAccent
        title={item.Name ?? ''}
        meta={episodeMeta(item)}
        ends={endsLine(item)}
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
          <Button focusKey={k('watched')} glyph={played ? 'eye' : 'eyeSlash'} label={played ? 'Watched' : 'Unwatched'} onPress={() => toggle(setPlayed(id, !played))} onFocus={toTop} />
          <Button
            focusKey={k('favorite')}
            glyph="heart"
            label={favorite ? 'Favorited' : 'Favorite'}
            trailing={favorite ? <IndicatorSquare tone="accent" /> : undefined}
            onPress={() => toggle(setFavorite(id, !favorite))}
            onFocus={toTop}
          />
          {item.SeriesId != null && item.SeasonId != null ? (
            <Button
              focusKey={k('episodes')}
              glyph="listUl"
              label="Episodes"
              onPress={() => push({ name: 'season', seriesId: item.SeriesId ?? '', seasonId: item.SeasonId ?? undefined, episodeId: id })}
              onFocus={toTop}
            />
          ) : null}
          <Button focusKey={k('more')} glyph="ellipsis" label="More" onPress={() => props.setDialog({ kind: 'menu', item, returnKey: k('more') })} onFocus={toTop} />
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
        {moreFromSeason.length > 0 ? (
          <MediaRow title={`More from ${seasonName(item)}`} count={moreFromSeason.length} focusKey={k('row-season')}>
            {moreFromSeason.map((e, i) => (
              <FrameCard
                key={e.Id}
                focusKey={k('season-' + String(i))}
                width={LANDSCAPE_W}
                height={LANDSCAPE_H}
                imageUrl={wideUrl(e, LANDSCAPE_W, LANDSCAPE_H)}
                kicker={seasonCardKicker(e)}
                title={e.Name ?? ''}
                progress={e.UserData?.Played === true ? null : resumePercent(e.UserData?.PlaybackPositionTicks ?? 0, e.RunTimeTicks ?? 0) / 100}
                favorite={e.UserData?.IsFavorite === true}
                onPress={() => e.Id != null && openItemPage(e.Id)}
              />
            ))}
          </MediaRow>
        ) : null}
      </div>
    </>
  );
}

export function EpisodePage(props: { item: BaseItemDto; pageKey: string; active: boolean; refresh: () => void }) {
  const [scrolled, setScrolled] = useState(false);
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const keys = useCardKeys(props.active && dialog === null);
  return (
    <div class="detail-page">
      <Backdrop url={backdropUrl(props.item)} />
      <DetailScroll onScrolled={setScrolled}>
        <EpisodeContent item={props.item} pageKey={props.pageKey} refresh={props.refresh} setDialog={setDialog} keys={keys} />
      </DetailScroll>
      <TopScrim visible={scrolled} />
      <Clock />
      <DetailDialogs dialog={dialog} setDialog={setDialog} pageKey={props.pageKey} onChanged={props.refresh} />
    </div>
  );
}
