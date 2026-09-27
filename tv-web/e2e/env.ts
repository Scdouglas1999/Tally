import { readFileSync } from 'node:fs';
import { homedir } from 'node:os';

export const SERVER = process.env.TALLY_SERVER ?? 'http://127.0.0.1:18200';
export const APP = '/?server=' + encodeURIComponent(SERVER);
export const AUTH_STATE = 'e2e/.auth/state.json';

export function adminToken(): string {
  const file = process.env.TALLY_TOKEN_FILE ?? homedir() + '/Documents/Tally/devmedia/.tools/dev-server.token';
  return readFileSync(file, 'utf8').trim();
}

/** A GET against the server as the admin (to find test items the way the app would). */
export async function api<T>(path: string): Promise<T> {
  const r = await fetch(SERVER + path, { headers: { Authorization: `MediaBrowser Token="${adminToken()}"` } });
  if (!r.ok) throw new Error(`${path}: HTTP ${r.status}`);
  return (await r.json()) as T;
}

/**
 * Approves a Quick Connect code the way a phone would, as the admin user (the token is an API key, which has no user of
 * its own: the server wants the user named).
 */
export async function approveQuickConnect(code: string): Promise<void> {
  const users = await api<Array<{ Id: string; Name: string; Policy?: { IsAdministrator?: boolean } }>>('/Users');
  const admin = users.find((u) => u.Name === 'admin') ?? users.find((u) => u.Policy?.IsAdministrator === true);
  const user = admin !== undefined ? `&userId=${admin.Id}` : '';
  const r = await fetch(`${SERVER}/QuickConnect/Authorize?code=${code}${user}`, {
    method: 'POST',
    headers: { Authorization: `MediaBrowser Token="${adminToken()}"` },
  });
  if (!r.ok) throw new Error('Quick Connect approval failed: HTTP ' + String(r.status));
}

/** Attaches a full-screen capture to the report and keeps a copy in test-results/shots/<name>.png. */
export async function shot(page: import('@playwright/test').Page, info: import('@playwright/test').TestInfo, name: string): Promise<void> {
  const body = await page.screenshot({ path: `test-results/shots/${name}.png` });
  await info.attach(name, { body, contentType: 'image/png' });
}

/** OK held past the hold threshold (the remote's long press). */
export async function holdOk(page: import('@playwright/test').Page): Promise<void> {
  await page.keyboard.down('Enter');
  await page.waitForTimeout(700);
  await page.keyboard.up('Enter');
}

/** The remote's STOP (Playwright has no MediaStop key). */
export async function stopKey(page: import('@playwright/test').Page): Promise<void> {
  await page.evaluate(() => window.dispatchEvent(new KeyboardEvent('keydown', { key: 'MediaStop', bubbles: true })));
}

/** Current time of the page's <video> (0 without one). */
export async function videoTime(page: import('@playwright/test').Page): Promise<number> {
  return page.evaluate(() => (document.querySelector('.player video') as HTMLVideoElement | null)?.currentTime ?? 0);
}
