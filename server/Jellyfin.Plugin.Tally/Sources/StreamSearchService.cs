using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Tally.Client;
using Jellyfin.Plugin.Tally.Models;
using Jellyfin.Plugin.Tally.Scores;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Tally.Sources;

/// <summary>
/// Searches for games' streams around the time they start, and whenever a viewer asks (<c>find</c>). Driven by the
/// scoreboard: every few seconds, while scores are on and a web page source is enabled, it reads the board (at most a
/// minute old) and queues the games whose slot in <see cref="GameSearchSchedule"/> has come; a viewer's <c>find</c>
/// queues its game at once. Queued games are searched together in one surgical pass
/// (<see cref="SourceManager.SearchAsync"/>): each source's listing once, a few pages per game, paced. Passes run one
/// at a time; one the site pushes back on makes the next wait (the back-off, logged once as a warning). Each pass is
/// logged in one line with what it read.
/// </summary>
public sealed class StreamSearchService : BackgroundService
{
    private static readonly TimeSpan Tick = TimeSpan.FromSeconds(10);
    private static readonly TimeSpan BoardAge = TimeSpan.FromSeconds(60);

    private readonly SourceManager _sources;
    private readonly ScoreboardService _scoreboard;
    private readonly ILogger<StreamSearchService> _logger;
    private readonly SemaphoreSlim _wake = new(0, 1);

    // the games the service has seen (the board, and games viewers asked for), for matching after a pass
    private readonly ConcurrentDictionary<string, GameInfo> _known = new(StringComparer.Ordinal);

    public StreamSearchService(SourceManager sources, ScoreboardService scoreboard, ILogger<StreamSearchService> logger)
    {
        _sources = sources;
        _scoreboard = scoreboard;
        _logger = logger;
        Schedule = new GameSearchSchedule(TimeProvider.System);
    }

    public GameSearchSchedule Schedule { get; }

    /// <summary>The schedule runs: scores are on and a web page source is enabled.</summary>
    public static bool Scheduled
    {
        get
        {
            var config = Plugin.Instance?.Configuration;
            return config != null && config.ScoresEnabled && config.Sources.Any(s => s.Enabled && s.Kind == SourceKind.Web);
        }
    }

    /// <summary>A viewer's <c>find</c> ("found", "searching" or "none"; see <see cref="GameSearchSchedule.Find"/>).</summary>
    public string Find(GameInfo game, bool hasStream)
    {
        _known[game.Id] = game;
        var state = Schedule.Find(game, hasStream, _sources.CrawlingGameIds);
        if (state == "searching")
        {
            Wake();
        }

        return state;
    }

    /// <summary>The board's <c>search</c> for a game without a stream.</summary>
    public GameSearch Describe(GameInfo game) => Schedule.Describe(game, _sources.CrawlingGameIds, Scheduled);

    /// <summary>A game gets a <c>search</c> on the board when it has no stream and is live or starts within 30 minutes.</summary>
    public static bool Shows(GameInfo g, DateTimeOffset now)
        => g.Watch == null && g.State != "post" && (g.State == "in" || g.Start - now <= TimeSpan.FromMinutes(30));

    /// <summary>
    /// Matches <paramref name="games"/> (fills their channel lists) to <paramref name="channels"/> the way the board
    /// does, and says per game whether it has a stream to watch (the board's <c>watch</c>) and how many streams its
    /// matched channels carry.
    /// </summary>
    public static Dictionary<string, (bool Found, int Streams)> Match(IReadOnlyList<GameInfo> games, IReadOnlyList<SourceChannel> channels, Func<string, string?> nowTitle)
    {
        var probes = channels.Select(c => new ChannelProbe(c.Id, c.Name, nowTitle(c.Id))).ToList();
        GameChannelMatcher.Match(games, probes);
        var byId = channels.GroupBy(c => c.Id, StringComparer.OrdinalIgnoreCase).ToDictionary(x => x.Key, x => x.First(), StringComparer.OrdinalIgnoreCase);
        var result = new Dictionary<string, (bool, int)>(StringComparer.Ordinal);
        foreach (var g in games)
        {
            var found = WatchResolver.Resolve(g, games, byId.ContainsKey) != null;
            var streams = g.Channels.Where(c => c.Kind != "network" && byId.ContainsKey(c.Id))
                .Sum(c => Math.Max(1, byId[c.Id].Candidates.Count));
            result[g.Id] = (found, streams);
        }

        return result;
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        // let the first full-site scan start first
        await Task.Delay(TimeSpan.FromSeconds(20), stoppingToken).ConfigureAwait(false);
        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                await TickAsync(stoppingToken).ConfigureAwait(false);
            }
            catch (Exception ex) when (ex is not OperationCanceledException || !stoppingToken.IsCancellationRequested)
            {
                _logger.LogWarning(ex, "JellyTV stream search: tick failed");
            }

