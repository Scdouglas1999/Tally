using System.Net;
using System.Text;
using Jellyfin.Plugin.Tally;
using Jellyfin.Plugin.Tally.Models;
using Jellyfin.Plugin.Tally.Scores;
using Jellyfin.Plugin.Tally.Sources;
using Microsoft.Extensions.Logging.Abstractions;
using Xunit;

namespace Tally.Tests;

/// <summary>A clock the tests move by hand.</summary>
public sealed class FakeClock : TimeProvider
{
    public FakeClock(DateTimeOffset now) => Now = now;

    public DateTimeOffset Now { get; set; }

    public override DateTimeOffset GetUtcNow() => Now;

    public void Advance(TimeSpan by) => Now += by;
}

/// <summary>
/// A made-up listing site, served from memory: a front page with 160 event links spread over five leagues, most
/// event pages with no stream yet, every tenth with one in its player config, and the wanted game (Rays at Phillies)
/// linked 121st, as "/mlb/tb-phi/5000120": past the 96 pages the general crawl reads. Its page embeds a player
/// (/embed/7700) whose "Link 2" button switches to /embed/7701. All addresses are on example.test.
/// </summary>
public sealed class FixtureSite
{
    public const string Home = "https://listing.example.test/";
    public const int WantedAt = 120;

    private static readonly string[] Leagues = { "mlb", "nba", "nhl", "nfl", "soccer" };

    public FixtureSite(string wantedHref = "/mlb/tb-phi/5000120", string wantedText = "Watch")
    {
        WantedHref = wantedHref;
        WantedText = wantedText;
    }

    public string WantedHref { get; }

    public string WantedText { get; }

    /// <summary>False: the wanted game's streams answer 404 (the event has ended upstream).</summary>
    public bool WantedAlive { get; set; } = true;

    /// <summary>False: the front page no longer lists the wanted game.</summary>
    public bool WantedListed { get; set; } = true;

    public List<string> Requests { get; } = new();

    /// <summary>When set, reading the front page waits for it.</summary>
    public TaskCompletionSource? Gate { get; set; }

    public static string FillerSlug(int i) => $"riverton-otters-{i}-lakeside-herons-{i}";

    public HttpResponseMessage Route(string url)
    {
        lock (Requests)
        {
            Requests.Add(url);
        }

        var path = new Uri(url).AbsolutePath;
        if (path == "/")
        {
            var sb = new StringBuilder("<html><head><title>Listing Example - every game</title></head><body><h1>Today</h1><ul>");
            for (var i = 0; i < 160; i++)
            {
                if (i == WantedAt)
                {
                    if (WantedListed)
                    {
                        sb.Append($"<li><a href=\"{WantedHref}\">{WantedText}</a></li>");
                    }

                    continue;
                }

                var league = Leagues[i % Leagues.Length];
                sb.Append($"<li><a href=\"/{league}/{FillerSlug(i)}/{5000000 + i}\">Riverton Otters {i} vs Lakeside Herons {i}</a></li>");
            }

            return Html(sb.Append("</ul></body></html>").ToString());
        }

        if (path == new Uri(new Uri(Home), WantedHref).AbsolutePath)
        {
            return Html("<html><head><title>Listing Example</title></head><body>" +
                "<iframe id=\"player\" src=\"https://embed.example.test/embed/7700\"></iframe>" +
                "<button onclick=\"changeStream(7700)\">Link 1</button><button onclick=\"changeStream(7701)\">Link 2</button></body></html>");
        }

        if (path.StartsWith("/embed/", StringComparison.Ordinal))
        {
            var id = path["/embed/".Length..];
            return Html($"<html><body><script>var source = \"https://cdn.example.test/wanted/{id}/index.m3u8\";</script></body></html>");
        }

        if (path.StartsWith("/wanted/", StringComparison.Ordinal))
        {
            return WantedAlive ? Playlist() : new HttpResponseMessage(HttpStatusCode.NotFound);
        }

        if (path.StartsWith("/filler/", StringComparison.Ordinal))
        {
            return Playlist();
        }

        var seg = path.Trim('/').Split('/');
        if (seg.Length == 3 && int.TryParse(seg[2], out var eventId))
        {
            var i = eventId - 5000000;
            var player = i % 10 == 0 ? $"<script>var player = {{ file: \"https://cdn.example.test/filler/{i}/index.m3u8\" }};</script>" : "<p>Stream starts soon</p>";
            return Html($"<html><head><title>Listing Example</title></head><body>{player}</body></html>");
        }

        return new HttpResponseMessage(HttpStatusCode.NotFound);
    }

