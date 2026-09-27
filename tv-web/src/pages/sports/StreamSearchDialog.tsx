import { useLayoutEffect, useRef } from 'preact/hooks';
import { isLive } from '../../api/tallyModels';
import { currentFocusKey, focusExists, FocusGroup, setFocus, useFocusable } from '../../focus/focus';
import { Button } from '../../kit/Button';
import { Lamp } from '../../kit/Lamp';
import { useKeyHandler } from '../../platform/keyRouter';
import { matchupTitle } from '../../sports/SportsBits';
import { gameStatusLabel, tallyUppercase } from '../../util/format';
import { useStore } from '../../util/store';
import { cancelStreamSearch, keepLooking, streamSearch, type StreamSearchView } from './streamSearch';
import './streamSearch.css';

const GROUP = 'stream-search';
const CANCEL = 'stream-search-cancel';
const OK = 'stream-search-ok';
const KEEP = 'stream-search-keep';

/** A press this soon after the dialog (or its message) came up is the OK that opened it, still repeating. */
const ARM_MS = 400;

export const NO_STREAM_MESSAGE = 'No stream for this game yet. Tally keeps looking and will light it up when one appears.';

/**
 * "Looking for a stream…" as a small Tally dialog (the confirm dialog's family: 60% scrim, hairline frame, the accent
 * kicker, a rule, square buttons). While searching: the kicker's lamp sputters, the matchup, and CANCEL (focused).
 * After 45 s without a stream: the message, OK (focused) and KEEP LOOKING. BACK closes it; the Magic Remote's pointer
 * hovers and clicks the buttons like any others.
 */
function StreamSearchDialog(props: { view: StreamSearchView }) {
  const { view } = props;
  const { game } = view;
  const group = useFocusable<HTMLDivElement>({ focusKey: GROUP, isFocusBoundary: true });
  const shownAt = useRef(Date.now());
  const armed = (): boolean => Date.now() - shownAt.current >= ARM_MS;
  useKeyHandler((key) => {
    if (key !== 'back') return false;
    cancelStreamSearch();
    return true;
  });
  // focus on the phase's safe button before the first paint (an OK pressed the moment it shows must reach it)
  useLayoutEffect(() => {
    shownAt.current = Date.now();
    setFocus(view.phase === 'searching' ? CANCEL : OK);
  }, [view.id, view.phase]);
  const searching = view.phase === 'searching';
  const status = tallyUppercase(`${game.league} · ${gameStatusLabel(game)}`);
  return (
    <div class="stream-search-scrim">
      <div ref={group.ref} class="stream-search" data-phase={view.phase}>
        <div class="kicker mono-label">
          <Lamp state={searching ? 'sputtering' : 'off'} size={16} glow={false} />
          <span class="ellipsis">{searching ? tallyUppercase('Looking for a stream…') : tallyUppercase('No stream yet')}</span>
        </div>
        <div class="rule" />
        <div class="title clamp-2">{matchupTitle(game)}</div>
        <div class={'status mono-label ellipsis' + (isLive(game) ? ' live' : '')}>{status}</div>
        <div class="message">{searching ? 'It plays as soon as one appears.' : NO_STREAM_MESSAGE}</div>
        <div class="buttons">
          <FocusGroup focusKey={GROUP}>
            {searching ? (
              <Button focusKey={CANCEL} label="Cancel" onPress={() => armed() && cancelStreamSearch()} />
            ) : (
              <>
                <Button focusKey={OK} label="OK" onPress={() => armed() && cancelStreamSearch()} />
                <Button focusKey={KEEP} label="Keep looking" onPress={() => armed() && keepLooking()} />
              </>
            )}
          </FocusGroup>
        </div>
      </div>
    </div>
  );
}

/**
 * Where the stream search shows: each page that can start one renders a host (as it does the ToastHost); only the
 * page on screen draws it. Focus goes back to what had it (the card) when the dialog goes, before anything found
 * plays, so BACK from the player returns there.
 */
export function StreamSearchHost(props: { active: boolean; pageKey: string }) {
  const view = useStore(streamSearch);
  const returnKey = useRef<string | null>(null);
  const shown = props.active && view !== null;
  if (shown && returnKey.current === null) returnKey.current = currentFocusKey();
  useLayoutEffect(() => {
    if (shown || returnKey.current === null) return;
    const back = returnKey.current;
    returnKey.current = null;
    setFocus(back.indexOf(GROUP) !== 0 && focusExists(back) ? back : props.pageKey);
  }, [shown]);
  if (!shown || view === null) return null;
  return <StreamSearchDialog view={view} />;
}

/** True while the search dialog is up (pages keep their own keys and holds away from it). */
export function useStreamSearchOpen(): boolean {
  return useStore(streamSearch) !== null;
}
