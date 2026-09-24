/**
 * The Tally plugin's Client API (/JellyTV/Client/v1, server/README.md). Same session and Authorization header as
 * every Jellyfin call. HTTP 404 on /info means the server has no Tally plugin: the Tally sections must not appear.
 */
import axios, { type AxiosError } from 'axios';
import { currentApi } from './jellyfin';
import { decodeBoard, decodeInfo, decodeSettings, type TallyBoard, type TallyInfo, type TallySettings } from './tallyModels';

export class TallyNotInstalled extends Error {
  constructor() {
    super('The Tally server plugin is not installed');
  }
}

function status(e: unknown): number | undefined {
  return (e as AxiosError).response?.status;
}

async function get(path: string): Promise<unknown> {
  const api = currentApi();
  try {
    const response = await axios.get(api.basePath + path, {
      headers: { Authorization: api.authorizationHeader },
      timeout: 20000,
    });
    return response.data;
  } catch (e) {
    if (status(e) === 404) throw new TallyNotInstalled();
    throw e;
  }
}

/** Root-relative plugin paths (hlsPath, cardPath, backdropPath) made absolute. */
export function absolute(path: string): string {
  return currentApi().basePath + path;
}

export async function tallyInfo(): Promise<TallyInfo> {
  return decodeInfo(await get('/JellyTV/Client/v1/info'));
}

/** Omit `since` on the first call (no events); afterwards pass the highest event id seen. */
export async function tallyBoard(since?: number): Promise<TallyBoard> {
  return decodeBoard(await get('/JellyTV/Client/v1/board' + (since === undefined ? '' : '?since=' + String(since))));
}

/** The raw settings document (kept whole: unknown keys belong to other clients and must survive a write). */
export async function tallySettingsRaw(): Promise<Record<string, unknown>> {
  const data = await get('/JellyTV/Client/v1/settings');
  return data !== null && typeof data === 'object' ? (data as Record<string, unknown>) : {};
}

export async function tallySettings(): Promise<TallySettings> {
  return decodeSettings(await tallySettingsRaw());
}

/** Read-modify-write: `change` gets the whole document, returns it with its own keys changed. */
export async function updateTallySettings(change: (doc: Record<string, unknown>) => Record<string, unknown>): Promise<void> {
  const api = currentApi();
  const doc = change(await tallySettingsRaw());
  await axios.put(api.basePath + '/JellyTV/Client/v1/settings', doc, {
    headers: { Authorization: api.authorizationHeader, 'Content-Type': 'application/json' },
  });
}