    public HttpMessageHandler Handler() => new Stub(this);

    private static HttpResponseMessage Html(string body)
        => new(HttpStatusCode.OK) { Content = new StringContent(body, Encoding.UTF8, "text/html") };

    private static HttpResponseMessage Playlist()
        => new(HttpStatusCode.OK) { Content = new StringContent("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nseg1.ts\n", Encoding.UTF8, "application/vnd.apple.mpegurl") };

    private sealed class Stub : HttpMessageHandler
    {
        private readonly FixtureSite _site;

        public Stub(FixtureSite site) => _site = site;

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            var url = request.RequestUri!.ToString();
            var response = _site.Route(url);
            if (url == Home && _site.Gate is { } gate)
            {
                await gate.Task.ConfigureAwait(false);
            }

            return response;
        }
    }
}

public class WantedGameTests
{
    public static GameInfo RaysAtPhillies(string state = "in", DateTimeOffset? start = null) => new()
    {
        Id = "401817078", Sport = "baseball", League = "MLB", Name = "TB @ PHI", State = state,
        Start = start ?? new DateTimeOffset(2026, 9, 26, 22, 40, 0, TimeSpan.Zero),
        Away = new GameTeam { Abbr = "TB", Name = "Tampa Bay Rays", ShortName = "Rays", Nickname = "Rays", Location = "Tampa Bay" },
        Home = new GameTeam { Abbr = "PHI", Name = "Philadelphia Phillies", ShortName = "Phillies", Nickname = "Phillies", Location = "Philadelphia" }
    };

    [Theory]
    [InlineData(null, "https://listing.example.test/mlb/rays-vs-phillies/1376048")]
    [InlineData("Tampa Bay Rays @ Philadelphia Phillies", "https://listing.example.test/event/1")]
    [InlineData(null, "https://listing.example.test/mlb/tb-phi")]
    [InlineData("TB @ PHI", "/watch")]
    [InlineData("RAYS vs PHILLIES", null)]
    [InlineData("Rays", "https://listing.example.test/game/philadelphia")]
    [InlineData(null, "/mlb/tampa-bay-rays-philadelphia-phillies?utm=x")]
    public void A_Link_Naming_Both_Teams_Scores_Two(string? text, string? url)
        => Assert.Equal(2, WantedGame.From(RaysAtPhillies()).Score(text, url));

    [Theory]
    [InlineData("Rays", "https://listing.example.test/watch/1")] // one team by name
    [InlineData(null, "https://listing.example.test/nhl/tampa-bay-lightning-boston-bruins/9")] // the city is a name too
    [InlineData("Phillies @ Mets", "/mlb/phi-nym")]
    public void A_Link_Naming_One_Team_Scores_One(string? text, string? url)
        => Assert.Equal(1, WantedGame.From(RaysAtPhillies()).Score(text, url));

    [Theory]
    [InlineData("arrays and phillips", "https://listing.example.test/x")] // whole words only
    [InlineData("TB", "/mlb/tb-sea")] // an abbreviation counts only next to the other team
    [InlineData("Watch", "https://tb.example.test/phi/")] // the host is the site's, and "phi" alone is no team
    [InlineData(null, null)]
    public void Other_Links_Score_Zero(string? text, string? url)
        => Assert.Equal(0, WantedGame.From(RaysAtPhillies()).Score(text, url));

    [Fact]
    public void Names_Are_Matched_Without_Accents_Or_Punctuation()
    {
        var g = new GameInfo
        {
            Id = "1", State = "pre",
            Away = new GameTeam { Abbr = "STL", Name = "St. Louis Cardinals", ShortName = "Cardinals", Location = "St. Louis" },
            Home = new GameTeam { Abbr = "MTL", Name = "CF Montréal", ShortName = "Montréal", Location = "Montréal" }
        };
        var w = WantedGame.From(g);
        Assert.Equal(2, w.Score(null, "https://x.example.test/st-louis-cardinals-vs-montreal/5"));
        Assert.Equal("STL @ MTL", w.Label);
        Assert.Equal("St. Louis Cardinals at CF Montréal", w.Name);
    }

