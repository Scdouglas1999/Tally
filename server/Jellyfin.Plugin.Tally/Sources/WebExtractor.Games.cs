using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Tally.Sources;

/// <summary>What one game-driven search pass did on one source: for its log line and for the back-off.</summary>
public sealed class GamePassStats
{
    private readonly object _gate = new();

    /// <summary>How many times the pass read the source's listing page (once, unless it failed).</summary>
    public int ListingReads { get; set; }

    /// <summary>Pages read per game (by game id), the listing not included.</summary>
    public Dictionary<string, int> PagesPerGame { get; } = new(StringComparer.Ordinal);

    /// <summary>Every request the pass made: the listing, the games' pages and the playlists it checked.</summary>
    public int Requests { get; set; }

    /// <summary>Requests that timed out or could not connect.</summary>
    public int Failures { get; set; }

    /// <summary>The most requests the pass had under way at once.</summary>
    public int MaxInFlight { get; set; }

    /// <summary>Why the pass stopped because the site pushed back ("HTTP 429 from …"); null when it did not.</summary>
    public string? PushedBack { get; set; }

    internal void Add(Action<GamePassStats> change)
    {
        lock (_gate)
        {
            change(this);
        }
    }
}

public partial class WebExtractor
{
    /// <summary>A game's search reads at most this many pages (its event pages and what they embed; the shared
    /// listing page not included).</summary>
    public const int MaxPagesPerGame = 6;

    /// <summary>Links naming only one team of a game are read only when no link names both, and at most this many.</summary>
    public const int MaxOneTeamPages = 2;

    /// <summary>A search pass gives a page (or a playlist check) this long to answer.</summary>
    internal TimeSpan PageTimeout { get; set; } = TimeSpan.FromSeconds(15);

    /// <summary>
    /// A game-driven search pass, surgical: it reads <paramref name="listingUrl"/> once for all <paramref name="games"/>,
    /// then, one game after the other, that game's event pages (the listing's links that name both its teams; failing
    /// any, at most <see cref="MaxOneTeamPages"/> naming one) and what those pages embed or link to as a player, at most
    /// <see cref="MaxPagesPerGame"/> pages per game. Every request goes through <paramref name="pacer"/>. The pass stops
    /// early when the site pushes back: a 429, a 403 on a page that answered before, or timeouts and failed connections
    /// on most of its requests (<see cref="GamePassStats.PushedBack"/>). Streams are named after the game whose page
    /// they were found on (unless that page's own address names it) and kept only when their playlist answers.
    /// </summary>
    public async Task<List<ExtractedStream>> SearchGamesAsync(string listingUrl, IReadOnlyList<WantedGame> games, SearchPacer pacer, GamePassStats stats, CancellationToken ct)
    {
        using var stop = CancellationTokenSource.CreateLinkedTokenSource(ct);
        var pass = new Pass(this, pacer, stats, stop);
        var found = new List<ExtractedStream>();
        var titles = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        var labels = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        var linkTexts = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);

