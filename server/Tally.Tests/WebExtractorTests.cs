using System.Net;
using System.Net.Http;
using Jellyfin.Plugin.Tally.Sources;
using Microsoft.Extensions.Logging.Abstractions;
using Xunit;

namespace Tally.Tests;

public class WebExtractorTests
{
    private const string Ua = "TestAgent/1.0";

    private static WebExtractor Extractor(Func<string, HttpResponseMessage> router)
    {
        var handler = new StubHandler(router);
        return new WebExtractor(new HttpClient(handler), NullLogger.Instance, Ua);
    }

    [Fact]
    public async Task Finds_Manifest_In_Page_Source()
    {
        var ex = Extractor(url => url.Contains("page")
            ? Html("<html><head><title>NBA Finals</title></head><body>" +
                   "<script>var src='https://cdn.example.com/nba/live.m3u8?token=abc';</script></body></html>")
            : Playlist());

        var streams = await ex.ExtractAsync("https://site.example/page", 12, CancellationToken.None);
        var s = Assert.Single(streams);
        Assert.Equal("https://cdn.example.com/nba/live.m3u8?token=abc", s.Url);
        Assert.Equal("https://site.example/page", s.Referer);
    }

    [Fact]
    public async Task Follows_Iframe_And_Extracts_Embed_Stream()
    {
        var ex = Extractor(url => url.EndsWith(".m3u8")
            ? Playlist()
            : url.Contains("embed")
                ? Html("<html><body><script>file: 'https://cdn.x/embed.m3u8'</script></body></html>")
                : Html("<html><head><title>Game</title></head><body><iframe src=\"https://x.example/embed/1\"></iframe></body></html>"));

        var streams = await ex.ExtractAsync("https://site.example/page", 12, CancellationToken.None);
        var s = Assert.Single(streams);
        Assert.Equal("https://cdn.x/embed.m3u8", s.Url);
        Assert.Equal("https://x.example/embed/1", s.Referer); // referer = embed page, not outer page
    }

    [Fact]
    public async Task Finds_The_Other_Links_A_Page_Switches_Its_Player_To()
    {
        // Modeled on a real listing site's event page: one iframe, and "Link" buttons that swap the iframe's id by script.
        const string eventPage = "<html><head><title>Kansas City Chiefs vs Buffalo Bills</title></head><body>" +
            "<iframe id=\"player\" src=\"https://embeds.source.example/stream-embed/1000\"></iframe>" +
            "<button id=\"stream-btn-1000\" onclick=\"changeStream(1000)\">Link 1</button>" +
            "<button id=\"stream-btn-1001\" onclick=\"changeStream(1001)\">Link 2 HD</button>" +
            "<span>Kickoff 1700000000</span></body></html>";
        var ex = Extractor(url => url.EndsWith(".m3u8")
            ? Playlist()
            : url.Contains("stream-embed/")
                ? Html($"<html><body><script>var source = 'https://cdn.source.example/{url[^4..]}/index.m3u8';</script></body></html>")
                : Html(eventPage));

        var streams = await ex.ExtractAsync("https://site.example/nfl/kansas-city-chiefs-buffalo-bills/123456", 12, CancellationToken.None);

        Assert.Equal(new[] { "https://cdn.source.example/1000/index.m3u8", "https://cdn.source.example/1001/index.m3u8" },
            streams.Select(s => s.Url).OrderBy(u => u, StringComparer.Ordinal));
        Assert.All(streams, s => Assert.Equal("Kansas City Chiefs Buffalo Bills", s.Name)); // one event: they merge into one channel
        Assert.Empty(WebExtractor.SiblingEmbeds("https://embeds.source.example/stream-embed/1000", "<p>1001 viewers</p>"));
    }

    [Fact]
    public async Task Decodes_Atob_Manifest()
    {
        var b64 = Convert.ToBase64String(System.Text.Encoding.UTF8.GetBytes("https://cdn.z/hidden.m3u8"));
        var ex = Extractor(url => url.Contains("page")
            ? Html($"<html><body><script>var s=atob(\"{b64}\");</script></body></html>")
            : Playlist());

        var streams = await ex.ExtractAsync("https://site.example/page", 12, CancellationToken.None);
        Assert.Single(streams);
        Assert.Equal("https://cdn.z/hidden.m3u8", streams[0].Url);
    }

    [Fact]
    public async Task Skips_Dead_Manifests()
    {
        var ex = Extractor(url => url.Contains("page")
            ? Html("<html><body>https://cdn.x/a.m3u8</body></html>")
            : new HttpResponseMessage(HttpStatusCode.NotFound));

        var streams = await ex.ExtractAsync("https://site.example/page", 12, CancellationToken.None);
        Assert.Empty(streams);
    }

    [Fact]
    public async Task EventPage_Embed_Extensionless_Manifest_Named_From_Slug()
    {
        // listing-site chain: /mlb/team-a-team-b/1376053 embeds
        // /new-stream-embed/56866 whose HTML carries a /playlist/…/load-playlist URL.
        var ex = Extractor(url =>
            url.Contains("load-playlist")
                ? Playlist()
                : url.Contains("new-stream-embed")
                    ? Html("<html><body><script>const source = \"https://cdn.x/playlist/56866/load-playlist\";</script></body></html>")
                    : Html("<html><head><title>Live</title></head><body><iframe src=\"https://emb.x/new-stream-embed/56866\"></iframe></body></html>"));

        var streams = await ex.ExtractAsync("https://site.example/mlb/new-york-yankees-arizona-diamondbacks/1376053", 12, CancellationToken.None);
        var s = Assert.Single(streams);
        Assert.Equal("https://cdn.x/playlist/56866/load-playlist", s.Url);
        Assert.Equal("https://emb.x/new-stream-embed/56866", s.Referer);
        Assert.Equal("New York Yankees Arizona Diamondbacks", s.Name);
    }

    [Fact]
    public void Classifier_Groups_And_Cleans()
    {
        Assert.Equal("Basketball", StreamClassifier.GroupFor("Lakers vs Celtics", ""));
        Assert.Equal("Soccer", StreamClassifier.GroupFor("Arsenal vs Chelsea - Premier League", ""));
        Assert.Equal("Sports Networks", StreamClassifier.GroupFor("ESPN HD", ""));
        Assert.True(StreamClassifier.LooksLikeEvent("Team A @ Team B"));
        Assert.Equal("Lakers vs Celtics", StreamClassifier.CleanName("Watch Lakers vs Celtics Live Stream HD"));
    }

    private static HttpResponseMessage Html(string body) =>
        new(HttpStatusCode.OK) { Content = new StringContent(body, System.Text.Encoding.UTF8, "text/html") };

    private static HttpResponseMessage Playlist() =>
        new(HttpStatusCode.OK) { Content = new StringContent("#EXTM3U\n#EXT-X-VERSION:3\n") };

    private sealed class StubHandler : HttpMessageHandler
    {
        private readonly Func<string, HttpResponseMessage> _router;
        public StubHandler(Func<string, HttpResponseMessage> router) => _router = router;
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
            => Task.FromResult(_router(request.RequestUri!.ToString()));
    }
}
