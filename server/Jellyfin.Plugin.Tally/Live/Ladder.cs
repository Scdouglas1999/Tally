using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;

namespace Jellyfin.Plugin.Tally.Live;

/// <summary>One playable rung: a candidate stream, and one rendition of it when its playlist is a master.</summary>
public sealed class Tier
{
    /// <summary>Which candidate of the channel (index into <c>SourceChannel.Candidates</c>).</summary>
    public int Candidate { get; init; }

    /// <summary>Stable key of the candidate: its URL (tokens and all — a new token is a new stream to probe).</summary>
    public string CandidateKey { get; init; } = string.Empty;

    /// <summary>Index of the rendition in the candidate's master (0 for a plain media playlist).</summary>
    public int Variant { get; init; }

    /// <summary>The media playlist to follow.</summary>
    public Uri MediaUri { get; init; } = null!;

    /// <summary>Declared peak bandwidth, bits/s (0 unknown).</summary>
    public long Bandwidth { get; init; }

    /// <summary>Measured bits/s of real segments (bytes × 8 ÷ duration), when a segment of this rung was fetched.</summary>
    public double? MeasuredBitrate { get; set; }

    public int? Width { get; set; }

    public int? Height { get; set; }

    public double? FrameRate { get; set; }

    public string? Codecs { get; init; }

    /// <summary>Identity across probes: rendition URLs may carry tokens that change on every master fetch.</summary>
    public string Key => CandidateKey + "#" + Variant.ToString(CultureInfo.InvariantCulture);

    /// <summary>What the rung needs from the network: measured when known, else declared, else a guess.</summary>
    public double Bitrate => MeasuredBitrate ?? (Bandwidth > 0 ? Bandwidth : 4_000_000);

    public string Label => LadderRanking.Label(Height, FrameRate);
}

/// <summary>A candidate's last probe: its rungs, and how this server fared fetching it.</summary>
public sealed class CandidateProbe
{
    public DateTimeOffset At { get; init; }

    public bool Ok { get; init; }

    public string? Error { get; init; }

    public List<Tier> Tiers { get; init; } = new();

    /// <summary>Bytes/s × 8 this server achieved downloading one segment.</summary>
    public double? Throughput { get; init; }

    /// <summary>The playlist is live and moving (not ended, and advancing when seen before).</summary>
    public bool Fresh { get; init; }

    public double TargetDuration { get; init; }

    /// <summary>Next media sequence number at probe time — the next probe's freshness check.</summary>
    public long NextSequence { get; init; }

    /// <summary>Segments are MPEG-TS (splice-able); false for fMP4 or unknown.</summary>
    public bool IsTs { get; init; }

    public bool Encrypted { get; init; }

    /// <summary>A live playlist (no #EXT-X-ENDLIST). Recordings and VOD are never laddered.</summary>
    public bool IsLive { get; init; } = true;
}

public static class LadderRanking
{
    /// <summary>Healthy means downloaded at 1.5× its bitrate or better.</summary>
    public const double HealthyMargin = 1.5;

    /// <summary>Stepping back up asks for more: 2× its bitrate.</summary>
    public const double StepUpMargin = 2.0;

    /// <summary>50/60 fps is a class above 25/30, whatever the resolution.</summary>
    public static int FpsClass(double? fps) => fps switch
    {
        null => 1,
        >= 47 => 2,
        >= 23 => 1,
        _ => 0
    };

    /// <summary>Best first: frame-rate class, then height, then bitrate.</summary>
    public static int Compare(Tier a, Tier b)
    {
        var c = FpsClass(b.FrameRate).CompareTo(FpsClass(a.FrameRate));
        if (c != 0)
        {
            return c;
        }

        c = (b.Height ?? 0).CompareTo(a.Height ?? 0);
        if (c != 0)
        {
            return c;
        }

        c = b.Bitrate.CompareTo(a.Bitrate);
        return c != 0 ? c : a.Candidate.CompareTo(b.Candidate);
    }

    public static List<Tier> Rank(IEnumerable<Tier> tiers)
    {
        var list = tiers.ToList();
        list.Sort(Compare);
        return list;
    }

    /// <summary>"1080p60", "720p30", "720p", "60fps", or "" when nothing is known.</summary>
    public static string Label(int? height, double? fps)
    {
        var f = fps is > 0 ? ((int)Math.Round(fps.Value)).ToString(CultureInfo.InvariantCulture) : null;
        if (height is > 0)
        {
            return height.Value.ToString(CultureInfo.InvariantCulture) + "p" + f;
        }

        return f == null ? string.Empty : f + "fps";
    }

    public static bool Healthy(Tier t, CandidateProbe? p, DateTimeOffset now, TimeSpan maxAge, double margin = HealthyMargin)
        => p != null && p.Ok && p.Fresh && now - p.At <= maxAge
           && p.Throughput is { } bps && bps >= margin * t.Bitrate;
}
