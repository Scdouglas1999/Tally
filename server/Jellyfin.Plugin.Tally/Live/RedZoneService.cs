using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json.Serialization;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Tally.Client;
using Jellyfin.Plugin.Tally.Models;
using Jellyfin.Plugin.Tally.Scores;
using Jellyfin.Plugin.Tally.Services;
using Jellyfin.Plugin.Tally.Sources;
using MediaBrowser.Common.Configuration;
using MediaBrowser.Common.Net;
using MediaBrowser.Controller;
using MediaBrowser.Controller.MediaEncoding;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Tally.Live;

/// <summary>What <c>GET /JellyTV/Client/v1/redzone</c> answers: the game on the RedZone channel now.</summary>
public sealed class RedZoneStatus
{
    /// <summary>A game is on (false: the channel shows the "No games live" slate, or is switched off).</summary>
    [JsonPropertyName("active")] public bool Active { get; set; }

    [JsonPropertyName("gameId")] public string? GameId { get; set; }

    [JsonPropertyName("title")] public string? Title { get; set; }

    /// <summary>"red zone", "score", "two-minute drill", "overtime", "close" or "hottest".</summary>
    [JsonPropertyName("reason")] public string? Reason { get; set; }

    /// <summary>When the channel cut to it; null while nobody watches (then this is the game it would open on).</summary>
    [JsonPropertyName("since")] public DateTimeOffset? Since { get; set; }

    /// <summary>The next games by rank, best first.</summary>
    [JsonPropertyName("next")] public List<string> Next { get; set; } = new();

    /// <summary>The channel's last cuts as its players got them, oldest first (2.3; empty while nobody watches). Each
    /// <c>since</c> is when the cut's first segment was listed in the channel's playlist, so a player that sits behind the
    /// live edge can tell which game its own picture shows (it runs that far behind these times). The top-level
    /// <c>since</c> is when the channel decided to cut, which can be a moment earlier.</summary>
    [JsonPropertyName("recent")] public List<RedZoneRecentCut> Recent { get; set; } = new();

    /// <summary>This server's clock when it answered (2.3), so a player can read <c>since</c> times on its own clock.</summary>
    [JsonPropertyName("serverTime")] public DateTimeOffset ServerTime { get; set; } = DateTimeOffset.UtcNow;
}

/// <summary>One cut in <see cref="RedZoneStatus.Recent"/>: from <c>since</c> on, the channel's playlist carries this game
/// (<c>active</c> false: the "No games live" slate).</summary>
public sealed class RedZoneRecentCut
{
    [JsonPropertyName("active")] public bool Active { get; set; }

    [JsonPropertyName("gameId")] public string? GameId { get; set; }

    [JsonPropertyName("title")] public string? Title { get; set; }

    [JsonPropertyName("reason")] public string? Reason { get; set; }

    [JsonPropertyName("since")] public DateTimeOffset Since { get; set; }
}

/// <summary>
/// The "Tally Whip-Around" channel: one full-screen stream that cuts to the hottest live game, put together on this
/// server from the games' own streams, with no transcoding. A viewer's TV plays one stream instead of the two or three
/// of a multiview. People see the name "Whip-Around" only; everything internal keeps "RedZone" (the channel id
/// <c>redzone</c>, <c>kind: "redzone"</c>, the <c>redzone</c> routes, the <c>RedZone*</c> settings and these class
/// names), so installs, favorites and apps keep working.
/// <list type="bullet">
/// <item>Always listed (id <see cref="ChannelId"/>, first in Live TV) once the sources have channels: it re-injects
/// itself into every refresh of <see cref="SourceManager"/>.</item>
/// <item>While someone watches it (a request in the last 45 s, like <see cref="LiveSession"/>) it polls the scoreboard
/// every 10 s, lets <see cref="RedZoneDirector"/> pick the game, keeps that game's live session and the next two's
/// running (warm) so a cut is instant, and serves their segments from memory through <see cref="RedZoneSession"/>. The
/// games' sessions are the same ones their own viewers share: Whip-Around adds no upstream load beyond the games it keeps
/// warm. Unwatched, it does nothing at all.</item>
/// <item>No live game with a stream: the "No games live" slate (<see cref="RedZoneSlate"/>).</item>
/// </list>
/// </summary>
public sealed class RedZoneService : IHostedService, IDisposable
{
    public const string ChannelId = "redzone";
    public const string ChannelName = "Tally Whip-Around";
    public const string ChannelGroup = "Whip-Around";

