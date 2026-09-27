using System;
using System.Collections.Generic;
using System.Linq;
using Jellyfin.Plugin.Tally.Scores;

namespace Jellyfin.Plugin.Tally.Live;

/// <summary>What the RedZone director knows about one live game at one moment.</summary>
/// <param name="ChannelId">The channel that carries it (its English stream), null when it has none right now.</param>
/// <param name="Points">Both teams' points together: a rise is a score.</param>
public sealed record RedZoneGame(
    string Id,
    string Title,
    string Sport,
    int Heat,
    bool RedZone,
    bool TwoMinuteDrill,
    bool Overtime,
    bool Close,
    int Points,
    string? ChannelId)
{
    /// <summary>Red zone, two-minute drill or overtime: outranks any game that is none of those.</summary>
    public bool Urgent => RedZone || TwoMinuteDrill || Overtime;

    public bool Football => Sport == "football";

    /// <summary>A live game as the director sees it (heat and tags already applied, see <see cref="GameHeat"/>).</summary>
    public static RedZoneGame From(GameInfo g, string? channelId)
    {
        var overtime = g.Tags.Any(t => t is "OVERTIME" or "EXTRA INNINGS" or "EXTRA TIME");
        var close = g.Tags.Any(t => t is "ONE-SCORE GAME" or "CLUTCH TIME" or "LATE & CLOSE" or "ONE-GOAL GAME" or "LATE DRAMA");
        return new RedZoneGame(g.Id, GameSchedule.Title(g), g.Sport, g.Heat, g.Sport == "football" && g.RedZone,
            g.Tags.Contains("TWO-MINUTE DRILL"), overtime, close, (g.Away.Score ?? 0) + (g.Home.Score ?? 0), channelId);
    }
}

/// <summary>A cut the director made, as logged and shown on the admin page.</summary>
public sealed record RedZoneCut(DateTimeOffset At, string? FromGame, string? ToGame, string? ToTitle, string Reason);

/// <summary>
/// Picks the game the RedZone channel shows. Pure: fed a clock and snapshots of the live games (see
/// <see cref="RedZoneGame"/>), it answers with a cut or nothing.
/// <list type="bullet">
/// <item>Only live games with a stream count. A game in its red zone, in a two-minute drill or in overtime outranks
/// any game that is not; then heat; football wins ties against other sports.</item>
/// <item>Another game entering the red zone, scoring or going to overtime is a cut right away (a red-zone entry does
/// not pull the channel away from a game that is itself in the red zone, a two-minute drill or overtime: that one is
/// ranked like any other).</item>
/// <item>Otherwise each game is held <see cref="MinDwell"/>, and left only for a game that outranks it: urgent against
/// not urgent, or at least <see cref="HeatMargin"/> hotter.</item>
/// <item>A score on the game on screen holds it <see cref="ExtraPointHold"/> (the try), then the ranking decides again
/// without waiting out the dwell.</item>
/// </list>
/// </summary>
public sealed class RedZoneDirector
{
    public const int HeatMargin = 15;
    public static readonly TimeSpan ExtraPointHold = TimeSpan.FromSeconds(20);

    /// <summary>How long an event on another game still asks for a cut the director could not make at once (the game on
    /// screen was holding for its extra point).</summary>
    public static readonly TimeSpan TriggerLife = TimeSpan.FromSeconds(30);

    private readonly Dictionary<string, (bool RedZone, bool Overtime, int Points)> _seen = new(StringComparer.Ordinal);
    private readonly Dictionary<string, (string Reason, DateTimeOffset At)> _triggers = new(StringComparer.Ordinal);
    private DateTimeOffset _holdUntil = DateTimeOffset.MinValue;
    private bool _dwellWaived;

    public RedZoneDirector(TimeSpan minDwell)
    {
        MinDwell = minDwell;
    }

    public TimeSpan MinDwell { get; set; }

    /// <summary>The game on screen, null for the slate.</summary>
    public string? CurrentGame { get; private set; }

    public string? CurrentChannel { get; private set; }

    public string? CurrentTitle { get; private set; }

    /// <summary>Why it is on: "red zone", "score", "two-minute drill", "overtime", "close" or "hottest".</summary>
    public string? Reason { get; private set; }

    public DateTimeOffset? Since { get; private set; }

    /// <summary>The next games by rank, best first (at most two): the ones kept warm for an instant cut.</summary>
    public IReadOnlyList<RedZoneGame> Next { get; private set; } = Array.Empty<RedZoneGame>();

