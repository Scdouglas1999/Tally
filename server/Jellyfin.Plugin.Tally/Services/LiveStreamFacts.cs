using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Linq;
using System.Net.Http;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Tally.Live;
using Jellyfin.Plugin.Tally.Sources;
using MediaBrowser.Controller.MediaEncoding;
using MediaBrowser.Model.Dlna;
using MediaBrowser.Model.Dto;
using MediaBrowser.Model.MediaInfo;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Tally.Services;

/// <summary>What Tally knows about a channel's stream: Jellyfin's own probe of it, and the rate it really runs at.</summary>
public sealed record StreamFacts(MediaInfo Probe, long? Bitrate, DateTimeOffset At);

/// <summary>Where <see cref="TallyTunerHost"/> gets a channel's facts (a seam for tests).</summary>
public interface IStreamFactsSource
{
    /// <summary>The facts of a channel's stream: from memory when known (refreshed in the background once they are a
    /// few minutes old), else probed now. Null when the probe fails: Jellyfin then probes as it always did.</summary>
    Task<StreamFacts?> GetAsync(MediaSourceInfo source, CancellationToken cancellationToken);

    /// <summary>The facts in memory, without probing (a stream is being opened: no time for a probe).</summary>
    StreamFacts? Peek(string path);
}

/// <summary>
/// Probes Tally's channels the way Jellyfin's Live TV does (ffprobe through Jellyfin's media encoder, same request) and
/// adds what ffprobe cannot say about HLS: the rate. That is the peak of the newest segments of the playlist Jellyfin
/// plays (or what a master declares for the rendition ffmpeg takes), raised to the fastest rung the ladder knows for the
/// channel, since the ladder may step up while someone watches. Kept in memory per channel.
/// </summary>
public sealed class LiveStreamFacts : IStreamFactsSource
{
    /// <summary>After this, facts are still used but refreshed in the background.</summary>
    public static readonly TimeSpan FreshFor = TimeSpan.FromMinutes(3);

    /// <summary>Facts older than this are dropped when a new probe fails.</summary>
    private static readonly TimeSpan KeepWithoutProbe = TimeSpan.FromMinutes(30);

    private static readonly TimeSpan RetryAfterFailure = TimeSpan.FromSeconds(30);
    private static readonly TimeSpan ProbeTimeout = TimeSpan.FromSeconds(25);
    private static readonly TimeSpan FetchTimeout = TimeSpan.FromSeconds(10);

    private readonly IMediaEncoder _encoder;
    private readonly IHttpClientFactory _http;
    private readonly SourceManager _sources;
    private readonly LiveLadderService _ladder;
    private readonly ILogger<LiveStreamFacts> _logger;
    private readonly ConcurrentDictionary<string, StreamFacts> _facts = new(StringComparer.OrdinalIgnoreCase);
    private readonly ConcurrentDictionary<string, DateTimeOffset> _failed = new(StringComparer.OrdinalIgnoreCase);
    private readonly Dictionary<string, Task<StreamFacts?>> _inflight = new(StringComparer.OrdinalIgnoreCase);

    public LiveStreamFacts(IMediaEncoder encoder, IHttpClientFactory http, SourceManager sources, LiveLadderService ladder, ILogger<LiveStreamFacts> logger)
    {
        _encoder = encoder;
        _http = http;
        _sources = sources;
        _ladder = ladder;
        _logger = logger;
    }

    /// <summary>The Tally channel id of a Live TV channel's address (<c>…/JellyTV/Live/{id}.m3u8?s=…</c>), or null when
    /// the address is not a Tally channel's.</summary>
    public static string? ChannelIdOf(string? path)
    {
        if (string.IsNullOrEmpty(path) || !Uri.TryCreate(path, UriKind.Absolute, out var uri))
        {
            return null;
        }

        const string Prefix = "/JellyTV/Live/";
        var p = uri.AbsolutePath;
        var at = p.IndexOf(Prefix, StringComparison.OrdinalIgnoreCase);
        if (at < 0 || !p.EndsWith(".m3u8", StringComparison.OrdinalIgnoreCase))
        {
            return null;
        }

        var id = p[(at + Prefix.Length)..^".m3u8".Length];
        return id.Length == 0 || id.Contains('/', StringComparison.Ordinal) ? null : Uri.UnescapeDataString(id);
    }

