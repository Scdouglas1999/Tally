using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;

namespace Jellyfin.Plugin.Tally.Live;

public sealed record SwitchDecision(Tier Target, string Reason, bool Hard, bool Up);

/// <summary>
/// When a watched channel changes rung. Pure: every input carries its own timestamp, so tests drive it with a
/// fake clock. Down when the current rung is really struggling (two consecutive segments that took more than 80% of
/// their duration to download, a segment that failed twice or took longer than 1.2× its duration, no new segment for two target durations); up again
/// after <see cref="StabilityWindow"/> without trouble, when a better rung downloads at 2× its bitrate. At least
/// <see cref="MinSwitchInterval"/> between switches — except that a dead stream is left at once, since holding on
/// to it would stall the viewer. A candidate that failed backs off, 2 minutes doubling to 16, before it is tried again.
/// </summary>
public sealed class SwitchPolicy
{
    public static readonly TimeSpan MinSwitchInterval = TimeSpan.FromSeconds(60);
    public static readonly TimeSpan HardSwitchFloor = TimeSpan.FromSeconds(8);
    public static readonly TimeSpan StabilityWindow = TimeSpan.FromMinutes(3);
    public static readonly TimeSpan ProbeMaxAgeForSwitch = TimeSpan.FromMinutes(5);
    public static readonly TimeSpan ProbeMaxAgeForStepUp = TimeSpan.FromSeconds(90);
    public const double SlowRatio = 0.8;

    private readonly Dictionary<string, (DateTimeOffset Until, int Level, DateTimeOffset At)> _backoff = new(StringComparer.Ordinal);
    private int _consecutiveSlow;
    private string? _hard;
    private double? _throughput;

    public SwitchPolicy(Tier current, DateTimeOffset now)
    {
        Current = current;
        LastSwitchAt = now;
        LastTroubleAt = DateTimeOffset.MinValue;
        LastNewSegmentAt = now;
    }

    public Tier Current { get; private set; }

    public DateTimeOffset LastSwitchAt { get; private set; }

    public DateTimeOffset LastTroubleAt { get; private set; }

    public DateTimeOffset LastNewSegmentAt { get; private set; }

    /// <summary>Target duration of the current rung's playlist, seconds.</summary>
    public double TargetDuration { get; set; } = 6;

    /// <summary>EWMA of this server's download rate on the current candidate, bits/s.</summary>
    public double? Throughput => _throughput;

    /// <summary>Candidates the policy would like fresh probes of (to decide a step up).</summary>
    public HashSet<string> WantProbe { get; } = new(StringComparer.Ordinal);

    public void OnNewSegmentListed(DateTimeOffset now) => LastNewSegmentAt = now;

    public void OnSegment(DateTimeOffset now, double seconds, double duration, long bytes)
    {
        if (seconds > 0.02 && bytes > 0)
        {
            var bps = bytes * 8 / seconds;
            _throughput = _throughput is { } t ? (0.5 * t) + (0.5 * bps) : bps;
        }

        if (duration > 0 && seconds > SlowRatio * duration)
        {
            _consecutiveSlow++;
            LastTroubleAt = now;
        }
        else
        {
            _consecutiveSlow = 0;
        }
    }

    /// <summary>A segment could not be had: failed twice, or its download ran slower than real time.</summary>
    public void OnSegmentFailed(DateTimeOffset now, string why)
    {
        _hard = "segment failed (" + why + ")";
        LastTroubleAt = now;
    }

    public bool IsBackedOff(string candidateKey, DateTimeOffset now)
        => _backoff.TryGetValue(candidateKey, out var b) && now < b.Until;

    public void BackOff(string candidateKey, DateTimeOffset now)
    {
        var level = _backoff.TryGetValue(candidateKey, out var b) && now - b.At < TimeSpan.FromMinutes(20) ? Math.Min(b.Level + 1, 3) : 0;
        _backoff[candidateKey] = (now + TimeSpan.FromMinutes(2 << level), level, now);
    }

    /// <summary>Starting rung: the best healthy one, else the best one not backed off.</summary>
    public static Tier? Initial(IReadOnlyList<Tier> ranked, Func<string, CandidateProbe?> probes, DateTimeOffset now, TimeSpan maxProbeAge)
        => ranked.FirstOrDefault(t => LadderRanking.Healthy(t, probes(t.CandidateKey), now, maxProbeAge))
           ?? ranked.FirstOrDefault(t => probes(t.CandidateKey) is not { Ok: false })
           ?? ranked.FirstOrDefault();

