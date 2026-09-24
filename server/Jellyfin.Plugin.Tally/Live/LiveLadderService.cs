using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Tally.Models;
using Jellyfin.Plugin.Tally.Services;
using Jellyfin.Plugin.Tally.Sources;
using MediaBrowser.Controller.MediaEncoding;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Tally.Live;

/// <summary>What the board says about a channel's stream.</summary>
public sealed class StreamStatus
{
    [System.Text.Json.Serialization.JsonPropertyName("label")] public string Label { get; set; } = string.Empty;

    /// <summary>The stream being played (or that a viewer would start on) is the best one the channel has.</summary>
    [System.Text.Json.Serialization.JsonPropertyName("firstChoice")] public bool FirstChoice { get; set; }

    /// <summary>How many streams the channel has (merged duplicates).</summary>
    [System.Text.Json.Serialization.JsonPropertyName("candidates")] public int Candidates { get; set; }

    /// <summary>Someone is watching and the plugin is steering the stream right now.</summary>
    [System.Text.Json.Serialization.JsonPropertyName("live")] public bool Live { get; set; }
}

/// <summary>
/// The live ladder: probes the candidate streams of every channel (once at discovery; the other candidates of a
/// watched channel every few minutes — never all channels all the time), ranks their renditions, and runs one
/// <see cref="LiveSession"/> per watched channel that has more than one rung. Channels with a single stream and a
/// single rendition are left to the plain proxy, byte for byte as before.
/// </summary>
public sealed class LiveLadderService : IHostedService, IDisposable
{
    private static readonly TimeSpan DiscoveryReprobe = TimeSpan.FromMinutes(20);
    private static readonly TimeSpan SingleReprobe = TimeSpan.FromHours(6);
    private static readonly TimeSpan HotProbeSpacing = TimeSpan.FromSeconds(60);

    private readonly SourceManager _sources;
    private readonly ILogger<LiveLadderService> _logger;
    private readonly StreamProber _prober;
    private readonly ConcurrentDictionary<string, CandidateProbe> _probes = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<string, DateTimeOffset> _lastProbeRequest = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<string, byte> _queued = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<string, DateTimeOffset> _singleProbed = new(StringComparer.OrdinalIgnoreCase);
    private readonly ConcurrentDictionary<string, (DateTimeOffset At, string Why)> _failures = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<string, (DateTimeOffset At, CadenceSummary Cadence)> _cadence = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<string, LiveSession> _sessions = new(StringComparer.OrdinalIgnoreCase);
    private readonly ConcurrentQueue<(SourceChannel Channel, int Index)> _hot = new();
    private readonly ConcurrentQueue<(SourceChannel Channel, int Index)> _cold = new();
    private readonly SemaphoreSlim _work = new(0);
    private CancellationTokenSource? _cts;
    private Task[] _workers = Array.Empty<Task>();

    public LiveLadderService(SourceManager sources, UpstreamFetcher fetcher, IMediaEncoder mediaEncoder, ILogger<LiveLadderService> logger)
    {
        _sources = sources;
        _logger = logger;
        Fetch = new LiveFetch(fetcher);
        _prober = new StreamProber(Fetch, () =>
        {
            try
            {
                return mediaEncoder.ProbePath;
            }
            catch (Exception)
            {
                return null;
            }
        }, logger);
    }

    public LiveFetch Fetch { get; }

    public static bool Enabled => Plugin.Instance?.Configuration.LiveLadderEnabled ?? true;

    /// <summary>Continuous timestamps at a switch (the default), or the plain #EXT-X-DISCONTINUITY variant kept for
    /// comparison (development setting LiveSwitchMode=discontinuity).</summary>
    public bool ContinuousMode
        => !string.Equals(Plugin.Instance?.Configuration.LiveSwitchMode, "discontinuity", StringComparison.OrdinalIgnoreCase);

    public CandidateProbe? Probe(string candidateKey) => _probes.TryGetValue(candidateKey, out var p) ? WithLatest(candidateKey, p) : null;

