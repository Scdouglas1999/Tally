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

    /// <summary>Candidate pages (event links, embeds) seen during the last
    /// <see cref="ExtractAsync"/> run — useful as browser-fallback seeds.</summary>
    public IReadOnlyList<string> DiscoveredLinks => _discoveredLinks;

    public WebExtractor(HttpClient http, ILogger logger, string userAgent)
    {
        _http = http;
        _logger = logger;
        _userAgent = userAgent;
    }

    public async Task<List<ExtractedStream>> ExtractAsync(string startUrl, int maxPages, CancellationToken ct)
    {
        _ = maxPages; // governs the browser fallback; HTTP uses MaxHttpPages
        var found = new List<ExtractedStream>();
        var visited = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var titles = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        // Two-tier queue: event pages and embeds are where streams live —
        // they jump ahead of generic "watch"-hint links (nav, blog posts) that
        // would otherwise burn the page budget.
        var hot = new Queue<(string Url, int Depth, string Origin)>();
        var warm = new Queue<(string Url, int Depth, string Origin)>();
        hot.Enqueue((startUrl, 0, startUrl));
        void Enqueue(string u, int d, string o, bool priority)
            => (priority ? hot : warm).Enqueue((u, d, o));

        while ((hot.Count > 0 || warm.Count > 0) && visited.Count < MaxHttpPages && !ct.IsCancellationRequested)
        {
            var batch = new List<(string Url, int Depth, string Origin)>();
            while (batch.Count < FetchParallelism && visited.Count < MaxHttpPages
                   && (hot.Count > 0 || warm.Count > 0))
            {
                var item = hot.Count > 0 ? hot.Dequeue() : warm.Dequeue();
                if (item.Depth <= 3 && visited.Add(NormalizePage(item.Url)))
                {
                    batch.Add(item);
                }
            }

            if (batch.Count == 0)
            {
                break;
            }

            var pages = await Task.WhenAll(batch.Select(b => FetchPage(b.Url, ct))).ConfigureAwait(false);
            for (var i = 0; i < batch.Count; i++)
            {
                var (url, depth, _) = batch[i];
                var html = pages[i];
                if (html == null)
                {
                    continue;
                }

                var pageTitle = ExtractTitle(html);
                titles[NormalizePage(url)] = pageTitle;

                // An event page is its own naming context; everything deeper
                // (embeds, player pages) inherits it.
                var origin = IsEventUrl(url) ? url : batch[i].Origin;
                ScanTextForStreams(html, url, pageTitle, origin, found);
                ScanScripts(html, url, pageTitle, origin, found);

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
                            if (depth < 3)
                            {
                                Enqueue(src, depth + 1, origin, true);

                                // The page's other links for this event ("Link 2", "HD", "Backup") are often buttons that
                                // swap the player's id by script — invisible to a crawl unless the siblings are built here.
                                foreach (var sibling in SiblingEmbeds(src, html))
                                {
                                    _discoveredLinks.Add(sibling);
                                    Enqueue(sibling, depth + 1, origin, true);
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
                            if (ev || LinkHint.IsMatch(href) || LinkHint.IsMatch(text)
                                || StreamClassifier.LooksLikeEvent(text))
                            {
                                _discoveredLinks.Add(href);
                                Enqueue(href, depth + 1, ev ? href : origin, ev || StreamClassifier.LooksLikeEvent(text));
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

        _logger.LogInformation("JellyTV: HTTP crawl visited {Pages} pages, found {Streams} manifest candidates", visited.Count, found.Count);

        // Name streams from the event page slug ("/mlb/yankees-diamondbacks/…"
        // → "New York Yankees Arizona Diamondbacks"), then the event page title.
        foreach (var s in found)
        {
            var named = NameFromUrl(s.Context);
            if (named == null && titles.TryGetValue(NormalizePage(s.Context), out var t))
            {
                named = StreamClassifier.CleanName(t);
            }

            if (!string.IsNullOrEmpty(named))
            {
                s.Name = named;
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

    private async Task<bool> IsLiveManifest(ExtractedStream s, CancellationToken ct)
    {
        try
        {
            using var req = new HttpRequestMessage(HttpMethod.Get, s.Url);
            req.Headers.TryAddWithoutValidation("User-Agent", _userAgent);
            req.Headers.TryAddWithoutValidation("Referer", s.Referer);
            using var resp = await _http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false);
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