    /// <summary>Best first: urgent, then heat, then football, then id (stable).</summary>
    public static List<RedZoneGame> Rank(IEnumerable<RedZoneGame> games)
        => games.Where(g => g.ChannelId != null)
            .OrderByDescending(g => g.Urgent)
            .ThenByDescending(g => g.Heat)
            .ThenByDescending(g => g.Football)
            .ThenBy(g => g.Id, StringComparer.Ordinal)
            .ToList();

    /// <summary>What a game is on for when no event put it there.</summary>
    public static string ReasonOf(RedZoneGame g)
        => g.RedZone ? "red zone" : g.Overtime ? "overtime" : g.TwoMinuteDrill ? "two-minute drill" : g.Close ? "close" : "hottest";

    /// <summary>Takes a snapshot of the live games; returns the cut to make now, or null to stay.</summary>
    public RedZoneCut? Update(DateTimeOffset now, IReadOnlyList<RedZoneGame> games)
    {
        foreach (var g in games)
        {
            if (_seen.TryGetValue(g.Id, out var before))
            {
                var what = g.Points > before.Points ? "score"
                    : g.Overtime && !before.Overtime ? "overtime"
                    : g.RedZone && !before.RedZone ? "red zone"
                    : null;
                if (what != null)
                {
                    Observe(now, g, what);
                }
            }

            _seen[g.Id] = (g.RedZone, g.Overtime, g.Points);
        }

        foreach (var gone in _seen.Keys.Where(id => !games.Any(g => g.Id == id)).ToList())
        {
            _seen.Remove(gone); // over: a game never comes back live
        }

        foreach (var stale in _triggers.Where(kv => now - kv.Value.At > TriggerLife).Select(kv => kv.Key).ToList())
        {
            _triggers.Remove(stale);
        }

        var ranked = Rank(games);
        var current = CurrentGame == null ? null : ranked.FirstOrDefault(g => g.Id == CurrentGame);
        RedZoneCut? cut = null;
        if (current == null)
        {
            // first pick, or the game on screen ended or lost its stream: the best one now, the slate when none
            var best = ranked.FirstOrDefault();
            if (best != null || CurrentGame != null)
            {
                cut = Cut(now, best, best == null ? "no games live" : _triggers.TryGetValue(best.Id, out var t) ? t.Reason : ReasonOf(best));
            }
        }
        else
        {
            CurrentChannel = current.ChannelId;
            CurrentTitle = current.Title;
            if (now >= _holdUntil)
            {
                cut = Decide(now, current, ranked);
            }
        }

        var on = CurrentGame;
        Next = ranked.Where(g => g.Id != on).Take(2).ToList();
        return cut;
    }

    private void Observe(DateTimeOffset now, RedZoneGame g, string what)
    {
        if (g.Id == CurrentGame)
        {
            Reason = what;
            // a score while holding is the try itself: it does not start another hold
            if (what == "score" && now >= _holdUntil)
            {
                _holdUntil = now + ExtraPointHold;
                _dwellWaived = true; // after the try, the ranking decides again at once
            }

            return;
        }

        _triggers[g.Id] = (what, now);
    }

    private RedZoneCut? Decide(DateTimeOffset now, RedZoneGame current, List<RedZoneGame> ranked)
    {
        // an event on another game: straight there (the best-ranked of them when several)
        var triggered = ranked.FirstOrDefault(g => g.Id != current.Id && _triggers.TryGetValue(g.Id, out var t)
            && !(t.Reason == "red zone" && current.Urgent));
        if (triggered != null)
        {
            return Cut(now, triggered, _triggers[triggered.Id].Reason);
        }

        if (!_dwellWaived && now - Since < MinDwell)
        {
            return null;
        }

        var best = ranked.FirstOrDefault(g => g.Id != current.Id);
        if (best == null)
        {
            return null;
        }

        var outranks = best.Urgent && !current.Urgent
                       || (best.Urgent == current.Urgent && best.Heat >= current.Heat + HeatMargin);
        return outranks ? Cut(now, best, ReasonOf(best)) : null;
    }

    private RedZoneCut Cut(DateTimeOffset now, RedZoneGame? to, string reason)
    {
        var cut = new RedZoneCut(now, CurrentGame, to?.Id, to?.Title, reason);
        CurrentGame = to?.Id;
        CurrentChannel = to?.ChannelId;
        CurrentTitle = to?.Title;
        Reason = to == null ? null : reason;
        Since = now;
        _holdUntil = DateTimeOffset.MinValue;
        _dwellWaived = false;
        if (to != null)
        {
            _triggers.Remove(to.Id);
        }

        return cut;
    }
}
