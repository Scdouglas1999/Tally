/**
 * The person page (media/person/TallyPersonPage.kt): a header (their usual credit as the kicker, the name, a life
 * line, a 4-line biography that OK opens in full when it is cut, FAVORITE) with the portrait in a square frame,
 * then one row per kind of credit: Films, Shows, Episodes, newest first.
 */
import type { BaseItemDto } from '@jellyfin/sdk/lib/generated-client/models/base-item-dto';
import { ImageType } from '@jellyfin/sdk/lib/generated-client/models/image-type';
import { useEffect, useLayoutEffect, useState } from 'preact/hooks';
import { itemImage, wideUrl } from '../../api/images';
import { useFocusable } from '../../focus/focus';
import { IndicatorSquare, RowHeader } from '../../kit/Bits';
import { Button } from '../../kit/Button';
import { FrameCard } from '../../kit/FrameCard';
import { ItemCard } from '../../kit/ItemCard';
import { MediaRow } from '../../kit/MediaRow';
import { ScrollPage, usePageScroll } from '../../kit/ScrollPage';
import { resumePercent, tallyUppercase } from '../../util/format';
import { Clock, useCardKeys } from '../details/common';
import { DetailDialogs, cardMenu, type Dialog } from '../details/DetailDialogs';
import { loadCredits, loadPrimaryRole, setFavorite } from '../details/detailsData';
import { creditKicker, personLifeLine, roleLabel } from '../details/detailsFormat';
import { openDetails, playItem } from '../details/navigate';
import './person.css';

const PORTRAIT = 333;

type Credits = { kind: 'loading' } | { kind: 'error'; message: string } | { kind: 'ready'; items: BaseItemDto[] };

const ROWS: Array<{ type: 'Movie' | 'Series' | 'Episode'; title: string; key: string }> = [
  { type: 'Movie', title: 'Films', key: 'films' },
  { type: 'Series', title: 'Shows', key: 'shows' },
  { type: 'Episode', title: 'Episodes', key: 'episodes' },
];

/** The biography, 4 lines; focusable (the frame stands off the text) only when cut, and OK then opens it all. */
function Biography(props: { text: string; focusKey: string; onOpen: () => void; onFocus: () => void }) {
  const [cut, setCut] = useState(false);
  const f = useFocusable<HTMLDivElement>({ focusKey: props.focusKey, focusable: cut, onEnter: props.onOpen, onFocus: props.onFocus });
  useLayoutEffect(() => {
    const text = f.ref.current?.firstElementChild as HTMLElement | null | undefined;
    if (text != null) setCut(text.scrollHeight > text.clientHeight + 1);
  }, [props.text]);
  return (
    <div ref={f.ref} class="person-bio" onClick={cut ? props.onOpen : undefined}>
      <div class="text">{props.text}</div>
    </div>
  );
}

function CreditRow(props: { title: string; state: Credits; rowKey: string; cardKey: (i: number) => string; episodes: boolean }) {
  const s = props.state;
  if (s.kind === 'loading' || s.kind === 'error') {
    return (
      <div class="person-note">
        <RowHeader title={props.title} />
        <div class={'message mono-label' + (s.kind === 'error' ? ' failure' : '')}>{tallyUppercase(s.kind === 'error' ? s.message : 'Loading…')}</div>
      </div>
    );
  }
  if (s.items.length === 0) return null;
  return (
    <MediaRow title={props.title} count={s.items.length} focusKey={props.rowKey}>
      {s.items.map((it, i) =>
        props.episodes ? (
          <FrameCard
            key={it.Id}
            focusKey={props.cardKey(i)}
            width={371}
            height={209}
            imageUrl={wideUrl(it, 371, 209)}
            kicker={creditKicker(it)}
            title={it.Name ?? ''}
            progress={it.UserData?.Played === true ? null : resumePercent(it.UserData?.PlaybackPositionTicks ?? 0, it.RunTimeTicks ?? 0) / 100}
            favorite={it.UserData?.IsFavorite === true}
            onPress={() => openDetails(it)}
          />
        ) : (
          <ItemCard key={it.Id} focusKey={props.cardKey(i)} item={it} shape="poster" onPress={() => openDetails(it)} />
        ),
      )}
    </MediaRow>
  );
}

