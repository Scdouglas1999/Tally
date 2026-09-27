using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;

namespace Jellyfin.Plugin.Tally.Live;

/// <summary>A cut as the RedZone channel's players got it.</summary>
/// <param name="How">"spliced", "discontinuity" or "slate"; null until the first segment of the new source is served.</param>
/// <param name="Game">The game cut to (null: the slate, or a caller that did not say).</param>
public sealed record RedZoneSwitch(DateTimeOffset At, string? From, string? To, string? Title, string Reason, string? How, string? Game = null);

/// <summary>
/// The RedZone channel's own playlist: segments taken from the live session of the game on screen (from its
/// <see cref="OutputWindow"/> in memory, never from the upstream again) and from the slate when no game is on, all
/// spliced into one continuous stream by <see cref="RedZoneSplicer"/>. A cut happens at a segment boundary, as soon as
/// the new game's session has something listed; until then the old source keeps flowing. The new game is entered at
/// the same distance from its live edge as the old one was left (<see cref="SwitchAlignment"/>), so the players'
/// buffers neither drain nor jump. Tick-driven and synchronous: whoever owns it calls <see cref="Pump"/>.
/// </summary>
public sealed class RedZoneSession
{
    private readonly object _gate = new();
    private readonly List<RedZoneSwitch> _switches = new();
    private readonly RedZoneSplicer _splicer = new();
    private string? _target;
    private string? _targetTitle;
    private string? _targetGame;
    private string _targetReason = string.Empty;
    private bool _hasTarget;
    private string? _source;
    private bool _onSlate;
    private long _cursor = -1;
    private bool _cutPending;
    private bool _fresh = true;
    private DateTimeOffset _slateNextAt;
    private DateTimeOffset _lastProgress;
    private DateTimeOffset _targetSetAt;

    public OutputWindow Window { get; } = new(size: 10);

    /// <summary>The "No games live" slate; without one the channel simply waits for a game.</summary>
    public RedZoneSlate? Slate { get; set; }

    public bool Continuous
    {
        get => _splicer.Continuous;
        set => _splicer.Continuous = value;
    }

    /// <summary>The channel whose segments are being served now (null: the slate, or nothing yet).</summary>
    public string? Source => _source;

    public bool OnSlate => _onSlate;

    /// <summary>The last cuts, oldest first.</summary>
    public IReadOnlyList<RedZoneSwitch> Switches
    {
        get
        {
            lock (_gate)
            {
                return _switches.ToList();
            }
        }
    }

    public RedZoneSwitch? LastSwitch
    {
        get
        {
            lock (_gate)
            {
                return _switches.Count == 0 ? null : _switches[^1];
            }
        }
    }

    /// <summary>What to show: a game's channel, or null for the slate. Takes effect at the next <see cref="Pump"/> that
    /// finds something to cut to. <paramref name="gameId"/> is recorded with the cut (<see cref="RedZoneSwitch.Game"/>).</summary>
    public void Target(string? channelId, string? title, string reason, DateTimeOffset now, string? gameId = null)
    {
        if (_hasTarget && channelId == _target)
        {
            return;
        }

        _hasTarget = true;
        _target = channelId;
        _targetTitle = title;
        _targetGame = channelId == null ? null : gameId;
        _targetReason = reason;
        _targetSetAt = now;
    }

    /// <summary>How long the channel has shown nothing new from the game it is on (a stalled source), or how long the
    /// game it should cut to has had nothing to read (a source that never started). Zero on the slate.</summary>
    public TimeSpan Starved(DateTimeOffset now)
    {
        if (_target != _source && _target != null)
        {
            return now - _targetSetAt;
        }

        return _source == null ? TimeSpan.Zero : now - _lastProgress;
    }

    /// <summary>After an idle spell: nothing listed, the next segment is taken a few back from the live edge. The
    /// timeline carries on from where it stopped, so a returning player never sees it step back.</summary>
    public void Restart()
    {
        Window.Clear();
        _source = null;
        _onSlate = false;
        _hasTarget = false;
        _target = null;
        _cursor = -1;
        _fresh = true;
        _cutPending = true;
    }

