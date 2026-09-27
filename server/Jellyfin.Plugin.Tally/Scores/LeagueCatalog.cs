using System;
using System.Collections.Generic;
using System.Linq;

namespace Jellyfin.Plugin.Tally.Scores;

/// <summary>
/// The leagues Tally knows by name: the ones its scoreboard covers unless the admin says otherwise, and the ones it
/// adds by itself when the sources carry their games (see <see cref="LeagueDetector"/>). Any other ESPN league path
/// can still be added by hand.
/// </summary>
public static class LeagueCatalog
{
    /// <summary>ESPN league path → the name shown in Settings.</summary>
    public static readonly IReadOnlyList<(string Path, string Label)> Known = new[]
    {
        ("football/nfl", "NFL"),
        ("football/college-football", "College Football"),
        ("baseball/mlb", "MLB"),
        ("basketball/nba", "NBA"),
        ("basketball/wnba", "WNBA"),
        ("hockey/nhl", "NHL"),
        ("basketball/mens-college-basketball", "Men's College Basketball"),
        ("soccer/usa.1", "MLS"),
        ("soccer/eng.1", "Premier League"),
        ("soccer/esp.1", "LaLiga"),
        ("soccer/ger.1", "Bundesliga"),
        ("soccer/ita.1", "Serie A"),
        ("soccer/fra.1", "Ligue 1"),
        ("soccer/uefa.champions", "Champions League"),
    };

    /// <summary>What the scoreboard covers when the Leagues setting is empty.</summary>
    public static readonly IReadOnlyList<string> Defaults = new[] { "football/nfl", "football/college-football", "baseball/mlb" };

    /// <summary>Short names the Leagues setting takes besides ESPN paths ("nfl, ncaaf, mlb").</summary>
    public static readonly IReadOnlyDictionary<string, string> ShortNames = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
    {
        ["nfl"] = "football/nfl",
        ["ncaaf"] = "football/college-football",
        ["cfb"] = "football/college-football",
        ["college-football"] = "football/college-football",
        ["mlb"] = "baseball/mlb",
        ["nba"] = "basketball/nba",
        ["wnba"] = "basketball/wnba",
        ["nhl"] = "hockey/nhl",
        ["ncaab"] = "basketball/mens-college-basketball",
        ["cbb"] = "basketball/mens-college-basketball",
        ["mls"] = "soccer/usa.1",
        ["epl"] = "soccer/eng.1",
        ["laliga"] = "soccer/esp.1",
        ["bundesliga"] = "soccer/ger.1",
        ["seriea"] = "soccer/ita.1",
        ["ligue1"] = "soccer/fra.1",
        ["ucl"] = "soccer/uefa.champions",
    };

    /// <summary>The ESPN path a short name stands for ("ncaaf" → "football/college-football"); anything else as given.</summary>
    public static string Resolve(string league)
        => ShortNames.TryGetValue(league.Trim(), out var path) ? path : league.Trim();

    /// <summary>"College Football" for "football/college-football"; the path itself for one Tally does not know.</summary>
    public static string Label(string path)
        => Known.FirstOrDefault(k => string.Equals(k.Path, path, StringComparison.OrdinalIgnoreCase)).Label ?? path;
}