    /// <summary>What a session saw of a candidate's cadence while playing it or watching it as an alternative: newer
    /// than the probe's, so rankings and switches use it (a stream that turned bursty stops counting as healthy).</summary>
    public void NoteCadence(string candidateKey, CadenceSummary cadence, DateTimeOffset at)
    {
        if (cadence.Enough)
        {
            _cadence[candidateKey] = (at, cadence);
        }
    }

    /// <summary>Every rung of a channel, best first. Candidates never probed contribute one unknown rung.</summary>
    public List<Tier> RankedTiers(SourceChannel c)
    {
        var tiers = new List<Tier>();
        var candidates = Candidates(c);
        for (var i = 0; i < candidates.Count; i++)
        {
            var cand = candidates[i];
            if (_probes.TryGetValue(cand.Url, out var p) && p.Ok && !p.IsLive)
            {
                continue; // VOD / recordings: plain pass-through
            }

            if (p != null && p.Tiers.Count > 0 && (p.IsTs || !p.Ok))
            {
                tiers.AddRange(p.Tiers);
            }
            else if (Uri.TryCreate(cand.Url, UriKind.Absolute, out var u))
            {
                tiers.Add(new Tier { Candidate = i, CandidateKey = cand.Url, Variant = 0, MediaUri = u });
            }
        }

        return LadderRanking.Rank(tiers);
    }

    public static IReadOnlyList<StreamCandidate> Candidates(SourceChannel c)
        => c.Candidates.Count > 0 ? c.Candidates
            : new[] { new StreamCandidate { Url = c.StreamUrl, Headers = c.Headers, Name = c.Name, MemberId = c.Id } };

    public LiveSession Session(SourceChannel channel)
    {
        var s = _sessions.GetOrAdd(channel.Id, _ => new LiveSession(this, channel, _logger));
        if (!s.Running)
        {
            s.Channel = channel; // pick up the latest scan's URLs before (re)starting
        }

        return s;
    }

    public LiveSession? FindSession(string channelId) => _sessions.TryGetValue(channelId, out var s) ? s : null;

    public IEnumerable<LiveSession> Sessions => _sessions.Values;

    /// <summary>Board entry for a channel: the rung being played, or the one a viewer would start on.</summary>
    public StreamStatus? Describe(SourceChannel c)
    {
        var ranked = RankedTiers(c);
        if (ranked.Count == 0)
        {
            return null;
        }

        var s = FindSession(c.Id);
        if (s is { Running: true, Passthrough: false, CurrentTier: { } cur })
        {
            var live = ranked.FirstOrDefault(t => t.Key == cur.Key) ?? cur;
            return new StreamStatus { Label = live.Label, FirstChoice = ranked[0].Key == cur.Key, Candidates = Candidates(c).Count, Live = true };
        }

        var start = SwitchPolicy.Initial(ranked, Probe, DateTimeOffset.UtcNow, TimeSpan.FromMinutes(45));
        if (start == null || !_probes.ContainsKey(start.CandidateKey))
        {
            return null; // never probed: nothing to say
        }

        return new StreamStatus { Label = start.Label, FirstChoice = ranked[0].Key == start.Key, Candidates = Candidates(c).Count, Live = false };
    }

    public void NoteFailure(string candidateKey, string why) => _failures[candidateKey] = (DateTimeOffset.UtcNow, why);

    /// <summary>Probe these candidates of a watched channel soon (ahead of discovery probes).</summary>
    public void RequestProbes(SourceChannel c, string? except, bool hot)
    {
        var list = Candidates(c);
        for (var i = 0; i < list.Count; i++)
        {
            if (list[i].Url != except)
            {
                Enqueue(c, i, hot);
            }
        }
    }

    public void RequestProbe(SourceChannel c, string candidateKey)
    {
        var list = Candidates(c);
        for (var i = 0; i < list.Count; i++)
        {
            if (list[i].Url == candidateKey)
            {
                Enqueue(c, i, hot: true);
            }
        }
    }

