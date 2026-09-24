using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Tally.Models;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Tally.Live;

/// <summary>A switch, as logged and shown in the ladder status.</summary>
public sealed record SwitchRecord(DateTimeOffset At, string From, string To, string Reason, bool Up, bool Spliced);

/// <summary>
/// One watched channel: follows the selected rung's media playlist, downloads each new segment itself (that download
/// is the health measurement), and republishes it under the channel's own continuous playlist. A segment is listed
/// only once it is in memory, so a slow or failing upstream never reaches the player as a slow or failing request:
/// the session switches to another rung first, and the player just keeps reading. Idle after 45 s without requests.
/// </summary>
public sealed class LiveSession
{
    public static readonly TimeSpan IdleTimeout = TimeSpan.FromSeconds(45);
    private static readonly TimeSpan ReprobeEvery = TimeSpan.FromMinutes(3);
    private const int InitialSegments = 3;

    private readonly LiveLadderService _svc;
    private readonly ILogger _logger;
    private readonly SemaphoreSlim _startLock = new(1, 1);
    private readonly List<SwitchRecord> _history = new();

    private SwitchPolicy? _policy;
    private Tier? _tier;
    private Uri? _mediaUri;
    private long _nextUpstreamSeq = -1;
    private double _anchorBehind;
    private bool _anchorUrgent = true;
    private HlsMediaPlaylist? _lastPlaylist;
    private DateTimeOffset _lastPlaylistAt;
    private int _epoch;
    private SpliceMap _map = SpliceMap.Identity;
    private TsInfo? _canonical;
    private Task? _canonicalReady;
    private bool _epochPending = true;
    private bool _discontinuityPending;
    private string? _switchReason;
    private Task? _loop;
    private CancellationTokenSource? _cts;
    private DateTimeOffset _lastReprobe = DateTimeOffset.MinValue;
    private DateTimeOffset _startedAt;
    private long _lastTouchedTicks;

    public LiveSession(LiveLadderService svc, SourceChannel channel, ILogger logger)
    {
        _svc = svc;
        Channel = channel;
        _logger = logger;
        Touch();
    }

    public SourceChannel Channel { get; set; }

    public OutputWindow Window { get; } = new(size: 10);

    public bool Passthrough { get; private set; }

    public bool Running => _loop is { IsCompleted: false };

    public Tier? CurrentTier => _tier;

    public DateTimeOffset LastTouched => new(Interlocked.Read(ref _lastTouchedTicks), TimeSpan.Zero);

    public DateTimeOffset StartedAt => _startedAt;

    public IReadOnlyList<SwitchRecord> History
    {
        get
        {
            lock (_history)
            {
                return _history.ToList();
            }
        }
    }

    public double? Throughput => _policy?.Throughput;

    public void Touch() => Interlocked.Exchange(ref _lastTouchedTicks, DateTimeOffset.UtcNow.UtcTicks);

    /// <summary>The channel's playlist, or null when this channel is served by plain pass-through
    /// (one candidate, one rendition — exactly as before the ladder existed).</summary>
    public async Task<string?> GetPlaylistAsync(Func<long, string> segmentUri, CancellationToken ct)
    {
        var previousTouch = LastTouched;
        Touch();
        if (Passthrough && !Running && DateTimeOffset.UtcNow - previousTouch < IdleTimeout)
        {
            return null; // a player is following the upstream's own playlist: never swap it out under it
        }

        if (!Running)
        {
            await StartAsync(ct).ConfigureAwait(false);
        }

        if (Passthrough)
        {
            return null;
        }

        return Window.Render(segmentUri);
    }

    public async Task<byte[]?> GetSegmentAsync(long sequence, CancellationToken ct)
    {
        Touch();
        var seg = Window.Get(sequence);
        if (seg == null)
        {
            return null;
        }

        return await seg.Body.WaitAsync(TimeSpan.FromSeconds(60), ct).ConfigureAwait(false);
    }

