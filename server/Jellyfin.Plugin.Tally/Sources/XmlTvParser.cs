using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Xml;
using Jellyfin.Plugin.Tally.Models;

namespace Jellyfin.Plugin.Tally.Sources;

/// <summary>Streaming XMLTV parser — handles channel display names, icons, and programmes.</summary>
public static class XmlTvParser
{
    public sealed class Result
    {
        /// <summary>tvg-id -> display name.</summary>
        public Dictionary<string, string> ChannelNames { get; } = new(StringComparer.OrdinalIgnoreCase);

        /// <summary>tvg-id -> icon url.</summary>
        public Dictionary<string, string> ChannelIcons { get; } = new(StringComparer.OrdinalIgnoreCase);

        /// <summary>tvg-id -> programmes.</summary>
        public Dictionary<string, List<Programme>> Programmes { get; } = new(StringComparer.OrdinalIgnoreCase);
    }

    public static Result Parse(Stream stream)
    {
        var result = new Result();
        var settings = new XmlReaderSettings { IgnoreComments = true, IgnoreWhitespace = true, DtdProcessing = DtdProcessing.Ignore };

        using var reader = XmlReader.Create(stream, settings);
        string? currentChannel = null;
        Programme? current = null;
        string? element = null;

        while (reader.Read())
        {
            if (reader.NodeType == XmlNodeType.Element)
            {
                element = reader.Name;
                switch (element)
                {
                    case "channel":
                        currentChannel = reader.GetAttribute("id") ?? string.Empty;
                        break;
                    case "programme":
                        current = new Programme
                        {
                            ChannelId = reader.GetAttribute("channel") ?? string.Empty,
                            Start = ParseTime(reader.GetAttribute("start")),
                            End = ParseTime(reader.GetAttribute("stop"))
                        };
                        break;
                    case "icon" when current != null:
                        current.IconUrl ??= reader.GetAttribute("src");
                        break;
                    case "icon" when currentChannel != null && current == null:
                        var src = reader.GetAttribute("src");
                        if (!string.IsNullOrEmpty(src) && !string.IsNullOrEmpty(currentChannel))
                        {
                            result.ChannelIcons[currentChannel] = src;
                        }

                        break;
                }
            }
            else if (reader.NodeType == XmlNodeType.Text || reader.NodeType == XmlNodeType.CDATA)
            {
                var text = reader.Value;
                if (current != null)
                {
                    switch (element)
                    {
                        case "title": current.Title = string.IsNullOrEmpty(current.Title) ? text : current.Title + " " + text; break;
                        case "sub-title": current.SubTitle = text; break;
                        case "desc": current.Description = string.IsNullOrEmpty(current.Description) ? text : current.Description + " " + text; break;
                        case "category": current.Category ??= text; break;
                    }
                }
                else if (currentChannel != null && element == "display-name" && !string.IsNullOrEmpty(text))
                {
                    if (!result.ChannelNames.TryGetValue(currentChannel, out var existing) || existing.Length < text.Length)
                    {
                        result.ChannelNames[currentChannel] = text;
                    }
                }
            }
            else if (reader.NodeType == XmlNodeType.EndElement)
            {
                switch (reader.Name)
                {
                    case "channel":
                        currentChannel = null;
                        break;
                    case "programme":
                        if (current != null && !string.IsNullOrEmpty(current.ChannelId) && current.End > current.Start)
                        {
                            if (!result.Programmes.TryGetValue(current.ChannelId, out var list))
                            {
                                list = new List<Programme>();
                                result.Programmes[current.ChannelId] = list;
                            }

                            list.Add(current);
                        }

                        current = null;
                        break;
                }

                element = null;
            }
        }

        foreach (var list in result.Programmes.Values)
        {
            list.Sort((a, b) => a.Start.CompareTo(b.Start));
        }

        return result;
    }

    /// <summary>Parses XMLTV timestamps like 20260920120000 +0000 or 20260920120000.</summary>
    public static DateTimeOffset ParseTime(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw))
        {
            return DateTimeOffset.MinValue;
        }

        raw = raw.Trim();
        var space = raw.IndexOf(' ', StringComparison.Ordinal);
        string stamp;
        TimeSpan offset = TimeSpan.Zero;
        if (space >= 0)
        {
            stamp = raw[..space];
            var tz = raw[(space + 1)..];
            if (tz.Length == 5 && int.TryParse(tz, out var minutes))
            {
                offset = new TimeSpan(minutes / 100, Math.Abs(minutes % 100) * Math.Sign(minutes), 0);
            }
        }
        else
        {
            stamp = raw;
        }

        if (DateTime.TryParseExact(stamp, "yyyyMMddHHmmss", CultureInfo.InvariantCulture, DateTimeStyles.None, out var dt)
            || DateTime.TryParseExact(stamp, "yyyyMMddHHmm", CultureInfo.InvariantCulture, DateTimeStyles.None, out dt))
        {
            return new DateTimeOffset(dt, offset);
        }

        return DateTimeOffset.MinValue;
    }
}
