using System;
using System.Collections.Generic;
using System.Text.Json.Serialization;

namespace Jellyfin.Plugin.Tally.Scores;

// JsonPropertyName throughout: Jellyfin's default serializer is PascalCase,
// the web app reads camelCase.

/// <summary>One side of a game.</summary>
public class GameTeam
{
    internal GameTeam MemberwiseCloneTeam()
    {
        var copy = (GameTeam)MemberwiseClone();
        copy.Periods = new List<int>(Periods);
        return copy;
    }

    [JsonPropertyName("id")] public string Id { get; set; } = string.Empty;

    [JsonPropertyName("abbr")] public string Abbr { get; set; } = string.Empty;

    /// <summary>Full name, e.g. "Kansas City Chiefs".</summary>
    [JsonPropertyName("name")] public string Name { get; set; } = string.Empty;

    /// <summary>Short name, e.g. "Chiefs".</summary>
    [JsonPropertyName("shortName")] public string ShortName { get; set; } = string.Empty;

    /// <summary>Nickname as the feed gives it (equals the full name for most soccer clubs).</summary>
    [JsonPropertyName("nickname")] public string Nickname { get; set; } = string.Empty;

    [JsonPropertyName("location")] public string Location { get; set; } = string.Empty;

    [JsonPropertyName("logo")] public string Logo { get; set; } = string.Empty;

    [JsonPropertyName("score")] public int? Score { get; set; }

    [JsonPropertyName("record")] public string? Record { get; set; }

    [JsonPropertyName("possession")] public bool HasPossession { get; set; }

    [JsonPropertyName("winner")] public bool Winner { get; set; }

    /// <summary>Points per period (quarter, inning, …) in order, from the scoreboard's linescores; empty before the game.</summary>
    [JsonPropertyName("periods")] public List<int> Periods { get; set; } = new();

    /// <summary>Team color as six hex digits without '#', e.g. "132448"; empty when the feed has none.</summary>
    [JsonPropertyName("color")] public string Color { get; set; } = string.Empty;

    /// <summary>Alternate team color, same format as <see cref="Color"/>.</summary>
    [JsonPropertyName("altColor")] public string AltColor { get; set; } = string.Empty;
}

/// <summary>A channel believed to be carrying a game, and why.</summary>
public class GameChannel
{
    [JsonPropertyName("id")] public string Id { get; set; } = string.Empty;

    /// <summary>"teams" (channel is named after the matchup), "epg" (its current programme
    /// names both teams) or "network" (it is the broadcaster — may be a different regional game).</summary>
    [JsonPropertyName("kind")] public string Kind { get; set; } = string.Empty;
}

/// <summary>A live, upcoming or finished game, normalized across sports.</summary>
public class GameInfo
{
    [JsonPropertyName("id")] public string Id { get; set; } = string.Empty;

    /// <summary>Sport family from the feed path: football, basketball, baseball, hockey, soccer…</summary>
    [JsonPropertyName("sport")] public string Sport { get; set; } = string.Empty;

    [JsonPropertyName("league")] public string League { get; set; } = string.Empty;

    [JsonPropertyName("name")] public string Name { get; set; } = string.Empty;

    [JsonPropertyName("start")] public DateTimeOffset Start { get; set; }

    /// <summary>"pre", "in" or "post".</summary>
    [JsonPropertyName("state")] public string State { get; set; } = "pre";

    /// <summary>The feed's status name ("STATUS_IN_PROGRESS", "STATUS_POSTPONED"…). Server-side only: the DVR tells a
    /// postponed or canceled game from a finished one by it.</summary>
    [JsonIgnore] public string StatusName { get; set; } = string.Empty;

    /// <summary>The configured league path the game came from ("baseball/mlb"). Server-side only.</summary>
    [JsonIgnore] public string LeaguePath { get; set; } = string.Empty;

    /// <summary>Human status, e.g. "7:58 - 4th", "Top 8th", "FT", "Sun 4:25 PM".</summary>
    [JsonPropertyName("detail")] public string Detail { get; set; } = string.Empty;

    [JsonPropertyName("period")] public int Period { get; set; }

