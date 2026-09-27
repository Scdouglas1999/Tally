using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Tally.Scores;

/// <summary>A team as ESPN's team list has it.</summary>
public sealed record DirectoryTeam(string League, string Abbr, string Name, string ShortName, string Nickname, string Location, string Logo);

/// <summary>Two teams a channel's name reads as, with the league they play in (null when the name was only split at
/// "vs" / "at" and no team list knows them).</summary>
public sealed record ChannelMatchup(string? League, string AwayText, string HomeText, DirectoryTeam? Away, DirectoryTeam? Home, bool At);

/// <summary>
/// ESPN's team lists (<c>…/sports/{league}/teams</c>) for the leagues Tally knows (<see cref="LeagueCatalog"/>): what a
/// channel that matches no game on the scoreboard is read with, to split its name into two teams and find their logos
/// (<see cref="Read"/>). A list is fetched the first time a card needs it, then kept a week (on disk too); a failed
/// fetch is tried again an hour later.
/// </summary>
public sealed class TeamDirectory
{
    private static readonly TimeSpan Keep = TimeSpan.FromDays(7);
    private static readonly TimeSpan RetryAfter = TimeSpan.FromHours(1);
    private static readonly string[] Separators = { "VS", "V", "AT", "X" };

    private readonly IHttpClientFactory _httpClientFactory;
    private readonly ILogger<TeamDirectory> _logger;
    private readonly ConcurrentDictionary<string, (List<DirectoryTeam> Teams, DateTimeOffset At)> _lists = new(StringComparer.OrdinalIgnoreCase);
    private readonly ConcurrentDictionary<string, Task<List<DirectoryTeam>>> _loading = new(StringComparer.OrdinalIgnoreCase);

    public TeamDirectory(IHttpClientFactory httpClientFactory, ILogger<TeamDirectory> logger)
    {
        _httpClientFactory = httpClientFactory;
        _logger = logger;
    }

    /// <summary>Tests: team lists in place of ESPN.</summary>
    internal Func<string, List<DirectoryTeam>>? ListsOverride { get; set; }

    /// <summary>
    /// Reads a channel name as two teams: "Mississippi State Bulldogs Missouri Tigers", "Oregon Ducks vs USC Trojans".
    /// The leagues of <paramref name="sportGroup"/> ("American Football") are tried first, then the others. Null when
    /// the name names no two teams of one league and has no "vs" / "at" to split at.
    /// </summary>
    public async Task<ChannelMatchup?> ReadAsync(string channelName, string? sportGroup, IEnumerable<string> activeLeagues, CancellationToken ct)
    {
        var order = LeagueOrder(sportGroup, activeLeagues);
        var lists = order.Select(l => GetAsync(l, ct)).ToList(); // the first time, all lists load at once
        for (var i = 0; i < order.Count; i++)
        {
            var teams = await lists[i].ConfigureAwait(false);
            if (Read(channelName, teams) is { } found)
            {
                return found with { League = order[i] };
            }
        }

        return SplitOnly(channelName);
    }

    /// <summary>The leagues to try for a channel, the likeliest first.</summary>
    public static List<string> LeagueOrder(string? sportGroup, IEnumerable<string> activeLeagues)
    {
        var sport = sportGroup switch
        {
            "College Football" => "football/college-football",
            "American Football" => "football/",
            "Basketball" => "basketball/",
            "Baseball" => "baseball/",
            "Hockey" => "hockey/",
            "Soccer" => "soccer/",
            _ => null
        };
        var known = LeagueCatalog.Known.Select(k => k.Path).ToList();
        var active = activeLeagues.Where(l => known.Contains(l, StringComparer.OrdinalIgnoreCase)).ToList();
        return known.Where(l => sport != null && l.StartsWith(sport, StringComparison.OrdinalIgnoreCase))
            .Concat(active)
            .Concat(known)
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToList();
    }

