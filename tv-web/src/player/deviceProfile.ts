/**
 * The DeviceProfile sent with every PlaybackInfo request: what this TV plays as is (direct play), and what the
 * server must convert to (HLS, H.264 or HEVC + AAC/AC-3). Built from two sources:
 *  - probes (`MediaSource.isTypeSupported` / `canPlayType`) for codecs the web engine reports honestly;
 *  - per-platform tables for what the native pipeline plays although the web engine does not say so (AVPlay plays
 *    MKV, TS, AC-3/E-AC-3 and HEVC from files that `canPlayType` knows nothing about).
 * Tables are conservative for 2020 sets (Tizen 5.5 / webOS 5); anything unlisted is transcoded, never refused.
 * DTS: Samsung left it out of 2018-2022 sets and LG out of 2020-2021 sets, so it is only offered when probed.
 */
import type { DeviceProfile } from '@jellyfin/sdk/lib/generated-client/models/device-profile';
import type { ShellPlatform } from '../shell-contract/shell';

export interface Capabilities {
  platform: ShellPlatform;
  /** Containers played directly. */
  containers: string[];
  videoCodecs: string[];
  audioCodecs: string[];
  /** Largest picture decoded (3840x2160 on UHD sets). */
  maxWidth: number;
  maxHeight: number;
  /** HEVC Main 10 / HDR10 / HLG shown as HDR. */
  hdr: boolean;
  /** Most audio channels output as is (surround passthrough on TVs). */
  maxAudioChannels: number;
  /** HEVC allowed inside the server's HLS (fewer bits for 4K transcodes; AVPlay and webOS play it). */
  hevcInHls: boolean;
}

export type Probe = (mime: string) => boolean;

const MP4_VIDEO: Record<string, string> = {
  h264: 'video/mp4; codecs="avc1.640029"',
  hevc: 'video/mp4; codecs="hvc1.1.6.L150.B0"',
  vp9: 'video/webm; codecs="vp9"',
  av1: 'video/mp4; codecs="av01.0.08M.08"',
  vp8: 'video/webm; codecs="vp8"',
};

const AUDIO: Record<string, string> = {
  aac: 'audio/mp4; codecs="mp4a.40.2"',
  mp3: 'audio/mpeg',
  opus: 'audio/webm; codecs="opus"',
  flac: 'audio/flac',
  vorbis: 'audio/webm; codecs="vorbis"',
  ac3: 'audio/mp4; codecs="ac-3"',
  eac3: 'audio/mp4; codecs="ec-3"',
  dts: 'audio/mp4; codecs="dtsc"',
};

const union = (a: string[], b: string[]): string[] => a.concat(b.filter((x) => a.indexOf(x) < 0));

/** What each platform's native pipeline plays from a file, whatever the probes say (2020 sets and later). */
const NATIVE: Record<ShellPlatform, { containers: string[]; video: string[]; audio: string[] }> = {
  tizen: {
    containers: ['mp4', 'm4v', 'mkv', 'webm', 'ts', 'mpegts', 'm2ts', 'mov', 'avi', 'mpg', 'mpeg', 'flv', '3gp', 'asf', 'wmv'],
    video: ['h264', 'hevc', 'mpeg2video', 'mpeg4', 'vp9', 'vc1'],
    audio: ['aac', 'mp3', 'ac3', 'eac3', 'flac', 'opus', 'vorbis', 'pcm_s16le', 'pcm_s24le', 'mp2'],
  },
  webos: {
    containers: ['mp4', 'm4v', 'mkv', 'webm', 'ts', 'mpegts', 'm2ts', 'mov', 'avi', 'mpg', 'mpeg'],
    video: ['h264', 'hevc', 'mpeg2video', 'mpeg4', 'vp9'],
    audio: ['aac', 'mp3', 'ac3', 'eac3', 'flac', 'opus', 'vorbis', 'pcm_s16le', 'mp2'],
  },
  browser: { containers: ['mp4', 'm4v', 'webm'], video: ['h264'], audio: ['aac', 'mp3'] },
};

/**
 * Codecs a platform's web probe reports that its native player does not play from files: on the Tizen emulator
 * (Tizen 10, September 2026) MSE says yes to AV1 while AVPlay refuses an AV1 MKV (prepareAsync: InvalidAccessError).
 * They are left to the server (converted), which always works.
 */
const PROBE_NOT_NATIVE: Partial<Record<ShellPlatform, string[]>> = { tizen: ['av1'] };

