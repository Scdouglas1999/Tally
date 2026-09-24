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
/// It also measures the upstream's cadence (a stream that publishes in bursts is trouble even when it downloads fast,
/// see <see cref="SwitchPolicy"/>), keeps a cushion for such a stream (see <see cref="Cushion"/>), and logs one line a
/// minute of what it saw (see <see cref="SessionStats"/>).
/// </summary>
public sealed class LiveSession
{
    public static readonly TimeSpan IdleTimeout = TimeSpan.FromSeconds(45);
    private static readonly TimeSpan ReprobeEvery = TimeSpan.FromMinutes(3);
    private static readonly TimeSpan WatchAlternatesFor = TimeSpan.FromMinutes(2);

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

    // cadence of the rung being played, and of the others while there is trouble (playlists only)
    private CadenceMeter? _meter;
    private readonly Dictionary<string, CadenceSummary> _alternates = new(StringComparer.Ordinal);
    private Task? _watchTask;
    private DateTimeOffset _watchUntil;

    // segments downloaded (and spliced) but held back to keep a cushion, see Cushion
    private readonly List<PublishedSegment> _pending = new();
    private PublishedSegment? _lastProcessed;
    private double _leadTarget;
    private DateTimeOffset _paceStart;
    private double _publishedSeconds;

    private readonly object _statsGate = new();
    private SessionStats _stats = new(DateTimeOffset.UtcNow);

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
    /// <param name="player">A viewer's player reads it (not the recorder): counts in the diagnostics.</param>
    public async Task<string?> GetPlaylistAsync(Func<long, string> segmentUri, CancellationToken ct, bool player = true)
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

        if (player)
        {
            lock (_statsGate)
            {
                _stats.OnPlayerPoll(DateTimeOffset.UtcNow, Window.NextSequence - 1);
            }
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

        var ready = seg.Body.IsCompleted;
        var started = DateTimeOffset.UtcNow;
        var bytes = await seg.Body.WaitAsync(TimeSpan.FromSeconds(60), ct).ConfigureAwait(false);
        var now = DateTimeOffset.UtcNow;
        lock (_statsGate)
        {
            _stats.OnRequest(now, sequence, seg.Duration, ready ? 0 : (now - started).TotalSeconds);
            _stats.Sample(now, AheadOfPlayer(), ReserveSeconds);
        }

        return bytes;
    }

    /// <summary>Seconds held back (downloaded, not yet listed).</summary>
    public double ReserveSeconds
    {
        get
        {
            lock (_pending)
            {
                return _pending.Sum(p => p.Duration);
            }
        }
    }

    /// <summary>The per-minute and whole-session numbers, and the cadence seen, for <c>GET /JellyTV/Ladder</c>.</summary>
    public object Diagnostics()
    {
        var now = DateTimeOffset.UtcNow;
        Dictionary<string, object> alternates;
        lock (_alternates)
        {
            alternates = _alternates.ToDictionary(kv => kv.Key, kv => (object)CadenceJson(kv.Value));
        }

        object stats;
        lock (_statsGate)
        {
            stats = _stats.Snapshot(now);
        }

        var meter = _meter;
        return new
        {
            stats,
            reserveSeconds = Math.Round(ReserveSeconds, 1),
            leadTargetSeconds = _leadTarget,
            aheadOfPlayerSeconds = Math.Round(AheadOfPlayer(), 1),
            cadence = meter == null ? null : new
            {
                lastMinute = CadenceJson(meter.Summarize(now, SwitchPolicy.CadenceWindow)),
                last5Minutes = CadenceJson(meter.Summarize(now, CadenceMeter.Horizon))
            },
            alternatesWatchedUntil = _watchUntil > now ? _watchUntil : (DateTimeOffset?)null,
            alternates
        };
    }

    public static object CadenceJson(CadenceSummary c) => new
    {
        watchedSeconds = Math.Round(c.Watched),
        updates = c.Arrivals,
        segments = c.Segments,
        segmentSeconds = Math.Round(c.SegmentDuration, 2),
        avgGapSeconds = Math.Round(c.AvgGap, 1),
        maxGapSeconds = Math.Round(c.MaxGap, 1),
        late = c.Late,
        neededCushionSeconds = Math.Round(c.NeededCushion, 1),
        irregular = c.Irregular,
        text = c.Describe()
    };

