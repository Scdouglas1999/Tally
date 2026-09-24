using System;
using System.Formats.Tar;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Net.Http;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Tally.Services;

/// <summary>One host platform's Node.js build for the Playwright driver.</summary>
/// <param name="PlatformId">Folder under <c>.playwright/node/</c> that Microsoft.Playwright looks in on this host.</param>
/// <param name="NodeDist">Suffix of the Node.js download (<c>node-v&lt;version&gt;-&lt;suffix&gt;</c>).</param>
/// <param name="ArchiveSha256">SHA-256 of that download (nodejs.org's SHASUMS256.txt).</param>
/// <param name="NodeSha256">SHA-256 of the node binary inside it, identical to the one in the Microsoft.Playwright package.</param>
/// <param name="ArchiveBytes">Size of the download.</param>
public sealed record DriverPlatform(string PlatformId, string NodeDist, string ArchiveSha256, string NodeSha256, long ArchiveBytes)
{
    public bool IsWindows => PlatformId.StartsWith("win", StringComparison.Ordinal);

    public string NodeFileName => IsWindows ? "node.exe" : "node";

    public string ArchiveName => $"node-v{PlaywrightDriver.NodeVersion}-{NodeDist}.{(IsWindows ? "zip" : "tar.gz")}";

    public string ArchiveUrl => $"https://nodejs.org/dist/v{PlaywrightDriver.NodeVersion}/{ArchiveName}";

    /// <summary>Path of the node binary inside the archive.</summary>
    public string ArchiveEntry => $"node-v{PlaywrightDriver.NodeVersion}-{NodeDist}/{(IsWindows ? "node.exe" : "bin/node")}";

    /// <summary>What has to be downloaded: this Node.js build plus the playwright-core package.</summary>
    public long DownloadBytes => ArchiveBytes + PlaywrightDriver.CoreBytes;
}

/// <summary>
/// The Playwright driver (a Node.js binary plus the playwright-core package) that Microsoft.Playwright starts to drive a
/// browser. The NuGet package carries it for every platform (half a gigabyte), so the plugin zips leave it out and the
/// plugin fetches the one for its host the first time a web page source needs a browser, from the same places the
/// Microsoft.Playwright build assembles it from (its src/tools/Playwright.Tooling/DriverDownloader.cs): playwright-core
/// from the npm registry and Node.js from nodejs.org. Both are pinned by hash, and the node binary's hash is the one in
/// the package, so the result is byte for byte the driver the package would have copied next to the plugin.
/// Layout (what Microsoft.Playwright's Driver.GetExecutablePath expects under PLAYWRIGHT_DRIVER_SEARCH_PATH):
///   &lt;root&gt;/.playwright/node/&lt;platform id&gt;/node[.exe]
///   &lt;root&gt;/.playwright/package/cli.js (and the rest of playwright-core)
/// Bumping Microsoft.Playwright: the tests fail until Version, NodeVersion and the hashes below match the new package
/// (NodeVersion is DriverNodeVersion in the playwright-dotnet tag's src/Common/Version.props; archive hashes come from
/// https://nodejs.org/dist/v&lt;NodeVersion&gt;/SHASUMS256.txt, CoreSha512 from `npm view playwright-core@&lt;Version&gt;
/// dist.integrity`, node hashes from `sha256sum ~/.nuget/packages/microsoft.playwright/&lt;Version&gt;/.playwright/node/*/node*`).
/// </summary>
public static class PlaywrightDriver
{
    /// <summary>The Microsoft.Playwright package version the plugin references (the driver must match it exactly).</summary>
    public const string Version = "1.62.0";

    /// <summary>The Node.js version the Microsoft.Playwright package bundles.</summary>
    public const string NodeVersion = "24.18.1";

    public const string CoreUrl = "https://registry.npmjs.org/playwright-core/-/playwright-core-" + Version + ".tgz";

    /// <summary>npm's integrity value for the playwright-core tarball (base64 SHA-512).</summary>
    public const string CoreSha512 = "nsNRyq0r2zsG8AcRHWknc9QRA5XCueC7gWMrs+Gx2tlZn9hcl8zudfh00lhJPY1DE7NmZ6bDsT9g2yey8mXljA==";

