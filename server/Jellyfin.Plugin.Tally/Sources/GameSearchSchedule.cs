using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using Jellyfin.Plugin.Tally.Scores;

namespace Jellyfin.Plugin.Tally.Sources;

/// <summary>
/// When to search for a game's streams, and the book of searches: which games are queued or being searched, when each
/// was last searched and whether that found anything. No I/O: <see cref="StreamSearchService"/> feeds it the scoreboard
/// and runs the passes; the clock is a <see cref="TimeProvider"/>, so tests move time by hand.
/// <list type="bullet">
/// <item>A game without a stream is searched 5 minutes before its start, at its start, then every 5 minutes until it
/// has one or is final. A game still listed as not started keeps its 5-minute searches for up to 3 hours past its
/// start (a delay); a live one keeps them until it ends.</item>
/// <item>A slot missed (the server was down, the gap below held it back) is made up once, not once per slot.</item>
/// <item>Games due together share one pass. Passes run one at a time, at least <see cref="MinGap"/> apart (end of one
/// to start of the next); a viewer's <c>find</c> does not wait for that gap.</item>
/// <item>A pass the site pushed back on (see <see cref="PassEnded"/>) makes the next one wait 5 minutes, then 10, 20 and
/// 30 while it keeps pushing back; finds wait too. A clean pass ends the back-off.</item>
/// <item>A game with a stream is not searched.</item>
/// </list>
/// </summary>
public sealed class GameSearchSchedule
{
    /// <summary>The first search, this long before the start.</summary>
    public static readonly TimeSpan Lead = TimeSpan.FromMinutes(5);

    /// <summary>From the start on, one search this often.</summary>
    public static readonly TimeSpan Interval = TimeSpan.FromMinutes(5);

    /// <summary>A game still listed as not started keeps being searched this long past its start.</summary>
    public static readonly TimeSpan LateStart = TimeSpan.FromHours(3);

    /// <summary>At least this long between the end of one scheduled pass and the start of the next.</summary>
    public static readonly TimeSpan MinGap = TimeSpan.FromSeconds(60);

    /// <summary><c>find</c> answers with the last search's result for this long after it ended.</summary>
    public static readonly TimeSpan NoneFor = TimeSpan.FromSeconds(60);

    /// <summary>The gap after a pass the site pushed back on: 5 minutes, doubling while it keeps pushing back, up to 30.</summary>
    public static readonly IReadOnlyList<TimeSpan> BackOffSteps = new[] { 5, 10, 20, 30 }.Select(m => TimeSpan.FromMinutes(m)).ToList();

    private readonly TimeProvider _clock;
    private readonly object _gate = new();
    private readonly Dictionary<string, Queued> _pending = new(StringComparer.Ordinal);
    private readonly HashSet<string> _running = new(StringComparer.Ordinal);
    private readonly Dictionary<string, Entry> _entries = new(StringComparer.Ordinal);
    private DateTimeOffset? _lastPassEnd;
    private int _backOffStep;

    public GameSearchSchedule(TimeProvider clock)
    {
        _clock = clock;
    }

    public DateTimeOffset Now => _clock.GetUtcNow();

    /// <summary>The gap after the last pass: <see cref="MinGap"/>, or the back-off while the site pushes back.</summary>
    public TimeSpan Gap
    {
        get
        {
            lock (_gate)
            {
                return GapLocked;
            }
        }
    }

    /// <summary>End of the last pass.</summary>
    public DateTimeOffset? LastPassEnd
    {
        get
        {
            lock (_gate)
            {
                return _lastPassEnd;
            }
        }
    }

    private TimeSpan GapLocked => _backOffStep == 0 ? MinGap : BackOffSteps[_backOffStep - 1];

    /// <summary>The newest slot at or before <paramref name="now"/>; null before the first one and once the game is over.</summary>
    public static DateTimeOffset? LatestSlot(GameInfo g, DateTimeOffset now)
    {
        if (g.State == "post")
        {
            return null;
        }

        var t = now - g.Start;
        if (t < -Lead)
        {
            return null;
        }

        // slot k is at start + k × 5 min, from k = -1 (start -5)
        var k = ((t + Lead).Ticks / Interval.Ticks) - 1;
        var slot = g.Start + TimeSpan.FromTicks(Interval.Ticks * k);
        if (k > 0 && !KeepsGoing(g, slot))
        {
            // a delayed start: its last slot is the last one within LateStart
            return g.Start + TimeSpan.FromTicks(Interval.Ticks * (LateStart.Ticks / Interval.Ticks));
        }

        return slot;
    }

