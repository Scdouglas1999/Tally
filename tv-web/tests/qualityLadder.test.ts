import { describe, expect, it } from 'vitest';
import { applyQualityToUrl, bitrateLabel, qualityOptions, widthFor } from '../src/player/qualityLadder';

describe('quality ladder (Android QualityLadder rungs)', () => {
  it('offers every rung when the source is unknown (live)', () => {
    const labels = qualityOptions(null, null).map((o) => o.label);
    expect(labels[0]).toBe('Original');
    expect(labels.slice(1)).toEqual([
      '4K · 120 Mbps', '4K · 80 Mbps', '4K · 60 Mbps', '4K · 40 Mbps',
      '1080p · 30 Mbps', '1080p · 20 Mbps', '1080p · 15 Mbps', '1080p · 10 Mbps', '1080p · 8 Mbps',
      '720p · 5 Mbps', '720p · 3 Mbps', '480p · 2 Mbps', '360p · 1 Mbps',
    ]);
  });

  it('drops rungs taller than the source or not under its bitrate', () => {
    const options = qualityOptions(1080, 14_800_000);
    expect(options[0]?.label).toBe('Original · 14.8 Mbps');
    expect(options.map((o) => o.label)).toContain('1080p · 10 Mbps');
    expect(options.map((o) => o.label)).not.toContain('1080p · 15 Mbps');
    expect(options.map((o) => o.label)).not.toContain('4K · 40 Mbps');
  });

  it('caps width and height with the bitrate', () => {
    const rung = qualityOptions(null, null).find((o) => o.label === '720p · 3 Mbps');
    expect(rung).toMatchObject({ bitsPerSecond: 3_000_000, maxWidth: 1280, maxHeight: 720 });
    expect(widthFor(2160)).toBe(3840);
    expect(widthFor(480)).toBe(854);
    expect(widthFor(360)).toBe(640);
  });

  it('forbids video stream copy and adds the size box to a transcoding URL', () => {
    const rung = qualityOptions(null, null).find((o) => o.label === '480p · 2 Mbps');
    if (rung === undefined) throw new Error('missing rung');
    expect(applyQualityToUrl('/videos/x/master.m3u8?a=1', rung)).toBe('/videos/x/master.m3u8?a=1&AllowVideoStreamCopy=false&MaxHeight=480&MaxWidth=854');
    expect(applyQualityToUrl('/v?MaxHeight=360&allowvideostreamcopy=true', rung)).toBe('/v?MaxHeight=360&allowvideostreamcopy=true&MaxWidth=854');
    const original = qualityOptions(null, null)[0];
    if (original === undefined) throw new Error('missing original');
    expect(applyQualityToUrl('/v?a=1', original)).toBe('/v?a=1');
  });

  it('labels bitrates like the Android app', () => {
    expect(bitrateLabel(14_800_000)).toBe('14.8 Mbps');
    expect(bitrateLabel(25_400_000)).toBe('25 Mbps');
    expect(bitrateLabel(2_000_000)).toBe('2 Mbps');
    expect(bitrateLabel(0)).toBeNull();
  });
});
