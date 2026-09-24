using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Linq;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Tally.Services;

/// <summary>
/// Short-lived in-memory cache + single-flight coordinator for proxied media.
/// Every upstream URL is fetched at most once per TTL window — prefetch,
/// player requests, retries, and multiview tiles all share one fetch. That
/// matters: the redirect-wrapper upstreams rate-limit aggressively (429 after
/// a couple of hits on the same URL), so duplicate fetches actively break
/// playback. Failed fetches are negative-cached briefly so client retry
/// storms don't hammer a throttling host.
/// </summary>
public class ProxyCache
{
    private const int MaxEntryBytes = 16 * 1024 * 1024;
    private const long MaxTotalBytes = 128 * 1024 * 1024;
    private static readonly TimeSpan Ttl = TimeSpan.FromSeconds(120);
    private static readonly TimeSpan NegativeTtl = TimeSpan.FromSeconds(5);

    // Ordinal keys: upstream URLs are case-sensitive, and distinct URLs must
    // never share a cached body.
    private readonly ConcurrentDictionary<string, Entry> _cache = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<string, Lazy<Task<Entry?>>> _inflight = new(StringComparer.Ordinal);
    private readonly UpstreamFetcher _fetcher;
    private readonly ILogger<ProxyCache> _logger;
    private long _totalBytes;

    public ProxyCache(UpstreamFetcher fetcher, ILogger<ProxyCache> logger)
    {
        _fetcher = fetcher;
        _logger = logger;
    }

    public sealed class Entry
    {
        public byte[] Data { get; init; } = Array.Empty<byte>();

        public string? ContentType { get; init; }

        public int StatusCode { get; init; } = 200;

        public bool Ok => StatusCode is >= 200 and < 300;

        public DateTimeOffset At { get; init; } = DateTimeOffset.UtcNow;

        public TimeSpan Lifetime => Ok ? Ttl : NegativeTtl;
    }

    /// <summary>Gets a cached response if present and fresh (including failures).</summary>
    public bool TryGet(string url, out Entry? entry)
    {
        entry = null;
        if (!_cache.TryGetValue(url, out var e))
        {
            return false;
        }

        if (DateTimeOffset.UtcNow - e.At > e.Lifetime)
        {
            if (_cache.TryRemove(url, out var dead))
            {
                Interlocked.Add(ref _totalBytes, -dead.Data.Length);
            }

            return false;
        }

        entry = e;
        return true;
    }

    /// <summary>
    /// Single-flight fetch through the cache: concurrent callers share one
    /// upstream request; the result (success or failure) is cached. Use for
    /// media segment URLs — playlists must NOT come through here.
    /// </summary>
    public Task<Entry?> GetOrFetchAsync(string url, Dictionary<string, string> headers, CancellationToken ct)
    {
        if (TryGet(url, out var hit))
        {
            return Task.FromResult(hit);
        }

        var lazy = _inflight.GetOrAdd(url, _ => new Lazy<Task<Entry?>>(() => DoFetchAsync(url, headers)));
        var task = lazy.Value;
        _ = task.ContinueWith(_ => _inflight.TryRemove(url, out Lazy<Task<Entry?>>? _), CancellationToken.None);
        return task;
    }

    /// <summary>Fire-and-forget prefetch — shares the single-flight path.</summary>
    public void Prefetch(string url, Dictionary<string, string> headers)
    {
        _ = GetOrFetchAsync(url, headers, CancellationToken.None);
    }

    private async Task<Entry?> DoFetchAsync(string url, Dictionary<string, string> headers)
    {
        try
        {
            if (!Uri.TryCreate(url, UriKind.Absolute, out var uri))
            {
                return null;
            }

            using var outcome = await _fetcher.FetchBufferedAsync(uri, headers, CancellationToken.None).ConfigureAwait(false);
            var body = outcome.Body;
            if (outcome.Status is < 200 or >= 300 || body == null)
            {
                var fail = new Entry { StatusCode = outcome.Status == 0 ? 502 : outcome.Status };
                Store(url, fail);
                return fail;
            }

            if (body.Length == 0 || body.Length > MaxEntryBytes
                || body.AsSpan(0, Math.Min(7, body.Length)).StartsWith("#EXTM3U"u8)
                || outcome.ContentType?.Contains("html", StringComparison.OrdinalIgnoreCase) == true
                || outcome.ContentType?.Contains("json", StringComparison.OrdinalIgnoreCase) == true)
            {
                // Never cache playlists (they roll) or 200-status junk bodies —
                // HTML interstitials and error JSON poisoned as "segments"
                // corrupt the decode pipeline. Not negative-cached either:
                // a junk body is not worth replaying.
                return null;
            }

            var entry = new Entry { Data = body, ContentType = outcome.ContentType, StatusCode = 200 };
            Store(url, entry);
            return entry;
        }
        catch (Exception)
        {
            // best-effort
            return null;
        }
    }

    private void Store(string url, Entry entry)
    {
        if (_cache.TryGetValue(url, out var old))
        {
            Interlocked.Add(ref _totalBytes, -old.Data.Length);
        }

        _cache[url] = entry;
        Interlocked.Add(ref _totalBytes, entry.Data.Length);
        if (_totalBytes <= MaxTotalBytes)
        {
            return;
        }

        // Evict oldest quarter until under the cap.
        var victims = _cache.OrderBy(kv => kv.Value.At).Take(_cache.Count / 4 + 1).Select(kv => kv.Key);
        foreach (var key in victims)
        {
            if (_cache.TryRemove(key, out var e))
            {
                Interlocked.Add(ref _totalBytes, -e.Data.Length);
            }
        }

        _logger.LogDebug("JellyTV: proxy cache evicted, now {Bytes} bytes", _totalBytes);
    }
}
