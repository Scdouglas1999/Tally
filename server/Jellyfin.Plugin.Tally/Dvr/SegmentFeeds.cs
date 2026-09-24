using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Tally.Live;
using Jellyfin.Plugin.Tally.Models;
using Jellyfin.Plugin.Tally.Services;

namespace Jellyfin.Plugin.Tally.Dvr;

/// <summary>A segment for the recording: bytes, duration, and whether the source says the timeline breaks before it.</summary>
public sealed record FeedSegment(byte[] Bytes, double Duration, bool Discontinuity);

/// <summary>New segments of a channel, oldest first, each time it is asked.</summary>
public interface ISegmentFeed
{
    string ChannelId { get; }

    /// <summary>Null when this feed cannot serve the channel (the ladder decided on pass-through): use the other one.</summary>
    Task<List<FeedSegment>?> PollAsync(CancellationToken ct);
}

/// <summary>This recording's stream format cannot be recorded (fMP4 segments, unsupported encryption).</summary>
public sealed class UnrecordableStreamException : Exception
{
    public UnrecordableStreamException(string message)
        : base(message)
    {
    }
}

/// <summary>
/// Reads the live ladder's continuous playlist for a channel: the same <see cref="LiveSession"/> viewers of
/// <c>/JellyTV/Live/{id}.m3u8</c> get, so a recording and any number of viewers share one upstream fetch, and a
/// source switch mid-game reaches the recording already spliced. Polling keeps the session alive as a viewer would.
/// </summary>
public sealed class LadderFeed : ISegmentFeed
{
    private readonly LiveLadderService _ladder;
    private SourceChannel _channel;
    private long _next = -1;

    public LadderFeed(LiveLadderService ladder, SourceChannel channel)
    {
        _ladder = ladder;
        _channel = channel;
    }

    public string ChannelId => _channel.Id;

    public void Update(SourceChannel channel) => _channel = channel;

    public async Task<List<FeedSegment>?> PollAsync(CancellationToken ct)
    {
        var session = _ladder.Session(_channel);
        var text = await session.GetPlaylistAsync(_ => string.Empty, ct, player: false).ConfigureAwait(false);
        if (text == null)
        {
            return null; // one stream, one rendition: the plain proxy path
        }

        var window = session.Window;
        var end = window.NextSequence;
        var first = end - window.Count;
        var result = new List<FeedSegment>();
        var gap = false;
        if (_next < 0 || _next > end)
        {
            gap = _next >= 0; // the session was restarted under us
            _next = first;
        }
        else if (_next < first)
        {
            gap = true; // fell behind the window
            _next = first;
        }

        for (; _next < end; _next++)
        {
            var seg = window.Get(_next);
            if (seg == null)
            {
                gap = true;
                continue;
            }

            byte[]? bytes;
            try
            {
                bytes = await seg.Body.WaitAsync(TimeSpan.FromSeconds(30), ct).ConfigureAwait(false);
            }
            catch (TimeoutException)
            {
                break; // still downloading: next poll
            }

            if (bytes == null)
            {
                gap = true;
                continue;
            }

            result.Add(new FeedSegment(bytes, seg.Duration, seg.Discontinuity || gap));
            gap = false;
        }

        return result;
    }
}

/// <summary>
/// A channel the ladder passes through untouched (one stream, one rendition, or the ladder switched off): follows the
/// upstream media playlist itself and fetches segments through the proxy's single-flight cache, so a viewer of the
/// same channel and the recording still share each segment download.
/// </summary>
public sealed class PassthroughFeed : ISegmentFeed
{
    private readonly LiveFetch _fetch;
    private readonly ProxyCache _cache;
    private readonly SourceChannel _channel;
    private Uri? _media;
    private long _next = -1;

    public PassthroughFeed(LiveFetch fetch, ProxyCache cache, SourceChannel channel)
    {
        _fetch = fetch;
        _cache = cache;
        _channel = channel;
    }

