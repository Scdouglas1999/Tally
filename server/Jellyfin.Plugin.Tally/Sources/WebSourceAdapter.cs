using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Tally.Models;
using Jellyfin.Plugin.Tally.Scores;
using Jellyfin.Plugin.Tally.Services;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Tally.Sources;

/// <summary>
/// Web source: scans a page (plus its embeds/links) for live stream manifests.
/// HTTP extraction first; optionally escalates to a headless browser that sniffs
/// network requests when the page builds players via JavaScript.
/// </summary>
public class WebSourceAdapter : ISourceAdapter
{
    private readonly IHttpClientFactory _httpClientFactory;
    private readonly ILogger _logger;
    private readonly BrowserRuntime? _browser;
    private readonly Func<CancellationToken, Task<IReadOnlyList<GameInfo>?>>? _games;

    /// <param name="games">Today's scoreboard, when there is one: a stream that names both teams of a game takes its
    /// group from the game's league and passes an Include filter naming that league.</param>
    public WebSourceAdapter(
        SourceDefinition definition,
        IHttpClientFactory httpClientFactory,
        ILogger logger,
        BrowserRuntime? browser = null,
        Func<CancellationToken, Task<IReadOnlyList<GameInfo>?>>? games = null)
    {
        Definition = definition;
        _httpClientFactory = httpClientFactory;
        _logger = logger;
        _browser = browser;
        _games = games;
    }

    public SourceDefinition Definition { get; }

    public async Task<SourceSnapshot> RefreshAsync(CancellationToken cancellationToken)
    {
        var snapshot = new SourceSnapshot { SourceName = Definition.Name };

        if (string.IsNullOrWhiteSpace(Definition.PageUrl))
        {
            snapshot.Error = "No page URL configured";
            return snapshot;
        }

        var ua = Plugin.Instance?.Configuration.UserAgent
            ?? "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36";

        List<ExtractedStream> found;
        WebExtractor? extractor = null;
        try
        {
            extractor = new WebExtractor(_httpClientFactory.CreateClient("jellytv"), _logger, ua);
            found = await extractor.ExtractAsync(Definition.PageUrl, Definition.MaxPages, cancellationToken)
                .ConfigureAwait(false);
        }
        catch (Exception ex)
        {
            snapshot.Error = "Web scan failed: " + ex.Message;
            _logger.LogWarning(ex, "JellyTV: web extraction failed for {Url}", Definition.PageUrl);
            return snapshot;
        }

        if (found.Count == 0 && Definition.UseBrowserFallback && _browser != null
            && !await _browser.ReadyAsync(allowDownloads: true, TimeSpan.FromSeconds(60), cancellationToken).ConfigureAwait(false))
        {
            // First use: the browser is still being downloaded (or failed). Keep the last channels; the refresh
            // that follows readiness brings the new ones.
            var status = _browser.Status;
            _logger.LogInformation("JellyTV: HTTP scan found nothing on {Url}; the headless browser is not ready ({Message})", Definition.PageUrl, status.Message);
            snapshot.Error = status.State == "failed" || status.Message.StartsWith("Preparing", StringComparison.Ordinal)
                ? status.Message
                : "Preparing the browser…";
            return snapshot;
        }

        var games = await GamesAsync(cancellationToken).ConfigureAwait(false);
        if (found.Count == 0 && Definition.UseBrowserFallback && _browser != null)
        {
            _logger.LogInformation("JellyTV: HTTP scan found nothing on {Url}; trying headless browser", Definition.PageUrl);
            try
            {
                var seeds = extractor?.DiscoveredLinks ?? Array.Empty<string>();
                var ranks = games == null ? null : CrawlRanks(seeds, games, DateTimeOffset.UtcNow);
                found = await new BrowserExtractor(_logger, ua, _browser)
                    .ExtractAsync(Definition.PageUrl, Definition.MaxPages, cancellationToken, seeds, ranks == null ? null : u => ranks.GetValueOrDefault(u, UnknownRank))
                    .ConfigureAwait(false);
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "JellyTV: browser fallback failed for {Url}", Definition.PageUrl);
            }
        }

