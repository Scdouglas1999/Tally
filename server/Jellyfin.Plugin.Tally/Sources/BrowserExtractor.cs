using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Linq;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.Extensions.Logging;
using Microsoft.Playwright;

namespace Jellyfin.Plugin.Tally.Sources;

/// <summary>
/// Stage-2 extractor: drives a real headless browser (system Chrome/Edge via
/// Playwright — no bundled browser download), crawls promising links off the
/// start page, pokes play buttons inside every frame, and sniffs network
/// requests for manifests. Used when plain HTTP extraction finds nothing —
/// catches streams that only exist after JavaScript runs and user interaction.
/// </summary>
public partial class BrowserExtractor
{
    private static readonly string[] Channels = { "chrome", "msedge" };

    private readonly ILogger _logger;
    private readonly string _userAgent;

    public BrowserExtractor(ILogger logger, string userAgent)
    {
        _logger = logger;
        _userAgent = userAgent;
    }

    public async Task<List<ExtractedStream>> ExtractAsync(string url, int maxPages, CancellationToken ct, IEnumerable<string>? seedUrls = null)
    {
        var found = new ConcurrentBag<ExtractedStream>();
        try
        {
            using var pw = await Playwright.CreateAsync().ConfigureAwait(false);

            IBrowser? browser = null;
            string? usedChannel = null;
            foreach (var channel in Channels)
            {
                try
                {
                    browser = await pw.Chromium.LaunchAsync(new BrowserTypeLaunchOptions
                    {
                        Channel = channel,
                        Headless = true,
                        Args = new[] { "--autoplay-policy=no-user-gesture-required", "--mute-audio" }
                    }).ConfigureAwait(false);
                    usedChannel = channel;
                    break;
                }
                catch (Exception ex)
                {
                    _logger.LogDebug("JellyTV: browser channel {Ch} unavailable: {Msg}", channel, ex.Message);
                }
            }

            // Fallback: common executable paths (system chromium, non-standard
            // Chrome/Edge installs), then bundled chromium if present.
            if (browser == null)
            {
                var candidates = new[]
                {
                    "/usr/bin/chromium", "/usr/bin/chromium-browser", "/usr/bin/google-chrome",
                    "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
                    "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
                    "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
                    "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe"
                };
                foreach (var exe in candidates)
                {
                    if (!System.IO.File.Exists(exe))
                    {
                        continue;
                    }

                    try
                    {
                        browser = await pw.Chromium.LaunchAsync(new BrowserTypeLaunchOptions
                        {
                            ExecutablePath = exe,
                            Headless = true,
                            Args = new[] { "--autoplay-policy=no-user-gesture-required", "--mute-audio" }
                        }).ConfigureAwait(false);
                        usedChannel = exe;
                        break;
                    }
                    catch (Exception ex)
                    {
                        _logger.LogDebug("JellyTV: browser at {Exe} failed: {Msg}", exe, ex.Message);
                    }
                }
            }

            if (browser == null)
            {
                try
                {
                    browser = await pw.Chromium.LaunchAsync(new BrowserTypeLaunchOptions { Headless = true }).ConfigureAwait(false);
                }
                catch (Exception ex)
                {
                    _logger.LogWarning("JellyTV: no usable browser for fallback extraction ({Msg})", ex.Message);
                    return new List<ExtractedStream>();
                }
            }

            _logger.LogInformation("JellyTV: browser extraction via channel {Ch}", usedChannel ?? "bundled");

            await using (browser.ConfigureAwait(false))
            {
                var context = await browser.NewContextAsync(new BrowserNewContextOptions
                {
                    UserAgent = _userAgent,
                    ViewportSize = new ViewportSize { Width = 1366, Height = 768 },
                    IgnoreHTTPSErrors = true
                }).ConfigureAwait(false);
                await using (context.ConfigureAwait(false))
                {
                    // Popups = ad traps on these sites — kill them instantly.
                    // (page.Popup, not context.Page — the latter fires for the
                    // page we created ourselves and we'd close it.)

                    // Context-level request/response events cover every page and iframe.
                    // Referer = the frame that requested the manifest (the embed
                    // upstream expects); Context = its parent page (the game page
                    // holding the matchup slug we name the channel from).
                    void Capture(string u, IFrame? frame, string? headerReferer)
                    {
                        var frameUrl = frame?.Url;
                        var parentUrl = frame?.ParentFrame?.Url;
                        var referer = !string.IsNullOrEmpty(frameUrl) && frameUrl != "about:blank" ? frameUrl!
                            : !string.IsNullOrEmpty(headerReferer) ? headerReferer!
                            : url;
                        var context = !string.IsNullOrEmpty(parentUrl) && parentUrl != "about:blank" ? parentUrl!
                            : url;
                        found.Add(new ExtractedStream
                        {
                            Url = u,
                            Name = string.Empty,
                            Referer = referer,
                            Context = context
                        });
                    }

                    context.Request += (_, req) =>
                    {
                        var u = req.Url;
                        if (ManifestishUrl.IsMatch(u))
                        {
                            Capture(u, req.Frame, req.Headers.TryGetValue("referer", out var r) ? r : null);
                        }
                    };

                    // Manifests frequently don't carry .m3u8 in the URL at all —
                    // e.g. /playlist/123/load-playlist. The content-type is the
                    // reliable signal.
                    context.Response += (_, resp) =>
                    {
                        try
                        {
                            var ctHeader = resp.Headers.TryGetValue("content-type", out var c) ? c : string.Empty;
                            if (!ctHeader.Contains("mpegurl", StringComparison.OrdinalIgnoreCase)
                                && !ctHeader.Contains("dash+xml", StringComparison.OrdinalIgnoreCase))
                            {
                                return;
                            }

                            Capture(resp.Url, resp.Frame, resp.Request.Headers.TryGetValue("referer", out var r) ? r : null);
                        }
                        catch (Exception)
                        {
                            // response already gone — ignore
                        }
                    };

                    var page = await context.NewPageAsync().ConfigureAwait(false);
                    page.Popup += (_, p) => { _ = p.CloseAsync(); };
                    var visited = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
                    var toVisit = new List<string> { url };
                    // Seed with pages the HTTP pass already found (event links,
                    // embeds) — the landing page may redirect-loop headless while
                    // direct game pages load fine.
                    if (seedUrls != null)
                    {
                        // Event pages first: /sport/team-a-team-b/1376048-shaped
                        // URLs hold players; category pages rarely do.
                        var seeds = seedUrls
                            .OrderByDescending(u =>
                            {
                                var p = new Uri(u).AbsolutePath.Trim('/').Split('/');
                                return p.Length >= 2 && p[^1].All(char.IsDigit) && p[^1].Length >= 4;
                            })
                            .Take(16);
                        toVisit.AddRange(seeds);
                    }

                    var pageBudget = Math.Clamp(maxPages, 1, 16);
                    _logger.LogInformation("JellyTV: browser crawl starting — {Count} queued pages", toVisit.Count);

                    while (toVisit.Count > 0 && visited.Count < pageBudget && !ct.IsCancellationRequested)
                    {
                        var current = toVisit[0];
                        toVisit.RemoveAt(0);
                        if (!visited.Add(current))
                        {
                            continue;
                        }

                        List<string> links;
                        try
                        {
                            await page.GotoAsync(current, new PageGotoOptions
                            {
                                WaitUntil = WaitUntilState.DOMContentLoaded,
                                Timeout = 25000
                            }).ConfigureAwait(false);
                            await page.WaitForTimeoutAsync(3000).ConfigureAwait(false);
                            links = await CollectCandidateLinks(page, current).ConfigureAwait(false);
                        }
                        catch (Exception ex)
                        {
                            _logger.LogInformation("JellyTV: browser nav failed {Url}: {Msg}", current, ex.Message);
                            links = new List<string>();
                        }

                        // Poke players: strip ad overlays and JS-dispatch clicks
                        // inside every frame (element clicks fail when invisible
                        // ad layers cover the player — the first click is a popup
                        // trap on most of these sites).
                        _logger.LogDebug("JellyTV: {Page} frames: {Frames}", current, string.Join(" | ", page.Frames.Select(f => f.Url)));
                        await ClickPlayEverywhere(page).ConfigureAwait(false);
                        try { await page.Mouse.ClickAsync(683, 384).ConfigureAwait(false); } catch (Exception) { /* ignore */ }
                        await page.WaitForTimeoutAsync(3000).ConfigureAwait(false);
                        await ClickPlayEverywhere(page).ConfigureAwait(false);
                        await page.WaitForTimeoutAsync(3000).ConfigureAwait(false);

                        // Name streams discovered on this page — URL slug is the
                        // most reliable matchup name ("carolina-panthers-atlanta-
                        // falcons"); fall back to the page title.
                        string title;
                        try { title = await page.TitleAsync().ConfigureAwait(false) ?? string.Empty; }
                        catch (Exception) { title = string.Empty; }
                        var pageName = NameFromUrl(current) ?? StreamClassifier.CleanName(title);
                        foreach (var s in found.Where(s => s.Context == current || s.Referer.StartsWith(current, StringComparison.OrdinalIgnoreCase)))
                        {
                            if (string.IsNullOrEmpty(s.Name) && !string.IsNullOrEmpty(pageName))
                            {
                                s.Name = pageName;
                            }
                        }

                        foreach (var l in links)
                        {
                            if (!visited.Contains(l) && toVisit.Count < pageBudget * 2)
                            {
                                toVisit.Add(l);
                            }
                        }
                    }
                }
            }
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "JellyTV: browser extraction failed for {Url}", url);
        }