    /// <summary>The channel's name before 2.3.3. Jellyfin's Live TV item for it (tvg-id <c>redzone</c>, so the same
    /// item) keeps this name until Jellyfin's next guide refresh, and cards drawn before carry it in their address:
    /// both are still recognized as this channel.</summary>
    public const string LegacyChannelName = "Tally RedZone";

    public static readonly TimeSpan PollEvery = TimeSpan.FromSeconds(10);

    /// <summary>A game whose stream has given nothing this long is left (and not picked again for a while).</summary>
    public static readonly TimeSpan StarvedAfter = TimeSpan.FromSeconds(25);

    /// <summary>How far back <see cref="RedZoneStatus.Recent"/> reaches (players sit well under a minute behind the edge).</summary>
    public static readonly TimeSpan RecentFor = TimeSpan.FromMinutes(2);

    /// <summary>At most this many cuts in <see cref="RedZoneStatus.Recent"/>.</summary>
    public const int RecentMax = 6;

    private static readonly TimeSpan FailedFor = TimeSpan.FromMinutes(2);
    private static readonly TimeSpan Tick = TimeSpan.FromMilliseconds(500);

    private readonly SourceManager _sources;
    private readonly LiveLadderService _ladder;
    private readonly ScoreboardService _scoreboard;
    private readonly StreamSigner _signer;
    private readonly Func<string> _loopback;
    private readonly Func<string?> _ffmpeg;
    private readonly ILogger _logger;
    private readonly object _gate = new();
    private readonly SemaphoreSlim _startLock = new(1, 1);
    private readonly ConcurrentDictionary<string, DateTimeOffset> _failing = new(StringComparer.OrdinalIgnoreCase);
    private readonly ConcurrentDictionary<string, Task> _starting = new(StringComparer.OrdinalIgnoreCase);
    private readonly List<RedZoneCut> _cuts = new();
    private RedZoneDirector _director = new(TimeSpan.FromSeconds(60));
    private List<GameInfo> _games = new();
    private Task<List<GameInfo>?>? _poll;
    private DateTimeOffset _nextPoll;
    private Task? _loop;
    private CancellationTokenSource? _cts;
    private Task<RedZoneSlate?>? _slate;
    private long _lastTouchedTicks;
    private DateTimeOffset _startedAt;
    private RedZoneSwitch? _logged;

    public RedZoneService(
        SourceManager sources,
        LiveLadderService ladder,
        ScoreboardService scoreboard,
        StreamSigner signer,
        IServerApplicationHost appHost,
        IConfigurationManager config,
        IMediaEncoder encoder,
        ILogger<RedZoneService> logger)
    {
        _sources = sources;
        _ladder = ladder;
        _scoreboard = scoreboard;
        _signer = signer;
        _logger = logger;
        _loopback = () =>
        {
            var baseUrl = config.GetNetworkConfiguration().BaseUrl?.Trim('/') ?? string.Empty;
            return $"http://127.0.0.1:{appHost.HttpPort}{(baseUrl.Length == 0 ? string.Empty : "/" + baseUrl)}";
        };
        _ffmpeg = () =>
        {
            try
            {
                return encoder.EncoderPath;
            }
            catch (Exception)
            {
                return null;
            }
        };
    }

    /// <summary>The channel is offered (switched on, and live scores are on: it needs them to pick a game).</summary>
    public static bool Enabled
        => (Plugin.Instance?.Configuration.RedZoneEnabled ?? true) && (Plugin.Instance?.Configuration.ScoresEnabled ?? true);

