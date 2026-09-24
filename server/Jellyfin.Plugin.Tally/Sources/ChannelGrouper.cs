using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.RegularExpressions;
using Jellyfin.Plugin.Tally.Models;
using Jellyfin.Plugin.Tally.Scores;

namespace Jellyfin.Plugin.Tally.Sources;

/// <summary>The merged channel list and where the ids of absorbed entries now point.</summary>
public sealed class GroupResult
{
    public List<SourceChannel> Channels { get; init; } = new();

    /// <summary>Absorbed channel id (and its legacy id) → the id of the channel it was merged into.</summary>
    public Dictionary<string, string> Aliases { get; init; } = new(StringComparer.OrdinalIgnoreCase);
}

/// <summary>
/// Folds entries that carry the same thing into one channel with several candidate streams: a web page's several
/// links for one game, an M3U's duplicates (same tvg-id, or the same name once quality words such as HD, 60FPS or
/// BACKUP are ignored), and — within one source — channels the game matcher ties to the same game by team names.
/// The merged channel keeps the id of one of its members, preferring the one that represented the group before,
/// so favorites and Jellyfin's guide keep pointing at it.
/// </summary>
public static partial class ChannelGrouper
{
    /// <summary>Runs after <see cref="ChannelIdentity.Assign"/>; the input channels are not modified.</summary>
    /// <param name="previousRepresentatives">Ids that represented a group in the last run (kept when still present).</param>
    /// <param name="stickyTeams">Member id → game id of earlier team matches, so a group survives the game ending.</param>
    public static GroupResult Group(
        IReadOnlyList<SourceChannel> channels,
        IReadOnlyList<GameInfo>? games,
        ISet<string>? previousRepresentatives = null,
        IReadOnlyDictionary<string, string>? stickyTeams = null,
        IDictionary<string, string>? teamMatchesOut = null)
    {
        var n = channels.Count;
        var parent = Enumerable.Range(0, n).ToArray();
        int Find(int x)
        {
            while (parent[x] != x)
            {
                parent[x] = parent[parent[x]];
                x = parent[x];
            }

            return x;
        }

        void Union(int a, int b)
        {
            a = Find(a);
            b = Find(b);
            if (a != b)
            {
                parent[Math.Max(a, b)] = Math.Min(a, b);
            }
        }

        var byKey = new Dictionary<string, int>(StringComparer.Ordinal);
        void Join(int i, string key)
        {
            if (byKey.TryGetValue(key, out var first))
            {
                Union(first, i);
            }
            else
            {
                byKey[key] = i;
            }
        }

        for (var i = 0; i < n; i++)
        {
            var c = channels[i];
            var src = c.SourceId + "|";
            if (!string.IsNullOrEmpty(c.GroupKey))
            {
                Join(i, src + "k:" + c.GroupKey);
            }

            if (!string.IsNullOrWhiteSpace(c.TvgId))
            {
                Join(i, src + "tvg:" + c.TvgId.Trim().ToLowerInvariant());
            }
        }

        // Same game by team names, same source.
        var teamOf = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        if (games != null && games.Count > 0)
        {
            var probes = channels.Select(c => new ChannelProbe(c.Id, c.Name, null)).ToList();
            GameChannelMatcher.Match(games, probes);
            foreach (var g in games)
            {
                foreach (var gc in g.Channels.Where(x => x.Kind == "teams"))
                {
                    teamOf.TryAdd(gc.Id, g.Id);
                }
            }
        }

        if (stickyTeams != null)
        {
            foreach (var (member, game) in stickyTeams)
            {
                teamOf.TryAdd(member, game);
            }
        }

        var index = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase);
        for (var i = 0; i < n; i++)
        {
            index.TryAdd(channels[i].Id, i);
        }

        foreach (var (member, game) in teamOf)
        {
            if (index.TryGetValue(member, out var i))
            {
                Join(i, channels[i].SourceId + "|game:" + game);
                teamMatchesOut?.TryAdd(member, game);
            }
        }

