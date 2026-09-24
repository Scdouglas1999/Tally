import { useEffect, useRef, useState } from 'preact/hooks';
import { session } from '../api/jellyfin';
import { currentFocusKey, FocusGroup, focusExists, setFocus, useFocusable } from '../focus/focus';
import { back, stack, type Entry } from '../router/router';
import { loadNav } from '../state/nav';
import { useStore } from '../util/store';
import { SignInPage } from '../pages/signin/SignInPage';
import { Rail, RAIL_KEY } from './Rail';
import { chromeOf, PAGES } from './routes';
import { pageFocusKey } from './pageKeys';
import type { PageProps } from './page';

function PageFrame(props: { entry: Entry; active: boolean }) {
  const key = pageFocusKey(props.entry.id);
  const full = chromeOf(props.entry.route) === 'full';
  const f = useFocusable<HTMLDivElement>({
    focusKey: key,
    saveLastFocusedChild: true,
    focusable: props.active,
    isFocusBoundary: true,
    // LEFT leaves a rail page for the rail; full-screen pages keep focus in every direction
    focusBoundaryDirections: full ? ['left', 'right', 'up', 'down'] : ['right', 'up', 'down'],
  });
  const Page = PAGES[props.entry.route.name].page as (p: PageProps) => preact.JSX.Element;
  return (
    <div ref={f.ref} class={'page' + (props.active ? '' : ' hidden')} data-entry={props.entry.id}>
      <FocusGroup focusKey={key}>
        <Page route={props.entry.route} active={props.active} pageKey={key} />
      </FocusGroup>
    </div>
  );
}

/** The signed-in app: the rail (on rail pages) and the stack of pages, the top one visible. */
function Frame() {
  const entries = useStore(stack);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const top = entries[entries.length - 1] as Entry;
  const full = chromeOf(top.route) === 'full';

  useEffect(() => {
    void loadNav();
  }, []);

  // a new top page (opened, or uncovered by BACK) takes focus: its last focus, or its arrival focus. After the
  // pages' own effects: the uncovered page must be focusable again before its saved focus can be restored.
  // A page being covered remembers exactly what had focus in it (a group that does not keep its last child, such
  // as a library's controls, would otherwise hand focus to its first one on the way back).
  const covered = useRef(new Map<number, string>());
  const shown = useRef(top.id);
  useEffect(() => {
    const previous = shown.current;
    shown.current = top.id;
    if (previous !== top.id && entries.some((e) => e.id === previous)) {
      const el = document.querySelector('[data-focused]');
      if (el !== null && el.closest(`.page[data-entry="${previous}"]`) !== null) covered.current.set(previous, currentFocusKey());
    }
    for (const id of Array.from(covered.current.keys())) if (!entries.some((e) => e.id === id)) covered.current.delete(id);
    const saved = covered.current.get(top.id);
    covered.current.delete(top.id);
    const t = window.setTimeout(() => setFocus(saved !== undefined && focusExists(saved) ? saved : pageFocusKey(top.id)), 0);
    return () => window.clearTimeout(t);
  }, [top.id]);

  return (
    <>
      {full ? null : <Rail onOpenChange={setDrawerOpen} />}
      <div class={'page-area' + (full ? ' full' : '') + (drawerOpen && !full ? ' pushed' : '')}>
        {entries.map((e) => (
          <PageFrame key={e.id} entry={e} active={e.id === top.id} />
        ))}
        {drawerOpen && !full ? <div class="page-scrim" /> : null}
      </div>
    </>
  );
}

/** BACK that nothing else used: the previous page; on Home the drawer opens; BACK in the open drawer leaves. */
export function rootBack(exit: () => void): void {
  if (session.get() === null) {
    exit();
    return;
  }
  const current = document.querySelector('.rail.open') !== null;
  if (current) {
    const s = stack.get();
    if (s.length <= 1) {
      exit();
      return;
    }
    setFocus(pageFocusKey((s[s.length - 1] as Entry).id));
    return;
  }
  if (!back()) setFocus(RAIL_KEY);
}

export function App(props: { onFirstScreen: () => void }) {
  const s = useStore(session);
  useEffect(() => {
    props.onFirstScreen();
  }, []);
  return s === null ? <SignInPage /> : <Frame key={s.serverId + s.userId} />;
}
