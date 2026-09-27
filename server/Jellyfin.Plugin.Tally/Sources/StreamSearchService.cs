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
/// scoreboard: every 30 seconds, while scores are on and a web page source is enabled, it reads the board (at most a
/// minute old) and queues the games whose slot in <see cref="GameSearchSchedule"/> has come; a viewer's <c>find</c>
/// queues its game at once. Queued games are searched together in one game-driven crawl
/// (<see cref="SourceManager.SearchAsync"/>), and at most one crawl of any kind runs at a time. The regular crawl looks
/// for the same games first (<see cref="SourceManager.WantedGames"/>). Each search is logged in one line.
/// </summary>
public sealed class StreamSearchService : BackgroundService
{
    private static readonly TimeSpan Tick = TimeSpan.FromSeconds(30);
    private static readonly TimeSpan BoardAge = TimeSpan.FromSeconds(60);

    private readonly SourceManager _sources;
    private readonly ScoreboardService _scoreboard;
    private readonly ILogger<StreamSearchService> _logger;
    private readonly SemaphoreSlim _wake = new(0, 1);

    // the games the service has seen (the board, and games viewers asked for), for matching after a crawl
    private readonly ConcurrentDictionary<string, GameInfo> _known = new(StringComparer.Ordinal);
    private volatile IReadOnlyList<GameInfo> _board = Array.Empty<GameInfo>();
    private volatile HashSet<string> _withStream = new(StringComparer.Ordinal);
    private volatile string _reason = string.Empty;

    public StreamSearchService(SourceManager sources, ScoreboardService scoreboard, ILogger<StreamSearchService> logger)
    {
        _sources = sources;
        _scoreboard = scoreboard;
        _logger = logger;
        Schedule = new GameSearchSchedule(TimeProvider.System);
        _sources.WantedGames = WantedNow;
        _sources.Crawled += OnCrawled;
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
        // let the first regular crawl start first
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

            _board = games;
            _withStream = match.Where(kv => kv.Value.Found).Select(kv => kv.Key).ToHashSet(StringComparer.Ordinal);
            Schedule.QueueDue(games, g => match.TryGetValue(g.Id, out var m) && m.Found);
        }

        while (true)
        {
            var (wanted, reason) = Schedule.TakePending();
            if (wanted.Count == 0)
            {
                break;
            }

            var started = DateTimeOffset.UtcNow;
            _reason = reason;
            try
            {
                await _sources.SearchAsync(wanted.Select(WantedGame.From).ToList(), ct).ConfigureAwait(false);
            }
            catch (Exception ex) when (ex is not OperationCanceledException || !ct.IsCancellationRequested)
            {
                _logger.LogWarning(ex, "JellyTV stream search ({Reason}) failed for {Games}", reason, string.Join(", ", wanted.Select(g => WantedGame.From(g).Label)));
                Schedule.Searched(wanted.Select(g => g.Id), started, DateTimeOffset.UtcNow, _ => false, gameDriven: true);
            }
        }

        foreach (var stale in _known.Where(kv => kv.Value.State == "post" || DateTimeOffset.UtcNow - kv.Value.Start > TimeSpan.FromDays(1)).Select(kv => kv.Key).ToList())
        {
            _known.TryRemove(stale, out _);
        }
    }

    /// <summary>What the regular crawl looks for first: the board's games in their search window without a stream.</summary>
    private IReadOnlyList<WantedGame> WantedNow()
    {
        var now = DateTimeOffset.UtcNow;
        var withStream = _withStream;
        return _board
            .Where(g => !withStream.Contains(g.Id) && GameSearchSchedule.LatestSlot(g, now) != null)
            .Select(WantedGame.From)
            .ToList();
    }

    private void OnCrawled(CrawlReport report)
    {
        if (report.Wanted.Count == 0)
        {
            return;
        }

        var games = _known.Values.Select(g => g.Clone()).ToList();
        var match = Match(games, _sources.GetChannels(), id => _sources.GetNowNext(id).Now?.Title);
        Schedule.Searched(report.Wanted.Select(w => w.GameId), report.StartedAt, report.FinishedAt,
            id => match.TryGetValue(id, out var m) && m.Found, report.GameDriven);

        var withStream = new HashSet<string>(_withStream, StringComparer.Ordinal);
        foreach (var (id, m) in match)
        {
            if (m.Found)
            {
                withStream.Add(id);
            }
            else
            {
                withStream.Remove(id);
            }
        }

        _withStream = withStream;

        var perGame = string.Join(", ", report.Wanted.Select(w => w.Label + " " + (match.TryGetValue(w.GameId, out var m) ? m.Streams : 0)));
        _logger.LogInformation(
            "JellyTV stream search ({Kind}): {Count} games wanted ({Games}); {Pages} pages visited ({Targeted} for these games); streams per game: {PerGame}; {Seconds:0.0} s",
            report.GameDriven ? "game-driven, " + _reason : "regular crawl, wanted games first",
            report.Wanted.Count,
            string.Join(", ", report.Wanted.Select(w => w.Label)),
            report.Pages,
            report.TargetedPages,
            perGame,
            (report.FinishedAt - report.StartedAt).TotalSeconds);
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