        _logger.LogInformation("JellyTV: browser pass captured {Count} manifest requests", found.Count);

        // Dedup — prefer streams whose referer is the deepest embed page.
        var best = new Dictionary<string, ExtractedStream>(StringComparer.OrdinalIgnoreCase);
        foreach (var s in found)
        {
            var key = s.Url.Split('?')[0];
            if (!best.TryGetValue(key, out var cur) || s.Referer.Length > cur.Referer.Length)
            {
                best[key] = s;
            }
        }

        // Collapse quality variants of the same event: /playlist/<id>/... URLs on
        // one page are one channel — keep the master (load-playlist) if present.
        var byEvent = new Dictionary<string, ExtractedStream>(StringComparer.OrdinalIgnoreCase);
        foreach (var s in best.Values)
        {
            var m = Regex.Match(s.Url, @"(?i)/playlist/(\d+)");
            var key = m.Success ? s.Context + "#" + m.Groups[1].Value : s.Url.Split('?')[0];
            if (!byEvent.TryGetValue(key, out var cur)
                || (s.Url.Contains("load-playlist", StringComparison.OrdinalIgnoreCase)
                    && !cur.Url.Contains("load-playlist", StringComparison.OrdinalIgnoreCase)))
            {
                byEvent[key] = s;
            }
        }

