using System;
using System.Collections.Generic;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Serialization;

namespace Jellyfin.Plugin.Tally.Models;

/// <summary>A single playable live channel produced by a source adapter.</summary>
public class SourceChannel
{
    /// <summary>Stable id: source + normalized name (assigned by <c>ChannelIdentity</c> after a scan).</summary>
    public string Id { get; set; } = string.Empty;

    /// <summary>The id this channel had under the old URL-derived scheme — only used to migrate stored favorites.</summary>
    public string LegacyId { get; set; } = string.Empty;

    public string Name { get; set; } = string.Empty;

    public string StreamUrl { get; set; } = string.Empty;

    public string LogoUrl { get; set; } = string.Empty;

    public string Group { get; set; } = "Live";

    public string SourceId { get; set; } = string.Empty;

    public string SourceName { get; set; } = string.Empty;

    /// <summary>tvg-id used to join EPG programmes onto this channel.</summary>
    public string TvgId { get; set; } = string.Empty;

    /// <summary>Headers the upstream server requires (Referer, Origin, UA, Cookie...).</summary>
    public Dictionary<string, string> Headers { get; set; } = new();

    /// <summary>What the adapter considers the same channel as another entry of its source (a web page's second
    /// link for one game, an M3U's "HD"/"BACKUP" duplicate). Channels sharing a key are merged by <c>ChannelGrouper</c>.</summary>
    public string GroupKey { get; set; } = string.Empty;

    /// <summary>The name came from a web page's title (or another label that says nothing about the event): it stands
    /// only if it names a game on the scoreboard, see <c>ChannelNaming</c>. Never persisted.</summary>
    [JsonIgnore]
    public bool NameFromTitle { get; set; }

    /// <summary>Every stream that carries this channel, first the one the channel was built from. Filled by
    /// <c>ChannelGrouper</c>; a channel nobody merged into has exactly one, its own <see cref="StreamUrl"/>.</summary>
    public List<StreamCandidate> Candidates { get; set; } = new();

    /// <summary>Ids of the channels merged into this one (they no longer appear on their own).</summary>
    public List<string> MergedIds { get; set; } = new();

    public SourceChannel ShallowCopy() => (SourceChannel)MemberwiseClone();

    public static string MakeId(string sourceId, string streamUrl)
    {
        var hash = SHA256.HashData(Encoding.UTF8.GetBytes(sourceId + "|" + streamUrl));
        return Convert.ToHexString(hash)[..16].ToLowerInvariant();
    }
}

/// <summary>One stream of a channel: the channel's own, or one merged in from a duplicate.</summary>
public class StreamCandidate
{
    public string Url { get; set; } = string.Empty;

    public Dictionary<string, string> Headers { get; set; } = new(StringComparer.OrdinalIgnoreCase);

    /// <summary>Name of the entry it came from ("ESPN HD", "Chiefs vs Bills 2").</summary>
    public string Name { get; set; } = string.Empty;

    /// <summary>Id that entry had (or would have had) as a channel of its own.</summary>
    public string MemberId { get; set; } = string.Empty;
}

/// <summary>A scheduled programme on a channel (from XMLTV or synthetic).
/// Serialized straight to the web app, which reads camelCase — pinned here because
/// Jellyfin's default JSON policy is PascalCase.</summary>
public class Programme
{
    [JsonPropertyName("channelId")]
    public string ChannelId { get; set; } = string.Empty;

    [JsonPropertyName("title")]
    public string Title { get; set; } = string.Empty;

    [JsonPropertyName("subTitle")]
    public string? SubTitle { get; set; }

    [JsonPropertyName("description")]
    public string? Description { get; set; }

    [JsonPropertyName("category")]
    public string? Category { get; set; }

    [JsonPropertyName("iconUrl")]
    public string? IconUrl { get; set; }

    [JsonPropertyName("start")]
    public DateTimeOffset Start { get; set; }

    [JsonPropertyName("end")]
    public DateTimeOffset End { get; set; }

    [JsonPropertyName("isLive")]
    public bool IsLive { get; set; } = true;
}
