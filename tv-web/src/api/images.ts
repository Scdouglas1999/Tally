import type { BaseItemDto } from '@jellyfin/sdk/lib/generated-client/models/base-item-dto';
import { ImageType } from '@jellyfin/sdk/lib/generated-client/models/image-type';
import { getImageApi } from '@jellyfin/sdk/lib/utils/api/image-api';
import { currentApi } from './jellyfin';

/** Image URLs sized for the 1080p canvas (a TV draws the canvas 1:1, so CSS pixels are screen pixels). */
export function itemImage(itemId: string, type: ImageType, tag: string | undefined | null, width: number, height?: number): string {
  return getImageApi(currentApi()).getItemImageUrlById(itemId, type, {
    tag: tag ?? undefined,
    fillWidth: width,
    fillHeight: height,
    quality: 90,
  });
}

/** 2:3 poster: the item's own Primary, else its series' (episodes in Continue Watching). */
export function posterUrl(item: BaseItemDto, width: number, height: number): string | null {
  if (item.Id != null && item.ImageTags?.Primary != null && item.Type !== 'Episode') {
    return itemImage(item.Id, ImageType.Primary, item.ImageTags.Primary, width, height);
  }
  if (item.SeriesId != null && item.SeriesPrimaryImageTag != null) {
    return itemImage(item.SeriesId, ImageType.Primary, item.SeriesPrimaryImageTag, width, height);
  }
  if (item.Id != null && item.ImageTags?.Primary != null) {
    return itemImage(item.Id, ImageType.Primary, item.ImageTags.Primary, width, height);
  }
  return null;
}

/** 16:9: Thumb, else Backdrop, else (episodes) the Primary still. Parent images for episodes without their own. */
export function wideUrl(item: BaseItemDto, width: number, height: number): string | null {
  if (item.Id == null) return null;
  if (item.ImageTags?.Thumb != null) return itemImage(item.Id, ImageType.Thumb, item.ImageTags.Thumb, width, height);
  if (item.Type === 'Episode' && item.ImageTags?.Primary != null) {
    return itemImage(item.Id, ImageType.Primary, item.ImageTags.Primary, width, height);
  }
  const backdrop = item.BackdropImageTags?.[0];
  if (backdrop !== undefined) return itemImage(item.Id, ImageType.Backdrop, backdrop, width, height);
  if (item.ParentThumbItemId != null && item.ParentThumbImageTag != null) {
    return itemImage(item.ParentThumbItemId, ImageType.Thumb, item.ParentThumbImageTag, width, height);
  }
  const parentBackdrop = item.ParentBackdropImageTags?.[0];
  if (item.ParentBackdropItemId != null && parentBackdrop !== undefined) {
    return itemImage(item.ParentBackdropItemId, ImageType.Backdrop, parentBackdrop, width, height);
  }
  return null;
}

/** The backdrop behind Home and detail pages, at the size they draw it (1400x788, top right). */
export function backdropUrl(item: BaseItemDto, width = 1400, height = 788): string | null {
  const own = item.BackdropImageTags?.[0];
  if (item.Id != null && own !== undefined) return itemImage(item.Id, ImageType.Backdrop, own, width, height);
  const parent = item.ParentBackdropImageTags?.[0];
  if (item.ParentBackdropItemId != null && parent !== undefined) {
    return itemImage(item.ParentBackdropItemId, ImageType.Backdrop, parent, width, height);
  }
  return null;
}

export function logoUrl(item: BaseItemDto, width = 720): string | null {
  if (item.Id != null && item.ImageTags?.Logo != null) return itemImage(item.Id, ImageType.Logo, item.ImageTags.Logo, width);
  if (item.ParentLogoItemId != null && item.ParentLogoImageTag != null) {
    return itemImage(item.ParentLogoItemId, ImageType.Logo, item.ParentLogoImageTag, width);
  }
  return null;
}
