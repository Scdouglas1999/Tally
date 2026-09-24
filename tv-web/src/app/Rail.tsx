import { useEffect, useMemo } from 'preact/hooks';
import { itemImage } from '../api/images';
import { session } from '../api/jellyfin';
import { FocusGroup, setFocus, useFocusable } from '../focus/focus';
import { GlyphIcon, IndicatorSquare } from '../kit/Bits';
import { libraries, tally } from '../state/nav';
import { resetTo, stack } from '../router/router';
import { pageFocusKey } from './pageKeys';
import { useStore } from '../util/store';
import { drawerKeyFor, drawerModel, type DrawerItem } from './drawer';
import { railPitch } from './railPitch';
import { ImageType } from '@jellyfin/sdk/lib/generated-client/models/image-type';
import './rail.css';

export const RAIL_KEY = 'rail';
const LIST_TOP = 'rail-top';

/**
 * RIGHT from anywhere in the drawer returns to the page. (Geometry cannot find it: the page is pushed right with a
 * transform, which the focus system's offset measurements do not see.)
 */
function backToPage(direction: string): boolean {
  if (direction !== 'right') return true;
  const s = stack.get();
  const top = s[s.length - 1];
  if (top !== undefined) setFocus(pageFocusKey(top.id));
  return false;
}

function Entry(props: { item: DrawerItem; selected: boolean; pitch: number; open: boolean; onPress: (item: DrawerItem) => void }) {
  const f = useFocusable<HTMLDivElement>({ focusKey: 'rail-' + props.item.key, onEnter: () => props.onPress(props.item), onArrow: backToPage });
  const square = { width: `${props.pitch}px`, height: `${props.pitch}px` };
  return (
    <div class={'entry' + (props.selected ? ' selected' : '')} style={{ height: `${props.pitch}px` }}>
      {props.selected && !props.open ? <span class="light" /> : null}
      <div ref={f.ref} class="spot" style={props.open ? undefined : square} onClick={() => props.onPress(props.item)}>
        <GlyphIcon name={props.item.glyph} />
        <span class="slot">{props.selected ? <IndicatorSquare tone="accent" /> : null}</span>
        <span class="glyph-box">
          <GlyphIcon name={props.item.glyph} />
        </span>
        <span class="label ellipsis">{props.item.label}</span>
      </div>
    </div>
  );
}

function UserRow(props: { open: boolean }) {
  const s = useStore(session);
  const f = useFocusable<HTMLDivElement>({ focusKey: 'rail-user', onArrow: backToPage });
  if (s === null) return null;
  const image = s.userImageTag !== undefined ? itemImage(s.userId, ImageType.Primary, s.userImageTag, 64, 64) : null;
  return (
    <div class="entry user" style={{ height: '80px' }}>
      <div ref={f.ref} class="spot" style={props.open ? undefined : { width: '80px', height: '80px' }}>
        <span class="tile">{image !== null ? <img src={image} alt="" /> : (s.userName[0] ?? '?').toUpperCase()}</span>
        <span class="who">
          <div class="name ellipsis">{s.userName}</div>
          <div class="server ellipsis">{s.serverName}</div>
        </span>
      </div>
    </div>
  );
}

/**
 * The collapsed rail and the open drawer. It opens while focus is inside it (LEFT from a page's first column) and
 * closes when focus leaves. Choosing an entry starts a new stack on top of Home.
 */
export function Rail(props: { onOpenChange: (open: boolean) => void }) {
  const views = useStore(libraries);
  const plugin = useStore(tally);
  const entries = useStore(stack);
  const model = useMemo(() => drawerModel(views ?? [], plugin.kind === 'available'), [views, plugin.kind]);
  let selectedKey: string | null = null;
  for (let i = entries.length - 1; i >= 0 && selectedKey === null; i--) {
    const e = entries[i];
    if (e !== undefined) selectedKey = drawerKeyFor(e.route);
  }
  const group = useFocusable<HTMLDivElement>({
    focusKey: RAIL_KEY,
    trackChildren: true,
    saveLastFocusedChild: false,
    preferredChildFocusKey: selectedKey !== null ? 'rail-' + selectedKey : 'rail-home',
  });
  const open = group.hasFocusedChild;
  useEffect(() => props.onOpenChange(open), [open]);
  const rows = model.top.length + model.primary.length + model.libraries.length + model.sections.length + 1;
  const pitch = railPitch(rows, 3);
  const press = (item: DrawerItem): void => {
    const current = stack.get();
    const top = current[current.length - 1];
    if (current.length === 1 && item.route.name === 'home' && top !== undefined) {
      // Home while on Home: back to the page as it was
      setFocus(pageFocusKey(top.id));
      return;
    }
    resetTo(item.route); // the page host focuses the new top page
  };
  const entry = (item: DrawerItem) => (
    <Entry key={item.key} item={item} selected={item.key === selectedKey} pitch={pitch} open={open} onPress={press} />
  );
  return (
    <div ref={group.ref} class={'rail' + (open ? ' open' : '')}>
      <FocusGroup focusKey={RAIL_KEY}>
        <div class="wordmark">{open ? [<IndicatorSquare key="i" tone="accent" />, <span key="n" class="name">TALLY</span>] : null}</div>
        <UserRow open={open} />
        <div class="list" id={LIST_TOP}>
          {model.top.map(entry)}
          {model.primary.map(entry)}
          {model.libraries.length > 0 ? [<div key="d1" class="divider" />, <div key="k1" class="kicker"><span>LIBRARIES</span></div>] : null}
          {model.libraries.map(entry)}
          <div class="divider" />
          {model.sections.map(entry)}
        </div>
        <div class="footer">
          <div class="divider" />
          {entry(model.settings)}
        </div>
      </FocusGroup>
    </div>
  );
}
