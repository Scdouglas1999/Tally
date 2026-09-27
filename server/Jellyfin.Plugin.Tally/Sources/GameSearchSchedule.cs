using System;
using System.Collections.Generic;
using System.Linq;
using Jellyfin.Plugin.Tally.Scores;

namespace Jellyfin.Plugin.Tally.Sources;

/// <summary>
/// When to search for a game's streams, and the book of searches: which games are queued or being searched, when each
/// was last searched and whether that found anything. No I/O: <see cref="StreamSearchService"/> feeds it the scoreboard
/// and runs the crawls; the clock is a <see cref="TimeProvider"/>, so tests move time by hand.
/// <list type="bullet">
/// <item>A game without a stream is searched at start −15, −5, 0, +3, +8 and +15 minutes, then every 5 minutes while
/// it is live (or, still listed as not started, up to an hour past its start).</item>
/// <item>A slot missed (the server was down, the gap below held it back) is made up once, not once per slot.</item>
/// <item>Game-driven crawls keep at least 2 minutes between the end of one and the start of the next; a viewer's
/// <c>find</c> does not wait for that.</item>
/// <item>A game with a stream is not searched.</item>
/// </list>
/// </summary>
public sealed class GameSearchSchedule
{
    public static readonly IReadOnlyList<TimeSpan> Offsets = new[] { -15, -5, 0, 3, 8, 15 }.Select(m => TimeSpan.FromMinutes(m)).ToList();

    public static readonly TimeSpan LiveInterval = TimeSpan.FromMinutes(5);

    public static readonly TimeSpan MinGap = TimeSpan.FromMinutes(2);

    /// <summary>A game still listed as not started keeps being searched this long past its start.</summary>
    public static readonly TimeSpan LateStart = TimeSpan.FromHours(1);

    /// <summary><c>find</c> answers "none" for this long after a search for the game found nothing.</summary>
    public static readonly TimeSpan NoneFor = TimeSpan.FromSeconds(60);

    private readonly TimeProvider _clock;
    private readonly object _gate = new();
    private readonly Dictionary<string, GameInfo> _pending = new(StringComparer.Ordinal);
    private readonly HashSet<string> _running = new(StringComparer.Ordinal);
    private readonly Dictionary<string, Entry> _entries = new(StringComparer.Ordinal);
    private readonly List<string> _reasons = new();
    private DateTimeOffset? _lastGameCrawlEnd;

    public GameSearchSchedule(TimeProvider clock)
    {
        _clock = clock;
    }

    public DateTimeOffset Now => _clock.GetUtcNow();

    /// <summary>End of the last game-driven crawl.</summary>
    public DateTimeOffset? LastGameCrawlEnd
    {
        get
        {
            lock (_gate)
            {
                return _lastGameCrawlEnd;
            }
        }
    }

    /// <summary>The newest slot at or before <paramref name="now"/>; null before the first one and once the game is over.</summary>
    public static DateTimeOffset? LatestSlot(GameInfo g, DateTimeOffset now)
    {
        if (g.State == "post")
        {
            return null;
        }

        var t = now - g.Start;
        if (t < Offsets[0])
        {
            return null;
        }

        var last = Offsets[^1];
        if (t < last)
        {
            return g.Start + Offsets.Last(o => o <= t);
        }

        if (!KeepsGoing(g, now))
        {
            return g.Start + last;
        }

        var k = (long)((t - last).Ticks / LiveInterval.Ticks);
        return g.Start + last + TimeSpan.FromTicks(LiveInterval.Ticks * k);
    }

    /// <summary>The first slot after <paramref name="now"/>; null when there is none.</summary>
    public static DateTimeOffset? NextSlot(GameInfo g, DateTimeOffset now)
    {
        if (g.State == "post")
        {
            return null;
        }

        var t = now - g.Start;
        var last = Offsets[^1];
        if (t < last)
        {
            return g.Start + Offsets.First(o => o > t);
        }

        var k = (long)((t - last).Ticks / LiveInterval.Ticks) + 1;
        var next = g.Start + last + TimeSpan.FromTicks(LiveInterval.Ticks * k);
        return KeepsGoing(g, next) ? next : null;
    }

    /// <summary>After +15: live, or still listed as not started within <see cref="LateStart"/> of its start.</summary>
    private static bool KeepsGoing(GameInfo g, DateTimeOffset at)
        => g.State == "in" || (g.State == "pre" && at - g.Start <= LateStart);

    /// <summary>
    /// Queues the games whose slot has come (and that have no stream), unless a game-driven crawl is running or the
    /// last one ended less than <see cref="MinGap"/> ago (and none is queued). Returns what it queued.
    /// </summary>
    public IReadOnlyList<GameInfo> QueueDue(IEnumerable<GameInfo> games, Func<GameInfo, bool> hasStream)
    {
        var now = Now;
        lock (_gate)
        {
            // a viewer's find already queued a crawl: the due games ride along, whatever the gap
            if (_running.Count > 0 || (_pending.Count == 0 && _lastGameCrawlEnd is { } end && now - end < MinGap))
            {
                return Array.Empty<GameInfo>();
            }

            var due = games.Where(g => !_pending.ContainsKey(g.Id) && !hasStream(g) && IsDueLocked(g, now)).ToList();
            foreach (var g in due)
            {
                _pending[g.Id] = g;
            }

            if (due.Count > 0)
            {
                _reasons.Add("schedule");
            }

            return due;
        }
    }

