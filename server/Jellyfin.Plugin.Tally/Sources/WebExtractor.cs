using System;
using System.Collections.Generic;
using System.Linq;
using System.Net.Http;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using AngleSharp.Dom;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Tally.Sources;

/// <summary>A stream candidate discovered on a web page.</summary>
public class ExtractedStream
{
    public string Url { get; set; } = string.Empty;

    public string Name { get; set; } = string.Empty;

    /// <summary>Page the stream URL was discovered on — used as Referer upstream.</summary>
    public string Referer { get; set; } = string.Empty;

    /// <summary>Nearest ancestor page that looks like an event — carries the
    /// matchup slug used for naming/grouping.</summary>
    public string Context { get; set; } = string.Empty;

    /// <summary>The name is a page title (or an element's title attribute), not an event page's slug or the text of
    /// a link to the event: page titles are mostly the site's own name and tagline, so such a name stands only if it
    /// names a game (see <c>ChannelNaming</c>).</summary>
    public bool NameFromTitle { get; set; } = true;
}

/// <summary>
/// Stage-1 extractor: crawls a page (and its iframes/watch links) over plain HTTP,
/// scanning markup and inline scripts for HLS/DASH manifest URLs. Page fetches run
/// in parallel batches — listing sites expose every event + embed page as ordinary
/// markup, so a deep crawl is cheap and needs no browser.
/// </summary>
public partial class WebExtractor
{
    private static readonly Regex ManifestUrl = ManifestUrlRegex();
    private static readonly Regex PlayerConfig = PlayerConfigRegex();
    private static readonly Regex Atob = AtobRegex();
    private static readonly Regex LinkHint = LinkHintRegex();

    // HTTP pages are cheap — the crawl is bounded by depth + this budget, not by
    // the (expensive, browser-bound) MaxPages setting.
    private const int MaxHttpPages = 96;
    private const int FetchParallelism = 8;
    private const int MaxStreams = 160;

    private readonly HttpClient _http;
    private readonly ILogger _logger;
    private readonly string _userAgent;
    private readonly List<string> _discoveredLinks = new();
    private readonly List<string> _targetedPages = new();
    private readonly Dictionary<string, WantedGame> _targetGames = new(StringComparer.OrdinalIgnoreCase);

    /// <summary>Candidate pages (event links, embeds) seen during the last
    /// <see cref="ExtractAsync"/> run — useful as browser-fallback seeds.</summary>
    public IReadOnlyList<string> DiscoveredLinks => _discoveredLinks;

    /// <summary>Pages the last run visited because they name a wanted team (or were reached from one).</summary>
    public IReadOnlyList<string> TargetedPages => _targetedPages;

    /// <summary>Pages the last run visited, in all.</summary>
    public int PagesVisited { get; private set; }

    /// <summary>Of <see cref="PagesVisited"/>, those visited for wanted games.</summary>
    public int TargetedPagesVisited { get; private set; }

    /// <summary>Pages a crawl for wanted games may visit on top of the general budget: 16 per game, 32 to 160.</summary>
    public static int TargetedBudget(int wantedGames) => wantedGames <= 0 ? 0 : Math.Clamp(16 * wantedGames, 32, 160);

    /// <summary>The wanted game whose teams a link to <paramref name="pageUrl"/> (or to a page embedding it) named,
    /// if any.</summary>
    public WantedGame? TargetGameFor(string? pageUrl)
    {
        if (string.IsNullOrEmpty(pageUrl) || !Uri.TryCreate(pageUrl, UriKind.Absolute, out _))
        {
            return null;
        }

        return _targetGames.TryGetValue(NormalizePage(pageUrl), out var g) ? g : null;
    }

    public WebExtractor(HttpClient http, ILogger logger, string userAgent)
    {
        _http = http;
        _logger = logger;
        _userAgent = userAgent;
    }

    public Task<List<ExtractedStream>> ExtractAsync(string startUrl, int maxPages, CancellationToken ct)
        => ExtractAsync(startUrl, maxPages, ct, null);

