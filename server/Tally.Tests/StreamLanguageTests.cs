using System.Net;
using System.Net.Http;
using System.Text.Json;
using System.Text.Json.Nodes;
using Jellyfin.Plugin.Tally;
using Jellyfin.Plugin.Tally.Api;
using Jellyfin.Plugin.Tally.Client;
using Jellyfin.Plugin.Tally.Live;
using Jellyfin.Plugin.Tally.Models;
using Jellyfin.Plugin.Tally.Scores;
using Jellyfin.Plugin.Tally.Sources;
using Microsoft.Extensions.Logging.Abstractions;
using Xunit;

namespace Tally.Tests;

public class StreamLanguageDetectorTests
{
    [Theory]
    [InlineData("ESPN Deportes", "es")]
    [InlineData("Fox Deportes HD", "es")]
    [InlineData("TUDN", "es")]
    [InlineData("Univision", "es")]
    [InlineData("UniMás", "es")]
    [InlineData("Telemundo", "es")]
    [InlineData("Galavisión", "es")]
    [InlineData("beIN Sports Español", "es")]
    [InlineData("Colts vs Texans (Espanol)", "es")]
    [InlineData("Spanish", "es")]
    [InlineData("Watch in Spanish", "es")]
    [InlineData("Chiefs vs Bills [ES]", "es")]
    [InlineData("Chiefs vs Bills (es)", "es")]
    [InlineData("ES: Fox Sports", "es")]
    [InlineData("ES | Deportes", "es")]
    [InlineData("US: Univision", "es")]
    [InlineData("Castellano", "es")]
    [InlineData("LATINO", "es")]
    [InlineData("Ver Chiefs vs Bills en vivo", "es")]
    [InlineData("English", "en")]
    [InlineData("Link 1 English", "en")]
    [InlineData("Chiefs vs Bills (EN)", "en")]
    [InlineData("Português", "pt")]
    [InlineData("PT: Sport TV 1", "pt")]
    [InlineData("Français", "fr")]
    [InlineData("[FR] Canal+", "fr")]
    [InlineData("Deutsch", "de")]
    [InlineData("Italiano", "it")]
    [InlineData("beIN Sports Arabic", "ar")]
    [InlineData("بي إن سبورت", "ar")]
    [InlineData("Матч ТВ", "ru")]
    public void Names_And_Labels_Say_Their_Language(string text, string language)
        => Assert.Equal(language, StreamLanguage.FromText(text));

    [Theory]
    [InlineData("Kansas City Chiefs vs Buffalo Bills")]
    [InlineData("Colts at Texans")]
    [InlineData("ESPN HD")]
    [InlineData("US: ESPN")]
    [InlineData("Link 2")]
    [InlineData("Watch")]
    [InlineData("NFL: Chiefs vs Bills")]
    [InlineData("Spanish La Liga: Barcelona vs Real Madrid")]
    [InlineData("French Open Final")]
    [InlineData("German Bundesliga - Bayern vs Dortmund")]
    [InlineData("Italian Serie A")]
    [InlineData("English Premier League: Arsenal vs Chelsea")]
    [InlineData("Saudi Arabia vs Argentina")]
    [InlineData("English / Español")]
    [InlineData("Denver Broncos")]
    [InlineData("Esperanza")]
    [InlineData("")]
    [InlineData(null)]
    public void Matchups_Leagues_And_Mixed_Labels_Say_Nothing(string? text)
        => Assert.Null(StreamLanguage.FromText(text));

