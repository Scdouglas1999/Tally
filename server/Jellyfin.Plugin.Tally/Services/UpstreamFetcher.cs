using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Tally.Services;

/// <summary>
/// Single upstream-fetch path for the proxy and the prefetch cache.
/// Anti-leech upstreams have contradictory header rules, so this adapts:
/// playlists keep their captured Referer (their endpoints 403 without it),
/// while media/segment URLs are retried with Referer+Origin stripped when the
/// first attempt fails — several CDNs (the fsy.nfl.com family behind the
/// redirect wrappers) actively 404 any request carrying a Referer. Hosts are
/// remembered once learned, and a resident browser relay remains as the last
/// resort for genuinely TLS-fingerprinted upstreams.
/// </summary>
public sealed class UpstreamFetcher
{
    private static readonly string[] MediaMarkers = { "/redirect/", ".ts", ".m4s", ".mp4", ".aac", ".vtt", ".key", ".cmfv", ".cmfa" };
    private static readonly string[] VolatileHeaders = { "Referer", "Origin" };

    private readonly IHttpClientFactory _httpClientFactory;
    private readonly BrowserFetchService _browser;
    private readonly ILogger<UpstreamFetcher> _logger;

    /// <summary>Learned per-host quirks (strip Referer / browser relay only) — survives restarts.</summary>
    private readonly HostKnowledge _hosts = new();
    private int _hostsLoaded;

    /// <summary>Hosts currently rate-limiting us — fail fast until the cooldown lapses
    /// instead of burning the allowance and extending the throttle.</summary>
    private readonly ConcurrentDictionary<string, DateTimeOffset> _cooldown = new(StringComparer.OrdinalIgnoreCase);

    public UpstreamFetcher(IHttpClientFactory httpClientFactory, BrowserFetchService browser, ILogger<UpstreamFetcher> logger)
    {
        _httpClientFactory = httpClientFactory;
        _browser = browser;
        _logger = logger;
    }

    public sealed class FetchOutcome : IDisposable
    {
        public int Status { get; init; }

        public string? ContentType { get; init; }

        /// <summary>Buffered body — set when the browser relay served the request.</summary>
        public byte[]? Body { get; init; }

        /// <summary>Streaming response — set when plain HttpClient served the request. Disposed by caller via this outcome.</summary>
        public HttpResponseMessage? Response { get; init; }

        public bool ViaBrowser => Body != null;

        public void Dispose() => Response?.Dispose();
    }

    private static string? KnowledgePath
        => Plugin.Instance == null ? null : Path.Combine(Plugin.Instance.DataFolderPath, "hosts.json");

    /// <summary>Load what previous runs learned and, if any host needs the browser relay, start the
    /// browser now — launching Chrome on the first viewer's request is what blows Jellyfin's probe budget.</summary>
    public async Task WarmAsync(CancellationToken ct)
    {
        EnsureHostsLoaded();
        if (_hosts.AnyNeedsBrowser)
        {
            var ok = await _browser.WarmAsync(ct).ConfigureAwait(false);
            _logger.LogInformation("JellyTV: browser relay pre-warmed for known hosts: {Ok}", ok);
        }
    }

    private void EnsureHostsLoaded()
    {
        if (Interlocked.Exchange(ref _hostsLoaded, 1) == 0 && KnowledgePath is { } path)
        {
            _hosts.Load(path);
        }
    }