    [Fact]
    public void A_Name_Needs_Both_Teams_By_Name_For_The_Board()
    {
        var w = WantedGame.From(RaysAtPhillies());
        Assert.True(w.NamesBoth("Rays Vs Phillies"));
        Assert.False(w.NamesBoth("Tb Phi")); // two abbreviations: the board's matcher would not tie it to the game
        Assert.False(w.NamesBoth("Rays"));
    }
}

public class GameSearchScheduleTests
{
    private static readonly DateTimeOffset T = new(2026, 9, 26, 22, 40, 0, TimeSpan.Zero);

    /// <summary>Runs the schedule every 30 s from <paramref name="from"/> to <paramref name="to"/> (relative to
    /// <see cref="T"/>), each crawl taking 20 s, and returns the minutes (relative to T) at which crawls started.</summary>
    private static List<double> Run(FakeClock clock, GameSearchSchedule s, IReadOnlyList<GameInfo> games, double from, double to,
        Func<GameInfo, bool>? hasStream = null, Action<GameInfo, DateTimeOffset>? update = null)
    {
        var fired = new List<double>();
        clock.Now = T.AddMinutes(from);
        while (clock.Now <= T.AddMinutes(to))
        {
            foreach (var g in games)
            {
                update?.Invoke(g, clock.Now);
            }

            s.QueueDue(games, hasStream ?? (_ => false));
            var (wanted, _) = s.TakePending();
            if (wanted.Count > 0)
            {
                var started = clock.Now;
                fired.Add((started - T).TotalMinutes);
                s.Searched(wanted.Select(g => g.Id), started, started.AddSeconds(20), id => hasStream?.Invoke(wanted.First(g => g.Id == id)) ?? false, gameDriven: true);
            }

            clock.Advance(TimeSpan.FromSeconds(30));
        }

        return fired;
    }

    private static void LiveFromStart(GameInfo g, DateTimeOffset now) => g.State = now >= g.Start ? "in" : "pre";

    [Fact]
    public void A_Game_Without_A_Stream_Is_Searched_Around_Its_Start_Then_Every_Five_Minutes_While_Live()
    {
        var clock = new FakeClock(T);
        var s = new GameSearchSchedule(clock);
        var game = WantedGameTests.RaysAtPhillies("pre", T);

        var fired = Run(clock, s, new[] { game }, -40, 32, update: LiveFromStart);

        Assert.Equal(new double[] { -15, -5, 0, 3, 8, 15, 20, 25, 30 }, fired);
    }

    [Fact]
    public void A_Found_Stream_Stops_The_Schedule()
    {
        var clock = new FakeClock(T);
        var s = new GameSearchSchedule(clock);
        var game = WantedGameTests.RaysAtPhillies("pre", T);

        // the stream turns up at +4
        var fired = Run(clock, s, new[] { game }, -20, 40, hasStream: _ => clock.Now >= T.AddMinutes(4), update: LiveFromStart);

        Assert.Equal(new double[] { -15, -5, 0, 3 }, fired);
    }

    [Fact]
    public void Game_Driven_Crawls_Keep_Two_Minutes_Apart_And_Share_A_Crawl()
    {
        var clock = new FakeClock(T);
        var s = new GameSearchSchedule(clock);
        var a = WantedGameTests.RaysAtPhillies("pre", T);
        var b = WantedGameTests.RaysAtPhillies("pre", T.AddMinutes(1));
        b.Id = "b";
        var c = WantedGameTests.RaysAtPhillies("pre", T.AddMinutes(10));
        c.Id = "c";

        var fired = Run(clock, s, new[] { a, b, c }, -16, -3);

        // A at -15; B's -15 slot (-14) waits for the gap after A's crawl ended (-14:40 + 2:00), then runs alone;
        // A's -5 and C's -5 (+5) come later
        Assert.Equal(new[] { -15.0, -12.5, -5.0 }, fired);
        for (var i = 1; i < fired.Count; i++)
        {
            Assert.True(fired[i] - (fired[i - 1] + 20 / 60.0) >= 2);
        }
    }

