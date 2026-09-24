using System.Diagnostics;
using System.Management;
using System.Net.Http.Json;
using System.ServiceProcess;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;
using System.Xml.Linq;
using Microsoft.Win32;

namespace Tally.ServerSetup;

/// <summary>How the Jellyfin on this computer runs, so it can be stopped and started again the same way.</summary>
public enum RunMode
{
    /// <summary>Not running and no service: nothing to restart.</summary>
    Stopped,
    /// <summary>The "JellyfinServer" Windows service the official installer creates.</summary>
    Service,
    /// <summary>Started by Jellyfin's tray app (the official installer's "Basic" setup).</summary>
    Tray,
    /// <summary>jellyfin.exe started some other way (a shortcut, a script, a scheduled task).</summary>
    Process
}

/// <summary>What was found of an existing Jellyfin server.</summary>
public sealed class JellyfinServer
{
    public string? InstallDir { get; init; }
    public required string DataDir { get; init; }
    public Version? Version { get; set; }
    public RunMode Mode { get; set; }
    public bool ServiceExists { get; init; }
    public string? TrayExe { get; set; }
    public string? ProcessExe { get; set; }
    public string? ProcessArgs { get; set; }
    public int Port { get; init; } = 8096;
    /// <summary>The account Jellyfin runs as (service account or the running process's owner), when known.</summary>
    public string? RunAs { get; init; }

    public string PluginsDir => Path.Combine(DataDir, "plugins");
    public string BaseUrl => $"http://localhost:{Port}";
}