export function detectCapabilities(platform: ShellPlatform, probe: Probe, uhd: boolean): Capabilities {
  const skip = PROBE_NOT_NATIVE[platform] ?? [];
  const probedVideo = Object.keys(MP4_VIDEO).filter((c) => skip.indexOf(c) < 0 && probe(MP4_VIDEO[c] as string));
  const probedAudio = Object.keys(AUDIO).filter((c) => probe(AUDIO[c] as string));
  const native = NATIVE[platform];
  const tv = platform !== 'browser';
  return {
    platform,
    containers: native.containers,
    videoCodecs: union(native.video, probedVideo),
    audioCodecs: union(native.audio, probedAudio),
    maxWidth: uhd ? 3840 : 1920,
    maxHeight: uhd ? 2160 : 1080,
    hdr: tv && uhd,
    maxAudioChannels: tv ? 6 : 2,
    hevcInHls: tv && union(native.video, probedVideo).indexOf('hevc') >= 0,
  };
}

/** A probe on the running engine (MSE where it exists, else canPlayType). */
export function browserProbe(): Probe {
  const video = document.createElement('video');
  const ms = (window as unknown as { MediaSource?: { isTypeSupported(t: string): boolean } }).MediaSource;
  return (mime) => {
    try {
      if (ms !== undefined && ms.isTypeSupported(mime)) return true;
      return video.canPlayType(mime) !== '';
    } catch {
      return false;
    }
  };
}

export function buildDeviceProfile(caps: Capabilities, maxBitrate: number): DeviceProfile {
  const video = caps.videoCodecs.join(',');
  const audio = caps.audioCodecs.join(',');
  const hlsVideo = caps.hevcInHls ? 'hevc,h264' : 'h264';
  const hlsAudio = caps.audioCodecs.filter((a) => ['aac', 'ac3', 'eac3', 'mp3'].indexOf(a) >= 0).join(',') || 'aac';
  const size = [
    { Condition: 'LessThanEqual' as const, Property: 'Width' as const, Value: String(caps.maxWidth), IsRequired: false },
    { Condition: 'LessThanEqual' as const, Property: 'Height' as const, Value: String(caps.maxHeight), IsRequired: false },
  ];
  const ranges = caps.hdr ? 'SDR|HDR10|HLG' : 'SDR';
  return {
    Name: 'Tally TV (' + caps.platform + ')',
    MaxStreamingBitrate: maxBitrate,
    MaxStaticBitrate: maxBitrate,
    MusicStreamingTranscodingBitrate: 384000,
    DirectPlayProfiles: [
      { Container: caps.containers.join(','), Type: 'Video', VideoCodec: video, AudioCodec: audio },
      { Container: 'mp3,aac,m4a,flac,ogg,opus,wav,webma', Type: 'Audio' },
    ],
    TranscodingProfiles: [
      {
        Container: 'ts',
        Type: 'Video',
        VideoCodec: hlsVideo,
        AudioCodec: hlsAudio,
        Protocol: 'hls',
        Context: 'Streaming',
        MaxAudioChannels: String(caps.maxAudioChannels),
        MinSegments: 1,
        BreakOnNonKeyFrames: false,
      },
      { Container: 'mp3', Type: 'Audio', AudioCodec: 'mp3', Protocol: 'http', Context: 'Streaming', MaxAudioChannels: '2' },
    ],
    ContainerProfiles: [],
    CodecProfiles: [
      {
        Type: 'Video',
        Codec: 'h264',
        Conditions: size.concat([
          { Condition: 'LessThanEqual' as never, Property: 'VideoLevel' as never, Value: '52', IsRequired: false },
          { Condition: 'LessThanEqual' as never, Property: 'VideoBitDepth' as never, Value: '8', IsRequired: false },
        ]),
      },
      {
        Type: 'Video',
        Codec: 'hevc',
        Conditions: size.concat([
          { Condition: 'EqualsAny' as never, Property: 'VideoRangeType' as never, Value: ranges, IsRequired: false },
        ]),
      },
      { Type: 'Video', Codec: 'vp9', Conditions: size },
      { Type: 'Video', Codec: 'av1', Conditions: size },
      {
        Type: 'VideoAudio',
        Conditions: [{ Condition: 'LessThanEqual', Property: 'AudioChannels', Value: String(caps.maxAudioChannels), IsRequired: false }],
      },
    ],
    // Text subtitles come to the app as files the app draws itself (same look on every engine); pictures (PGS,
    // DVD, DVB) are burned into the video by the server.
    SubtitleProfiles: [
      { Format: 'vtt', Method: 'External' },
      { Format: 'srt', Method: 'External' },
      { Format: 'subrip', Method: 'External' },
      { Format: 'ass', Method: 'External' },
      { Format: 'ssa', Method: 'External' },
      { Format: 'pgssub', Method: 'Encode' },
      { Format: 'dvdsub', Method: 'Encode' },
      { Format: 'dvbsub', Method: 'Encode' },
    ],
  };
}
