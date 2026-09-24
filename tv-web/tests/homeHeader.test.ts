import type { BaseItemDto } from '@jellyfin/sdk/lib/generated-client/models/base-item-dto';
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { itemMeta } from '../src/pages/home/HomeHeader';

const load = (name: string): BaseItemDto => JSON.parse(readFileSync(new URL(`./fixtures/${name}`, import.meta.url), 'utf8')) as BaseItemDto;
/** Dune as the dev server sends it (90 s long, watched), with its watched state and resume point changed. */
const dune = (played: boolean, positionTicks = 0): BaseItemDto => {
  const item = load('details-film-dune.json');
  return { ...item, UserData: { ...(item.UserData as NonNullable<BaseItemDto['UserData']>), Played: played, PlaybackPositionTicks: positionTicks } };
};
const NOW = new Date(2026, 8, 24, 20, 0, 0);
const texts = (item: BaseItemDto): string[] => itemMeta(item, NOW).map((p) => p.text);

describe('Home header meta line (Android homeMeta)', () => {
  it('ends at now + the time left for a film not watched yet', () => {
    expect(texts(dune(false))).toEqual(['2021', 'PG-13', '1m 30s', '★ 7.8', 'ENDS 8:01 PM']);
  });
  it('counts from the resume point', () => {
    expect(texts(dune(false, 600_000_000)).pop()).toBe('ENDS 8:00 PM');
  });
  it('has no end time for a film already watched (as the dev server sends Dune)', () => {
    expect(texts(load('details-film-dune.json'))).toEqual(['2021', 'PG-13', '1m 30s', '★ 7.8']);
  });
  it('has no running time or end time for a series', () => {
    expect(texts(load('details-series-severance.json')).some((t) => t.startsWith('ENDS'))).toBe(false);
  });
});