    [Theory]
    [InlineData("https://site.example/es/nfl/colts-texans/123", "es")]
    [InlineData("https://site.example/es-mx/stream/1", "es")]
    [InlineData("https://cdn.example/hls/colts-texans-es/index.m3u8", "es")]
    [InlineData("https://cdn.example/live/feed-es.m3u8", "es")]
    [InlineData("https://cdn.example/espanol/1.m3u8", "es")]
    [InlineData("https://cdn.example/live/espn-deportes/index.m3u8", "es")]
    [InlineData("https://site.example/stream/spanish/1234", "es")]
    [InlineData("https://cdn.example/play.m3u8?id=4&lang=es", "es")]
    [InlineData("https://site.example/en/nfl/colts-texans/123", "en")]
    [InlineData("https://site.example/stream/english/1234", "en")]
    [InlineData("https://cdn.example/play.m3u8?audio=spa", "es")]
    [InlineData("https://site.example/watch/portugues/12", "pt")]
    public void Addresses_Say_Their_Language(string url, string language)
        => Assert.Equal(language, StreamLanguage.FromUrl(url));

    [Theory]
    [InlineData("https://site.example/nfl/indianapolis-colts-houston-texans/123456")]
    [InlineData("https://embeds.source.example/stream-embed/1000")]
    [InlineData("https://cdn.x/playlist/56866/load-playlist")]
    [InlineData("https://site.es/nfl/colts-texans/1")]                              // a country domain says nothing
    [InlineData("https://cdn.example/de/fra1/live.m3u8")]                           // an edge location, not German
    [InlineData("https://site.example/ver-partido-en-vivo/1")]                      // "en" alone is not English
    [InlineData("https://site.example/soccer/spanish-la-liga-barcelona-real-madrid/9")]
    [InlineData("https://site.example/soccer/atletico-de-madrid-sevilla/9")]
    [InlineData("not a url")]
    public void Ordinary_Addresses_Say_Nothing(string url)
        => Assert.Null(StreamLanguage.FromUrl(url));

    [Fact]
    public void M3u_Entries_Read_Language_Then_Name_Then_Group_Then_Country()
    {
        const string playlist = "#EXTM3U\n" +
            "#EXTINF:-1 tvg-id=\"espn\" tvg-language=\"Spanish\" group-title=\"Sports\",ESPN 2\nhttp://h/1.m3u8\n" +
            "#EXTINF:-1 tvg-id=\"fox\" group-title=\"ES | Deportes\",FOX\nhttp://h/2.m3u8\n" +
            "#EXTINF:-1 tvg-id=\"tele\" group-title=\"LATINO\",Canal 5\nhttp://h/3.m3u8\n" +
            "#EXTINF:-1 tvg-country=\"MX\",Canal 7\nhttp://h/4.m3u8\n" +
            "#EXTINF:-1 group-title=\"MEXICO\",Azteca Uno\nhttp://h/5.m3u8\n" +
            "#EXTINF:-1 tvg-language=\"Portuguese\",SporTV\nhttp://h/6.m3u8\n" +
            "#EXTINF:-1 tvg-country=\"US\",Univision\nhttp://h/7.m3u8\n" +
            "#EXTINF:-1 tvg-language=\"English\" tvg-country=\"MX\",ESPN\nhttp://h/8.m3u8\n" +
            "#EXTINF:-1 group-title=\"NFL\",Chiefs vs Bills\nhttp://h/9.m3u8\n" +
            "#EXTINF:-1 tvg-language=\"und\",Stadium\nhttp://h/10.m3u8\n";
        var channels = M3uParser.Parse(playlist, "src", "Src", new Dictionary<string, string>());
        Assert.Equal(new[] { "es", "es", "es", "es", "es", "pt", "es", "en", "en", "en" }, channels.Select(c => c.Language));
    }