    public static bool IsRedZone(string id) => string.Equals(id, ChannelId, StringComparison.OrdinalIgnoreCase);

    public RedZoneSession Session { get; } = new();

    public bool Running => _loop is { IsCompleted: false };

    public DateTimeOffset LastTouched => new(Interlocked.Read(ref _lastTouchedTicks), TimeSpan.Zero);

    /// <summary>The synthetic channel. Its stream address is the plugin's own signed RedZone playlist on loopback (what
    /// Jellyfin's channel folder plays); everything else addresses it by id.</summary>
    public static SourceChannel Channel(string streamUrl) => new()
    {
        Id = ChannelId,
        LegacyId = ChannelId,
        Name = ChannelName,
        Group = ChannelGroup,
        Kind = ChannelId,
        SourceId = "tally",
        SourceName = "Tally",
        StreamUrl = streamUrl,
        GroupKey = ChannelId
    };

    /// <summary>Makes <paramref name="sources"/> list the RedZone channel after every refresh (while it is
    /// <see cref="Enabled"/>), and now.</summary>
    public static void Install(SourceManager sources, Func<string> streamUrl)
    {
        sources.Synthetic = _ => Enabled ? new[] { Channel(streamUrl()) } : Array.Empty<SourceChannel>();
        sources.Reinject();
    }

    public Task StartAsync(CancellationToken cancellationToken)
    {
        Install(_sources, () => _loopback() + "/JellyTV/Live/" + ChannelId + ".m3u8?s=" + _signer.Sign("live:" + ChannelId, string.Empty));
        if (Enabled)
        {
            // made once, in the background, once Jellyfin has found its ffmpeg: the first viewer with no game on finds
            // it ready
            _ = Task.Delay(TimeSpan.FromSeconds(30), CancellationToken.None).ContinueWith(_ => SlateAsync(), TaskScheduler.Default);
        }

        return Task.CompletedTask;
    }

    public Task StopAsync(CancellationToken cancellationToken)
    {
        _cts?.Cancel();
        return Task.CompletedTask;
    }

    public void Dispose()
    {
        _cts?.Dispose();
        _startLock.Dispose();
    }

    public void Touch() => Interlocked.Exchange(ref _lastTouchedTicks, DateTimeOffset.UtcNow.UtcTicks);

    /// <summary>The channel's playlist; starts the channel when nobody was watching. Waits (up to 15 s) for its first
    /// segment, so a player never opens an empty playlist.</summary>
    public async Task<string> GetPlaylistAsync(Func<long, string> segmentUri, CancellationToken ct)
    {
        Touch();
        if (!Running)
        {
            await StartSessionAsync(ct).ConfigureAwait(false);
        }

        var until = DateTimeOffset.UtcNow + TimeSpan.FromSeconds(15);
        while (Session.Window.Count == 0 && DateTimeOffset.UtcNow < until && Running)
        {
            await Task.Delay(250, ct).ConfigureAwait(false);
        }

        return Session.Window.Render(segmentUri);
    }

    public Task<byte[]?> GetSegmentAsync(long sequence)
    {
        Touch();
        return Session.Window.Get(sequence)?.Body ?? Task.FromResult<byte[]?>(null);
    }

    /// <summary>The game on now (while watched), or the one the channel would open on (asks the scoreboard's cache, like
    /// a board does).</summary>
    public async Task<RedZoneStatus> StatusAsync(CancellationToken ct)
    {
        if (!Enabled)
        {
            return new RedZoneStatus();
        }

        if (Running)
        {
            lock (_gate)
            {
                return new RedZoneStatus
                {
                    Active = _director.CurrentGame != null,
                    GameId = _director.CurrentGame,
                    Title = _director.CurrentTitle,
                    Reason = _director.Reason,
                    Since = _director.Since,
                    Next = _director.Next.Select(g => g.Id).ToList(),
                    Recent = Recent(Session.Switches, DateTimeOffset.UtcNow)
                };
            }
        }

        var games = await _scoreboard.GetGamesAsync(ct).ConfigureAwait(false);
        var ranked = RedZoneDirector.Rank(Snapshot(Match(games), DateTimeOffset.UtcNow));
        var best = ranked.FirstOrDefault();
        return new RedZoneStatus
        {
            Active = best != null,
            GameId = best?.Id,
            Title = best?.Title,
            Reason = best == null ? null : RedZoneDirector.ReasonOf(best),
            Next = ranked.Skip(1).Take(2).Select(g => g.Id).ToList()
        };
    }

