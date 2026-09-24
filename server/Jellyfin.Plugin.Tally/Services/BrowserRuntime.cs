using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.Extensions.Logging;
using Microsoft.Playwright;

namespace Jellyfin.Plugin.Tally.Services;

/// <summary>
/// Gets the headless browser that web page sources need (the crawl in BrowserExtractor, the segment relay in
/// BrowserFetchService) ready, once, in the background:
///   1. the Playwright driver for this host (<see cref="PlaywrightDriver"/>): next to the plugin if a build left one
///      there, else in the plugin's data folder, downloaded the first time (~60 MB);
///   2. a browser: an installed Chrome or Edge as before; failing that Playwright's own Chromium headless shell,
///      installed into the data folder by the driver's installer (~120 MB);
///   3. in a container running as root (the official Jellyfin image), the system libraries Chromium needs, through
///      the driver's own install-deps (apt). Elsewhere the error names the one command to run.
/// Nothing here runs unless a web page source asks for the browser, and it never downloads for a server without an
/// enabled web page source. Callers that find it not ready carry on without a browser; <see cref="BecameReady"/> tells
/// them when to try again.
/// </summary>
public sealed partial class BrowserRuntime
{
    /// <summary>Browsers tried before Playwright's own Chromium, in order (as the plugin always did).</summary>
    private static readonly string[] Channels = { "chrome", "msedge" };

    private static readonly string[] ExecutableCandidates =
    {
        "/usr/bin/chromium", "/usr/bin/chromium-browser", "/usr/bin/google-chrome",
        "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
        "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
        "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
        "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe"
    };

    /// <summary>Approximate download of Playwright's Chromium headless shell plus ffmpeg (115 + 2 MiB for 1.62).</summary>
    public const int ChromiumDownloadMb = 120;

    private static readonly TimeSpan RetryAfterFailure = TimeSpan.FromMinutes(10);

    private readonly IHttpClientFactory _httpClientFactory;
    private readonly ILogger<BrowserRuntime> _logger;
    private readonly object _gate = new();
    private readonly string? _dataRoot;

    private Task? _preparing;
    private string _state = "idle";
    private string _message = string.Empty;
    private string? _browser;
    private int? _downloadMb;
    private DateTimeOffset _failedAt;
    private bool _failedWithoutDownloads;
    private LaunchPlan? _plan;

    public BrowserRuntime(IHttpClientFactory httpClientFactory, ILogger<BrowserRuntime> logger)
        : this(httpClientFactory, logger, Plugin.Instance?.DataFolderPath)
    {
    }

    public BrowserRuntime(IHttpClientFactory httpClientFactory, ILogger<BrowserRuntime> logger, string? dataFolder)
    {
        _httpClientFactory = httpClientFactory;
        _logger = logger;
        _dataRoot = string.IsNullOrEmpty(dataFolder) ? null : Path.Combine(dataFolder, "browser");
    }

    /// <summary>Raised (on a pool thread) when the browser becomes ready after having been prepared.</summary>
    public event Action? BecameReady;

    /// <summary>How the browser is started once ready.</summary>
    /// <param name="Channel">Playwright channel (installed Chrome/Edge), or null.</param>
    /// <param name="ExecutablePath">An installed Chromium-family browser, or null.</param>
    /// <param name="Description">For the log and the plugin page.</param>
    public sealed record LaunchPlan(string? Channel, string? ExecutablePath, string Description);

    /// <summary>What the plugin page shows for web page sources.</summary>
    public sealed record StatusInfo(string State, string Message, string? Browser, int? DownloadMb);

    public bool IsReady => Volatile.Read(ref _plan) != null;

    public StatusInfo Status
    {
        get
        {
            lock (_gate)
            {
                return new StatusInfo(_state, _message, _browser, _downloadMb);
            }
        }
    }

    /// <summary>True when any enabled source is a web page source: the only kind that may cause downloads.</summary>
    public static bool WebSourcesConfigured
        => Plugin.Instance?.Configuration.Sources.Any(s => s.Enabled && s.Kind == SourceKind.Web) == true;