    [Fact]
    public void A_Master_Whose_Every_Audio_Rendition_Is_Spanish_Is_Spanish()
    {
        const string spanish = "#EXTM3U\n" +
            "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"Español\",LANGUAGE=\"es\",DEFAULT=YES,URI=\"a/es.m3u8\"\n" +
            "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"b\",NAME=\"Español\",LANGUAGE=\"es-MX\",URI=\"b/es.m3u8\"\n" +
            "#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"s\",NAME=\"English\",LANGUAGE=\"en\",URI=\"s/en.m3u8\"\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=5000000,AUDIO=\"a\"\nv/1080.m3u8\n";
        Assert.Equal(new[] { "es", "es-MX" }, HlsParser.AudioLanguages(spanish));
        Assert.Equal("es", StreamLanguage.FromAudio(HlsParser.AudioLanguages(spanish)));

        const string both = "#EXTM3U\n" +
            "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"English\",LANGUAGE=\"en\",DEFAULT=YES,URI=\"a/en.m3u8\"\n" +
            "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"Español\",LANGUAGE=\"es\",URI=\"a/es.m3u8\"\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=5000000,AUDIO=\"a\"\nv/1080.m3u8\n";
        Assert.Null(StreamLanguage.FromAudio(HlsParser.AudioLanguages(both)));   // the player picks: nothing to conclude
        Assert.Null(StreamLanguage.FromAudio(HlsParser.AudioLanguages("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv.m3u8\n")));
        Assert.Null(StreamLanguage.FromAudio(new string?[] { "es", null }));
    }

    [Fact]
    public void The_Preference_Is_Read_From_The_Settings_And_Defaults_To_English()
    {
        Assert.Equal("es", StreamLanguage.Preferred(JsonNode.Parse("""{ "streamLanguage": "es" }""")!.AsObject()));
        Assert.Equal("en", StreamLanguage.Preferred(JsonNode.Parse("""{ "streamLanguage": "en" }""")!.AsObject()));
        Assert.Equal("en", StreamLanguage.Preferred(JsonNode.Parse("""{ "favorites": [] }""")!.AsObject()));
        Assert.Equal("en", StreamLanguage.Preferred(JsonNode.Parse("""{ "streamLanguage": 3 }""")!.AsObject()));
        Assert.Equal("en", StreamLanguage.Preferred(JsonNode.Parse("""{ "streamLanguage": "fr" }""")!.AsObject()));
        Assert.Equal("en", StreamLanguage.Preferred(null));
    }

    [Fact]
    public void Spanish_Channels_Are_Named_And_Grouped_Once_And_Other_Languages_Are_Left_Out()
    {
        var channels = new List<SourceChannel>
        {
            new() { Name = "Colts at Texans", Group = "American Football", Language = "en" },
            new() { Name = "Colts at Texans", Group = "American Football", Language = "es" },
            new() { Name = "ESPN Deportes", Group = "Sports Networks", Language = "es" },
            new() { Name = "beIN Arabic", Group = "Sports Networks", Language = "ar" },
            new() { Name = "Colts at Texans (Español)", Group = "American Football · Español", Language = "es" }
        };

        var (kept, dropped) = StreamLanguage.Apply(channels);
        Assert.Equal(1, dropped);
        Assert.Equal(new[] { "Colts at Texans", "Colts at Texans (Español)", "ESPN Deportes (Español)", "Colts at Texans (Español)" }, kept.Select(c => c.Name));
        Assert.Equal(new[] { "American Football", "American Football · Español", "Sports Networks · Español", "American Football · Español" }, kept.Select(c => c.Group));

        StreamLanguage.Apply(kept); // a second pass (a kept "last good" list) changes nothing
        Assert.Equal("Colts at Texans (Español)", kept[1].Name);
        Assert.Equal("American Football · Español", kept[1].Group);
    }
}

public class StreamLanguageWebTests
{
    private const string EventUrl = "https://site.example/nfl/indianapolis-colts-houston-texans/123456";

