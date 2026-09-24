using System;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using MediaBrowser.Controller.Configuration;
using MediaBrowser.Model.Updates;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Tally.Services;

/// <summary>
/// Adds Tally's plugin repository to the server's plugin repositories once, so every way of installing the plugin
/// (the installers, Docker, a manual copy) ends up with Jellyfin's own plugin updates. Once only: the plugin
/// remembers that it did this, and an admin who removes the repository afterwards is never overridden.
/// </summary>
public class PluginRepositoryService : IHostedService
{
    public const string RepositoryName = "Tally";
    public const string ManifestUrl = "https://raw.githubusercontent.com/Scdouglas1999/Tally/main/server/manifest.json";

    private readonly IServerConfigurationManager _serverConfig;
    private readonly ILogger<PluginRepositoryService> _logger;

    public PluginRepositoryService(IServerConfigurationManager serverConfig, ILogger<PluginRepositoryService> logger)
    {
        _serverConfig = serverConfig;
        _logger = logger;
    }

    public Task StartAsync(CancellationToken cancellationToken)
    {
        var plugin = Plugin.Instance;
        if (plugin is null || plugin.Configuration.PluginRepositoryAdded)
        {
            return Task.CompletedTask;
        }

        try
        {
            var repositories = _serverConfig.Configuration.PluginRepositories ?? Array.Empty<RepositoryInfo>();
            var (updated, added) = WithTallyRepository(repositories);
            if (added)
            {
                _serverConfig.Configuration.PluginRepositories = updated;
                _serverConfig.SaveConfiguration();
                _logger.LogInformation("Tally: added the plugin repository {Url} for plugin updates", ManifestUrl);
            }

            plugin.Configuration.PluginRepositoryAdded = true;
            plugin.SaveConfiguration();
        }
        catch (Exception ex)
        {
            // never worth failing startup over; the next start tries again
            _logger.LogWarning(ex, "Tally: could not add the plugin repository {Url}", ManifestUrl);
        }

        return Task.CompletedTask;
    }

    public Task StopAsync(CancellationToken cancellationToken) => Task.CompletedTask;

    /// <summary>The repository list with Tally's appended, unless an entry with its address is already there.</summary>
    public static (RepositoryInfo[] Repositories, bool Added) WithTallyRepository(RepositoryInfo[] repositories)
    {
        if (repositories.Any(r => SameUrl(r.Url, ManifestUrl)))
        {
            return (repositories, false);
        }

        var tally = new RepositoryInfo { Name = RepositoryName, Url = ManifestUrl, Enabled = true };
        return (repositories.Append(tally).ToArray(), true);
    }

    private static bool SameUrl(string? a, string b)
        => a is not null && string.Equals(a.Trim().TrimEnd('/'), b, StringComparison.OrdinalIgnoreCase);
}
