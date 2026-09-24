using Jellyfin.Plugin.Tally.Api;
using Xunit;

namespace Tally.Tests;

public class TvAppTests
{
    [Theory]
    [InlineData("manifest.json", "application/json", "no-cache")]
    [InlineData("index.html", "text/html", "no-cache")]
    [InlineData("app.3vfZ6pjk.js", "application/javascript", "public, max-age=31536000, immutable")]
    [InlineData("app.Bqf4963p.css", "text/css", "public, max-age=31536000, immutable")]
    [InlineData("assets/ibm_plex_sans_bold.CsEuyrSQ.ttf", "font/ttf", "public, max-age=31536000, immutable")]
    [InlineData("hls-1.7.3.min.js", "application/javascript", "public, max-age=31536000, immutable")]
    [InlineData("hls.js-LICENSE.txt", "text/plain", "no-cache")]
    public void Manifest_And_Page_Are_Never_Cached_Hashed_Files_Are(string path, string mime, string cache)
    {
        var (contentType, cacheControl) = TvAppController.Describe(path);

        Assert.Equal(mime, contentType);
        Assert.Equal(cache, cacheControl);
    }

    [Theory]
    [InlineData(null, "index.html")]
    [InlineData("", "index.html")]
    [InlineData("assets/x.ttf", "assets/x.ttf")]
    [InlineData("assets//x.ttf", "assets/x.ttf")]
    [InlineData("../Jellyfin.Plugin.JellyTV.dll", null)]
    [InlineData("assets/../../x", null)]
    [InlineData("./manifest.json", null)]
    public void Paths_Stay_Inside_The_Bundle(string? path, string? expected)
        => Assert.Equal(expected, TvAppController.Normalize(path));
}