    private bool IsDueLocked(GameInfo g, DateTimeOffset now)
    {
        var slot = LatestSlot(g, now);
        if (slot == null)
        {
            return false;
        }

        return !_entries.TryGetValue(g.Id, out var e) || e.LastStartedAt == null || e.LastStartedAt < slot;
    }

    /// <summary>
    /// A viewer asked for a game's stream. "found" when it has one; "searching" when a search covering it is queued
    /// or running (<paramref name="crawlingNow"/>: the games of the crawl running now, whatever started it), or after
    /// queuing one; "none" when a search for it ended less than <see cref="NoneFor"/> ago with nothing (or the game is
    /// over).
    /// </summary>
    public string Find(GameInfo g, bool hasStream, IReadOnlyCollection<string> crawlingNow)
    {
        if (hasStream)
        {
            return "found";
        }

        var now = Now;
        lock (_gate)
        {
            if (_pending.ContainsKey(g.Id) || _running.Contains(g.Id) || crawlingNow.Contains(g.Id))
            {
                return "searching";
            }

            if (g.State == "post")
            {
                return "none";
            }

            if (_entries.TryGetValue(g.Id, out var e) && e.LastFinishedAt is { } done && now - done < NoneFor && !e.LastFound)
            {
                return "none";
            }

            _pending[g.Id] = g;
            _reasons.Add("find");
            return "searching";
        }
    }

    /// <summary>Moves the queue to running and returns it (empty when nothing is queued), with what queued it.</summary>
    public (IReadOnlyList<GameInfo> Games, string Reason) TakePending()
    {
        lock (_gate)
        {
            if (_pending.Count == 0)
            {
                return (Array.Empty<GameInfo>(), string.Empty);
            }

            var games = _pending.Values.ToList();
            _pending.Clear();
            foreach (var g in games)
            {
                _running.Add(g.Id);
            }

            var reason = string.Join('+', _reasons.Distinct(StringComparer.Ordinal));
            _reasons.Clear();
            return (games, reason);
        }
    }

    /// <summary>A crawl that looked for <paramref name="gameIds"/> ended. <paramref name="gameDriven"/>: one of the
    /// schedule's own (it counts for <see cref="MinGap"/>); a regular crawl that looked for them first counts as a
    /// search for them too.</summary>
    public void Searched(IEnumerable<string> gameIds, DateTimeOffset started, DateTimeOffset finished, Func<string, bool> found, bool gameDriven)
    {
        lock (_gate)
        {
            foreach (var id in gameIds)
            {
                _running.Remove(id);
                if (!_entries.TryGetValue(id, out var e))
                {
                    e = new Entry();
                    _entries[id] = e;
                }

                e.LastStartedAt = e.LastStartedAt is { } s && s > started ? s : started;
                e.LastFinishedAt = finished;
                e.LastFound = found(id);
            }

            if (gameDriven)
            {
                _running.Clear();
                _lastGameCrawlEnd = finished;
            }

            foreach (var stale in _entries.Where(kv => finished - kv.Value.LastFinishedAt > TimeSpan.FromDays(1)).Select(kv => kv.Key).ToList())
            {
                _entries.Remove(stale);
            }
        }
    }

    /// <summary>
    /// The board's <c>search</c> for a game without a stream: "searching" while a search covering it is queued or
    /// running, else "waiting" with the time the next one is due (<paramref name="scheduled"/> false: the schedule is
    /// not running, so none is).
    /// </summary>
    public GameSearch Describe(GameInfo g, IReadOnlyCollection<string> crawlingNow, bool scheduled)
    {
        var now = Now;
        lock (_gate)
        {
            _entries.TryGetValue(g.Id, out var e);
            var searching = _pending.ContainsKey(g.Id) || _running.Contains(g.Id) || crawlingNow.Contains(g.Id);
            return new GameSearch
            {
                State = searching ? "searching" : "waiting",
                LastAt = e?.LastFinishedAt,
                NextAt = searching || !scheduled ? null : NextAtLocked(g, now)
            };
        }
    }

    private DateTimeOffset? NextAtLocked(GameInfo g, DateTimeOffset now)
    {
        var slot = IsDueLocked(g, now) ? now : NextSlot(g, now);
        if (slot == null)
        {
            return null;
        }

        var earliest = _lastGameCrawlEnd is { } end ? end + MinGap : slot.Value;
        return slot.Value >= earliest ? slot : earliest;
    }

    private sealed class Entry
    {
        public DateTimeOffset? LastStartedAt { get; set; }

        public DateTimeOffset? LastFinishedAt { get; set; }

        public bool LastFound { get; set; }
    }
}
