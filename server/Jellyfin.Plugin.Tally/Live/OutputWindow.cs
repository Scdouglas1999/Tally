using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

namespace Jellyfin.Plugin.Tally.Live;

/// <summary>A segment of a channel's continuous playlist.</summary>
public sealed class PublishedSegment
{
    public long Sequence { get; set; }

    public double Duration { get; init; }

    /// <summary>Emit #EXT-X-DISCONTINUITY before it (a switch that could not be spliced, or discontinuity mode).</summary>
    public bool Discontinuity { get; init; }

    /// <summary>Upstream segment it republishes (for logs).</summary>
    public Uri Upstream { get; init; } = null!;

    public string TierKey { get; init; } = string.Empty;

    public int Epoch { get; init; }

    /// <summary>The bytes players get: completes when downloaded (and spliced). Null result = unavailable.</summary>
    public Task<byte[]?> Body { get; init; } = Task.FromResult<byte[]?>(null);

    /// <summary>Layout, counters and time span of the bytes as served; set once <see cref="Body"/> completes.</summary>
    public TsInfo? OutInfo { get; set; }
}

/// <summary>
/// The playlist a channel's players follow: our own media sequence, advancing by one per segment whatever
/// upstream the segment came from, and a sliding window. Target duration never shrinks (players size their
/// reload interval by it).
/// </summary>
public sealed class OutputWindow
{
    private readonly object _gate = new();
    private readonly List<PublishedSegment> _segments = new();
    private readonly int _size;
    private long _nextSequence;
    private long _discontinuitySequence;
    private int _targetDuration;
    private bool _everDiscontinuous;

    public OutputWindow(int size = 10, long firstSequence = 0)
    {
        _size = size;
        _nextSequence = firstSequence;
    }

    public long NextSequence
    {
        get
        {
            lock (_gate)
            {
                return _nextSequence;
            }
        }
    }

    public int Count
    {
        get
        {
            lock (_gate)
            {
                return _segments.Count;
            }
        }
    }

    public DateTimeOffset LastPublishedAt { get; private set; } = DateTimeOffset.MinValue;

    public PublishedSegment? Last
    {
        get
        {
            lock (_gate)
            {
                return _segments.Count == 0 ? null : _segments[^1];
            }
        }
    }

    public PublishedSegment Publish(PublishedSegment segment, DateTimeOffset now)
    {
        lock (_gate)
        {
            segment.Sequence = _nextSequence++;
            _segments.Add(segment);
            _everDiscontinuous |= segment.Discontinuity;
            _targetDuration = Math.Max(_targetDuration, (int)Math.Ceiling(segment.Duration - 0.001));
            while (_segments.Count > _size)
            {
                if (_segments[0].Discontinuity)
                {
                    _discontinuitySequence++;
                }

                _segments.RemoveAt(0);
            }

            LastPublishedAt = now;
            return segment;
        }
    }

    /// <summary>Drops everything listed (a restart after the channel sat idle) — numbering carries on.</summary>
    public void Clear()
    {
        lock (_gate)
        {
            foreach (var s in _segments.Where(s => s.Discontinuity))
            {
                _discontinuitySequence++;
            }

            _segments.Clear();
        }
    }

    public PublishedSegment? Get(long sequence)
    {
        lock (_gate)
        {
            return _segments.FirstOrDefault(s => s.Sequence == sequence);
        }
    }

    public string Render(Func<long, string> segmentUri)
    {
        lock (_gate)
        {
            var sb = new StringBuilder(256 + (_segments.Count * 160));
            sb.Append("#EXTM3U\n#EXT-X-VERSION:3\n");
            sb.Append("#EXT-X-TARGETDURATION:").Append(Math.Max(1, _targetDuration).ToString(CultureInfo.InvariantCulture)).Append('\n');
            sb.Append("#EXT-X-MEDIA-SEQUENCE:").Append((_segments.Count > 0 ? _segments[0].Sequence : _nextSequence).ToString(CultureInfo.InvariantCulture)).Append('\n');
            if (_everDiscontinuous)
            {
                sb.Append("#EXT-X-DISCONTINUITY-SEQUENCE:").Append(_discontinuitySequence.ToString(CultureInfo.InvariantCulture)).Append('\n');
            }

            foreach (var s in _segments)
            {
                if (s.Discontinuity)
                {
                    sb.Append("#EXT-X-DISCONTINUITY\n");
                }

                sb.Append("#EXTINF:").Append(s.Duration.ToString("0.000", CultureInfo.InvariantCulture)).Append(",\n");
                sb.Append(segmentUri(s.Sequence)).Append('\n');
            }

            return sb.ToString();
        }
    }
}
