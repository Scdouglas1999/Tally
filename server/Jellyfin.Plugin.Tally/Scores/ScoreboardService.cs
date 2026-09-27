using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Net.Http;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Tally.Scores;

/// <summary>
/// Live game data from ESPN's public scoreboard feed. Pull-through cache, no background
/// polling: nothing is fetched unless a client is actually looking at scores, or the DVR has a rule or a job. Each league
/// refreshes on its own clock — seconds while a game is live, minutes otherwise.
/// </summary>
public sealed class ScoreboardService
{
    // Anything ESPN has a scoreboard for can be added under Settings → Live scores
    // ("basketball/nba", "hockey/nhl", "soccer/eng.1"…), or by a short name (LeagueCatalog.ShortNames); leagues the
    // sources carry are added by themselves (LeagueDetector).
    public static readonly string DefaultLeagues = string.Join(',', LeagueCatalog.Defaults);

    // The feed sits behind a bot filter that is picky in non-obvious ways: site.api.espn.com
    // 403s requests with no User-Agent (HttpClient's default), with an unknown one, and with a
    // browser one; site.web.api.espn.com serves the same JSON to an honestly-labeled client.
    // Try that first, keep the other as a fallback in case the rules move.
    private static readonly string[] Hosts = { "site.web.api.espn.com", "site.api.espn.com" };

    private static readonly TimeSpan LiveTtl = TimeSpan.FromSeconds(12);
    private static readonly TimeSpan SoonTtl = TimeSpan.FromSeconds(60);
    private static readonly TimeSpan IdleTtl = TimeSpan.FromMinutes(5);
    private static readonly TimeSpan ErrorTtl = TimeSpan.FromSeconds(30);

    private readonly IHttpClientFactory _httpClientFactory;
    private readonly ILogger<ScoreboardService> _logger;
    private readonly ConcurrentDictionary<string, LeagueCache> _cache = new(StringComparer.OrdinalIgnoreCase);
    private readonly ConcurrentDictionary<string, string> _errors = new(StringComparer.OrdinalIgnoreCase);
    private readonly SemaphoreSlim _refreshLock = new(1, 1);
    private readonly ConcurrentDictionary<string, LeagueCache> _upcoming = new(StringComparer.OrdinalIgnoreCase);
    private volatile IReadOnlyList<string> _fromSources = Array.Empty<string>();

    public ScoreboardService(IHttpClientFactory httpClientFactory, ILogger<ScoreboardService> logger)
    {
        _httpClientFactory = httpClientFactory;
        _logger = logger;
    }

    /// <summary>Leagues whose last refresh failed, and why — shown on the board so a dead feed
    /// never looks like a quiet day.</summary>
    public IReadOnlyDictionary<string, string> Errors => _errors;

    /// <summary>Where to fetch a league's board: ESPN's hosts in order, or only the configured development override.
    /// <paramref name="date"/> (yyyyMMdd) asks for one day's games instead of the feed's default day.</summary>
    public static IReadOnlyList<string> ScoreboardUrls(string league, string? sourceOverride, string? date = null)
        => string.IsNullOrWhiteSpace(sourceOverride)
            ? Hosts.Select(h => ScoreboardUrl(h, league, date)).ToList()
            : new[] { ScoreboardUrl(sourceOverride.Trim().TrimEnd('/'), league, date) };

    public static string ScoreboardUrl(string host, string league, string? date = null)
    {
        // College boards default to ranked teams only (~20 of ~75 games on a football Saturday).
        // groups=80 is all of FBS, groups=50 all of Division I basketball.
        var query = league.EndsWith("/college-football", StringComparison.OrdinalIgnoreCase) ? "?groups=80&limit=300"
            : league.EndsWith("college-basketball", StringComparison.OrdinalIgnoreCase) ? "?groups=50&limit=300"
            : string.Empty;
        if (date != null)
        {
            query += (query.Length == 0 ? "?" : "&") + "dates=" + date;
        }

        var origin = host.Contains("://", StringComparison.Ordinal) ? host : "https://" + host;
        return $"{origin}/apis/site/v2/sports/{league}/scoreboard{query}";
    }