    public const long CoreBytes = 3_070_084;

    public static readonly DriverPlatform[] Platforms =
    {
        new("linux-x64", "linux-x64",
            "9f5eb6ac21845a66c493c91a253b1da32fd684e89e9b7202d4936982336be4ca",
            "f3432a45b03b2da0d270095fdd8813dc34cbea73f5fc8b18c7a384b7cf9b333a", 57_254_099),
        new("linux-arm64", "linux-arm64",
            "df224555a083b918e46260cc969838501b9f9a87140c1195e5b9597b56d5dae2",
            "a990a8ae388fc285ddbce280e63fca48cfd7695f632b66aec6ed581566eace99", 56_968_528),
        new("win32_x64", "win-x64",
            "ec56b84a7551893ab2324ebdfdc4ab974a63b4781162600b68a1293cc3e53765",
            "ac51903c4c111815d52280b1fdcc8da067cbb37e2fe1a765097b85c3292c8582", 37_177_316),
        new("darwin-x64", "darwin-x64",
            "6fb20fceacbb157c2f95825b80df4a454a0f6d81cdcd7bb81eeae9147e0e76ec",
            "cd7598be0b10583df334b3720d648cc2a22e650b122e6172c226964260138640", 53_284_823),
        new("darwin-arm64", "darwin-arm64",
            "eb02f7fab96d3d67de40c5ec8566096fcb4c2026728787683ae5a97eb612b941",
            "f480e325ee0ca9cb9eef00b5ca6057a2a104807a1b073f1bc373a55c67facff5", 52_089_613),
    };

    private const string StampFile = ".tally-driver.json";

    /// <summary>
    /// The driver build for a host, chosen the way Microsoft.Playwright's Driver.GetPath picks the folder it runs:
    /// Windows always uses win32_x64 (arm64 Windows runs it emulated), Linux and macOS by process architecture.
    /// Null where Playwright has no driver (32-bit ARM, other systems).
    /// </summary>
    public static DriverPlatform? For(OSPlatform os, Architecture arch)
    {
        string? id = null;
        if (os == OSPlatform.Windows)
        {
            id = "win32_x64";
        }
        else if (os == OSPlatform.Linux)
        {
            id = arch switch { Architecture.X64 => "linux-x64", Architecture.Arm64 => "linux-arm64", _ => null };
        }
        else if (os == OSPlatform.OSX)
        {
            id = arch switch { Architecture.X64 => "darwin-x64", Architecture.Arm64 => "darwin-arm64", _ => null };
        }

        return id == null ? null : Platforms.First(p => p.PlatformId == id);
    }

    public static DriverPlatform? ForThisHost()
    {
        var os = RuntimeInformation.IsOSPlatform(OSPlatform.Windows) ? OSPlatform.Windows
            : RuntimeInformation.IsOSPlatform(OSPlatform.OSX) ? OSPlatform.OSX
            : RuntimeInformation.IsOSPlatform(OSPlatform.Linux) ? OSPlatform.Linux
            : OSPlatform.Create("OTHER");
        return For(os, RuntimeInformation.ProcessArchitecture);
    }

    public static string NodePath(string root, DriverPlatform p) => Path.Combine(root, ".playwright", "node", p.PlatformId, p.NodeFileName);

    public static string CliPath(string root) => Path.Combine(root, ".playwright", "package", "cli.js");

    /// <summary>True when <paramref name="root"/> holds a complete driver of this version for this platform
    /// (as left by <see cref="InstallAsync"/>, or copied next to the plugin by a build).</summary>
    public static bool IsPresent(string root, DriverPlatform p, bool requireStamp)
    {
        if (!File.Exists(NodePath(root, p)) || !File.Exists(CliPath(root)))
        {
            return false;
        }

        if (PackageVersion(root) != Version)
        {
            return false;
        }

        if (!requireStamp)
        {
            return true;
        }

        try
        {
            return File.ReadAllText(Path.Combine(root, StampFile)).Contains($"\"{p.NodeSha256}\"", StringComparison.Ordinal);
        }
        catch (IOException)
        {
            return false;
        }
    }

