using System;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Tally.Services;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Tally.Sources;

/// <summary>Periodically refreshes playlists + EPG data in the background.</summary>
public class RefreshService : BackgroundService
{
    private readonly SourceManager _sourceManager;
    private readonly UpstreamFetcher _fetcher;
    private readonly ILogger<RefreshService> _logger;

    public RefreshService(SourceManager sourceManager, UpstreamFetcher fetcher, ILogger<RefreshService> logger)
    {
        _sourceManager = sourceManager;
        _fetcher = fetcher;
        _logger = logger;
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        // Give the rest of the server a moment to come up.
        await Task.Delay(TimeSpan.FromSeconds(10), stoppingToken).ConfigureAwait(false);

        try
        {
            // Native Live TV gives a stream one short probe — have the proxy ready before anyone presses play.
            await _fetcher.WarmAsync(stoppingToken).ConfigureAwait(false);
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            _logger.LogWarning(ex, "JellyTV: proxy warm-up failed");
        }

        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                await _sourceManager.RefreshAsync(stoppingToken).ConfigureAwait(false);
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "JellyTV: scheduled refresh failed");
            }

            var minutes = Plugin.Instance?.Configuration.RefreshIntervalMinutes ?? 30;
            await Task.Delay(TimeSpan.FromMinutes(Math.Clamp(minutes, 1, 720)), stoppingToken)
                .ConfigureAwait(false);
        }
    }
}