    public Task StartAsync(CancellationToken cancellationToken)
    {
        _cts = new CancellationTokenSource();
        _sources.Refreshed += OnRefreshed;
        // probes of multi-stream channels spend most of their time waiting on a playlist watch
        _workers = Enumerable.Range(0, 3).Select(_ => Task.Run(() => WorkAsync(_cts.Token))).ToArray();
        var now = _sources.GetChannels();
        if (now.Count > 0)
        {
            OnRefreshed(now);
        }

        return Task.CompletedTask;
    }

    public async Task StopAsync(CancellationToken cancellationToken)
    {
        _sources.Refreshed -= OnRefreshed;
        _cts?.Cancel();
        foreach (var s in _sessions.Values)
        {
            s.Stop();
        }

        try
        {
            await Task.WhenAll(_workers).WaitAsync(TimeSpan.FromSeconds(5), cancellationToken).ConfigureAwait(false);
        }
        catch (Exception ex) when (ex is TimeoutException or OperationCanceledException)
        {
        }
    }

    public void Dispose()
    {
        _cts?.Dispose();
        _work.Dispose();
    }

    private void OnRefreshed(IReadOnlyList<SourceChannel> channels)
    {
        if (!Enabled)
        {
            return;
        }

        var live = new HashSet<string>(StringComparer.Ordinal);
        var now = DateTimeOffset.UtcNow;
        var queued = 0;
        foreach (var c in channels)
        {
            var list = Candidates(c);
            foreach (var cand in list)
            {
                live.Add(cand.Url);
            }

            if (_sessions.TryGetValue(c.Id, out var s) && !s.Running)
            {
                s.Channel = c;
            }
            else if (s != null)
            {
                s.Channel = MergeKeepingCurrent(s.Channel, c);
            }

            if (list.Count > 1)
            {
                for (var i = 0; i < list.Count; i++)
                {
                    if (!_probes.TryGetValue(list[i].Url, out var p) || now - p.At > DiscoveryReprobe)
                    {
                        Enqueue(c, i, hot: false);
                        queued++;
                    }
                }
            }
            else if (list.Count == 1 && !_probes.ContainsKey(list[0].Url)
                     && (!_singleProbed.TryGetValue(c.Id, out var at) || now - at > SingleReprobe))
            {
                // one stream: one probe at discovery (it tells whether the stream has renditions to choose from)
                _singleProbed[c.Id] = now;
                Enqueue(c, 0, hot: false);
                queued++;
            }
        }

        foreach (var key in _probes.Keys.Where(k => !live.Contains(k)).ToList())
        {
            _probes.TryRemove(key, out _);
        }

        foreach (var s in _sessions.Values.Where(s => !s.Running && now - s.LastTouched > TimeSpan.FromHours(2)).ToList())
        {
            _sessions.TryRemove(s.Channel.Id, out _);
        }

        if (queued > 0)
        {
            _logger.LogInformation("JellyTV ladder: probing {Count} streams", queued);
        }
    }

    /// <summary>A running session keeps the candidate it is playing even if the rescan's list moved it.</summary>
    private static SourceChannel MergeKeepingCurrent(SourceChannel old, SourceChannel fresh)
    {
        var urls = fresh.Candidates.Select(x => x.Url).ToHashSet(StringComparer.Ordinal);
        if (old.Candidates.All(x => urls.Contains(x.Url)) && old.Candidates.Count == fresh.Candidates.Count)
        {
            return fresh;
        }

        // Candidate indexes are baked into the session's rungs: keep the old list, append the new streams.
        var copy = fresh.ShallowCopy();
        copy.Candidates = old.Candidates.Concat(fresh.Candidates.Where(x => !old.Candidates.Any(o => o.Url == x.Url))).ToList();
        return copy;
    }

