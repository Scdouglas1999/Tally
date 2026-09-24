using System;

namespace Jellyfin.Plugin.Tally.Live;

/// <summary>
/// Where to pick up another stream of the same game. Streams carry no shared clock (PROGRAM-DATE-TIME is rare on
/// scraped streams), so the best estimate is that they run about equally far behind the live action: the new
/// stream is entered at the same distance from its live edge as the content already published ended from the old
/// stream's live edge. A step down (urgent) takes the closest segment that exists now; a step up (the current stream
/// is healthy) rather waits for the next segment than repeat most of one.
/// </summary>
public static class SwitchAlignment
{
    /// <param name="playlist">The new stream's media playlist.</param>
    /// <param name="behindSeconds">How much of the old stream's playlist, counted from its end, was not yet
    /// published when it was left.</param>
    /// <param name="urgent">The viewer's buffer is draining: take something now.</param>
    /// <returns>The media sequence number to publish first; may be the next one, not yet listed.</returns>
    public static long StartSequence(HlsMediaPlaylist playlist, double behindSeconds, bool urgent)
    {
        var segs = playlist.Segments;
        if (segs.Count == 0)
        {
            return playlist.NextSequence;
        }

        var last = segs[^1].Duration > 0 ? segs[^1].Duration : Math.Max(1, playlist.TargetDuration);
        if (!urgent && behindSeconds < 0.5 * last)
        {
            return playlist.NextSequence;
        }

        // start of segment i, counted in seconds back from the live edge
        var best = segs.Count - 1;
        var bestDiff = double.MaxValue;
        var fromEnd = 0.0;
        for (var i = segs.Count - 1; i >= 0; i--)
        {
            fromEnd += segs[i].Duration;
            var diff = Math.Abs(fromEnd - behindSeconds);
            if (diff < bestDiff)
            {
                bestDiff = diff;
                best = i;
            }
        }

        return segs[best].Sequence;
    }
}