    /// <summary>The playwright-core version in a driver folder, or null.</summary>
    public static string? PackageVersion(string root)
    {
        try
        {
            using var doc = JsonDocument.Parse(File.ReadAllText(Path.Combine(root, ".playwright", "package", "package.json")));
            return doc.RootElement.TryGetProperty("version", out var v) ? v.GetString() : null;
        }
        catch (Exception ex) when (ex is IOException or JsonException or UnauthorizedAccessException)
        {
            return null;
        }
    }

    /// <summary>
    /// Downloads, verifies and unpacks the driver into <paramref name="root"/> (replacing whatever is there). Works in a
    /// sibling folder and renames it into place at the end, so an interrupted run leaves no half driver behind.
    /// </summary>
    public static async Task InstallAsync(string root, DriverPlatform p, HttpClient http, Action<long>? progress, ILogger logger, CancellationToken ct)
    {
        var parent = Path.GetDirectoryName(Path.GetFullPath(root))!;
        Directory.CreateDirectory(parent);
        var work = Path.Combine(parent, "download-" + Guid.NewGuid().ToString("N")[..8]);
        var staging = Path.Combine(work, "driver");
        Directory.CreateDirectory(staging);
        try
        {
            long done = 0;
            void Advance(long n) => progress?.Invoke(done += n);

            var coreFile = Path.Combine(work, "playwright-core.tgz");
            logger.LogInformation("JellyTV browser: downloading {Url}", CoreUrl);
            await DownloadAsync(http, CoreUrl, coreFile, Advance, ct).ConfigureAwait(false);
            Verify(coreFile, SHA512.Create(), Convert.FromBase64String(CoreSha512), CoreUrl);

            var nodeFile = Path.Combine(work, p.ArchiveName);
            logger.LogInformation("JellyTV browser: downloading {Url}", p.ArchiveUrl);
            await DownloadAsync(http, p.ArchiveUrl, nodeFile, Advance, ct).ConfigureAwait(false);
            Verify(nodeFile, SHA256.Create(), Convert.FromHexString(p.ArchiveSha256), p.ArchiveUrl);

            await ExtractPackageAsync(coreFile, staging, ct).ConfigureAwait(false);
            var node = NodePath(staging, p);
            await ExtractNodeAsync(nodeFile, p, node, ct).ConfigureAwait(false);
            Verify(node, SHA256.Create(), Convert.FromHexString(p.NodeSha256), p.ArchiveEntry);
            if (PackageVersion(staging) != Version)
            {
                throw new InvalidDataException($"playwright-core in {CoreUrl} is not version {Version}");
            }

            File.WriteAllText(Path.Combine(staging, StampFile), JsonSerializer.Serialize(new
            {
                playwright = Version,
                node = NodeVersion,
                platform = p.PlatformId,
                nodeSha256 = p.NodeSha256,
                sources = new[] { CoreUrl, p.ArchiveUrl },
                installed = DateTimeOffset.UtcNow
            }));

            if (Directory.Exists(root))
            {
                Directory.Delete(root, recursive: true);
            }

            Directory.Move(staging, root);
        }
        finally
        {
            try
            {
                Directory.Delete(work, recursive: true);
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                logger.LogDebug(ex, "JellyTV browser: could not remove {Dir}", work);
            }
        }
    }

    private static async Task DownloadAsync(HttpClient http, string url, string file, Action<long> advance, CancellationToken ct)
    {
        using var resp = await http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false);
        if (!resp.IsSuccessStatusCode)
        {
            throw new HttpRequestException($"{url} answered {(int)resp.StatusCode} {resp.ReasonPhrase}");
        }

