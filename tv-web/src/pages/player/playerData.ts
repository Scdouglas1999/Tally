/**
 * What the player asks the server for besides the stream (player/playback.ts): the item, the queue of episodes that
 * follow it (upstream's PlaylistCreator.createFromEpisode), media segments (skip intro/credits), trickplay tile and
 * chapter image addresses, the post-play page's similar titles, and the saved default quality.
 */
import type { BaseItemDto } from '@jellyfin/sdk/lib/generated-client/models/base-item-dto';
import { ImageType } from '@jellyfin/sdk/lib/generated-client/models/image-type';
import { ItemFields } from '@jellyfin/sdk/lib/generated-client/models/item-fields';
import type { MediaSegmentDto } from '@jellyfin/sdk/lib/generated-client/models/media-segment-dto';
import type { TrickplayInfoDto } from '@jellyfin/sdk/lib/generated-client/models/trickplay-info-dto';
import { getImageApi } from '@jellyfin/sdk/lib/utils/api/image-api';
import { getLibraryApi } from '@jellyfin/sdk/lib/utils/api/library-api';
import { getMediaSegmentApi } from '@jellyfin/sdk/lib/utils/api/media-segment-api';
import { getShowApi } from '@jellyfin/sdk/lib/utils/api/show-api';
import { currentApi, session } from '../../api/jellyfin';
import { qualityOptions, type QualityOption } from '../../player/qualityLadder';
import { readJson, writeJson } from '../../util/storage';

/** Upstream's Playlist.MAX_SIZE. */
const QUEUE_MAX = 100;

const FIELDS = [ItemFields.Overview, ItemFields.Chapters, ItemFields.Trickplay, ItemFields.MediaSources, ItemFields.PrimaryImageAspectRatio];

function userId(): string {
  return session.get()?.userId ?? '';
}

export async function loadItem(itemId: string): Promise<BaseItemDto> {
  return (await getLibraryApi(currentApi()).getItem({ itemId, userId: userId() })).data;
}

/**
 * The queue an item plays in: for an episode, it and the episodes after it in the series (every season), as
 * upstream builds it; anything else plays alone.
 */
export async function loadQueue(item: BaseItemDto): Promise<BaseItemDto[]> {
  if (item.Type !== 'Episode' || item.SeriesId == null || item.Id == null) return [item];
  const episodes =
    (
      await getShowApi(currentApi()).getEpisodes({
        seriesId: item.SeriesId,
        userId: userId(),
        startItemId: item.Id,
        isMissing: false,
        limit: QUEUE_MAX,
        fields: FIELDS,
        enableUserData: true,
      })
    ).data.Items ?? [];
  const start = episodes.findIndex((e) => e.Id === item.Id);
  return start >= 0 ? episodes.slice(start) : [item].concat(episodes);
}

/**
 * A queue given by id (a library's Play all / Shuffle, upstream's PlaybackList): the items in that order, at most
 * QUEUE_MAX; ids the server no longer knows are left out.
 */
export async function loadItems(ids: readonly string[]): Promise<BaseItemDto[]> {
  const wanted = ids.slice(0, QUEUE_MAX);
  if (wanted.length === 0) return [];
  const items = (await getLibraryApi(currentApi()).getItems({ userId: userId(), ids: wanted, fields: FIELDS, enableUserData: true })).data.Items ?? [];
  const byId = new Map(items.map((i) => [i.Id ?? '', i]));
  const out: BaseItemDto[] = [];
  for (const id of wanted) {
    const it = byId.get(id);
    if (it !== undefined) out.push(it);
  }
  return out;
}

/** Intro, credits, recap… markers the server knows for the item (empty without a segment provider). */
export async function loadSegments(itemId: string): Promise<MediaSegmentDto[]> {
  try {
    return (await getMediaSegmentApi(currentApi()).getItemSegments({ itemId })).data.Items ?? [];
  } catch {
    return [];
  }
}

/** One trickplay tile sheet (a grid of thumbnails). */
export function trickplaySheetUrl(itemId: string, info: TrickplayInfoDto, sheet: number, mediaSourceId: string | null): string {
  const params = new URLSearchParams({ ApiKey: session.get()?.token ?? '' });
  if (mediaSourceId !== null) params.set('MediaSourceId', mediaSourceId);
  return `${currentApi().basePath}/Videos/${itemId}/Trickplay/${String(info.Width ?? 0)}/${sheet}.jpg?${params.toString()}`;
}

/** A chapter's still, sized for a landscape card. */
export function chapterImageUrl(itemId: string, index: number, tag: string | null | undefined, width: number, height: number): string | null {
  if (tag == null) return null;
  return getImageApi(currentApi()).getItemImageUrlById(itemId, ImageType.Chapter, { tag, imageIndex: index, fillWidth: width, fillHeight: height, quality: 90 });
}

/** Up to six titles like the finished film, unwatched first (PostPlayViewModel). */
export async function loadSimilar(itemId: string): Promise<BaseItemDto[]> {
  const items = (await getLibraryApi(currentApi()).getSimilarItems({ itemId, userId: userId(), limit: 12, fields: [ItemFields.Genres] })).data.Items ?? [];
  const others = items.filter((i) => i.Id !== itemId);
  return others
    .filter((i) => i.UserData?.Played !== true)
    .concat(others.filter((i) => i.UserData?.Played === true))
    .slice(0, 6);
}

const QUALITY_KEY = 'tally.player.quality.v1';

/** The rung saved with "Use as my default on this TV" (Original when none). */
export function defaultQuality(): QualityOption {
  const bits = readJson<number>(QUALITY_KEY);
  const all = qualityOptions(null, null);
  return all.find((q) => q.bitsPerSecond === bits) ?? (all[0] as QualityOption);
}

export function saveDefaultQuality(option: QualityOption): void {
  writeJson(QUALITY_KEY, option.bitsPerSecond);
}