        var sourceId = Definition.Id.ToString("N");
        var sourceHeaders = Definition.Headers.ToDictionary();
        var channels = new List<SourceChannel>();
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var counter = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase);
        var skipped = 0;
        var gameOf = games == null ? new Dictionary<int, GameInfo>() : GamesByStream(found, games);

        for (var i = 0; i < found.Count; i++)
        {
            var s = found[i];
            if (!seen.Add(s.Url))
            {
                continue;
            }

            var game = gameOf.GetValueOrDefault(i);
            var group = (game == null ? null : StreamClassifier.GroupForGame(game))
                ?? StreamClassifier.GroupFor(s.Name, s.Context + " " + s.Referer);
            if (!Included(s, group, game))
            {
                skipped++;
                continue;
            }

            var headers = new Dictionary<string, string>(sourceHeaders, StringComparer.OrdinalIgnoreCase)
            {
                ["Referer"] = s.Referer,
                ["Origin"] = new Uri(s.Referer).GetLeftPart(UriPartial.Authority)
            };
            if (!headers.ContainsKey("User-Agent"))
            {
                headers["User-Agent"] = ua;
            }

            // Disambiguate identical names ("Stream" repeated per page). Entries of one event share a group key,
            // so the several links a page offers for a game end up as one channel with several candidates. A name
            // taken from a page title gets its key once ChannelNaming has matched it to a game (or dropped it).
            var groupKey = s.NameFromTitle ? string.Empty : ChannelGrouper.KeyFor(s.Name);
            var name = s.Name;
            if (counter.TryGetValue(name, out var n))
            {
                counter[name] = n + 1;
                name = $"{name} {n + 1}";
            }
            else
            {
                counter[name] = 0;
            }

            channels.Add(new SourceChannel
            {
                Id = SourceChannel.MakeId(sourceId, s.Url),
                Name = name,
                StreamUrl = s.Url,
                Group = group,
                SourceId = sourceId,
                SourceName = Definition.Name,
                Headers = headers,
                GroupKey = groupKey.Length > 0 ? "web:" + groupKey : string.Empty,
                NameFromTitle = s.NameFromTitle
            });
        }

        if (skipped > 0)
        {
            _logger.LogInformation("JellyTV: {Source} include filter dropped {Count} streams", Definition.Name, skipped);
        }

        snapshot.Channels = channels;
        if (channels.Count == 0)
        {
            snapshot.Error = "No streams found on page";
        }

        return snapshot;
    }

    /// <summary>Crawl rank of a page no game on the scoreboard matches (another sport, or a game the feed lacks).</summary>
    public const int UnknownRank = 2;

    /// <summary>
    /// Crawl rank of each event page, from the game its URL names (see <see cref="BrowserExtractor.PlanCrawl"/>):
    /// 0 live now, 1 starting within 30 minutes, <see cref="UnknownRank"/> no game on the board, 3 later, 4 over.
    /// </summary>
    public static Dictionary<string, int> CrawlRanks(IEnumerable<string> pages, IReadOnlyList<GameInfo> games, DateTimeOffset now)
    {
        var named = pages.Distinct(StringComparer.OrdinalIgnoreCase)
            .Select(u => (Url: u, Name: BrowserExtractor.NameFromUrl(u)))
            .Where(p => p.Name != null)
            .ToList();
        var ranks = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase);
        foreach (var (i, g) in Matches(named.Select(p => p.Name!).ToList(), games, includeFinished: true))
        {
            var rank = g.State == "in" ? 0
                : g.State == "post" ? 4
                : g.Start - now <= TimeSpan.FromMinutes(30) ? 1
                : 3;
            ranks[named[i].Url] = Math.Min(rank, ranks.GetValueOrDefault(named[i].Url, int.MaxValue));
        }

        return ranks;
    }

    private async Task<IReadOnlyList<GameInfo>?> GamesAsync(CancellationToken cancellationToken)
    {
        if (_games == null)
        {
            return null;
        }

        try
        {
            var games = await _games(cancellationToken).ConfigureAwait(false);
            return games is { Count: > 0 } ? games : null;
        }
        catch (Exception ex) when (ex is not OperationCanceledException || !cancellationToken.IsCancellationRequested)
        {
            _logger.LogDebug(ex, "JellyTV: no scoreboard for {Source}", Definition.Name);
            return null;
        }
    }

    /// <summary>Stream index → the game its name names both teams of.</summary>
    private static Dictionary<int, GameInfo> GamesByStream(List<ExtractedStream> found, IReadOnlyList<GameInfo> games)
    {
        var result = new Dictionary<int, GameInfo>();
        foreach (var (i, g) in Matches(found.Select(f => f.Name).ToList(), games, includeFinished: false))
        {
            result.TryAdd(i, g);
        }

        return result;
    }

    /// <summary>(index into <paramref name="names"/>, game) for each name that names both teams of a game. The
    /// matcher passes over finished games; <paramref name="includeFinished"/> matches them too.</summary>
    private static IEnumerable<(int Index, GameInfo Game)> Matches(IReadOnlyList<string> names, IReadOnlyList<GameInfo> games, bool includeFinished)
    {
        if (names.Count == 0)
        {
            yield break;
        }

        // the matcher mutates the games' channel lists: work on copies
        var copies = games.Select(g => g.Clone()).ToList();
        if (includeFinished)
        {
            copies.ForEach(c => c.State = c.State == "post" ? "pre" : c.State);
        }

        GameChannelMatcher.Match(copies, names.Select((n, i) => new ChannelProbe(i.ToString(CultureInfo.InvariantCulture), n, null)).ToList());
        for (var k = 0; k < copies.Count; k++)
        {
            foreach (var gc in copies[k].Channels.Where(c => c.Kind == "teams"))
            {
                yield return (int.Parse(gc.Id, CultureInfo.InvariantCulture), games[k]);
            }
        }
    }

    private static readonly string[] CollegeFootball = { "college football", "ncaaf", "cfb", "ncaa football", "college-football", "ncaa-football" };

    // League/group tokens expand to every way they can appear: the classifier's
    // group name, the league tag used in listing URLs (/nfl/…), and loose names.
    private static readonly Dictionary<string, string[]> LeagueAliases = new(StringComparer.OrdinalIgnoreCase)
    {
        ["nfl"] = new[] { "american football", "nfl", "cfb", "ncaaf", "college football" },
        ["football"] = new[] { "american football", "nfl", "cfb", "ncaaf", "college football" },
        ["american football"] = new[] { "american football", "nfl", "cfb", "ncaaf", "college football" },
        ["ncaaf"] = CollegeFootball,
        ["cfb"] = CollegeFootball,
        ["college football"] = CollegeFootball,
        ["ncaa football"] = CollegeFootball,
        ["mlb"] = new[] { "baseball", "mlb" },
        ["baseball"] = new[] { "baseball", "mlb" },
        ["nba"] = new[] { "basketball", "nba", "wnba" },
        ["basketball"] = new[] { "basketball", "nba", "wnba" },
        ["wnba"] = new[] { "basketball", "wnba" },
        ["nhl"] = new[] { "hockey", "nhl" },
        ["hockey"] = new[] { "hockey", "nhl" },
        ["soccer"] = new[] { "soccer" },
        ["mma"] = new[] { "fighting", "mma", "ufc" },
        ["ufc"] = new[] { "fighting", "mma", "ufc" },
        ["f1"] = new[] { "motorsport", "formula 1", "f1" },
    };

    /// <summary>True when a stream passes the source's Include filter. A token
    /// matches on the classified group or as a word in the stream's name/URLs —
    /// so "NFL" keeps a game even when team names weren't enough to classify it.</summary>
    private bool Included(ExtractedStream s, string group, GameInfo? game)
    {
        if (string.IsNullOrWhiteSpace(Definition.Include))
        {
            return true;
        }

        // the game's league label and path ("NCAAF", "football college football") count as words of the stream
        var league = game == null ? string.Empty : " " + game.League + " " + game.LeaguePath.Replace('/', ' ').Replace('-', ' ');
        var hay = (group + " " + s.Name + " " + s.Context + " " + s.Referer + league).ToLowerInvariant();
        foreach (var raw in Definition.Include.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
        {
            var keywords = LeagueAliases.TryGetValue(raw, out var aliases) ? aliases : new[] { raw };
            foreach (var k in keywords)
            {
                if (StreamClassifier.ContainsKeyword(hay, k.ToLowerInvariant()))
                {
                    return true;
                }
            }
        }

        return false;
    }
}