    /// <summary>The cuts that reached the channel's playlist (<see cref="RedZoneSwitch.How"/> set), oldest first: those of
    /// the last <see cref="RecentFor"/>, at most <see cref="RecentMax"/>, and always the latest (what is on now).</summary>
    public static List<RedZoneRecentCut> Recent(IReadOnlyList<RedZoneSwitch> switches, DateTimeOffset now)
    {
        var served = switches.Where(s => s.How != null).ToList();
        var recent = served.Where(s => now - s.At <= RecentFor).TakeLast(RecentMax).ToList();
        if (recent.Count == 0 && served.Count > 0)
        {
            recent.Add(served[^1]);
        }

        return recent.Select(s =>
        {
            var slate = s.How == "slate";
            return new RedZoneRecentCut
            {
                Active = !slate,
                GameId = slate ? null : s.Game,
                Title = slate ? null : s.Title,
                Reason = slate ? null : s.Reason,
                Since = s.At
            };
        }).ToList();
    }

    /// <summary>For the admin page (<c>GET /JellyTV/Ladder</c>): the session, the director's last cuts and how each
    /// reached the players.</summary>
    public object Diagnostics()
    {
        lock (_gate)
        {
            var now = DateTimeOffset.UtcNow;
            return new
            {
                enabled = Enabled,
                running = Running,
                startedAt = Running ? _startedAt : (DateTimeOffset?)null,
                lastRequest = LastTouched,
                game = _director.CurrentGame,
                title = _director.CurrentTitle,
                reason = _director.Reason,
                since = _director.Since,
                channel = Session.Source,
                slate = Session.OnSlate,
                slateReady = _slate is { IsCompletedSuccessfully: true, Result: not null },
                next = _director.Next.Select(g => new { id = g.Id, title = g.Title, heat = g.Heat, channel = g.ChannelId }),
                warm = _ladder.Sessions.Where(s => s.Running && s.KeptWarm).Select(s => s.Channel.Name),
                failing = _failing.Where(kv => kv.Value > now).Select(kv => kv.Key),
                nextSequence = Session.Window.NextSequence,
                cuts = _cuts.ToList(),
                switches = Session.Switches
            };
        }
    }

