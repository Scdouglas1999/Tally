using System;
using System.Collections.Generic;
using System.IO;
using System.Text.RegularExpressions;
using Jellyfin.Plugin.Tally.Models;

namespace Jellyfin.Plugin.Tally.Sources;

/// <summary>Parses extended M3U playlists (#EXTINF with tvg-* attributes).</summary>
public static partial class M3uParser
{
    private static readonly Regex AttrRegex = AttrPattern();

    public static List<SourceChannel> Parse(string content, string sourceId, string sourceName, Dictionary<string, string> defaultHeaders)
    {
        var channels = new List<SourceChannel>();
        using var reader = new StringReader(content);

        string? line;
        string? pendingName = null;
        var pendingAttrs = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);

        while ((line = reader.ReadLine()) != null)
        {
            line = line.Trim();
            if (line.Length == 0)
            {
                continue;
            }

            if (line.StartsWith("#EXTINF:", StringComparison.OrdinalIgnoreCase))
            {
                var comma = line.LastIndexOf(',');
                var attrText = comma >= 0 ? line[..comma] : line;
                pendingName = comma >= 0 ? line[(comma + 1)..].Trim() : string.Empty;

                pendingAttrs.Clear();
                foreach (Match m in AttrRegex.Matches(attrText))
                {
                    pendingAttrs[m.Groups[1].Value] = m.Groups[2].Value;
                }
            }
            else if (line.StartsWith('#'))
            {
                continue; // other directives we don't care about
            }
            else if (pendingName != null)
            {
                var headers = new Dictionary<string, string>(defaultHeaders, StringComparer.OrdinalIgnoreCase);
                if (pendingAttrs.TryGetValue("http-referrer", out var refHeader))
                {
                    headers["Referer"] = refHeader;
                }

                if (pendingAttrs.TryGetValue("http-user-agent", out var uaHeader))
                {
                    headers["User-Agent"] = uaHeader;
                }

                var channelName = string.IsNullOrWhiteSpace(pendingName)
                    ? (pendingAttrs.TryGetValue("tvg-name", out var n) ? n : "Channel")
                    : pendingName;
                var key = ChannelGrouper.KeyFor(channelName);
                channels.Add(new SourceChannel
                {
                    Id = SourceChannel.MakeId(sourceId, line),
                    Name = channelName,
                    GroupKey = key.Length > 0 ? "name:" + key : string.Empty,
                    StreamUrl = line,
                    LogoUrl = pendingAttrs.TryGetValue("tvg-logo", out var logo) ? logo : string.Empty,
                    Group = pendingAttrs.TryGetValue("group-title", out var g) && !string.IsNullOrWhiteSpace(g) ? g : "Live",
                    SourceId = sourceId,
                    SourceName = sourceName,
                    TvgId = pendingAttrs.TryGetValue("tvg-id", out var tvg) ? tvg : string.Empty,
                    Headers = headers
                });

                pendingName = null;
                pendingAttrs.Clear();
            }
        }

        return channels;
    }

    /// <summary>Extracts the first URL found in url-tvg / x-tvg-url attributes of the #EXTM3U header line.</summary>
    public static string? ExtractEpgUrl(string content)
    {
        using var reader = new StringReader(content);
        var first = reader.ReadLine();
        if (first == null || !first.StartsWith("#EXTM3U", StringComparison.OrdinalIgnoreCase))
        {
            return null;
        }

        foreach (Match m in AttrRegex.Matches(first))
        {
            var key = m.Groups[1].Value;
            if (key.Equals("url-tvg", StringComparison.OrdinalIgnoreCase)
                || key.Equals("x-tvg-url", StringComparison.OrdinalIgnoreCase))
            {
                return m.Groups[2].Value;
            }
        }

        return null;
    }

    [GeneratedRegex("([\\w-]+)\\s*=\\s*\"([^\"]*)\"")]
    private static partial Regex AttrPattern();
}
