/**
 * Route name → page. Adding a screen: add its Route variant in router/router.ts, write the page under pages/<name>/,
 * register it here (replacing the placeholder). `chrome`: 'rail' pages sit beside the navigation rail; 'full' pages
 * take the whole screen (players).
 */
import type { FunctionComponent } from 'preact';
import type { Route } from '../router/router';
import type { PageProps } from './page';
import { HomePage } from '../pages/home/HomePage';
import { PlayerPage } from '../pages/player/PlayerPage';
import { PostPlayPage } from '../pages/postplay/PostPlayPage';
import { LivePage } from '../pages/player/LivePage';
import { PlaceholderPage } from '../pages/placeholder/PlaceholderPage';
import { SettingsPage } from '../pages/settings/SettingsPage';

type Pages = { [N in Route['name']]: { page: FunctionComponent<PageProps<Extract<Route, { name: N }>>>; chrome: 'rail' | 'full' } };

export const PAGES: Pages = {
  home: { page: HomePage, chrome: 'rail' },
  search: { page: PlaceholderPage, chrome: 'rail' },
  library: { page: PlaceholderPage, chrome: 'rail' },
  item: { page: PlaceholderPage, chrome: 'rail' },
  sports: { page: PlaceholderPage, chrome: 'rail' },
  settings: { page: SettingsPage, chrome: 'rail' },
  placeholder: { page: PlaceholderPage, chrome: 'rail' },
  player: { page: PlayerPage, chrome: 'full' },
  postplay: { page: PostPlayPage, chrome: 'full' },
  live: { page: LivePage, chrome: 'full' },
};

export function chromeOf(route: Route): 'rail' | 'full' {
  return PAGES[route.name].chrome;
}