    [Fact]
    public void Missed_Slots_Are_Made_Up_Once()
    {
        var clock = new FakeClock(T);
        var s = new GameSearchSchedule(clock);
        var game = WantedGameTests.RaysAtPhillies("in", T);

        // the server comes up at +10: the +8 slot is made up (once), the next is +15
        var fired = Run(clock, s, new[] { game }, 10, 16);

        Assert.Equal(new double[] { 10, 15 }, fired);
    }

    [Fact]
    public void A_Late_Start_Is_Searched_For_An_Hour_And_A_Final_Not_At_All()
    {
        var clock = new FakeClock(T);
        var s = new GameSearchSchedule(clock);
        var late = WantedGameTests.RaysAtPhillies("pre", T);
        var fired = Run(clock, s, new[] { late }, 50, 75);
        Assert.Equal(new double[] { 50, 55, 60 }, fired);

        var final = WantedGameTests.RaysAtPhillies("post", T);
        final.Id = "final";
        Assert.Empty(Run(clock, new GameSearchSchedule(clock), new[] { final }, -20, 30));
    }

    [Fact]
    public void Find_Starts_A_Search_Joins_A_Running_One_And_Says_None_For_A_Minute_After_Nothing()
    {
        var clock = new FakeClock(T);
        var s = new GameSearchSchedule(clock);
        var game = WantedGameTests.RaysAtPhillies("in", T);
        var none = Array.Empty<string>();

        Assert.Equal("found", s.Find(game, hasStream: true, none));
        Assert.Equal("searching", s.Find(game, false, none));
        Assert.Equal("searching", s.Find(game, false, none)); // joins: still one queued search
        var (wanted, reason) = s.TakePending();
        Assert.Single(wanted);
        Assert.Equal("find", reason);
        Assert.Equal("searching", s.Find(game, false, none)); // running
        Assert.Empty(s.TakePending().Games);

        clock.Advance(TimeSpan.FromSeconds(12));
        s.Searched(new[] { game.Id }, T, clock.Now, _ => false, gameDriven: true);
        Assert.Equal("none", s.Find(game, false, none));
        clock.Advance(TimeSpan.FromSeconds(59));
        Assert.Equal("none", s.Find(game, false, none));
        clock.Advance(TimeSpan.FromSeconds(2));
        Assert.Equal("searching", s.Find(game, false, none)); // a minute on: a new search
        Assert.Single(s.TakePending().Games);
    }

    [Fact]
    public void Find_Joins_A_Regular_Crawl_That_Looks_For_The_Game()
    {
        var s = new GameSearchSchedule(new FakeClock(T));
        var game = WantedGameTests.RaysAtPhillies("in", T);
        Assert.Equal("searching", s.Find(game, false, new[] { game.Id }));
        Assert.Empty(s.TakePending().Games);
        Assert.Equal("searching", s.Describe(game, new[] { game.Id }, scheduled: true).State);
    }

    [Fact]
    public void The_Board_Says_Searching_Or_When_The_Next_Search_Is_Due()
    {
        var clock = new FakeClock(T.AddMinutes(-25));
        var s = new GameSearchSchedule(clock);
        var game = WantedGameTests.RaysAtPhillies("pre", T);
        var none = Array.Empty<string>();

        var d = s.Describe(game, none, scheduled: true);
        Assert.Equal("waiting", d.State);
        Assert.Null(d.LastAt);
        Assert.Equal(T.AddMinutes(-15), d.NextAt);
        Assert.Null(s.Describe(game, none, scheduled: false).NextAt); // no web page source: nothing is scheduled

        clock.Now = T.AddMinutes(-15);
        s.QueueDue(new[] { game }, _ => false);
        Assert.Equal("searching", s.Describe(game, none, true).State);
        Assert.Null(s.Describe(game, none, true).NextAt);
        var (wanted, _) = s.TakePending();
        s.Searched(wanted.Select(g => g.Id), clock.Now, clock.Now.AddSeconds(30), _ => false, true);

        clock.Advance(TimeSpan.FromMinutes(1));
        d = s.Describe(game, none, true);
        Assert.Equal("waiting", d.State);
        Assert.Equal(T.AddMinutes(-15).AddSeconds(30), d.LastAt);
        Assert.Equal(T.AddMinutes(-5), d.NextAt);

        // a slot held back by the gap after another game's crawl: due when the gap is over
        var other = WantedGameTests.RaysAtPhillies("pre", T.AddMinutes(-4));
        other.Id = "other";
        clock.Now = T.AddMinutes(-4).AddMinutes(-5).AddSeconds(-50); // just before other's -5 slot... then after it
        s.Searched(new[] { "x" }, clock.Now, clock.Now.AddSeconds(20), _ => false, true);
        clock.Advance(TimeSpan.FromSeconds(60));
        Assert.Equal(clock.Now.AddSeconds(-40).AddMinutes(2), s.Describe(other, none, true).NextAt);
    }
}