    [Fact]
    public async Task An_Event_Page_Offering_English_Spanish_And_French_Players_Tags_Each_Stream()
    {
        // one iframe and buttons that switch it (the owner's site's shape), plus a plain link to a Spanish player page
        const string eventPage = "<html><head><title>Indianapolis Colts vs Houston Texans</title></head><body>" +
            "<iframe id=\"player\" src=\"https://embeds.source.example/stream-embed/1000\"></iframe>" +
            "<button onclick=\"changeStream(1000)\">English</button>" +
            "<button onclick=\"changeStream(1001)\">ESPN Deportes</button>" +
            "<button onclick=\"changeStream(1002)\">Français</button>" +
            "<button onclick=\"changeStream(1003)\">Link 4 HD</button>" +
            "<a href=\"/watch/es-player/77\">Español</a></body></html>";
        var ex = new WebExtractor(new HttpClient(new Stub(url =>
            url.EndsWith(".m3u8", StringComparison.Ordinal) ? Playlist()
            : url.Contains("stream-embed/", StringComparison.Ordinal)
                ? Html($"<html><body><script>var source = 'https://cdn.source.example/{url[^4..]}/index.m3u8';</script></body></html>")
            : url.Contains("es-player", StringComparison.Ordinal)
                ? Html("<html><body><script>var source = 'https://cdn.source.example/77/index.m3u8';</script></body></html>")
            : Html(eventPage))), NullLogger.Instance, "TestAgent/1.0");

        var streams = await ex.ExtractAsync(EventUrl, 12, CancellationToken.None);
        string? Lang(string id) => streams.Single(s => s.Url == $"https://cdn.source.example/{id}/index.m3u8").Language;

        Assert.Equal("en", Lang("1000"));
        Assert.Equal("es", Lang("1001"));
        Assert.Equal("fr", Lang("1002"));
        Assert.Null(Lang("1003"));                        // nothing said: English
        Assert.Equal("es", Lang("77"));
        Assert.All(streams, s => Assert.Equal("Indianapolis Colts Houston Texans", s.Name)); // names are cleaned after
    }

    [Fact]
    public async Task A_Spanish_Event_Link_Or_Address_Tags_Everything_Under_It()
    {
        const string listing = "<html><body><a href=\"/nfl/colts-texans/123456\">Colts vs Texans</a>" +
            "<a href=\"/es/nfl/colts-texans/123457\">Colts vs Texans</a>" +
            "<a href=\"/nfl/colts-texans-spanish/123458\">Colts vs Texans (Spanish)</a></body></html>";
        var ex = new WebExtractor(new HttpClient(new Stub(url =>
            url.EndsWith(".m3u8", StringComparison.Ordinal) ? Playlist()
            : url.Contains("/12345", StringComparison.Ordinal)
                ? Html($"<html><head><title>Colts vs Texans</title></head><body><script>var source = 'https://cdn.source.example/{url[^6..]}/index.m3u8';</script></body></html>")
            : Html(listing))), NullLogger.Instance, "TestAgent/1.0");

        var streams = await ex.ExtractAsync("https://site.example/", 12, CancellationToken.None);
        Assert.Null(streams.Single(s => s.Url.Contains("/123456/", StringComparison.Ordinal)).Language);
        Assert.Equal("es", streams.Single(s => s.Url.Contains("/123457/", StringComparison.Ordinal)).Language);
        Assert.Equal("es", streams.Single(s => s.Url.Contains("/123458/", StringComparison.Ordinal)).Language);
    }

    [Fact]
    public async Task A_Game_Search_Finds_The_Spanish_Player_And_Makes_It_A_Channel_Of_Its_Own()
    {
        var site = new FixtureSite { SpanishFeed = true };
        var (m, games) = Manager(site);

        await m.SearchAsync(new[] { WantedGame.From(games[0]) }, CancellationToken.None);

        var english = Assert.Single(m.GetChannels(), c => c.Name == "Tampa Bay Rays at Philadelphia Phillies");
        var spanish = Assert.Single(m.GetChannels(), c => c.Name == "Tampa Bay Rays at Philadelphia Phillies (Español)");
        Assert.Equal(("en", 2), (english.Language, english.Candidates.Count));   // Link 1 and Link 2: one channel
        Assert.Equal(("es", 1), (spanish.Language, spanish.Candidates.Count));   // ESPN Deportes: never among them
        Assert.All(english.Candidates, c => Assert.Equal("en", c.Language));
        Assert.Equal("es", Assert.Single(spanish.Candidates).Language);
        Assert.Equal(english.Group + " · Español", spanish.Group);
        Assert.DoesNotContain(english.Candidates, c => c.Url.Contains("/7702/", StringComparison.Ordinal));
    }

