using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Data.Enums;
using Jellyfin.Plugin.Tally.Sources;
using MediaBrowser.Common.Configuration;
using MediaBrowser.Common.Net;
using MediaBrowser.Controller;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Library;
using MediaBrowser.Model.Entities;
using MediaBrowser.Model.Tasks;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Tally.Services;

/// <summary>
/// Keeps native apps' Live TV looking like the Games board. While a game is on, every couple of minutes:
/// (1) points each Live TV channel at a freshly versioned card — Jellyfin's guide refresh only ever sets a
/// channel image once, so this has to be done on the item directly; (2) queues Jellyfin's own guide
/// refresh, which re-reads our playlist + guide: channel numbers follow the heat, programme cards update.
/// Does nothing when no channel is carrying a live game and nothing has changed.
/// </summary>
public sealed class LiveCardRefreshService : BackgroundService
{
    private readonly CardArtService _cards;
    private readonly SourceManager _sourceManager;
    private readonly ILibraryManager _libraryManager;
    private readonly ITaskManager _taskManager;
    private readonly IServerApplicationHost _appHost;
    private readonly IConfigurationManager _config;
    private readonly ILogger<LiveCardRefreshService> _logger;

    private readonly Dictionary<Guid, string> _applied = new();
    private string _lastSignature = string.Empty;

    public LiveCardRefreshService(
        CardArtService cards,
        SourceManager sourceManager,
        ILibraryManager libraryManager,
        ITaskManager taskManager,
        IServerApplicationHost appHost,
        IConfigurationManager config,
        ILogger<LiveCardRefreshService> logger)
    {
        _cards = cards;
        _sourceManager = sourceManager;
        _libraryManager = libraryManager;
        _taskManager = taskManager;
        _appHost = appHost;
        _config = config;
        _logger = logger;
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        await Task.Delay(TimeSpan.FromSeconds(45), stoppingToken).ConfigureAwait(false);

        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                var cfg = Plugin.Instance?.Configuration;
                if (cfg is { LiveCardsEnabled: true, ScoresEnabled: true } && _appHost.CoreStartupHasCompleted)
                {
                    await TickAsync(stoppingToken).ConfigureAwait(false);
                }
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                _logger.LogWarning(ex, "JellyTV: live card refresh failed");
            }

            await Task.Delay(CardArtService.LiveCardInterval, stoppingToken).ConfigureAwait(false);
        }
    }

    private async Task TickAsync(CancellationToken ct)
    {
        var channels = _sourceManager.GetChannels();
        if (channels.Count == 0)
        {
            return;
        }

        var games = await _cards.GetChannelGamesAsync(ct).ConfigureAwait(false);
        var now = DateTimeOffset.UtcNow;

        // what native apps would see: order + each card's version
        var signature = string.Join('|', CardArtService.HeatOrder(channels, games)
            .Select(c => c.Id + "=" + CardArtService.Version(games.TryGetValue(c.Id, out var g) ? g : null, now)));
        if (signature == _lastSignature)
        {
            return;
        }

        _lastSignature = signature;

        var updated = await UpdateChannelImagesAsync(channels, games, now, ct).ConfigureAwait(false);

        var guide = _taskManager.ScheduledTasks.FirstOrDefault(t => string.Equals(t.ScheduledTask.Key, "RefreshGuide", StringComparison.Ordinal));
        if (guide != null && guide.State == TaskState.Idle)
        {
            _taskManager.QueueScheduledTask(guide.ScheduledTask, new TaskOptions());
        }

        _logger.LogDebug("JellyTV: live cards — {Updated} channel images updated, guide refresh queued", updated);
    }

    private async Task<int> UpdateChannelImagesAsync(
        IReadOnlyList<Models.SourceChannel> channels,
        IReadOnlyDictionary<string, Scores.GameInfo> games,
        DateTimeOffset now,
        CancellationToken ct)
    {
        var byName = new Dictionary<string, Models.SourceChannel>(StringComparer.OrdinalIgnoreCase);
        foreach (var c in channels)
        {
            byName.TryAdd(c.Name, c);
        }

        var items = _libraryManager.GetItemList(new InternalItemsQuery { IncludeItemTypes = new[] { BaseItemKind.LiveTvChannel } });
        var baseUrl = _config.GetNetworkConfiguration().BaseUrl?.Trim('/') ?? string.Empty;
        var prefix = string.IsNullOrEmpty(baseUrl) ? string.Empty : "/" + baseUrl;
        var updated = 0;

        foreach (var item in items)
        {
            if (item.Name == null || !byName.TryGetValue(item.Name, out var channel))
            {
                continue; // another tuner's channel — not ours to touch
            }

            games.TryGetValue(channel.Id, out var game);
            if (game == null && !string.IsNullOrEmpty(channel.LogoUrl))
            {
                continue; // keeps its provider logo
            }

            var version = CardArtService.Version(game, now);
            if (_applied.TryGetValue(item.Id, out var had) && had == version)
            {
                continue;
            }

            item.SetImage(
                new ItemImageInfo
                {
                    Path = $"http://127.0.0.1:{_appHost.HttpPort}{prefix}{CardArtService.CardPath(channel, game, now)}",
                    Type = ImageType.Primary,
                    // apps cache images by a tag Jellyfin derives from this timestamp — without it the
                    // server serves the new card but every client keeps showing its cached old one
                    DateModified = DateTime.UtcNow
                },
                0);
            await item.UpdateToRepositoryAsync(ItemUpdateType.ImageUpdate, ct).ConfigureAwait(false);
            _applied[item.Id] = version;
            updated++;
        }

        return updated;
    }
}
