using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Text.RegularExpressions;

namespace Jellyfin.Plugin.Tally.Live;

/// <summary>One rendition of a master playlist.</summary>
public sealed class HlsVariant
{
    public Uri Uri { get; init; } = null!;

    /// <summary>Peak bits per second as declared (BANDWIDTH); 0 when missing.</summary>
    public long Bandwidth { get; init; }

    public long? AverageBandwidth { get; init; }

    public int? Width { get; init; }

    public int? Height { get; init; }

    public double? FrameRate { get; init; }

    public string? Codecs { get; init; }
}

public sealed class HlsSegment
{
    public long Sequence { get; init; }

    public double Duration { get; init; }

    public Uri Uri { get; init; } = null!;

    /// <summary>An #EXT-X-DISCONTINUITY precedes this segment.</summary>
    public bool Discontinuity { get; init; }

    /// <summary>METHOD of the #EXT-X-KEY in force, null or "NONE" when the segment is clear.</summary>
    public string? KeyMethod { get; init; }

    public Uri? KeyUri { get; init; }

    public string? KeyIv { get; init; }

    /// <summary>#EXT-X-MAP in force (fragmented MP4): such segments are passed through, never spliced.</summary>
    public Uri? MapUri { get; init; }
}

public sealed class HlsMediaPlaylist
{
    public double TargetDuration { get; init; }

    public long MediaSequence { get; init; }

    public bool EndList { get; init; }

    public List<HlsSegment> Segments { get; init; } = new();

    public long NextSequence => MediaSequence + Segments.Count;
}

/// <summary>Just enough of RFC 8216 to follow a live playlist and read a master's renditions.</summary>
public static partial class HlsParser
{
    public static bool IsMaster(string text) => text.Contains("#EXT-X-STREAM-INF", StringComparison.Ordinal);

    public static List<HlsVariant> ParseMaster(string text, Uri baseUri)
    {
        var list = new List<HlsVariant>();
        Dictionary<string, string>? pending = null;
        using var reader = new StringReader(text);
        string? line;
        while ((line = reader.ReadLine()) != null)
        {
            line = line.Trim();
            if (line.StartsWith("#EXT-X-STREAM-INF:", StringComparison.Ordinal))
            {
                pending = Attributes(line["#EXT-X-STREAM-INF:".Length..]);
            }
            else if (line.Length > 0 && !line.StartsWith('#') && pending != null)
            {
                if (Uri.TryCreate(baseUri, line, out var uri))
                {
                    int? w = null, h = null;
                    if (pending.TryGetValue("RESOLUTION", out var res))
                    {
                        var parts = res.Split('x', 'X');
                        if (parts.Length == 2 && int.TryParse(parts[0], NumberStyles.Integer, CultureInfo.InvariantCulture, out var pw)
                            && int.TryParse(parts[1], NumberStyles.Integer, CultureInfo.InvariantCulture, out var ph))
                        {
                            w = pw;
                            h = ph;
                        }
                    }

                    list.Add(new HlsVariant
                    {
                        Uri = uri,
                        Bandwidth = Long(pending, "BANDWIDTH") ?? 0,
                        AverageBandwidth = Long(pending, "AVERAGE-BANDWIDTH"),
                        Width = w,
                        Height = h,
                        FrameRate = pending.TryGetValue("FRAME-RATE", out var fr)
                            && double.TryParse(fr, NumberStyles.Float, CultureInfo.InvariantCulture, out var f) ? f : null,
                        Codecs = pending.TryGetValue("CODECS", out var c) ? c : null
                    });
                }

                pending = null;
            }
        }

        return list;
    }