    private void Enqueue(SourceChannel c, int index, bool hot)
    {
        var key = Candidates(c)[index].Url;
        var now = DateTimeOffset.UtcNow;
        if (hot && _lastProbeRequest.TryGetValue(key, out var last) && now - last < HotProbeSpacing)
        {
            return;
        }

        if (!_queued.TryAdd(key, 0))
        {
            return;
        }

        _lastProbeRequest[key] = now;
        (hot ? _hot : _cold).Enqueue((c, index));
        _work.Release();
    }

    private async Task WorkAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                await _work.WaitAsync(ct).ConfigureAwait(false);
                if (!_hot.TryDequeue(out var job) && !_cold.TryDequeue(out job))
                {
                    continue;
                }

                var list = Candidates(job.Channel);
                if (job.Index >= list.Count)
                {
                    continue;
                }

                var cand = list[job.Index];
                _queued.TryRemove(cand.Url, out _);
                _probes.TryGetValue(cand.Url, out var previous);
                // several streams: watch each one's playlist for a while too, so a bursty one is not ranked first
                var probe = await _prober.ProbeAsync(job.Index, cand, previous, withSegment: true, ct,
                    list.Count > 1 ? StreamProber.CadenceWatch : null).ConfigureAwait(false);

                if (!probe.Ok)
                {
                    NoteFailure(cand.Url, probe.Error ?? "probe failed");
                }

                _probes[cand.Url] = probe;
                _logger.LogInformation("JellyTV ladder: probed {Channel} #{Index}: {Result}", job.Channel.Name, job.Index + 1, Summary(probe));
                await Task.Delay(300, ct).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "JellyTV ladder: probe failed");
            }
        }
    }

    /// <summary>The probe with what was learned since: "it has not failed recently" (a failure noted in the last 5
    /// minutes spoils an older good probe), and the newest cadence a session saw (within 10 minutes).</summary>
    private CandidateProbe WithLatest(string key, CandidateProbe p)
    {
        var now = DateTimeOffset.UtcNow;
        var failure = _failures.TryGetValue(key, out var f) ? f : default;
        var failed = p.Ok && failure.Why != null && failure.At > p.At && now - failure.At < TimeSpan.FromMinutes(5);
        var seen = _cadence.TryGetValue(key, out var c) && c.At > p.At && now - c.At < TimeSpan.FromMinutes(10);
        if (!failed && !seen)
        {
            return p;
        }

        return new CandidateProbe
        {
            At = p.At, Ok = p.Ok && !failed, Error = failed ? failure.Why : p.Error, Tiers = p.Tiers, Throughput = p.Throughput, Fresh = p.Fresh,
            TargetDuration = p.TargetDuration, NextSequence = p.NextSequence, IsTs = p.IsTs, Encrypted = p.Encrypted, IsLive = p.IsLive,
            Cadence = seen ? c.Cadence : p.Cadence
        };
    }

    public static string Summary(CandidateProbe p)
    {
        if (!p.Ok)
        {
            return "failed: " + p.Error;
        }

        var tiers = string.Join(", ", LadderRanking.Rank(p.Tiers).Select(t =>
            (string.IsNullOrEmpty(t.Label) ? "?" : t.Label) + " @" + (t.Bitrate / 1e6).ToString("0.0", System.Globalization.CultureInfo.InvariantCulture) + " Mbps"));
        return string.Create(System.Globalization.CultureInfo.InvariantCulture,
            $"{tiers}; downloads at {(p.Throughput ?? 0) / 1e6:0.0} Mbps; target {p.TargetDuration:0}s; {(!p.IsLive ? "VOD" : p.Fresh ? "live" : "stale")}{(p.IsTs ? string.Empty : "; not MPEG-TS")}{(p.Encrypted ? "; AES" : string.Empty)}{(p.Cadence is { } c ? "; " + (c.Irregular ? "BURSTY: " : string.Empty) + c.Describe() : string.Empty)}");
    }
}
