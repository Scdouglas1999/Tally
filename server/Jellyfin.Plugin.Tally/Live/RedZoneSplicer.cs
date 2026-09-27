using System;
using System.Collections.Generic;
using System.Linq;

namespace Jellyfin.Plugin.Tally.Live;

/// <summary>A segment as the RedZone channel serves it.</summary>
/// <param name="Discontinuity">The layout changed (different codecs, not MPEG-TS): #EXT-X-DISCONTINUITY before it.</param>
public sealed record RedZoneOutput(byte[] Bytes, TsInfo Info, bool Discontinuity);

/// <summary>
/// Keeps the RedZone channel one transport stream while it cuts between games: every segment of a new source (another
/// game, the slate, a source that restarted or broke its own timeline) is spliced onto the end of what was served with
/// <see cref="TsSplicer"/> — PIDs mapped onto the first source's, timestamps shifted to continue, counters carried on —
/// so Jellyfin's copy remux (<c>-codec copy -copyts</c>) reads straight across a cut. When the layouts cannot be merged
/// (different codecs) the new source keeps its own layout, still shifted so no timestamp ever steps back, and the
/// playlist marks the cut with #EXT-X-DISCONTINUITY; its layout is the one later sources are mapped onto.
/// </summary>
public sealed class RedZoneSplicer
{
    /// <summary>A step in the timeline bigger than this inside one source is a break to join (see RecordingSplicer).</summary>
    private const long JumpTicks = 135000;

    private TsInfo? _canonical;
    private TsInfo? _previous;
    private SpliceMap _map = SpliceMap.Identity;

    /// <summary>Continuous timestamps at a cut (the default), or discontinuity mode (development setting
    /// LiveSwitchMode=discontinuity): every cut keeps the new source's layout and is marked.</summary>
    public bool Continuous { get; set; } = true;

    /// <summary>The last segment served, as served.</summary>
    public TsInfo? Previous => _previous;

    /// <param name="cut">The first segment from another source, or after a gap in this one.</param>
    public RedZoneOutput Process(byte[] bytes, bool cut)
    {
        var info = TsSplicer.Analyze(bytes);
        if (!info.IsTs)
        {
            _map = SpliceMap.Identity;
            return new RedZoneOutput(bytes, info, _previous != null && cut);
        }

        if (_canonical == null || _previous == null)
        {
            _canonical = info;
            _previous = info;
            _map = SpliceMap.Identity;
            return new RedZoneOutput(bytes, info, false);
        }

        var jump = _previous.EndDts is { } pe && info.StartDts is { } ns
                   && Math.Abs(TsSplicer.Unwrap(TsSplicer.Mod(ns + _map.Offset), pe) - pe) > JumpTicks;
        var discontinuity = false;
        if (cut || jump)
        {
            if (Continuous && TsSplicer.Plan(_canonical, info, _previous) is { } plan)
            {
                _map = plan;
            }
            else
            {
                _map = ShiftOnly(info, _previous);
                _canonical = info;
                discontinuity = true;
            }
        }

        if (!_map.IsIdentity)
        {
            _map = TsSplicer.WithContinuity(_map, info, _previous.LastCc);
        }

        var output = _map.IsIdentity ? bytes : TsSplicer.Rewrite(bytes, _map);
        var outInfo = _map.IsIdentity ? info : TsSplicer.Analyze(output);
        _previous = outInfo;
        return new RedZoneOutput(output, outInfo, discontinuity);
    }

    /// <summary>The new source as it is (its PIDs, tables and codecs), moved in time to start where the last segment
    /// served ended: the player resets its decoders at the discontinuity, but its clock never runs backwards.</summary>
    public static SpliceMap ShiftOnly(TsInfo next, TsInfo previousOut)
    {
        var pids = next.Streams.Select(s => s.Pid).ToHashSet();
        if (next.PcrPid >= 0)
        {
            pids.Add(next.PcrPid);
        }

        var offset = previousOut.EndDts is { } end && next.StartDts is { } start ? TsSplicer.Mod(end - start) : 0;
        return new SpliceMap
        {
            Offset = offset,
            PidMap = pids.ToDictionary(p => p, p => p),
            Pat = next.PatPacket,
            Pmt = next.PmtPacket,
            InPmtPid = next.PmtPid,
            OutPmtPid = next.PmtPid
        };
    }
}
