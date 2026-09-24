using System.IO.Compression;
using System.Reflection;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace Tally.ServerSetup;

/// <summary>Progress reported to the window (or the console in quiet mode).</summary>
public interface ISetupReport
{
    void Step(string text);
    void Log(string text);
    /// <summary>0..100, or null for "working, no measure".</summary>
    void Progress(int? percent);
}

/// <summary>The whole install: find or install Jellyfin, put the right Tally build in its plugins folder, restart it.</summary>
public sealed class Setup
{
    public const string TallyGuid = "91920c7b-e920-46ee-b4d3-421f05d3761b";
    private const string JellyfinListing = "https://repo.jellyfin.org/?path=/server/windows/latest-stable/amd64";
    private const string JellyfinFiles = "https://repo.jellyfin.org/files/server/windows/latest-stable/amd64/";

    private readonly ISetupReport _report;
    private readonly List<string> _summary = new();

    public Setup(ISetupReport report) => _report = report;

    public static string TallyVersion => Assembly.GetExecutingAssembly().GetName().Version is { } v ? $"{v.Major}.{v.Minor}.{v.Build}" : "0.0.0";

    /// <summary>What was done, in plain sentences, for the last screen.</summary>
    public IReadOnlyList<string> Summary => _summary;

    /// <summary>The address to open when done.</summary>
    public string? OpenUrl { get; private set; }

    public async Task RunAsync(CancellationToken ct)
    {
        _report.Step("Looking for Jellyfin");
        var server = Jellyfin.Find();
        var installed = server is not null
            && (server.ServiceExists || server.Mode != RunMode.Stopped
                || (server.InstallDir is not null && File.Exists(Path.Combine(server.InstallDir, "jellyfin.exe"))));
        if (!installed)
        {
            _report.Log("Jellyfin is not installed on this computer.");
            await InstallJellyfinAsync(ct);
            server = Jellyfin.Find() ?? throw new SetupException("Jellyfin's installer finished, but Jellyfin was not found afterward.");
        }
        else
        {
            var how = server!.Mode switch
            {
                RunMode.Service => "running as the Windows service " + Jellyfin.ServiceName,
                RunMode.Tray => "running from the Jellyfin tray app",
                RunMode.Process => "running as " + server.ProcessExe,
                _ => server.ServiceExists ? "installed as a Windows service, not running" : "not running"
            };
            _report.Log($"Found Jellyfin {server!.Version?.ToString() ?? string.Empty} ({how}).");
            _report.Log($"Data folder: {server.DataDir}");
        }

        if (server.Version is null)
        {
            var info = await Jellyfin.GetInfoAsync(server, ct);
            if (info?.Version is not null && Version.TryParse(info.Version, out var reported))
            {
                server.Version = reported;
            }
        }

        if (server.Version is null)
        {
            throw new SetupException("Could not tell which Jellyfin version this is. Start Jellyfin and run this setup again.");
        }

        var abi = AbiFor(server.Version)
            ?? throw new SetupException($"Tally has builds for Jellyfin 10.10, 10.11 and 12.1 or later. This computer has Jellyfin {server.Version}; update Jellyfin first.");
        _report.Log($"Using Tally {TallyVersion} built for Jellyfin {abi}.");

        // Is the right Tally already there?
        var existing = FindTally(server.PluginsDir);
        // each build has its own version: <Tally version>.10, .11 or .12 for the Jellyfin line
        var wanted = Version.Parse($"{TallyVersion}.{RevisionFor(abi)}");
        var current = existing.FirstOrDefault(t => t.Abi == abi && t.Version >= wanted);
        if (current is not null)
        {
            _report.Log($"Tally {current.Version} is already installed in {current.Dir}; nothing to replace.");
            _summary.Add($"Tally {current.Version} was already installed; nothing was changed.");
        }
        else
        {
            var wasRunning = server.Mode != RunMode.Stopped;
            if (wasRunning)
            {
                _report.Step("Stopping Jellyfin");
                _report.Progress(null);
                await Jellyfin.StopAsync(server, _report.Log);
                _summary.Add("Stopped Jellyfin.");
            }

            try
            {
                _report.Step("Adding the Tally plugin");
                foreach (var old in existing)
                {
                    Directory.Delete(old.Dir, recursive: true);
                    _report.Log($"Removed the previous Tally {old.Version} ({old.Dir}).");
                    _summary.Add($"Removed the previous Tally {old.Version} from {old.Dir}.");
                }

                var target = Path.Combine(server.PluginsDir, $"Tally_{wanted}");
                Directory.CreateDirectory(target);
                // Files an administrator creates are read-only to the account Jellyfin runs as (Network Service for the
                // official service install), and Jellyfin refuses to start when it cannot rewrite a plugin's meta.json.
                // Granted on the empty folder so every unpacked file inherits it.
                GrantAccess(target, server);
                using (var zip = OpenPluginZip(abi))
                {
                    zip.ExtractToDirectory(target, overwriteFiles: true);
                }

                _report.Log($"Added {target}");
                _summary.Add($"Added the Tally {TallyVersion} plugin (the build for Jellyfin {abi}) in {target}.");
            }
            catch when (wasRunning)
            {
                // never leave the server down: start it again as it was, then report what went wrong
                try
                {
                    Jellyfin.Start(server);
                    _summary.Add("Started Jellyfin again after the error.");
                }
                catch (Exception restart)
                {
                    _report.Log("Could not start Jellyfin again: " + restart.Message);
                }

                throw;
            }

            if (!wasRunning && server.ServiceExists)
            {
                server.Mode = RunMode.Service;
            }

            if (server.Mode != RunMode.Stopped)
            {
                _report.Step("Starting Jellyfin");
                Jellyfin.Start(server);
                _summary.Add(server.Mode switch
                {
                    RunMode.Service => "Started the Jellyfin service again.",
                    RunMode.Tray => "Started the Jellyfin tray app again.",
                    _ => "Started Jellyfin again."
                });
            }
            else
            {
                _summary.Add("Jellyfin was not running; Tally loads the next time it starts.");
            }
        }

        if (server.Mode != RunMode.Stopped)
        {
            _report.Step("Waiting for Jellyfin to answer");
            var info = await Jellyfin.WaitUntilUpAsync(server, TimeSpan.FromMinutes(3), ct);
            if (info is null)
            {
                throw new SetupException($"Jellyfin did not answer at {server.BaseUrl} within 3 minutes. Its log is in {Path.Combine(server.DataDir, "log")}.");
            }

            OpenUrl = info.StartupWizardCompleted
                ? $"{server.BaseUrl}/web/#/configurationpage?name=JellyTV"
                : $"{server.BaseUrl}/web/";
            _summary.Add(info.StartupWizardCompleted
                ? $"Jellyfin is running at {server.BaseUrl}. Tally is in its side menu."
                : $"Jellyfin is running at {server.BaseUrl}. Finish Jellyfin's setup there; Tally is in its side menu afterward.");
        }

        _summary.Add("Nothing else on the server was changed.");
    }