    private async Task StartAsync(CancellationToken ct)
    {
        await _startLock.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            if (Running)
            {
                return;
            }

            var ranked = _svc.RankedTiers(Channel);
            if (Channel.Candidates.Count <= 1 && ranked.Count <= 1)
            {
                Passthrough = true;
                return;
            }

            Passthrough = false;
            var now = DateTimeOffset.UtcNow;
            var restarted = Window.NextSequence > 0;
            Window.Clear();
            _canonical = null;
            _canonicalReady = null;
            _map = SpliceMap.Identity;
            _epochPending = true;
            _discontinuityPending = restarted; // a player that was here before must reset its timeline
            _startedAt = now;

            var first = SwitchPolicy.Initial(ranked, _svc.Probe, now, TimeSpan.FromMinutes(45));
            var order = new[] { first! }.Concat(ranked.Where(t => t.Key != first!.Key)).Take(4).ToList();
            HlsMediaPlaylist? playlist = null;
            foreach (var t in order)
            {
                var (pl, uri, error) = await FetchMediaAsync(t, t.MediaUri, ct).ConfigureAwait(false);
                if (pl != null && pl.Segments.Count > 0)
                {
                    playlist = pl;
                    _tier = t;
                    _mediaUri = uri;
                    break;
                }

                _svc.NoteFailure(t.CandidateKey, error ?? "no segments");
                _logger.LogInformation("JellyTV ladder: {Channel}: {Tier} unavailable at start ({Error})", Channel.Name, SwitchPolicy.Describe(t), error ?? "no segments");
            }

            if (playlist == null || _tier == null)
            {
                Passthrough = true; // nothing answered: let the plain proxy try (and report) as it always did
                return;
            }

            _policy = new SwitchPolicy(_tier, now) { TargetDuration = playlist.TargetDuration };
            _logger.LogInformation("JellyTV ladder: {Channel}: starting on {Tier} ({Rank} of {Count} rungs, {Candidates} streams)",
                Channel.Name, SwitchPolicy.Describe(_tier), ranked.FindIndex(t => t.Key == _tier.Key) + 1, ranked.Count, Channel.Candidates.Count);

            // List the newest few right away, downloading them in order in the background — the player asks for
            // them in order too, and its first request simply waits for the first download (same as a direct proxy).
            var initial = playlist.Segments.Skip(Math.Max(0, playlist.Segments.Count - InitialSegments)).ToList();
            Task previous = Task.CompletedTask;
            var tier = _tier;
            foreach (var seg in initial)
            {
                var tcs = new TaskCompletionSource<byte[]?>(TaskCreationOptions.RunContinuationsAsynchronously);
                var published = Window.Publish(new PublishedSegment
                {
                    Duration = seg.Duration,
                    Discontinuity = _discontinuityPending,
                    Upstream = seg.Uri,
                    TierKey = tier.Key,
                    Epoch = 0,
                    Body = tcs.Task
                }, now);
                _discontinuityPending = false;
                var wait = previous;
                var isFirst = _canonicalReady == null;
                var job = Task.Run(async () =>
                {
                    await wait.ConfigureAwait(false);
                    var bytes = await DownloadAsync(seg, tier, CancellationToken.None).ConfigureAwait(false);
                    if (bytes != null)
                    {
                        var info = TsSplicer.Analyze(bytes);
                        published.OutInfo = info;
                        if (isFirst && info.IsTs)
                        {
                            _canonical = info;
                        }
                    }

                    tcs.TrySetResult(bytes);
                });
                _canonicalReady ??= job;
                previous = job;
            }

            _epochPending = false;
            _epoch = 0;
            _nextUpstreamSeq = initial[^1].Sequence + 1;
            _lastReprobe = now - ReprobeEvery + TimeSpan.FromSeconds(10); // first look at the others shortly
            _cts = new CancellationTokenSource();
            var token = _cts.Token;
            _loop = Task.Run(() => LoopAsync(token), CancellationToken.None);
        }
        finally
        {
            _startLock.Release();
        }
    }

    public void Stop() => _cts?.Cancel();

    private async Task LoopAsync(CancellationToken ct)
    {
        var playlistFailures = 0;
        try
        {
            while (!ct.IsCancellationRequested)
            {
                var now = DateTimeOffset.UtcNow;
                if (now - LastTouched > IdleTimeout)
                {
                    _logger.LogInformation("JellyTV ladder: {Channel}: idle, stopping", Channel.Name);
                    break;
                }

                var tier = _tier!;
                var switched = false;
                var (pl, uri, error) = await FetchMediaAsync(tier, _mediaUri!, ct).ConfigureAwait(false);
                if (pl == null)
                {
                    // A rendition URL with an expired token: the latest probe may know the new one.
                    var renewed = _svc.RankedTiers(Channel).FirstOrDefault(t => t.Key == tier.Key);
                    if (renewed != null && renewed.MediaUri != _mediaUri)
                    {
                        _mediaUri = renewed.MediaUri;
                    }
                    else if (++playlistFailures >= 2)
                    {
                        _policy!.OnSegmentFailed(now, "playlist " + error);
                    }
                }
                else
                {
                    playlistFailures = 0;
                    _mediaUri = uri;
                    _policy!.TargetDuration = pl.TargetDuration > 0 ? pl.TargetDuration : _policy.TargetDuration;
                    if (_nextUpstreamSeq < 0 || _nextUpstreamSeq < pl.MediaSequence || _nextUpstreamSeq > pl.NextSequence + 3)
                    {
                        if (_nextUpstreamSeq >= 0)
                        {
                            _epochPending = true; // the upstream restarted its numbering or we fell out of its window
                        }

                        _nextUpstreamSeq = SwitchAlignment.StartSequence(pl, _anchorBehind, _anchorUrgent);
                        _anchorBehind = 0;
                        _anchorUrgent = true;
                    }

                    _lastPlaylist = pl;
                    _lastPlaylistAt = DateTimeOffset.UtcNow;

                    var fresh = pl.Segments.Where(s => s.Sequence >= _nextUpstreamSeq).ToList();
                    if (fresh.Count > 0)
                    {
                        _policy.OnNewSegmentListed(DateTimeOffset.UtcNow);
                    }

                    foreach (var seg in fresh)
                    {
                        var bytes = await DownloadAsync(seg, tier, ct).ConfigureAwait(false);
                        if (bytes != null)
                        {
                            await PublishAsync(seg, bytes, tier).ConfigureAwait(false);
                            _nextUpstreamSeq = seg.Sequence + 1;
                        }
                        else if (!HasAlternative(tier))
                        {
                            _nextUpstreamSeq = seg.Sequence + 1; // nowhere else to go: skip it, keep the channel moving
                        }

                        if (Evaluate())
                        {
                            switched = true;
                            break;
                        }

                        if (bytes == null)
                        {
                            break;
                        }
                    }
                }

                switched |= Evaluate();

                if (DateTimeOffset.UtcNow - _lastReprobe > ReprobeEvery)
                {
                    _lastReprobe = DateTimeOffset.UtcNow;
                    _svc.RequestProbes(Channel, except: _tier!.CandidateKey, hot: true);
                }

                if (!switched)
                {
                    var wait = TimeSpan.FromSeconds(Math.Clamp((_policy?.TargetDuration ?? 6) / 3, 1, 3));
                    await Task.Delay(wait, ct).ConfigureAwait(false);
                }
            }
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "JellyTV ladder: {Channel}: session failed", Channel.Name);
        }
    }

    private bool HasAlternative(Tier tier) => _svc.RankedTiers(Channel).Any(t => t.Key != tier.Key);

    /// <summary>
    /// Downloads a segment, feeding the timing to the policy. Null when it failed: an HTTP error or junk body twice,
    /// or a download that ran (or, from its rate after a second, would run) past 1.2× the segment's duration once —
    /// slower than real time, so waiting for it
    /// (or retrying it) would only drain the viewer's buffer while another stream could already be serving.
    /// </summary>
    private async Task<byte[]?> DownloadAsync(HlsSegment seg, Tier tier, CancellationToken ct)
    {
        var headers = Headers(tier);
        var alternative = HasAlternative(tier);
        var total = 0.0;
        string? error = null;
        for (var attempt = 0; attempt < 2; attempt++)
        {
            var timeout = TimeSpan.FromSeconds(alternative
                ? Math.Max(3, seg.Duration * 1.2)
                : Math.Max(6, seg.Duration * 2.5)); // nowhere else to go: be patient
            var r = await _svc.Fetch.GetSegmentAsync(seg, headers, timeout, ct, alternative ? timeout : null).ConfigureAwait(false);
            total += r.Seconds;
            if (r.Ok)
            {
                if (_tier?.Key == tier.Key)
                {
                    _policy?.OnSegment(DateTimeOffset.UtcNow, total, seg.Duration, r.Body!.Length);
                    if (seg.Duration > 0)
                    {
                        tier.MeasuredBitrate = r.Body!.Length * 8 / seg.Duration;
                    }
                }

                return r.Body;
            }

            error = r.Error;
            if (r.Status == 0 && r.Error?.StartsWith("timed out", StringComparison.Ordinal) == true && alternative)
            {
                error = "download slower than real time: " + r.Error;
                break;
            }
        }

        if (_tier?.Key == tier.Key)
        {
            _policy?.OnSegmentFailed(DateTimeOffset.UtcNow, error ?? "error");
        }

        _logger.LogDebug("JellyTV ladder: {Channel}: segment {Seq} of {Tier} failed: {Error}", Channel.Name, seg.Sequence, SwitchPolicy.Describe(tier), error);
        return null;
    }

    private async Task PublishAsync(HlsSegment seg, byte[] bytes, Tier tier)
    {
        var info = TsSplicer.Analyze(bytes);
        var disc = false;
        var last = Window.Last;
        TsInfo? prevOut = null;
        if (last != null)
        {
            try
            {
                await last.Body.WaitAsync(TimeSpan.FromSeconds(20)).ConfigureAwait(false);
            }
            catch (TimeoutException)
            {
            }

            prevOut = last.OutInfo;
        }

        if (_canonicalReady != null && _canonical == null)
        {
            try
            {
                await _canonicalReady.WaitAsync(TimeSpan.FromSeconds(20)).ConfigureAwait(false);
            }
            catch (TimeoutException)
            {
            }
        }

        var jump = !_epochPending && prevOut?.EndDts is { } pe && info.StartDts is { } ns
                   && Math.Abs(TsSplicer.Unwrap(TsSplicer.Mod(ns + _map.Offset), pe) - pe) > 10 * 90000;
        if (_epochPending || seg.Discontinuity || jump)
        {
            if (_canonical == null)
            {
                _map = SpliceMap.Identity;
                if (info.IsTs)
                {
                    _canonical = info;
                }

                disc = last != null;
            }
            else if (_svc.ContinuousMode && prevOut != null && TsSplicer.Plan(_canonical, info, prevOut) is { } plan)
            {
                _map = plan;
            }
            else
            {
                // Different codecs, fMP4, or discontinuity mode: tell the player instead.
                _map = SpliceMap.Identity;
                disc = true;
            }

            _epoch++;
            if (_switchReason != null)
            {
                lock (_history)
                {
                    if (_history.Count > 0)
                    {
                        var h = _history[^1];
                        _history[^1] = h with { Spliced = !disc };
                    }
                }

                _logger.LogInformation("JellyTV ladder: {Channel}: first segment from {Tier} published ({How}, offset {Offset:0.000}s)",
                    Channel.Name, SwitchPolicy.Describe(tier), disc ? "discontinuity" : "spliced", _map.Offset / 90000.0);
                _switchReason = null;
            }
            else if (seg.Discontinuity || jump)
            {
                _logger.LogInformation("JellyTV ladder: {Channel}: upstream {What} on {Tier} — {How}", Channel.Name,
                    seg.Discontinuity ? "discontinuity" : "timestamp jump", SwitchPolicy.Describe(tier), disc ? "passed on" : "spliced");
            }

            _epochPending = false;
        }

        if (!_map.IsIdentity && prevOut != null)
        {
            _map = TsSplicer.WithContinuity(_map, info, prevOut.LastCc);
        }

        var output = _map.IsIdentity ? bytes : TsSplicer.Rewrite(bytes, _map);
        var outInfo = _map.IsIdentity ? info : TsSplicer.Analyze(output);
        Window.Publish(new PublishedSegment
        {
            Duration = seg.Duration,
            Discontinuity = disc,
            Upstream = seg.Uri,
            TierKey = tier.Key,
            Epoch = _epoch,
            Body = Task.FromResult<byte[]?>(output),
            OutInfo = outInfo
        }, DateTimeOffset.UtcNow);
    }

    /// <summary>Asks the policy; switches when it says so. True when a switch happened.</summary>
    private bool Evaluate()
    {
        if (_policy == null || _tier == null)
        {
            return false;
        }

        var now = DateTimeOffset.UtcNow;
        var ranked = _svc.RankedTiers(Channel);
        var from = _tier;
        var d = _policy.Evaluate(now, ranked, _svc.Probe);
        foreach (var key in _policy.WantProbe)
        {
            _svc.RequestProbe(Channel, key);
        }

        if (d == null)
        {
            return false;
        }

        var sameCandidate = d.Target.CandidateKey == from.CandidateKey;
        _tier = d.Target;
        _mediaUri = d.Target.MediaUri;
        _epochPending = true;
        _switchReason = d.Reason;
        if (!sameCandidate)
        {
            // Another stream of the game: pick up at the same distance from its live edge as the old one's content
            // ended (see SwitchAlignment).
            // (what it had listed but we had not published, plus how far its live edge moved since we last looked)
            _anchorBehind = _lastPlaylist == null ? 0
                : _lastPlaylist.Segments.Where(x => x.Sequence >= _nextUpstreamSeq).Sum(x => x.Duration) + (now - _lastPlaylistAt).TotalSeconds;
            _anchorUrgent = !d.Up;
            _nextUpstreamSeq = -1;
        }

        if (d.Hard)
        {
            _svc.NoteFailure(from.CandidateKey, d.Reason);
        }

        lock (_history)
        {
            _history.Add(new SwitchRecord(now, SwitchPolicy.Describe(from), SwitchPolicy.Describe(d.Target), d.Reason, d.Up, false));
            if (_history.Count > 30)
            {
                _history.RemoveAt(0);
            }
        }

        _logger.LogInformation("JellyTV ladder: {Channel}: switch {Direction} {From} → {To}: {Reason}", Channel.Name,
            d.Up ? "up" : "down", SwitchPolicy.Describe(from), SwitchPolicy.Describe(d.Target), d.Reason);
        return true;
    }

    /// <summary>The candidate's own headers, found by URL (a rescan may reorder the channel's candidates).</summary>
    private Dictionary<string, string> Headers(Tier t)
        => LiveLadderService.Candidates(Channel).FirstOrDefault(c => c.Url == t.CandidateKey)?.Headers
           ?? new Dictionary<string, string>(Channel.Headers, StringComparer.OrdinalIgnoreCase);

    /// <summary>A rung's media playlist; follows a master to its best rendition when the rung was never probed.</summary>
    private async Task<(HlsMediaPlaylist? Playlist, Uri Uri, string? Error)> FetchMediaAsync(Tier t, Uri uri, CancellationToken ct)
    {
        var headers = Headers(t);
        var r = await _svc.Fetch.GetAsync(uri, headers, TimeSpan.FromSeconds(10), ct).ConfigureAwait(false);
        if (!r.Ok)
        {
            return (null, uri, r.Error);
        }

        var text = r.Text;
        if (HlsParser.IsMaster(text))
        {
            var variants = HlsParser.ParseMaster(text, r.FinalUri);
            var best = variants.OrderByDescending(v => v.Bandwidth).FirstOrDefault();
            if (best == null)
            {
                return (null, uri, "empty master");
            }

            r = await _svc.Fetch.GetAsync(best.Uri, headers, TimeSpan.FromSeconds(10), ct).ConfigureAwait(false);
            if (!r.Ok)
            {
                return (null, uri, r.Error);
            }

            text = r.Text;
        }

        if (!text.Contains("#EXTINF", StringComparison.Ordinal))
        {
            return (null, uri, "not a media playlist");
        }

        var pl = HlsParser.ParseMedia(text, r.FinalUri);
        if (pl.EndList)
        {
            return (null, uri, "not a live playlist");
        }

        if (pl.Segments.Count > 0 && pl.Segments[^1].MapUri != null)
        {
            return (null, uri, "fMP4 segments are not supported by the ladder");
        }

        return (pl, r.FinalUri, null);
    }

    public override string ToString() => string.Create(CultureInfo.InvariantCulture, $"{Channel.Name} on {(_tier == null ? "-" : SwitchPolicy.Describe(_tier))}");
}
