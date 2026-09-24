using System.Net;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using Jellyfin.Plugin.Tally.Services;
using Microsoft.Extensions.Logging.Abstractions;
using Xunit;

namespace Tally.Tests;

/// <summary>The Playwright driver the plugin downloads on first use instead of shipping it in the zips.</summary>
public class PlaywrightDriverTests
{
    [Theory]
    [InlineData("WINDOWS", Architecture.X64, "win32_x64", "https://nodejs.org/dist/v24.18.1/node-v24.18.1-win-x64.zip", "node-v24.18.1-win-x64/node.exe", "node.exe")]
    [InlineData("WINDOWS", Architecture.Arm64, "win32_x64", "https://nodejs.org/dist/v24.18.1/node-v24.18.1-win-x64.zip", "node-v24.18.1-win-x64/node.exe", "node.exe")]
    [InlineData("LINUX", Architecture.X64, "linux-x64", "https://nodejs.org/dist/v24.18.1/node-v24.18.1-linux-x64.tar.gz", "node-v24.18.1-linux-x64/bin/node", "node")]
    [InlineData("LINUX", Architecture.Arm64, "linux-arm64", "https://nodejs.org/dist/v24.18.1/node-v24.18.1-linux-arm64.tar.gz", "node-v24.18.1-linux-arm64/bin/node", "node")]
    [InlineData("OSX", Architecture.X64, "darwin-x64", "https://nodejs.org/dist/v24.18.1/node-v24.18.1-darwin-x64.tar.gz", "node-v24.18.1-darwin-x64/bin/node", "node")]
    [InlineData("OSX", Architecture.Arm64, "darwin-arm64", "https://nodejs.org/dist/v24.18.1/node-v24.18.1-darwin-arm64.tar.gz", "node-v24.18.1-darwin-arm64/bin/node", "node")]
    public void Each_Host_Gets_Its_Own_Node_Build(string os, Architecture arch, string platformId, string url, string entry, string exe)
    {
        var p = PlaywrightDriver.For(OSPlatform.Create(os), arch);

        Assert.NotNull(p);
        Assert.Equal(platformId, p!.PlatformId);
        Assert.Equal(url, p.ArchiveUrl);
        Assert.Equal(entry, p.ArchiveEntry);
        Assert.Equal(exe, p.NodeFileName);
        Assert.Equal(Path.Combine("root", ".playwright", "node", platformId, exe), PlaywrightDriver.NodePath("root", p));
        Assert.InRange(p.DownloadBytes / 1_000_000, 35, 65); // what the plugin page announces
    }

    [Theory]
    [InlineData("LINUX", Architecture.Arm)]
    [InlineData("LINUX", Architecture.X86)]
    [InlineData("OSX", Architecture.Arm)]
    [InlineData("FREEBSD", Architecture.X64)]
    public void Hosts_Without_A_Playwright_Driver_Get_None(string os, Architecture arch)
        => Assert.Null(PlaywrightDriver.For(OSPlatform.Create(os), arch));

    [Fact]
    public void The_Package_Comes_From_The_Npm_Registry_At_The_Referenced_Version()
    {
        Assert.Equal("https://registry.npmjs.org/playwright-core/-/playwright-core-1.62.0.tgz", PlaywrightDriver.CoreUrl);
        Assert.Equal(64, Convert.FromBase64String(PlaywrightDriver.CoreSha512).Length);
    }

    /// <summary>Fails after a Microsoft.Playwright update until the pins in PlaywrightDriver follow it.</summary>
    [Fact]
    public void Pins_Match_The_Referenced_Microsoft_Playwright()
    {
        var assembly = typeof(Microsoft.Playwright.Playwright).Assembly.GetName().Version!;
        Assert.Equal(PlaywrightDriver.Version, assembly.ToString(3));

        var package = NuGetPackageDriver();
        Assert.Equal(PlaywrightDriver.Version, PlaywrightDriver.PackageVersion(package));
        foreach (var p in PlaywrightDriver.Platforms)
        {
            var node = PlaywrightDriver.NodePath(package, p);
            Assert.True(File.Exists(node), $"the package has no {node}");
            using var s = File.OpenRead(node);
            Assert.Equal(p.NodeSha256, Convert.ToHexString(SHA256.HashData(s)).ToLowerInvariant());
        }
    }

    /// <summary>The folder the plugin fills is the one Microsoft.Playwright runs the driver from.</summary>
    [Fact]
    public void Microsoft_Playwright_Runs_The_Driver_From_That_Layout()
    {
        var p = PlaywrightDriver.ForThisHost();
        Assert.NotNull(p);
        var root = Path.Combine(Path.GetTempPath(), "tally-pw-" + Guid.NewGuid().ToString("N"));
        var before = Environment.GetEnvironmentVariable("PLAYWRIGHT_DRIVER_SEARCH_PATH");
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(PlaywrightDriver.NodePath(root, p!))!);
            File.WriteAllText(PlaywrightDriver.NodePath(root, p!), string.Empty);
            Environment.SetEnvironmentVariable("PLAYWRIGHT_DRIVER_SEARCH_PATH", root);

