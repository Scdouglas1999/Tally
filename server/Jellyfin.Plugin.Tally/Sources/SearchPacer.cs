using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;

namespace Jellyfin.Plugin.Tally.Sources;

/// <summary>
/// Spaces the requests of game-driven searches, across passes and viewers' finds alike: at most
/// <see cref="MaxInFlight"/> at once, and at least <see cref="Gap"/> between the starts of two requests to the same
/// host. It also remembers which pages answered 200, so a pass can tell a 403 on a page that used to answer (the site
/// pushing back) from a page that never did.
/// </summary>
public sealed class SearchPacer
{
    private const int MaxRemembered = 4096;

    private readonly SemaphoreSlim _slots;
    private readonly object _gate = new();
    private readonly Dictionary<string, DateTimeOffset> _nextStart = new(StringComparer.OrdinalIgnoreCase);
    private readonly ConcurrentDictionary<string, byte> _answered = new(StringComparer.OrdinalIgnoreCase);
    private int _inFlight;

    public SearchPacer(TimeSpan gap, int maxInFlight = 2)
    {
        Gap = gap;
        MaxInFlight = maxInFlight;
        _slots = new SemaphoreSlim(maxInFlight, maxInFlight);
    }

    /// <summary>The pacing the plugin uses: 2 requests in flight, 300 ms between two to the same host.</summary>
    public static SearchPacer Default() => new(TimeSpan.FromMilliseconds(300), 2);

    public TimeSpan Gap { get; }

    public int MaxInFlight { get; }

    /// <summary>Requests under way right now.</summary>
    public int InFlight => Volatile.Read(ref _inFlight);

    /// <summary>Waits for a free slot and the host's gap; dispose the result when the request is done.</summary>
    public async Task<IDisposable> EnterAsync(Uri uri, CancellationToken ct)
    {
        await _slots.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            TimeSpan wait;
            lock (_gate)
            {
                var now = DateTimeOffset.UtcNow;
                var at = _nextStart.TryGetValue(uri.Host, out var next) && next > now ? next : now;
                _nextStart[uri.Host] = at + Gap;
                wait = at - now;
            }

            if (wait > TimeSpan.Zero)
            {
                await Task.Delay(wait, ct).ConfigureAwait(false);
            }
        }
        catch
        {
            _slots.Release();
            throw;
        }

        Interlocked.Increment(ref _inFlight);
        return new Slot(this);
    }

    /// <summary>The page answered 200.</summary>
    public void Answered(string pageKey)
    {
        if (_answered.Count >= MaxRemembered)
        {
            _answered.Clear();
        }

        _answered.TryAdd(pageKey, 0);
    }

    /// <summary>True when the page answered 200 before.</summary>
    public bool AnsweredBefore(string pageKey) => _answered.ContainsKey(pageKey);

    private void Leave()
    {
        Interlocked.Decrement(ref _inFlight);
        _slots.Release();
    }

    private sealed class Slot : IDisposable
    {
        private SearchPacer? _pacer;

        public Slot(SearchPacer pacer) => _pacer = pacer;

        public void Dispose() => Interlocked.Exchange(ref _pacer, null)?.Leave();
    }
}