    private string? DriverRoot => _dataRoot == null ? null : Path.Combine(_dataRoot, "driver-" + PlaywrightDriver.Version);

    private string? BrowsersPath => _dataRoot == null ? null : Path.Combine(_dataRoot, "ms-playwright");

    /// <summary>
    /// True when the browser is ready. Otherwise starts getting it ready in the background (unless that is already
    /// under way, or failed less than ten minutes ago) and returns false at once.
    /// </summary>
    /// <param name="allowDownloads">False keeps to what is already on disk (used when no web page source is set up).</param>
    /// <param name="retryNow">Retry a recent failure (the admin pressed Refresh now).</param>
    public bool EnsureStarted(bool allowDownloads, bool retryNow = false)
    {
        if (IsReady)
        {
            return true;
        }

        lock (_gate)
        {
            if (_preparing is { IsCompleted: false })
            {
                return false;
            }

            if (_state == "failed" && !retryNow && !(allowDownloads && _failedWithoutDownloads)
                && DateTimeOffset.UtcNow - _failedAt < RetryAfterFailure)
            {
                return false;
            }

            _state = "preparing";
            _message = "Checking for a browser…";
            _downloadMb = null;
            _preparing = Task.Run(() => PrepareAsync(allowDownloads));
        }

        return false;
    }

    /// <summary>
    /// <see cref="EnsureStarted"/>, then waits up to <paramref name="wait"/> for the preparation: long enough for a
    /// browser that is already on disk (a few seconds), not for a first-time download, which carries on in the
    /// background.
    /// </summary>
    public async Task<bool> ReadyAsync(bool allowDownloads, TimeSpan wait, CancellationToken ct)
    {
        if (EnsureStarted(allowDownloads))
        {
            return true;
        }

        Task? preparing;
        lock (_gate)
        {
            preparing = _preparing;
        }

        if (preparing is { IsCompleted: false })
        {
            try
            {
                await preparing.WaitAsync(wait, ct).ConfigureAwait(false);
            }
            catch (TimeoutException)
            {
                // still downloading — the caller goes on without a browser for now
            }
        }

        return IsReady;
    }

    /// <summary>Retry after a failure right away (Refresh now), if a web page source is configured.</summary>
    public void RetryIfFailed()
    {
        bool failed;
        lock (_gate)
        {
            failed = _state == "failed";
        }

        if (failed && WebSourcesConfigured)
        {
            EnsureStarted(allowDownloads: true, retryNow: true);
        }
    }

    /// <summary>Starts the prepared browser. Throws when it cannot; the next <see cref="EnsureStarted"/> then prepares again.</summary>
    public async Task<IBrowser> LaunchAsync(IPlaywright pw, IEnumerable<string> args)
    {
        var plan = Volatile.Read(ref _plan) ?? throw new InvalidOperationException("The browser is not ready");
        try
        {
            return await pw.Chromium.LaunchAsync(Options(plan, args)).ConfigureAwait(false);
        }
        catch (Exception ex)
        {
            _logger.LogWarning("JellyTV browser: {Browser} no longer starts ({Msg}); it will be set up again on next use", plan.Description, FirstLine(ex.Message));
            lock (_gate)
            {
                if (ReferenceEquals(_plan, plan))
                {
                    _plan = null;
                    _state = "idle";
                    _message = string.Empty;
                }
            }

            throw;
        }
    }

    private static BrowserTypeLaunchOptions Options(LaunchPlan plan, IEnumerable<string> args) => new()
    {
        Channel = plan.Channel,
        ExecutablePath = plan.ExecutablePath,
        Headless = true,
        Args = args.ToArray()
    };

    private void Report(string message, int? downloadMb = null)
    {
        lock (_gate)
        {
            _message = message;
            _downloadMb = downloadMb;
        }
    }

