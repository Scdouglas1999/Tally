using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;

namespace Jellyfin.Plugin.Tally.Live;

/// <summary>How regularly a stream's playlist gained segments over a stretch of watching.</summary>
/// <param name="Watched">Seconds covered by this summary.</param>
/// <param name="Arrivals">Playlist updates that brought at least one new segment.</param>
/// <param name="Segments">New segments seen.</param>
/// <param name="AvgGap">Average time between updates, seconds (0 when fewer than two).</param>
/// <param name="MaxGap">Longest time between updates, seconds, the wait still open included.</param>
/// <param name="Late">Updates that came more than <see cref="CadenceMeter.LateFactor"/>× a segment's duration after
/// the one before, plus a wait still open that long.</param>
/// <param name="NeededCushion">How far ahead of real time a player must have been to never run dry over this
/// stretch: the most by which elapsed time outran the content that arrived in it. A stream that lists one segment
/// per segment duration needs one segment's worth; one that pauses 15 s needs 15 s.</param>
/// <param name="SegmentDuration">Typical segment duration, seconds.</param>
public sealed record CadenceSummary(double Watched, int Arrivals, int Segments, double AvgGap, double MaxGap, int Late, double NeededCushion, double SegmentDuration)
{
    /// <summary>Cushion needed beyond the one segment any live stream needs: 0 for a steady stream.</summary>
    public double Excess => Math.Max(0, NeededCushion - SegmentDuration);

    /// <summary>Watched long enough to compare with another stream: two segment durations and two updates.</summary>
    public bool Enough => SegmentDuration > 0 && Watched >= 2 * SegmentDuration && Arrivals >= 2;

    /// <summary>Updates came late and a player would have needed at least half a segment more cushion than a steady
    /// stream asks for. One such update in a probe's short watch is enough to say so; while a stream is being played,
    /// the policy asks for more before it acts (<see cref="SwitchPolicy.LateUpdatesForTrouble"/>).</summary>
    public bool Irregular => Late > 0 && Excess >= 0.5 * SegmentDuration;

    public string Describe()
        => Arrivals == 0
            ? string.Create(CultureInfo.InvariantCulture, $"no new segment in {Watched:0}s")
            : string.Create(CultureInfo.InvariantCulture,
                $"updates {(AvgGap > 0 ? $"every {AvgGap:0.0}s avg / " : string.Empty)}{MaxGap:0.0}s max apart for {SegmentDuration:0.#}s segments ({Segments} in {Watched:0}s), {Late} late, needs {NeededCushion:0.0}s cushion");

    /// <summary>Negative when <paramref name="a"/> arrives more steadily than <paramref name="b"/>: fewer late updates,
    /// then less cushion needed beyond one segment.</summary>
    public static int CompareSteadiness(CadenceSummary a, CadenceSummary b)
    {
        var c = a.Late.CompareTo(b.Late);
        return c != 0 ? c : a.Excess.CompareTo(b.Excess);
    }
}

/// <summary>
/// Measures a live playlist's cadence: when each new segment first shows up in it, relative to the segments'
/// durations. Fed with every fetch of the playlist (also the fetches that brought nothing new), so a playlist that
/// stands still and then lists two or three segments at once shows as a long gap followed by a burst. Pure: every
/// call carries its own timestamp. Keeps <see cref="Horizon"/> of history.
/// </summary>
public sealed class CadenceMeter
{
    /// <summary>An update later than this many segment durations after the one before is late.</summary>
    public const double LateFactor = 1.5;

    public static readonly TimeSpan Horizon = TimeSpan.FromMinutes(5);

    private readonly List<Arrival> _arrivals = new();
    private DateTimeOffset _baseline;
    private DateTimeOffset _lastFetch;
    private long _lastSequence = -1;
    private double _lastDuration;

    public CadenceMeter(DateTimeOffset now)
    {
        _baseline = now;
        _lastFetch = now;
    }

    /// <summary>Highest media sequence seen (-1 before the first fetch that listed anything).</summary>
    public long LastSequence => _lastSequence;

    /// <summary>When the playlist last gained a segment (the first fetch, before it ever did).</summary>
    public DateTimeOffset LastUpdateAt => _arrivals.Count > 0 ? _arrivals[^1].At : _baseline;

