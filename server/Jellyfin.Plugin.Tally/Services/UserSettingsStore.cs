using System;
using System.Collections.Generic;
using System.IO;
using System.Text.Json;
using System.Text.Json.Nodes;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Tally.Services;

/// <summary>Per-user JellyTV settings persisted as JSON files in the plugin data folder.
/// This is where per-profile fantasy league config will live later.</summary>
public class UserSettingsStore
{
    private readonly ILogger<UserSettingsStore> _logger;
    private readonly object _lock = new();

    public UserSettingsStore(ILogger<UserSettingsStore> logger)
    {
        _logger = logger;
    }

    private string Dir => Path.Combine(Plugin.Instance!.DataFolderPath, "users");

    public JsonObject Get(Guid userId)
    {
        lock (_lock)
        {
            var path = PathFor(userId);
            if (!File.Exists(path))
            {
                return new JsonObject();
            }

            try
            {
                return JsonNode.Parse(File.ReadAllText(path)) as JsonObject ?? new JsonObject();
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "JellyTV: failed to read user settings for {User}", userId);
                return new JsonObject();
            }
        }
    }

    public void Save(Guid userId, JsonObject settings)
    {
        lock (_lock)
        {
            Directory.CreateDirectory(Dir);
            File.WriteAllText(PathFor(userId), settings.ToJsonString());
        }
    }

    private string PathFor(Guid userId) => Path.Combine(Dir, userId.ToString("N") + ".json");
}