        var src = await resp.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
        await using (src.ConfigureAwait(false))
        {
            var dst = File.Create(file);
            await using (dst.ConfigureAwait(false))
            {
                var buf = new byte[81920];
                int n;
                while ((n = await src.ReadAsync(buf, ct).ConfigureAwait(false)) > 0)
                {
                    await dst.WriteAsync(buf.AsMemory(0, n), ct).ConfigureAwait(false);
                    advance(n);
                }
            }
        }
    }

    private static void Verify(string file, HashAlgorithm algorithm, byte[] expected, string what)
    {
        using (algorithm)
        using (var s = File.OpenRead(file))
        {
            var actual = algorithm.ComputeHash(s);
            if (!CryptographicOperations.FixedTimeEquals(actual, expected))
            {
                throw new InvalidDataException($"{what} failed verification: its hash is {Convert.ToHexString(actual).ToLowerInvariant()}, expected {Convert.ToHexString(expected).ToLowerInvariant()}");
            }
        }
    }

    /// <summary>Unpacks the npm tarball's package/ folder into &lt;root&gt;/.playwright/package/.</summary>
    private static async Task ExtractPackageAsync(string tgz, string root, CancellationToken ct)
    {
        var dest = Path.GetFullPath(Path.Combine(root, ".playwright"));
        var fs = File.OpenRead(tgz);
        await using (fs.ConfigureAwait(false))
        {
            var gz = new GZipStream(fs, CompressionMode.Decompress);
            await using (gz.ConfigureAwait(false))
            {
                using var tar = new TarReader(gz);
                var count = 0;
                while (await tar.GetNextEntryAsync(copyData: false, ct).ConfigureAwait(false) is { } entry)
                {
                    if (entry.EntryType is not (TarEntryType.RegularFile or TarEntryType.V7RegularFile)
                        || !entry.Name.StartsWith("package/", StringComparison.Ordinal))
                    {
                        continue;
                    }

                    var target = Path.GetFullPath(Path.Combine(dest, entry.Name));
                    if (!target.StartsWith(dest + Path.DirectorySeparatorChar, StringComparison.Ordinal))
                    {
                        throw new InvalidDataException("playwright-core tarball has an entry outside its folder: " + entry.Name);
                    }

                    await WriteEntryAsync(entry.DataStream, target, executable: false, ct).ConfigureAwait(false);
                    count++;
                }

                if (count == 0)
                {
                    throw new InvalidDataException("playwright-core tarball held no files");
                }
            }
        }
    }

    private static async Task ExtractNodeAsync(string archive, DriverPlatform p, string target, CancellationToken ct)
    {
        if (p.IsWindows)
        {
            using var zip = ZipFile.OpenRead(archive);
            var entry = zip.GetEntry(p.ArchiveEntry) ?? throw new InvalidDataException($"{p.ArchiveName} has no {p.ArchiveEntry}");
            var s = entry.Open();
            await using (s.ConfigureAwait(false))
            {
                await WriteEntryAsync(s, target, executable: true, ct).ConfigureAwait(false);
            }

            return;
        }

        var fs = File.OpenRead(archive);
        await using (fs.ConfigureAwait(false))
        {
            var gz = new GZipStream(fs, CompressionMode.Decompress);
            await using (gz.ConfigureAwait(false))
            {
                using var tar = new TarReader(gz);
                while (await tar.GetNextEntryAsync(copyData: false, ct).ConfigureAwait(false) is { } entry)
                {
                    if (entry.Name == p.ArchiveEntry
                        && entry.EntryType is TarEntryType.RegularFile or TarEntryType.V7RegularFile)
                    {
                        await WriteEntryAsync(entry.DataStream, target, executable: true, ct).ConfigureAwait(false);
                        return;
                    }
                }
            }
        }

        throw new InvalidDataException($"{p.ArchiveName} has no {p.ArchiveEntry}");
    }

    private static async Task WriteEntryAsync(Stream? data, string target, bool executable, CancellationToken ct)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(target)!);
        var output = File.Create(target);
        await using (output.ConfigureAwait(false))
        {
            if (data != null)
            {
                await data.CopyToAsync(output, ct).ConfigureAwait(false);
            }
        }

        if (!OperatingSystem.IsWindows())
        {
            File.SetUnixFileMode(target, executable
                ? UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute | UnixFileMode.GroupRead | UnixFileMode.GroupExecute | UnixFileMode.OtherRead | UnixFileMode.OtherExecute
                : UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.GroupRead | UnixFileMode.OtherRead);
        }
    }
}