    /// <param name="wanted">Games to look for first: pages whose link text or address names a team of one of them
    /// (and what those pages embed) are visited before the general crawl, on a budget of their own
    /// (<see cref="TargetedBudget"/>) on top of the general one.</param>
    /// <param name="targetedOnly">A game-driven search: read the start page and the pages that name a wanted team,
    /// nothing else.</param>
    public async Task<List<ExtractedStream>> ExtractAsync(string startUrl, int maxPages, CancellationToken ct, IReadOnlyList<WantedGame>? wanted, bool targetedOnly = false)
    {
        _ = maxPages; // governs the browser fallback; HTTP uses MaxHttpPages
        wanted ??= Array.Empty<WantedGame>();
        var generalBudget = targetedOnly ? 1 : MaxHttpPages;
        var targetedBudget = TargetedBudget(wanted.Count);
        var generalVisited = 0;
        var targetedVisited = 0;
        var found = new List<ExtractedStream>();
        var visited = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var titles = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        // page → the matchup a link to it (or to the page embedding it) was labeled with ("Chiefs vs Bills")
        var labels = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        // Two-tier queue: event pages and embeds are where streams live —
        // they jump ahead of generic "watch"-hint links (nav, blog posts) that
        // would otherwise burn the page budget.
        var hot = new Queue<CrawlItem>();
        var warm = new Queue<CrawlItem>();
        // Pages that name a wanted team go before both, best first: what a page naming both teams embeds (3), the
        // page itself (2), then pages naming one team (1).
        var targeted = new PriorityQueue<CrawlItem, (int, long)>();
        long seq = 0;
        hot.Enqueue(new CrawlItem(startUrl, 0, startUrl));
        void Enqueue(CrawlItem item, bool priority)
        {
            if (item.Priority > 0)
            {
                targeted.Enqueue(item, (-item.Priority, seq++));
            }
            else
            {
                (priority ? hot : warm).Enqueue(item);
            }
        }

        bool GeneralLeft() => generalVisited < generalBudget && (hot.Count > 0 || warm.Count > 0);
        bool TargetedLeft() => targetedVisited < targetedBudget && targeted.Count > 0;

        while ((GeneralLeft() || TargetedLeft()) && !ct.IsCancellationRequested)
        {
            var batch = new List<CrawlItem>();
            while (batch.Count < FetchParallelism && (GeneralLeft() || TargetedLeft()))
            {
                var fromTargeted = TargetedLeft();
                var item = fromTargeted ? targeted.Dequeue() : hot.Count > 0 ? hot.Dequeue() : warm.Dequeue();
                if (item.Depth <= 3 && visited.Add(NormalizePage(item.Url)))
                {
                    batch.Add(item);
                    if (fromTargeted)
                    {
                        targetedVisited++;
                        _targetedPages.Add(item.Url);
                    }
                    else
                    {
                        generalVisited++;
                    }
                }
            }

            if (batch.Count == 0)
            {
                break;
            }

            var pages = await Task.WhenAll(batch.Select(b => FetchPage(b.Url, ct))).ConfigureAwait(false);
            for (var i = 0; i < batch.Count; i++)
            {
                var item = batch[i];
                var (url, depth) = (item.Url, item.Depth);
                var html = pages[i];
                if (html == null)
                {
                    continue;
                }

                var pageTitle = ExtractTitle(html);
                titles[NormalizePage(url)] = pageTitle;
                if (item.Game != null)
                {
                    _targetGames.TryAdd(NormalizePage(url), item.Game);
                }

                // An event page is its own naming context; everything deeper
                // (embeds, player pages) inherits it. So is a page a link named a wanted game's teams on.
                var origin = IsEventUrl(url) || item.GamePage ? url : item.Origin;
                ScanTextForStreams(html, url, pageTitle, origin, found);
                ScanScripts(html, url, pageTitle, origin, found);

                // What a page found for a wanted game links to or embeds is looked at for that game too.
                var inherit = item.Priority > 0 && depth > 0 ? Math.Max(item.Priority, item.Game != null ? 3 : 1) : 0;

                try
                {
                    var doc = new AngleSharp.Html.Parser.HtmlParser().ParseDocument(html);

                    foreach (var f in doc.QuerySelectorAll("iframe[src], frame[src], embed[src], video[src], source[src]"))
                    {
                        var src = Resolve(url, f.GetAttribute("src"));
                        if (src == null)
                        {
                            continue;
                        }

                        if (IsManifest(src))
                        {
                            AddStream(found, src, f.GetAttribute("title") ?? f.GetAttribute("name") ?? pageTitle, url, origin);
                        }
                        else if (f.LocalName is "iframe" or "frame" or "embed")
                        {
                            _discoveredLinks.Add(src);
                            if (labels.TryGetValue(NormalizePage(url), out var parentLabel))
                            {
                                labels.TryAdd(NormalizePage(src), parentLabel);
                            }

                            if (depth < 3)
                            {
                                var (priority, game) = inherit > 0 ? (inherit, item.Game) : Target(wanted, f.GetAttribute("title"), src);
                                Enqueue(new CrawlItem(src, depth + 1, origin, priority, game), true);

                                // The page's other links for this event ("Link 2", "HD", "Backup") are often buttons that
                                // swap the player's id by script — invisible to a crawl unless the siblings are built here.
                                foreach (var sibling in SiblingEmbeds(src, html))
                                {
                                    _discoveredLinks.Add(sibling);
                                    if (labels.TryGetValue(NormalizePage(url), out var siblingLabel))
                                    {
                                        labels.TryAdd(NormalizePage(sibling), siblingLabel);
                                    }

                                    Enqueue(new CrawlItem(sibling, depth + 1, origin, priority, game), true);
                                }
                            }
                        }
                    }

                    if (depth < 2)
                    {
                        foreach (var a in doc.QuerySelectorAll("a[href]"))
                        {
                            var href = Resolve(url, a.GetAttribute("href"));
                            var text = a.TextContent?.Trim() ?? string.Empty;
                            if (href == null || visited.Contains(NormalizePage(href)))
                            {
                                continue;
                            }

                            // Follow links that look like watch/stream pages, event
                            // matchups ("Team A vs Team B"), or same-host game pages
                            // ending in a numeric id (/mlb/team-a-team-b/1376048).
                            var ev = IsEventPath(url, href);
                            var looksLikeEvent = StreamClassifier.LooksLikeEvent(text);
                            var follow = ev || LinkHint.IsMatch(href) || LinkHint.IsMatch(text) || looksLikeEvent;

                            // A link naming a wanted team is followed whatever it looks like ("/mlb/tb-phi", "Rays").
                            var own = Target(wanted, text, href);
                            var inherited = inherit > 0 && follow;
                            var priority = Math.Max(own.Priority, inherited ? inherit : 0);
                            var game = own.Game ?? (inherited ? item.Game : null);
                            if (follow || priority > 0)
                            {
                                _discoveredLinks.Add(href);
                                if (LinkLabel(text) is { } label)
                                {
                                    labels.TryAdd(NormalizePage(href), label);
                                }

                                Enqueue(new CrawlItem(href, depth + 1, ev ? href : origin, priority, game, own.Game != null), ev || looksLikeEvent);
                            }
                        }
                    }
                }
                catch (Exception ex)
                {
                    _logger.LogDebug("JellyTV: DOM parse failed {Url}: {Msg}", url, ex.Message);
                }
            }
        }

        PagesVisited = visited.Count;
        TargetedPagesVisited = targetedVisited;
        _logger.LogInformation("JellyTV: HTTP crawl visited {Pages} pages ({Targeted} for wanted games), found {Streams} manifest candidates", visited.Count, targetedVisited, found.Count);

        // Name streams from the event page slug ("/mlb/yankees-diamondbacks/…" → "New York Yankees Arizona
        // Diamondbacks"), then the matchup a link to the page said ("Yankees vs Diamondbacks"). Failing both, the event
        // page's title is kept only as a hint (NameFromTitle): a title is mostly the site's name and tagline.
        foreach (var s in found)
        {
            var named = NameFromUrl(s.Context);
            if (named == null && (labels.TryGetValue(NormalizePage(s.Context), out var label) || labels.TryGetValue(NormalizePage(s.Referer), out label)))
            {
                named = label;
            }

            // Found for a wanted game on a page whose link named both its teams ("/mlb/tb-phi"): named after the game
            // unless the page's own name already says which game it is.
            if ((TargetGameFor(s.Context) ?? TargetGameFor(s.Referer)) is { } game && (named == null || !game.NamesBoth(named)))
            {
                named = game.Name;
            }

            if (!string.IsNullOrEmpty(named))
            {
                s.Name = named;
                s.NameFromTitle = false;
            }
            else if (titles.TryGetValue(NormalizePage(s.Context), out var t) && StreamClassifier.CleanName(t) is { Length: > 0 } cleaned)
            {
                s.Name = cleaned;
            }
        }

        // Validate candidates in parallel — keep only live playlists.
        var validated = new List<ExtractedStream>();
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var checks = found.Where(f => seen.Add(f.Url)).Select(async s =>
        {
            if (await IsLiveManifest(s, ct).ConfigureAwait(false))
            {
                lock (validated)
                {
                    validated.Add(s);
                }
            }
        });
        await Task.WhenAll(checks).ConfigureAwait(false);
        return validated;
    }