        try
        {
            var listing = await pass.GetAsync(listingUrl, isListing: true).ConfigureAwait(false);
            if (listing != null)
            {
                var links = ListingLinks(listingUrl, listing);
                foreach (var game in games)
                {
                    if (stop.IsCancellationRequested)
                    {
                        break;
                    }

                    await SearchGameAsync(pass, listingUrl, links, game, found, titles, labels, linkTexts).ConfigureAwait(false);
                }
            }
        }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested)
        {
            // the site pushed back: the pass stops here
        }

        var validated = new List<ExtractedStream>();
        if (stats.PushedBack == null && !ct.IsCancellationRequested)
        {
            NameStreams(found, labels, titles, linkTexts);
            var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            await Task.WhenAll(found.Where(f => seen.Add(f.Url)).Select(async s =>
            {
                if (await pass.IsLiveAsync(s).ConfigureAwait(false))
                {
                    lock (validated)
                    {
                        validated.Add(s);
                    }
                }
            })).ConfigureAwait(false);
        }

        if (stats.PushedBack == null && stats.Requests > 0 && stats.Failures * 2 > stats.Requests)
        {
            stats.PushedBack = string.Create(CultureInfo.InvariantCulture, $"{stats.Failures} of {stats.Requests} requests timed out or could not connect");
        }

        return validated.OrderBy(s => found.FindIndex(f => f.Url == s.Url)).ToList();
    }

    private async Task SearchGameAsync(Pass pass, string listingUrl, IReadOnlyList<(string Href, string Text)> links, WantedGame game,
        List<ExtractedStream> found, Dictionary<string, string> titles, Dictionary<string, string> labels, Dictionary<string, string> linkTexts)
    {
        // best first: what an event page embeds (4), a player link on it (3), an event page (2), a page naming one team (1)
        var queue = new PriorityQueue<GameItem, (int, long)>();
        long seq = 0;
        void Enqueue(GameItem item) => queue.Enqueue(item, (-item.Priority, seq++));

        var both = links.Where(l => game.Score(l.Text, l.Href) == 2).ToList();
        foreach (var (href, text) in both)
        {
            if (LinkLabel(text) is { } label)
            {
                labels.TryAdd(NormalizePage(href), label);
            }

            if (text.Length > 0)
            {
                linkTexts.TryAdd(NormalizePage(href), text);
            }

            Enqueue(new GameItem(href, 1, href, 2, true));
        }

        if (both.Count == 0)
        {
            foreach (var (href, _) in links.Where(l => game.Score(l.Text, l.Href) == 1).Take(MaxOneTeamPages))
            {
                Enqueue(new GameItem(href, 1, href, 1, false));
            }
        }

        var visited = new HashSet<string>(StringComparer.OrdinalIgnoreCase) { NormalizePage(listingUrl) };
        var pages = 0;
        while (queue.Count > 0 && pages < MaxPagesPerGame)
        {
            var item = queue.Dequeue();
            if (!visited.Add(NormalizePage(item.Url)))
            {
                continue;
            }

            pages++;
            pass.Stats.Add(s => s.PagesPerGame[game.GameId] = pages);
            var html = await pass.GetAsync(item.Url, isListing: false).ConfigureAwait(false);
            if (html == null)
            {
                continue;
            }

            var url = item.Url;
            var pageTitle = ExtractTitle(html);
            titles[NormalizePage(url)] = pageTitle;
            if (item.ForGame)
            {
                _targetGames.TryAdd(NormalizePage(url), game);
            }

            var origin = IsEventUrl(url) || item.Priority == 2 ? url : item.Origin;
            ScanTextForStreams(html, url, pageTitle, origin, found);
            ScanScripts(html, url, pageTitle, origin, found);

            AngleSharp.Html.Dom.IHtmlDocument doc;
            try
            {
                doc = new AngleSharp.Html.Parser.HtmlParser().ParseDocument(html);
            }
            catch (Exception ex)
            {
                _logger.LogDebug("JellyTV: DOM parse failed {Url}: {Msg}", url, ex.Message);
                continue;
            }

            var switchLabels = SwitchLabels(doc);
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
                else if (f.LocalName is "iframe" or "frame" or "embed" && item.Depth < 3)
                {
                    if (labels.TryGetValue(NormalizePage(url), out var parentLabel))
                    {
                        labels.TryAdd(NormalizePage(src), parentLabel);
                    }

                    NoteLinkText(linkTexts, src, url, switchLabels);
                    Enqueue(new GameItem(src, item.Depth + 1, origin, 4, item.ForGame));
                    foreach (var sibling in SiblingEmbeds(src, html))
                    {
                        if (labels.TryGetValue(NormalizePage(url), out var siblingLabel))
                        {
                            labels.TryAdd(NormalizePage(sibling), siblingLabel);
                        }

                        NoteLinkText(linkTexts, sibling, url, switchLabels);

                        Enqueue(new GameItem(sibling, item.Depth + 1, origin, 4, item.ForGame));
                    }
                }
            }

            // links: on a page naming one team, the ones naming both (the game's event page); on an event page, those
            // and its player links ("Link 2", "/stream/…")
            if (item.Priority is 1 or 2)
            {
                foreach (var a in doc.QuerySelectorAll("a[href]"))
                {
                    var href = Resolve(url, a.GetAttribute("href"));
                    var text = a.TextContent?.Trim() ?? string.Empty;
                    if (href == null || visited.Contains(NormalizePage(href)))
                    {
                        continue;
                    }

                    if (game.Score(text, href) == 2)
                    {
                        if (LinkLabel(text) is { } label)
                        {
                            labels.TryAdd(NormalizePage(href), label);
                        }

                        if (text.Length > 0)
                        {
                            linkTexts.TryAdd(NormalizePage(href), text);
                        }

                        Enqueue(new GameItem(href, item.Depth + 1, href, 2, true));
                    }
                    else if (item.Priority == 2 && (PlayerLinkText().IsMatch(text) || PlayerLinkPath().IsMatch(new Uri(href).AbsolutePath)
                                 || (text.Length <= 40 && StreamLanguage.FromText(text) != null))
                             && !IsEventUrl(href) && !StreamClassifier.LooksLikeEvent(text))
                    {
                        // (an event page's links to other games are not this game's players; its "Español" player is)
                        if (text.Length > 0)
                        {
                            linkTexts.TryAdd(NormalizePage(href), text);
                        }

                        Enqueue(new GameItem(href, item.Depth + 1, origin, 3, item.ForGame));
                    }
                }
            }
        }

        pass.Stats.Add(s => s.PagesPerGame[game.GameId] = pages);
    }

    /// <summary>The listing's links, resolved, once per pass.</summary>
    private List<(string Href, string Text)> ListingLinks(string listingUrl, string html)
    {
        var links = new List<(string, string)>();
        try
        {
            var doc = new AngleSharp.Html.Parser.HtmlParser().ParseDocument(html);
            foreach (var a in doc.QuerySelectorAll("a[href]"))
            {
                if (Resolve(listingUrl, a.GetAttribute("href")) is { } href)
                {
                    links.Add((href, a.TextContent?.Trim() ?? string.Empty));
                }
            }
        }
        catch (Exception ex)
        {
            _logger.LogDebug("JellyTV: DOM parse failed {Url}: {Msg}", listingUrl, ex.Message);
        }

        return links;
    }

    /// <summary>A page of a game's search: how deep, the event page it was reached from, its rank in the game's
    /// queue, and whether a link naming both teams led to it (its streams are then the game's).</summary>
    private sealed record GameItem(string Url, int Depth, string Origin, int Priority, bool ForGame);

    [GeneratedRegex(@"(?i)^(?:(?:link|stream|server|mirror|source|player|option)\s*#?\s*\d*|watch(?:\s+(?:live|now|here))?|hd|sd|backup|alt(?:ernate)?)$")]
    private static partial Regex PlayerLinkText();

    [GeneratedRegex(@"(?i)/(?:embed|player|stream|streams|watch|live-stream)(?:/|$|[-_.])")]
    private static partial Regex PlayerLinkPath();

    /// <summary>One pass's requests: paced, counted, and watched for the site pushing back.</summary>
    private sealed class Pass
    {
        private readonly WebExtractor _owner;
        private readonly SearchPacer _pacer;
        private readonly CancellationTokenSource _stop;
        private readonly Dictionary<string, Task<string?>> _pages = new(StringComparer.OrdinalIgnoreCase);
        private int _inFlight;

        public Pass(WebExtractor owner, SearchPacer pacer, GamePassStats stats, CancellationTokenSource stop)
        {
            _owner = owner;
            _pacer = pacer;
            Stats = stats;
            _stop = stop;
        }

        public GamePassStats Stats { get; }

        /// <summary>A page's HTML (null when it did not answer with one); each page is fetched once per pass.</summary>
        public Task<string?> GetAsync(string url, bool isListing)
        {
            var key = NormalizePage(url);
            lock (_pages)
            {
                if (!_pages.TryGetValue(key, out var task))
                {
                    task = FetchAsync(url, key, isListing);
                    _pages[key] = task;
                }

                return task;
            }
        }

        private async Task<string?> FetchAsync(string url, string key, bool isListing)
        {
            var uri = new Uri(url);
            using var slot = await EnterAsync(uri).ConfigureAwait(false);
            if (isListing)
            {
                Stats.Add(s => s.ListingReads++);
            }

            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(_stop.Token);
            timeout.CancelAfter(_owner.PageTimeout);
            try
            {
                using var req = new HttpRequestMessage(HttpMethod.Get, uri);
                req.Headers.TryAddWithoutValidation("User-Agent", _owner._userAgent);
                req.Headers.TryAddWithoutValidation("Accept", "text/html,application/xhtml+xml,*/*;q=0.8");
                using var resp = await _owner._http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, timeout.Token).ConfigureAwait(false);
                var status = (int)resp.StatusCode;
                if (status == 429)
                {
                    PushBack($"HTTP 429 from {uri.Host}");
                    return null;
                }

                if (status == (int)HttpStatusCode.Forbidden && _pacer.AnsweredBefore(key))
                {
                    PushBack($"HTTP 403 from {uri.Host} on a page that answered before");
                    return null;
                }

                if (!resp.IsSuccessStatusCode || !IsHtml(resp))
                {
                    return null;
                }

                var html = await resp.Content.ReadAsStringAsync(timeout.Token).ConfigureAwait(false);
                _pacer.Answered(key);
                return html.Length > 2_000_000 ? html[..2_000_000] : html;
            }
            catch (Exception ex) when (IsFailure(ex))
            {
                Failed(url, ex);
                return null;
            }
        }

        /// <summary>A stream's playlist answers (checked with the headers it is played with).</summary>
        public async Task<bool> IsLiveAsync(ExtractedStream s)
        {
            if (_stop.IsCancellationRequested)
            {
                return false;
            }

            var uri = new Uri(s.Url);
            IDisposable slot;
            try
            {
                slot = await EnterAsync(uri).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                return false;
            }

            using (slot)
            {
                using var timeout = CancellationTokenSource.CreateLinkedTokenSource(_stop.Token);
                timeout.CancelAfter(_owner.PageTimeout);
                try
                {
                    using var req = new HttpRequestMessage(HttpMethod.Get, uri);
                    req.Headers.TryAddWithoutValidation("User-Agent", _owner._userAgent);
                    req.Headers.TryAddWithoutValidation("Referer", s.Referer);
                    using var resp = await _owner._http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, timeout.Token).ConfigureAwait(false);
                    if ((int)resp.StatusCode == 429)
                    {
                        PushBack($"HTTP 429 from {uri.Host}");
                        return false;
                    }

                    if (!resp.IsSuccessStatusCode)
                    {
                        return false;
                    }

                    var buf = new byte[1024];
                    await using var stream = await resp.Content.ReadAsStreamAsync(timeout.Token).ConfigureAwait(false);
                    var n = await stream.ReadAsync(buf, timeout.Token).ConfigureAwait(false);
                    var head = System.Text.Encoding.UTF8.GetString(buf, 0, n);
                    return head.Contains("#EXTM3U") || head.Contains("<MPD") || head.Contains("<mpd");
                }
                catch (Exception ex) when (IsFailure(ex))
                {
                    Failed(s.Url, ex);
                    return false;
                }
            }
        }

        private async Task<IDisposable> EnterAsync(Uri uri)
        {
            var slot = await _pacer.EnterAsync(uri, _stop.Token).ConfigureAwait(false);
            var now = Interlocked.Increment(ref _inFlight);
            Stats.Add(s =>
            {
                s.Requests++;
                s.MaxInFlight = Math.Max(s.MaxInFlight, now);
            });
            return new Leave(this, slot);
        }

        /// <summary>A timeout or a failed connection (not the pass being stopped).</summary>
        private bool IsFailure(Exception ex)
            => !_stop.IsCancellationRequested && ex is HttpRequestException or TaskCanceledException or OperationCanceledException or System.IO.IOException;

        private void Failed(string url, Exception ex)
        {
            var stopNow = false;
            Stats.Add(s =>
            {
                s.Failures++;
                // most requests failing, with a few of them made: stop now rather than time out on the rest
                stopNow = s.Failures >= 2 && s.Failures * 2 > s.Requests && s.PushedBack == null;
            });
            _owner._logger.LogDebug("JellyTV: stream search fetch failed on {Host}: {Type}: {Message}", new Uri(url).Host, ex.GetType().Name, ex.Message);
            if (stopNow)
            {
                PushBack(string.Create(CultureInfo.InvariantCulture, $"{Stats.Failures} of {Stats.Requests} requests timed out or could not connect"));
            }
        }

        private void PushBack(string why)
        {
            Stats.Add(s => s.PushedBack ??= why);
            _stop.Cancel();
        }

        private sealed class Leave : IDisposable
        {
            private readonly Pass _pass;
            private IDisposable? _slot;

            public Leave(Pass pass, IDisposable slot)
            {
                _pass = pass;
                _slot = slot;
            }

            public void Dispose()
            {
                if (Interlocked.Exchange(ref _slot, null) is { } slot)
                {
                    Interlocked.Decrement(ref _pass._inFlight);
                    slot.Dispose();
                }
            }
        }
    }
}
