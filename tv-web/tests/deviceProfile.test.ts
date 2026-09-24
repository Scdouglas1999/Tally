import { describe, expect, it } from 'vitest';
import { buildDeviceProfile, detectCapabilities } from '../src/player/deviceProfile';

const none = (): boolean => false;
const chrome = (mime: string): boolean => /avc1|vp9|opus|mp4a|audio\/mpeg|flac|vorbis|av01/.test(mime);

describe('device profiles', () => {
  it('lets Tizen direct play MKV with AC-3 and HEVC even when the web engine probes nothing', () => {
    const caps = detectCapabilities('tizen', none, true);
    const profile = buildDeviceProfile(caps, 120_000_000);
    const direct = profile.DirectPlayProfiles?.[0];
    expect(direct?.Container).toContain('mkv');
    expect(direct?.VideoCodec).toContain('hevc');
    expect(direct?.AudioCodec).toContain('ac3');
    expect(direct?.AudioCodec).not.toContain('dts');
    expect(profile.TranscodingProfiles?.[0]).toMatchObject({ Container: 'ts', Protocol: 'hls', VideoCodec: 'hevc,h264' });
    expect(caps.maxWidth).toBe(3840);
  });

  it('keeps a browser to what it probes, converts MKV, and sends 2 channels', () => {
    const caps = detectCapabilities('browser', chrome, false);
    const profile = buildDeviceProfile(caps, 8_000_000);
    expect(profile.DirectPlayProfiles?.[0]?.Container).not.toContain('mkv');
    expect(profile.DirectPlayProfiles?.[0]?.VideoCodec).toContain('vp9');
    expect(profile.TranscodingProfiles?.[0]).toMatchObject({ VideoCodec: 'h264', MaxAudioChannels: '2' });
    expect(profile.MaxStreamingBitrate).toBe(8_000_000);
  });

  it('draws text subtitles itself and burns in picture subtitles', () => {
    const profile = buildDeviceProfile(detectCapabilities('webos', none, false), 1);
    const methods = Object.fromEntries((profile.SubtitleProfiles ?? []).map((s) => [s.Format, s.Method]));
    expect(methods.srt).toBe('External');
    expect(methods.ass).toBe('External');
    expect(methods.pgssub).toBe('Encode');
  });
});
