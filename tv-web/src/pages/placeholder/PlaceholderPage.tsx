import { useFocusable } from '../../focus/focus';
import type { PageProps } from '../../app/page';
import { useArrivalFocus } from '../../app/page';
import type { Route } from '../../router/router';
import { tallyUppercase } from '../../util/format';
import './placeholder.css';

function title(route: Route): string {
  switch (route.name) {
    case 'placeholder':
      return route.title;
    case 'library':
      return route.title;
    case 'search':
      return 'Search';
    case 'sports':
      return 'Sports';
    case 'item':
      return 'Details';
    default:
      return route.name;
  }
}

/** Stands in for a screen a later task builds (Samsung 1 scope, tv-web/ARCHITECTURE.md). */
export function PlaceholderPage(props: PageProps) {
  const t = title(props.route);
  const note = props.route.name === 'placeholder' ? props.route.note : `${t} comes in a later build.`;
  const f = useFocusable<HTMLDivElement>({ focusKey: props.pageKey + '-note' });
  useArrivalFocus(props, props.pageKey + '-note', true);
  return (
    <div class="placeholder-page">
      <div class="mono-label kicker">{tallyUppercase(t)}</div>
      <div ref={f.ref} class="note">
        {note}
      </div>
    </div>
  );
}