    /// <summary>
    /// Two teams of <paramref name="teams"/> named in <paramref name="channelName"/>, each by its full name, its
    /// place or its short name ("Mississippi State Bulldogs", "Missouri", "Ole Miss"), or, in a name split at "vs" or
    /// "at", by its nickname alone; a place, short name or nickname two teams share counts for neither. In the order the
    /// name gives them; null unless exactly two are named.
    /// </summary>
    public static ChannelMatchup? Read(string channelName, IReadOnlyList<DirectoryTeam> teams)
    {
        var tokens = Scores.GameChannelMatcher.Tokens(channelName);
        var sep = tokens.FindIndex(t => Separators.Contains(t));
        var hasSeparator = sep > 0 && sep < tokens.Count - 1;
        // a place, short name or nickname two teams share ("Bulldogs", "Miami") names neither
        var shared = teams.SelectMany(t => new[] { t.Location, t.ShortName, t.Nickname }.Distinct(StringComparer.OrdinalIgnoreCase))
            .Where(k => !string.IsNullOrWhiteSpace(k))
            .GroupBy(k => string.Join(' ', Scores.GameChannelMatcher.Tokens(k)), StringComparer.Ordinal)
            .Where(g => g.Count() > 1)
            .Select(g => g.Key)
            .ToHashSet(StringComparer.Ordinal);
        var hits = new List<(int Start, int Length, DirectoryTeam Team)>();
        foreach (var team in teams)
        {
            var keys = new[] { team.Name }
                .Concat(new[] { team.Location, team.ShortName }.Where(k => !shared.Contains(string.Join(' ', Scores.GameChannelMatcher.Tokens(k)))))
                .Concat(hasSeparator && !shared.Contains(string.Join(' ', Scores.GameChannelMatcher.Tokens(team.Nickname))) ? new[] { team.Nickname } : Array.Empty<string>())
                .Select(k => Scores.GameChannelMatcher.Tokens(k))
                .Where(k => k.Count > 0 && string.Concat(k).Length >= 3)
                .OrderByDescending(k => k.Count)
                .ToList();
            foreach (var key in keys)
            {
                var at = IndexOf(tokens, key);
                if (at >= 0)
                {
                    hits.Add((at, key.Count, team));
                    break;
                }
            }
        }

        // longest names first, no two overlapping, one per team
        var chosen = new List<(int Start, int Length, DirectoryTeam Team)>();
        foreach (var h in hits.OrderByDescending(h => h.Length).ThenBy(h => h.Start))
        {
            if (chosen.All(c => h.Start >= c.Start + c.Length || c.Start >= h.Start + h.Length) && chosen.All(c => c.Team != h.Team))
            {
                chosen.Add(h);
            }
        }

        if (chosen.Count != 2)
        {
            return null;
        }

        chosen = chosen.OrderBy(c => c.Start).ToList();
        var at2 = hasSeparator && tokens[sep] == "AT";
        return new ChannelMatchup(chosen[0].Team.League, chosen[0].Team.Name, chosen[1].Team.Name, chosen[0].Team, chosen[1].Team, at2);
    }

    /// <summary>"Riverton Otters vs Lakeside Herons" → the two sides, when no team list knows them.</summary>
    public static ChannelMatchup? SplitOnly(string channelName)
    {
        var m = System.Text.RegularExpressions.Regex.Match(channelName.Trim(), @"^(.{3,}?)\s+(vs\.?|v\.?|at|@|x)\s+(.{3,})$", System.Text.RegularExpressions.RegexOptions.IgnoreCase);
        if (!m.Success)
        {
            return null;
        }

        var sep = m.Groups[2].Value.ToLowerInvariant();
        return new ChannelMatchup(null, m.Groups[1].Value.Trim(), m.Groups[3].Value.Trim(), null, null, sep is "at" or "@");
    }

    private static int IndexOf(List<string> tokens, List<string> key)
    {
        for (var i = 0; i + key.Count <= tokens.Count; i++)
        {
            var hit = true;
            for (var j = 0; j < key.Count && hit; j++)
            {
                hit = tokens[i + j] == key[j];
            }

            if (hit)
            {
                return i;
            }
        }

        return -1;
    }

    /// <summary>A league's team list (empty when it cannot be had).</summary>
    public async Task<List<DirectoryTeam>> GetAsync(string league, CancellationToken ct)
    {
        if (ListsOverride != null)
        {
            return ListsOverride(league);
        }

        var now = DateTimeOffset.UtcNow;
        if (_lists.TryGetValue(league, out var have) && now - have.At < (have.Teams.Count > 0 ? Keep : RetryAfter))
        {
            return have.Teams;
        }

        var load = _loading.GetOrAdd(league, l => LoadAsync(l));
        try
        {
            return await load.WaitAsync(ct).ConfigureAwait(false);
        }
        finally
        {
            if (load.IsCompleted)
            {
                _loading.TryRemove(league, out _);
            }
        }
    }

