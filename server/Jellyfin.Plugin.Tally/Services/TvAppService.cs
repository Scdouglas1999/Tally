using System;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Tally.Services;

/// <summary>Keeps a copy of the newest TV app APK so <c>/JellyTV/app</c> can hand a TV an APK that already
/// knows this server's address (<see cref="ApkStamper"/>). The copy is refreshed with a conditional request, so
/// a new GitHub release is picked up within minutes and an unchanged one costs one 304.</summary>
public sealed class TvAppService
{
    private static readonly TimeSpan Fresh = TimeSpan.FromMinutes(10);

    private readonly IHttpClientFactory _httpClientFactory;
    private readonly ILogger<TvAppService> _logger;
    private readonly SemaphoreSlim _gate = new(1, 1);

    private byte[]? _apk;
    private string? _source;
    private string? _etag;
    private DateTime _checkedAt;

    public TvAppService(IHttpClientFactory httpClientFactory, ILogger<TvAppService> logger)
    {
        _httpClientFactory = httpClientFactory;
        _logger = logger;
    }

    /// <summary>The APK stamped with <paramref name="serverAddress"/>, or null when it cannot be fetched or is
    /// not something we can stamp; the caller then redirects the TV to the plain download instead.</summary>
    public async Task<byte[]?> GetStampedAsync(string sourceUrl, string serverAddress, CancellationToken cancellationToken)
    {
        var apk = await GetApkAsync(sourceUrl, cancellationToken).ConfigureAwait(false);
        return apk == null ? null : ApkStamper.Stamp(apk, serverAddress);
    }

    private async Task<byte[]?> GetApkAsync(string sourceUrl, CancellationToken cancellationToken)
    {
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var sameSource = string.Equals(_source, sourceUrl, StringComparison.Ordinal);
            if (sameSource && _apk != null && DateTime.UtcNow - _checkedAt < Fresh)
            {
                return _apk;
            }

            try
            {
                using var request = new HttpRequestMessage(HttpMethod.Get, sourceUrl);
                if (sameSource && _apk != null && _etag != null)
                {
                    request.Headers.TryAddWithoutValidation("If-None-Match", _etag);
                }

                var client = _httpClientFactory.CreateClient("jellytv");
                using var response = await client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken).ConfigureAwait(false);
                if (response.StatusCode == System.Net.HttpStatusCode.NotModified)
                {
                    _checkedAt = DateTime.UtcNow;
                    return _apk;
                }

                response.EnsureSuccessStatusCode();
                var bytes = await response.Content.ReadAsByteArrayAsync(cancellationToken).ConfigureAwait(false);
                if (ApkStamper.Stamp(bytes, "http://probe") == null)
                {
                    _logger.LogWarning("JellyTV: {Url} did not return a signed APK ({Length} bytes)", sourceUrl, bytes.Length);
                    return sameSource ? _apk : null;
                }

                _apk = bytes;
                _source = sourceUrl;
                _etag = response.Headers.ETag?.ToString();
                _checkedAt = DateTime.UtcNow;
                _logger.LogInformation("JellyTV: cached TV app APK, {Length} bytes", bytes.Length);
                return _apk;
            }
            catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or InvalidOperationException)
            {
                _logger.LogWarning("JellyTV: could not fetch the TV app from {Url}: {Message}", sourceUrl, ex.Message);
                return sameSource ? _apk : null; // a stale copy still installs and then updates itself
            }
        }
        finally
        {
            _gate.Release();
        }
    }
}
