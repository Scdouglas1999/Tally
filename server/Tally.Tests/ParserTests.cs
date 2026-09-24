using Jellyfin.Plugin.Tally.Sources;
using Xunit;

namespace Tally.Tests;

public class M3uParserTests
{
    [Fact]
    public void Parses_Extinf_Attributes_And_Url()
    {
        var m3u = """
            #EXTM3U url-tvg="https://example.com/epg.xml"
            #EXTINF:-1 tvg-id="espn.us" tvg-logo="https://l/espn.png" group-title="Sports",ESPN HD
            https://s1.example.com/espn/index.m3u8
            #EXTINF:-1 group-title="News" http-referrer="https://ref.example/",News Now
            https://s2.example.com/news.m3u8
            """;

        var channels = M3uParser.Parse(m3u, "src1", "Test", new Dictionary<string, string> { ["Referer"] = "https://default/" });

        Assert.Equal(2, channels.Count);

        var espn = channels[0];
        Assert.Equal("ESPN HD", espn.Name);
        Assert.Equal("espn.us", espn.TvgId);
        Assert.Equal("https://l/espn.png", espn.LogoUrl);
        Assert.Equal("Sports", espn.Group);
        Assert.Equal("https://default/", espn.Headers["Referer"]);

        var news = channels[1];
        Assert.Equal("https://ref.example/", news.Headers["Referer"]); // per-channel override wins
        Assert.Equal(SourceChannelId( "src1", "https://s2.example.com/news.m3u8"), news.Id);

        Assert.Equal("https://example.com/epg.xml", M3uParser.ExtractEpgUrl(m3u));
    }

    [Fact]
    public void Skips_Lines_Without_Extinf()
    {
        var m3u = "#EXTM3U\nhttps://orphan.example.com/x.m3u8\n";
        Assert.Empty(M3uParser.Parse(m3u, "s", "n", new()));
    }

    private static string SourceChannelId(string src, string url)
        => Jellyfin.Plugin.Tally.Models.SourceChannel.MakeId(src, url);
}

public class XmlTvParserTests
{
    [Fact]
    public void Parses_Channels_And_Programmes()
    {
        var xml = """
            <?xml version="1.0"?>
            <tv>
              <channel id="espn.us"><display-name>ESPN</display-name><icon src="https://i/espn.png"/></channel>
              <programme start="20260920200000 +0000" stop="20260920220000 +0000" channel="espn.us">
                <title>Monday Night Football</title>
                <desc>Chiefs at Bills</desc>
                <category>Sports</category>
              </programme>
              <programme start="20260920220000 +0000" stop="20260920230000 +0000" channel="espn.us">
                <title>SportsCenter</title>
              </programme>
            </tv>
            """;

        using var ms = new MemoryStream(System.Text.Encoding.UTF8.GetBytes(xml));
        var r = XmlTvParser.Parse(ms);

        Assert.Equal("ESPN", r.ChannelNames["espn.us"]);
        Assert.Equal("https://i/espn.png", r.ChannelIcons["espn.us"]);
        Assert.Equal(2, r.Programmes["espn.us"].Count);
        Assert.Equal("Monday Night Football", r.Programmes["espn.us"][0].Title);
        Assert.Equal("Chiefs at Bills", r.Programmes["espn.us"][0].Description);
        Assert.Equal(new DateTimeOffset(2026, 9, 20, 20, 0, 0, TimeSpan.Zero), r.Programmes["espn.us"][0].Start);
    }

    [Fact]
    public void Parses_Timezone_Offset()
    {
        var t = XmlTvParser.ParseTime("20260920153000 -0530");
        Assert.Equal(TimeSpan.FromMinutes(-330), t.Offset);
        Assert.Equal(21, t.UtcDateTime.Hour); // 15:30 -0530 = 21:00 UTC
    }
}
