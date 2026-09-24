using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Data.Enums;
using Jellyfin.Plugin.Tally.Scores;
using Jellyfin.Plugin.Tally.Services;
using Jellyfin.Plugin.Tally.Sources;
using MediaBrowser.Common.Configuration;
using MediaBrowser.Common.Net;
using MediaBrowser.Controller;
using MediaBrowser.Controller.Channels;
using MediaBrowser.Controller.Providers;
using MediaBrowser.Model.Channels;
using MediaBrowser.Model.Dto;
using MediaBrowser.Model.Entities;
using MediaBrowser.Model.MediaInfo;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Tally.Channels;

/// <summary>Exposes JellyTV sources as a native Jellyfin channel so every client
/// (Android TV, Roku, Swiftfin...) can browse + play through the normal UI.</summary>
public class TallyChannel : IChannel
{
    private const string GroupPrefix = "group:";

    private readonly SourceManager _sourceManager;
    private readonly CardArtService _cards;
    private readonly IServerApplicationHost _appHost;
    private readonly IConfigurationManager _config;
    private readonly ILogger<TallyChannel> _logger;

    public TallyChannel(SourceManager sourceManager, CardArtService cards, IServerApplicationHost appHost, IConfigurationManager config, ILogger<TallyChannel> logger)
    {
        _sourceManager = sourceManager;
        _cards = cards;
        _appHost = appHost;
        _config = config;
        _logger = logger;
    }

    public string Name => "JellyTV";

    public string Description => "Live streams managed by JellyTV.";

    public string DataVersion => _sourceManager.DataVersion;

    public string HomePageUrl => string.Empty;

    public ChannelParentalRating ParentalRating => ChannelParentalRating.Adult;

    public InternalChannelFeatures GetChannelFeatures()
    {
        return new InternalChannelFeatures
        {
            MediaTypes = new List<ChannelMediaType> { ChannelMediaType.Video },
            ContentTypes = new List<ChannelMediaContentType> { ChannelMediaContentType.Clip },
            MaxPageSize = 500,
            SupportsContentDownloading = false,
            SupportsSortOrderToggle = false,
            AutoRefreshLevels = 0
        };
    }

    public bool IsEnabledFor(string userId) => true;

    public async Task<ChannelItemResult> GetChannelItems(InternalChannelItemQuery query, CancellationToken cancellationToken)
    {
        var channels = _sourceManager.GetChannels();
        var games = await _cards.GetChannelGamesAsync(cancellationToken).ConfigureAwait(false);
        List<ChannelItemInfo> items;

        if (string.IsNullOrEmpty(query.FolderId))
        {
            items = channels
                .GroupBy(c => c.Group)
                .OrderBy(g => g.Key, StringComparer.OrdinalIgnoreCase)
                .Select(g => new ChannelItemInfo
                {
                    Id = GroupPrefix + g.Key,
                    Name = g.Key,
                    Type = ChannelItemType.Folder,
                    FolderType = ChannelFolderType.Container,
                    ImageUrl = Artwork(g.First(), games)
                })
                .ToList();
        }
        else
        {
            var group = query.FolderId.StartsWith(GroupPrefix, StringComparison.Ordinal)
                ? query.FolderId[GroupPrefix.Length..]
                : null;

            items = channels
                .Where(c => group == null || c.Group == group)
                .Select(c => ToItem(c, games))
                .ToList();
        }

        return new ChannelItemResult { Items = items, TotalRecordCount = items.Count };
    }

    public Task<DynamicImageResponse> GetChannelImage(ImageType type, CancellationToken cancellationToken)
    {
        return Task.FromResult(new DynamicImageResponse { HasImage = false });
    }

    public IEnumerable<ImageType> GetSupportedChannelImages() => Array.Empty<ImageType>();

    /// <summary>Jellyfin downloads channel images server-side, so loopback is the one address that always works.</summary>
    private string Artwork(Models.SourceChannel c, IReadOnlyDictionary<string, GameInfo> games)
    {
        games.TryGetValue(c.Id, out var game);
        if (game == null && !string.IsNullOrEmpty(c.LogoUrl))
        {
            return c.LogoUrl;
        }

        var baseUrl = _config.GetNetworkConfiguration().BaseUrl?.Trim('/') ?? string.Empty;
        var prefix = string.IsNullOrEmpty(baseUrl) ? string.Empty : "/" + baseUrl;
        return $"http://127.0.0.1:{_appHost.HttpPort}{prefix}{CardArtService.CardPath(c, game, DateTimeOffset.UtcNow)}";
    }

    private ChannelItemInfo ToItem(Models.SourceChannel c, IReadOnlyDictionary<string, GameInfo> games)
    {
        var (now, _) = _sourceManager.GetNowNext(c.Id);
        games.TryGetValue(c.Id, out var game);
        return new ChannelItemInfo
        {
            Id = c.Id,
            Name = c.Name,
            ImageUrl = Artwork(c, games),
            Type = ChannelItemType.Media,
            MediaType = ChannelMediaType.Video,
            ContentType = ChannelMediaContentType.Clip,
            IsLiveStream = true,
            Overview = now != null ? $"LIVE: {now.Title}" : game != null ? $"{GameSchedule.Title(game)} · {game.League}" : "Live stream",
            Genres = new List<string> { c.Group },
            Tags = new List<string> { c.SourceName },
            MediaSources = new List<MediaSourceInfo>
            {
                new MediaSourceInfo
                {
                    Id = c.Id,
                    Path = c.StreamUrl,
                    Protocol = MediaProtocol.Http,
                    IsRemote = true,
                    RequiredHttpHeaders = c.Headers
                }
            }
        };
    }
}