    public SwitchDecision? Evaluate(DateTimeOffset now, IReadOnlyList<Tier> ranked, Func<string, CandidateProbe?> probes)
    {
        WantProbe.Clear();
        var cur = IndexOf(ranked, Current);
        var sinceSwitch = now - LastSwitchAt;

        if (_hard == null && TargetDuration > 0 && now - LastNewSegmentAt > TimeSpan.FromSeconds(2 * TargetDuration + 1))
        {
            _hard = string.Format(CultureInfo.InvariantCulture, "no new segment for {0:0}s", (now - LastNewSegmentAt).TotalSeconds);
            LastTroubleAt = now;
        }

        if (cur < 0)
        {
            _hard ??= "stream no longer offered";
        }

        if (_hard != null)
        {
            if (sinceSwitch < HardSwitchFloor)
            {
                return null;
            }

            BackOff(Current.CandidateKey, now);
            var target = ChooseAfterFailure(ranked, cur, probes, now);
            if (target == null)
            {
                return null; // nothing else to try: keep following this one
            }

            return Commit(new SwitchDecision(target, _hard, Hard: true, Up: IndexOf(ranked, target) < cur), now);
        }

        if (_consecutiveSlow >= 2 && sinceSwitch >= MinSwitchInterval)
        {
            var target = ChooseLower(ranked, cur, probes, now);
            if (target != null)
            {
                var reason = string.Format(CultureInfo.InvariantCulture, "{0} consecutive segments took over {1:0}% of their duration (≈{2:0.0} Mbps for a {3:0.0} Mbps stream)",
                    _consecutiveSlow, SlowRatio * 100, (_throughput ?? 0) / 1e6, Current.Bitrate / 1e6);
                return Commit(new SwitchDecision(target, reason, Hard: false, Up: false), now);
            }
        }

        if (cur > 0 && sinceSwitch >= StabilityWindow && now - LastTroubleAt >= StabilityWindow)
        {
            for (var i = 0; i < cur; i++)
            {
                var t = ranked[i];
                if (IsBackedOff(t.CandidateKey, now))
                {
                    continue;
                }

                bool ok;
                if (t.CandidateKey == Current.CandidateKey)
                {
                    ok = _throughput is { } bps && bps >= LadderRanking.StepUpMargin * t.Bitrate;
                }
                else
                {
                    var p = probes(t.CandidateKey);
                    if (p == null || now - p.At > ProbeMaxAgeForStepUp)
                    {
                        WantProbe.Add(t.CandidateKey);
                        continue;
                    }

                    ok = LadderRanking.Healthy(t, p, now, ProbeMaxAgeForStepUp, LadderRanking.StepUpMargin);
                }

                if (ok)
                {
                    var reason = string.Format(CultureInfo.InvariantCulture, "stable for {0:0} min and {1} is healthy with margin", (now - LastTroubleAt > TimeSpan.FromDays(1) ? sinceSwitch : now - LastTroubleAt).TotalMinutes, Describe(t));
                    return Commit(new SwitchDecision(t, reason, Hard: false, Up: true), now);
                }
            }
        }

        return null;
    }

    public static string Describe(Tier t)
        => string.IsNullOrEmpty(t.Label) ? "candidate " + (t.Candidate + 1) : t.Label + " (candidate " + (t.Candidate + 1) + ")";

    private SwitchDecision Commit(SwitchDecision d, DateTimeOffset now)
    {
        if (d.Target.CandidateKey != Current.CandidateKey)
        {
            _throughput = null; // measured on another host
        }

        Current = d.Target;
        LastSwitchAt = now;
        LastNewSegmentAt = now;
        _consecutiveSlow = 0;
        _hard = null;
        if (!d.Up)
        {
            LastTroubleAt = now;
        }

        return d;
    }

    private Tier? ChooseLower(IReadOnlyList<Tier> ranked, int cur, Func<string, CandidateProbe?> probes, DateTimeOffset now)
    {
        for (var i = cur + 1; i < ranked.Count; i++)
        {
            var t = ranked[i];
            if (IsBackedOff(t.CandidateKey, now))
            {
                continue;
            }

            if (t.CandidateKey == Current.CandidateKey)
            {
                // same host: what we just measured says whether the smaller rendition fits
                if (_throughput is { } bps && bps >= LadderRanking.HealthyMargin * t.Bitrate)
                {
                    return t;
                }
            }
            else if (LadderRanking.Healthy(t, probes(t.CandidateKey), now, ProbeMaxAgeForSwitch))
            {
                return t;
            }
        }

        // Nothing provably fits: at least drop to the lightest rendition of this stream if it is much lighter.
        return ranked.Where(t => t.CandidateKey == Current.CandidateKey && t.Bitrate < 0.7 * Current.Bitrate)
            .OrderBy(t => t.Bitrate).FirstOrDefault();
    }

    private Tier? ChooseAfterFailure(IReadOnlyList<Tier> ranked, int cur, Func<string, CandidateProbe?> probes, DateTimeOffset now)
    {
        var others = ranked.Where(t => t.CandidateKey != Current.CandidateKey && !IsBackedOff(t.CandidateKey, now)).ToList();
        bool Healthy(Tier t) => LadderRanking.Healthy(t, probes(t.CandidateKey), now, ProbeMaxAgeForSwitch);

        return others.FirstOrDefault(t => IndexOf(ranked, t) > cur && Healthy(t))
               ?? others.FirstOrDefault(Healthy)
               ?? others.FirstOrDefault(t => probes(t.CandidateKey) is not { Ok: false })
               ?? others.FirstOrDefault()
               // a single stream with several renditions: another rendition may still be served
               ?? ranked.FirstOrDefault(t => t.Key != Current.Key && t.CandidateKey == Current.CandidateKey && IndexOf(ranked, t) > cur);
    }

    private static int IndexOf(IReadOnlyList<Tier> ranked, Tier t)
    {
        for (var i = 0; i < ranked.Count; i++)
        {
            if (ranked[i].Key == t.Key)
            {
                return i;
            }
        }

        return -1;
    }
}
