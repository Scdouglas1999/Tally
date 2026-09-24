using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Tally.Services;

namespace Jellyfin.Plugin.Tally.Live;

public sealed record FetchResult(int Status, byte[]? Body, Uri FinalUri, string? ContentType, double Seconds, string? Error)
{
    public bool Ok => Body != null && Status is >= 200 and < 300 && Error == null;

    public string Text => Body == null ? string.Empty : Encoding.UTF8.GetString(Body);
}

/// <summary>Timed, buffered upstream fetches for the ladder, through the proxy's own fetcher (same header rules,
/// Referer stripping, browser relay), plus AES-128 segment decryption.</summary>
public sealed class LiveFetch
{
    private const int MaxBody = 32 * 1024 * 1024;
    private readonly UpstreamFetcher _fetcher;
    private readonly ConcurrentDictionary<string, byte[]> _keys = new(StringComparer.Ordinal);

    public LiveFetch(UpstreamFetcher fetcher) => _fetcher = fetcher;

    /// <param name="giveUpAfter">When set and the response says how long it is: abandon the download as soon as
    /// the rate so far projects a finish later than this (a segment that will arrive slower than real time is
    /// known to be too slow after a second, not after the whole timeout).</param>
    public async Task<FetchResult> GetAsync(Uri uri, Dictionary<string, string> headers, TimeSpan timeout, CancellationToken ct, TimeSpan? giveUpAfter = null)
    {
        var sw = Stopwatch.StartNew();
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        cts.CancelAfter(timeout);
        try
        {
            using var o = await _fetcher.FetchAsync(uri, headers, cts.Token).ConfigureAwait(false);
            if (o.Status is < 200 or >= 300)
            {
                return new FetchResult(o.Status, null, uri, o.ContentType, sw.Elapsed.TotalSeconds, "HTTP " + o.Status.ToString(CultureInfo.InvariantCulture));
            }

            if (o.Body != null)
            {
                return Check(new FetchResult(o.Status, o.Body, uri, o.ContentType, sw.Elapsed.TotalSeconds, null));
            }

            var resp = o.Response!;
            var final = resp.RequestMessage?.RequestUri ?? uri;
            var length = resp.Content.Headers.ContentLength;
            if (giveUpAfter is not { } limit || length is not > 0)
            {
                var body = await resp.Content.ReadAsByteArrayAsync(cts.Token).ConfigureAwait(false);
                return Check(new FetchResult(o.Status, body, final, o.ContentType, sw.Elapsed.TotalSeconds, body.Length > MaxBody ? "too large" : null));
            }

            using var ms = new System.IO.MemoryStream((int)Math.Min(length.Value, MaxBody));
            var stream = await resp.Content.ReadAsStreamAsync(cts.Token).ConfigureAwait(false);
            await using (stream.ConfigureAwait(false))
            {
                var buffer = new byte[64 * 1024];
                int read;
                while ((read = await stream.ReadAsync(buffer, cts.Token).ConfigureAwait(false)) > 0)
                {
                    ms.Write(buffer, 0, read);
                    var elapsed = sw.Elapsed.TotalSeconds;
                    if (elapsed > 1.0 && ms.Length < length.Value)
                    {
                        var projected = elapsed * length.Value / ms.Length;
                        if (projected > limit.TotalSeconds)
                        {
                            return new FetchResult(0, null, uri, o.ContentType, elapsed, string.Create(CultureInfo.InvariantCulture,
                                $"timed out: {ms.Length * 8 / elapsed / 1e6:0.0} Mbps, would take {projected:0.0}s"));
                        }
                    }

                    if (ms.Length > MaxBody)
                    {
                        return new FetchResult(o.Status, null, final, o.ContentType, elapsed, "too large");
                    }
                }
            }

            return Check(new FetchResult(o.Status, ms.ToArray(), final, o.ContentType, sw.Elapsed.TotalSeconds, null));
        }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested)
        {
            return new FetchResult(0, null, uri, null, sw.Elapsed.TotalSeconds, "timed out after " + timeout.TotalSeconds.ToString("0", CultureInfo.InvariantCulture) + "s");
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            return new FetchResult(0, null, uri, null, sw.Elapsed.TotalSeconds, ex.Message);
        }
    }

    /// <summary>A segment's bytes: fetched, checked for junk (HTML/JSON interstitials, empty bodies) and decrypted.</summary>
    public async Task<FetchResult> GetSegmentAsync(HlsSegment seg, Dictionary<string, string> headers, TimeSpan timeout, CancellationToken ct, TimeSpan? giveUpAfter = null)
    {
        var r = await GetAsync(seg.Uri, headers, timeout, ct, giveUpAfter).ConfigureAwait(false);
        if (!r.Ok)
        {
            return r;
        }

        var head = r.Body!.AsSpan(0, Math.Min(64, r.Body!.Length));
        if (r.Body!.Length == 0 || head.IndexOf("#EXTM3U"u8) >= 0 || head.IndexOf("<html"u8) >= 0 || head.IndexOf("<!DOCTYPE"u8) >= 0
            || r.ContentType?.Contains("html", StringComparison.OrdinalIgnoreCase) == true
            || r.ContentType?.Contains("json", StringComparison.OrdinalIgnoreCase) == true)
        {
            return r with { Body = null, Error = "not a media segment" };
        }

        if (string.Equals(seg.KeyMethod, "AES-128", StringComparison.OrdinalIgnoreCase) && seg.KeyUri != null)
        {
            var key = await GetKeyAsync(seg.KeyUri, headers, ct).ConfigureAwait(false);
            if (key == null)
            {
                return r with { Body = null, Error = "key unavailable" };
            }

            try
            {
                using var aes = Aes.Create();
                aes.Key = key;
                var clear = aes.DecryptCbc(r.Body!, Iv(seg), PaddingMode.PKCS7);
                return r with { Body = clear };
            }
            catch (CryptographicException ex)
            {
                return r with { Body = null, Error = "decrypt: " + ex.Message };
            }
        }

        if (seg.KeyMethod != null)
        {
            return r with { Body = null, Error = "unsupported encryption " + seg.KeyMethod };
        }

        return r;
    }

    private async Task<byte[]?> GetKeyAsync(Uri keyUri, Dictionary<string, string> headers, CancellationToken ct)
    {
        var k = keyUri.ToString();
        if (_keys.TryGetValue(k, out var cached))
        {
            return cached;
        }

        var r = await GetAsync(keyUri, headers, TimeSpan.FromSeconds(10), ct).ConfigureAwait(false);
        if (!r.Ok || r.Body!.Length != 16)
        {
            return null;
        }

        if (_keys.Count > 256)
        {
            _keys.Clear();
        }

        _keys[k] = r.Body!;
        return r.Body;
    }

    private static byte[] Iv(HlsSegment seg)
    {
        var iv = new byte[16];
        if (!string.IsNullOrEmpty(seg.KeyIv))
        {
            var hex = seg.KeyIv.StartsWith("0x", StringComparison.OrdinalIgnoreCase) ? seg.KeyIv[2..] : seg.KeyIv;
            hex = hex.PadLeft(32, '0');
            try
            {
                return Convert.FromHexString(hex[^32..]);
            }
            catch (FormatException)
            {
            }
        }

        var n = seg.Sequence;
        for (var i = 15; i >= 8; i--)
        {
            iv[i] = (byte)(n & 0xFF);
            n >>= 8;
        }

        return iv;
    }

    private static FetchResult Check(FetchResult r) => r;
}
