import type { BaseItemDto } from '@jellyfin/sdk/lib/generated-client/models/base-item-dto';
import { useEffect, useRef, useState } from 'preact/hooks';
import { backdropUrl, logoUrl, posterUrl } from '../../api/images';
import type { PageProps } from '../../app/page';
import { currentFocusKey, focusExists, setFocus, useFocusable } from '../../focus/focus';
import { LabelBar } from '../../kit/Bits';
import { Button } from '../../kit/Button';
import { MediaRow, useRowReveal } from '../../kit/MediaRow';
import { useBack, useKeyHandler } from '../../platform/keyRouter';
import { back, push, resetTo, type Route } from '../../router/router';
import { formatRuntime, tallyUppercase } from '../../util/format';
import { loadItem, loadSimilar } from '../player/playerData';
import './postplay.css';

const POSTER_W = 211;
const POSTER_H = 317;

/** `2008 · PG-13 · 2h 28m`. */
export function postPlayMeta(film: BaseItemDto): string {
  const parts: string[] = [];
  if (film.ProductionYear != null) parts.push(String(film.ProductionYear));
  if (film.OfficialRating != null && film.OfficialRating.trim() !== '') parts.push(film.OfficialRating);
  if (film.RunTimeTicks != null && film.RunTimeTicks > 0) parts.push(formatRuntime(film.RunTimeTicks));
  return tallyUppercase(parts.join(' · '));
}

function SimilarPoster(props: { item: BaseItemDto; focusKey: string; onPress: () => void }) {
  const reveal = useRowReveal();
  const f = useFocusable<HTMLDivElement>({
    focusKey: props.focusKey,
    onEnter: props.onPress,
    // UP from any poster goes to Watch again (as on Android), not to the nearest button
    onArrow: (d) => {
      if (d !== 'up') return true;
      setFocus('postplay-watch');
      return false;
    },
    onFocus: () => {
      if (f.ref.current !== null) reveal(f.ref.current);
    },
  });
  const url = posterUrl(props.item, POSTER_W, POSTER_H);
  const [failed, setFailed] = useState(false);
  return (
    <div ref={f.ref} class="card poster postplay-poster" onClick={props.onPress}>
      <div class="art">{url !== null && !failed ? <img src={url} alt="" onError={() => setFailed(true)} /> : <div class="art-fallback">{props.item.Name ?? ''}</div>}</div>
      <LabelBar text={props.item.Name ?? ''} />
    </div>
  );
}

/** Sends DOWN from the buttons to the first poster (the buttons are the kit's, with no arrow hook). */
function DownToPosters(props: { enabled: boolean }) {
  useKeyHandler((key) => {
    if (!props.enabled || key !== 'down') return false;
    const current = currentFocusKey();
    if (current !== 'postplay-watch' && current !== 'postplay-done') return false;
    setFocus('postplay-poster-0');
    return true;
  }, props.enabled);
  return null;
}

/**
 * Shown when a film ends with nothing queued after it (postplay/PostPlayPage.kt): the film's backdrop dimmed, YOU
 * JUST WATCHED, its logo or title and meta line, Watch again / Done, and More like this. BACK does what Done does
 * (back to where the film was started; Home if there is nothing under it), so it never leaves the app.
 */
export function PostPlayPage(props: PageProps<Extract<Route, { name: 'postplay' }>>) {
  const [film, setFilm] = useState<BaseItemDto | null>(null);
  const [similar, setSimilar] = useState<BaseItemDto[] | null>(null);
  const [logoFailed, setLogoFailed] = useState(false);
  const [backdropFailed, setBackdropFailed] = useState(false);
  const placed = useRef(false);

  useEffect(() => {
    let alive = true;
    loadItem(props.route.itemId)
      .then((f) => alive && setFilm(f))
      .catch(() => undefined);
    loadSimilar(props.route.itemId)
      .then((s) => alive && setSimilar(s))
      .catch(() => alive && setSimilar([]));
    return () => {
      alive = false;
    };
  }, [props.route.itemId]);

  const done = (): void => {
    if (!back()) resetTo({ name: 'home' });
  };
  useBack(done, props.active);

  // first focus: the first similar poster when there are some, else Watch again
  useEffect(() => {
    if (placed.current || film === null || !props.active) return;
    if (similar === null) {
      if (focusExists('postplay-watch')) setFocus('postplay-watch');
      return;
    }
    placed.current = true;
    setFocus(similar.length > 0 ? 'postplay-poster-0' : 'postplay-watch');
  }, [film, similar, props.active]);

  const backdrop = film !== null ? backdropUrl(film, 1920) : null;
  const logo = film !== null ? logoUrl(film, 576) : null;
  return (
    <div class="postplay">
      {backdrop !== null && !backdropFailed ? (
        <div class="postplay-backdrop">
          <img src={backdrop} alt="" onError={() => setBackdropFailed(true)} />
        </div>
      ) : null}
      {film !== null ? (
        <div class="postplay-column">
          <div class="kicker mono-label">YOU JUST WATCHED</div>
          {logo !== null && !logoFailed ? (
            <div class="logo">
              <img src={logo} alt={film.Name ?? ''} onError={() => setLogoFailed(true)} />
            </div>
          ) : (
            <div class="title clamp-2">{film.Name ?? ''}</div>
          )}
          {postPlayMeta(film) !== '' ? <div class="meta mono-label ellipsis">{postPlayMeta(film)}</div> : null}
          <div class="buttons">
            <Button focusKey="postplay-watch" label="Watch again" primary onPress={() => film.Id != null && push({ name: 'player', itemId: film.Id, startMs: 0 })} />
            <Button focusKey="postplay-done" label="Done" onPress={done} />
            {/* DOWN from either button: the first poster (Android's focusProperties) */}
            <DownToPosters enabled={similar !== null && similar.length > 0} />
          </div>
        </div>
      ) : null}
      {similar !== null && similar.length > 0 ? (
        <div class="postplay-similar">
          <MediaRow title="More like this" focusKey="postplay-row">
            {similar.map((s, i) => (
              <SimilarPoster key={s.Id ?? String(i)} item={s} focusKey={'postplay-poster-' + String(i)} onPress={() => s.Id != null && push({ name: 'item', itemId: s.Id })} />
            ))}
          </MediaRow>
        </div>
      ) : null}
    </div>
  );
}