            try
            {
                await _wake.WaitAsync(Tick, stoppingToken).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                return;
            }
        }
    }

    private async Task TickAsync(CancellationToken ct)
    {
        if (Scheduled)
        {
            var games = await _scoreboard.GetGamesAsync(ct, BoardAge).ConfigureAwait(false);
            var match = Match(games, _sources.GetChannels(), id => _sources.GetNowNext(id).Now?.Title);
            foreach (var g in games)
            {
                _known[g.Id] = g;
            }

            Schedule.QueueDue(games, g => match.TryGetValue(g.Id, out var m) && m.Found);
        }

        // one pass per tick at most: the next scheduled one waits for the gap anyway, and a find wakes the loop
        var taken = Schedule.TakePending();
        if (taken.Count > 0)
        {
            await RunPassAsync(taken, ct).ConfigureAwait(false);
        }

        foreach (var stale in _known.Where(kv => kv.Value.State == "post" || DateTimeOffset.UtcNow - kv.Value.Start > TimeSpan.FromDays(1)).Select(kv => kv.Key).ToList())
        {
            _known.TryRemove(stale, out _);
        }
    }

    private async Task RunPassAsync(IReadOnlyList<(GameInfo Game, string Why)> taken, CancellationToken ct)
    {
        var wanted = taken.Select(t => WantedGame.From(t.Game)).ToList();
        var reasons = string.Join('+', taken.Select(t => t.Why == "find" ? "find" : "schedule").Distinct(StringComparer.Ordinal));
        CrawlReport report;
        try
        {
            report = await _sources.SearchAsync(wanted, ct).ConfigureAwait(false);
        }
        catch (Exception ex) when (ex is not OperationCanceledException || !ct.IsCancellationRequested)
        {
            _logger.LogWarning(ex, "JellyTV stream search ({Reason}) failed for {Games}", reasons, string.Join(", ", wanted.Select(w => w.Label)));
            Schedule.PassEnded(wanted.Select(w => w.GameId), DateTimeOffset.UtcNow, _ => false, null);
            return;
        }

        var games = _known.Values.Select(g => g.Clone()).ToList();
        var match = Match(games, _sources.GetChannels(), id => _sources.GetNowNext(id).Now?.Title);
        bool Found(string id) => match.TryGetValue(id, out var m) && m.Found;
        var (backOff, recovered) = Schedule.PassEnded(wanted.Select(w => w.GameId), DateTimeOffset.UtcNow, Found, report.PushedBack);

        var label = taken.ToDictionary(t => t.Game.Id, t => t.Why, StringComparer.Ordinal);
        _logger.LogInformation(
            "JellyTV stream search pass ({Reason}): {Count} games ({Games}); listing read {Listing}x; pages per game: {PerGame}; {Requests} requests, at most {InFlight} in flight, {Failures} failed; streams per game: {Streams}; {Seconds:0.0} s",
            reasons,
            wanted.Count,
            string.Join(", ", wanted.Select(w => w.Label + " at " + label[w.GameId])),
            report.ListingReads,
            string.Join(", ", wanted.Select(w => w.Label + " " + report.PagesPerGame.GetValueOrDefault(w.GameId))),
            report.Requests,
            report.MaxInFlight,
            report.Failures,
            string.Join(", ", wanted.Select(w => w.Label + " " + (match.TryGetValue(w.GameId, out var m) ? m.Streams : 0))),
            (report.FinishedAt - report.StartedAt).TotalSeconds);

        if (backOff is { } wait)
        {
            _logger.LogWarning("JellyTV stream search: the site pushed back ({Why}); the pass stopped and the next one waits {Minutes} min", report.PushedBack, (int)wait.TotalMinutes);
        }
        else if (recovered)
        {
            _logger.LogInformation("JellyTV stream search: a clean pass; searches are back to their schedule");
        }
    }

    private void Wake()
    {
        try
        {
            _wake.Release();
        }
        catch (SemaphoreFullException)
        {
            // already awake
        }
    }
}
