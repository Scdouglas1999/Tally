using System;
using System.IO;
using System.Linq;
using System.Text.Json;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Tally.Dvr;

/// <summary>The DVR's settings, rules and jobs: one JSON file in the plugin's data folder, replaced atomically.</summary>
public sealed class DvrStore
{
    private const int KeepFinished = 300;
    private static readonly JsonSerializerOptions Json = new() { WriteIndented = true };

    private readonly string _path;
    private readonly ILogger _logger;

    public DvrStore(string folder, ILogger logger)
    {
        _path = Path.Combine(folder, "dvr.json");
        _logger = logger;
    }

    public DvrState Load()
    {
        try
        {
            if (File.Exists(_path))
            {
                var state = JsonSerializer.Deserialize<DvrState>(File.ReadAllText(_path), Json);
                if (state != null)
                {
                    state.Settings = state.Settings.Normalized();
                    return state;
                }
            }
        }
        catch (Exception ex) when (ex is IOException or JsonException or UnauthorizedAccessException)
        {
            _logger.LogWarning(ex, "JellyTV DVR: could not read {Path}; starting empty", _path);
            try
            {
                File.Copy(_path, _path + ".bad", overwrite: true);
            }
            catch (IOException)
            {
            }
        }

        return new DvrState();
    }

    public void Save(DvrState state)
    {
        // a long history is not worth keeping; recordings that still have a file always are
        var finished = state.Jobs.Where(j => JobState.IsFinal(j.State) && string.IsNullOrEmpty(j.FilePath))
            .OrderByDescending(j => j.EndedAt ?? j.CreatedAt).Skip(KeepFinished).ToHashSet();
        if (finished.Count > 0)
        {
            state.Jobs.RemoveAll(finished.Contains);
        }

        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(_path)!);
            var tmp = _path + ".tmp";
            File.WriteAllText(tmp, JsonSerializer.Serialize(state, Json));
            File.Move(tmp, _path, overwrite: true);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            _logger.LogWarning(ex, "JellyTV DVR: could not save {Path}", _path);
        }
    }
}
