using System;
using Jellyfin.Plugin.Tally.Live;

namespace Jellyfin.Plugin.Tally.Dvr;

/// <summary>
/// Keeps a recording one continuous MPEG-TS timeline, so the finished file has no jumps and the start-over playlist
/// needs no discontinuities. The ladder's playlist is already continuous across source switches; what is left are the
/// breaks the recorder itself sees: a pick-up after a server restart (a new ladder session starts from whatever
/// timestamps the upstream has), a gap after falling behind, a change of channel, or an upstream discontinuity on a
/// single-stream channel. Each is spliced onto the end of what was written with the ladder's own
/// <see cref="TsSplicer"/>: PIDs mapped onto the first segment's, timestamps shifted to continue, counters carried on.
/// </summary>
public sealed class RecordingSplicer
{
    /// <summary>A step in the timeline bigger than this between two segments is a break to join: 1.5 s, less than a
    /// dropped segment (a player would sit through the hole, and a remux would carry it into the file) and more than
    /// the frame or two by which consecutive segments of one source normally differ.</summary>
    private const long JumpTicks = 135000;

    private TsInfo? _canonical;
    private TsInfo? _previous;
    private SpliceMap _map = SpliceMap.Identity;
    private bool _pending;

    /// <summary>Picks up a recording after a restart: the first and last segments written so far.</summary>
    public void Resume(byte[]? first, byte[]? last)
    {
        if (first != null)
        {
            var info = TsSplicer.Analyze(first);
            _canonical = info.IsTs ? info : null;
        }

        if (last != null)
        {
            var info = TsSplicer.Analyze(last);
            _previous = info.IsTs ? info : null;
        }

        _map = SpliceMap.Identity;
        _pending = true;
    }

    /// <summary>The next segment as it should be written. <paramref name="discontinuity"/>: the source says the
    /// timeline breaks here. <paramref name="spliced"/> is false when the break could not be joined (different codecs,
    /// not MPEG-TS): the start-over playlist then marks it.</summary>
    public byte[] Process(byte[] bytes, bool discontinuity, out bool spliced)
    {
        spliced = true;
        var info = TsSplicer.Analyze(bytes);
        if (!info.IsTs)
        {
            spliced = _previous == null;
            return bytes;
        }

        if (_canonical == null)
        {
            _canonical = info;
            _previous = info;
            _map = SpliceMap.Identity;
            _pending = false;
            return bytes;
        }

        var jump = _previous?.EndDts is { } pe && info.StartDts is { } ns
                   && Math.Abs(TsSplicer.Unwrap(TsSplicer.Mod(ns + _map.Offset), pe) - pe) > JumpTicks;
        if (_pending || discontinuity || jump)
        {
            _pending = false;
            if (_previous != null && TsSplicer.Plan(_canonical, info, _previous) is { } plan)
            {
                _map = plan;
            }
            else
            {
                _map = SpliceMap.Identity;
                spliced = false;
            }
        }

        if (!_map.IsIdentity && _previous != null)
        {
            _map = TsSplicer.WithContinuity(_map, info, _previous.LastCc);
        }

        var output = _map.IsIdentity ? bytes : TsSplicer.Rewrite(bytes, _map);
        _previous = _map.IsIdentity ? info : TsSplicer.Analyze(output);
        return output;
    }
}
