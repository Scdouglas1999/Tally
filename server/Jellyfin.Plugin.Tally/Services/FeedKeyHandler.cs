using System;
using System.Net;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;

namespace Jellyfin.Plugin.Tally.Services;

/// <summary>
/// Adds the Live TV feed key (<see cref="Api.LiveTvFeedController.FeedKey"/>) as a header to Jellyfin's own requests
/// for Tally's playlist and guide over loopback. It sits on Jellyfin's default HTTP client, the one its M3U tuner reads
/// the playlist with, so the tuner's address can stay <c>…/JellyTV/livetv.m3u</c> with no key in it: Jellyfin derives
/// every M3U channel's id from the tuner address, and a changed address would give every Tally channel a new Live TV
/// item (favorites, recording rules and watch history lost). Any other request passes untouched, and the key never
/// leaves the machine.
/// </summary>
public sealed class FeedKeyHandler : DelegatingHandler
{
    /// <summary>The request header that carries the feed key.</summary>
    public const string HeaderName = "X-Tally-Feed-Key";

    private readonly Func<string?> _key;

    /// <param name="key">The feed key (read per request: the proxy secret is set when the plugin's config loads).</param>
    public FeedKeyHandler(Func<string?> key)
    {
        _key = key;
    }

    /// <summary>Whether a request is Jellyfin's own read of Tally's playlist or guide on this machine.</summary>
    public static bool IsFeedRequest(Uri? uri)
    {
        if (uri == null || !uri.IsAbsoluteUri)
        {
            return false;
        }

        var loopback = uri.IsLoopback || (IPAddress.TryParse(uri.Host.Trim('[', ']'), out var ip) && IPAddress.IsLoopback(ip));
        var path = uri.AbsolutePath;
        return loopback
               && (path.EndsWith("/JellyTV/livetv.m3u", StringComparison.OrdinalIgnoreCase)
                   || path.EndsWith("/JellyTV/epg.xml", StringComparison.OrdinalIgnoreCase));
    }

    protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
    {
        try
        {
            if (IsFeedRequest(request.RequestUri) && !request.Headers.Contains(HeaderName) && _key() is { Length: > 0 } key)
            {
                request.Headers.TryAddWithoutValidation(HeaderName, key);
            }
        }
        catch (Exception)
        {
            // never in the way of Jellyfin's own requests; without the header the feed answers 403 and the tuner
            // registration falls back to a keyed address (LiveTvRegistrationService)
        }

        return base.SendAsync(request, cancellationToken);
    }
}