    private async Task<string?> FetchPage(string url, CancellationToken ct)
    {
        try
        {
            using var req = new HttpRequestMessage(HttpMethod.Get, url);
            req.Headers.TryAddWithoutValidation("User-Agent", _userAgent);
            req.Headers.TryAddWithoutValidation("Accept", "text/html,application/xhtml+xml,*/*;q=0.8");
            using var resp = await _http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false);
            if (!resp.IsSuccessStatusCode || !IsHtml(resp))
            {
                return null;
            }

            var html = await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
            return html.Length > 2_000_000 ? html[..2_000_000] : html;
        }
        catch (Exception ex)
        {
            _logger.LogDebug("JellyTV: web extract fetch failed {Url}: {Msg}", url, ex.Message);
            return null;
        }
    }

    private static void ScanTextForStreams(string html, string pageUrl, string pageTitle, string origin, List<ExtractedStream> sink)
    {
        foreach (Match m in ManifestUrl.Matches(html))
        {
            var u = Resolve(pageUrl, m.Value.Trim('"', '\''));
            if (u != null)
            {
                AddStream(sink, u, pageTitle, pageUrl, origin);
            }
        }
    }

    private static void ScanScripts(string html, string pageUrl, string pageTitle, string origin, List<ExtractedStream> sink)
    {
        // Player configs: file:/src:/source:/hlsUrl:/streamUrl: "..."
        foreach (Match m in PlayerConfig.Matches(html))
        {
            var u = Resolve(pageUrl, m.Groups[1].Value);
            if (u != null && IsManifest(u))
            {
                AddStream(sink, u, pageTitle, pageUrl, origin);
            }
        }

        // atob("...") blobs frequently hide the manifest URL
        foreach (Match m in Atob.Matches(html))
        {
            try
            {
                var decoded = Encoding.UTF8.GetString(Convert.FromBase64String(m.Groups[1].Value));
                ScanTextForStreams(decoded, pageUrl, pageTitle, origin, sink);
            }
            catch (FormatException)
            {
                // not actually base64 — ignore
            }
        }
    }

    private Task<bool> IsLiveManifest(ExtractedStream s, CancellationToken ct)
        => IsLiveAsync(_http, s.Url, new Dictionary<string, string> { ["User-Agent"] = _userAgent, ["Referer"] = s.Referer }, ct);

    /// <summary>True when <paramref name="url"/> answers with an HLS or DASH manifest, fetched with
    /// <paramref name="headers"/> (the ones the channel is played with).</summary>
    public static async Task<bool> IsLiveAsync(HttpClient http, string url, IReadOnlyDictionary<string, string> headers, CancellationToken ct)
    {
        try
        {
            using var req = new HttpRequestMessage(HttpMethod.Get, url);
            foreach (var (k, v) in headers)
            {
                req.Headers.TryAddWithoutValidation(k, v);
            }

            using var resp = await http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false);
            if (!resp.IsSuccessStatusCode)
            {
                return false;
            }

            var buf = new byte[1024];
            await using var stream = await resp.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
            var n = await stream.ReadAsync(buf, ct).ConfigureAwait(false);
            var head = Encoding.UTF8.GetString(buf, 0, n);
            return head.Contains("#EXTM3U") || head.Contains("<MPD") || head.Contains("<mpd");
        }
        catch (Exception)
        {
            return false;
        }
    }

    private static void AddStream(List<ExtractedStream> sink, string url, string? nameHint, string referer, string context)
    {
        if (sink.Count >= MaxStreams || sink.Any(s => s.Url == url))
        {
            return;
        }

        var name = StreamClassifier.CleanName(nameHint);
        sink.Add(new ExtractedStream
        {
            Url = url,
            Name = string.IsNullOrEmpty(name) ? "Stream" : name,
            Referer = referer,
            Context = context
        });
    }

    /// <summary>A link's text when it names a matchup ("Chiefs vs Bills", "KC @ BUF"); null for "Watch", "Link 2",
    /// "NBA" and the like.</summary>
    public static string? LinkLabel(string? text)
    {
        var cleaned = StreamClassifier.CleanName(text);
        return cleaned.Length >= 5 && StreamClassifier.LooksLikeEvent(cleaned) && ChannelGrouper.KeyFor(cleaned).Length > 0 ? cleaned : null;
    }

    private static bool IsHtml(HttpResponseMessage resp)
    {
        var ct = resp.Content.Headers.ContentType?.MediaType ?? string.Empty;
        return ct.Contains("html", StringComparison.OrdinalIgnoreCase) || ct.Contains("text", StringComparison.OrdinalIgnoreCase);
    }

    /// <summary>
    /// Embed URLs for the other stream ids a page switches its player between: an iframe <c>…/embed/1234</c> plus
    /// buttons such as <c>onclick="changeStream(1235)"</c> give <c>…/embed/1235</c>. Only ids passed to a script call
    /// from an event attribute count, so ordinary numbers on the page do not.
    /// </summary>
    public static IEnumerable<string> SiblingEmbeds(string embedUrl, string html)
    {
        var m = TrailingId().Match(embedUrl);
        if (!m.Success)
        {
            return Array.Empty<string>();
        }

        var own = m.Groups[2].Value;
        return SwitchCall().Matches(html)
            .Select(x => x.Groups[1].Value)
            .Where(id => id != own && id.Length >= 3)
            .Distinct(StringComparer.Ordinal)
            .Take(8)
            .Select(id => m.Groups[1].Value + id + m.Groups[3].Value)
            .ToList();
    }

    /// <summary>The best match among the wanted games for a link: its priority (2 for both teams of a game, 1 for
    /// one team, 0 for none) and, for a match on both teams, the game.</summary>
    private static (int Priority, WantedGame? Game) Target(IReadOnlyList<WantedGame> wanted, string? text, string url)
    {
        var best = 0;
        WantedGame? game = null;
        foreach (var w in wanted)
        {
            var score = w.Score(text, url);
            if (score > best)
            {
                best = score;
                game = score == 2 ? w : null;
            }
        }

        return (best, game);
    }

    /// <summary>A page to visit. <paramref name="Priority"/> above 0 puts it in the wanted games' queue;
    /// <paramref name="Game"/> is the game whose teams the link to it (or to a page embedding it) named;
    /// <paramref name="GamePage"/>: the link itself named them, so the page is its streams' naming context.</summary>
    private sealed record CrawlItem(string Url, int Depth, string Origin, int Priority = 0, WantedGame? Game = null, bool GamePage = false);

    [GeneratedRegex(@"^(.*/)(\d{3,})(/?(?:\?.*)?)$")]
    private static partial Regex TrailingId();

    [GeneratedRegex(@"\bon(?:click|change|mousedown|touchstart)\s*=\s*[""'][\w.$]+\(\s*['""]?(\d{3,})['""]?\s*\)", RegexOptions.IgnoreCase)]
    private static partial Regex SwitchCall();

    // Same-host links whose path ends in a numeric id are almost always event
    // pages on sports listings (/nba/lakers-celtics/1376048).
    private static bool IsEventPath(string baseUrl, string href)
    {
        var b = new Uri(baseUrl);
        var h = new Uri(href);
        if (!b.Host.Equals(h.Host, StringComparison.OrdinalIgnoreCase))
        {
            return false;
        }

        return IsEventUrl(href);
    }

    private static bool IsEventUrl(string url)
    {
        var seg = new Uri(url).AbsolutePath.Trim('/').Split('/');
        if (seg.Length < 2 || !seg[^1].All(char.IsDigit) || seg[^1].Length < 4)
        {
            return false;
        }

        // Structural pages can end in digits too (/new-stream-embed/56866) —
        // they must never become the naming context for a stream.
        return !seg.Any(s => s.Contains("embed", StringComparison.OrdinalIgnoreCase)
            || s.Contains("player", StringComparison.OrdinalIgnoreCase)
            || s.Contains("iframe", StringComparison.OrdinalIgnoreCase));
    }

    // "/nfl/carolina-panthers-atlanta-falcons/1360794" → "Carolina Panthers Atlanta Falcons"
    private static string? NameFromUrl(string url)
    {
        try
        {
            var seg = new Uri(url).AbsolutePath.Trim('/').Split('/');
            var slug = seg.LastOrDefault(s => s.Any(char.IsLetter) && s.Contains('-'));
            if (string.IsNullOrEmpty(slug))
            {
                return null;
            }

            var name = slug.Replace('-', ' ').Replace('_', ' ').Trim();
            return name.Length < 6 ? null
                : string.Join(' ', name.Split(' ', StringSplitOptions.RemoveEmptyEntries)
                    .Select(w => char.ToUpperInvariant(w[0]) + w[1..]));
        }
        catch (Exception)
        {
            return null;
        }
    }

    private static bool IsManifest(string url)
        => ManifestEndpoint.IsMatch(url);

    private static string? Resolve(string baseUrl, string? candidate)
    {
        if (string.IsNullOrWhiteSpace(candidate))
        {
            return null;
        }

        if (candidate.StartsWith("//", StringComparison.Ordinal))
        {
            candidate = new Uri(baseUrl).Scheme + ":" + candidate;
        }

        return Uri.TryCreate(new Uri(baseUrl), candidate, out var u) && (u.Scheme == "http" || u.Scheme == "https")
            ? u.ToString()
            : null;
    }

    private static string NormalizePage(string url)
    {
        // Strip fragment + common tracking params so we don't refetch the same page.
        var u = new Uri(url);
        return u.GetLeftPart(UriPartial.Path) + u.Query;
    }

    private static string ExtractTitle(string html)
    {
        var m = Regex.Match(html, "<title[^>]*>(.*?)</title>", RegexOptions.IgnoreCase | RegexOptions.Singleline);
        return m.Success ? m.Groups[1].Value.Trim() : string.Empty;
    }

    // Manifests don't always carry .m3u8/.mpd — syndicated embeds use endpoints
    // like /playlist/56813/load-playlist. Validation (IsLiveManifest) filters
    // false positives, so the matcher can be liberal.
    private static readonly Regex ManifestEndpoint = new(
        @"(?i)\.(m3u8|mpd)(\?|$)|/playlist/\d+|load[-_]?playlist|/manifest\.|/hls/|\.ism/",
        RegexOptions.Compiled);

    [GeneratedRegex(@"(?i)(?:https?:)?//[^\s""'<>\\]+/(?:playlist/\d+|[^\s""'<>\\]*load[-_]?playlist[^\s""'<>\\]*)|(?:https?:)?//[^\s""'<>\\]+\.(?:m3u8|mpd)(?:\?[^\s""'<>\\]*)?|/[A-Za-z0-9_./~-]+(?:\.(?:m3u8|mpd)(?:\?[^\s""'<>\\]*)?|/playlist/\d+[^\s""'<>\\]*)")]
    private static partial Regex ManifestUrlRegex();

    [GeneratedRegex(@"(?:file|src|source|hlsUrl|streamUrl|stream|video|clip)\s*[:=]\s*[""']([^""']{4,500})[""']", RegexOptions.IgnoreCase)]
    private static partial Regex PlayerConfigRegex();

    [GeneratedRegex(@"atob\(\s*[""']([A-Za-z0-9+/=]{12,})[""']\s*\)")]
    private static partial Regex AtobRegex();

    [GeneratedRegex(@"(?i)(watch|stream|live|play|channel|embed|player|video|tv|sport|game|match)")]
    private static partial Regex LinkHintRegex();
}