    /// <summary>The first slot after <paramref name="now"/>; null when there is none.</summary>
    public static DateTimeOffset? NextSlot(GameInfo g, DateTimeOffset now)
    {
        if (g.State == "post")
        {
            return null;
        }

        var t = now - g.Start;
        if (t < -Lead)
        {
            return g.Start - Lead;
        }

        var k = (t + Lead).Ticks / Interval.Ticks;
        var next = g.Start + TimeSpan.FromTicks(Interval.Ticks * k);
        return k <= 1 || KeepsGoing(g, next) ? next : null;
    }

    /// <summary>"start -5 min", "start", "start +10 min": which slot a time is, for the log.</summary>
    public static string SlotLabel(GameInfo g, DateTimeOffset slot)
    {
        var m = (int)Math.Round((slot - g.Start).TotalMinutes);
        return m == 0 ? "start" : string.Create(CultureInfo.InvariantCulture, $"start {(m > 0 ? "+" : "-")}{Math.Abs(m)} min");
    }

    /// <summary>Past the start: live, or still listed as not started within <see cref="LateStart"/> of it.</summary>
    private static bool KeepsGoing(GameInfo g, DateTimeOffset at)
        => g.State == "in" || at - g.Start <= LateStart;

    /// <summary>
    /// Queues the games whose slot has come (and that have no stream), unless a pass is running or the gap after the
    /// last one is not over. A viewer's <c>find</c> already queued (and not held back by a back-off) takes due games
    /// along whatever the gap. Returns what it queued.
    /// </summary>
    public IReadOnlyList<GameInfo> QueueDue(IEnumerable<GameInfo> games, Func<GameInfo, bool> hasStream)
    {
        var now = Now;
        lock (_gate)
        {
            if (_running.Count > 0)
            {
                return Array.Empty<GameInfo>();
            }

            var held = HeldUntilLocked(now);
            if (held != null && (_pending.Count == 0 || _backOffStep > 0))
            {
                return Array.Empty<GameInfo>();
            }

            var due = new List<GameInfo>();
            foreach (var g in games)
            {
                if (_pending.ContainsKey(g.Id) || hasStream(g) || !IsDueLocked(g, now, out var slot))
                {
                    continue;
                }

                _pending[g.Id] = new Queued(g, SlotLabel(g, slot));
                due.Add(g);
            }

            return due;
        }
    }

    private bool IsDueLocked(GameInfo g, DateTimeOffset now, out DateTimeOffset slot)
    {
        slot = default;
        if (LatestSlot(g, now) is not { } s)
        {
            return false;
        }

        slot = s;
        return !_entries.TryGetValue(g.Id, out var e) || e.Covered == null || e.Covered < s;
    }

    /// <summary>When the gap after the last pass ends, if it has not yet.</summary>
    private DateTimeOffset? HeldUntilLocked(DateTimeOffset now)
        => _lastPassEnd is { } end && now < end + GapLocked ? end + GapLocked : null;

    /// <summary>True while a back-off holds every pass back.</summary>
    private bool BackingOffLocked(DateTimeOffset now) => _backOffStep > 0 && HeldUntilLocked(now) != null;

    /// <summary>
    /// A viewer asked for a game's stream. "found" when it has one. "none" when a search for it ended less than
    /// <see cref="NoneFor"/> ago with nothing, when the game is over, or while a back-off holds searches back (the game
    /// then rides along with the next pass). Otherwise "searching": a pass covering it is running
    /// (<paramref name="crawlingNow"/>: the games of the pass running now) or queued to run next, now that this call
    /// queued it.
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
            if (_running.Contains(g.Id) || crawlingNow.Contains(g.Id))
            {
                return "searching";
            }

            if (_pending.ContainsKey(g.Id))
            {
                return BackingOffLocked(now) ? "none" : "searching";
            }

            if (g.State == "post")
            {
                return "none";
            }

            if (_entries.TryGetValue(g.Id, out var e) && e.LastFinishedAt is { } done && now - done < NoneFor && !e.LastFound)
            {
                return "none";
            }

