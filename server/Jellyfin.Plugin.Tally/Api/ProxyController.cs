using System;
using System.Collections.Generic;
using System.IO;
using System.Net.Http;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Tally.Services;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Tally.Api;

/// <summary>
/// Signed HLS proxy. Every URL served here carries an HMAC signature minted by the server,
/// so this cannot be abused as an open relay — but it works from any client without auth,
/// which is what makes multiview + native clients possible.
/// </summary>
[ApiController]
[Route("JellyTV/Proxy")]
public class ProxyController : ControllerBase
{
    private const int PlaylistMaxBytes = 8 * 1024 * 1024;
    private const string LivePrefix = "live:";
    private static readonly HashSet<string> ForwardHeaders = new(StringComparer.OrdinalIgnoreCase)
    {
        "Referer", "Origin", "User-Agent", "Cookie", "Accept", "Accept-Language"
    };

    private readonly UpstreamFetcher _fetcher;
    private readonly StreamSigner _signer;
    private readonly ProxyCache _cache;
    private readonly Sources.SourceManager _sourceManager;
    private readonly Live.LiveLadderService _ladder;
    private readonly ILogger<ProxyController> _logger;

    public ProxyController(UpstreamFetcher fetcher, StreamSigner signer, ProxyCache cache, Sources.SourceManager sourceManager, Live.LiveLadderService ladder, ILogger<ProxyController> logger)
    {
        _sourceManager = sourceManager;
        _ladder = ladder;
        _fetcher = fetcher;
        _signer = signer;
        _cache = cache;
        _logger = logger;
    }

    /// <summary>Builds a signed proxy URL for an upstream resource. PathBase-aware.</summary>
    public static string BuildProxyUrl(Microsoft.AspNetCore.Http.HttpRequest request, StreamSigner signer, string upstream, Dictionary<string, string> headers)
    {
        var h = StreamSigner.EncodeHeaders(headers);
        var s = signer.Sign(upstream, h);
        var pb = request.PathBase.Value ?? string.Empty;
        return $"{pb}/JellyTV/Proxy/{ProxyFileName(upstream)}?u={Uri.EscapeDataString(upstream)}&h={h}&s={s}";
    }

    // ffmpeg's HLS demuxer (allowed_segment_extensions, "extension_picky") as shipped with Jellyfin 12.1.
    private static readonly HashSet<string> SegmentExtensions = new(StringComparer.Ordinal)
    {
        "3gp", "3gpp", "aac", "avi", "ac3", "eac3", "flac", "mkv", "m3u8", "m4a", "m4s", "m4v", "mpg", "mov", "mp2", "mp3",
        "mp4", "mpeg", "mpegts", "ogg", "ogv", "oga", "ts", "vob", "vtt", "wav", "webvtt", "cmfv", "cmfa"
    };

    /// <summary>
    /// The file name in a proxy address (<c>/JellyTV/Proxy/s.ts?u=…</c>): the upstream's own extension when ffmpeg knows
    /// it, otherwise <c>ts</c>. The ffmpeg in Jellyfin 12.1 refuses an HLS segment whose address does not end in a media
    /// extension ("not in allowed_segment_extensions"), which broke Jellyfin's Live TV of proxied channels there; the
    /// name is only for ffmpeg, the signed query still says what is fetched.
    /// </summary>
    public static string ProxyFileName(string upstream)
    {
        var ext = Uri.TryCreate(upstream, UriKind.Absolute, out var uri)
            ? Path.GetExtension(uri.AbsolutePath).TrimStart('.').ToLowerInvariant()
            : string.Empty;
        return "s." + (SegmentExtensions.Contains(ext) ? ext : "ts");
    }

    /// <summary>
    /// The stable address of a channel: <c>/JellyTV/Live/{id}.m3u8?s=…</c>. Unlike a signed upstream URL it
    /// never changes when a source rescan finds the stream under a new tokenised URL — which matters
    /// because Jellyfin derives a Live TV channel's identity from this URL, and because a player that
    /// reconnects after a rescan gets the fresh stream instead of a dead one.
    /// </summary>
    public static string BuildLiveUrl(Microsoft.AspNetCore.Http.HttpRequest request, StreamSigner signer, string channelId)
    {
        var pb = request.PathBase.Value ?? string.Empty;
        return $"{pb}/JellyTV/Live/{channelId}.m3u8?s={signer.Sign(LivePrefix + channelId, string.Empty)}";
    }