        return new List<ExtractedStream>(byEvent.Values);
    }

    /// <summary>Pull same-host links worth visiting: event matchups, watch/play
    /// pages, and /sport/team-vs-team/12345-style game URLs.</summary>
    private static async Task<List<string>> CollectCandidateLinks(IPage page, string baseUrl)
    {
        var result = new List<string>();
        try
        {
            var anchors = await page.QuerySelectorAllAsync("a[href]").ConfigureAwait(false);
            var baseHost = new Uri(baseUrl).Host;
            foreach (var a in anchors)
            {
                var href = await a.GetAttributeAsync("href").ConfigureAwait(false);
                var text = (await a.InnerTextAsync().ConfigureAwait(false))?.Trim() ?? string.Empty;
                if (string.IsNullOrEmpty(href) || href.StartsWith('#') || href.StartsWith("javascript", StringComparison.OrdinalIgnoreCase))
                {
                    continue;
                }

                if (!Uri.TryCreate(new Uri(baseUrl), href, out var abs) || (abs.Scheme != "http" && abs.Scheme != "https"))
                {
                    continue;
                }

                var sameHost = abs.Host.Equals(baseHost, StringComparison.OrdinalIgnoreCase);
                var seg = abs.AbsolutePath.Trim('/').Split('/');
                var eventPath = sameHost && seg.Length >= 2 && seg[^1].All(char.IsDigit) && seg[^1].Length >= 4;
                if (LinkHint.IsMatch(abs.ToString()) || LinkHint.IsMatch(text)
                    || StreamClassifier.LooksLikeEvent(text) || eventPath)
                {
                    result.Add(abs.ToString());
                }
            }
        }
        catch (Exception)
        {
            // DOM may be mid-navigation — just take what we got
        }

        return result.Distinct(StringComparer.OrdinalIgnoreCase).Take(12).ToList();
    }

    // Removes overlay divs covering the player (ad traps) and JS-dispatches
    // clicks on play controls — bypasses hit-testing which fails when invisible
    // layers sit over the real buttons.
    private async Task ClickPlayEverywhere(IPage page)
    {
        const string js = @"(() => {
            document.querySelectorAll('div,ins,a').forEach(e => {
                const s = getComputedStyle(e), r = e.getBoundingClientRect();
                const zi = parseInt(s.zIndex) || 0;
                if ((zi > 1000 || (s.position !== 'static' && r.width > 600 && r.height > 300)) && !e.closest('.player-wrapper,#player,.video-js') && !e.querySelector('iframe,video')) e.remove();
            });
            document.querySelectorAll('.player-poster,.play-wrapper,[data-poster],.vjs-big-play-button,.jw-icon-playback,button[class*=""play""],[aria-label*=""play""],.plyr__control--overlaid,.media-control-button,video').forEach(e => { try { e.click(); } catch (_) {} });
            document.querySelectorAll('video').forEach(v => { try { const p = v.play(); if (p) p.catch(() => {}); } catch (_) {} });
        })()";

        foreach (var frame in page.Frames)
        {
            try { await frame.EvaluateAsync(js).ConfigureAwait(false); }
            catch (Exception ex) { _logger.LogDebug("JellyTV: click eval failed on {Url}: {Msg}", frame.Url, ex.Message); }
        }
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

    [GeneratedRegex(@"(?i)(watch|stream|live|play|channel|embed|player|video|tv|sport|game|match)")]
    private static partial Regex LinkHintRegex();

    private static readonly Regex LinkHint = LinkHintRegex();

    // Manifests don't always carry .m3u8/.mpd in the URL — syndicated embeds use
    // endpoints like /playlist/56813/load-playlist. Match the common shapes.
    [GeneratedRegex(@"(?i)(\.m3u8|\.mpd|/playlist|load[-_]?playlist|/manifest|/hls/|\.ism/)")]
    private static partial Regex ManifestishUrlRegex();

    private static readonly Regex ManifestishUrl = ManifestishUrlRegex();
}
