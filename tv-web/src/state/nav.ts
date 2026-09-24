import type { BaseItemDto } from '@jellyfin/sdk/lib/generated-client/models/base-item-dto';
import { getUserViewApi } from '@jellyfin/sdk/lib/utils/api/user-view-api';
import { currentApi, session } from '../api/jellyfin';
import { TallyNotInstalled, tallyInfo } from '../api/tally';
import type { TallyInfo } from '../api/tallyModels';
import { createStore } from '../util/store';

/** unknown: not asked yet; absent: the server has no Tally plugin (the Tally sections do not appear at all). */
export type TallyAvailability = { kind: 'unknown' } | { kind: 'absent' } | { kind: 'available'; info: TallyInfo } | { kind: 'error' };

export const libraries = createStore<BaseItemDto[] | null>(null);
export const tally = createStore<TallyAvailability>({ kind: 'unknown' });

export async function loadNav(): Promise<void> {
  const s = session.get();
  if (s === null) return;
  const views = getUserViewApi(currentApi())
    .getUserViews({ userId: s.userId })
    .then((r) => libraries.set(r.data.Items ?? []))
    .catch(() => libraries.set([]));
  const plugin = tallyInfo()
    .then((info) => tally.set({ kind: 'available', info }))
    .catch((e: unknown) => tally.set(e instanceof TallyNotInstalled ? { kind: 'absent' } : { kind: 'error' }));
  await Promise.all([views, plugin]);
}
