/**
 * In-player quality choices, the same rungs as the Android app (quality/QualityLadder.kt): 4K 120/80/60/40,
 * 1080p 30/20/15/10/8, 720p 5/3, 480p 2, 360p 1 (Mbps). A rung sets MaxStreamingBitrate AND MaxWidth/MaxHeight, and
 * forbids video stream copy, because Jellyfin caps a live channel's output at ten times the video bitrate it
 * reports (often ~0 for live), so a bitrate alone left live video at full resolution (measured on Android).
 */
export interface QualityOption {
  /** null = Original. */
  bitsPerSecond: number | null;
  maxWidth: number | null;
  maxHeight: number | null;
  label: string;
}

const MEGABIT = 1_000_000;

const RUNGS: ReadonlyArray<[height: number, megabits: number]> = [
  [2160, 120], [2160, 80], [2160, 60], [2160, 40],
  [1080, 30], [1080, 20], [1080, 15], [1080, 10], [1080, 8],
  [720, 5], [720, 3],
  [480, 2],
  [360, 1],
];

/** 16:9 width for a rung's height (the cap is a box: Jellyfin keeps the aspect ratio inside it). */
export function widthFor(height: number): number {
  return height >= 2000 ? 3840 : Math.round((height * 16) / 9 / 2) * 2;
}

export function bitrateLabel(bitsPerSecond: number | null | undefined): string | null {
  if (bitsPerSecond == null || bitsPerSecond <= 0) return null;
  const mbps = bitsPerSecond / MEGABIT;
  if (mbps >= 20) return `${Math.round(mbps)} Mbps`;
  const tenths = Math.round(mbps * 10);
  const fraction = tenths % 10;
  return (fraction === 0 ? String(Math.floor(tenths / 10)) : `${Math.floor(tenths / 10)}.${fraction}`) + ' Mbps';
}

function rungLabel(height: number, megabits: number): string {
  return (height >= 2000 ? '4K' : `${height}p`) + ` · ${megabits} Mbps`;
}

/**
 * Original first, then the rungs no taller than the source and strictly under its bitrate (an unknown height or
 * bitrate never excludes a rung on that axis).
 */
export function qualityOptions(sourceHeight: number | null, sourceBitrate: number | null): QualityOption[] {
  const height = sourceHeight !== null && sourceHeight > 0 ? sourceHeight : null;
  const bitrate = sourceBitrate !== null && sourceBitrate > 0 ? sourceBitrate : null;
  const rate = bitrateLabel(bitrate);
  const options: QualityOption[] = [
    { bitsPerSecond: null, maxWidth: null, maxHeight: null, label: rate === null ? 'Original' : `Original · ${rate}` },
  ];
  for (const [h, mb] of RUNGS) {
    const bps = mb * MEGABIT;
    if ((height === null || h <= height) && (bitrate === null || bps < bitrate)) {
      options.push({ bitsPerSecond: bps, maxWidth: widthFor(h), maxHeight: h, label: rungLabel(h, mb) });
    }
  }
  return options;
}

/**
 * A server transcoding URL with a chosen rung applied: no video stream copy, MaxWidth/MaxHeight added when the
 * server left them out. Original returns the URL unchanged.
 */
export function applyQualityToUrl(url: string, option: QualityOption): string {
  if (option.bitsPerSecond === null) return url;
  let result = url;
  const has = (name: string): boolean => new RegExp('[?&]' + name + '=', 'i').test(result);
  const add = (name: string, value: string | number): void => {
    result += (result.indexOf('?') >= 0 ? '&' : '?') + name + '=' + String(value);
  };
  if (!has('AllowVideoStreamCopy')) add('AllowVideoStreamCopy', 'false');
  if (option.maxHeight !== null && !has('MaxHeight')) add('MaxHeight', option.maxHeight);
  if (option.maxWidth !== null && !has('MaxWidth')) add('MaxWidth', option.maxWidth);
  return result;
}