    [Fact]
    public async Task The_English_Channel_Keeps_Its_Id_Whether_Or_Not_A_Spanish_Feed_Turns_Up()
    {
        async Task<IReadOnlyList<SourceChannel>> Scan(bool spanish)
        {
            var (m, games) = Manager(new FixtureSite { SpanishFeed = spanish });
            await m.SearchAsync(new[] { WantedGame.From(games[0]) }, CancellationToken.None);
            return m.GetChannels();
        }

        var before = await Scan(spanish: false);
        var with = await Scan(spanish: true);
        var after = await Scan(spanish: false);
        string IdOf(IReadOnlyList<SourceChannel> list) => list.Single(c => c.Name == "Tampa Bay Rays at Philadelphia Phillies").Id;

        Assert.DoesNotContain(before, c => c.Language == "es");
        Assert.Contains(with, c => c.Language == "es");
        Assert.Equal(IdOf(before), IdOf(with));
        Assert.Equal(IdOf(before), IdOf(after));
    }

    [Fact]
    public void Ids_Do_Not_Depend_On_Which_Language_Came_First()
    {
        static List<SourceChannel> Scan(params string[] languages)
        {
            var list = languages.Select((l, i) => new SourceChannel
            {
                SourceId = "web", Name = "Colts at Texans", Group = "American Football", Language = l, StreamUrl = "https://cdn/" + i
            }).ToList();
            var (kept, _) = StreamLanguage.Apply(list);
            ChannelIdentity.Assign(kept);
            return kept;
        }

        var alone = Scan("en").Single();
        var spanishFirst = Scan("es", "en");
        var spanishAfter = Scan("en", "es");
        Assert.Equal(alone.Id, spanishFirst.Single(c => c.Language == "en").Id);
        Assert.Equal(alone.Id, spanishAfter.Single(c => c.Language == "en").Id);
        Assert.Equal(spanishFirst.Single(c => c.Language == "es").Id, spanishAfter.Single(c => c.Language == "es").Id);
        Assert.NotEqual(alone.Id, spanishFirst.Single(c => c.Language == "es").Id);
    }

    private static (SourceManager Manager, List<GameInfo> Games) Manager(FixtureSite site)
    {
        var games = new List<GameInfo> { WantedGameTests.RaysAtPhillies("in", DateTimeOffset.UtcNow.AddMinutes(-20)) };
        var source = new SourceDefinition { Id = new Guid("5b0c3e8e-3a55-4f55-9d0e-0a1b2c3d4e5f"), Name = "Listing", Kind = SourceKind.Web, PageUrl = FixtureSite.Home, Enabled = true };
        var m = new SourceManager(new Factory(site.Handler()), NullLogger<SourceManager>.Instance)
        {
            DefinitionsOverride = () => new[] { source },
            GamesOverride = () => games,
            Pacer = new SearchPacer(TimeSpan.Zero),
            Clock = new FakeClock(DateTimeOffset.UtcNow)
        };
        return (m, games);
    }

    private static HttpResponseMessage Html(string body) =>
        new(HttpStatusCode.OK) { Content = new StringContent(body, System.Text.Encoding.UTF8, "text/html") };

    private static HttpResponseMessage Playlist() =>
        new(HttpStatusCode.OK) { Content = new StringContent("#EXTM3U\n#EXT-X-VERSION:3\n") };

    private sealed class Factory : IHttpClientFactory
    {
        private readonly HttpMessageHandler _handler;

        public Factory(HttpMessageHandler handler) => _handler = handler;

        public HttpClient CreateClient(string name) => new(_handler, disposeHandler: false);
    }

    private sealed class Stub : HttpMessageHandler
    {
        private readonly Func<string, HttpResponseMessage> _router;

        public Stub(Func<string, HttpResponseMessage> router) => _router = router;

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
            => Task.FromResult(_router(request.RequestUri!.ToString()));
    }
}

