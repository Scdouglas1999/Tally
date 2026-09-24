import { session, signOut } from '../../api/jellyfin';
import { app } from '../../app/context';
import { useArrivalFocus, type PageProps } from '../../app/page';
import { FocusGroup, useFocusable } from '../../focus/focus';
import { Button } from '../../kit/Button';
import type { Route } from '../../router/router';
import { tally } from '../../state/nav';
import { useStore } from '../../util/store';
import { APP_VERSION } from '../../version';
import './settings.css';

/** Settings (first cut): who is signed in where, sign out, change server, reload, versions. */
export function SettingsPage(props: PageProps<Extract<Route, { name: 'settings' }>>) {
  const s = useStore(session);
  const plugin = useStore(tally);
  const group = useFocusable<HTMLDivElement>({ focusKey: props.pageKey + '-actions' });
  useArrivalFocus(props, 'settings-signout', true);
  const rows: Array<[string, string]> = [
    ['Signed in as', s?.userName ?? ''],
    ['Server', `${s?.serverName ?? ''} · ${s?.serverUrl ?? ''}`],
    ['Jellyfin', s?.serverVersion ?? ''],
    ['Tally plugin', plugin.kind === 'available' ? (plugin.info.pluginBuild ?? 'installed') : plugin.kind === 'absent' ? 'not installed' : '…'],
    ['Tally TV', `${APP_VERSION} · shell ${app.shell.shellVersion} · ${app.platform.name}`],
  ];
  return (
    <div class="settings-page">
      <div class="mono-label kicker">SETTINGS</div>
      <table class="facts">
        <tbody>
          {rows.map(([k, v]) => (
            <tr key={k}>
              <td class="mono-label">{k.toUpperCase()}</td>
              <td>{v}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <div ref={group.ref} class="actions">
        <FocusGroup focusKey={props.pageKey + '-actions'}>
          <Button focusKey="settings-signout" label="Sign out" onPress={signOut} />
          <Button focusKey="settings-server" label="Change server" onPress={() => app.shell.changeServer(null)} />
          <Button focusKey="settings-reload" label="Reload app" onPress={() => app.shell.reload()} />
        </FocusGroup>
      </div>
    </div>
  );
}