    /// <summary>Moves whatever is ready into the channel's playlist. <paramref name="windows"/> gives a channel's live
    /// session window, or null when it has none running. Returns the number of segments published.</summary>
    public int Pump(DateTimeOffset now, Func<string, OutputWindow?> windows)
    {
        if (_hasTarget && (_target != _source || (_target == null && !_onSlate)))
        {
            SwitchSource(now, windows);
        }

        var published = 0;
        if (_source != null)
        {
            var w = windows(_source);
            var listed = w?.Listed() ?? new List<PublishedSegment>();
            if (listed.Count > 0 && _cursor < listed[0].Sequence)
            {
                _cursor = listed[0].Sequence; // fell out of its window (or it restarted): a gap to join
                _cutPending = true;
            }

            foreach (var seg in listed.Where(s => s.Sequence >= _cursor))
            {
                if (!seg.Body.IsCompleted)
                {
                    break; // still downloading: next time
                }

                _cursor = seg.Sequence + 1;
                var bytes = seg.Body.IsCompletedSuccessfully ? seg.Body.Result : null;
                if (bytes == null)
                {
                    _cutPending = true;
                    continue;
                }

                Emit(bytes, seg.Duration, _cutPending || seg.Discontinuity, now);
                published++;
            }
        }
        else if (_onSlate && Slate is { } slate)
        {
            // real time, a segment ahead: players keep playing, and a game cuts in without a long wait
            while (_slateNextAt <= now + TimeSpan.FromSeconds(slate.Duration))
            {
                Emit(slate.Bytes, slate.Duration, cut: true, now);
                _slateNextAt += TimeSpan.FromSeconds(slate.Duration);
                published++;
            }
        }

        return published;
    }

    private void SwitchSource(DateTimeOffset now, Func<string, OutputWindow?> windows)
    {
        if (_target == null)
        {
            Record(now, "slate");
            _source = null;
            _onSlate = true;
            _slateNextAt = now;
            _cutPending = true;
            return;
        }

        var w = windows(_target);
        var listed = w?.Listed() ?? new List<PublishedSegment>();
        if (listed.Count == 0)
        {
            return; // not started yet: the old source (or the slate) keeps flowing
        }

        // As far behind the new game's live edge as the channel was behind the old one's (not yet served). A fresh
        // start takes the last few, like a session's own start (see Cushion); from the slate, the newest one.
        double behind;
        if (_fresh)
        {
            behind = listed.TakeLast(Cushion.InitialSegments).Sum(s => s.Duration) - 0.001;
        }
        else if (_source != null && windows(_source) is { } old)
        {
            behind = old.SecondsAfter(_cursor - 1);
        }
        else
        {
            behind = 0;
        }

        var playlist = new HlsMediaPlaylist
        {
            MediaSequence = listed[0].Sequence,
            Segments = listed.Select(s => new HlsSegment { Sequence = s.Sequence, Duration = s.Duration, Uri = s.Upstream }).ToList()
        };
        var entry = SwitchAlignment.StartSequence(playlist, behind, urgent: true);
        if (listed.FirstOrDefault(x => x.Sequence == entry) is { Body.IsCompleted: false })
        {
            return; // a session just started downloads its first segments in order: the old source flows until then
        }

        Record(now, null);
        _cursor = entry;
        _source = _target;
        _onSlate = false;
        _cutPending = true;
        _lastProgress = now;
    }

    private void Record(DateTimeOffset now, string? how)
    {
        lock (_gate)
        {
            _switches.Add(new RedZoneSwitch(now, _onSlate ? "slate" : _source, _target ?? "slate", _targetTitle, _targetReason, how, _targetGame));
            if (_switches.Count > 30)
            {
                _switches.RemoveAt(0);
            }
        }
    }

    private void Emit(byte[] bytes, double duration, bool cut, DateTimeOffset now)
    {
        var first = _cutPending;
        var o = _splicer.Process(bytes, cut);
        _fresh = false;
        _cutPending = false;
        _lastProgress = now;
        Window.Publish(new PublishedSegment
        {
            Duration = duration,
            Discontinuity = o.Discontinuity,
            Upstream = new Uri("redzone:" + Uri.EscapeDataString(_source ?? "slate")),
            TierKey = _source ?? "slate",
            Body = Task.FromResult<byte[]?>(o.Bytes),
            OutInfo = o.Info
        }, now);

        if (first)
        {
            lock (_gate)
            {
                if (_switches.Count > 0 && _switches[^1].How == null)
                {
                    _switches[^1] = _switches[^1] with { How = o.Discontinuity ? "discontinuity" : "spliced" };
                }
            }
        }
    }
}