    private async Task StartSessionAsync(CancellationToken ct)
    {
        await _startLock.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            if (Running)
            {
                return;
            }

            var config = Plugin.Instance?.Configuration;
            var now = DateTimeOffset.UtcNow;
            _startedAt = now;
            lock (_gate)
            {
                _director = new RedZoneDirector(TimeSpan.FromSeconds(Math.Clamp(config?.RedZoneMinDwellSeconds ?? 60, 10, 900)));
            }

            Session.Restart();
            Session.Continuous = _ladder.ContinuousMode;
            Session.Slate = _slate is { IsCompletedSuccessfully: true } s ? s.Result : null;
            if (Session.Slate == null)
            {
                _ = SlateAsync(); // not made yet, or making it failed last time: try (again)
            }
            _poll = null;
            _nextPoll = now;
            _cts?.Dispose();
            _cts = new CancellationTokenSource();
            var token = _cts.Token;
            _logger.LogInformation("JellyTV Whip-Around: a viewer tuned in, starting");

            // the first pick before the first playlist: the scoreboard's answer is usually a memory hit
            try
            {
                using var wait = CancellationTokenSource.CreateLinkedTokenSource(ct);
                wait.CancelAfter(TimeSpan.FromSeconds(8));
                Apply(now, await PollAsync(wait.Token).ConfigureAwait(false));
                _nextPoll = now + PollEvery;
            }
            catch (OperationCanceledException) when (!ct.IsCancellationRequested)
            {
            }

            _loop = Task.Run(() => LoopAsync(token), CancellationToken.None);
        }
        finally
        {
            _startLock.Release();
        }
    }

    private async Task LoopAsync(CancellationToken ct)
    {
        try
        {
            while (!ct.IsCancellationRequested)
            {
                var now = DateTimeOffset.UtcNow;
                if (now - LastTouched > LiveSession.IdleTimeout)
                {
                    _logger.LogInformation("JellyTV Whip-Around: idle, stopping after {Minutes:0.0} min", (now - _startedAt).TotalMinutes);
                    break;
                }

                if (Session.Slate == null && _slate is { IsCompletedSuccessfully: true, Result: { } slate })
                {
                    Session.Slate = slate;
                }

                if (_poll == null && now >= _nextPoll)
                {
                    _poll = PollAsync(ct);
                }

                if (_poll is { IsCompleted: true } done)
                {
                    _poll = null;
                    _nextPoll = now + PollEvery;
                    Apply(now, done.IsCompletedSuccessfully ? done.Result : null);
                }

                Warm(ct);
                Session.Pump(now, WindowOf);
                if (Session.LastSwitch is { How: not null } shown && !ReferenceEquals(shown, _logged))
                {
                    _logged = shown;
                    _logger.LogInformation("JellyTV Whip-Around: now showing {What} ({How})", shown.Title ?? "the slate", shown.How);
                }

                if (Session.Starved(now) > StarvedAfter)
                {
                    var channel = _director.CurrentChannel;
                    if (channel != null)
                    {
                        _failing[channel] = now + FailedFor;
                        _logger.LogInformation("JellyTV Whip-Around: {Game}: its stream gave nothing for {Seconds:0} s, leaving it", _director.CurrentTitle, Session.Starved(now).TotalSeconds);
                        Apply(now, null);
                    }
                }

                await Task.Delay(Tick, ct).ConfigureAwait(false);
            }
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "JellyTV Whip-Around: failed");
        }
    }

    /// <summary>The games from the scoreboard (leagues with a live game at most <see cref="PollEvery"/> old), matched to
    /// channels, with heat; null when the scoreboard failed.</summary>
    private async Task<List<GameInfo>?> PollAsync(CancellationToken ct)
    {
        try
        {
            return Match(await _scoreboard.GetLiveGamesAsync(PollEvery, ct).ConfigureAwait(false));
        }
        catch (Exception ex) when (ex is not OperationCanceledException || !ct.IsCancellationRequested)
        {
            _logger.LogDebug(ex, "JellyTV Whip-Around: scoreboard poll failed");
            return null;
        }
    }

    private List<GameInfo> Match(List<GameInfo> games)
    {
        var probes = _sources.GetChannels().Where(c => !c.IsSynthetic)
            .Select(c => new ChannelProbe(c.Id, c.Name, _sources.GetNowNext(c.Id).Now?.Title))
            .ToList();
        GameChannelMatcher.Match(games, probes);
        var now = DateTimeOffset.UtcNow;
        foreach (var g in games)
        {
            GameHeat.Apply(g, now);
        }

        return games;
    }

    /// <summary>The live games of the allowed leagues, each with the channel it plays on (its English stream: the
    /// board's own <c>watch</c> pick), unless that channel is failing.</summary>
    private List<RedZoneGame> Snapshot(List<GameInfo> games, DateTimeOffset now)
    {
        var leagues = ScoreboardService.ParseList(Plugin.Instance?.Configuration.RedZoneLeagues);
        var languages = _sources.GetChannels().Where(c => !c.IsSynthetic)
            .GroupBy(c => c.Id, StringComparer.OrdinalIgnoreCase)
            .ToDictionary(x => x.Key, x => StreamLanguage.Of(x.First()), StringComparer.OrdinalIgnoreCase);
        return games
            .Where(g => g.State == "in" && (leagues.Count == 0 || leagues.Contains(g.LeaguePath) || leagues.Contains(g.League)))
            .Select(g =>
            {
                // English only: RedZone never cuts to a Spanish feed
                var pick = WatchResolver.Resolve(g, games, id => languages.TryGetValue(id, out var l) && l == StreamLanguage.English);
                var usable = pick != null && !(_failing.TryGetValue(pick.Id, out var until) && until > now);
                return RedZoneGame.From(g, usable ? pick!.Id : null);
            })
            .ToList();
    }

    /// <summary>Asks the director with the latest games (<paramref name="fresh"/>, or the last ones when null) and points
    /// the session at its pick.</summary>
    private void Apply(DateTimeOffset now, List<GameInfo>? fresh)
    {
        lock (_gate)
        {
            if (fresh != null)
            {
                _games = fresh;
            }

            _director.MinDwell = TimeSpan.FromSeconds(Math.Clamp(Plugin.Instance?.Configuration.RedZoneMinDwellSeconds ?? 60, 10, 900));
            var cut = _director.Update(now, Snapshot(_games, now));
            if (cut != null)
            {
                _cuts.Add(cut);
                if (_cuts.Count > 30)
                {
                    _cuts.RemoveAt(0);
                }

                _logger.LogInformation("JellyTV Whip-Around: cut to {Game} ({Reason}); next: {Next}", cut.ToTitle ?? "the slate", cut.Reason,
                    string.Join(", ", _director.Next.Select(g => g.Title)));
            }

            Session.Target(_director.CurrentChannel, _director.CurrentTitle, _director.Reason ?? "no games live", now, _director.CurrentGame);
        }
    }

    /// <summary>Keeps the game on screen and the next two running, so a cut finds their segments in memory.</summary>
    private void Warm(CancellationToken ct)
    {
        List<string> wanted;
        lock (_gate)
        {
            wanted = new[] { _director.CurrentChannel, Session.Source }
                .Concat(_director.Next.Select(g => g.ChannelId))
                .Where(id => id != null)
                .Select(id => id!)
                .Distinct(StringComparer.OrdinalIgnoreCase)
                .ToList();
        }

        foreach (var id in wanted)
        {
            var channel = _sources.GetChannel(id);
            if (channel == null || channel.IsSynthetic)
            {
                continue;
            }

            if (_starting.TryGetValue(channel.Id, out var starting) && !starting.IsCompleted)
            {
                continue;
            }

            var session = _ladder.Session(channel);
            if (session.Running)
            {
                _ = session.WarmAsync(ct); // running: only marks it (completes at once)
                continue;
            }

            _starting[channel.Id] = Task.Run(async () =>
            {
                await session.WarmAsync(ct).ConfigureAwait(false);
                if (!session.Running)
                {
                    _failing[channel.Id] = DateTimeOffset.UtcNow + FailedFor;
                    _logger.LogInformation("JellyTV Whip-Around: {Channel}: no stream answered, skipping it for now", channel.Name);
                }
            }, CancellationToken.None);
        }
    }

    private OutputWindow? WindowOf(string channelId)
        => _ladder.FindSession(channelId) is { Running: true, Passthrough: false } s ? s.Window : null;

    private Task<RedZoneSlate?> SlateAsync()
    {
        lock (_gate)
        {
            if (_slate is { IsCompleted: false } || _slate is { IsCompletedSuccessfully: true, Result: not null })
            {
                return _slate;
            }

            var folder = Path.Combine(Plugin.Instance?.DataFolderPath ?? Path.GetTempPath(), "redzone");
            _slate = Task.Run(() => RedZoneSlate.LoadOrCreateAsync(folder, _ffmpeg(), _logger, CancellationToken.None));
            return _slate;
        }
    }
}