            _pending[g.Id] = new Queued(g, "find");
            return BackingOffLocked(now) ? "none" : "searching";
        }
    }

    /// <summary>
    /// Moves the queue to running and returns it with why each game is in it ("start -5 min", "find"), or nothing when
    /// the queue is empty or held back: by a back-off, or (unless a viewer's find is in it) by the gap after the last pass.
    /// </summary>
    public IReadOnlyList<(GameInfo Game, string Why)> TakePending()
    {
        var now = Now;
        lock (_gate)
        {
            if (_pending.Count == 0 || _running.Count > 0)
            {
                return Array.Empty<(GameInfo, string)>();
            }

            if (HeldUntilLocked(now) != null && (_backOffStep > 0 || _pending.Values.All(q => q.Why != "find")))
            {
                return Array.Empty<(GameInfo, string)>();
            }

            var taken = _pending.Values.Select(q => (q.Game, q.Why)).ToList();
            _pending.Clear();
            foreach (var (g, _) in taken)
            {
                _running.Add(g.Id);
                if (!_entries.TryGetValue(g.Id, out var e))
                {
                    e = new Entry();
                    _entries[g.Id] = e;
                }

                // the pass covers the game's newest slot (a find between slots covers none it had not)
                if (LatestSlot(g, now) is { } slot && (e.Covered == null || e.Covered < slot))
                {
                    e.Covered = slot;
                }
            }

            return taken;
        }
    }

    /// <summary>
    /// A pass for <paramref name="gameIds"/> ended. <paramref name="pushedBack"/>: why the site pushed back on it (null
    /// for a clean pass). Returns the back-off now in force when this pass started or changed one (null otherwise), and
    /// whether this pass ended one.
    /// </summary>
    public (TimeSpan? BackOff, bool Recovered) PassEnded(IEnumerable<string> gameIds, DateTimeOffset finished, Func<string, bool> found, string? pushedBack)
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

                e.LastFinishedAt = finished;
                e.LastFound = found(id);
            }

            _running.Clear();
            _lastPassEnd = finished;

            foreach (var stale in _entries.Where(kv => kv.Value.LastFinishedAt is { } at && finished - at > TimeSpan.FromDays(1)).Select(kv => kv.Key).ToList())
            {
                _entries.Remove(stale);
            }

            if (pushedBack != null)
            {
                _backOffStep = Math.Min(_backOffStep + 1, BackOffSteps.Count);
                return (BackOffSteps[_backOffStep - 1], false);
            }

            var recovered = _backOffStep > 0;
            _backOffStep = 0;
            return (null, recovered);
        }
    }

    /// <summary>When the last search covering the game ended (null before the first).</summary>
    public DateTimeOffset? LastSearched(string gameId)
    {
        lock (_gate)
        {
            return _entries.TryGetValue(gameId, out var e) ? e.LastFinishedAt : null;
        }
    }

    /// <summary>
    /// The board's <c>search</c> for a game without a stream: "searching" while a pass covering it runs or is about to
    /// (queued and not held back), else "waiting" with the time the next one is due (<paramref name="scheduled"/>
    /// false: the schedule is not running, so none is).
    /// </summary>
    public GameSearch Describe(GameInfo g, IReadOnlyCollection<string> crawlingNow, bool scheduled)
    {
        var now = Now;
        lock (_gate)
        {
            _entries.TryGetValue(g.Id, out var e);
            var pending = _pending.ContainsKey(g.Id);
            var searching = _running.Contains(g.Id) || crawlingNow.Contains(g.Id) || (pending && !BackingOffLocked(now));
            return new GameSearch
            {
                State = searching ? "searching" : "waiting",
                LastAt = e?.LastFinishedAt,
                NextAt = searching || !scheduled ? null : NextAtLocked(g, now, pending)
            };
        }
    }

    private DateTimeOffset? NextAtLocked(GameInfo g, DateTimeOffset now, bool pending)
    {
        var slot = pending || IsDueLocked(g, now, out _) ? now : NextSlot(g, now);
        if (slot == null)
        {
            return null;
        }

        var held = HeldUntilLocked(now);
        return held is { } h && h > slot ? h : slot;
    }

    private sealed record Queued(GameInfo Game, string Why);

    private sealed class Entry
    {
        /// <summary>The newest slot a pass covered.</summary>
        public DateTimeOffset? Covered { get; set; }

        public DateTimeOffset? LastFinishedAt { get; set; }

        public bool LastFound { get; set; }
    }
}