    private void SaveHosts()
    {
        try
        {
            if (KnowledgePath is { } path)
            {
                _hosts.Save(path);
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            _logger.LogDebug(ex, "JellyTV: could not persist host knowledge");
        }
    }

    /// <summary>True for segment/key/media URLs — the ones anti-leech CDNs gate on.</summary>
    public static bool LooksLikeMedia(Uri uri)
    {
        var p = uri.AbsolutePath;
        return MediaMarkers.Any(m => p.Contains(m, StringComparison.OrdinalIgnoreCase));
    }

    /// <summary>Streaming-capable fetch used by the proxy's hot path.</summary>
    public async Task<FetchOutcome> FetchAsync(Uri uri, Dictionary<string, string> headers, CancellationToken ct)
    {
        EnsureHostsLoaded();
        var media = LooksLikeMedia(uri);
        if (media
            && _cooldown.TryGetValue(uri.Host, out var until)
            && DateTimeOffset.UtcNow < until)
        {
            return new FetchOutcome { Status = 503 };
        }

        if (media && _hosts.NeedsBrowser(uri.Host))
        {
            var r0 = await FetchViaBrowserAsync(uri, headers, ct).ConfigureAwait(false);
            if (r0 != null && r0.Status != 0)
            {
                return r0;
            }
            // Relay failed mid-flight — fall through to HttpClient anyway.
        }

        var strip = media && _hosts.StripsReferer(uri.Host);
        var resp = await SendDotNetAsync(uri, headers, strip, ct).ConfigureAwait(false);
        if (resp != null && resp.IsSuccessStatusCode)
        {
            return Streamed(resp);
        }

        if (!media)
        {
            // Playlists: keep the captured Referer, no fallbacks — a legit 404
            // shouldn't burn retries on every player poll.
            var status = resp != null ? (int)resp.StatusCode : 502;
            resp?.Dispose();
            return new FetchOutcome { Status = status };
        }

        var firstStatus = resp != null ? (int)resp.StatusCode : 0;
        resp?.Dispose();
        if (firstStatus == 429)
        {
            _cooldown[uri.Host] = DateTimeOffset.UtcNow.AddSeconds(15);
        }

        // Fallback 1: same request with Referer/Origin stripped — the fsy.nfl.com
        // family 404s any segment request carrying a Referer.
        if (!strip)
        {
            resp = await SendDotNetAsync(uri, headers, stripVolatile: true, ct).ConfigureAwait(false);
            if (resp != null && resp.IsSuccessStatusCode)
            {
                if (_hosts.LearnStripsReferer(uri.Host))
                {
                    _logger.LogInformation("JellyTV: host {Host} rejects Referer on media — stripped (was {Status})", uri.Host, firstStatus);
                    SaveHosts();
                }

                return Streamed(resp);
            }

            if (resp != null && (int)resp.StatusCode == 429)
            {
                _cooldown[uri.Host] = DateTimeOffset.UtcNow.AddSeconds(15);
            }

            resp?.Dispose();
        }

        // Fallback 2: resident-browser relay for genuinely fingerprinted hosts.
        var relayed = await FetchViaBrowserAsync(uri, headers, ct).ConfigureAwait(false);
        if (relayed != null && relayed.Status is >= 200 and < 400)
        {
            if (_hosts.LearnNeedsBrowser(uri.Host))
            {
                _logger.LogInformation("JellyTV: host {Host} requires browser relay (HttpClient got {Status})", uri.Host, firstStatus);
                SaveHosts();
            }

            return relayed;
        }

        // Prefer the real upstream status over a relay transport failure —
        // a status-0 relay result must not mask a meaningful HTTP code.
        if (relayed != null && relayed.Status != 0 && firstStatus == 0)
        {
            return relayed;
        }

        return new FetchOutcome { Status = firstStatus == 0 ? 502 : firstStatus };
    }

    /// <summary>Always-buffered fetch used by prefetch/cache.</summary>
    public async Task<FetchOutcome> FetchBufferedAsync(Uri uri, Dictionary<string, string> headers, CancellationToken ct)
    {
        var outcome = await FetchAsync(uri, headers, ct).ConfigureAwait(false);
        if (outcome.Body != null || outcome.Response == null)
        {
            return outcome;
        }

        using (outcome)
        {
            if (outcome.Status is < 200 or >= 300)
            {
                return new FetchOutcome { Status = outcome.Status };
            }

            var body = await outcome.Response.Content.ReadAsByteArrayAsync(ct).ConfigureAwait(false);
            return new FetchOutcome { Status = outcome.Status, ContentType = outcome.ContentType, Body = body };
        }
    }

    private FetchOutcome Streamed(HttpResponseMessage resp) => new()
    {
        Status = (int)resp.StatusCode,
        ContentType = resp.Content.Headers.ContentType?.MediaType,
        Response = resp
    };

    private async Task<HttpResponseMessage?> SendDotNetAsync(Uri uri, Dictionary<string, string> headers, bool stripVolatile, CancellationToken ct)
    {
        try
        {
            var client = _httpClientFactory.CreateClient("jellytv-proxy");
            var req = new HttpRequestMessage(HttpMethod.Get, uri);
            foreach (var kv in headers)
            {
                if (stripVolatile && VolatileHeaders.Contains(kv.Key, StringComparer.OrdinalIgnoreCase))
                {
                    continue;
                }

                req.Headers.TryAddWithoutValidation(kv.Key, kv.Value);
            }

            if (!req.Headers.Contains("User-Agent"))
            {
                req.Headers.TryAddWithoutValidation("User-Agent",
                    Plugin.Instance?.Configuration.UserAgent ?? "JellyTV");
            }

            return await client.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false);
        }
        catch (Exception ex)
        {
            _logger.LogDebug(ex, "JellyTV: upstream fetch failed for {Url}", uri.Host);
            return null;
        }
    }

    private async Task<FetchOutcome?> FetchViaBrowserAsync(Uri uri, Dictionary<string, string> headers, CancellationToken ct)
    {
        string? origin = null;
        if (headers.TryGetValue("Referer", out var referer)
            && Uri.TryCreate(referer, UriKind.Absolute, out var refUri))
        {
            origin = refUri.GetLeftPart(UriPartial.Authority);
        }

        origin ??= uri.GetLeftPart(UriPartial.Authority);

        var r = await _browser.FetchAsync(uri.ToString(), origin, ct).ConfigureAwait(false);
        if (r == null)
        {
            return null;
        }

        return new FetchOutcome { Status = r.Status, ContentType = r.ContentType, Body = r.Body };
    }
}