public class StreamLanguageGroupingTests
{
    private static SourceChannel Ch(string name, string language, string key = "", string tvg = "") => new()
    {
        SourceId = "src", Name = name, Language = language, StreamUrl = "https://cdn/" + name.GetHashCode(StringComparison.Ordinal) + language,
        GroupKey = key, TvgId = tvg
    };

    [Fact]
    public void The_Grouper_Never_Folds_Two_Languages_Together()
    {
        var channels = new List<SourceChannel>
        {
            Ch("Colts at Texans", "en", "web:colts at texans"),
            Ch("Colts at Texans 2", "en", "web:colts at texans"),
            Ch("Colts at Texans (Español)", "es", "web:colts at texans"),
            Ch("ESPN", "en", tvg: "espn.us"),
            Ch("ESPN Deportes (Español)", "es", tvg: "espn.us")
        };
        ChannelIdentity.Assign(channels);
        var games = new List<GameInfo>
        {
            new()
            {
                Id = "g1", State = "in",
                Away = new GameTeam { Name = "Indianapolis Colts", ShortName = "Colts", Abbr = "IND" },
                Home = new GameTeam { Name = "Houston Texans", ShortName = "Texans", Abbr = "HOU" }
            }
        };

        var result = ChannelGrouper.Group(channels, games);

        Assert.Equal(4, result.Channels.Count);
        var english = result.Channels.Single(c => c.Name == "Colts at Texans");
        Assert.Equal(2, english.Candidates.Count);
        Assert.All(english.Candidates, c => Assert.Equal("en", c.Language));
        Assert.Equal("es", Assert.Single(result.Channels.Single(c => c.Name == "Colts at Texans (Español)").Candidates).Language);
        Assert.Single(result.Channels.Single(c => c.Name == "ESPN").Candidates);
    }

    [Fact]
    public void The_Ladder_Plays_Only_The_Channel_Language()
    {
        var channel = new SourceChannel { Name = "Colts at Texans", Language = "en" };
        var candidates = new List<StreamCandidate>
        {
            new() { Url = "https://a/1", Language = "en" },
            new() { Url = "https://a/2", Language = "es" },   // (the grouper never does this: the guard)
            new() { Url = "https://a/3", Language = "en" },
            new() { Url = "https://a/4", Language = "en" }
        };

        // the third's master declares only Spanish audio: left out while another English one remains
        Assert.Equal(new[] { true, false, false, true }, LiveLadderService.InLanguage(channel, candidates, new string?[] { null, null, "es", "en" }));

        // every English-declared candidate turns out Spanish: nothing better to play, keep them
        Assert.Equal(new[] { true, false }, LiveLadderService.InLanguage(channel, candidates.Take(2).ToList(), new string?[] { "es", null }));

        var single = new SourceChannel { Name = "X", Language = "es", StreamUrl = "https://a/5" };
        Assert.Equal("es", Assert.Single(LiveLadderService.Candidates(single)).Language);
    }
}

public class StreamLanguageBoardTests
{
    private static GameInfo Game(string id, params (string Channel, string Kind)[] channels) => new()
    {
        Id = id, State = "in", Channels = channels.Select(c => new GameChannel { Id = c.Channel, Kind = c.Kind }).ToList()
    };

    private static readonly Dictionary<string, string> Languages = new()
    {
        ["colts-en"] = "en", ["colts-es"] = "es", ["only-es"] = "es", ["fox"] = "en", ["deportes"] = "es"
    };