    /// <summary>The day a daily board (MLB, NBA, NHL…) is showing, from its <c>day.date</c>; null for weekly
    /// boards (NFL, college football), which have no such field.</summary>
    public static string? BoardDay(string json)
    {
        using var doc = JsonDocument.Parse(json);
        return doc.RootElement.TryGetProperty("day", out var day) && day.ValueKind == JsonValueKind.Object
            && day.TryGetProperty("date", out var date) && date.ValueKind == JsonValueKind.String
            ? date.GetString()
            : null;
    }

    /// <summary>Today in US Eastern time (yyyy-MM-dd), the calendar ESPN's days follow.</summary>
    public static string EspnToday(DateTimeOffset now)
        => TimeZoneInfo.ConvertTime(now, Eastern).ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);

    /// <summary>
    /// The default board plus today's, when the feed's default day is not today. ESPN keeps serving yesterday's
    /// board until late morning Eastern, so without this the morning shows last night's finals and none of today's
    /// games. Yesterday's games stay (a late game may still be on); today's are added once each.
    /// </summary>
    public static List<GameInfo> MergeBoards(List<GameInfo> defaultBoard, List<GameInfo> today)
    {
        var seen = defaultBoard.Select(g => g.Id).ToHashSet(StringComparer.Ordinal);
        return defaultBoard.Concat(today.Where(g => seen.Add(g.Id))).ToList();
    }

    private static TimeZoneInfo Eastern
    {
        get
        {
            foreach (var id in new[] { "America/New_York", "Eastern Standard Time" })
            {
                try
                {
                    return TimeZoneInfo.FindSystemTimeZoneById(id);
                }
                catch (Exception ex) when (ex is TimeZoneNotFoundException or InvalidTimeZoneException)
                {
                }
            }

            return TimeZoneInfo.CreateCustomTimeZone("US Eastern (fixed)", TimeSpan.FromHours(-5), "US Eastern", "US Eastern");
        }
    }

    public static IReadOnlyList<string> ParseLeagues(string? configured)
        => (string.IsNullOrWhiteSpace(configured) ? DefaultLeagues : configured)
            .Split(new[] { ',', '\n', ' ' }, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Select(LeagueCatalog.Resolve)
            // path segments only — this string ends up in an outbound URL
            .Where(l => l.Count(ch => ch == '/') == 1 && l.All(ch => char.IsLetterOrDigit(ch) || ch is '/' or '.' or '-'))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .Take(16)
            .ToList();

    /// <summary>
    /// The leagues the scoreboard covers: the Leagues setting (its defaults when empty), plus the leagues the sources
    /// carry games of (<paramref name="fromSources"/>, see <see cref="LeagueDetector"/>) that the admin has not
    /// removed (<paramref name="excluded"/>).
    /// </summary>
    public static IReadOnlyList<string> EffectiveLeagues(string? configured, string? excluded, IEnumerable<string> fromSources)
    {
        var off = ParseList(excluded);
        return ParseLeagues(configured)
            .Concat(fromSources.Where(l => !off.Contains(l)))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .Take(24)
            .ToList();
    }

    /// <summary>A comma-separated list of league paths (or short names, see <see cref="LeagueCatalog.ShortNames"/>), as a
    /// set of paths.</summary>
    public static HashSet<string> ParseList(string? list)
        => (list ?? string.Empty).Split(new[] { ',', '\n', ' ' }, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Select(LeagueCatalog.Resolve)
            .ToHashSet(StringComparer.OrdinalIgnoreCase);

    /// <summary>Leagues added because the sources carry their games (set by <see cref="LeagueDetector"/>).</summary>
    public IReadOnlyList<string> LeaguesFromSources
    {
        get => _fromSources;
        set => _fromSources = value;
    }

    /// <summary>The leagues the scoreboard covers now (see <see cref="EffectiveLeagues"/>).</summary>
    public IReadOnlyList<string> ActiveLeagues
    {
        get
        {
            var config = Plugin.Instance?.Configuration;
            return EffectiveLeagues(config?.ScoreLeagues, config?.ScoreLeaguesExcluded, _fromSources);
        }
    }

    /// <summary>All games for the configured leagues, refreshing whatever has gone stale.</summary>
    public Task<List<GameInfo>> GetGamesAsync(CancellationToken cancellationToken)
        => GetGamesAsync(cancellationToken, TimeSpan.Zero);

    /// <summary>All games, refreshing a league only when it has gone stale and is at least <paramref name="minAge"/>
    /// old: the background stream search reads the board once a minute, not at the live rate a viewer's board uses.</summary>
    public Task<List<GameInfo>> GetGamesAsync(CancellationToken cancellationToken, TimeSpan minAge)
        => GetGamesAsync(ActiveLeagues, cancellationToken, minAge);

    /// <summary>All games, a league with a live game refreshed once its board is <paramref name="maxLiveAge"/> old: the
    /// RedZone channel polls every 10 s while someone watches it, a little faster than the board's live rate.</summary>
    public Task<List<GameInfo>> GetLiveGamesAsync(TimeSpan maxLiveAge, CancellationToken cancellationToken)
        => GetGamesAsync(ActiveLeagues, cancellationToken, TimeSpan.Zero, maxLiveAge);

    /// <summary>The games of <paramref name="leagues"/>, whether the scoreboard covers them or not (the league
    /// detector looks at leagues it does not cover yet), through the same cache.</summary>
    public async Task<List<GameInfo>> GetGamesAsync(IReadOnlyList<string> leagues, CancellationToken cancellationToken, TimeSpan minAge, TimeSpan? maxLiveAge = null)
    {
        var now = DateTimeOffset.UtcNow;

        if (leagues.Any(l => IsStale(l, now, minAge, maxLiveAge)))
        {
            // single flight: concurrent viewers share one refresh instead of each hitting upstream
            await _refreshLock.WaitAsync(cancellationToken).ConfigureAwait(false);
            try
            {
                now = DateTimeOffset.UtcNow;
                await Task.WhenAll(leagues.Where(l => IsStale(l, now, minAge, maxLiveAge)).Select(l => RefreshLeagueAsync(l, cancellationToken))).ConfigureAwait(false);
            }
            finally
            {
                _refreshLock.Release();
            }
        }

        return leagues
            .SelectMany(l => _cache.TryGetValue(l, out var c) ? c.Games : Enumerable.Empty<GameInfo>())
            .Select(g => g.Clone())
            .ToList();
    }

    private bool IsStale(string league, DateTimeOffset now, TimeSpan minAge, TimeSpan? maxLiveAge = null)
        => !_cache.TryGetValue(league, out var c) || (now - c.FetchedAt >= c.Ttl && now - c.FetchedAt >= minAge)
           || (maxLiveAge is { } max && c.Ttl == LiveTtl && now - c.FetchedAt >= max);

    private async Task RefreshLeagueAsync(string league, CancellationToken cancellationToken)
    {
        var now = DateTimeOffset.UtcNow;
        try
        {
            var json = await FetchBoardAsync(league, null, cancellationToken).ConfigureAwait(false);
            var games = EspnScoreboardParser.Parse(json, league);

            var today = EspnToday(now);
            var day = BoardDay(json);
            if (day != null && !string.Equals(day, today, StringComparison.Ordinal))
            {
                try
                {
                    var todayJson = await FetchBoardAsync(league, today.Replace("-", string.Empty, StringComparison.Ordinal), cancellationToken).ConfigureAwait(false);
                    games = MergeBoards(games, EspnScoreboardParser.Parse(todayJson, league));
                }
                catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or JsonException)
                {
                    // the default board is still worth showing
                    _logger.LogInformation("JellyTV scores: {League} board for {Day} unavailable: {Message}", league, today, ex.Message);
                }
            }

            _errors.TryRemove(league, out _);

            var ttl = games.Any(g => g.State == "in") ? LiveTtl
                : games.Any(g => g.State == "pre" && g.Start - now < TimeSpan.FromMinutes(45)) ? SoonTtl
                : IdleTtl;
            _cache[league] = new LeagueCache(games, now, ttl);
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or JsonException)
        {
            // keep serving the last good board; try again shortly
            _logger.LogWarning("JellyTV scores: {League} refresh failed: {Message}", league, ex.Message);
            _errors[league] = ex is TaskCanceledException ? "timed out" : ex.Message;
            var old = _cache.TryGetValue(league, out var c) ? c.Games : new List<GameInfo>();
            _cache[league] = new LeagueCache(old, now, ErrorTtl);
        }
    }

    private async Task<string> FetchBoardAsync(string league, string? date, CancellationToken cancellationToken)
    {
        var client = _httpClientFactory.CreateClient("jellytv");
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(TimeSpan.FromSeconds(15));

        HttpRequestException? failure = null;
        foreach (var url in ScoreboardUrls(league, Plugin.Instance?.Configuration.ScoreboardSourceOverride, date))
        {
            using var request = new HttpRequestMessage(HttpMethod.Get, url);
            request.Headers.TryAddWithoutValidation("User-Agent", "JellyTV/" + (Plugin.Instance?.Version?.ToString() ?? "0.1"));
            request.Headers.TryAddWithoutValidation("Accept", "application/json");
            using var response = await client.SendAsync(request, timeout.Token).ConfigureAwait(false);
            if (response.IsSuccessStatusCode)
            {
                return await response.Content.ReadAsStringAsync(timeout.Token).ConfigureAwait(false);
            }

            failure = new HttpRequestException($"{new Uri(url).Host} answered HTTP {(int)response.StatusCode}");
        }

        throw failure ?? new HttpRequestException("no scoreboard host answered");
    }

    /// <summary>
    /// Games of the next <paramref name="days"/> days in <paramref name="leagues"/>, for the DVR's team rules (the
    /// regular board only covers today, or this week for weekly boards). One request per league and day, cached for
    /// three hours; only called while a team rule exists.
    /// </summary>
    public async Task<List<GameInfo>> GetUpcomingAsync(IReadOnlyCollection<string> leagues, int days, CancellationToken cancellationToken)
    {
        var now = DateTimeOffset.UtcNow;
        var result = new List<GameInfo>();
        var seen = new HashSet<string>(StringComparer.Ordinal);
        foreach (var league in leagues)
        {
            for (var d = 1; d <= days; d++)
            {
                var day = TimeZoneInfo.ConvertTime(now, Eastern).AddDays(d).ToString("yyyyMMdd", CultureInfo.InvariantCulture);
                var key = league + "@" + day;
                if (!_upcoming.TryGetValue(key, out var cached) || now - cached.FetchedAt >= cached.Ttl)
                {
                    try
                    {
                        var json = await FetchBoardAsync(league, day, cancellationToken).ConfigureAwait(false);
                        cached = new LeagueCache(EspnScoreboardParser.Parse(json, league), now, TimeSpan.FromHours(3));
                    }
                    catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or JsonException)
                    {
                        _logger.LogInformation("JellyTV scores: {League} board for {Day} unavailable: {Message}", league, day, ex.Message);
                        cached = new LeagueCache(cached?.Games ?? new List<GameInfo>(), now, TimeSpan.FromMinutes(20));
                    }

                    _upcoming[key] = cached;
                }

                result.AddRange(cached.Games.Where(g => seen.Add(g.Id)).Select(g => g.Clone()));
            }
        }

        foreach (var stale in _upcoming.Where(kv => now - kv.Value.FetchedAt > TimeSpan.FromDays(2)).Select(kv => kv.Key).ToList())
        {
            _upcoming.TryRemove(stale, out _);
        }

        return result;
    }

    private sealed record LeagueCache(List<GameInfo> Games, DateTimeOffset FetchedAt, TimeSpan Ttl);
}
