using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.Extensions.Logging;
using Microsoft.Playwright;

namespace Jellyfin.Plugin.Tally.Services;

/// <summary>
/// Fetches upstream resources through a resident headless Chromium. Some CDNs
/// (e.g. the Akamai edge in front of fsy.nfl.com) fingerprint TLS clients and
/// serve 404 to anything that isn't a real browser — HttpClient and curl both
/// get rejected. A fetch() evaluated inside a real page uses Chromium's network
/// stack and passes. The worker page is kept on the embed's origin so Origin /
/// Referer headers match what the real player sends (the redirect-wrapper
/// returns 403 to foreign origins).
/// </summary>
public sealed class BrowserFetchService : IAsyncDisposable
{
    /// <summary>Max concurrent in-page fetches. Bounded so multiview can't wedge the pipe.</summary>
    private const int MaxConcurrent = 6;

    private static readonly string[] ChromiumCandidates =
    {
        "/usr/bin/chromium", "/usr/bin/chromium-browser", "/usr/bin/google-chrome",
        "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
        "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
        "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
        "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe"
    };

    private readonly ILogger<BrowserFetchService> _logger;
    private readonly SemaphoreSlim _gate = new(MaxConcurrent);
    private readonly SemaphoreSlim _launchLock = new(1, 1);
    private readonly ConcurrentDictionary<string, IPage> _pages = new(StringComparer.OrdinalIgnoreCase);
    private IPlaywright? _pw;
    private IBrowser? _browser;
    private IBrowserContext? _ctx;
    private int _launched; // 0 = not tried, 1 = up, -1 = unavailable

    public BrowserFetchService(ILogger<BrowserFetchService> logger)
    {
        _logger = logger;
    }

    public sealed record FetchResult(int Status, string? ContentType, byte[] Body);