    private async Task<List<DirectoryTeam>> LoadAsync(string league)
    {
        var now = DateTimeOffset.UtcNow;
        var file = FilePath(league);
        try
        {
            if (file != null && File.Exists(file) && now - File.GetLastWriteTimeUtc(file) < Keep)
            {
                var cached = Parse(await File.ReadAllTextAsync(file).ConfigureAwait(false), league);
                if (cached.Count > 0)
                {
                    _lists[league] = (cached, new DateTimeOffset(File.GetLastWriteTimeUtc(file), TimeSpan.Zero));
                    return cached;
                }
            }
        }
        catch (Exception ex) when (ex is IOException or JsonException or UnauthorizedAccessException)
        {
            _logger.LogDebug(ex, "JellyTV teams: cached {League} list unreadable", league);
        }

        Exception? failure = null;
        foreach (var url in TeamsUrls(league, Plugin.Instance?.Configuration.ScoreboardSourceOverride))
        {
            try
            {
                using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(15));
                var client = _httpClientFactory.CreateClient("jellytv");
                using var request = new HttpRequestMessage(HttpMethod.Get, url);
                request.Headers.TryAddWithoutValidation("User-Agent", "JellyTV/" + (Plugin.Instance?.Version?.ToString() ?? "0.1"));
                request.Headers.TryAddWithoutValidation("Accept", "application/json");
                using var response = await client.SendAsync(request, cts.Token).ConfigureAwait(false);
                response.EnsureSuccessStatusCode();
                var json = await response.Content.ReadAsStringAsync(cts.Token).ConfigureAwait(false);
                var teams = Parse(json, league);
                if (teams.Count == 0)
                {
                    continue;
                }

                _lists[league] = (teams, now);
                if (file != null)
                {
                    Directory.CreateDirectory(System.IO.Path.GetDirectoryName(file)!);
                    await File.WriteAllTextAsync(file, json).ConfigureAwait(false);
                }

                _logger.LogInformation("JellyTV teams: {League} has {Count} teams", LeagueCatalog.Label(league), teams.Count);
                return teams;
            }
            catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or JsonException or IOException)
            {
                failure = ex;
            }
        }

        _logger.LogInformation("JellyTV teams: {League} team list unavailable: {Message}", LeagueCatalog.Label(league), failure?.Message ?? "no teams");
        _lists[league] = (new List<DirectoryTeam>(), now);
        return new List<DirectoryTeam>();
    }

    /// <summary>Where a league's team list is read: the development override first (a simulator may serve none), then
    /// ESPN.</summary>
    public static IEnumerable<string> TeamsUrls(string league, string? sourceOverride)
    {
        if (!string.IsNullOrWhiteSpace(sourceOverride))
        {
            yield return TeamsUrl(league, sourceOverride);
        }

        yield return TeamsUrl(league, null);
    }

    /// <summary>ESPN's team list for a league (or the development override's).</summary>
    public static string TeamsUrl(string league, string? sourceOverride)
    {
        var origin = string.IsNullOrWhiteSpace(sourceOverride) ? "https://site.web.api.espn.com" : sourceOverride.Trim().TrimEnd('/');
        var query = league.EndsWith("/college-football", StringComparison.OrdinalIgnoreCase) ? "?groups=80&limit=1000" : "?limit=1000";
        return $"{origin}/apis/site/v2/sports/{league}/teams{query}";
    }

    /// <summary>The teams of an ESPN team list: <c>sports[].leagues[].teams[].team</c>.</summary>
    public static List<DirectoryTeam> Parse(string json, string league)
    {
        using var doc = JsonDocument.Parse(json);
        var result = new List<DirectoryTeam>();
        if (!doc.RootElement.TryGetProperty("sports", out var sports) || sports.ValueKind != JsonValueKind.Array)
        {
            return result;
        }

        foreach (var sport in sports.EnumerateArray())
        {
            if (!sport.TryGetProperty("leagues", out var leagues) || leagues.ValueKind != JsonValueKind.Array)
            {
                continue;
            }

            foreach (var l in leagues.EnumerateArray())
            {
                if (!l.TryGetProperty("teams", out var teams) || teams.ValueKind != JsonValueKind.Array)
                {
                    continue;
                }

                foreach (var wrap in teams.EnumerateArray())
                {
                    var t = wrap.TryGetProperty("team", out var inner) ? inner : wrap;
                    var name = Str(t, "displayName");
                    if (string.IsNullOrEmpty(name))
                    {
                        continue;
                    }

                    var logo = t.TryGetProperty("logos", out var logos) && logos.ValueKind == JsonValueKind.Array && logos.GetArrayLength() > 0
                        ? Str(logos[0], "href")
                        : Str(t, "logo");
                    result.Add(new DirectoryTeam(league, Str(t, "abbreviation"), name, Str(t, "shortDisplayName"), Str(t, "name"), Str(t, "location"), logo));
                }
            }
        }

        return result;
    }

    private static string Str(JsonElement e, string name)
        => e.ValueKind == JsonValueKind.Object && e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() ?? string.Empty : string.Empty;

    private static string? FilePath(string league)
        => Plugin.Instance == null ? null : System.IO.Path.Combine(Plugin.Instance.DataFolderPath, "teams", league.Replace('/', '_') + ".json");
}
