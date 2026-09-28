using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Tally.Live;
using MediaBrowser.Controller.Library;
using MediaBrowser.Controller.LiveTv;
using MediaBrowser.Model.Dto;
using MediaBrowser.Model.LiveTv;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Tally.Services;

/// <summary>
/// Tells Jellyfin's Live TV the real bitrate of Tally's channels, so a client whose limit is above it gets the stream
/// remuxed (video copied) instead of transcoded.
/// <para>
/// Tally's channels stay on Jellyfin's own M3U tuner (<see cref="LiveTvRegistrationService"/>): same tuner, same channel
/// ids, guide, favorites and DVR. Jellyfin asks each tuner host in turn for a channel's stream, and a plugin's hosts
/// come before Jellyfin's own, so this host sees the request first. For a Tally channel it asks the M3U host for the
/// stream, as Jellyfin would have, and fills in the stream's media info from <see cref="IStreamFactsSource"/>
/// (Jellyfin's own ffprobe, plus the measured rate) before Jellyfin probes it and guesses 20 Mbps. Any other channel
/// it passes on untouched (FileNotFoundException / no sources is how a host says "not mine"). It lists no channels and
/// cannot be added as a tuner. Without its facts (probe failed, or not known yet when a stream opens) the stream is
/// exactly Jellyfin's, and if Jellyfin ever asked its own hosts first, this host would simply never be reached.
/// </para>
/// </summary>
public sealed class TallyTunerHost : ITunerHost, IConfigurableTunerHost
{
    public const string HostType = "tally";

    private readonly Func<ITunerHost?> _m3u;
    private readonly Func<IStreamFactsSource?> _facts;
    private readonly ILogger _logger;

    /// <param name="m3u">Jellyfin's M3U tuner host (resolved late: it is built alongside this one).</param>
    /// <param name="facts">Where a channel's stream facts come from (resolved late, for the same reason).</param>
    /// <param name="logger">Logger.</param>
    public TallyTunerHost(Func<ITunerHost?> m3u, Func<IStreamFactsSource?> facts, ILogger logger)
    {
        _m3u = m3u;
        _facts = facts;
        _logger = logger;
    }

    public string Name => "Tally (set up by Tally)";

    public string Type => HostType;

    public bool IsSupported => true;

    public Task<List<ChannelInfo>> GetChannels(bool enableCache, CancellationToken cancellationToken)
        => Task.FromResult(new List<ChannelInfo>());

    public Task<List<TunerHostInfo>> DiscoverDevices(int discoveryDurationMs, CancellationToken cancellationToken)
        => Task.FromResult(new List<TunerHostInfo>());

    /// <summary>Refuses a tuner of this type: Tally registers its channels on an M3U tuner by itself.</summary>
    public Task Validate(TunerHostInfo info)
        => throw new ArgumentException("Tally adds its channels to Live TV by itself. To add channels, add a source in Tally's settings.");

    public async Task<List<MediaSourceInfo>> GetChannelStreamMediaSources(string channelId, CancellationToken cancellationToken)
    {
        var sources = await TallySourcesAsync(channelId, cancellationToken).ConfigureAwait(false);
        if (sources == null)
        {
            return new List<MediaSourceInfo>(); // not ours: the next host answers
        }

        var facts = _facts();
        if (facts != null)
        {
            foreach (var source in sources)
            {
                try
                {
                    // PlaybackInfo lands here before it opens the stream, outside Jellyfin's live stream lock: the one
                    // place to wait for a probe
                    var f = await facts.GetAsync(source, cancellationToken).ConfigureAwait(false);
                    if (f != null)
                    {
                        LiveBitrate.Apply(source, f.Probe, f.Bitrate, opened: false);
                    }
                }
                catch (Exception ex) when (ex is not OperationCanceledException)
                {
                    _logger.LogWarning(ex, "Tally: no stream facts for Live TV channel {Channel}", channelId);
                }
            }
        }

        return sources;
    }

    public async Task<ILiveStream> GetChannelStream(string channelId, string streamId, IList<ILiveStream> currentLiveStreams, CancellationToken cancellationToken)
    {
        var m3u = _m3u();
        if (m3u == null || await TallySourcesAsync(channelId, cancellationToken).ConfigureAwait(false) == null)
        {
            throw new FileNotFoundException(); // not ours: the next host opens it
        }

        // Jellyfin's M3U host opens it (tuner limit, headers and all); only its media info is filled in
        var stream = await m3u.GetChannelStream(channelId, streamId, currentLiveStreams, cancellationToken).ConfigureAwait(false);
        try
        {
            var source = stream.MediaSource;
            if (source != null && _facts()?.Peek(source.Path) is { } f)
            {
                LiveBitrate.Apply(source, f.Probe, f.Bitrate, opened: true);
            }
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Tally: could not fill in the media info of Live TV channel {Channel}", channelId);
        }

        return stream;
    }

    /// <summary>The M3U host's sources for the channel when it is a Tally channel, else null.</summary>
    private async Task<List<MediaSourceInfo>?> TallySourcesAsync(string channelId, CancellationToken cancellationToken)
    {
        var m3u = _m3u();
        if (m3u == null || string.IsNullOrEmpty(channelId))
        {
            return null;
        }

        List<MediaSourceInfo> sources;
        try
        {
            sources = await m3u.GetChannelStreamMediaSources(channelId, cancellationToken).ConfigureAwait(false);
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            return null;
        }

        return sources.Count > 0 && sources.All(s => LiveStreamFacts.ChannelIdOf(s.Path) != null) ? sources : null;
    }
}
