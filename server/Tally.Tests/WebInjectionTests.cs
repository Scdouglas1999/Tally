using Jellyfin.Plugin.Tally.Services;
using Xunit;

namespace Tally.Tests;

public class WebInjectionTests
{
    [Theory]
    [InlineData("GET", "/web/", true)]
    [InlineData("GET", "/web/index.html", true)]
    [InlineData("GET", "/jellyfin/web/index.html", true)]   // behind a base URL
    [InlineData("GET", "/Web/Index.html", true)]
    [InlineData("GET", "/web/main.jellyfin.bundle.js", false)]
    [InlineData("GET", "/web/configurationpage", false)]
    [InlineData("POST", "/web/index.html", false)]
    [InlineData("GET", null, false)]
    public void Only_The_Web_Client_Document_Is_Touched(string method, string? path, bool expected)
        => Assert.Equal(expected, IndexInjectionMiddleware.IsIndexRequest(method, path));

    [Fact]
    public void Script_Goes_Before_Closing_Body_Exactly_Once()
    {
        var once = IndexInjectionMiddleware.Inject("<html><body><div id=\"reactRoot\"></div></body></html>");

        Assert.Contains("src=\"../JellyTV/Assets/inject.js\"></script></body></html>", once);
        Assert.Equal(once, IndexInjectionMiddleware.Inject(once)); // idempotent
    }

    [Fact]
    public void Document_Without_A_Body_Tag_Still_Gets_The_Script()
        => Assert.EndsWith("inject.js\"></script>", IndexInjectionMiddleware.Inject("<div>odd</div>"));
}