    [HttpGet("/JellyTV/Live/{id}.m3u8")]
    public async Task<IActionResult> Live(string id, [FromQuery] string? s, CancellationToken cancellationToken)
    {
        if (!_signer.Validate(LivePrefix + id, string.Empty, s))
        {
            return StatusCode(403);
        }

        var channel = _sourceManager.GetChannel(id);
        if (channel == null || !Uri.TryCreate(channel.StreamUrl, UriKind.Absolute, out var uri)
            || (uri.Scheme != Uri.UriSchemeHttp && uri.Scheme != Uri.UriSchemeHttps))
        {
            return NotFound();
        }

        if (Jellyfin.Plugin.Tally.Live.LiveLadderService.Enabled)
        {
            // Several streams or renditions: the ladder serves one continuous playlist of its own.
            var pb = Request.PathBase.Value ?? string.Empty;
            var text = await _ladder.Session(channel)
                .GetPlaylistAsync(seq => $"{pb}/JellyTV/Live/{id}/{seq}.ts?s={s}", cancellationToken).ConfigureAwait(false);
            if (text != null)
            {
                Response.Headers.CacheControl = "no-store";
                return Content(text, "application/vnd.apple.mpegurl");
            }
        }

        return await ServeAsync(channel.StreamUrl, uri, new Dictionary<string, string>(channel.Headers, StringComparer.OrdinalIgnoreCase)).ConfigureAwait(false);
    }

    /// <summary>A segment of a ladder playlist, from the session's memory.</summary>
    [HttpGet("/JellyTV/Live/{id}/{seq:long}.ts")]
    public async Task<IActionResult> LiveSegment(string id, long seq, [FromQuery] string? s, CancellationToken cancellationToken)
    {
        if (!_signer.Validate(LivePrefix + id, string.Empty, s))
        {
            return StatusCode(403);
        }

        Response.Headers.CacheControl = "no-store";
        var channel = _sourceManager.GetChannel(id);
        var session = channel == null ? null : _ladder.FindSession(channel.Id);
        if (session == null)
        {
            return NotFound();
        }

        var bytes = await session.GetSegmentAsync(seq, cancellationToken).ConfigureAwait(false);
        return bytes == null ? NotFound() : File(bytes, "video/mp2t");
    }

    /// <summary>The same as <see cref="Get"/>, under a file name ffmpeg accepts (<see cref="ProxyFileName"/>).</summary>
    [HttpGet("{file}")]
    public Task<IActionResult> GetFile(string file, [FromQuery] string u, [FromQuery] string? h, [FromQuery] string? s, CancellationToken cancellationToken)
        => Get(u, h, s, cancellationToken);

    /// <summary>A signed upstream resource; addresses without a file name are still served (made before 2.1).</summary>
    [HttpGet]
    public Task<IActionResult> Get([FromQuery] string u, [FromQuery] string? h, [FromQuery] string? s, CancellationToken cancellationToken)
    {
        h ??= string.Empty;
        if (!_signer.Validate(u, h, s))
        {
            return Task.FromResult<IActionResult>(StatusCode(403));
        }

        if (!Uri.TryCreate(u, UriKind.Absolute, out var uri)
            || (uri.Scheme != Uri.UriSchemeHttp && uri.Scheme != Uri.UriSchemeHttps))
        {
            return Task.FromResult<IActionResult>(BadRequest());
        }

        return ServeAsync(u, uri, StreamSigner.DecodeHeaders(h));
    }

