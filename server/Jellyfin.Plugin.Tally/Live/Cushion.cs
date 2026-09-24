using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;

namespace Jellyfin.Plugin.Tally.Live;

/// <summary>Where a session starts in its first stream's playlist, and how much of it it holds back.</summary>
/// <param name="Listed">Segments listed in the channel's playlist at once (the player starts on the first).</param>
/// <param name="NextSequence">First upstream segment the session downloads after them.</param>
/// <param name="LeadTarget">Seconds the channel's playlist is kept ahead of real time (what <paramref name="Listed"/>
/// holds); 0 when the session publishes every segment as soon as it has it.</param>
/// <param name="Behind">Seconds from the start of <paramref name="Listed"/> to the stream's live edge: the viewer's whole
/// cushion at tune-in.</param>
/// <param name="Why">Why it starts further back than usual, for the log; null when it does not.</param>
public sealed record StartPlan(IReadOnlyList<HlsSegment> Listed, long NextSequence, double LeadTarget, double Behind, string? Why)
{
    public bool Paced => LeadTarget > 0;
}

/// <summary>
/// The viewer's cushion against a stream that pauses. A viewer can ride out an upstream pause only with content it
/// already has, and everything downstream of the plugin (Jellyfin's remux, which finishes a 3-second segment only when
/// the next one begins and re-reads the channel playlist every few seconds; the app's 15-second live offset over
/// Jellyfin's playlist) starts close to the newest content the plugin listed. So the plugin makes the cushion: for a
/// stream seen publishing in bursts it starts further back from the live edge, lists the usual few segments at once
/// (tune-in is as fast as ever) and holds the rest back, publishing them at real-time pace so the channel's playlist
/// stays <see cref="StartPlan.LeadTarget"/> ahead of real time. Between bursts the held-back segments keep the channel
/// moving; players see a steady stream. A steady stream starts one segment further back than the newest few when its
/// window has room (measured on the live bed: with three 4 s segments listed, a steady channel's viewer on Android TV
/// runs dry about 12 s after the plugin's last new segment, so the "no new segment" failover at 9 s plus the ~3-4 s the
/// remux and the player take to show new content froze the picture for 0.7 s); that segment is held back like the
/// rest, so tune-in is unchanged.
/// </summary>
public static class Cushion
{
    /// <summary>Segments listed at start.</summary>
    public const int InitialSegments = 3;

    /// <summary>Segments a steady stream is started behind the listed ones (held back), when its window has room for
    /// them and one more (the oldest listed segment may be about to leave the upstream's window).</summary>
    public const int SteadyHeldBack = 1;

    /// <summary>What the chain downstream of the plugin eats of a cushion: the remux's unfinished last segment (3 s) and
    /// the remux's and the player's playlist reloads (up to a target duration each).</summary>
    public const double ChainAllowance = 10;

    /// <summary>Never start further back than this, whatever the stream's window offers.</summary>
    public const double MaxBehind = 45;

    public static StartPlan Plan(HlsMediaPlaylist playlist, CadenceSummary? cadence)
    {
        var segs = playlist.Segments;
        var defaultStart = Math.Max(0, segs.Count - InitialSegments);
        var steadyStart = segs.Count >= InitialSegments + SteadyHeldBack + 1 ? segs.Count - InitialSegments - SteadyHeldBack : defaultStart;
        var start = steadyStart;
        string? why = start < defaultStart ? "a steady stream keeps one segment in hand" : null;
        if (cadence is { Irregular: true } c && segs.Count > InitialSegments)
        {
            var want = Math.Min(MaxBehind, c.NeededCushion + ChainAllowance);
            var behind = 0.0;
            for (var i = segs.Count - 1; i >= 0; i--)
            {
                behind += segs[i].Duration;
                start = i;
                if (behind >= want)
                {
                    break;
                }
            }

            start = Math.Min(start, steadyStart);
            why = string.Create(CultureInfo.InvariantCulture, $"it publishes in bursts ({c.Describe()})");
        }

        var listed = segs.Skip(start).Take(InitialSegments).ToList();
        var total = segs.Skip(start).Sum(s => s.Duration);
        var lead = listed.Sum(s => s.Duration);
        var paced = start < defaultStart;
        return new StartPlan(
            listed,
            listed.Count > 0 ? listed[^1].Sequence + 1 : playlist.NextSequence,
            paced ? lead : 0,
            total,
            paced ? why : null);
    }

    /// <summary>When the next held-back segment is due: the moment the channel's playlist would fall below its lead
    /// over real time. <paramref name="published"/> is the content published since <paramref name="start"/>.</summary>
    public static DateTimeOffset NextRelease(DateTimeOffset start, double published, double leadTarget)
        => start + TimeSpan.FromSeconds(published - leadTarget);
}
