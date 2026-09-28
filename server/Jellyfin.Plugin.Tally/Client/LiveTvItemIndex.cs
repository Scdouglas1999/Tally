using System;
using System.Collections.Generic;
using Jellyfin.Data.Enums;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Library;

namespace Jellyfin.Plugin.Tally.Client;

/// <summary>Channel name → the Jellyfin Live TV item a native player can be handed. Jellyfin creates those
/// items on its guide refresh, so a brand-new channel has none for a minute or two.</summary>
public sealed class LiveTvItemIndex
{
    private readonly ILibraryManager _libraryManager;
    private readonly object _lock = new();
    private Dictionary<string, string> _byName = new(StringComparer.OrdinalIgnoreCase);
    private DateTimeOffset _builtAt = DateTimeOffset.MinValue;

    public LiveTvItemIndex(ILibraryManager libraryManager)
    {
        _libraryManager = libraryManager;
    }

    public string? Find(string channelName)
    {
        lock (_lock)
        {
            if (DateTimeOffset.UtcNow - _builtAt > TimeSpan.FromSeconds(45))
            {
                var map = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
                foreach (var item in _libraryManager.GetItemList(new InternalItemsQuery { IncludeItemTypes = new[] { BaseItemKind.LiveTvChannel } }))
                {
                    if (!string.IsNullOrEmpty(item.Name))
                    {
                        map.TryAdd(item.Name, item.Id.ToString("N"));
                    }
                }

                _byName = map;
                _builtAt = DateTimeOffset.UtcNow;
            }

            if (_byName.TryGetValue(channelName, out var id))
            {
                return id;
            }

            // renamed in 2.3.3: Jellyfin's item keeps the old name until its next guide refresh
            return string.Equals(channelName, Live.RedZoneService.ChannelName, StringComparison.OrdinalIgnoreCase)
                && _byName.TryGetValue(Live.RedZoneService.LegacyChannelName, out var legacy) ? legacy : null;
        }
    }
}