    private void GrantAccess(string dir, JellyfinServer server)
    {
        var accounts = new List<string>();
        if (server.RunAs is not null)
        {
            accounts.Add(server.RunAs);
        }

        // whoever owns the plugins folder is the account Jellyfin created it as
        var owner = new DirectoryInfo(server.PluginsDir).GetAccessControl().GetOwner(typeof(System.Security.Principal.NTAccount))?.Value;
        if (owner is not null && !accounts.Contains(owner, StringComparer.OrdinalIgnoreCase)
            && !owner.EndsWith("\\Administrators", StringComparison.OrdinalIgnoreCase) && !owner.EndsWith("\\SYSTEM", StringComparison.OrdinalIgnoreCase))
        {
            accounts.Add(owner);
        }

        var info = new DirectoryInfo(dir);
        var acl = info.GetAccessControl();
        var granted = new HashSet<string>();
        foreach (var account in accounts)
        {
            // "NT Authority\\NetworkService" and "NT AUTHORITY\\NETWORK SERVICE" are one account
            string sid;
            try
            {
                sid = new System.Security.Principal.NTAccount(account).Translate(typeof(System.Security.Principal.SecurityIdentifier)).Value;
            }
            catch (System.Security.Principal.IdentityNotMappedException)
            {
                _report.Log($"Could not find the account {account}; skipped.");
                continue;
            }

            if (!granted.Add(sid))
            {
                continue;
            }

            acl.AddAccessRule(new System.Security.AccessControl.FileSystemAccessRule(
                new System.Security.Principal.SecurityIdentifier(sid),
                System.Security.AccessControl.FileSystemRights.FullControl,
                System.Security.AccessControl.InheritanceFlags.ContainerInherit | System.Security.AccessControl.InheritanceFlags.ObjectInherit,
                System.Security.AccessControl.PropagationFlags.None,
                System.Security.AccessControl.AccessControlType.Allow));
            _report.Log($"Gave {account} (the account Jellyfin runs as) full access to the plugin folder.");
        }

        info.SetAccessControl(acl);
    }

    /// <summary>The fourth part of a build's plugin version.</summary>
    public static int RevisionFor(string abi) => abi switch
    {
        "10.10" => 10,
        "10.11" => 11,
        _ => 12
    };

    /// <summary>Which plugin build a Jellyfin version needs.</summary>
    public static string? AbiFor(Version v) => (v.Major, v.Minor) switch
    {
        (10, 10) => "10.10",
        (10, 11) => "10.11",
        (12, 0) => null, // the 12 build needs 12.1: it does not load on 12.0
        ( >= 12, _) => "12",
        _ => null
    };

    private static ZipArchive OpenPluginZip(string abi)
    {
        var name = $"Tally-server-{TallyVersion}-jf{abi}.zip";
        var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream(name)
            ?? throw new SetupException($"This setup program is missing {name}.");
        return new ZipArchive(stream, ZipArchiveMode.Read);
    }