    private async Task<IActionResult> ServeAsync(string u, Uri uri, Dictionary<string, string> headers)
    {
        var ct = HttpContext.RequestAborted;

        // Media segments go through the single-flight cache: one upstream fetch
        // per URL, shared by prefetch + players + retries + multiview tiles.
        // The wrappers rate-limit repeat hits (429) — dedupe is load-bearing.
        if (UpstreamFetcher.LooksLikeMedia(uri))
        {
            var entry = await _cache.GetOrFetchAsync(u, headers, ct).ConfigureAwait(false);
            Response.Headers.CacheControl = "no-store";
            if (entry == null)
            {
                return StatusCode(502);
            }

            if (!entry.Ok)
            {
                return StatusCode(entry.StatusCode);
            }

            // A "segment" URL that served a playlist still needs rewriting.
            if (HlsPlaylistRewriter.LooksLikePlaylist(u, entry.ContentType, entry.Data.AsSpan(0, Math.Min(4096, entry.Data.Length))))
            {
                var text = Encoding.UTF8.GetString(entry.Data);
                var rewritten = HlsPlaylistRewriter.Rewrite(text, uri, resolved =>
                    BuildProxyUrl(Request, _signer, resolved.ToString(), headers));
                PrefetchAhead(text, uri, headers);
                return Content(rewritten, "application/vnd.apple.mpegurl");
            }

            return File(entry.Data, entry.ContentType ?? "application/octet-stream");
        }

        // Playlists: never cached (they roll) — direct streaming fetch.
        using var outcome = await _fetcher.FetchAsync(uri, headers, ct).ConfigureAwait(false);

        if (outcome.Status is < 200 or >= 300)
        {
            Response.Headers.CacheControl = "no-store";
            return StatusCode(outcome.Status == 0 ? 502 : outcome.Status);
        }

        // Browser-relay path: body already buffered.
        if (outcome.Body != null)
        {
            if (HlsPlaylistRewriter.LooksLikePlaylist(u, outcome.ContentType, outcome.Body.AsSpan(0, Math.Min(4096, outcome.Body.Length))))
            {
                var text = Encoding.UTF8.GetString(outcome.Body);
                var rewritten = HlsPlaylistRewriter.Rewrite(text, uri, resolved =>
                    BuildProxyUrl(Request, _signer, resolved.ToString(), headers));
                PrefetchAhead(text, uri, headers);
                Response.Headers.CacheControl = "no-store";
                return Content(rewritten, "application/vnd.apple.mpegurl");
            }

            Response.Headers.CacheControl = "no-store";
            return File(outcome.Body, outcome.ContentType ?? "application/octet-stream");
        }

        using (outcome.Response)
        {
            var response = outcome.Response!;
            // Resolve relative URIs against the post-redirect URL — wrapper
            // endpoints 302 playlists to another host and relative segment
            // paths must anchor there, not at the request URL.
            var baseUri = response.RequestMessage?.RequestUri ?? uri;
            var mediaType = outcome.ContentType;
            var stream = await response.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
            await using (stream.ConfigureAwait(false))
            {
                var prefix = new byte[4096];
                var prefixLen = await FillAsync(stream, prefix, ct).ConfigureAwait(false);

                if (HlsPlaylistRewriter.LooksLikePlaylist(u, mediaType, prefix.AsSpan(0, prefixLen)))
                {
                    var text = await ReadAllTextAsync(stream, prefix, prefixLen, ct).ConfigureAwait(false);
                    var rewritten = HlsPlaylistRewriter.Rewrite(text, baseUri, resolved =>
                        BuildProxyUrl(Request, _signer, resolved.ToString(), headers));

                    // Prefetch the live-edge segments so the player's next
                    // request is a memory hit.
                    PrefetchAhead(text, baseUri, headers);

                    Response.Headers.CacheControl = "no-store";
                    return Content(rewritten, "application/vnd.apple.mpegurl");
                }

                Response.StatusCode = 200;
                Response.ContentType = mediaType ?? "application/octet-stream";
                // No Content-Length forward: AutomaticDecompression rewrites the
                // body, so the upstream length is often wrong — sending it would
                // truncate/hang the client.
                Response.Headers.CacheControl = "no-store";
                await Response.Body.WriteAsync(prefix.AsMemory(0, prefixLen), ct).ConfigureAwait(false);
                await stream.CopyToAsync(Response.Body, ct).ConfigureAwait(false);
                return new EmptyResult();
            }
        }
    }

    /// <summary>Prefetch the last URI line of a MEDIA playlist — the live-edge
    /// segment the player reaches ~15-20s later. Fetching more per poll would
    /// multiply upstream hits on rate-limited wrappers without benefit (the
    /// player's own requests already share this fetch via the cache).
    /// Master playlists are skipped (variant playlists must never be cached —
    /// live playlists roll).</summary>
    private void PrefetchAhead(string playlistText, Uri baseUri, Dictionary<string, string> headers)
    {
        if (!playlistText.Contains("#EXTINF", StringComparison.Ordinal))
        {
            return;
        }

        try
        {
            var segs = new List<string>(2);
            var reader = new StringReader(playlistText);
            string? line;
            while ((line = reader.ReadLine()) != null)
            {
                line = line.Trim();
                if (line.Length == 0 || line.StartsWith('#'))
                {
                    continue;
                }

                segs.Add(line);
                if (segs.Count > 1)
                {
                    segs.RemoveAt(0);
                }
            }

            foreach (var s in segs)
            {
                if (Uri.TryCreate(baseUri, s, out var seg) && (seg.Scheme == "http" || seg.Scheme == "https"))
                {
                    _cache.Prefetch(seg.ToString(), headers);
                }
            }
        }
        catch (Exception)
        {
            // best-effort
        }
    }

    private static async Task<int> FillAsync(Stream stream, byte[] buffer, CancellationToken ct)
    {
        var total = 0;
        while (total < buffer.Length)
        {
            var read = await stream.ReadAsync(buffer.AsMemory(total, buffer.Length - total), ct).ConfigureAwait(false);
            if (read == 0)
            {
                break;
            }

            total += read;
        }

        return total;
    }

    private static async Task<string> ReadAllTextAsync(Stream stream, byte[] prefix, int prefixLen, CancellationToken ct)
    {
        using var ms = new MemoryStream();
        await ms.WriteAsync(prefix.AsMemory(0, prefixLen), ct).ConfigureAwait(false);
        var buffer = new byte[81920];
        int read;
        while (ms.Length < PlaylistMaxBytes
               && (read = await stream.ReadAsync(buffer, ct).ConfigureAwait(false)) > 0)
        {
            await ms.WriteAsync(buffer.AsMemory(0, read), ct).ConfigureAwait(false);
        }

        return Encoding.UTF8.GetString(ms.ToArray());
    }
}
