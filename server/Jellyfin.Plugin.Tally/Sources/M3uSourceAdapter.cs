using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Tally.Models;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Tally.Sources;

public class M3uSourceAdapter : ISourceAdapter
{
    private readonly IHttpClientFactory _httpClientFactory;
    private readonly ILogger _logger;

    public M3uSourceAdapter(SourceDefinition definition, IHttpClientFactory httpClientFactory, ILogger logger)
    {
        Definition = definition;
        _httpClientFactory = httpClientFactory;
        _logger = logger;
    }

    public SourceDefinition Definition { get; }

    public async Task<SourceSnapshot> RefreshAsync(CancellationToken cancellationToken)
    {
        var snapshot = new SourceSnapshot();
        var client = _httpClientFactory.CreateClient("jellytv");

        var playlist = await GetStringAsync(client, Definition.PlaylistUrl, cancellationToken).ConfigureAwait(false);
        if (playlist == null)
        {
            snapshot.Error = $"Failed to fetch playlist {Definition.PlaylistUrl}";
            return snapshot;
        }

        var channels = M3uParser.Parse(playlist, Definition.Id.ToString("N"), Definition.Name, Definition.Headers.ToDictionary());
        snapshot.Channels = channels;

        var epgUrl = !string.IsNullOrWhiteSpace(Definition.EpgUrl)
            ? Definition.EpgUrl
            : M3uParser.ExtractEpgUrl(playlist);

        if (!string.IsNullOrWhiteSpace(epgUrl))
        {
            try
            {
                var epgStream = await GetStreamAsync(client, epgUrl, cancellationToken).ConfigureAwait(false);
                if (epgStream != null)
                {
                    await using (epgStream.ConfigureAwait(false))
                    {
                        var parsed = XmlTvParser.Parse(epgStream);
                        snapshot.ProgrammesByTvgId = parsed.Programmes;

                        foreach (var ch in channels)
                        {
                            if (string.IsNullOrEmpty(ch.LogoUrl)
                                && !string.IsNullOrEmpty(ch.TvgId)
                                && parsed.ChannelIcons.TryGetValue(ch.TvgId, out var icon))
                            {
                                ch.LogoUrl = icon;
                            }
                        }
                    }
                }
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "JellyTV: failed to load EPG {Url}", epgUrl);
            }
        }

        return snapshot;
    }

    private async Task<string?> GetStringAsync(HttpClient client, string url, CancellationToken ct)
    {
        try
        {
            using var request = BuildRequest(url);
            using var response = await client.SendAsync(request, ct).ConfigureAwait(false);
            response.EnsureSuccessStatusCode();
            return await response.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "JellyTV: GET {Url} failed", url);
            return null;
        }
    }

    private async Task<Stream?> GetStreamAsync(HttpClient client, string url, CancellationToken ct)
    {
        try
        {
            using var request = BuildRequest(url);
            var response = await client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false);
            response.EnsureSuccessStatusCode();

            var stream = await response.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
            if (url.EndsWith(".gz", StringComparison.OrdinalIgnoreCase)
                || response.Content.Headers.ContentEncoding.Contains("gzip"))
            {
                return new GZipStream(stream, CompressionMode.Decompress);
            }

            return stream;
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "JellyTV: GET {Url} failed", url);
            return null;
        }
    }

    private HttpRequestMessage BuildRequest(string url)
    {
        var request = new HttpRequestMessage(HttpMethod.Get, url);
        foreach (var kv in Definition.Headers.ToDictionary())
        {
            request.Headers.TryAddWithoutValidation(kv.Key, kv.Value);
        }

        if (!request.Headers.Contains("User-Agent"))
        {
            request.Headers.TryAddWithoutValidation("User-Agent", Plugin.Instance?.Configuration.UserAgent ?? "JellyTV");
        }

        return request;
    }
}
