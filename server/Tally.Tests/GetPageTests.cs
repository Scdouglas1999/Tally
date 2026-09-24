using Jellyfin.Plugin.Tally.Api;
using Xunit;

namespace Tally.Tests;

public class GetPageTests
{
    private const string Template = "<body class=\"{{HAS_CODE}}\"><code>{{APP_ADDRESS}}</code><code>{{CODE}}</code><a href=\"{{SERVER}}/web/\">{{SERVER}}</a></body>";

    [Fact]
    public void Address_Typed_On_A_Remote_Drops_The_Scheme_A_Tv_Assumes_Anyway()
    {
        var html = GetController.Render(Template, "http://203.0.113.7:8096", null);

        Assert.Contains("<code>203.0.113.7:8096/JellyTV/app</code>", html);
        Assert.Contains("href=\"http://203.0.113.7:8096/web/\"", html);
        Assert.Contains("class=\"no-code\"", html);
    }

    [Fact]
    public void Https_Is_Kept_Because_A_Tv_Would_Not_Guess_It()
        => Assert.Contains("<code>https://tv.example.org/JellyTV/app</code>", GetController.Render(Template, "https://tv.example.org", ""));

    [Fact]
    public void A_Downloader_Code_Switches_The_Page_To_The_Code()
    {
        var html = GetController.Render(Template, "http://h", " 48213 ");

        Assert.Contains("class=\"has-code\"", html);
        Assert.Contains("<code>48213</code>", html);
    }

    [Fact]
    public void A_Hostile_Host_Header_Cannot_Inject_Markup()
    {
        var html = GetController.Render(Template, "http://x\"><script>alert(1)</script>", "<b>");

        Assert.DoesNotContain("<script>", html);
        Assert.DoesNotContain("<b>", html);
    }

    [Theory]
    [InlineData("https://github.com/Scdouglas1999/Tally/releases/latest/download/Tally.apk", true)]
    [InlineData("http://192.168.1.50:8096/files/Tally.apk", true)]
    [InlineData("", false)]
    [InlineData(null, false)]
    [InlineData("/relative/path.apk", false)]
    [InlineData("javascript:alert(1)", false)]
    [InlineData("file:///etc/passwd", false)]
    public void The_Tv_Is_Only_Sent_To_An_Absolute_Web_Address(string? configured, bool ok)
        => Assert.Equal(ok, GetController.TryGetAppTarget(configured, out _));

    [Fact]
    public void The_Shipped_Page_Has_Every_Placeholder_The_Controller_Fills()
    {
        using var stream = typeof(GetController).Assembly.GetManifestResourceStream("Jellyfin.Plugin.Tally.Web.get.html");
        Assert.NotNull(stream);
        var page = new System.IO.StreamReader(stream!).ReadToEnd();

        foreach (var placeholder in new[] { "{{SERVER}}", "{{APP_ADDRESS}}", "{{CODE}}", "{{HAS_CODE}}" })
        {
            Assert.Contains(placeholder, page);
        }

        Assert.DoesNotContain("{{", GetController.Render(page, "http://h:1", "1"));
    }
}
