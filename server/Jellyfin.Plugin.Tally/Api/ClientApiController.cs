using System;
using System.Collections.Generic;
using System.Linq;
using System.Reflection;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Tally.Client;
using Jellyfin.Plugin.Tally.Models;
using Jellyfin.Plugin.Tally.Scores;
using Jellyfin.Plugin.Tally.Services;
using Jellyfin.Plugin.Tally.Sources;
using MediaBrowser.Controller.Net;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace Jellyfin.Plugin.Tally.Api;

/// <summary>
/// Client API v1 — the versioned contract the native apps (and eventually the web UI) are built against.
/// The models are in Client/BoardModels.cs. Rules of the road: additive changes keep <see cref="Version"/>;
/// clients ignore what they don't know; all decisions (which channel, which events) are made here.
/// </summary>
[ApiController]
[Route("JellyTV/Client/v1")]
[Authorize]
public class ClientApiController : ControllerBase
{
    public const int Version = 1;

    private readonly SourceManager _sourceManager;
    private readonly StreamSigner _signer;
    private readonly UserSettingsStore _settingsStore;
    private readonly IAuthorizationContext _authContext;
    private readonly IEnumerable<IBoardEnricher> _enrichers;
    private readonly EventFeed _events;
    private readonly LiveTvItemIndex _liveTv;
    private readonly Live.LiveLadderService _ladder;

    public ClientApiController(
        SourceManager sourceManager,
        StreamSigner signer,
        UserSettingsStore settingsStore,
        IAuthorizationContext authContext,
        IEnumerable<IBoardEnricher> enrichers,
        EventFeed events,
        LiveTvItemIndex liveTv,
        Live.LiveLadderService ladder)
    {
        _ladder = ladder;
        _sourceManager = sourceManager;
        _signer = signer;
        _settingsStore = settingsStore;
        _authContext = authContext;
        _enrichers = enrichers;
        _events = events;
        _liveTv = liveTv;
    }

    /// <summary>Capability probe. A 404 here means "no JellyTV on this server" — clients hide the section.</summary>
    [HttpGet("info")]
    public IActionResult Info()
    {
        var features = _enrichers.Where(e => e.IsEnabled).Select(e => e.Name).ToList();
        features.AddRange(new[] { "events", "multiview" });
        if (_sourceManager.GetChannels().Any(c => _sourceManager.GetNowNext(c.Id).Now != null))
        {
            features.Add("guide");
        }

        return Ok(new
        {
            apiVersion = Version,
            pluginBuild = typeof(Plugin).Assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion,
            features,
            pollSeconds = 15,
            latestEventId = _events.LatestId
        });
    }

    /// <summary>Everything a client needs to draw JellyTV, in one call.</summary>
    /// <param name="since">Last event id the client has shown; only newer events are returned. Omit on first load to get none (and read latestEventId from /info).</param>
    [HttpGet("board")]
    public async Task<IActionResult> GetBoard([FromQuery] long? since, CancellationToken cancellationToken)
    {
        var auth = await _authContext.GetAuthorizationInfo(Request).ConfigureAwait(false);
        var now = DateTimeOffset.UtcNow;
        var source = _sourceManager.GetChannels();
        var board = new Board { ServerTime = now };
        var context = new BoardContext(auth.User?.Id ?? Guid.Empty, now, board, source);

        foreach (var enricher in _enrichers.Where(e => e.IsEnabled).OrderBy(e => e.Order))
        {
            try
            {
                await enricher.EnrichAsync(context, cancellationToken).ConfigureAwait(false);
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                board.Errors[enricher.Name] = ex.Message; // one module failing must not blank the board
            }
        }

        var byId = source.ToDictionary(c => c.Id, StringComparer.OrdinalIgnoreCase);
        var carrying = new Dictionary<string, GameInfo>(StringComparer.OrdinalIgnoreCase);
        foreach (var g in board.Games)
        {
            g.BackdropPath = Request.PathBase.Value + GameArtService.BackdropPath(g);
            var pick = WatchResolver.Resolve(g, board.Games, byId.ContainsKey);
            if (pick != null)
            {
                g.Watch = ToWatch(byId[pick.Id], g, pick.Kind, now);
                if (pick.Kind != "network")
                {
                    carrying.TryAdd(pick.Id, g);
                }
            }
        }

        board.Channels = source.Select(c => ToChannel(c, carrying.TryGetValue(c.Id, out var g) ? g : null, now)).ToList();

        board.Events = since.HasValue ? _events.Since(since.Value) : new List<BoardEvent>();
        var games = board.Games.ToDictionary(g => g.Id, StringComparer.Ordinal);
        foreach (var e in board.Events.Where(e => e.GameId != null && e.Watch == null))
        {
            e.Watch = games.TryGetValue(e.GameId!, out var g) ? g.Watch : null;
        }

        return Ok(board);
    }

    /// <summary>One channel, fresh — call right before playback.</summary>
    [HttpGet("channels/{id}")]
    public IActionResult GetChannel(string id)
    {
        var c = _sourceManager.GetChannel(_sourceManager.ResolveId(id) ?? id);
        return c == null ? NotFound() : Ok(ToChannel(c, null, DateTimeOffset.UtcNow));
    }

    [HttpGet("settings")]
    public async Task<IActionResult> GetSettings()
    {
        var auth = await _authContext.GetAuthorizationInfo(Request).ConfigureAwait(false);
        if (auth.User == null)
        {
            return Unauthorized();
        }

        var settings = _settingsStore.Get(auth.User.Id);
        if (_sourceManager.GetChannels().Count > 0 && UserSettingsMigrator.MigrateChannelIds(settings, _sourceManager.ResolveId))
        {
            _settingsStore.Save(auth.User.Id, settings);
        }

        return Ok(settings);
    }

    /// <summary>Same per-user store as the web UI: a favorite starred on the phone is starred on the TV.</summary>
    [HttpPut("settings")]
    public async Task<IActionResult> PutSettings([FromBody] JsonObject settings)
    {
        var auth = await _authContext.GetAuthorizationInfo(Request).ConfigureAwait(false);
        if (auth.User == null)
        {
            return Unauthorized();
        }

        _settingsStore.Save(auth.User.Id, settings);
        return NoContent();
    }

    private WatchTarget ToWatch(SourceChannel c, GameInfo game, string confidence, DateTimeOffset now) => new()
    {
        ChannelId = c.Id,
        ChannelName = c.Name,
        LiveTvItemId = _liveTv.Find(c.Name),
        HlsPath = ProxyController.BuildLiveUrl(Request, _signer, c.Id),
        CardPath = Request.PathBase.Value + CardArtService.CardPath(c, game, now),
        Confidence = confidence
    };

    private BoardChannel ToChannel(SourceChannel c, GameInfo? game, DateTimeOffset now)
    {
        var (current, next) = _sourceManager.GetNowNext(c.Id);
        return new BoardChannel
        {
            Id = c.Id,
            Name = c.Name,
            Group = c.Group,
            Logo = string.IsNullOrEmpty(c.LogoUrl) ? null : c.LogoUrl,
            LiveTvItemId = _liveTv.Find(c.Name),
            HlsPath = ProxyController.BuildLiveUrl(Request, _signer, c.Id),
            CardPath = Request.PathBase.Value + CardArtService.CardPath(c, game, now),
            GameId = game?.Id,
            Now = current,
            Next = next,
            Stream = _ladder.Describe(c)
        };
    }
}