    /// <summary>
    /// Fetches <paramref name="url"/> inside a page hosted on
    /// <paramref name="pageOrigin"/> (so the browser sends that Origin/Referer).
    /// Returns null when no browser is available.
    /// </summary>
    public async Task<FetchResult?> FetchAsync(string url, string? pageOrigin, CancellationToken ct)
    {
        if (!await EnsureLaunchedAsync(ct).ConfigureAwait(false))
        {
            return null;
        }

        var page = await GetPageAsync(pageOrigin, ct).ConfigureAwait(false);
        if (page == null)
        {
            return null;
        }

        await _gate.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            var json = await page.EvaluateAsync<string>(@"async (u) => {
  const ctl = new AbortController();
  const to = setTimeout(() => ctl.abort(), 25000);
  try {
    // no-referrer is load-bearing: these CDNs 404 any segment request that
    // carries a Referer header.
    const r = await fetch(u, { credentials: 'omit', signal: ctl.signal, redirect: 'follow', referrerPolicy: 'no-referrer' });
    const buf = await r.arrayBuffer();
    const bytes = new Uint8Array(buf);
    let bin = '';
    for (let i = 0; i < bytes.length; i += 0x8000) {
      bin += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    }
    return JSON.stringify({ s: r.status, ct: r.headers.get('content-type') || '', b64: btoa(bin) });
  } catch (e) {
    return JSON.stringify({ s: 0, err: String(e) });
  } finally { clearTimeout(to); }
}", url).ConfigureAwait(false);

            using var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;
            var status = root.GetProperty("s").GetInt32();
            if (status == 0)
            {
                var err = root.TryGetProperty("err", out var e) ? e.GetString() : "?";
                _logger.LogDebug("JellyTV: browser fetch of {Url} failed: {Err}", url[..Math.Min(80, url.Length)], err);
                return new FetchResult(0, null, Array.Empty<byte>());
            }

            var ctype = root.TryGetProperty("ct", out var c) ? c.GetString() : null;
            var b64 = root.GetProperty("b64").GetString() ?? string.Empty;
            return new FetchResult(status, ctype, Convert.FromBase64String(b64));
        }
        catch (Exception ex)
        {
            _logger.LogDebug(ex, "JellyTV: browser fetch threw for {Url}", url[..Math.Min(80, url.Length)]);
            await ResetAsync().ConfigureAwait(false);
            return null;
        }
        finally
        {
            _gate.Release();
        }
    }

    private async Task<IPage?> GetPageAsync(string? origin, CancellationToken ct)
    {
        if (_ctx == null)
        {
            return null;
        }

        var key = string.IsNullOrEmpty(origin) ? "about:blank" : origin;
        if (_pages.TryGetValue(key, out var existing) && !existing.IsClosed)
        {
            return existing;
        }

        await _launchLock.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            if (_pages.TryGetValue(key, out existing) && !existing.IsClosed)
            {
                return existing;
            }

            var page = await _ctx.NewPageAsync().ConfigureAwait(false);
            if (!string.IsNullOrEmpty(origin))
            {
                try
                {
                    await page.GotoAsync(origin + "/", new PageGotoOptions { WaitUntil = WaitUntilState.DOMContentLoaded, Timeout = 20000 }).ConfigureAwait(false);
                }
                catch (Exception ex)
                {
                    _logger.LogDebug(ex, "JellyTV: relay page nav to {Origin} failed, using blank origin", origin);
                }
            }

            _pages[key] = page;
            return page;
        }
        finally
        {
            _launchLock.Release();
        }
    }

    /// <summary>Launch the relay browser ahead of the first request that needs it.</summary>
    public Task<bool> WarmAsync(CancellationToken ct) => EnsureLaunchedAsync(ct);

    private async Task<bool> EnsureLaunchedAsync(CancellationToken ct)
    {
        if (Volatile.Read(ref _launched) == 1)
        {
            return true;
        }

        if (Volatile.Read(ref _launched) == -1)
        {
            return false;
        }

        await _launchLock.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            if (_launched != 0)
            {
                return _launched == 1;
            }

            _pw = await Playwright.CreateAsync().ConfigureAwait(false);
            var opts = new BrowserTypeLaunchOptions
            {
                Headless = true,
                Args = new[] { "--no-sandbox", "--disable-dev-shm-usage", "--mute-audio" }
            };

            foreach (var channel in new[] { "chrome", "msedge" })
            {
                try
                {
                    opts.Channel = channel;
                    _browser = await _pw.Chromium.LaunchAsync(opts).ConfigureAwait(false);
                    _logger.LogInformation("JellyTV: segment relay browser via channel {Channel}", channel);
                    break;
                }
                catch (PlaywrightException) { }
            }

            if (_browser == null)
            {
                foreach (var path in ChromiumCandidates.Where(System.IO.File.Exists))
                {
                    try
                    {
                        opts.Channel = null;
                        opts.ExecutablePath = path;
                        _browser = await _pw.Chromium.LaunchAsync(opts).ConfigureAwait(false);
                        _logger.LogInformation("JellyTV: segment relay browser at {Path}", path);
                        break;
                    }
                    catch (PlaywrightException) { }
                }
            }

            if (_browser == null)
            {
                _logger.LogWarning("JellyTV: no browser available for fingerprinted-CDN relay");
                _launched = -1;
                return false;
            }

            _ctx = await _browser.NewContextAsync(new BrowserNewContextOptions
            {
                UserAgent = Plugin.Instance?.Configuration.UserAgent
                    ?? "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
            }).ConfigureAwait(false);
            _launched = 1;
            return true;
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "JellyTV: failed to launch segment relay browser");
            _launched = -1;
            return false;
        }
        finally
        {
            _launchLock.Release();
        }
    }

    private async Task ResetAsync()
    {
        foreach (var p in _pages.Values)
        {
            try { await p.CloseAsync().ConfigureAwait(false); } catch { }
        }

        _pages.Clear();
        try { if (_ctx != null) await _ctx.CloseAsync().ConfigureAwait(false); } catch { }
        try { if (_browser != null) await _browser.CloseAsync().ConfigureAwait(false); } catch { }
        _ctx = null;
        _browser = null;
        _launched = 0;
    }

    public async ValueTask DisposeAsync()
    {
        await ResetAsync().ConfigureAwait(false);
        _pw?.Dispose();
    }
}