    [JsonPropertyName("clock")] public string Clock { get; set; } = string.Empty;

    [JsonPropertyName("clockSeconds")] public double ClockSeconds { get; set; }

    [JsonPropertyName("home")] public GameTeam Home { get; set; } = new();

    [JsonPropertyName("away")] public GameTeam Away { get; set; } = new();

    [JsonPropertyName("lastPlay")] public string? LastPlay { get; set; }

    [JsonPropertyName("lastPlayType")] public string? LastPlayType { get; set; }

    /// <summary>Points scored on the last play (0 when it was not a scoring play).</summary>
    [JsonPropertyName("lastPlayScore")] public int LastPlayScore { get; set; }

    /// <summary>Football: "2nd &amp; 7 at CAR 36".</summary>
    [JsonPropertyName("downDistance")] public string? DownDistance { get; set; }

    [JsonPropertyName("redZone")] public bool RedZone { get; set; }

    [JsonPropertyName("balls")] public int? Balls { get; set; }

    [JsonPropertyName("strikes")] public int? Strikes { get; set; }

    [JsonPropertyName("outs")] public int? Outs { get; set; }

    [JsonPropertyName("onFirst")] public bool OnFirst { get; set; }

    [JsonPropertyName("onSecond")] public bool OnSecond { get; set; }

    [JsonPropertyName("onThird")] public bool OnThird { get; set; }

    /// <summary>Home win probability 0–1, when the feed provides it.</summary>
    [JsonPropertyName("homeWinPct")] public double? HomeWinPct { get; set; }

    [JsonPropertyName("broadcasts")] public List<string> Broadcasts { get; set; } = new();

    [JsonPropertyName("channels")] public List<GameChannel> Channels { get; set; } = new();

    /// <summary>0–100: how much this game deserves a screen right now.</summary>
    [JsonPropertyName("heat")] public int Heat { get; set; }

    /// <summary>Short reasons behind the heat, most important first ("RED ZONE", "OVERTIME"…).</summary>
    [JsonPropertyName("tags")] public List<string> Tags { get; set; } = new();

    /// <summary>Where to watch it — filled in by the Client API (null on the legacy /Scores endpoint).</summary>
    [JsonPropertyName("watch")] public Client.WatchTarget? Watch { get; set; }

    /// <summary>Root-relative, anonymous 16:9 PNG of text-free matchup art for app backdrops — filled in by the
    /// Client API. It does not change with the score, so clients may cache it for the life of the game.</summary>
    [JsonPropertyName("backdropPath")] public string? BackdropPath { get; set; }

    /// <summary>The DVR's job for this game (see Dvr/), absent when there is none.</summary>
    [JsonPropertyName("recording")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Dvr.GameRecording? Recording { get; set; }

    /// <summary>Per-module additions, keyed by module name ("fantasy"…). Clients ignore keys they don't know.</summary>
    [JsonPropertyName("extras")] public Dictionary<string, object> Extras { get; set; } = new();

    /// <summary>Postponed or canceled, by the feed's status (such a game is "post" without having been played).</summary>
    [JsonIgnore]
    public bool IsCalledOff
        => StatusName.Contains("POSTPONED", StringComparison.OrdinalIgnoreCase)
           || StatusName.Contains("CANCELED", StringComparison.OrdinalIgnoreCase)
           || StatusName.Contains("CANCELLED", StringComparison.OrdinalIgnoreCase)
           || (StatusName.Length == 0 && Detail is "Postponed" or "Canceled" or "Cancelled");

    /// <summary>Copy with its own channel/tag lists — cached games are shared between requests,
    /// matching and heat are computed per request.</summary>
    public GameInfo Clone()
    {
        var copy = (GameInfo)MemberwiseClone();
        copy.Channels = new List<GameChannel>();
        copy.Home = (GameTeam)Home.MemberwiseCloneTeam();
        copy.Away = (GameTeam)Away.MemberwiseCloneTeam();
        copy.Tags = new List<string>();
        copy.Extras = new Dictionary<string, object>();
        copy.Watch = null;
        copy.Recording = null;
        return copy;
    }
}