public class TargetedCrawlTests
{
    private static WebExtractor Extractor(FixtureSite site)
        => new(new HttpClient(site.Handler()), NullLogger.Instance, "TestAgent/1.0");

    private static IReadOnlyList<WantedGame> Wanted() => new[] { WantedGame.From(WantedGameTests.RaysAtPhillies()) };

    [Fact]
    public async Task The_General_Crawl_Stops_At_96_Pages_And_Misses_A_Game_Listed_Beyond()
    {
        var site = new FixtureSite();
        var ex = Extractor(site);
        var streams = await ex.ExtractAsync(FixtureSite.Home, 12, CancellationToken.None);

        Assert.Equal(96, ex.PagesVisited);
        Assert.Equal(0, ex.TargetedPagesVisited);
        Assert.DoesNotContain(streams, s => s.Url.Contains("/wanted/", StringComparison.Ordinal));
        Assert.NotEmpty(streams); // it does find the listed games it reaches
        Assert.DoesNotContain(site.Requests, r => r.Contains("tb-phi", StringComparison.Ordinal));
    }

    [Fact]
    public async Task A_Crawl_For_A_Wanted_Game_Visits_Its_Pages_First_On_Top_Of_The_General_Budget()
    {
        var site = new FixtureSite();
        var ex = Extractor(site);
        var streams = await ex.ExtractAsync(FixtureSite.Home, 12, CancellationToken.None, Wanted());

        var wanted = streams.Where(s => s.Url.Contains("/wanted/", StringComparison.Ordinal)).ToList();
        Assert.Equal(new[] { "https://cdn.example.test/wanted/7700/index.m3u8", "https://cdn.example.test/wanted/7701/index.m3u8" },
            wanted.Select(s => s.Url).OrderBy(u => u, StringComparer.Ordinal));
        // "/mlb/tb-phi/…" names the teams only by abbreviation: the streams are named after the game, so the board ties them to it
        Assert.All(wanted, s => Assert.Equal("Tampa Bay Rays at Philadelphia Phillies", s.Name));
        Assert.All(wanted, s => Assert.False(s.NameFromTitle));
        Assert.Equal(3, ex.TargetedPagesVisited); // the event page and its two players
        Assert.Equal(96 + 3, ex.PagesVisited); // the general budget is untouched

        // first: right after the front page, before the general crawl's event pages
        var order = site.Requests.Where(r => !r.Contains("cdn.example.test", StringComparison.Ordinal)).ToList();
        Assert.Equal(FixtureSite.Home, order[0]);
        Assert.Contains("tb-phi", order[1], StringComparison.Ordinal);
    }

    [Fact]
    public async Task A_Game_Driven_Search_Reads_Only_The_Page_And_What_Names_The_Game()
    {
        var site = new FixtureSite();
        var ex = Extractor(site);
        var streams = await ex.ExtractAsync(FixtureSite.Home, 12, CancellationToken.None, Wanted(), targetedOnly: true);

        Assert.Equal(2, streams.Count);
        Assert.Equal(4, ex.PagesVisited);
        Assert.Equal(3, ex.TargetedPagesVisited);
    }

    [Theory]
    [InlineData("/mlb/rays-vs-phillies/5000120", "Watch", "Rays Vs Phillies")]
    [InlineData("/event/5000120", "Tampa Bay Rays @ Philadelphia Phillies", "Tampa Bay Rays @ Philadelphia Phillies")]
    public async Task Link_Text_Or_Slug_Names_The_Game(string href, string text, string name)
    {
        var site = new FixtureSite(href, text);
        var ex = Extractor(site);
        var streams = await ex.ExtractAsync(FixtureSite.Home, 12, CancellationToken.None, Wanted(), targetedOnly: true);

        Assert.Equal(2, streams.Count);
        Assert.All(streams, s => Assert.Equal(name, s.Name)); // the page's own name stands when it names the game
        Assert.True(WantedGame.From(WantedGameTests.RaysAtPhillies()).NamesBoth(name));
    }

