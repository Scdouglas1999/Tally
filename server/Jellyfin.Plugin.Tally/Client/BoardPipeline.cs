using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Tally.Models;
using Jellyfin.Plugin.Tally.Scores;
using Jellyfin.Plugin.Tally.Services;
using Jellyfin.Plugin.Tally.Sources;

namespace Jellyfin.Plugin.Tally.Client;

/// <summary>What an enricher gets to work with while a board is being built for one request.</summary>
public sealed class BoardContext
{
    public BoardContext(Guid userId, DateTimeOffset now, Board board, IReadOnlyList<SourceChannel> channels)
    {
        UserId = userId;
        Now = now;
        Board = board;
        SourceChannels = channels;
    }

    public Guid UserId { get; }

    public DateTimeOffset Now { get; }

    public Board Board { get; }

    public IReadOnlyList<SourceChannel> SourceChannels { get; }
}

/// <summary>
/// A feature module's hook into the board (architecture §4c). An enricher may attach a namespaced blob to any
/// game (<c>game.Extras["fantasy"]</c>), add a module document (<c>board.Modules["fantasy"]</c>) and publish
/// events under its own source. Scores is the first; fantasy is meant to be the second — without touching
/// the board contract, the controller or the clients' core views.
/// </summary>
public interface IBoardEnricher
{
    /// <summary>Module name: the key for extras/modules, the event source, and the /info feature flag.</summary>
    string Name { get; }

    /// <summary>Lower runs first. Scores is 0; anything that decorates games should be greater.</summary>
    int Order { get; }

    bool IsEnabled { get; }

    Task EnrichAsync(BoardContext context, CancellationToken cancellationToken);
}

/// <summary>Live games from the scoreboard, matched to channels. Heat is still computed (the web UI and
/// stock native apps use it); custom native clients simply ignore it.</summary>
public sealed class ScoresEnricher : IBoardEnricher
{
    private readonly ScoreboardService _scoreboard;
    private readonly SourceManager _sourceManager;
    private readonly EventFeed _events;

    public ScoresEnricher(ScoreboardService scoreboard, SourceManager sourceManager, EventFeed events)
    {
        _scoreboard = scoreboard;
        _sourceManager = sourceManager;
        _events = events;
    }

    public string Name => "scores";

    public int Order => 0;

    public bool IsEnabled => Plugin.Instance?.Configuration.ScoresEnabled ?? true;

    public async Task EnrichAsync(BoardContext context, CancellationToken cancellationToken)
    {
        var games = await _scoreboard.GetGamesAsync(cancellationToken).ConfigureAwait(false);
        var probes = context.SourceChannels
            .Select(c => new ChannelProbe(c.Id, c.Name, _sourceManager.GetNowNext(c.Id).Now?.Title))
            .ToList();
        GameChannelMatcher.Match(games, probes);
        foreach (var g in games)
        {
            GameHeat.Apply(g, context.Now);
        }

        context.Board.Games = games;
        foreach (var e in _scoreboard.Errors)
        {
            context.Board.Errors[e.Key] = e.Value;
        }

        _events.ObserveScores(games, context.Now);
    }
}

/// <summary>Picks the one channel to open for a game.</summary>
public static class WatchResolver
{
    /// <summary>
    /// A channel named after the matchup (or whose programme names it) wins. A broadcaster-only match is
    /// used only when that channel isn't also the broadcaster of another live game — eight regional NFL
    /// games share one "FOX", and guessing wrong is worse than offering nothing.
    /// </summary>
    public static GameChannel? Resolve(GameInfo game, IReadOnlyCollection<GameInfo> allGames, Func<string, bool> channelExists)
    {
        foreach (var gc in game.Channels.Where(c => channelExists(c.Id)))
        {
            if (gc.Kind != "network")
            {
                return gc;
            }

            var contested = game.State == "in" && allGames.Any(o =>
                !ReferenceEquals(o, game) && o.State == "in" && o.Channels.Any(x => x.Id == gc.Id));
            if (!contested)
            {
                return gc;
            }
        }

        return null;
    }
}
