using System.IO.Compression;
using Xunit;

namespace Tally.Tests;

/// <summary>
/// What the plugin zips in dist/ may carry. build.sh runs this after writing them (TALLY_REQUIRE_DIST=1); in a plain
/// test run it checks whatever zips are there.
/// </summary>
public class ReleaseZipTests
{
    [Fact]
    public void Release_Zips_Carry_No_Playwright_Driver()
    {
        var zips = DistZips();
        if (Environment.GetEnvironmentVariable("TALLY_REQUIRE_DIST") == "1")
        {
            Assert.NotEmpty(zips);
        }

        foreach (var path in zips)
        {
            using var zip = ZipFile.OpenRead(path);
            var names = zip.Entries.Select(e => e.FullName.Replace('\\', '/')).ToList();

            // the driver is platform specific (node for linux-x64 alone is 118 MB): the plugin fetches its host's own
            Assert.DoesNotContain(names, n => n.Split('/').Contains(".playwright"));
            Assert.DoesNotContain(names, n => Path.GetFileName(n) is "node" or "node.exe" or "playwright.ps1" or "cli.js");
            Assert.Contains("Jellyfin.Plugin.JellyTV.dll", names);
            Assert.Contains("Microsoft.Playwright.dll", names);
            Assert.Contains("meta.json", names);
            Assert.True(zip.Entries.Sum(e => e.Length) < 15_000_000, $"{Path.GetFileName(path)} unpacks to {zip.Entries.Sum(e => e.Length)} bytes");
        }
    }

    private static List<string> DistZips()
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir != null && !File.Exists(Path.Combine(dir.FullName, "build.sh")))
        {
            dir = dir.Parent;
        }

        var dist = dir == null ? null : Path.Combine(dir.FullName, "dist");
        return dist != null && Directory.Exists(dist)
            ? Directory.GetFiles(dist, "Tally-server-*.zip").ToList()
            : new List<string>();
    }
}
