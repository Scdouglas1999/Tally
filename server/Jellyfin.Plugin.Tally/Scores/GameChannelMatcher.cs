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
        ["ACC NET"] = "ACC NETWORK",
        ["SEC NET"] = "SEC NETWORK",
        ["BIG TEN NET"] = "BIG TEN NETWORK",
        ["ESPN U"] = "ESPNU",
        ["THE CW"] = "CW",
        ["CW NETWORK"] = "CW",
        ["AMAZON PRIME VIDEO"] = "PRIME VIDEO",
        ["PRIME"] = "PRIME VIDEO",
        ["AMAZON PRIME"] = "PRIME VIDEO"
    };

    public static void Match(IEnumerable<GameInfo> games, IReadOnlyList<ChannelProbe> channels)
    {
        var list = games as IReadOnlyList<GameInfo> ?? games.ToList();
        var names = new TeamNames(list);
        var probes = channels.Select(c => new
        {
            c.Id,
            Name = names.Read(c.Name),
            Now = c.NowTitle == null ? null : names.Read(c.NowTitle),
            Network = NetworkKey(c.Name)
        }).ToList();

        foreach (var g in list)
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
                if (names.NamesBoth(p.Name, g))
                {
                    byTeams.Add(new GameChannel { Id = p.Id, Kind = "teams" });
                }
                else if (p.Now != null && names.NamesBoth(p.Now, g))
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

    /// <summary>
    /// Which teams a piece of text names, judged against every team on the board at once. College football is what
    /// shapes this: schools nest inside one another ("Texas" in "Texas Tech", "Michigan" in "Michigan State"), so a
    /// name only counts where no longer team name covers the same words; and nicknames repeat (a Saturday has several
    /// Tigers and Bulldogs), so a name or abbreviation two teams of one league share only counts when the other team is
    /// named by one of its own ("Tigers vs Gamecocks" is Clemson's game; "Tigers vs Bulldogs" is nobody's).
    /// </summary>
    private sealed class TeamNames
    {
        // every team name on the board, by its first token, longest first
        private readonly Dictionary<string, List<string[]>> _byFirst = new(StringComparer.Ordinal);

        // league + name → the teams of that league going by it
        private readonly Dictionary<string, HashSet<string>> _owners = new(StringComparer.Ordinal);

        public TeamNames(IReadOnlyList<GameInfo> games)
        {
            var all = new HashSet<string>(StringComparer.Ordinal);
            foreach (var g in games)
            {
                foreach (var t in new[] { g.Home, g.Away })
                {
                    var who = t.Id.Length > 0 ? t.Id : t.Name;
                    foreach (var key in Keys(t))
                    {
                        all.Add(key);
                        Own(g.League + "|" + key, who);
                    }

                    if (t.Abbr.Length >= 2)
                    {
                        Own(g.League + "|abbr:" + t.Abbr.ToUpperInvariant(), who);
                    }
                }
            }

            foreach (var key in all)
            {
                var tokens = key.Split(' ');
                if (!_byFirst.TryGetValue(tokens[0], out var list))
                {
                    _byFirst[tokens[0]] = list = new List<string[]>();
                }

                list.Add(tokens);
            }

            foreach (var list in _byFirst.Values)
            {
                list.Sort((a, b) => b.Length.CompareTo(a.Length));
            }
        }

        /// <summary>The team names <paramref name="text"/> holds that no longer team name covers, plus its tokens.</summary>
        public Reading Read(string text)
        {
            var tokens = Tokens(text);
            var spans = new List<(int Start, int End, string Key)>();
            for (var i = 0; i < tokens.Count; i++)
            {
                if (!_byFirst.TryGetValue(tokens[i], out var candidates))
                {
                    continue;
                }

                foreach (var key in candidates)
                {
                    if (i + key.Length <= tokens.Count && key.Select((k, j) => tokens[i + j] == k).All(b => b))
                    {
                        spans.Add((i, i + key.Length, string.Join(' ', key)));
                    }
                }
            }

            var named = spans
                .Where(s => !spans.Any(o => o.End - o.Start > s.End - s.Start && o.Start <= s.Start && o.End >= s.End))
                .Select(s => s.Key)
                .ToHashSet(StringComparer.Ordinal);
            var padded = " " + string.Join(' ', tokens) + " ";
            return new Reading(named, padded, Separators.Any(s => padded.Contains(" " + s + " ", StringComparison.Ordinal)));
        }

        public bool NamesBoth(Reading r, GameInfo g)
        {
            var (home, away) = (Names(r, g, g.Home), Names(r, g, g.Away));
            return home != Named.No && away != Named.No && (home == Named.Own || away == Named.Own);
        }

        private Named Names(Reading r, GameInfo g, GameTeam t)
        {
            var named = Named.No;
            foreach (var k in Keys(t).Where(r.Named.Contains))
            {
                if (!Shared(g.League + "|" + k))
                {
                    return Named.Own;
                }

                named = Named.Shared;
            }

            // Abbreviations are short enough to collide with ordinary words ("NO", "LA", "TB"),
            // so they only count in something shaped like a matchup: "KC vs BUF", "KC @ BUF".
            // "TA&M" and "M-OH" read as the tokens they break into.
            var abbr = t.Abbr.ToUpperInvariant();
            if (r.HasSeparator && abbr.Length >= 2 && r.Padded.Contains(" " + string.Join(' ', Tokens(abbr)) + " ", StringComparison.Ordinal))
            {
                return Shared(g.League + "|abbr:" + abbr) ? Named.Shared : Named.Own;
            }

            return named;
        }

        private bool Shared(string leagueKey) => _owners.TryGetValue(leagueKey, out var who) && who.Count > 1;

        private void Own(string leagueKey, string who)
        {
            if (!_owners.TryGetValue(leagueKey, out var set))
            {
                _owners[leagueKey] = set = new HashSet<string>(StringComparer.Ordinal);
            }

            set.Add(who);
        }

        private static IEnumerable<string> Keys(GameTeam t)
            => new[] { t.Name, t.ShortName, t.Nickname, t.Location }
                .Where(k => k.Length >= 3)
                .Select(k => string.Join(' ', Tokens(k)))
                .Where(k => k.Length > 0)
                .Distinct(StringComparer.Ordinal);
    }

    private enum Named
    {
        No,
        Shared,
        Own
    }

    private sealed record Reading(HashSet<string> Named, string Padded, bool HasSeparator);
}