    public StreamFacts? Peek(string path)
        => ChannelIdOf(path) is { } id && _facts.TryGetValue(id, out var f) ? f : null;

    public async Task<StreamFacts?> GetAsync(MediaSourceInfo source, CancellationToken cancellationToken)
    {
        var id = ChannelIdOf(source.Path);
        if (id == null)
        {
            return null;
        }

        var now = DateTimeOffset.UtcNow;
        if (_facts.TryGetValue(id, out var known))
        {
            if (now - known.At > FreshFor && !(_failed.TryGetValue(id, out var lastFailure) && now - lastFailure < RetryAfterFailure))
            {
                _ = Refresh(id, source);
            }

            return known;
        }

        if (_failed.TryGetValue(id, out var failedAt) && now - failedAt < RetryAfterFailure)
        {
            return null;
        }

        try
        {
            return await Refresh(id, source).WaitAsync(cancellationToken).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            return null;
        }
    }

    /// <summary>One probe per channel at a time; callers share it.</summary>
    private Task<StreamFacts?> Refresh(string id, MediaSourceInfo source)
    {
        lock (_inflight)
        {
            if (_inflight.TryGetValue(id, out var running))
            {
                return running;
            }

            var clone = JsonSerializer.Deserialize<MediaSourceInfo>(JsonSerializer.SerializeToUtf8Bytes(source))!;
            var task = RefreshAsync(id, clone);
            _inflight[id] = task;
            return task;
        }
    }

    private async Task<StreamFacts?> RefreshAsync(string id, MediaSourceInfo source)
    {
        await Task.Yield(); // off the caller's thread, and never done before Refresh has listed it
        try
        {
            var facts = await ProbeAsync(id, source).ConfigureAwait(false);
            if (facts != null)
            {
                _facts[id] = facts;
                _failed.TryRemove(id, out _);
                return facts;
            }

            _failed[id] = DateTimeOffset.UtcNow;
            if (_facts.TryGetValue(id, out var old) && DateTimeOffset.UtcNow - old.At > KeepWithoutProbe)
            {
                // it has not probed for a long while: back to Jellyfin's own probe until it does again
                _facts.TryRemove(id, out _);
                return null;
            }

            return old;
        }
        finally
        {
            lock (_inflight)
            {
                _inflight.Remove(id);
            }
        }
    }

    private async Task<StreamFacts?> ProbeAsync(string id, MediaSourceInfo source)
    {
        using var cts = new CancellationTokenSource(ProbeTimeout);
        var started = DateTimeOffset.UtcNow;
        MediaInfo probe;
        try
        {
            // exactly what Jellyfin's live stream probe asks for (LiveStreamHelper.AddMediaInfoWithProbe)
            source.AnalyzeDurationMs = 3000;
            probe = await _encoder.GetMediaInfo(
                new MediaInfoRequest { MediaSource = source, MediaType = DlnaProfileType.Video, ExtractChapters = false },
                cts.Token).ConfigureAwait(false);
        }
        catch (Exception ex)
        {
            _logger.LogWarning("Tally: could not probe channel {Id} for Live TV ({Error}); Jellyfin probes it itself", id, ex.Message);
            return null;
        }

        long? measured = null;
        try
        {
            measured = await MeasureAsync(source, cts.Token).ConfigureAwait(false);
        }
        catch (Exception ex)
        {
            _logger.LogDebug(ex, "Tally: could not measure channel {Id}", id);
        }

        var ceiling = Ceiling(id);
        var bitrate = LiveBitrate.Max(measured, ceiling);
        _logger.LogInformation(
            "Tally: Live TV channel {Id} runs at {Mbps} Mbps (measured {Measured}, ladder {Ceiling}); probed in {Seconds:0.0} s",
            id, Mbps(bitrate), Mbps(measured), Mbps(ceiling), (DateTimeOffset.UtcNow - started).TotalSeconds);
        return new StreamFacts(probe, bitrate, DateTimeOffset.UtcNow);
    }

