using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;

namespace Jellyfin.Plugin.Tally.Scores;

/// <summary>What the matcher needs to know about a channel.</summary>
public sealed record ChannelProbe(string Id, string Name, string? NowTitle);

/// <summary>
/// Works out which channels are carrying which game. Three signals, strongest first:
/// the channel is named after the matchup ("Chiefs vs Bills", "KC @ BUF"), its current EPG
/// programme names both teams, or the channel is one of the game's broadcasters ("FOX", "ESPN HD").
/// A broadcaster match can be a different regional game, so it is reported as its own kind.
/// </summary>
public static class GameChannelMatcher
{
    private static readonly HashSet<string> Noise = new(StringComparer.Ordinal)
    {
        "HD", "FHD", "UHD", "SD", "4K", "HEVC", "H265", "H264", "1080P", "720P", "60FPS", "50FPS", "BACKUP", "ALT", "VIP", "LIVE", "TV", "CHANNEL"
    };

    private static readonly HashSet<string> RegionPrefixes = new(StringComparer.Ordinal) { "US", "USA", "UK", "CA", "CAN", "AU" };

    private static readonly HashSet<string> Separators = new(StringComparer.Ordinal) { "VS", "V", "AT", "X" };

    private static readonly Dictionary<string, string> NetworkAliases = new(StringComparer.Ordinal)
    {
        ["FOX SPORTS 1"] = "FS1",
        ["FOX SPORTS 2"] = "FS2",
        ["NFL NET"] = "NFL NETWORK",
        ["NFLN"] = "NFL NETWORK",
        ["MLB NET"] = "MLB NETWORK",
        ["MLBN"] = "MLB NETWORK",
        ["NHL NET"] = "NHL NETWORK",
        ["NHLN"] = "NHL NETWORK",
        ["NBATV"] = "NBA",
        ["USA NET"] = "USA NETWORK",
        ["USA"] = "USA NETWORK",
        ["TELE"] = "TELEMUNDO",
        ["TRU"] = "TRUTV",
        ["CBSSN"] = "CBS SPORTS NETWORK",
        ["CBS SPORTS"] = "CBS SPORTS NETWORK",
        ["BTN"] = "BIG TEN NETWORK",
        ["BIG TEN"] = "BIG TEN NETWORK",
        ["SECN"] = "SEC NETWORK",
        ["ACCN"] = "ACC NETWORK",
        ["AMAZON PRIME VIDEO"] = "PRIME VIDEO",
        ["PRIME"] = "PRIME VIDEO",
        ["AMAZON PRIME"] = "PRIME VIDEO"
    };

    public static void Match(IEnumerable<GameInfo> games, IReadOnlyList<ChannelProbe> channels)
    {
        var probes = channels.Select(c => new
        {
            c.Id,
            Name = " " + string.Join(' ', Tokens(c.Name)) + " ",
            Now = c.NowTitle == null ? null : " " + string.Join(' ', Tokens(c.NowTitle)) + " ",
            Network = NetworkKey(c.Name)
        }).ToList();

        foreach (var g in games)
        {
            g.Channels.Clear();
            if (g.State == "post")
            {
                continue;
            }

            var networks = g.Broadcasts.Select(NetworkKey).Where(n => n.Length > 0).ToHashSet(StringComparer.Ordinal);
            var byTeams = new List<GameChannel>();
            var byEpg = new List<GameChannel>();
            var byNetwork = new List<GameChannel>();

            foreach (var p in probes)
            {
                if (NamesBothTeams(p.Name, g))
                {
                    byTeams.Add(new GameChannel { Id = p.Id, Kind = "teams" });
                }
                else if (p.Now != null && NamesBothTeams(p.Now, g))
                {
                    byEpg.Add(new GameChannel { Id = p.Id, Kind = "epg" });
                }
                else if (p.Network.Length > 0 && networks.Contains(p.Network))
                {
                    byNetwork.Add(new GameChannel { Id = p.Id, Kind = "network" });
                }
            }

            g.Channels.AddRange(byTeams);
            g.Channels.AddRange(byEpg);
            g.Channels.AddRange(byNetwork);
        }
    }

    /// <summary>Upper-cased alphanumeric tokens; "@" reads as AT and "+" as PLUS so "ESPN+" never equals "ESPN".</summary>
    public static List<string> Tokens(string text)
    {
        var sb = new StringBuilder(text.Length + 8);
        foreach (var ch in text)
        {
            if (char.IsLetterOrDigit(ch))
            {
                sb.Append(char.ToUpperInvariant(ch));
            }
            else if (ch == '@')
            {
                sb.Append(" AT ");
            }
            else if (ch == '+')
            {
                sb.Append(" PLUS ");
            }
            else if (ch == '\'' || ch == '’' || ch == '.')
            {
                // drop: "76ers'", "St." and "MLB.TV" stay one token
            }
            else
            {
                sb.Append(' ');
            }
        }

        return sb.ToString().Split(' ', StringSplitOptions.RemoveEmptyEntries).ToList();
    }

    /// <summary>Channel or broadcaster name reduced to a comparable network key ("US: ESPN HD" → "ESPN").</summary>
    public static string NetworkKey(string name)
    {
        var tokens = Tokens(name);
        // "US: ESPN" is a region prefix; "USA Network" is the network itself
        if (tokens.Count > 1 && RegionPrefixes.Contains(tokens[0]) && !(tokens[0] == "USA" && tokens[1] is "NETWORK" or "NET"))
        {
            tokens.RemoveAt(0);
        }

        tokens.RemoveAll(t => Noise.Contains(t));
        var key = string.Join(' ', tokens);
        return NetworkAliases.TryGetValue(key, out var alias) ? alias : key;
    }

    private static bool NamesBothTeams(string paddedTokens, GameInfo g)
    {
        var hasSeparator = Separators.Any(s => paddedTokens.Contains(" " + s + " ", StringComparison.Ordinal));
        return NamesTeam(paddedTokens, g.Home, hasSeparator) && NamesTeam(paddedTokens, g.Away, hasSeparator);
    }

    private static bool NamesTeam(string paddedTokens, GameTeam t, bool hasSeparator)
    {
        foreach (var key in new[] { t.Name, t.ShortName, t.Nickname, t.Location })
        {
            if (key.Length < 3)
            {
                continue;
            }

            var needle = " " + string.Join(' ', Tokens(key)) + " ";
            if (needle.Length > 2 && paddedTokens.Contains(needle, StringComparison.Ordinal))
            {
                return true;
            }
        }

        // Abbreviations are short enough to collide with ordinary words ("NO", "LA", "TB"),
        // so they only count in something shaped like a matchup: "KC vs BUF", "KC @ BUF".
        return hasSeparator && t.Abbr.Length >= 2
            && paddedTokens.Contains(" " + t.Abbr.ToUpperInvariant() + " ", StringComparison.Ordinal);
    }
}
