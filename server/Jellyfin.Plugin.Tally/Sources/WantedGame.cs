using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Text;
using Jellyfin.Plugin.Tally.Scores;

namespace Jellyfin.Plugin.Tally.Sources;

/// <summary>
/// A scoreboard game a crawl is asked to find streams for, with the words that name each team: the display name
/// ("Tampa Bay Rays"), short name ("Rays"), nickname, location ("Tampa Bay") and abbreviation ("TB"), all taken from
/// the scoreboard. Links are matched case-insensitively on whole words, so "rays-vs-phillies", "Tampa Bay Rays @
/// Philadelphia Phillies" and "/mlb/tb-phi" all name the game, and "arrays" or "rayshawn" name nothing.
/// </summary>
public sealed class WantedGame
{
    private WantedGame(string gameId, string label, string name, TeamWords away, TeamWords home)
    {
        GameId = gameId;
        Label = label;
        Name = name;
        Away = away;
        Home = home;
    }

    public string GameId { get; }

    /// <summary>"TB @ PHI", for logs.</summary>
    public string Label { get; }

    /// <summary>"Tampa Bay Rays at Philadelphia Phillies": what a stream found on a page that names both teams is
    /// called when its own page says nothing better.</summary>
    public string Name { get; }

    public TeamWords Away { get; }

    public TeamWords Home { get; }

    public static WantedGame From(GameInfo g)
    {
        var label = g.Away.Abbr.Length > 0 && g.Home.Abbr.Length > 0 ? g.Away.Abbr + " @ " + g.Home.Abbr : g.Name;
        return new WantedGame(g.Id, label, ChannelNaming.GameName(g), TeamWords.From(g.Away), TeamWords.From(g.Home));
    }

    /// <summary>How strongly a link names this game: 2 when it names both teams, 1 when it names one, 0 otherwise.
    /// <paramref name="text"/> is the link's text, <paramref name="url"/> its address (only the path and query are
    /// read: a host name is the site's, not the game's).</summary>
    public int Score(string? text, string? url)
    {
        var words = Words(text);
        words.Add(string.Empty); // a break: a team's name never spans the text and the address
        words.AddRange(Words(UrlPart(url)));
        return Score(words);
    }

    /// <summary>True when <paramref name="name"/> (a channel name) names both teams by name: what the board's matcher
    /// needs to tie a channel to the game (a pair of abbreviations, "Tb Phi", is not enough there).</summary>
    public bool NamesBoth(string? name)
    {
        var words = Words(name);
        return Away.NamedIn(words) && Home.NamedIn(words);
    }

    private int Score(List<string> words)
    {
        var awayName = Away.NamedIn(words);
        var homeName = Home.NamedIn(words);
        var awayAbbr = Away.AbbrIn(words);
        var homeAbbr = Home.AbbrIn(words);

        // Abbreviations are short enough to be ordinary words ("TB", "LA", "NO", "SEA"): one counts only next to the
        // other team, by name or abbreviation ("tb-phi", "TB @ Phillies").
        var away = awayName || (awayAbbr && (homeName || homeAbbr));
        var home = homeName || (homeAbbr && (awayName || awayAbbr));
        return (away ? 1 : 0) + (home ? 1 : 0);
    }

    /// <summary>Lower-case words of a text or an address: letters and digits, accents dropped ("Montréal" →
    /// "montreal"), apostrophes and periods inside a word dropped ("St. Louis" → "st louis", "76ers'" → "76ers").</summary>
    public static List<string> Words(string? text)
    {
        var words = new List<string>();
        if (string.IsNullOrEmpty(text))
        {
            return words;
        }

        var sb = new StringBuilder();
        foreach (var ch in text.Normalize(NormalizationForm.FormD))
        {
            if (CharUnicodeInfo.GetUnicodeCategory(ch) == UnicodeCategory.NonSpacingMark)
            {
                continue;
            }

            if (char.IsLetterOrDigit(ch))
            {
                sb.Append(char.ToLowerInvariant(ch));
            }
            else if (ch is '\'' or '’' or '.')
            {
                // "St.", "76ers'": part of the word
            }
            else if (sb.Length > 0)
            {
                words.Add(sb.ToString());
                sb.Clear();
            }
        }

        if (sb.Length > 0)
        {
            words.Add(sb.ToString());
        }

        return words;
    }

    private static string UrlPart(string? url)
    {
        if (string.IsNullOrEmpty(url))
        {
            return string.Empty;
        }

        string raw;
        if (Uri.TryCreate(url, UriKind.Absolute, out var u))
        {
            raw = u.AbsolutePath + " " + u.Query;
        }
        else
        {
            raw = url;
        }

        try
        {
            return Uri.UnescapeDataString(raw);
        }
        catch (UriFormatException)
        {
            return raw;
        }
    }

    /// <summary>The words that name one team.</summary>
    public sealed class TeamWords
    {
        private readonly List<string[]> _names;

        private TeamWords(List<string[]> names, string abbr)
        {
            _names = names;
            Abbr = abbr;
        }

        public string Abbr { get; }

        public static TeamWords From(GameTeam t)
        {
            var names = new List<string[]>();
            foreach (var n in new[] { t.Name, t.ShortName, t.Nickname, t.Location })
            {
                var w = Words(n);
                // "Tampa Bay Rays", "Rays", "Tampa Bay": a single word of two letters is no name ("LA" is the abbreviation's job)
                if (w.Count == 0 || string.Concat(w).Length < 3 || names.Any(x => x.SequenceEqual(w)))
                {
                    continue;
                }

                names.Add(w.ToArray());
            }

            var abbr = Words(t.Abbr);
            return new TeamWords(names, abbr.Count == 1 && abbr[0].Length >= 2 ? abbr[0] : string.Empty);
        }

        internal bool NamedIn(List<string> words) => _names.Any(n => Contains(words, n));

        internal bool AbbrIn(List<string> words) => Abbr.Length > 0 && words.Contains(Abbr, StringComparer.Ordinal);

        private static bool Contains(List<string> words, string[] seq)
        {
            for (var i = 0; i + seq.Length <= words.Count; i++)
            {
                var hit = true;
                for (var j = 0; j < seq.Length && hit; j++)
                {
                    hit = string.Equals(words[i + j], seq[j], StringComparison.Ordinal);
                }

                if (hit)
                {
                    return true;
                }
            }

            return false;
        }
    }
}