    /// <summary>
    /// One fetch of the playlist; returns how many segments were new. The first fetch only sets the baseline: what it
    /// lists is backlog, not arrivals. Numbering that goes backwards (the upstream restarted) or jumps far ahead starts
    /// over from this fetch.
    /// </summary>
    public int OnPlaylist(DateTimeOffset now, IReadOnlyList<HlsSegment> segments)
    {
        _lastFetch = now;
        if (segments.Count == 0)
        {
            return 0;
        }

        var newest = segments[^1].Sequence;
        if (_lastSequence < 0 || newest < _lastSequence || newest > _lastSequence + 1000)
        {
            _lastSequence = newest;
            _lastDuration = segments[^1].Duration;
            _arrivals.Clear();
            _baseline = now;
            return 0;
        }

        var fresh = segments.Where(s => s.Sequence > _lastSequence).ToList();
        if (fresh.Count == 0)
        {
            return 0;
        }

        var previous = _arrivals.Count > 0 ? _arrivals[^1].At : _baseline;
        var expected = fresh[0].Duration > 0 ? fresh[0].Duration : _lastDuration;
        // after the baseline the gap is only known to be at least this long: late if even that is too long
        var late = expected > 0 && (now - previous).TotalSeconds > LateFactor * expected;
        _arrivals.Add(new Arrival(now, fresh.Count, fresh.Sum(s => s.Duration), fresh[0].Duration, (now - previous).TotalSeconds, _arrivals.Count > 0, late));
        _lastSequence = fresh[^1].Sequence;
        _lastDuration = fresh[^1].Duration > 0 ? fresh[^1].Duration : _lastDuration;

        var cut = now - Horizon;
        var drop = _arrivals.FindIndex(a => a.At >= cut);
        if (drop > 0)
        {
            _arrivals.RemoveRange(0, drop);
        }

        return fresh.Count;
    }

    /// <summary>The cadence over the last <paramref name="window"/> (or since the baseline, if that is shorter).</summary>
    public CadenceSummary Summarize(DateTimeOffset now, TimeSpan window)
    {
        var end = now > _lastFetch ? now : _lastFetch;
        var from = end - window;
        if (from < _baseline)
        {
            from = _baseline;
        }

        var inWindow = _arrivals.Where(a => a.At >= from).ToList();
        var durations = inWindow.Select(a => a.FirstDuration).Where(d => d > 0).OrderBy(d => d).ToList();
        var segDuration = durations.Count > 0 ? durations[durations.Count / 2] : _lastDuration;

        // X(t) = elapsed since `from` − content that arrived since `from`; the cushion needed is its largest rise.
        var credit = 0.0;
        var lowest = 0.0;
        var needed = 0.0;
        double X(DateTimeOffset t) => (t - from).TotalSeconds - credit;
        foreach (var a in inWindow)
        {
            needed = Math.Max(needed, X(a.At) - lowest);
            credit += a.Seconds;
            lowest = Math.Min(lowest, X(a.At));
        }

        needed = Math.Max(needed, X(end) - lowest);

        var lastAt = _arrivals.Count > 0 ? _arrivals[^1].At : _baseline;
        var open = (end - lastAt).TotalSeconds;
        var openLate = _lastDuration > 0 && open > LateFactor * _lastDuration;
        var gaps = inWindow.Where(a => a.GapKnown).Select(a => a.Gap).ToList();
        // the first update after the baseline: its gap is only known to be at least this long
        var atLeast = inWindow.Where(a => !a.GapKnown).Select(a => a.Gap).DefaultIfEmpty(0).Max();
        return new CadenceSummary(
            Watched: (end - from).TotalSeconds,
            Arrivals: inWindow.Count,
            Segments: inWindow.Sum(a => a.Count),
            AvgGap: gaps.Count > 0 ? gaps.Average() : 0,
            MaxGap: Math.Max(Math.Max(gaps.Count > 0 ? gaps.Max() : 0, atLeast), open),
            Late: inWindow.Count(a => a.Late) + (openLate ? 1 : 0),
            NeededCushion: needed,
            SegmentDuration: segDuration);
    }

    private readonly record struct Arrival(DateTimeOffset At, int Count, double Seconds, double FirstDuration, double Gap, bool GapKnown, bool Late);
}