    /// <summary>Listed seconds the player has not asked for yet.</summary>
    private double AheadOfPlayer()
    {
        long max;
        lock (_statsGate)
        {
            max = _stats.MaxRequested;
        }

        return Window.SecondsAfter(max);
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
            lock (_pending)
            {
                _pending.Clear();
            }

            _lastProcessed = null;
            _canonical = null;
            _canonicalReady = null;
            _map = SpliceMap.Identity;
            _epochPending = true;
            _discontinuityPending = restarted; // a player that was here before must reset its timeline
            _startedAt = now;
            lock (_statsGate)
            {
                _stats = new SessionStats(now);
            }

            lock (_alternates)
            {
                _alternates.Clear();
            }

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
            _meter = new CadenceMeter(now);
            _meter.OnPlaylist(now, playlist.Segments);

            // List the newest few right away, downloading them in order in the background — the player asks for
            // them in order too, and its first request simply waits for the first download (same as a direct proxy).
            // A stream seen publishing in bursts starts further back, the rest held back as a cushion (see Cushion).
            var plan = Cushion.Plan(playlist, _svc.Probe(_tier.CandidateKey)?.Cadence);
            _leadTarget = plan.LeadTarget;
            _paceStart = now;
            _publishedSeconds = plan.Listed.Sum(x => x.Duration);
            if (plan.Why == null)
            {
                _logger.LogInformation("JellyTV ladder: {Channel}: starting on {Tier} ({Rank} of {Count} rungs, {Candidates} streams), {Behind:0} s behind its live edge",
                    Channel.Name, SwitchPolicy.Describe(_tier), ranked.FindIndex(t => t.Key == _tier.Key) + 1, ranked.Count, Channel.Candidates.Count, plan.Behind);
            }
            else
            {
                _logger.LogInformation("JellyTV ladder: {Channel}: starting on {Tier} ({Rank} of {Count} rungs, {Candidates} streams), {Behind:0} s behind its live edge: {Listed:0} s listed, the rest held back as a cushion, because {Why}",
                    Channel.Name, SwitchPolicy.Describe(_tier), ranked.FindIndex(t => t.Key == _tier.Key) + 1, ranked.Count, Channel.Candidates.Count, plan.Behind, plan.LeadTarget, plan.Why);
            }

            var initial = plan.Listed;
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
                _lastProcessed = published;
                _discontinuityPending = false;
                lock (_statsGate)
                {
                    _stats.OnPublished(now, seg.Duration);
                }

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
            _nextUpstreamSeq = plan.NextSequence;
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

    /// <summary>Lists held-back segments that are due: all of them when nothing is held back on purpose, else as many
    /// as keep the channel's playlist <see cref="_leadTarget"/> ahead of real time.</summary>
    private void Release(DateTimeOffset now)
    {
        while (true)
        {
            PublishedSegment seg;
            lock (_pending)
            {
                if (_pending.Count == 0)
                {
                    return;
                }

                if (_leadTarget > 0 && now < Cushion.NextRelease(_paceStart, _publishedSeconds, _leadTarget)
                    && _pending.Sum(p => p.Duration) < Cushion.MaxBehind)
                {
                    return;
                }

                seg = _pending[0];
                _pending.RemoveAt(0);
            }

            Window.Publish(seg, now);
            _publishedSeconds += seg.Duration;
            lock (_statsGate)
            {
                _stats.OnPublished(now, seg.Duration);
                _stats.Sample(now, AheadOfPlayer(), ReserveSeconds);
            }
        }
    }

    /// <summary>How long the loop may sleep before a held-back segment is due.</summary>
    private TimeSpan UntilNextRelease(DateTimeOffset now)
    {
        lock (_pending)
        {
            if (_pending.Count == 0 || _leadTarget <= 0)
            {
                return TimeSpan.MaxValue;
            }
        }

        var due = Cushion.NextRelease(_paceStart, _publishedSeconds, _leadTarget) - now;
        return due < TimeSpan.FromMilliseconds(100) ? TimeSpan.FromMilliseconds(100) : due;
    }

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

                Release(now);
                LogMinute(now);
                var tier = _tier!;
                var switched = false;
                var fetchStarted = DateTimeOffset.UtcNow;
                var (pl, uri, error) = await FetchMediaAsync(tier, _mediaUri!, ct).ConfigureAwait(false);
                lock (_statsGate)
                {
                    _stats.OnPlaylistFetch((DateTimeOffset.UtcNow - fetchStarted).TotalSeconds);
                }

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
                    ObserveCadence(tier, pl, _lastPlaylistAt);

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

                        Release(DateTimeOffset.UtcNow);

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
                Release(DateTimeOffset.UtcNow);

                if (DateTimeOffset.UtcNow - _lastReprobe > ReprobeEvery)
                {
                    _lastReprobe = DateTimeOffset.UtcNow;
                    _svc.RequestProbes(Channel, except: _tier!.CandidateKey, hot: true);
                }

                if (_policy?.WatchAlternates == true && HasAlternative(_tier!))
                {
                    _watchUntil = DateTimeOffset.UtcNow + WatchAlternatesFor;
                    if (_watchTask is not { IsCompleted: false })
                    {
                        _watchTask = Task.Run(() => WatchAlternatesAsync(ct), CancellationToken.None);
                    }
                }

                if (!switched)
                {
                    var wait = TimeSpan.FromSeconds(Math.Clamp((_policy?.TargetDuration ?? 6) / 3, 1, 3));
                    var release = UntilNextRelease(DateTimeOffset.UtcNow);
                    await Task.Delay(release < wait ? release : wait, ct).ConfigureAwait(false);
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
        finally
        {
            var now = DateTimeOffset.UtcNow;
            string line;
            lock (_statsGate)
            {
                line = _stats.TotalLine(now, _meter?.Summarize(now, now - _startedAt));
            }

            _logger.LogInformation("JellyTV ladder: {Channel}: session ended after {Minutes:0.0} min: {Line}", Channel.Name, (now - _startedAt).TotalMinutes, line);
        }
    }

    /// <summary>One line a minute: what the session published, how the upstream behaved, how the player fared.</summary>
    private void LogMinute(DateTimeOffset now)
    {
        string line;
        lock (_statsGate)
        {
            if (!_stats.Due(now))
            {
                return;
            }

            line = _stats.MinuteLine(now, _meter?.Summarize(now, now - _stats.MinuteStart));
        }

        lock (_alternates)
        {
            var others = _alternates.Where(kv => kv.Key != _tier?.CandidateKey).ToList();
            if (_watchUntil > now && others.Count > 0)
            {
                line += "; watching " + string.Join(", ", others.Select(kv => CandidateName(kv.Key) + ": " + kv.Value.Describe()));
            }
        }

        _logger.LogInformation("JellyTV ladder: {Channel} [minute] on {Tier}: {Line}", Channel.Name,
            _tier == null ? "-" : SwitchPolicy.Describe(_tier), line);
    }

    private string CandidateName(string key)
    {
        var list = LiveLadderService.Candidates(Channel);
        for (var i = 0; i < list.Count; i++)
        {
            if (list[i].Url == key)
            {
                return "candidate " + (i + 1).ToString(CultureInfo.InvariantCulture);
            }
        }

        return "a former candidate";
    }

    /// <summary>Feeds a fetch of the current rung's playlist to its cadence meter and the policy.</summary>
    private void ObserveCadence(Tier tier, HlsMediaPlaylist pl, DateTimeOffset now)
    {
        var meter = _meter ??= new CadenceMeter(now);
        meter.OnPlaylist(now, pl.Segments);
        _policy?.OnCadence(now, meter.Summarize(now, SwitchPolicy.CadenceWindow));
        _svc.NoteCadence(tier.CandidateKey, meter.Summarize(now, TimeSpan.FromMinutes(2)), now);
    }

    /// <summary>
    /// While the current stream's segments come late: fetches the other streams' media playlists (one rung each, never
    /// a segment) every couple of seconds and records their cadence, so the policy knows whether one arrives steadier.
    /// Stops <see cref="WatchAlternatesFor"/> after the last late update.
    /// </summary>
    private async Task WatchAlternatesAsync(CancellationToken ct)
    {
        var meters = new Dictionary<string, (Uri Uri, CadenceMeter Meter)>(StringComparer.Ordinal);
        try
        {
            while (!ct.IsCancellationRequested && DateTimeOffset.UtcNow < _watchUntil)
            {
                var current = _tier;
                lock (_alternates)
                {
                    if (current != null)
                    {
                        _alternates.Remove(current.CandidateKey); // now being played: its own meter tells
                    }
                }

                var others = _svc.RankedTiers(Channel)
                    .Where(t => current == null || t.CandidateKey != current.CandidateKey)
                    .GroupBy(t => t.CandidateKey, StringComparer.Ordinal)
                    .Select(g => g.First())
                    .Take(3)
                    .ToList();
                var interval = 3.0;
                foreach (var t in others)
                {
                    if (!meters.TryGetValue(t.CandidateKey, out var m))
                    {
                        m = (t.MediaUri, new CadenceMeter(DateTimeOffset.UtcNow));
                    }

                    var (pl, uri, _) = await FetchMediaAsync(t, m.Uri, ct).ConfigureAwait(false);
                    var now = DateTimeOffset.UtcNow;
                    if (pl != null)
                    {
                        m.Meter.OnPlaylist(now, pl.Segments);
                        m = (uri, m.Meter);
                        interval = Math.Min(interval, Math.Clamp(pl.TargetDuration / 3, 1, 3));
                        var summary = m.Meter.Summarize(now, TimeSpan.FromMinutes(2));
                        _svc.NoteCadence(t.CandidateKey, summary, now);
                        lock (_alternates)
                        {
                            _alternates[t.CandidateKey] = summary;
                        }
                    }

                    meters[t.CandidateKey] = m;
                }

                await Task.Delay(TimeSpan.FromSeconds(interval), ct).ConfigureAwait(false);
            }
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception ex)
        {
            _logger.LogDebug(ex, "JellyTV ladder: {Channel}: watching the other streams failed", Channel.Name);
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
            lock (_statsGate)
            {
                _stats.OnDownload(r.Seconds, seg.Duration, r.Ok);
            }

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
        var last = _lastProcessed;
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
        var processed = new PublishedSegment
        {
            Duration = seg.Duration,
            Discontinuity = disc,
            Upstream = seg.Uri,
            TierKey = tier.Key,
            Epoch = _epoch,
            Body = Task.FromResult<byte[]?>(output),
            OutInfo = outInfo
        };
        _lastProcessed = processed;
        lock (_pending)
        {
            _pending.Add(processed);
        }

        Release(DateTimeOffset.UtcNow);
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
        var lastUpdate = _meter?.LastUpdateAt ?? _lastPlaylistAt;
        _tier = d.Target;
        _mediaUri = d.Target.MediaUri;
        _meter = null; // a new playlist: its cadence starts over
        lock (_statsGate)
        {
            _stats.OnSwitch();
        }

        _epochPending = true;
        _switchReason = d.Reason;
        if (!sameCandidate)
        {
            // Another stream of the game: pick up at the same distance from its live edge as the old one's content
            // ended (see SwitchAlignment): what it had listed but we had not published, plus how far the live action
            // has moved since its playlist last gained a segment. A stream that stalled or holds segments back lists
            // an edge that stands still while the game goes on; entering the new stream that much further back
            // publishes the missing seconds at once (they refill the viewer's cushion) instead of skipping them.
            var since = Math.Max((now - lastUpdate).TotalSeconds, (now - _lastPlaylistAt).TotalSeconds);
            _anchorBehind = _lastPlaylist == null ? 0
                : _lastPlaylist.Segments.Where(x => x.Sequence >= _nextUpstreamSeq).Sum(x => x.Duration) + since;
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
