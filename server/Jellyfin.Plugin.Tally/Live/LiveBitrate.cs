using System;
using System.Collections.Generic;
using System.Linq;
using MediaBrowser.Model.Dto;
using MediaBrowser.Model.Entities;
using MediaBrowser.Model.MediaInfo;

namespace Jellyfin.Plugin.Tally.Live;

/// <summary>
/// The bitrate of a Tally channel as Jellyfin's Live TV sees it, and the media info Jellyfin plays it with.
/// Jellyfin probes an M3U channel with ffprobe, which reports no bitrate for HLS, so Jellyfin assumes 20 Mbps for
/// 1080p (30 for 4K) and transcodes the video for every client whose limit is below that, though Tally's streams run
/// at 5-8 Mbps. Tally measures what it serves instead (see <c>Services/LiveStreamFacts</c>).
/// </summary>
public static class LiveBitrate
{
    /// <summary>The segments a measurement looks at: the newest few of a media playlist.</summary>
    public const int MeasuredSegments = 3;

    /// <summary>Below this a figure is not a video stream's (a slate, a stalled download): ignored.</summary>
    public const long Plausible = 100_000;

    /// <summary>The rate a master playlist declares for the rendition ffmpeg plays without a -map (the one with the
    /// most pixels, then the highest bandwidth): AVERAGE-BANDWIDTH when given, else BANDWIDTH.</summary>
    public static long? FromMaster(IReadOnlyList<HlsVariant> variants)
    {
        var best = variants
            .OrderByDescending(v => (long)(v.Width ?? 0) * (v.Height ?? 0))
            .ThenByDescending(v => v.Bandwidth)
            .FirstOrDefault();
        var bps = best?.AverageBandwidth is > 0 ? best.AverageBandwidth.Value : best?.Bandwidth ?? 0;
        return bps >= Plausible ? bps : null;
    }

    /// <summary>The peak of the segments' bits per second (bytes x 8 / duration). Null when none is usable.</summary>
    public static long? FromSegments(IEnumerable<(long Bytes, double Duration)> segments)
    {
        long? peak = null;
        foreach (var (bytes, duration) in segments)
        {
            if (bytes <= 0 || duration < 0.5)
            {
                continue;
            }

            var bps = (long)(bytes * 8 / duration);
            if (bps >= Plausible && (peak == null || bps > peak))
            {
                peak = bps;
            }
        }

        return peak;
    }

    /// <summary>The highest rate any of the ladder's rungs is known to run at (declared or measured), which the
    /// ladder may switch to while a player watches. Null when no rung's rate is known.</summary>
    public static long? Ceiling(IEnumerable<Tier> tiers)
    {
        long? top = null;
        foreach (var t in tiers)
        {
            var bps = Math.Max(t.Bandwidth, (long)(t.MeasuredBitrate ?? 0));
            if (bps >= Plausible && (top == null || bps > top))
            {
                top = bps;
            }
        }

        return top;
    }

    /// <summary>The larger of the figures that are known.</summary>
    public static long? Max(params long?[] figures)
    {
        long? max = null;
        foreach (var f in figures)
        {
            if (f is >= Plausible && (max == null || f > max))
            {
                max = f;
            }
        }

        return max;
    }

    /// <summary>
    /// Fills a channel's media source the way Jellyfin's live stream probe does (LiveStreamHelper.AddMediaInfoWithProbe:
    /// one video and one audio stream, index -1 so ffmpeg picks the streams itself, no language, IsAVC cleared), from
    /// the probe <paramref name="probe"/>, with <paramref name="totalBitrate"/> as the stream's real rate: the video
    /// stream gets the total less the audio. <paramref name="probe"/> is not changed. With <paramref name="opened"/>
    /// the source is also marked as probed, so Jellyfin does not probe it again (which would put its 20 Mbps guess
    /// back).
    /// </summary>
    public static void Apply(MediaSourceInfo source, MediaInfo probe, long? totalBitrate, bool opened)
    {
        var streams = probe.MediaStreams ?? (IReadOnlyList<MediaStream>)Array.Empty<MediaStream>();

        // without a -map, ffmpeg takes the video with the most pixels and the audio with the most channels
        var video = streams.Where(s => s.Type == MediaStreamType.Video)
            .OrderByDescending(s => (long)(s.Width ?? 0) * (s.Height ?? 0))
            .FirstOrDefault();
        var audio = streams.Where(s => s.Type == MediaStreamType.Audio)
            .OrderByDescending(s => s.Channels ?? 0)
            .FirstOrDefault();

        var list = new List<MediaStream>();
        foreach (var s in new[] { video, audio })
        {
            if (s != null)
            {
                var copy = Copy(s);
                copy.Index = -1;
                copy.Language = null;
                list.Add(copy);
            }
        }

        var originalRuntime = source.RunTimeTicks;
        source.Container = probe.Container;
        source.Formats = probe.Formats;
        source.MediaStreams = list;
        source.RunTimeTicks = originalRuntime.HasValue ? probe.RunTimeTicks : null; // live: no duration
        source.Size = probe.Size;
        source.Timestamp = probe.Timestamp;
        source.Video3DFormat = probe.Video3DFormat;
        source.VideoType = probe.VideoType;
        source.DefaultSubtitleStreamIndex = null;
        source.DefaultAudioStreamIndex = null;

        var v = list.FirstOrDefault(s => s.Type == MediaStreamType.Video);
        if (v != null)
        {
            if (totalBitrate is >= Plausible)
            {
                var a = list.FirstOrDefault(s => s.Type == MediaStreamType.Audio)?.BitRate ?? 0;
                v.BitRate = (int)Math.Min(int.MaxValue, Math.Max(totalBitrate.Value - a, totalBitrate.Value / 2));
            }

            // Jellyfin clears it after its own probe: "coming up false and preventing stream copy"
            v.IsAVC = null;
        }

        source.AnalyzeDurationMs = 3000;
        source.Bitrate = null;
        source.InferTotalBitrate(true);

        if (opened)
        {
            source.SupportsProbing = false;
        }
    }

    private static MediaStream Copy(MediaStream s)
        => System.Text.Json.JsonSerializer.Deserialize<MediaStream>(System.Text.Json.JsonSerializer.Serialize(s))!;
}
