using System;
using System.Collections.Generic;
using System.Linq;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Tally.Models;
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

    public WebSourceAdapter(SourceDefinition definition, IHttpClientFactory httpClientFactory, ILogger logger, BrowserRuntime? browser = null)
    {
        Definition = definition;
        _httpClientFactory = httpClientFactory;
        _logger = logger;
        _browser = browser;
    }

    public SourceDefinition Definition { get; }

    private static string UserAgent => Plugin.Instance?.Configuration.UserAgent
        ?? "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36";

    /// <summary>The full-site scan (see <see cref="WebExtractor.ExtractAsync"/>), then the headless browser when plain
    /// HTTP found nothing.</summary>
    public async Task<SourceSnapshot> RefreshAsync(CancellationToken cancellationToken)
    {
        var snapshot = new SourceSnapshot { SourceName = Definition.Name };

        if (string.IsNullOrWhiteSpace(Definition.PageUrl))
        {
            snapshot.Error = "No page URL configured";
            return snapshot;
        }

        var ua = UserAgent;
        List<ExtractedStream> found;
        WebExtractor? extractor = null;
        try
        {
            extractor = new WebExtractor(_httpClientFactory.CreateClient("jellytv"), _logger, ua);
            found = await extractor.ExtractAsync(Definition.PageUrl, Definition.MaxPages, cancellationToken).ConfigureAwait(false);
            snapshot.PagesVisited = extractor.PagesVisited;
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
        else if (found.Count == 0 && Definition.UseBrowserFallback && _browser != null)
        {
            found = await BrowserFallback(ua, extractor.DiscoveredLinks, cancellationToken).ConfigureAwait(false);
        }

        return Build(snapshot, found, ua);
    }

    /// <summary>
    /// A game-driven search pass on this source (see <see cref="WebExtractor.SearchGamesAsync"/>): plain HTTP only, the
    /// listing read once for all <paramref name="wanted"/> games and at most a few pages per game, every request through
    /// <paramref name="pacer"/>. <paramref name="stats"/> says what it read and whether the site pushed back.
    /// </summary>
    public async Task<SourceSnapshot> SearchGamesAsync(IReadOnlyList<WantedGame> wanted, SearchPacer pacer, GamePassStats stats, CancellationToken cancellationToken)
    {
        var snapshot = new SourceSnapshot { SourceName = Definition.Name };
        if (string.IsNullOrWhiteSpace(Definition.PageUrl) || wanted.Count == 0)
        {
            return snapshot;
        }

        var ua = UserAgent;
        var extractor = new WebExtractor(_httpClientFactory.CreateClient("jellytv"), _logger, ua);
        var found = await extractor.SearchGamesAsync(Definition.PageUrl, wanted, pacer, stats, cancellationToken).ConfigureAwait(false);
        snapshot.PagesVisited = stats.ListingReads + stats.PagesPerGame.Values.Sum();
        return Build(snapshot, found, ua);
    }

    private SourceSnapshot Build(SourceSnapshot snapshot, List<ExtractedStream> found, string ua)
    {
        var sourceId = Definition.Id.ToString("N");
        var sourceHeaders = Definition.Headers.ToDictionary();
        var channels = new List<SourceChannel>();
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var counter = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase);
        var skipped = 0;

        var otherLanguages = 0;
        foreach (var s in found)
        {
            if (!seen.Add(s.Url))
            {
                continue;
            }

            var language = s.Language
                ?? StreamLanguage.First(StreamLanguage.FromText(s.RawName), StreamLanguage.FromUrl(s.Url), StreamLanguage.FromUrl(s.Referer), StreamLanguage.FromUrl(s.Context))
                ?? StreamLanguage.English;
            if (!StreamLanguage.IsKept(language))
            {
                otherLanguages++;
                continue;
            }

            var group = StreamClassifier.GroupFor(s.Name, s.Context + " " + s.Referer);
            if (!Included(s, group))
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
            // A Spanish stream is named (and numbered) apart from the English ones: "Colts at Texans (Español)", so an
            // English channel keeps its name, and its id, whether or not a Spanish feed turns up beside it.
            var groupKey = s.NameFromTitle ? string.Empty : ChannelGrouper.KeyFor(s.Name);
            var name = s.Name;
            if (language == StreamLanguage.Spanish && !s.NameFromTitle)
            {
                name += StreamLanguage.NameSuffix;
            }

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
                NameFromTitle = s.NameFromTitle,
                Language = language
            });
        }

        if (otherLanguages > 0)
        {
            _logger.LogInformation("JellyTV: {Source}: left out {Count} streams in languages other than English and Spanish", Definition.Name, otherLanguages);
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

    private async Task<List<ExtractedStream>> BrowserFallback(string ua, IEnumerable<string> seeds, CancellationToken ct)
    {
        _logger.LogInformation("JellyTV: HTTP scan found nothing on {Url}; trying headless browser", Definition.PageUrl);
        try
        {
            return await new BrowserExtractor(_logger, ua, _browser!)
                .ExtractAsync(Definition.PageUrl, Definition.MaxPages, ct, seeds.Distinct(StringComparer.OrdinalIgnoreCase).ToList())
                .ConfigureAwait(false);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "JellyTV: browser fallback failed for {Url}", Definition.PageUrl);
            return new List<ExtractedStream>();
        }
    }

    // League/group tokens expand to every way they can appear: the classifier's
    // group name, the league tag used in listing URLs (/nfl/…), and loose names.
    private static readonly Dictionary<string, string[]> LeagueAliases = new(StringComparer.OrdinalIgnoreCase)
    {
        ["nfl"] = new[] { "american football", "nfl", "cfb", "ncaaf", "college football" },
        ["football"] = new[] { "american football", "nfl", "cfb", "ncaaf", "college football" },
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
    private bool Included(ExtractedStream s, string group)
    {
        if (string.IsNullOrWhiteSpace(Definition.Include))
        {
            return true;
        }

        var hay = (group + " " + s.Name + " " + s.Context + " " + s.Referer).ToLowerInvariant();
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
