using System;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Tally.Sources;
using MediaBrowser.Common.Configuration;
using MediaBrowser.Common.Net;
using MediaBrowser.Controller;
using MediaBrowser.Controller.LiveTv;
using MediaBrowser.Model.LiveTv;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Tally.Services;

/// <summary>
/// Registers JellyTV's anonymous feeds as a Jellyfin Live TV tuner + XMLTV listings
/// provider so the built-in Live TV guide, DVR and playback work without manual setup.
/// Idempotent: entries are matched by their /JellyTV/ URL, so restarts update in place
/// instead of duplicating, and user-configured tuners are never touched.
/// </summary>
public class LiveTvRegistrationService : BackgroundService
{
    /// <summary>Simultaneous native Live TV streams allowed; must be &gt; 0 (see RegisterAsync).</summary>
    public const int NativeClientStreamLimit = 50;

    private const string PlaylistMarker = "/JellyTV/livetv.m3u";
    private const string EpgMarker = "/JellyTV/epg.xml";
    private const int MaxAttempts = 5;

    private readonly ITunerHostManager _tunerHostManager;
    private readonly IListingsManager _listingsManager;
    private readonly IConfigurationManager _config;
    private readonly IServerApplicationHost _appHost;
    private readonly SourceManager _sourceManager;
    private readonly ILogger<LiveTvRegistrationService> _logger;

    public LiveTvRegistrationService(
        ITunerHostManager tunerHostManager,
        IListingsManager listingsManager,
        IConfigurationManager config,
        IServerApplicationHost appHost,
        SourceManager sourceManager,
        ILogger<LiveTvRegistrationService> logger)
    {
        _tunerHostManager = tunerHostManager;
        _listingsManager = listingsManager;
        _config = config;
        _appHost = appHost;
        _sourceManager = sourceManager;
        _logger = logger;
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        // Registering the tuner makes Jellyfin fetch livetv.m3u over loopback to
        // validate it — wait until the web server is actually accepting requests.
        // Bounded: if the flag never flips, the retry loop below covers it anyway.
        var startupDeadline = DateTime.UtcNow.AddMinutes(3);
        while (!_appHost.CoreStartupHasCompleted
               && DateTime.UtcNow < startupDeadline
               && !stoppingToken.IsCancellationRequested)
        {
            await Task.Delay(TimeSpan.FromSeconds(2), stoppingToken).ConfigureAwait(false);
        }

        // Prefer a populated channel list so the tuner validates with real data.
        var deadline = DateTime.UtcNow.AddMinutes(5);
        while (_sourceManager.LoadedAt == DateTimeOffset.MinValue
               && DateTime.UtcNow < deadline
               && !stoppingToken.IsCancellationRequested)
        {
            await Task.Delay(TimeSpan.FromSeconds(5), stoppingToken).ConfigureAwait(false);
        }

        for (var attempt = 1; attempt <= MaxAttempts && !stoppingToken.IsCancellationRequested; attempt++)
        {
            try
            {
                await RegisterAsync().ConfigureAwait(false);
                return;
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "JellyTV: Live TV tuner registration failed (attempt {Attempt}/{Max})", attempt, MaxAttempts);
                await Task.Delay(TimeSpan.FromSeconds(15), stoppingToken).ConfigureAwait(false);
            }
        }
    }

    private async Task RegisterAsync()
    {
        var baseUrl = LoopbackBaseUrl();
        var playlistUrl = baseUrl + PlaylistMarker;
        var epgUrl = baseUrl + EpgMarker;

        var options = _config.GetConfiguration<LiveTvOptions>("livetv") ?? new LiveTvOptions();

        var tuner = (options.TunerHosts ?? Array.Empty<TunerHostInfo>())
            .FirstOrDefault(t => t.Url?.Contains(PlaylistMarker, StringComparison.OrdinalIgnoreCase) == true)
            ?? new TunerHostInfo();

        tuner.Type = "m3u";
        tuner.Url = playlistUrl;
        tuner.FriendlyName = "JellyTV";
        tuner.UserAgent = Plugin.Instance?.Configuration.UserAgent;

        // Jellyfin fetches our playlist over loopback, so every channel's address is
        // http://127.0.0.1:<port>/JellyTV/Proxy… — right for the server, meaningless to anyone else.
        // With TunerCount == 0 the M3U tuner flags channels SupportsDirectPlay, and native clients
        // (Swiftfin, Android TV…) then open that loopback address *themselves*: playback dies at 0 ms
        // with "Unable to load this item" while the server logs nothing wrong. Any count > 0 turns
        // direct play off, so Jellyfin serves the stream itself (remux, no re-encode for H.264/AAC).
        // The number is also the tuner's simultaneous-stream cap, hence generous.
        tuner.TunerCount = NativeClientStreamLimit;
        tuner.EnableStreamLooping = false;
        tuner.AllowStreamSharing = false; // HLS playlists can't be shared as a raw byte stream

        // Matched entries keep their Id and are updated in place; a fresh entry gets
        // a new Id assigned by SaveTunerHost. Saving queues a guide refresh.
        tuner = await _tunerHostManager.SaveTunerHost(tuner, true).ConfigureAwait(false);

        // SaveTunerHost re-saved the "livetv" config — re-read before editing providers.
        options = _config.GetConfiguration<LiveTvOptions>("livetv") ?? new LiveTvOptions();

        var provider = (options.ListingProviders ?? Array.Empty<ListingsProviderInfo>())
            .FirstOrDefault(p => p.Path?.Contains(EpgMarker, StringComparison.OrdinalIgnoreCase) == true)
            ?? new ListingsProviderInfo();

        provider.Type = "xmltv";
        provider.Path = epgUrl;

        // Scope the guide to our tuner only, unless the user enabled it for all.
        if (!provider.EnableAllTuners
            && (provider.EnabledTuners is null || !provider.EnabledTuners.Contains(tuner.Id, StringComparer.OrdinalIgnoreCase)))
        {
            provider.EnabledTuners = (provider.EnabledTuners ?? Array.Empty<string>())
                .Append(tuner.Id)
                .ToArray();
        }

        await _listingsManager.SaveListingProvider(provider, false, false).ConfigureAwait(false);

        _logger.LogInformation(
            "JellyTV: registered Live TV tuner '{Playlist}' and xmltv guide '{Epg}'",
            playlistUrl, epgUrl);
    }

    private string LoopbackBaseUrl()
    {
        var baseUrl = _config.GetNetworkConfiguration().BaseUrl?.Trim('/') ?? string.Empty;
        var prefix = string.IsNullOrEmpty(baseUrl) ? string.Empty : "/" + baseUrl;
        return $"http://127.0.0.1:{_appHost.HttpPort}{prefix}";
    }
}
