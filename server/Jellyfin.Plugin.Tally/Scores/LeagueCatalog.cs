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

    /// <summary>"College Football" for "football/college-football"; the path itself for one Tally does not know.</summary>
    public static string Label(string path)
        => Known.FirstOrDefault(k => string.Equals(k.Path, path, StringComparison.OrdinalIgnoreCase)).Label ?? path;
}
