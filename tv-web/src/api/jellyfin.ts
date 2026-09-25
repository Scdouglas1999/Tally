/**
 * Jellyfin access through the official TypeScript SDK (@jellyfin/sdk, MPL-2.0). One `Api` per signed-in session;
 * every request carries `Authorization: MediaBrowser Client=…, Device=…, DeviceId=…, Version=…, Token=…`, which
 * Jellyfin 10.10 through 12 accept (12 rejects the old X-Emby-Token header).
 */
import { Jellyfin, type Api } from '@jellyfin/sdk';
import { getAuthenticationApi } from '@jellyfin/sdk/lib/utils/api/authentication-api';
import { getSystemApi } from '@jellyfin/sdk/lib/utils/api/system-api';
import type { AuthenticationResult } from '@jellyfin/sdk/lib/generated-client/models/authentication-result';
import { createStore } from '../util/store';
import { readJson, writeJson } from '../util/storage';
import { APP_VERSION } from '../version';

export interface Session {
  serverUrl: string;
  serverId: string;
  serverName: string;
  serverVersion: string;
  userId: string;
  userName: string;
  userImageTag?: string;
  token: string;
}

const SESSION_KEY = 'tally.session.v1';
const DEVICE_KEY = 'tally.deviceId';

function newId(): string {
  const bytes = new Uint8Array(16);
  window.crypto.getRandomValues(bytes);
  let out = '';
  for (let i = 0; i < bytes.length; i++) out += (bytes[i] ?? 0).toString(16).padStart(2, '0');
  return out;
}

/** One id per install; Jellyfin binds access tokens to it, so it must survive restarts. */
export function deviceId(): string {
  const existing = readJson<string>(DEVICE_KEY);
  if (existing !== null && existing !== '') return existing;
  const id = newId();
  writeJson(DEVICE_KEY, id);
  return id;
}

let jellyfin: Jellyfin | null = null;

export function initJellyfin(deviceName: string): Jellyfin {
  jellyfin = new Jellyfin({
    clientInfo: { name: 'Tally TV', version: APP_VERSION },
    deviceInfo: { name: deviceName, id: deviceId() },
  });
  return jellyfin;
}

function sdk(): Jellyfin {
  if (jellyfin === null) throw new Error('initJellyfin first');
  return jellyfin;
}

/** The signed-in session, or null. Persisted so the TV opens straight to Home. */
export const session = createStore<Session | null>(readJson<Session>(SESSION_KEY));

let api: Api | null = null;

/** The SDK client for the current session (throws when signed out). */
export function currentApi(): Api {
  const s = session.get();
  if (s === null) throw new Error('signed out');
  if (api === null || api.basePath !== s.serverUrl || api.accessToken !== s.token) {
    api = sdk().createApi(s.serverUrl, s.token);
  }
  return api;
}

/** An unauthenticated client for `serverUrl` (sign-in steps). */
export function anonymousApi(serverUrl: string): Api {
  return sdk().createApi(serverUrl);
}

export interface ServerInfo {
  url: string;
  id: string;
  name: string;
  version: string;
}

/**
 * Finds the server behind what someone typed ("192.168.1.50", "tv.example.com", "http://nas:8096/jellyfin"):
 * tries the SDK's candidates (http/https, 8096/8920) and keeps the best one that answers /System/Info/Public.
 */
export async function findServer(input: string): Promise<ServerInfo | null> {
  const candidates = await sdk().discovery.getRecommendedServerCandidates(input.trim());
  const best = sdk().discovery.findBestServer(candidates);
  if (best === undefined || best.systemInfo === undefined) return null;
  return {
    url: best.address.replace(/\/+$/, ''),
    id: best.systemInfo.Id ?? '',
    name: best.systemInfo.ServerName ?? best.address,
    version: best.systemInfo.Version ?? '',
  };
}

/** /System/Info/Public of a known address (the shell's server), or null when it does not answer. */
export async function probeServer(url: string): Promise<ServerInfo | null> {
  try {
    const info = (await getSystemApi(anonymousApi(url)).getPublicSystemInfo()).data;
    return { url, id: info.Id ?? '', name: info.ServerName ?? url, version: info.Version ?? '' };
  } catch {
    return null;
  }
}

export function completeSignIn(server: ServerInfo, result: AuthenticationResult): Session {
  const user = result.User;
  if (result.AccessToken == null || user?.Id == null) throw new Error('The server did not return a session');
  const s: Session = {
    serverUrl: server.url,
    serverId: server.id,
    serverName: server.name,
    serverVersion: server.version,
    userId: user.Id,
    userName: user.Name ?? '',
    userImageTag: user.PrimaryImageTag ?? undefined,
    token: result.AccessToken,
  };
  writeJson(SESSION_KEY, s);
  session.set(s);
  return s;
}

export async function signInWithPassword(server: ServerInfo, username: string, password: string): Promise<Session> {
  const auth = getAuthenticationApi(anonymousApi(server.url));
  const result = await auth.authenticateUserByName({ authenticateUserByName: { Username: username, Pw: password } });
  return completeSignIn(server, result.data);
}

export interface QuickConnectCode {
  code: string;
  secret: string;
}

export async function startQuickConnect(server: ServerInfo): Promise<QuickConnectCode> {
  const result = (await getAuthenticationApi(anonymousApi(server.url)).initiateQuickConnect()).data;
  if (result.Code == null || result.Secret == null) throw new Error('Quick Connect is off on this server');
  return { code: result.Code, secret: result.Secret };
}

/** True once someone approved the code on another device. */
export async function quickConnectApproved(server: ServerInfo, secret: string): Promise<boolean> {
  const result = (await getAuthenticationApi(anonymousApi(server.url)).getQuickConnectState({ secret })).data;
  return result.Authenticated === true;
}

export async function finishQuickConnect(server: ServerInfo, secret: string): Promise<Session> {
  const auth = getAuthenticationApi(anonymousApi(server.url));
  const result = await auth.authenticateWithQuickConnect({ quickConnectDto: { Secret: secret } });
  return completeSignIn(server, result.data);
}

export function signOut(): void {
  writeJson(SESSION_KEY, null);
  api = null;
  session.set(null);
}

/**
 * The shell's server (stamped by the installer, or typed on the TV) wins over the address saved with the session:
 * the same server at a new address keeps the session with the new address; another server signs out (its sign-in
 * screen follows). Nothing changes when the shell's server does not answer (the saved session is kept).
 */
export async function adoptShellServer(url: string | null): Promise<void> {
  const s = session.get();
  if (s === null || url === null || url === '' || s.serverUrl === url) return;
  const info = await probeServer(url);
  if (info === null) return;
  if (info.id !== '' && info.id === s.serverId) {
    const moved: Session = { ...s, serverUrl: url, serverName: info.name, serverVersion: info.version };
    writeJson(SESSION_KEY, moved);
    session.set(moved);
  } else signOut();
}