    public static HlsMediaPlaylist ParseMedia(string text, Uri baseUri)
    {
        var segments = new List<HlsSegment>();
        double target = 0;
        long mediaSequence = 0;
        var endList = false;
        double? duration = null;
        var discontinuity = false;
        string? keyMethod = null;
        Uri? keyUri = null;
        string? keyIv = null;
        Uri? map = null;
        long seq = -1;

        using var reader = new StringReader(text);
        string? line;
        while ((line = reader.ReadLine()) != null)
        {
            line = line.Trim();
            if (line.Length == 0)
            {
                continue;
            }

            if (line[0] != '#')
            {
                if (seq < 0)
                {
                    seq = mediaSequence;
                }

                if (Uri.TryCreate(baseUri, line, out var uri))
                {
                    segments.Add(new HlsSegment
                    {
                        Sequence = seq,
                        Duration = duration ?? target,
                        Uri = uri,
                        Discontinuity = discontinuity,
                        KeyMethod = keyMethod,
                        KeyUri = keyUri,
                        KeyIv = keyIv,
                        MapUri = map
                    });
                }

                seq++;
                duration = null;
                discontinuity = false;
                continue;
            }

            if (line.StartsWith("#EXTINF:", StringComparison.Ordinal))
            {
                var v = line["#EXTINF:".Length..];
                var comma = v.IndexOf(',', StringComparison.Ordinal);
                if (double.TryParse(comma >= 0 ? v[..comma] : v, NumberStyles.Float, CultureInfo.InvariantCulture, out var d))
                {
                    duration = d;
                }
            }
            else if (line.StartsWith("#EXT-X-TARGETDURATION:", StringComparison.Ordinal))
            {
                double.TryParse(line["#EXT-X-TARGETDURATION:".Length..], NumberStyles.Float, CultureInfo.InvariantCulture, out target);
            }
            else if (line.StartsWith("#EXT-X-MEDIA-SEQUENCE:", StringComparison.Ordinal))
            {
                long.TryParse(line["#EXT-X-MEDIA-SEQUENCE:".Length..], NumberStyles.Integer, CultureInfo.InvariantCulture, out mediaSequence);
            }
            else if (line.StartsWith("#EXT-X-DISCONTINUITY", StringComparison.Ordinal)
                     && !line.StartsWith("#EXT-X-DISCONTINUITY-SEQUENCE", StringComparison.Ordinal))
            {
                discontinuity = true;
            }
            else if (line.StartsWith("#EXT-X-ENDLIST", StringComparison.Ordinal))
            {
                endList = true;
            }
            else if (line.StartsWith("#EXT-X-KEY:", StringComparison.Ordinal))
            {
                var a = Attributes(line["#EXT-X-KEY:".Length..]);
                keyMethod = a.TryGetValue("METHOD", out var m) ? m : null;
                keyUri = a.TryGetValue("URI", out var u) && Uri.TryCreate(baseUri, u, out var ku) ? ku : null;
                keyIv = a.TryGetValue("IV", out var iv) ? iv : null;
                if (string.Equals(keyMethod, "NONE", StringComparison.OrdinalIgnoreCase))
                {
                    keyMethod = null;
                    keyUri = null;
                    keyIv = null;
                }
            }
            else if (line.StartsWith("#EXT-X-MAP:", StringComparison.Ordinal))
            {
                var a = Attributes(line["#EXT-X-MAP:".Length..]);
                map = a.TryGetValue("URI", out var u) && Uri.TryCreate(baseUri, u, out var mu) ? mu : null;
            }
        }

        return new HlsMediaPlaylist
        {
            TargetDuration = target,
            MediaSequence = mediaSequence,
            EndList = endList,
            Segments = segments
        };
    }

    /// <summary>Attribute list: KEY=value,KEY="quoted, value".</summary>
    public static Dictionary<string, string> Attributes(string list)
    {
        var d = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (Match m in AttributePattern().Matches(list))
        {
            var v = m.Groups[2].Value;
            d[m.Groups[1].Value] = v.Length >= 2 && v[0] == '"' ? v[1..^1] : v;
        }

        return d;
    }

    private static long? Long(Dictionary<string, string> a, string key)
        => a.TryGetValue(key, out var s) && long.TryParse(s, NumberStyles.Integer, CultureInfo.InvariantCulture, out var v) ? v : null;

    [GeneratedRegex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)")]
    private static partial Regex AttributePattern();
}
