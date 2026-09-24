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

public class DirectSourceAdapter : ISourceAdapter
{
    private readonly IHttpClientFactory _httpClientFactory;
    private readonly ILogger _logger;

    public DirectSourceAdapter(SourceDefinition definition, IHttpClientFactory httpClientFactory, ILogger logger)
    {
        Definition = definition;
        _httpClientFactory = httpClientFactory;
        _logger = logger;
    }

    public SourceDefinition Definition { get; }

    public async Task<SourceSnapshot> RefreshAsync(CancellationToken cancellationToken)
    {
        var snapshot = new SourceSnapshot();
        var sourceId = Definition.Id.ToString("N");
        var channels = new List<SourceChannel>();

        foreach (var s in Definition.Streams)
        {
            if (string.IsNullOrWhiteSpace(s.Url))
            {
                continue;
            }

            var headers = Definition.Headers.ToDictionary();
            foreach (var kv in s.Headers.ToDictionary())
            {
                headers[kv.Key] = kv.Value;
            }

            var name = string.IsNullOrWhiteSpace(s.Name) ? "Stream" : s.Name;
            var key = ChannelGrouper.KeyFor(name);
            channels.Add(new SourceChannel
            {
                Id = SourceChannel.MakeId(sourceId, s.Url),
                Name = name,
                GroupKey = key.Length > 0 ? "name:" + key : string.Empty,
                StreamUrl = s.Url,
                LogoUrl = s.LogoUrl,
                Group = string.IsNullOrWhiteSpace(s.Group) ? "Live" : s.Group,
                SourceId = sourceId,
                SourceName = Definition.Name,
                TvgId = s.TvgId,
                Headers = headers
            });
        }

        snapshot.Channels = channels;

        if (!string.IsNullOrWhiteSpace(Definition.EpgUrl))
        {
            try
            {
                var client = _httpClientFactory.CreateClient("jellytv");
                var response = await client.GetAsync(Definition.EpgUrl, cancellationToken).ConfigureAwait(false);
                response.EnsureSuccessStatusCode();
                var stream = await response.Content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false);
                await using (stream.ConfigureAwait(false))
                {
                    Stream s = Definition.EpgUrl.EndsWith(".gz", StringComparison.OrdinalIgnoreCase)
                        ? new GZipStream(stream, CompressionMode.Decompress)
                        : stream;
                    snapshot.ProgrammesByTvgId = XmlTvParser.Parse(s).Programmes;
                }
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "JellyTV: failed to load EPG {Url}", Definition.EpgUrl);
            }
        }

        return snapshot;
    }
}