function PersonContent(props: { person: BaseItemDto; pageKey: string; refresh: () => void; setDialog: (d: Dialog | null) => void; keys: ReturnType<typeof useCardKeys> }) {
  const { person, pageKey, keys } = props;
  const id = person.Id ?? '';
  const k = (name: string): string => `${pageKey}-${name}`;
  const page = usePageScroll();
  const toTop = (): void => page.toTop();
  const [role, setRole] = useState<string | null>(null);
  const [credits, setCredits] = useState<Record<string, Credits>>({});

  useEffect(() => {
    loadPrimaryRole(id).then(setRole).catch(() => undefined);
    for (const row of ROWS) {
      loadCredits(id, row.type)
        .then((items) => setCredits((c) => ({ ...c, [row.key]: { kind: 'ready', items } })))
        .catch((e: unknown) => setCredits((c) => ({ ...c, [row.key]: { kind: 'error', message: e instanceof Error ? e.message : 'Could not load this row' } })));
    }
  }, [id]);

  const favorite = person.UserData?.IsFavorite === true;
  const lifeLine = personLifeLine(person.PremiereDate, person.EndDate, person.ProductionLocations?.[0]);
  const portrait = person.ImageTags?.Primary != null ? itemImage(id, ImageType.Primary, person.ImageTags.Primary, PORTRAIT, PORTRAIT) : null;
  const [portraitFailed, setPortraitFailed] = useState(false);
  const name = person.Name ?? '';

  keys.play.clear();
  keys.menu.clear();
  for (const row of ROWS) {
    const c = credits[row.key];
    if (c?.kind !== 'ready') continue;
    c.items.forEach((it, i) => {
      const key = k(`${row.key}-${i}`);
      if (row.type !== 'Series') keys.play.set(key, () => playItem(it));
      keys.menu.set(key, () => props.setDialog(cardMenu(it, key)));
    });
  }

  return (
    <>
      <div class="person-header">
        <div class="column">
          <div class="kicker mono-label ellipsis">{tallyUppercase(roleLabel(role))}</div>
          <div class="name clamp-2">{name}</div>
          {lifeLine !== null ? <div class="life mono-label ellipsis">{tallyUppercase(lifeLine)}</div> : null}
          {person.Overview != null && person.Overview.trim() !== '' ? (
            <Biography
              text={person.Overview}
              focusKey={k('bio')}
              onFocus={toTop}
              onOpen={() => props.setDialog({ kind: 'overview', title: name, text: person.Overview ?? '', returnKey: k('bio') })}
            />
          ) : null}
          <div class="actions">
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
          </div>
        </div>
        <div class="portrait">{portrait !== null && !portraitFailed ? <img src={portrait} alt={name} onError={() => setPortraitFailed(true)} /> : null}</div>
      </div>
      <div class="person-rows">
        {ROWS.map((row) => (
          <CreditRow
            key={row.key}
            title={row.title}
            state={credits[row.key] ?? { kind: 'loading' }}
            rowKey={k('row-' + row.key)}
            cardKey={(i) => k(`${row.key}-${i}`)}
            episodes={row.type === 'Episode'}
          />
        ))}
      </div>
    </>
  );
}

export function PersonPage(props: { item: BaseItemDto; pageKey: string; active: boolean; refresh: () => void }) {
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const keys = useCardKeys(props.active && dialog === null);
  return (
    <div class="person-page">
      <ScrollPage revealMode="nearest" nearestBefore={43} nearestAfter={38}>
        <PersonContent person={props.item} pageKey={props.pageKey} refresh={props.refresh} setDialog={setDialog} keys={keys} />
      </ScrollPage>
      <Clock />
      <DetailDialogs dialog={dialog} setDialog={setDialog} pageKey={props.pageKey} onChanged={props.refresh} />
    </div>
  );
}