    public string ChannelId => _channel.Id;

    private Dictionary<string, string> Headers => new(_channel.Headers, StringComparer.OrdinalIgnoreCase);

    public async Task<List<FeedSegment>?> PollAsync(CancellationToken ct)
    {
        var pl = await FetchPlaylistAsync(ct).ConfigureAwait(false);
        var result = new List<FeedSegment>();
        if (pl == null || pl.Segments.Count == 0)
        {
            return result;
        }

        if (pl.Segments[^1].MapUri != null)
        {
            throw new UnrecordableStreamException("This stream uses fMP4 segments, which the recorder cannot join yet");
        }

        var gap = false;
        if (_next < 0)
        {
            _next = Math.Max(pl.MediaSequence, pl.NextSequence - 3);
        }
        else if (_next < pl.MediaSequence || _next > pl.NextSequence + 3)
        {
            gap = true; // the upstream restarted its numbering, or we fell out of its window
            _next = Math.Max(pl.MediaSequence, pl.NextSequence - 3);
        }

        foreach (var seg in pl.Segments.Where(s => s.Sequence >= _next))
        {
            var bytes = await GetSegmentAsync(seg, ct).ConfigureAwait(false);
            _next = seg.Sequence + 1;
            if (bytes == null)
            {
                gap = true;
                continue;
            }

            result.Add(new FeedSegment(bytes, seg.Duration, seg.Discontinuity || gap));
            gap = false;
        }

        return result;
    }

    private async Task<byte[]?> GetSegmentAsync(HlsSegment seg, CancellationToken ct)
    {
        if (seg.KeyMethod != null)
        {
            // encrypted: the ladder's fetch decrypts (AES-128); the proxy cache only holds the ciphertext
            var r = await _fetch.GetSegmentAsync(seg, Headers, TimeSpan.FromSeconds(Math.Max(10, seg.Duration * 3)), ct).ConfigureAwait(false);
            if (!r.Ok && r.Error?.StartsWith("unsupported encryption", StringComparison.Ordinal) == true)
            {
                throw new UnrecordableStreamException("This stream's encryption is not supported");
            }

            return r.Ok ? r.Body : null;
        }

        var entry = await _cache.GetOrFetchAsync(seg.Uri.ToString(), Headers, ct).ConfigureAwait(false);
        if (entry == null || !entry.Ok || entry.Data.Length == 0)
        {
            return null;
        }

        var head = entry.Data.AsSpan(0, Math.Min(64, entry.Data.Length));
        if (head.IndexOf("#EXTM3U"u8) >= 0 || head.IndexOf("<html"u8) >= 0 || head.IndexOf("<!DOCTYPE"u8) >= 0)
        {
            return null; // an interstitial page, not a segment
        }

        return entry.Data;
    }

    private async Task<HlsMediaPlaylist?> FetchPlaylistAsync(CancellationToken ct)
    {
        for (var attempt = 0; attempt < 2; attempt++)
        {
            var uri = _media ?? new Uri(_channel.StreamUrl);
            var r = await _fetch.GetAsync(uri, Headers, TimeSpan.FromSeconds(10), ct).ConfigureAwait(false);
            if (!r.Ok)
            {
                _media = null; // a rendition address may have expired: start from the channel's own again
                continue;
            }

            var text = r.Text;
            if (HlsParser.IsMaster(text))
            {
                var best = HlsParser.ParseMaster(text, r.FinalUri).OrderByDescending(v => v.Bandwidth).FirstOrDefault();
                if (best == null)
                {
                    return null;
                }

                _media = best.Uri;
                continue;
            }

            if (!text.Contains("#EXTINF", StringComparison.Ordinal))
            {
                return null;
            }

            if (_media == null)
            {
                _media = r.FinalUri;
            }

            return HlsParser.ParseMedia(text, r.FinalUri);
        }

        return null;
    }
}