        var groups = new Dictionary<int, List<int>>();
        for (var i = 0; i < n; i++)
        {
            var r = Find(i);
            if (!groups.TryGetValue(r, out var list))
            {
                list = new List<int>();
                groups[r] = list;
            }

            list.Add(i);
        }

        var result = new GroupResult();
        foreach (var members in groups.OrderBy(g => g.Key).Select(g => g.Value))
        {
            var rep = members
                .OrderByDescending(i => previousRepresentatives?.Contains(channels[i].Id) == true)
                .ThenByDescending(i => string.IsNullOrEmpty(channels[i].GroupKey) || Plain(channels[i]))
                .ThenBy(i => channels[i].Name.Length)
                .ThenBy(i => i)
                .First();
            var ordered = new[] { rep }.Concat(members.Where(i => i != rep)).ToList();

            var merged = channels[rep].ShallowCopy();
            merged.Candidates = ordered.Select(i => new StreamCandidate
            {
                Url = channels[i].StreamUrl,
                Headers = new Dictionary<string, string>(channels[i].Headers, StringComparer.OrdinalIgnoreCase),
                Name = channels[i].Name,
                MemberId = channels[i].Id
            }).ToList();
            merged.MergedIds = ordered.Skip(1).Select(i => channels[i].Id).ToList();
            if (string.IsNullOrEmpty(merged.LogoUrl))
            {
                merged.LogoUrl = ordered.Select(i => channels[i].LogoUrl).FirstOrDefault(l => !string.IsNullOrEmpty(l)) ?? string.Empty;
            }

            if (string.IsNullOrEmpty(merged.TvgId))
            {
                merged.TvgId = ordered.Select(i => channels[i].TvgId).FirstOrDefault(t => !string.IsNullOrEmpty(t)) ?? string.Empty;
            }

            foreach (var i in ordered.Skip(1))
            {
                result.Aliases[channels[i].Id] = merged.Id;
                if (!string.IsNullOrEmpty(channels[i].LegacyId))
                {
                    result.Aliases.TryAdd(channels[i].LegacyId, merged.Id);
                }
            }

            result.Channels.Add(merged);
        }

        return result;
    }

    /// <summary>A name with quality words and link numbering removed, normalized: "ESPN HD" and "ESPN (Backup)" →
    /// "espn"; "Chiefs vs Bills - Link 2" → "chiefs vs bills".</summary>
    public static string QualityKey(string name)
    {
        var s = QualityWords().Replace(name, " ");
        s = LinkNumber().Replace(s, " ");
        return ChannelIdentity.Normalize(s);
    }

    private static readonly HashSet<string> Generic = new(StringComparer.Ordinal)
    {
        "stream", "streams", "live", "video", "player", "watch", "channel", "hls", "embed", "tv", "link", "event", "match", "game", "sports"
    };

    /// <summary>Group key for an entry's name (taken before the adapter numbers repeats " 2", " 3"): its quality-free
    /// form, or empty for names too generic to say two entries carry the same thing ("Stream", "Live").</summary>
    public static string KeyFor(string name)
    {
        var key = QualityKey(name);
        return key.Length < 3 || Generic.Contains(key) ? string.Empty : key;
    }

    private static bool Plain(SourceChannel c) => QualityKey(c.Name) == ChannelIdentity.Normalize(c.Name);

    [GeneratedRegex(@"(?i)\b(?:u?hd|fhd|sd|4k|uhd|hevc|h\.?26[45]|1080[pi]?|720p|540p|480p|(?:50|60|30|25)\s?fps|backup|alt(?:ernate)?|mirror|server\s?\d*|feed\s?\d*|raw|multi)\b")]
    private static partial Regex QualityWords();

    [GeneratedRegex(@"(?i)\b(?:link|stream|option|source)\s*#?\s*\d+\b")]
    private static partial Regex LinkNumber();
}