    [Fact]
    public void The_Targeted_Budget_Grows_With_The_Games_Wanted()
    {
        Assert.Equal(0, WebExtractor.TargetedBudget(0));
        Assert.Equal(32, WebExtractor.TargetedBudget(1));
        Assert.Equal(80, WebExtractor.TargetedBudget(5));
        Assert.Equal(160, WebExtractor.TargetedBudget(30));
    }
}

public class GameDrivenMergeTests
{
    private sealed class Factory : IHttpClientFactory
    {
        private readonly HttpMessageHandler _handler;

        public Factory(HttpMessageHandler handler) => _handler = handler;

        public HttpClient CreateClient(string name) => new(_handler, disposeHandler: false);
    }

    private static (SourceManager Manager, FixtureSite Site, List<GameInfo> Games) Setup()
    {
        var site = new FixtureSite();
        var games = new List<GameInfo> { WantedGameTests.RaysAtPhillies("in", DateTimeOffset.UtcNow.AddMinutes(-20)) };
        var source = new SourceDefinition { Id = Guid.NewGuid(), Name = "Listing", Kind = SourceKind.Web, PageUrl = FixtureSite.Home, Enabled = true };
        var m = new SourceManager(new Factory(site.Handler()), NullLogger<SourceManager>.Instance)
        {
            DefinitionsOverride = () => new[] { source },
            GamesOverride = () => games
        };
        return (m, site, games);
    }

    private static bool HasGame(SourceManager m) => m.GetChannels().Any(c => c.Name == "Tampa Bay Rays at Philadelphia Phillies");

    [Fact]
    public async Task A_Game_Driven_Search_Adds_To_The_Source_And_Its_Streams_Survive_The_Next_General_Crawl_Until_The_Game_Ends()
    {
        var (m, site, games) = Setup();
        var reports = new List<CrawlReport>();
        m.Crawled += reports.Add;

        await m.RefreshAsync(CancellationToken.None);
        var general = m.GetChannels().Select(c => c.Name).ToList();
        Assert.NotEmpty(general);
        Assert.False(HasGame(m));

        var report = await m.SearchAsync(new[] { WantedGame.From(games[0]) }, CancellationToken.None);
        Assert.True(report.GameDriven);
        Assert.Equal(4, report.Pages);
        Assert.Equal(3, report.TargetedPages);
        var game = Assert.Single(m.GetChannels(), c => c.Name == "Tampa Bay Rays at Philadelphia Phillies");
        Assert.Equal(2, game.Candidates.Count); // both players, one channel
        Assert.All(general, n => Assert.Contains(m.GetChannels(), c => c.Name == n)); // nothing the general crawl found was dropped

        // the front page stops listing the game: the next general crawl does not reach it, the streams stay
        site.WantedListed = false;
        await m.RefreshAsync(CancellationToken.None);
        Assert.True(HasGame(m));
        Assert.Equal(game.Id, m.GetChannels().First(c => c.Name == game.Name).Id);

        // their playlists stop answering: let go
        site.WantedAlive = false;
        await m.RefreshAsync(CancellationToken.None);
        Assert.False(HasGame(m));

        Assert.Equal(new[] { false, true, false, false }, reports.Select(r => r.GameDriven));
    }

    [Fact]
    public async Task A_Search_Before_The_First_Crawl_Does_The_Regular_One()
    {
        var (m, _, games) = Setup();
        var report = await m.SearchAsync(new[] { WantedGame.From(games[0]) }, CancellationToken.None);

        Assert.Equal(96 + 3, report.Pages); // nothing to add to yet: the whole site, the wanted game first
        Assert.True(HasGame(m));
        Assert.True(m.GetChannels().Count > 1);
    }

