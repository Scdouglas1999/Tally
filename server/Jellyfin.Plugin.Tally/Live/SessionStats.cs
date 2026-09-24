using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Text;

namespace Jellyfin.Plugin.Tally.Live;

/// <summary>
/// What a live session did, per minute and in total, for the one-line-a-minute log and <c>GET /JellyTV/Ladder</c>:
/// segments published, how long the upstream playlist and segment fetches took, how far the plugin was ahead of its
/// player (Jellyfin's remux, or whoever reads the channel playlist) and how long that player had to wait for new
/// segments. Pure: every call carries its own timestamp. Not thread-safe on its own; the session locks around it.
/// </summary>
public sealed class SessionStats
{
    public static readonly TimeSpan Interval = TimeSpan.FromMinutes(1);

    private Bucket _minute;
    private readonly Bucket _total;
    private DateTimeOffset? _edgeSince;
    private DateTimeOffset? _firstRequest;
    private DateTimeOffset? _lastRequest;
    private double _servedSeconds;
    private long _maxRequested = -1;

    public SessionStats(DateTimeOffset now)
    {
        _minute = new Bucket(now);
        _total = new Bucket(now);
    }

    /// <summary>Highest segment the player asked for (-1 before its first request).</summary>
    public long MaxRequested => _maxRequested;

    public DateTimeOffset MinuteStart => _minute.Start;

    public bool Due(DateTimeOffset now) => now - _minute.Start >= Interval;

    public void OnPlaylistFetch(double seconds) => Both(b => b.Fetches.Add(seconds));

    public void OnDownload(double seconds, double duration, bool ok)
        => Both(b =>
        {
            if (ok && duration > 0)
            {
                b.Downloads.Add(seconds / duration);
                b.DownloadSeconds.Add(seconds);
            }
            else if (!ok)
            {
                b.DownloadFailures++;
            }
        });

    public void OnSwitch() => Both(b => b.Switches++);

    /// <summary>A segment was listed in the channel's playlist. Ends a wait of a player that had everything.</summary>
    public void OnPublished(DateTimeOffset now, double duration)
    {
        Both(b =>
        {
            b.Published++;
            b.PublishedSeconds += duration;
        });
        if (_edgeSince is { } since)
        {
            var waited = (now - since).TotalSeconds;
            if (PlayerActive(since))
            {
                Both(b => b.EdgeWaits.Add(waited));
            }

            _edgeSince = null;
        }
    }

    /// <summary>The player fetched the channel playlist. When it already asked for every listed segment it is
    /// waiting at the live edge until the next one is published.</summary>
    public void OnPlayerPoll(DateTimeOffset now, long lastListed)
    {
        if (_maxRequested >= 0 && _maxRequested >= lastListed && _edgeSince == null)
        {
            _edgeSince = now;
        }
    }

    /// <summary>The player asked for a segment; <paramref name="waited"/> is how long it waited for its bytes (a
    /// listed segment still downloading).</summary>
    public void OnRequest(DateTimeOffset now, long sequence, double duration, double waited)
    {
        _firstRequest ??= now;
        _lastRequest = now;
        if (sequence > _maxRequested)
        {
            _maxRequested = sequence;
            _servedSeconds += duration;
        }

        Both(b =>
        {
            b.Requests++;
            if (waited > 0.05)
            {
                b.WaitedRequests++;
                b.MaxRequestWait = Math.Max(b.MaxRequestWait, waited);
            }
        });
    }

    /// <summary>Seconds of content the player was given beyond real time since its first request: a lower bound of
    /// what it holds (it started later than its first request). Null before the first request.</summary>
    public double? Lead(DateTimeOffset now) => _firstRequest is { } f ? _servedSeconds - (now - f).TotalSeconds : null;

    /// <summary>A player asked for a segment within the last 15 s (when none has, it stopped watching and the
    /// session is only waiting to go idle: its numbers would describe nobody).</summary>
    public bool PlayerActive(DateTimeOffset now) => _lastRequest is { } r && now - r < TimeSpan.FromSeconds(15);

    /// <summary>Samples, at a publish or a request: listed-but-not-yet-requested seconds, the player's lead and the
    /// seconds held back (the session's reserve). Ignored while no player is active.</summary>
    public void Sample(DateTimeOffset now, double ahead, double reserve)
    {
        if (!PlayerActive(now))
        {
            return;
        }

        var lead = Lead(now);
        Both(b =>
        {
            b.Ahead.Add(ahead);
            b.Reserve.Add(reserve);
            if (lead is { } l)
            {
                b.MinLead = Math.Min(b.MinLead, l);
            }
        });
    }

    /// <summary>The minute's line (and starts the next minute).</summary>
    public string MinuteLine(DateTimeOffset now, CadenceSummary? cadence)
    {
        var line = Describe(_minute, now, cadence);
        _minute = new Bucket(now);
        return line;
    }

    public string TotalLine(DateTimeOffset now, CadenceSummary? cadence) => Describe(_total, now, cadence);

