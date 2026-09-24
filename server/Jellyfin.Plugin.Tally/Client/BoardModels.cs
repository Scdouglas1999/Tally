using System;
using System.Collections.Generic;
using System.Text.Json.Serialization;
using Jellyfin.Plugin.Tally.Models;
using Jellyfin.Plugin.Tally.Scores;

namespace Jellyfin.Plugin.Tally.Client;

// The contract native clients are built against.
// Additive changes are free; anything breaking bumps ClientApi.Version.

/// <summary>Where to watch a game — resolved on the server so no client ever has to choose.</summary>
public class WatchTarget
{
    [JsonPropertyName("channelId")] public string ChannelId { get; set; } = string.Empty;

    [JsonPropertyName("channelName")] public string ChannelName { get; set; } = string.Empty;

    /// <summary>Jellyfin Live TV item for the app's own player; null until Jellyfin's guide has the channel.</summary>
    [JsonPropertyName("liveTvItemId")] public string? LiveTvItemId { get; set; }

    /// <summary>Root-relative, signed, anonymous HLS — for multiview players.</summary>
    [JsonPropertyName("hlsPath")] public string HlsPath { get; set; } = string.Empty;

    [JsonPropertyName("cardPath")] public string CardPath { get; set; } = string.Empty;

    /// <summary>"teams", "epg" or "network" (a broadcaster match may be a different regional game).</summary>
    [JsonPropertyName("confidence")] public string Confidence { get; set; } = string.Empty;
}

public class BoardChannel
{
    [JsonPropertyName("id")] public string Id { get; set; } = string.Empty;

    [JsonPropertyName("name")] public string Name { get; set; } = string.Empty;

    [JsonPropertyName("group")] public string Group { get; set; } = string.Empty;

    [JsonPropertyName("logo")] public string? Logo { get; set; }

    [JsonPropertyName("liveTvItemId")] public string? LiveTvItemId { get; set; }

    [JsonPropertyName("hlsPath")] public string HlsPath { get; set; } = string.Empty;

    [JsonPropertyName("cardPath")] public string CardPath { get; set; } = string.Empty;

    /// <summary>Id of the game this channel is carrying, when known with confidence.</summary>
    [JsonPropertyName("gameId")] public string? GameId { get; set; }

    [JsonPropertyName("now")] public Programme? Now { get; set; }

    [JsonPropertyName("next")] public Programme? Next { get; set; }

    /// <summary>The stream being played, or the one a viewer would start on ("1080p60", first choice or not);
    /// null until the plugin has probed the channel.</summary>
    [JsonPropertyName("stream")] public Live.StreamStatus? Stream { get; set; }
}

/// <summary>Something that happened. Clients keep the last id they showed; "new" is a comparison.</summary>
public class BoardEvent
{
    [JsonPropertyName("id")] public long Id { get; set; }

    /// <summary>Which module produced it ("scores"; later "fantasy"). Users opt in per source.</summary>
    [JsonPropertyName("source")] public string Source { get; set; } = string.Empty;

    [JsonPropertyName("kind")] public string Kind { get; set; } = string.Empty;

    [JsonPropertyName("gameId")] public string? GameId { get; set; }

    [JsonPropertyName("title")] public string Title { get; set; } = string.Empty;

    [JsonPropertyName("text")] public string Text { get; set; } = string.Empty;

    [JsonPropertyName("createdAt")] public DateTimeOffset CreatedAt { get; set; }

    [JsonPropertyName("watch")] public WatchTarget? Watch { get; set; }
}

public class Board
{
    [JsonPropertyName("serverTime")] public DateTimeOffset ServerTime { get; set; }

    [JsonPropertyName("games")] public List<GameInfo> Games { get; set; } = new();

    [JsonPropertyName("channels")] public List<BoardChannel> Channels { get; set; } = new();

    [JsonPropertyName("events")] public List<BoardEvent> Events { get; set; } = new();

    /// <summary>One document per feature module, keyed by module name (e.g. "fantasy").</summary>
    [JsonPropertyName("modules")] public Dictionary<string, object> Modules { get; set; } = new();

    [JsonPropertyName("errors")] public Dictionary<string, string> Errors { get; set; } = new();
}