    [Fact]
    public void The_Board_Shows_A_Search_For_Live_And_Soon_Games_Without_A_Stream()
    {
        var now = DateTimeOffset.UtcNow;
        Assert.True(StreamSearchService.Shows(WantedGameTests.RaysAtPhillies("in", now.AddHours(-1)), now));
        Assert.True(StreamSearchService.Shows(WantedGameTests.RaysAtPhillies("pre", now.AddMinutes(30)), now));
        Assert.False(StreamSearchService.Shows(WantedGameTests.RaysAtPhillies("pre", now.AddMinutes(31)), now));
        Assert.False(StreamSearchService.Shows(WantedGameTests.RaysAtPhillies("post", now.AddHours(-3)), now));
        var watched = WantedGameTests.RaysAtPhillies("in", now);
        watched.Watch = new Jellyfin.Plugin.Tally.Client.WatchTarget { ChannelId = "c" };
        Assert.False(StreamSearchService.Shows(watched, now));
    }

    [Fact]
    public void The_Search_Field_Serializes_As_The_Contract_Says()
    {
        var g = WantedGameTests.RaysAtPhillies();
        var json = System.Text.Json.JsonSerializer.Serialize(g);
        Assert.DoesNotContain("\"search\"", json, StringComparison.Ordinal); // absent without one

        g.Search = new GameSearch { State = "waiting", LastAt = null, NextAt = new DateTimeOffset(2026, 9, 26, 22, 45, 0, TimeSpan.Zero) };
        json = System.Text.Json.JsonSerializer.Serialize(g);
        Assert.Contains("\"search\":{\"state\":\"waiting\",\"lastAt\":null,\"nextAt\":\"2026-09-26T22:45:00+00:00\"}", json, StringComparison.Ordinal);

        var found = System.Text.Json.JsonSerializer.Serialize(new Jellyfin.Plugin.Tally.Client.FindResult { State = "none" });
        Assert.Equal("{\"state\":\"none\",\"watch\":null}", found);
    }

    [Fact]
    public async Task Pinned_Streams_Go_When_The_Game_Is_Final()
    {
        var (m, site, games) = Setup();
        await m.RefreshAsync(CancellationToken.None);
        await m.SearchAsync(new[] { WantedGame.From(games[0]) }, CancellationToken.None);
        Assert.True(HasGame(m));

        site.WantedListed = false;
        games[0].State = "post";
        await m.RefreshAsync(CancellationToken.None);
        Assert.False(HasGame(m));
    }

    [Fact]
    public async Task The_Regular_Crawl_Looks_For_The_Wanted_Games_First()
    {
        var (m, _, games) = Setup();
        m.WantedGames = () => new[] { WantedGame.From(games[0]) };
        CrawlReport? report = null;
        m.Crawled += r => report = r;

        await m.RefreshAsync(CancellationToken.None);

        Assert.True(HasGame(m));
        Assert.False(report!.GameDriven);
        Assert.Equal(96 + 3, report.Pages);
        Assert.Equal(games[0].Id, Assert.Single(report.Wanted).GameId);
    }

    [Fact]
    public async Task One_Crawl_At_A_Time_And_Callers_Coalesce()
    {
        var (m, site, games) = Setup();
        site.Gate = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var first = Task.Run(() => m.RefreshAsync(CancellationToken.None));
        await WaitFor(() => site.Requests.Count == 1); // the first crawl is reading the front page

        var second = Task.Run(() => m.RefreshAsync(CancellationToken.None)); // waits behind it
        await Task.Delay(200);
        Assert.False(second.IsCompleted);
        var third = m.RefreshAsync(CancellationToken.None); // one is already waiting: joins it
        var fourth = m.RefreshAsync(CancellationToken.None);
        Assert.True(third.IsCompleted && fourth.IsCompleted);
        var search = m.SearchAsync(new[] { WantedGame.From(games[0]) }, CancellationToken.None);
        await Task.Delay(50);
        Assert.Equal(1, site.Requests.Count(r => r == FixtureSite.Home)); // nothing else ran meanwhile

        site.Gate.SetResult();
        await Task.WhenAll(first, second, search);

        Assert.Equal(3, site.Requests.Count(r => r == FixtureSite.Home)); // two general crawls and the search
        Assert.True(HasGame(m));
    }

    private static async Task WaitFor(Func<bool> condition)
    {
        for (var i = 0; i < 200 && !condition(); i++)
        {
            await Task.Delay(10);
        }

        Assert.True(condition());
    }
}