    public object Snapshot(DateTimeOffset now) => new
    {
        lastMinute = Json(_minute, now),
        total = Json(_total, now),
        leadSeconds = Lead(now) is { } l ? Math.Round(l, 1) : (double?)null
    };

    private static object Json(Bucket b, DateTimeOffset now) => new
    {
        seconds = Math.Round((now - b.Start).TotalSeconds),
        published = b.Published,
        publishedSeconds = Math.Round(b.PublishedSeconds, 1),
        switches = b.Switches,
        playlistFetchMs = Stat(b.Fetches, 1000),
        downloadMs = Stat(b.DownloadSeconds, 1000),
        downloadOfDuration = Stat(b.Downloads, 1, 2),
        downloadFailures = b.DownloadFailures,
        aheadSeconds = Stat(b.Ahead, 1, 1),
        reserveSeconds = Stat(b.Reserve, 1, 1),
        minLeadSeconds = double.IsPositiveInfinity(b.MinLead) ? (double?)null : Math.Round(b.MinLead, 1),
        requests = b.Requests,
        requestsThatWaited = b.WaitedRequests,
        maxRequestWaitSeconds = Math.Round(b.MaxRequestWait, 2),
        edgeWaitSeconds = Stat(b.EdgeWaits, 1, 1)
    };

    private static object? Stat(List<double> xs, double scale, int digits = 0)
        => xs.Count == 0 ? null : new { avg = Math.Round(xs.Average() * scale, digits), max = Math.Round(xs.Max() * scale, digits), min = Math.Round(xs.Min() * scale, digits), n = xs.Count };

    private static string Describe(Bucket b, DateTimeOffset now, CadenceSummary? cadence)
    {
        var c = CultureInfo.InvariantCulture;
        var sb = new StringBuilder();
        sb.Append(c, $"{b.Published} segments ({b.PublishedSeconds:0} s) published in {(now - b.Start).TotalSeconds:0} s");
        if (b.Switches > 0)
        {
            sb.Append(c, $", {b.Switches} switch{(b.Switches == 1 ? string.Empty : "es")}");
        }

        if (cadence is { } cad)
        {
            sb.Append("; upstream ").Append(cad.Describe());
        }

        if (b.Fetches.Count > 0)
        {
            sb.Append(c, $"; playlist fetch {b.Fetches.Average() * 1000:0} ms avg / {b.Fetches.Max() * 1000:0} ms max");
        }

        if (b.Downloads.Count > 0)
        {
            sb.Append(c, $"; segment download {b.DownloadSeconds.Average() * 1000:0} ms avg / {b.DownloadSeconds.Max() * 1000:0} ms max ({b.Downloads.Average():0.00}x / {b.Downloads.Max():0.00}x of its duration)");
        }

        if (b.DownloadFailures > 0)
        {
            sb.Append(c, $", {b.DownloadFailures} failed");
        }

        if (b.Reserve.Count > 0 && b.Reserve.Max() > 0)
        {
            sb.Append(c, $"; held back {b.Reserve.Average():0.0} s avg / {b.Reserve.Min():0.0} s min");
        }

        if (b.Requests == 0)
        {
            sb.Append("; no player requests");
            return sb.ToString();
        }

        sb.Append(c, $"; ahead of the player {b.Ahead.Average():0.0} s avg / {b.Ahead.Max():0.0} s max");
        if (!double.IsPositiveInfinity(b.MinLead))
        {
            sb.Append(c, $", player lead over real time {b.MinLead:0.0} s min");
        }

        if (b.EdgeWaits.Count > 0)
        {
            sb.Append(c, $"; player waited at the live edge {b.EdgeWaits.Count}x, {b.EdgeWaits.Average():0.0} s avg / {b.EdgeWaits.Max():0.0} s max");
        }

        if (b.WaitedRequests > 0)
        {
            sb.Append(c, $"; {b.WaitedRequests} of {b.Requests} segment requests waited for their bytes, up to {b.MaxRequestWait:0.0} s");
        }

        return sb.ToString();
    }

    private void Both(Action<Bucket> a)
    {
        a(_minute);
        a(_total);
    }

    private sealed class Bucket
    {
        public Bucket(DateTimeOffset start) => Start = start;

        public DateTimeOffset Start { get; }

        public int Published { get; set; }

        public double PublishedSeconds { get; set; }

        public int Switches { get; set; }

        public List<double> Fetches { get; } = new();

        public List<double> Downloads { get; } = new();

        public List<double> DownloadSeconds { get; } = new();

        public int DownloadFailures { get; set; }

        public List<double> Ahead { get; } = new();

        public List<double> Reserve { get; } = new();

        public double MinLead { get; set; } = double.PositiveInfinity;

        public int Requests { get; set; }

        public int WaitedRequests { get; set; }

        public double MaxRequestWait { get; set; }

        public List<double> EdgeWaits { get; } = new();
    }
}
