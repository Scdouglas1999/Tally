using System;
using System.Collections.Generic;
using System.Linq;
using Jellyfin.Plugin.Tally.Scores;

namespace Jellyfin.Plugin.Tally.Client;

/// <summary>
/// Append-only feed of things worth telling a viewer about, computed once on the server so every client
/// agrees on it. Pull-driven like the scoreboard: observing happens when someone asks for a board, never
/// on a timer. Ids only grow; a client asks for "everything after the last id I showed".
/// </summary>
public sealed class EventFeed
{
    private const int Capacity = 300;

    private readonly object _lock = new();
    private readonly LinkedList<BoardEvent> _events = new();
    private readonly Dictionary<string, (int Away, int Home, string State)> _lastSeen = new(StringComparer.Ordinal);
    private long _nextId = DateTimeOffset.UtcNow.ToUnixTimeSeconds() * 1000; // survives restarts without going backwards

    public long LatestId
    {
        get
        {
            lock (_lock)
            {
                return _events.Last?.Value.Id ?? 0;
            }
        }
    }

    /// <summary>Compares the scoreboard with what was seen last time and records scores and finals.
    /// The first sighting of a game records nothing — a client that just opened must not be told about
    /// every point already on the board.</summary>
    public void ObserveScores(IReadOnlyList<GameInfo> games, DateTimeOffset now)
    {
        lock (_lock)
        {
            foreach (var g in games)
            {
                var current = (g.Away.Score ?? 0, g.Home.Score ?? 0, g.State);
                if (_lastSeen.TryGetValue(g.Id, out var before))
                {
                    if (g.State == "in" && (current.Item1 > before.Away || current.Item2 > before.Home))
                    {
                        var scorer = current.Item1 > before.Away ? g.Away : g.Home;
                        Add(new BoardEvent
                        {
                            Source = "scores", Kind = "score", GameId = g.Id, CreatedAt = now,
                            Title = $"{scorer.ShortName} score" + (string.IsNullOrEmpty(g.LastPlayType) ? string.Empty : $" — {g.LastPlayType}"),
                            Text = $"{g.Away.Abbr} {current.Item1} · {g.Home.Abbr} {current.Item2} · {g.Detail}"
                        });
                    }
                    else if (g.State == "post" && before.State == "in")
                    {
                        Add(new BoardEvent
                        {
                            Source = "scores", Kind = "final", GameId = g.Id, CreatedAt = now,
                            Title = "Final",
                            Text = $"{g.Away.Abbr} {current.Item1} · {g.Home.Abbr} {current.Item2}"
                        });
                    }
                }

                _lastSeen[g.Id] = current;
            }
        }
    }

    /// <summary>For other modules (fantasy…): publish an event under their own source.</summary>
    public void Publish(BoardEvent e)
    {
        lock (_lock)
        {
            Add(e);
        }
    }

    public List<BoardEvent> Since(long afterId, int max = 50)
    {
        lock (_lock)
        {
            return _events.Where(e => e.Id > afterId).TakeLast(max).ToList();
        }
    }

    private void Add(BoardEvent e)
    {
        e.Id = ++_nextId;
        _events.AddLast(e);
        while (_events.Count > Capacity)
        {
            _events.RemoveFirst();
        }
    }
}
