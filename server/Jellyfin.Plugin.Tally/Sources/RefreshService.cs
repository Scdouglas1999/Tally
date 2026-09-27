using System;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Tally.Services;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Tally.Sources;

/// <summary>
/// Refreshes sources in the background: everything on startup; then M3U and direct sources every refresh interval,
/// and web page sources' full-site scan every "Full site scan" interval (<see cref="SourceManager.FullScanInterval"/>).
/// Games without a stream are searched on their own schedule (<see cref="StreamSearchService"/>).
/// </summary>
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

        var first = true;
        var lastRegular = DateTimeOffset.UtcNow;
        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                if (first)
                {
                    await _sourceManager.RefreshAsync(stoppingToken).ConfigureAwait(false);
                }
                else
                {
                    await _sourceManager.RefreshScheduledAsync(stoppingToken).ConfigureAwait(false);
                }
            }
            catch (Exception ex) when (ex is not OperationCanceledException || !stoppingToken.IsCancellationRequested)
            {
                _logger.LogWarning(ex, "JellyTV: scheduled refresh failed");
            }

            first = false;
            lastRegular = DateTimeOffset.UtcNow;

            // wake for whichever comes first: the next regular refresh or the web page sources' next full-site scan
            var minutes = Plugin.Instance?.Configuration.RefreshIntervalMinutes ?? 30;
            var regular = lastRegular + TimeSpan.FromMinutes(Math.Clamp(minutes, 1, 720));
            var web = _sourceManager.NextWebScanAt;
            var wake = web < regular ? web : regular;
            var wait = wake - DateTimeOffset.UtcNow;
            await Task.Delay(wait < TimeSpan.FromMinutes(1) ? TimeSpan.FromMinutes(1) : wait, stoppingToken).ConfigureAwait(false);
        }
    }
}