            var driver = typeof(Microsoft.Playwright.Playwright).Assembly.GetType("Microsoft.Playwright.Helpers.Driver", throwOnError: true)!;
            var result = driver.GetMethod("GetExecutablePath", BindingFlags.Static | BindingFlags.NonPublic)!.Invoke(null, null)!;
            var exe = (string)result.GetType().GetField("Item1")!.GetValue(result)!;
            var args = (Delegate)result.GetType().GetField("Item2")!.GetValue(result)!;

            Assert.Equal(Path.GetFullPath(PlaywrightDriver.NodePath(root, p!)), exe);
            Assert.Contains(PlaywrightDriver.CliPath(root), (string)args.DynamicInvoke((string?)null)!);
        }
        finally
        {
            Environment.SetEnvironmentVariable("PLAYWRIGHT_DRIVER_SEARCH_PATH", before);
            Directory.Delete(root, recursive: true);
        }
    }

    [Fact]
    public async Task A_Download_That_Fails_Verification_Leaves_Nothing_Behind()
    {
        var p = PlaywrightDriver.For(OSPlatform.Linux, Architecture.X64)!;
        var parent = Path.Combine(Path.GetTempPath(), "tally-pw-" + Guid.NewGuid().ToString("N"));
        var root = Path.Combine(parent, "driver-" + PlaywrightDriver.Version);
        var requested = new List<string>();
        using var http = new HttpClient(new FakeHandler(requested));
        try
        {
            var ex = await Assert.ThrowsAsync<InvalidDataException>(
                () => PlaywrightDriver.InstallAsync(root, p, http, null, NullLogger.Instance, CancellationToken.None));

            Assert.Contains("failed verification", ex.Message);
            Assert.Equal(new[] { PlaywrightDriver.CoreUrl }, requested); // stopped at the first bad file
            Assert.False(Directory.Exists(root));
            Assert.Empty(Directory.EnumerateFileSystemEntries(parent)); // no partial download left over
            Assert.False(PlaywrightDriver.IsPresent(root, p, requireStamp: true));
        }
        finally
        {
            Directory.Delete(parent, recursive: true);
        }
    }

    [Fact]
    public void A_Driver_Of_Another_Version_Does_Not_Count()
    {
        var p = PlaywrightDriver.For(OSPlatform.Linux, Architecture.X64)!;
        var root = Path.Combine(Path.GetTempPath(), "tally-pw-" + Guid.NewGuid().ToString("N"));
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(PlaywrightDriver.NodePath(root, p))!);
            File.WriteAllText(PlaywrightDriver.NodePath(root, p), string.Empty);
            Directory.CreateDirectory(Path.GetDirectoryName(PlaywrightDriver.CliPath(root))!);
            File.WriteAllText(PlaywrightDriver.CliPath(root), string.Empty);
            var packageJson = Path.Combine(root, ".playwright", "package", "package.json");

            File.WriteAllText(packageJson, "{\"name\":\"playwright-core\",\"version\":\"1.49.0\"}");
            Assert.False(PlaywrightDriver.IsPresent(root, p, requireStamp: false)); // a stale build folder's driver

            File.WriteAllText(packageJson, "{\"name\":\"playwright-core\",\"version\":\"" + PlaywrightDriver.Version + "\"}");
            Assert.True(PlaywrightDriver.IsPresent(root, p, requireStamp: false));
            Assert.False(PlaywrightDriver.IsPresent(root, p, requireStamp: true)); // not one the plugin finished installing
        }
        finally
        {
            Directory.Delete(root, recursive: true);
        }
    }

    /// <summary>The Microsoft.Playwright package in the NuGet cache, which carries the driver for every platform.</summary>
    private static string NuGetPackageDriver()
    {
        var cache = Environment.GetEnvironmentVariable("NUGET_PACKAGES");
        if (string.IsNullOrEmpty(cache))
        {
            cache = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".nuget", "packages");
        }

        return Path.Combine(cache, "microsoft.playwright", PlaywrightDriver.Version);
    }

    private sealed class FakeHandler : HttpMessageHandler
    {
        private readonly List<string> _requested;

        public FakeHandler(List<string> requested) => _requested = requested;

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            _requested.Add(request.RequestUri!.ToString());
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content = new ByteArrayContent(new byte[] { 1, 2, 3 }) });
        }
    }
}