    private static string Mbps(long? bps) => bps is { } b ? (b / 1e6).ToString("0.0", System.Globalization.CultureInfo.InvariantCulture) : "?";

    /// <summary>What the playlist Jellyfin plays runs at: a master's declared rate for the rendition ffmpeg takes, or
    /// the peak of a media playlist's newest segments (from Tally's memory or its proxy cache).</summary>
    private async Task<long?> MeasureAsync(MediaSourceInfo source, CancellationToken ct)
    {
        var client = _http.CreateClient("jellytv-proxy");
        var top = new Uri(source.Path);
        var text = await GetTextAsync(client, top, source, ct).ConfigureAwait(false);
        if (text == null)
        {
            return null;
        }

        if (HlsParser.IsMaster(text))
        {
            return LiveBitrate.FromMaster(HlsParser.ParseMaster(text, top));
        }

        var media = HlsParser.ParseMedia(text, top);
        var sizes = new List<(long, double)>();
        foreach (var seg in media.Segments.Skip(Math.Max(0, media.Segments.Count - LiveBitrate.MeasuredSegments)))
        {
            using var req = Request(seg.Uri, source);
            using var fetch = CancellationTokenSource.CreateLinkedTokenSource(ct);
            fetch.CancelAfter(FetchTimeout);
            using var resp = await client.SendAsync(req, HttpCompletionOption.ResponseContentRead, fetch.Token).ConfigureAwait(false);
            if (resp.IsSuccessStatusCode)
            {
                var bytes = await resp.Content.ReadAsByteArrayAsync(fetch.Token).ConfigureAwait(false);
                sizes.Add((bytes.LongLength, seg.Duration));
            }
        }

        return LiveBitrate.FromSegments(sizes);
    }

    private static async Task<string?> GetTextAsync(HttpClient client, Uri uri, MediaSourceInfo source, CancellationToken ct)
    {
        using var req = Request(uri, source);
        using var fetch = CancellationTokenSource.CreateLinkedTokenSource(ct);
        fetch.CancelAfter(FetchTimeout);
        using var resp = await client.SendAsync(req, fetch.Token).ConfigureAwait(false);
        return resp.IsSuccessStatusCode ? await resp.Content.ReadAsStringAsync(fetch.Token).ConfigureAwait(false) : null;
    }

    private static HttpRequestMessage Request(Uri uri, MediaSourceInfo source)
    {
        var req = new HttpRequestMessage(HttpMethod.Get, uri);
        if (source.RequiredHttpHeaders != null)
        {
            foreach (var h in source.RequiredHttpHeaders)
            {
                req.Headers.TryAddWithoutValidation(h.Key, h.Value);
            }
        }

        return req;
    }

    /// <summary>The fastest rung the ladder may play on this channel; for the Whip-Around channel, on any channel the
    /// ladder is playing now (it cuts between them).</summary>
    private long? Ceiling(string id)
    {
        try
        {
            if (!LiveLadderService.Enabled)
            {
                return null;
            }

            if (RedZoneService.IsRedZone(id))
            {
                return LiveBitrate.Max(_ladder.Sessions.Select(s => LiveBitrate.Ceiling(_ladder.RankedTiers(s.Channel))).ToArray());
            }

            var channel = _sources.GetChannel(id);
            return channel == null ? null : LiveBitrate.Ceiling(_ladder.RankedTiers(channel));
        }
        catch (Exception ex)
        {
            _logger.LogDebug(ex, "Tally: no ladder ceiling for {Id}", id);
            return null;
        }
    }
}