/// <summary>Finding, stopping and starting Jellyfin on Windows.</summary>
public static class Jellyfin
{
    public const string ServiceName = "JellyfinServer";
    private const string RegistryPath = @"Software\Jellyfin\Server";
    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(5) };

    /// <summary>The Jellyfin on this computer, or null when there is none.</summary>
    public static JellyfinServer? Find()
    {
        string? installDir = null, dataDir = null;
        // Jellyfin's installer is a 32-bit NSIS program, so its key lands in the 32-bit view (WOW6432Node)
        foreach (var view in new[] { RegistryView.Registry32, RegistryView.Registry64 })
        {
            using var hklm = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, view);
            using var key = hklm.OpenSubKey(RegistryPath);
            installDir ??= Expand(key?.GetValue("InstallFolder") as string);
            dataDir ??= Expand(key?.GetValue("DataFolder") as string);
        }

        var serviceExists = ServiceController.GetServices().Any(s => s.ServiceName.Equals(ServiceName, StringComparison.OrdinalIgnoreCase));
        var (processExe, processArgs, processOwner) = RunningServer();
        var trayExe = RunningTray();

        // --datadir on the running server's command line wins over the registry
        var argDataDir = processArgs is null ? null : Regex.Match(processArgs, "--datadir[ =]+(?:\"([^\"]+)\"|(\\S+))") is { Success: true } m
            ? (m.Groups[1].Success ? m.Groups[1].Value : m.Groups[2].Value)
            : null;
        dataDir = argDataDir ?? dataDir;
        installDir ??= processExe is null ? null : Path.GetDirectoryName(processExe);

        if (dataDir is null)
        {
            var programData = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "Jellyfin", "Server");
            var localAppData = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "jellyfin");
            dataDir = Directory.Exists(programData) ? programData : Directory.Exists(localAppData) ? localAppData : null;
        }

        if (installDir is null && dataDir is null && !serviceExists)
        {
            return null;
        }

        dataDir ??= Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "Jellyfin", "Server");
        var server = new JellyfinServer
        {
            InstallDir = installDir,
            DataDir = dataDir.TrimEnd('\\', '/'),
            ServiceExists = serviceExists,
            ProcessExe = processExe,
            ProcessArgs = processArgs,
            TrayExe = trayExe,
            Port = ReadPort(dataDir),
            RunAs = (serviceExists ? ServiceAccount() : null) ?? processOwner
        };

        server.Mode = serviceExists && ServiceRunning() ? RunMode.Service
            : trayExe is not null ? RunMode.Tray
            : processExe is not null ? RunMode.Process
            : RunMode.Stopped;

        var exe = processExe ?? (installDir is null ? null : Path.Combine(installDir, "jellyfin.exe"));
        if (exe is not null && File.Exists(exe))
        {
            var info = FileVersionInfo.GetVersionInfo(exe);
            server.Version = new Version(info.FileMajorPart, info.FileMinorPart, info.FileBuildPart);
        }

        return server;
    }

    /// <summary>The version the running server reports, when the file version could not be read.</summary>
    public static async Task<PublicInfo?> GetInfoAsync(JellyfinServer server, CancellationToken ct = default)
    {
        try
        {
            return await Http.GetFromJsonAsync<PublicInfo>($"{server.BaseUrl}/System/Info/Public", ct);
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or System.Text.Json.JsonException)
        {
            return null;
        }
    }

    public static async Task<PublicInfo?> WaitUntilUpAsync(JellyfinServer server, TimeSpan timeout, CancellationToken ct = default)
    {
        var deadline = DateTime.UtcNow + timeout;
        while (DateTime.UtcNow < deadline)
        {
            var info = await GetInfoAsync(server, ct);
            if (info is not null)
            {
                return info;
            }

            await Task.Delay(2000, ct);
        }

        return null;
    }

    public static async Task StopAsync(JellyfinServer server, Action<string> log)
    {
        switch (server.Mode)
        {
            case RunMode.Service:
                using (var sc = new ServiceController(ServiceName))
                {
                    if (sc.Status != ServiceControllerStatus.Stopped)
                    {
                        sc.Stop();
                        sc.WaitForStatus(ServiceControllerStatus.Stopped, TimeSpan.FromSeconds(90));
                    }
                }

                break;
            case RunMode.Tray:
                // the tray app owns the server process; close the server first so it shuts down cleanly, then the tray
                await StopServerProcessAsync(log);
                foreach (var tray in System.Diagnostics.Process.GetProcessesByName("Jellyfin.Windows.Tray"))
                {
                    tray.Kill();
                    tray.WaitForExit(10000);
                }

                break;
            case RunMode.Process:
                await StopServerProcessAsync(log);
                break;
        }
    }

    public static void Start(JellyfinServer server)
    {
        switch (server.Mode)
        {
            case RunMode.Service:
                using (var sc = new ServiceController(ServiceName))
                {
                    sc.Start();
                    sc.WaitForStatus(ServiceControllerStatus.Running, TimeSpan.FromSeconds(60));
                }

                break;
            case RunMode.Tray:
                System.Diagnostics.Process.Start(new ProcessStartInfo(server.TrayExe!) { UseShellExecute = true, WorkingDirectory = Path.GetDirectoryName(server.TrayExe!) });
                break;
            case RunMode.Process:
                System.Diagnostics.Process.Start(new ProcessStartInfo(server.ProcessExe!, server.ProcessArgs ?? string.Empty)
                {
                    UseShellExecute = true,
                    WorkingDirectory = Path.GetDirectoryName(server.ProcessExe!)
                });
                break;
        }
    }

    private static async Task StopServerProcessAsync(Action<string> log)
    {
        foreach (var p in System.Diagnostics.Process.GetProcessesByName("jellyfin"))
        {
            // Ctrl+C first: Jellyfin shuts down cleanly on it. Killing is the fallback.
            if (!ConsoleControl.SendCtrlC(p.Id) || !p.WaitForExit(45000))
            {
                log("Jellyfin did not stop on its own; ending the process.");
                p.Kill();
                await p.WaitForExitAsync();
            }
        }
    }

    private static bool ServiceRunning()
    {
        using var sc = new ServiceController(ServiceName);
        return sc.Status is ServiceControllerStatus.Running or ServiceControllerStatus.StartPending;
    }

    private static (string? Exe, string? Args, string? Owner) RunningServer()
    {
        using var searcher = new ManagementObjectSearcher("SELECT Handle, ExecutablePath, CommandLine FROM Win32_Process WHERE Name = 'jellyfin.exe'");
        foreach (var o in searcher.Get().Cast<ManagementObject>())
        {
            var exe = o["ExecutablePath"] as string;
            var cmd = o["CommandLine"] as string ?? string.Empty;
            // drop the executable itself from the command line, quoted or not
            var args = Regex.Replace(cmd, "^\\s*(\"[^\"]*\"|\\S+)\\s*", string.Empty);
            string? owner = null;
            try
            {
                var outArgs = new object?[] { null, null };
                if (Convert.ToInt32(o.InvokeMethod("GetOwner", outArgs)) == 0 && outArgs[0] is string user)
                {
                    owner = outArgs[1] is string domain && domain.Length > 0 ? $"{domain}\\{user}" : user;
                }
            }
            catch (ManagementException)
            {
            }

            return (exe, args, owner);
        }

        return (null, null, null);
    }

    /// <summary>The Jellyfin service's log-on account ("NT Authority\\NetworkService"), or null for LocalSystem.</summary>
    private static string? ServiceAccount()
    {
        using var searcher = new ManagementObjectSearcher($"SELECT StartName FROM Win32_Service WHERE Name = '{ServiceName}'");
        var name = searcher.Get().Cast<ManagementObject>().Select(o => o["StartName"] as string).FirstOrDefault();
        return string.IsNullOrEmpty(name) || name.Equals("LocalSystem", StringComparison.OrdinalIgnoreCase) ? null : name;
    }

    private static string? RunningTray()
    {
        foreach (var p in System.Diagnostics.Process.GetProcessesByName("Jellyfin.Windows.Tray"))
        {
            try
            {
                return p.MainModule?.FileName;
            }
            catch (System.ComponentModel.Win32Exception)
            {
            }
        }

        return null;
    }

    private static int ReadPort(string? dataDir)
    {
        // the service and tray installs keep config under the data folder
        var file = dataDir is null ? null : Path.Combine(dataDir, "config", "network.xml");
        try
        {
            if (file is not null && File.Exists(file))
            {
                var port = XDocument.Load(file).Descendants("InternalHttpPort").FirstOrDefault()?.Value;
                if (int.TryParse(port, out var p) && p > 0)
                {
                    return p;
                }
            }
        }
        catch (System.Xml.XmlException)
        {
        }

        return 8096;
    }

    private static string? Expand(string? path) => string.IsNullOrWhiteSpace(path) ? null : Environment.ExpandEnvironmentVariables(path).TrimEnd('\\');

    public sealed class PublicInfo
    {
        [JsonPropertyName("Version")] public string? Version { get; set; }
        [JsonPropertyName("StartupWizardCompleted")] public bool StartupWizardCompleted { get; set; }
    }
}