    private sealed record InstalledTally(string Dir, Version Version, string? Abi);

    /// <summary>Every Tally plugin folder, found by the plugin id in its meta.json whatever the folder is called.
    /// Folders without a meta.json (such as the plugin's settings folder) are never touched.</summary>
    private static List<InstalledTally> FindTally(string pluginsDir)
    {
        var found = new List<InstalledTally>();
        if (!Directory.Exists(pluginsDir))
        {
            return found;
        }

        foreach (var dir in Directory.GetDirectories(pluginsDir))
        {
            var meta = Path.Combine(dir, "meta.json");
            if (!File.Exists(meta))
            {
                continue;
            }

            try
            {
                using var doc = JsonDocument.Parse(File.ReadAllText(meta));
                var root = doc.RootElement;
                string? Str(string key) => root.EnumerateObject().FirstOrDefault(p => p.Name.Equals(key, StringComparison.OrdinalIgnoreCase)).Value is { ValueKind: JsonValueKind.String } v ? v.GetString() : null;
                if (!string.Equals(Str("guid") ?? Str("id"), TallyGuid, StringComparison.OrdinalIgnoreCase))
                {
                    continue;
                }

                var version = Version.TryParse(Str("version"), out var ver) ? ver : new Version(0, 0, 0, 0);
                var abi = Version.TryParse(Str("targetAbi"), out var target) ? AbiFor(target) : null;
                found.Add(new InstalledTally(dir, version, abi));
            }
            catch (Exception ex) when (ex is JsonException or IOException)
            {
                // not a manifest we can read: not ours to touch
            }
        }

        return found;
    }

    private async Task InstallJellyfinAsync(CancellationToken ct)
    {
        _report.Step("Finding the newest Jellyfin for Windows");
        using var http = new HttpClient { Timeout = TimeSpan.FromMinutes(30) };
        http.DefaultRequestHeaders.UserAgent.ParseAdd($"Tally-Server-Setup/{TallyVersion}");
        var listing = await http.GetStringAsync(JellyfinListing, ct);
        var match = Regex.Matches(listing, @"jellyfin_([0-9][0-9.]*)_windows-x64\.exe").Cast<Match>().FirstOrDefault()
            ?? throw new SetupException("Could not find the Jellyfin installer on repo.jellyfin.org.");
        var file = match.Value;
        _report.Log($"Newest Jellyfin for Windows: {match.Groups[1].Value} ({JellyfinFiles}{file})");

        _report.Step($"Downloading Jellyfin {match.Groups[1].Value} from repo.jellyfin.org");
        var dir = Path.Combine(Path.GetTempPath(), "Tally-Server-Setup");
        Directory.CreateDirectory(dir);
        var installer = Path.Combine(dir, file);
        using (var response = await http.GetAsync(JellyfinFiles + file, HttpCompletionOption.ResponseHeadersRead, ct))
        {
            response.EnsureSuccessStatusCode();
            var total = response.Content.Headers.ContentLength;
            await using var body = await response.Content.ReadAsStreamAsync(ct);
            await using var output = File.Create(installer);
            var buffer = new byte[1 << 16];
            long done = 0;
            int read, last = -1;
            while ((read = await body.ReadAsync(buffer, ct)) > 0)
            {
                await output.WriteAsync(buffer.AsMemory(0, read), ct);
                done += read;
                if (total > 0 && (int)(done * 100 / total.Value) is var pct && pct != last)
                {
                    last = pct;
                    _report.Progress(pct);
                }
            }

            _report.Log($"Downloaded {done / 1_048_576} MB.");
        }

        _report.Step($"Installing Jellyfin {match.Groups[1].Value}");
        _report.Progress(null);
        // /S: the official NSIS installer's silent mode. Unattended it installs Jellyfin as the JellyfinServer
        // service (Network Service account, data in %ProgramData%\Jellyfin\Server) and starts it.
        using (var p = System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(installer, "/S") { UseShellExecute = false })!)
        {
            await p.WaitForExitAsync(ct);
            if (p.ExitCode != 0)
            {
                throw new SetupException($"Jellyfin's installer ended with code {p.ExitCode}.");
            }
        }

        File.Delete(installer);
        _summary.Add($"Installed Jellyfin {match.Groups[1].Value} from repo.jellyfin.org as the Windows service {Jellyfin.ServiceName}.");

        _report.Step("Waiting for Jellyfin to start");
        var server = Jellyfin.Find() ?? throw new SetupException("Jellyfin's installer finished, but Jellyfin was not found afterward.");
        if (await Jellyfin.WaitUntilUpAsync(server, TimeSpan.FromMinutes(5), ct) is null)
        {
            throw new SetupException($"Jellyfin was installed but did not answer at {server.BaseUrl}.");
        }
    }
}

public sealed class SetupException(string message) : Exception(message);