    [Fact]
    public void Watch_Is_The_Preferred_Language_When_There_Is_One_Otherwise_The_Other()
    {
        var both = Game("1", ("colts-es", "teams"), ("colts-en", "teams"));
        var onlySpanish = Game("2", ("only-es", "teams"));
        var all = new[] { both, onlySpanish };

        var (enWatch, feeds) = WatchResolver.ResolveFeeds(both, all, _ => true, id => Languages[id], "en");
        Assert.Equal("colts-en", enWatch!.Id);
        Assert.Equal(new[] { ("en", "colts-en"), ("es", "colts-es") }, feeds.Select(f => (f.Language, f.Channel.Id)));   // English first

        Assert.Equal("colts-es", WatchResolver.ResolveFeeds(both, all, _ => true, id => Languages[id], "es").Watch!.Id);
        Assert.Equal("colts-en", WatchResolver.ResolveFeeds(both, all, _ => true, id => Languages[id], null).Watch!.Id);

        var (fallback, one) = WatchResolver.ResolveFeeds(onlySpanish, all, _ => true, id => Languages[id], "en");
        Assert.Equal("only-es", fallback!.Id);
        Assert.Single(one);

        var (gone, none) = WatchResolver.ResolveFeeds(onlySpanish, all, _ => false, id => Languages[id], "en");
        Assert.Null(gone);
        Assert.Empty(none);
    }

    [Fact]
    public void The_Broadcaster_Rule_Holds_Within_Each_Language()
    {
        // FOX carries two live games: not guessed at; the Spanish broadcaster carries only this one
        var a = Game("1", ("fox", "network"), ("deportes", "network"));
        var b = Game("2", ("fox", "network"));
        var (watch, feeds) = WatchResolver.ResolveFeeds(a, new[] { a, b }, _ => true, id => Languages[id], "en");
        Assert.Equal("deportes", watch!.Id);
        Assert.Equal("es", Assert.Single(feeds).Language);
    }

    [Fact]
    public void The_Json_Is_Additive()
    {
        var watch = new WatchTarget { ChannelId = "c", Language = "es" };
        var game = new GameInfo { Id = "g", Watch = watch };
        var json = JsonSerializer.Serialize(game);
        Assert.DoesNotContain("\"feeds\"", json, StringComparison.Ordinal);    // one language: no feeds at all
        Assert.Contains("\"language\":\"es\"", json, StringComparison.Ordinal);

        game.Feeds = new List<GameFeed>
        {
            new() { Language = "en", Label = "English", Watch = new WatchTarget { ChannelId = "e" } },
            new() { Language = "es", Label = "Español", Watch = watch }
        };
        var feeds = JsonNode.Parse(JsonSerializer.Serialize(game))!["feeds"]!.AsArray();
        Assert.Equal(new[] { "en", "es" }, feeds.Select(f => f!["language"]!.GetValue<string>()));
        Assert.Equal("Español", feeds[1]!["label"]!.GetValue<string>());
        Assert.Equal("c", feeds[1]!["watch"]!["channelId"]!.GetValue<string>());
        Assert.Null(game.Clone().Feeds);

        Assert.Equal("en", JsonNode.Parse(JsonSerializer.Serialize(new BoardChannel()))!["language"]!.GetValue<string>());
    }

    [Fact]
    public void Live_Tv_Numbers_English_Channels_First_And_Spanish_Ones_After_Or_Not_At_All()
    {
        var channels = new List<SourceChannel>
        {
            new() { Id = "es-hot", Name = "Chiefs at Bills (Español)", Language = "es" },
            new() { Id = "en-cold", Name = "ESPN", Language = "en" },
            new() { Id = "en-hot", Name = "Chiefs at Bills", Language = "en" },
            new() { Id = "es-cold", Name = "Univision (Español)", Language = "es" },
            new() { Id = "old", Name = "Stadium" } // stored before 2.3: no language means English
        };
        channels[4].Language = string.Empty;
        var hot = new GameInfo { Id = "g", State = "in", Heat = 90 };
        var games = new Dictionary<string, GameInfo> { ["es-hot"] = hot, ["en-hot"] = hot };

        Assert.Equal(new[] { "en-hot", "en-cold", "old", "es-hot", "es-cold" }, LiveTvFeedController.LiveTvOrder(channels, games, spanish: true).Select(c => c.Id));
        Assert.Equal(new[] { "en-hot", "en-cold", "old" }, LiveTvFeedController.LiveTvOrder(channels, games, spanish: false).Select(c => c.Id));
        Assert.True(new PluginConfiguration().SpanishInLiveTv);
    }
}