    private async Task PrepareAsync(bool allowDownloads)
    {
        var started = Stopwatch.StartNew();
        try
        {
            using var cts = new CancellationTokenSource(TimeSpan.FromMinutes(30));
            await PrepareDriverAsync(allowDownloads, cts.Token).ConfigureAwait(false);
            var plan = await FindBrowserAsync(allowDownloads, cts.Token).ConfigureAwait(false);
            lock (_gate)
            {
                _plan = plan;
                _state = "ready";
                _message = "Ready";
                _browser = plan.Description;
                _downloadMb = null;
            }

            _logger.LogInformation("JellyTV browser: ready ({Browser}) after {Seconds:F0} s", plan.Description, started.Elapsed.TotalSeconds);
        }
        catch (Exception ex)
        {
            var message = ex is BrowserSetupException ? ex.Message : "Could not prepare the browser: " + FirstLine(ex.Message);
            lock (_gate)
            {
                _state = "failed";
                _message = message;
                _downloadMb = null;
                _failedAt = DateTimeOffset.UtcNow;
                _failedWithoutDownloads = !allowDownloads;
            }

            if (ex is BrowserSetupException && !allowDownloads)
            {
                // the relay's last resort on a server without web page sources: nothing to set up, nothing to warn about
                _logger.LogDebug("JellyTV browser: {Message}", message);
            }
            else if (ex is BrowserSetupException)
            {
                _logger.LogWarning("JellyTV browser: {Message}", message);
            }
            else
            {
                _logger.LogWarning(ex, "JellyTV browser: {Message}", message);
            }

            return;
        }

        try
        {
            BecameReady?.Invoke();
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "JellyTV browser: a ready listener failed");
        }
    }

    /// <summary>Finds or downloads the driver and points Microsoft.Playwright at it.</summary>
    private async Task PrepareDriverAsync(bool allowDownloads, CancellationToken ct)
    {
        var platform = PlaywrightDriver.ForThisHost()
            ?? throw new BrowserSetupException($"The headless browser is not available on this system ({RuntimeInformation.OSDescription}, {RuntimeInformation.ProcessArchitecture}).");

        // Chromium goes to the data folder too (unless the server's owner chose a place).
        if (string.IsNullOrEmpty(Environment.GetEnvironmentVariable("PLAYWRIGHT_BROWSERS_PATH")) && BrowsersPath != null)
        {
            Environment.SetEnvironmentVariable("PLAYWRIGHT_BROWSERS_PATH", BrowsersPath);
        }

        var configured = Environment.GetEnvironmentVariable("PLAYWRIGHT_DRIVER_SEARCH_PATH");
        if (!string.IsNullOrEmpty(configured) && configured != DriverRoot)
        {
            _logger.LogInformation("JellyTV browser: using the Playwright driver in PLAYWRIGHT_DRIVER_SEARCH_PATH ({Path})", configured);
            return;
        }

        // A build folder copied over by hand (or a development build) carries one next to the plugin.
        var pluginDir = Path.GetDirectoryName(typeof(Playwright).Assembly.Location);
        if (!string.IsNullOrEmpty(pluginDir) && PlaywrightDriver.IsPresent(pluginDir, platform, requireStamp: false))
        {
            _logger.LogInformation("JellyTV browser: using the Playwright driver next to the plugin ({Path})", pluginDir);
            return;
        }

        var root = DriverRoot ?? throw new BrowserSetupException("The plugin has no data folder for the browser.");
        if (!PlaywrightDriver.IsPresent(root, platform, requireStamp: true))
        {
            if (!allowDownloads)
            {
                throw new BrowserSetupException("No headless browser has been set up; it is set up when a web page source needs it.");
            }

            var mb = (int)Math.Ceiling(platform.DownloadBytes / 1e6);
            var totalMb = mb + (HasInstalledBrowser() ? 0 : ChromiumDownloadMb);
            Report($"Preparing the browser (one-time download, ~{totalMb} MB)…", totalMb);
            _logger.LogInformation(
                "JellyTV browser: a web page source needs the headless browser. Downloading the Playwright {Version} driver for {Platform} (Node.js {Node}, {Mb} MB) into {Root}",
                PlaywrightDriver.Version, platform.PlatformId, PlaywrightDriver.NodeVersion, mb, root);
            var sw = Stopwatch.StartNew();
            using var http = _httpClientFactory.CreateClient("jellytv-download");
            long last = 0;
            await PlaywrightDriver.InstallAsync(root, platform, http, bytes =>
            {
                if (bytes - last >= 2_000_000)
                {
                    last = bytes;
                    Report($"Preparing the browser (one-time download, ~{totalMb} MB)… {bytes / 1_000_000} MB", totalMb);
                }
            }, _logger, ct).ConfigureAwait(false);
            _logger.LogInformation("JellyTV browser: driver downloaded and verified in {Seconds:F0} s", sw.Elapsed.TotalSeconds);
            RemoveOldDrivers(root);
        }
        else
        {
            _logger.LogInformation("JellyTV browser: using the Playwright driver in {Root}", root);
        }

        Environment.SetEnvironmentVariable("PLAYWRIGHT_DRIVER_SEARCH_PATH", root);
    }

    private void RemoveOldDrivers(string keep)
    {
        if (_dataRoot == null)
        {
            return;
        }

        foreach (var dir in Directory.EnumerateDirectories(_dataRoot, "driver-*"))
        {
            if (string.Equals(Path.GetFullPath(dir), Path.GetFullPath(keep), StringComparison.Ordinal))
            {
                continue;
            }

            try
            {
                Directory.Delete(dir, recursive: true);
                _logger.LogInformation("JellyTV browser: removed the old driver {Dir}", dir);
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                _logger.LogDebug(ex, "JellyTV browser: could not remove {Dir}", dir);
            }
        }
    }

    /// <summary>Whether a Chrome/Edge/Chromium install is visible without starting Playwright (only sizes the message).</summary>
    private static bool HasInstalledBrowser()
    {
        if (ExecutableCandidates.Any(File.Exists))
        {
            return true;
        }

        return OperatingSystem.IsLinux() && (File.Exists("/opt/google/chrome/chrome") || File.Exists("/opt/microsoft/msedge/msedge"));
    }

    private async Task<LaunchPlan> FindBrowserAsync(bool allowDownloads, CancellationToken ct)
    {
        using var pw = await Playwright.CreateAsync().ConfigureAwait(false);

        var tries = Channels.Select(c => new LaunchPlan(c, null, c == "chrome" ? "Google Chrome" : "Microsoft Edge"))
            .Concat(ExecutableCandidates.Where(File.Exists).Select(p => new LaunchPlan(null, p, p)))
            .ToList();
        foreach (var plan in tries)
        {
            if (await TryLaunchAsync(pw, plan).ConfigureAwait(false) == null)
            {
                return plan;
            }
        }

        var bundled = new LaunchPlan(null, null, $"Playwright Chromium (headless shell, Playwright {PlaywrightDriver.Version})");
        var error = await TryLaunchAsync(pw, bundled).ConfigureAwait(false);
        if (error == null)
        {
            return bundled;
        }

        if (!allowDownloads)
        {
            throw new BrowserSetupException("No installed Chrome or Edge, and Playwright's Chromium has not been set up.");
        }

        if (error.Contains("Executable doesn't exist", StringComparison.OrdinalIgnoreCase)
            || error.Contains("Looks like Playwright", StringComparison.OrdinalIgnoreCase))
        {
            await InstallChromiumAsync(ct).ConfigureAwait(false);
            error = await TryLaunchAsync(pw, bundled).ConfigureAwait(false);
            if (error == null)
            {
                return bundled;
            }
        }

        if (MissingLibraries(error))
        {
            if (CanInstallSystemPackages())
            {
                await InstallDependenciesAsync(ct).ConfigureAwait(false);
                error = await TryLaunchAsync(pw, bundled).ConfigureAwait(false);
                if (error == null)
                {
                    return bundled;
                }
            }
            else
            {
                throw new BrowserSetupException(InContainer()
                    ? "Chromium needs system libraries this container lacks, and Jellyfin does not run as root here to install them. Run once, then press Refresh now: docker exec -u 0 <container> " + DepsCommand()
                    : "Chromium needs system libraries this server lacks. Run once as root (sudo), then press Refresh now: " + DepsCommand());
            }
        }

        throw new BrowserSetupException("Chromium does not start: " + FirstLine(error));
    }

    /// <summary>Launches and closes; null when it worked, else the error text.</summary>
    private async Task<string?> TryLaunchAsync(IPlaywright pw, LaunchPlan plan)
    {
        try
        {
            var browser = await pw.Chromium.LaunchAsync(Options(plan, new[] { "--no-sandbox", "--disable-dev-shm-usage" })).ConfigureAwait(false);
            await browser.CloseAsync().ConfigureAwait(false);
            return null;
        }
        catch (Exception ex)
        {
            _logger.LogDebug("JellyTV browser: {Browser} unavailable: {Msg}", plan.Description, FirstLine(ex.Message));
            return ex.Message;
        }
    }

    private static bool MissingLibraries(string error)
        => error.Contains("missing dependencies", StringComparison.OrdinalIgnoreCase)
            || error.Contains("error while loading shared libraries", StringComparison.OrdinalIgnoreCase);

    private async Task InstallChromiumAsync(CancellationToken ct)
    {
        Report($"Preparing the browser (one-time download, ~{ChromiumDownloadMb} MB)…", ChromiumDownloadMb);
        _logger.LogInformation("JellyTV browser: no Chrome or Edge on this server; installing Playwright's Chromium headless shell into {Path}",
            Environment.GetEnvironmentVariable("PLAYWRIGHT_BROWSERS_PATH"));
        var sw = Stopwatch.StartNew();
        var (code, output) = await RunDriverCliAsync("install chromium-headless-shell", line =>
        {
            var m = ProgressRegex().Match(line);
            if (m.Success)
            {
                var mb = (int)Math.Round(double.Parse(m.Groups[2].Value, System.Globalization.CultureInfo.InvariantCulture) * 1.048576);
                if (mb > 20)
                {
                    Report($"Preparing the browser (one-time download, ~{mb} MB)… {m.Groups[1].Value}%", mb);
                }
            }
            else if (line.StartsWith("Downloading ", StringComparison.Ordinal) || line.Contains("downloaded to", StringComparison.Ordinal))
            {
                _logger.LogInformation("JellyTV browser: {Line}", AnsiRegex().Replace(line, string.Empty));
            }
        }, ct).ConfigureAwait(false);
        if (code != 0)
        {
            throw new BrowserSetupException("Installing Playwright's Chromium failed: " + LastLines(output));
        }

        _logger.LogInformation("JellyTV browser: Chromium installed in {Seconds:F0} s", sw.Elapsed.TotalSeconds);
    }

    private async Task InstallDependenciesAsync(CancellationToken ct)
    {
        Report("Preparing the browser (installing the system libraries Chromium needs, one time)…");
        _logger.LogInformation("JellyTV browser: Chromium lacks system libraries; this is a container running as root, so installing them with the driver's install-deps (apt-get)");
        var sw = Stopwatch.StartNew();
        var (code, output) = await RunDriverCliAsync("install-deps chromium-headless-shell", null, ct).ConfigureAwait(false);
        if (code != 0)
        {
            throw new BrowserSetupException("Installing Chromium's system libraries failed (" + DepsCommand() + "): " + LastLines(output));
        }

        _logger.LogInformation("JellyTV browser: system libraries installed in {Seconds:F0} s", sw.Elapsed.TotalSeconds);
    }

    private (string Node, string Cli) DriverFiles()
    {
        var platform = PlaywrightDriver.ForThisHost()!;
        var root = Environment.GetEnvironmentVariable("PLAYWRIGHT_DRIVER_SEARCH_PATH");
        if (string.IsNullOrEmpty(root))
        {
            root = Path.GetDirectoryName(typeof(Playwright).Assembly.Location)!;
        }

        return (Environment.GetEnvironmentVariable("PLAYWRIGHT_NODEJS_PATH") ?? PlaywrightDriver.NodePath(root, platform), PlaywrightDriver.CliPath(root));
    }

    private string DepsCommand()
    {
        var (node, cli) = DriverFiles();
        return $"\"{node}\" \"{cli}\" install-deps chromium-headless-shell";
    }

    private async Task<(int Code, string Output)> RunDriverCliAsync(string args, Action<string>? onLine, CancellationToken ct)
    {
        var (node, cli) = DriverFiles();
        var psi = new ProcessStartInfo(node, $"\"{cli}\" {args}")
        {
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true
        };
        psi.Environment["PW_LANG_NAME"] = "csharp";
        psi.Environment["PW_CLI_DISPLAY_VERSION"] = PlaywrightDriver.Version;
        psi.Environment["DEBIAN_FRONTEND"] = "noninteractive";

        var output = new StringBuilder();
        using var proc = new Process { StartInfo = psi };
        void OnData(object? _, DataReceivedEventArgs e)
        {
            if (e.Data == null)
            {
                return;
            }

            lock (output)
            {
                output.AppendLine(e.Data);
            }

            onLine?.Invoke(e.Data);
        }

        proc.OutputDataReceived += OnData;
        proc.ErrorDataReceived += OnData;
        proc.Start();
        proc.BeginOutputReadLine();
        proc.BeginErrorReadLine();
        try
        {
            await proc.WaitForExitAsync(ct).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            try { proc.Kill(entireProcessTree: true); } catch (InvalidOperationException) { }
            throw new BrowserSetupException($"`{args}` took longer than 30 minutes and was stopped.");
        }

        proc.WaitForExit(); // flush the output events
        lock (output)
        {
            return (proc.ExitCode, output.ToString());
        }
    }

    /// <summary>Only inside a container, as root, with apt: installing packages there touches nothing but the container.</summary>
    private static bool CanInstallSystemPackages()
        => OperatingSystem.IsLinux() && InContainer() && IsRoot() && File.Exists("/usr/bin/apt-get");

    private static bool InContainer()
    {
        if (File.Exists("/.dockerenv") || File.Exists("/run/.containerenv") || !string.IsNullOrEmpty(Environment.GetEnvironmentVariable("container")))
        {
            return true;
        }

        try
        {
            var cgroup = File.ReadAllText("/proc/1/cgroup");
            return cgroup.Contains("docker", StringComparison.Ordinal) || cgroup.Contains("kubepods", StringComparison.Ordinal)
                || cgroup.Contains("containerd", StringComparison.Ordinal);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return false;
        }
    }

    private static bool IsRoot()
    {
        try
        {
            return geteuid() == 0;
        }
        catch (Exception ex) when (ex is DllNotFoundException or EntryPointNotFoundException)
        {
            return false;
        }
    }

    [DllImport("libc", SetLastError = true)]
    private static extern uint geteuid();

    private static string FirstLine(string s)
    {
        var line = AnsiRegex().Replace(s, string.Empty).Split('\n').Select(l => l.Trim()).FirstOrDefault(l => l.Length > 0 && !l.StartsWith("=====", StringComparison.Ordinal)) ?? s;
        return line.Length > 300 ? line[..300] + "…" : line;
    }

    private static string LastLines(string s)
    {
        var lines = AnsiRegex().Replace(s, string.Empty).Split('\n').Select(l => l.Trim()).Where(l => l.Length > 0 && !l.StartsWith('|')).ToList();
        return string.Join(" / ", lines.Skip(Math.Max(0, lines.Count - 3)));
    }

    [GeneratedRegex(@"(\d+)% of ([\d.]+) MiB")]
    private static partial Regex ProgressRegex();

    [GeneratedRegex(@"\x1b\[[0-9;]*m")]
    private static partial Regex AnsiRegex();

    private sealed class BrowserSetupException : Exception
    {
        public BrowserSetupException(string message)
            : base(message)
        {
        }
    }
}
