using System.IO;
using System.Linq;
using System.Text.RegularExpressions;
using Jellyfin.Plugin.Tally;
using Jellyfin.Plugin.Tally.Services;
using Xunit;

namespace Tally.Tests;

public class WebInjectionTests
{
    // the shape of jellyfin-web's index.html (10.10–12.1): a title, a theme-color meta, a React root
    private const string Index = "<!doctype html><html class=\"preload\" dir=\"ltr\"><head><meta charset=\"utf-8\">"
        + "<meta id=\"themeColor\" name=\"theme-color\" content=\"#202020\"><link rel=\"shortcut icon\" href=\"favicon.ico\">"
        + "<title>Jellyfin</title></head><body><div id=\"reactRoot\"></div></body></html>";

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

    [Fact]
    public void Takeover_Alone_Leaves_No_Trace_Of_The_Look()
    {
        var html = IndexInjectionMiddleware.Inject(Index, liveTv: true, look: false);

        Assert.Contains("inject.js", html);
        Assert.DoesNotContain("web-look", html);
        Assert.DoesNotContain("tally-icon", html);
        Assert.Contains("<title>Jellyfin</title>", html);
        Assert.Contains("content=\"#202020\"", html);
        Assert.DoesNotContain("data-tally-look", html);
    }

    [Fact]
    public void Both_Off_Serves_The_Document_Untouched()
        => Assert.Equal(Index, IndexInjectionMiddleware.Inject(Index, liveTv: false, look: false));

    [Fact]
    public void Look_Puts_Its_Stylesheet_And_Icon_In_The_Head_And_Renames_The_Document()
    {
        var html = IndexInjectionMiddleware.Inject(Index, liveTv: false, look: true);
        var head = html[..html.IndexOf("</head>", System.StringComparison.Ordinal)];

        Assert.Contains("rel=\"stylesheet\" href=\"../JellyTV/Assets/web-look.css\"", head);
        Assert.Contains("rel=\"icon\" type=\"image/svg+xml\" href=\"../JellyTV/Assets/tally-icon.svg\"", head);
        Assert.Contains("<title>Tally</title>", head);
        Assert.StartsWith("<!doctype html><html data-tally-look class=\"preload\"", html);
        Assert.DoesNotContain("<title>Jellyfin</title>", html);
        Assert.Contains("content=\"#0e0f0e\"", head);
        Assert.Contains("data-sports=\"0\" defer src=\"../JellyTV/Assets/web-look.js\"></script></body>", html);
        Assert.DoesNotContain("inject.js", html);
    }

    [Fact]
    public void Look_With_Takeover_Offers_Sports_And_Loads_Both_Scripts_Once()
    {
        var html = IndexInjectionMiddleware.Inject(Index, liveTv: true, look: true);

        Assert.Contains("data-sports=\"1\"", html);
        Assert.Single(Regex.Matches(html, "web-look\\.js"));
        Assert.Single(Regex.Matches(html, "web-look\\.css"));
        Assert.EndsWith("inject.js\"></script></body></html>", html);
        Assert.Equal(html, IndexInjectionMiddleware.Inject(html, liveTv: true, look: true)); // idempotent
    }

    [Fact]
    public void Look_Copes_With_An_Odd_Document()
    {
        var html = IndexInjectionMiddleware.Inject("<div>odd</div>", liveTv: false, look: true);

        Assert.StartsWith("<link", html);   // no <head>, no <html>: the links go first, nothing else changes
        Assert.EndsWith("web-look.js\"></script>", html);
    }

    [Fact]
    public void Look_Is_On_By_Default()
    {
        var config = new PluginConfiguration();
        Assert.True(config.WebLook);
        Assert.True(config.ReplaceLiveTv);
    }

    [Theory]
    [InlineData("web-look.css")]
    [InlineData("web-look.js")]
    [InlineData("tally-icon.svg")]
    [InlineData("inject.js")]
    public void Injected_Assets_Ship_In_The_Plugin(string file)
        => Assert.NotNull(Resource(file));

    [Fact]
    public void Look_Stylesheet_Only_Uses_The_Plugins_Own_Fonts_And_Files()
    {
        var css = new StreamReader(Resource("web-look.css")!).ReadToEnd();
        var urls = Regex.Matches(css, @"url\(([^)]+)\)").Select(m => m.Groups[1].Value.Trim('\'', '"')).ToList();

        Assert.NotEmpty(urls);
        foreach (var url in urls.Where(u => !u.StartsWith("data:", System.StringComparison.Ordinal)))
        {
            Assert.DoesNotContain("//", url);          // no CDN, no third-party request
            Assert.NotNull(Resource(url));             // resolves next to the stylesheet, in /JellyTV/Assets/
        }
    }

    private static Stream? Resource(string file)
        => typeof(Plugin).Assembly.GetManifestResourceStream("Jellyfin.Plugin.Tally.Web." + file);
}
