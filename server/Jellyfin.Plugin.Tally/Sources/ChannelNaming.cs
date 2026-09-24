using System;
using System.Collections.Generic;
using System.Linq;
using Jellyfin.Plugin.Tally.Models;
using Jellyfin.Plugin.Tally.Scores;

namespace Jellyfin.Plugin.Tally.Sources;

/// <summary>
/// Settles the names web page sources could only take from a page title (<see cref="SourceChannel.NameFromTitle"/>).
/// A page title is mostly the site's own name and tagline ("… - Sports for NBA, NFL, Boxing, MMA, and F1"), so a
/// channel named from one would show the site's advertising as a channel name. Such a name stands only as the game it
/// names: a channel whose title names both teams of a game on the scoreboard is named after that game ("Away at Home");
/// any other is dropped. Without a scoreboard (scores switched off or unreachable) a title that is shaped like a
/// matchup keeps just its matchup part ("Chiefs vs Bills"), and anything else is dropped.
/// </summary>
public static class ChannelNaming
{
    /// <returns>The channels to keep (renamed in place) and how many were dropped.</returns>
    public static (List<SourceChannel> Kept, int Dropped) Resolve(IReadOnlyList<SourceChannel> channels, IReadOnlyList<GameInfo>? games)
    {
        var weak = channels.Where(c => c.NameFromTitle).ToList();
        if (weak.Count == 0)
        {
            return (channels.ToList(), 0);
        }

        var names = new Dictionary<SourceChannel, string>();
        if (games is { Count: > 0 })
        {
            // the matcher mutates the games' channel lists: work on copies
            var copies = games.Select(g => g.Clone()).ToList();
            var probes = weak.Select((c, i) => new ChannelProbe(i.ToString(System.Globalization.CultureInfo.InvariantCulture), c.Name, null)).ToList();
            GameChannelMatcher.Match(copies, probes);
            foreach (var g in copies)
            {
                foreach (var gc in g.Channels.Where(x => x.Kind == "teams"))
                {
                    var c = weak[int.Parse(gc.Id, System.Globalization.CultureInfo.InvariantCulture)];
                    names.TryAdd(c, GameName(g));
                }
            }
        }
        else
        {
            foreach (var c in weak)
            {
                if (MatchupPart(c.Name) is { } m)
                {
                    names[c] = m;
                }
            }
        }

        var kept = new List<SourceChannel>(channels.Count);
        var dropped = 0;
        foreach (var c in channels)
        {
            if (!c.NameFromTitle)
            {
                kept.Add(c);
            }
            else if (names.TryGetValue(c, out var name))
            {
                c.Name = name;
                c.NameFromTitle = false;
                var key = ChannelGrouper.KeyFor(name);
                c.GroupKey = key.Length > 0 ? "web:" + key : string.Empty;
                c.Group = StreamClassifier.GroupFor(name, c.Group);
                kept.Add(c);
            }
            else
            {
                dropped++;
            }
        }

        return (kept, dropped);
    }

    /// <summary>"Arizona Diamondbacks at Colorado Rockies".</summary>
    public static string GameName(GameInfo g)
    {
        var away = string.IsNullOrWhiteSpace(g.Away.Name) ? g.Away.ShortName : g.Away.Name;
        var home = string.IsNullOrWhiteSpace(g.Home.Name) ? g.Home.ShortName : g.Home.Name;
        return string.IsNullOrWhiteSpace(away) || string.IsNullOrWhiteSpace(home) ? g.Name : away + " at " + home;
    }

    /// <summary>The part of a title that is a matchup ("Chiefs vs Bills" of "Chiefs vs Bills Live - SiteName -
    /// Sports for …"), or null when no part of it is.</summary>
    public static string? MatchupPart(string title)
    {
        foreach (var part in title.Split(new[] { " - ", " | ", " – ", " — ", " :: ", " · " }, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
        {
            var cleaned = StreamClassifier.CleanName(part);
            if (cleaned.Length >= 5 && StreamClassifier.LooksLikeEvent(cleaned) && ChannelGrouper.KeyFor(cleaned).Length > 0)
            {
                return cleaned;
            }
        }

        return null;
    }
}
