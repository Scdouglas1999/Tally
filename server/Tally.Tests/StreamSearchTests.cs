using System.Diagnostics;
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

/// <summary>A game the fixture site lists: its link, the slot it takes in the listing, and its player's id (the page
/// embeds /embed/{Embed}, and a "Link 2" button switches to /embed/{Embed + 1}).</summary>
public sealed record FixtureGame(string Href, string Text, int Slot, int Embed);

/// <summary>
/// A made-up listing site, served from memory: a front page with 160 event links spread over five leagues, most
/// event pages with no stream yet, every tenth with one in its player config, and the wanted game (Rays at Phillies)
/// linked 121st, as "/mlb/tb-phi/5000120": past the 96 pages the general crawl reads. Its page embeds a player
/// (/embed/7700) whose "Link 2" button switches to /embed/7701. More wanted games can be listed the same way. It counts
/// every request, when each started and how many were under way at once, and can answer with faults. All addresses are
/// on example.test.
/// </summary>
public sealed class FixtureSite
{
    public const string Home = "https://listing.example.test/";
    public const int WantedAt = 120;

    private static readonly string[] Leagues = { "mlb", "nba", "nhl", "nfl", "soccer" };
    private int _inFlight;
    private int _maxInFlight;

    public FixtureSite(string wantedHref = "/mlb/tb-phi/5000120", string wantedText = "Watch", string fillerTeam = "Riverton Otters", IEnumerable<FixtureGame>? more = null)
    {
        WantedHref = wantedHref;
        WantedText = wantedText;
        FillerTeam = fillerTeam;
        Games = new[] { new FixtureGame(wantedHref, wantedText, WantedAt, 7700) }.Concat(more ?? Array.Empty<FixtureGame>()).ToList();
    }

    /// <summary>The away team of every other listed game ("Riverton Otters 3 vs Lakeside Herons 3").</summary>
    public string FillerTeam { get; }

    public string WantedHref { get; }

    public string WantedText { get; }

    public IReadOnlyList<FixtureGame> Games { get; }

    /// <summary>True: every wanted game's page offers a third player, an "ESPN Deportes" button switching to
    /// /embed/{Embed + 2} (a Spanish feed of the game).</summary>
    public bool SpanishFeed { get; set; }

    /// <summary>False: the wanted games' streams answer 404 (the event has ended upstream).</summary>
    public bool WantedAlive { get; set; } = true;

    /// <summary>False: the front page no longer lists the wanted games.</summary>
    public bool WantedListed { get; set; } = true;

    /// <summary>True: the front page links the first wanted game only through its home team's page ("Phillies"),
    /// which links the game.</summary>
    public bool ThroughTeamPage { get; set; }

    public List<string> Requests { get; } = new();

    /// <summary>When each request started (host, time).</summary>
    public List<(string Host, long Ticks)> Starts { get; } = new();

    public int MaxInFlight => Volatile.Read(ref _maxInFlight);

    /// <summary>How long every request takes (so requests under way at once overlap).</summary>
    public TimeSpan Latency { get; set; }

    /// <summary>A fault for a request: a response to give instead (429, 403), or an exception to throw.</summary>
    public Func<string, HttpResponseMessage?>? Fault { get; set; }

    /// <summary>When set, reading the front page waits for it.</summary>
    public TaskCompletionSource? Gate { get; set; }

    public static string FillerSlug(int i) => $"riverton-otters-{i}-lakeside-herons-{i}";

    public int Count(Func<string, bool> which)
    {
        lock (Requests)
        {
            return Requests.Count(which);
        }
    }

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
                var game = Games.FirstOrDefault(g => g.Slot == i);
                if (game != null)
                {
                    if (WantedListed)
                    {
                        sb.Append(ThroughTeamPage && game == Games[0]
                            ? "<li><a href=\"/team/phillies\">Phillies</a></li>"
                            : $"<li><a href=\"{game.Href}\">{game.Text}</a></li>");
                    }

                    continue;
                }

                var league = Leagues[i % Leagues.Length];
                sb.Append($"<li><a href=\"/{league}/{FillerSlug(i)}/{5000000 + i}\">{FillerTeam} {i} vs Lakeside Herons {i}</a></li>");
            }

            return Html(sb.Append("</ul></body></html>").ToString());
        }

        if (path == "/team/phillies")
        {
            return Html($"<html><head><title>Phillies</title></head><body><a href=\"/team/phillies/roster\">Roster</a><a href=\"{Games[0].Href}\">Watch</a></body></html>");
        }

        if (Games.FirstOrDefault(g => path == new Uri(new Uri(Home), g.Href).AbsolutePath) is { } wanted)
        {
            return Html("<html><head><title>Listing Example</title></head><body>" +
                $"<iframe id=\"player\" src=\"https://embed.example.test/embed/{wanted.Embed}\"></iframe>" +
                $"<button onclick=\"changeStream({wanted.Embed})\">Link 1</button><button onclick=\"changeStream({wanted.Embed + 1})\">Link 2</button>" +
                (SpanishFeed ? $"<button onclick=\"changeStream({wanted.Embed + 2})\">ESPN Deportes</button>" : string.Empty) + "</body></html>");
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

    public static HttpResponseMessage Status(HttpStatusCode code) => new(code) { Content = new StringContent("no", Encoding.UTF8, "text/html") };

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
            var now = Interlocked.Increment(ref _site._inFlight);
            try
            {
                int max;
                do
                {
                    max = Volatile.Read(ref _site._maxInFlight);
                }
                while (now > max && Interlocked.CompareExchange(ref _site._maxInFlight, now, max) != max);

                lock (_site.Requests)
                {
                    _site.Starts.Add((request.RequestUri.Host, Stopwatch.GetTimestamp()));
                }

                if (_site.Latency > TimeSpan.Zero)
                {
                    await Task.Delay(_site.Latency, cancellationToken).ConfigureAwait(false);
                }

                if (_site.Fault?.Invoke(url) is { } fault)
                {
                    lock (_site.Requests)
                    {
                        _site.Requests.Add(url);
                    }

                    return fault;
                }

                var response = _site.Route(url);
                if (url == Home && _site.Gate is { } gate)
                {
                    await gate.Task.ConfigureAwait(false);
                }

                return response;
            }
            finally
            {
                Interlocked.Decrement(ref _site._inFlight);
            }
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

    private static readonly string[] None = Array.Empty<string>();

    /// <summary>Runs the schedule every 30 s from <paramref name="from"/> to <paramref name="to"/> (relative to
    /// <see cref="T"/>), each pass taking 20 s, and returns the minutes (relative to T) at which passes started.</summary>
    private static List<double> Run(FakeClock clock, GameSearchSchedule s, IReadOnlyList<GameInfo> games, double from, double to,
        Func<GameInfo, bool>? hasStream = null, Action<GameInfo, DateTimeOffset>? update = null, List<int>? sizes = null)
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
            var taken = s.TakePending();
            if (taken.Count > 0)
            {
                var started = clock.Now;
                fired.Add((started - T).TotalMinutes);
                sizes?.Add(taken.Count);
                s.PassEnded(taken.Select(t => t.Game.Id), started.AddSeconds(20), id => hasStream?.Invoke(taken.First(t => t.Game.Id == id).Game) ?? false, null);
            }

            clock.Advance(TimeSpan.FromSeconds(30));
        }

        return fired;
    }

    private static void LiveFromStart(GameInfo g, DateTimeOffset now) => g.State = now >= g.Start ? "in" : "pre";

    private static GameInfo Game(string id, string state, DateTimeOffset start)
    {
        var g = WantedGameTests.RaysAtPhillies(state, start);
        g.Id = id;
        return g;
    }

    [Fact]
    public void A_Game_Without_A_Stream_Is_Searched_5_Minutes_Before_At_Its_Start_Then_Every_5_Minutes()
    {
        var clock = new FakeClock(T);
        var s = new GameSearchSchedule(clock);
        var game = WantedGameTests.RaysAtPhillies("pre", T);

        var fired = Run(clock, s, new[] { game }, -40, 32, update: LiveFromStart);

        Assert.Equal(new double[] { -5, 0, 5, 10, 15, 20, 25, 30 }, fired);
    }

    [Fact]
    public void A_Found_Stream_Stops_The_Schedule()
    {
        var clock = new FakeClock(T);
        var s = new GameSearchSchedule(clock);
        var game = WantedGameTests.RaysAtPhillies("pre", T);

        // the stream turns up at +4
        var fired = Run(clock, s, new[] { game }, -20, 40, hasStream: _ => clock.Now >= T.AddMinutes(4), update: LiveFromStart);

        Assert.Equal(new double[] { -5, 0 }, fired);
    }

    [Fact]
    public void A_Final_Stops_The_Schedule_And_Is_Never_Searched()
    {
        var clock = new FakeClock(T);
        var s = new GameSearchSchedule(clock);
        var game = WantedGameTests.RaysAtPhillies("pre", T);

        // live from the start, final at +12
        var fired = Run(clock, s, new[] { game }, -10, 40, update: (g, now) => g.State = now >= T.AddMinutes(12) ? "post" : now >= T ? "in" : "pre");
        Assert.Equal(new double[] { -5, 0, 5, 10 }, fired);

        var final = Game("final", "post", T);
        Assert.Empty(Run(clock, new GameSearchSchedule(clock), new[] { final }, -20, 30));
    }

    [Fact]
    public void A_Delayed_Start_Is_Searched_Every_5_Minutes_For_3_Hours()
    {
        var clock = new FakeClock(T);
        var s = new GameSearchSchedule(clock);
        var late = WantedGameTests.RaysAtPhillies("pre", T); // still listed as not started, hours on

        var fired = Run(clock, s, new[] { late }, -10, 240);

        Assert.Equal(Enumerable.Range(-1, 38).Select(k => k * 5.0), fired); // -5, 0, 5 … 180
        Assert.Equal(180, fired[^1]);

        // a live game keeps going past that
        var live = Game("live", "in", T);
        var liveFired = Run(clock, new GameSearchSchedule(clock), new[] { live }, 178, 192);
        Assert.Equal(new double[] { 178, 180, 185, 190 }, liveFired); // 178 makes up +175 once
    }

    [Fact]
    public void Missed_Slots_Are_Made_Up_Once()
    {
        var clock = new FakeClock(T);
        var s = new GameSearchSchedule(clock);
        var game = WantedGameTests.RaysAtPhillies("in", T);

        // the server comes up at +12: the +10 slot is made up (once), the next is +15
        var fired = Run(clock, s, new[] { game }, 12, 16);

        Assert.Equal(new double[] { 12, 15 }, fired);
    }

    [Fact]
    public void Games_Due_Together_Share_One_Pass()
    {
        var clock = new FakeClock(T);
        var s = new GameSearchSchedule(clock);
        var slate = new[] { Game("a", "pre", T), Game("b", "pre", T), Game("c", "pre", T) };
        var sizes = new List<int>();

        var fired = Run(clock, s, slate, -10, 2, update: LiveFromStart, sizes: sizes);

        Assert.Equal(new double[] { -5, 0 }, fired);
        Assert.Equal(new[] { 3, 3 }, sizes);
    }

    [Fact]
    public void Passes_Keep_60_Seconds_Apart()
    {
        var clock = new FakeClock(T);
        var s = new GameSearchSchedule(clock);
        var a = Game("a", "pre", T);
        var b = Game("b", "pre", T.AddMinutes(1));

        var fired = Run(clock, s, new[] { a, b }, -20, 2, update: LiveFromStart);

        // A at -5 (ends -4:40); B's -5 slot (-4:00) waits for the gap (-3:40), then the next tick; A at 0 (ends 0:20);
        // B's start slot (+1:00) waits for the gap (+1:20)
        Assert.Equal(new[] { -5.0, -3.5, 0, 1.5 }, fired);
        for (var i = 1; i < fired.Count; i++)
        {
            Assert.True(fired[i] - (fired[i - 1] + 20 / 60.0) >= 1);
        }
    }

    [Fact]
    public void Find_Starts_A_Search_Joins_A_Running_One_And_Answers_With_The_Last_Result_For_A_Minute()
    {
        var clock = new FakeClock(T);
        var s = new GameSearchSchedule(clock);
        var game = WantedGameTests.RaysAtPhillies("in", T);

        Assert.Equal("found", s.Find(game, hasStream: true, None));
        Assert.Equal("searching", s.Find(game, false, None));
        Assert.Equal("searching", s.Find(game, false, None)); // joins: still one queued search
        var taken = s.TakePending();
        Assert.Equal("find", Assert.Single(taken).Why);
        Assert.Equal("searching", s.Find(game, false, None)); // running
        Assert.Empty(s.TakePending());

        clock.Advance(TimeSpan.FromSeconds(12));
        s.PassEnded(new[] { game.Id }, clock.Now, _ => false, null);
        Assert.Equal("none", s.Find(game, false, None));
        clock.Advance(TimeSpan.FromSeconds(59));
        Assert.Equal("none", s.Find(game, false, None));
        clock.Advance(TimeSpan.FromSeconds(2));
        Assert.Equal("searching", s.Find(game, false, None)); // a minute on: a new search, right away
        Assert.Single(s.TakePending());
    }

    [Fact]
    public void A_Find_Does_Not_Wait_For_The_Gap_But_The_Schedule_Does()
    {
        var clock = new FakeClock(T.AddMinutes(10));
        var s = new GameSearchSchedule(clock);
        var scheduled = Game("scheduled", "in", T);
        var asked = Game("asked", "in", T.AddMinutes(30));

        s.PassEnded(new[] { "other" }, clock.Now.AddSeconds(-10), _ => false, null); // a pass ended 10 s ago
        Assert.Empty(s.QueueDue(new[] { scheduled }, _ => false)); // +10 slot: held by the gap

        Assert.Equal("searching", s.Find(asked, false, None));
        Assert.Single(s.QueueDue(new[] { scheduled }, _ => false)); // rides along with the find
        Assert.Equal(new[] { "asked", "scheduled" }, s.TakePending().Select(t => t.Game.Id).OrderBy(x => x));
    }

    [Fact]
    public void Find_Joins_A_Pass_That_Covers_The_Game()
    {
        var s = new GameSearchSchedule(new FakeClock(T));
        var game = WantedGameTests.RaysAtPhillies("in", T);
        Assert.Equal("searching", s.Find(game, false, new[] { game.Id }));
        Assert.Empty(s.TakePending());
        Assert.Equal("searching", s.Describe(game, new[] { game.Id }, scheduled: true).State);
    }

    [Fact]
    public void The_Site_Pushing_Back_Doubles_The_Gap_Up_To_30_Minutes_And_A_Clean_Pass_Resets_It()
    {
        var clock = new FakeClock(T);
        var s = new GameSearchSchedule(clock);
        var game = Game("g", "in", T.AddMinutes(-60));
        var other = Game("other", "in", T.AddMinutes(-60));

        var waits = new List<double>();
        for (var i = 0; i < 5; i++)
        {
            var (backOff, recovered) = s.PassEnded(new[] { game.Id }, clock.Now, _ => false, "HTTP 429 from listing.example.test");
            Assert.False(recovered);
            waits.Add(backOff!.Value.TotalMinutes);

            // held back: no scheduled pass, and a viewer's find answers "none" and waits for the next pass too
            clock.Advance(backOff.Value - TimeSpan.FromSeconds(1));
            Assert.Empty(s.QueueDue(new[] { game }, _ => false));
            Assert.Equal("none", s.Find(other, false, None));
            Assert.Empty(s.TakePending());
            var d = s.Describe(other, None, true);
            Assert.Equal("waiting", d.State);
            Assert.Equal(clock.Now.AddSeconds(1), d.NextAt);

            clock.Advance(TimeSpan.FromSeconds(1));
            s.QueueDue(new[] { game }, _ => false);
            var taken = s.TakePending();
            Assert.Contains(taken, t => t.Game.Id == "other" && t.Why == "find");
        }

        Assert.Equal(new double[] { 5, 10, 20, 30, 30 }, waits);

        var clean = s.PassEnded(new[] { game.Id, other.Id }, clock.Now, _ => false, null);
        Assert.Null(clean.BackOff);
        Assert.True(clean.Recovered);
        Assert.Equal(GameSearchSchedule.MinGap, s.Gap);
    }

    [Fact]
    public void The_Board_Says_Searching_Or_When_The_Next_Search_Is_Due()
    {
        var clock = new FakeClock(T.AddMinutes(-25));
        var s = new GameSearchSchedule(clock);
        var game = WantedGameTests.RaysAtPhillies("pre", T);

        var d = s.Describe(game, None, scheduled: true);
        Assert.Equal("waiting", d.State);
        Assert.Null(d.LastAt);
        Assert.Equal(T.AddMinutes(-5), d.NextAt);
        Assert.Null(s.Describe(game, None, scheduled: false).NextAt); // no web page source: nothing is scheduled

        clock.Now = T.AddMinutes(-5);
        s.QueueDue(new[] { game }, _ => false);
        Assert.Equal("searching", s.Describe(game, None, true).State);
        Assert.Null(s.Describe(game, None, true).NextAt);
        var taken = s.TakePending();
        Assert.Equal("start -5 min", Assert.Single(taken).Why);
        s.PassEnded(taken.Select(t => t.Game.Id), clock.Now.AddSeconds(30), _ => false, null);

        clock.Advance(TimeSpan.FromMinutes(1));
        d = s.Describe(game, None, true);
        Assert.Equal("waiting", d.State);
        Assert.Equal(T.AddMinutes(-5).AddSeconds(30), d.LastAt);
        Assert.Equal(T, d.NextAt);

        clock.Now = T.AddMinutes(2);
        game.State = "in";
        Assert.Equal(T.AddMinutes(2), s.Describe(game, None, true).NextAt); // the start slot is due now
        s.QueueDue(new[] { game }, _ => false);
        Assert.Equal("start", Assert.Single(s.TakePending()).Why);
        s.PassEnded(new[] { game.Id }, clock.Now.AddSeconds(5), _ => false, null);
        Assert.Equal(T.AddMinutes(5), s.Describe(game, None, true).NextAt);

        // a slot held back by the gap after the last pass: due when the gap is over
        clock.Now = T.AddMinutes(5);
        s.PassEnded(new[] { "x" }, clock.Now.AddSeconds(-20), _ => false, null);
        Assert.Equal(T.AddMinutes(5).AddSeconds(40), s.Describe(game, None, true).NextAt);
    }

    [Theory]
    [InlineData(-6.0, null)]
    [InlineData(-5.0, -5.0)]
    [InlineData(-0.5, -5.0)]
    [InlineData(0.0, 0.0)]
    [InlineData(9.9, 5.0)]
    [InlineData(180.0, 180.0)]
    [InlineData(200.0, 180.0)]
    public void Slots_Of_A_Game_Listed_As_Not_Started(double at, double? latest)
    {
        var g = WantedGameTests.RaysAtPhillies("pre", T);
        var slot = GameSearchSchedule.LatestSlot(g, T.AddMinutes(at));
        Assert.Equal(latest, slot is { } x ? (x - T).TotalMinutes : null);
    }
}

public class SurgicalSearchTests
{
    private static readonly DateTimeOffset T = new(2026, 9, 26, 23, 5, 0, TimeSpan.Zero);

    public static GameInfo RedsAtBlueJays() => new()
    {
        Id = "401817080", Sport = "baseball", League = "MLB", Name = "CIN @ TOR", State = "pre", Start = T,
        Away = new GameTeam { Abbr = "CIN", Name = "Cincinnati Reds", ShortName = "Reds", Nickname = "Reds", Location = "Cincinnati" },
        Home = new GameTeam { Abbr = "TOR", Name = "Toronto Blue Jays", ShortName = "Blue Jays", Nickname = "Blue Jays", Location = "Toronto" }
    };

    public static GameInfo CardinalsAtBrewers() => new()
    {
        Id = "401817081", Sport = "baseball", League = "MLB", Name = "STL @ MIL", State = "pre", Start = T,
        Away = new GameTeam { Abbr = "STL", Name = "St. Louis Cardinals", ShortName = "Cardinals", Nickname = "Cardinals", Location = "St. Louis" },
        Home = new GameTeam { Abbr = "MIL", Name = "Milwaukee Brewers", ShortName = "Brewers", Nickname = "Brewers", Location = "Milwaukee" }
    };

    /// <summary>Rays at Phillies, Reds at Blue Jays and Cardinals at Brewers, a 7:05 slate, all listed past the general
    /// crawl's 96 pages.</summary>
    public static FixtureSite Slate() => new(more: new[]
    {
        new FixtureGame("/mlb/cin-tor/5000130", "Watch", 130, 7710),
        new FixtureGame("/mlb/cardinals-vs-brewers/5000140", "Cardinals vs Brewers", 140, 7720)
    });

    public static IReadOnlyList<GameInfo> SlateGames() => new[] { WantedGameTests.RaysAtPhillies("pre", T), RedsAtBlueJays(), CardinalsAtBrewers() };

    private static WebExtractor Extractor(FixtureSite site) => new(new HttpClient(site.Handler()), NullLogger.Instance, "TestAgent/1.0");

    private static IReadOnlyList<WantedGame> Wanted(IEnumerable<GameInfo> games) => games.Select(WantedGame.From).ToList();

    private static bool IsPage(string url) => !url.Contains("cdn.example.test", StringComparison.Ordinal);

    [Fact]
    public async Task A_Pass_For_Three_Games_Reads_The_Listing_Once_And_A_Few_Pages_Per_Game_Two_At_Most_At_Once()
    {
        var site = Slate();
        site.Latency = TimeSpan.FromMilliseconds(30);
        var stats = new GamePassStats();
        var streams = await Extractor(site).SearchGamesAsync(FixtureSite.Home, Wanted(SlateGames()), new SearchPacer(TimeSpan.FromMilliseconds(10)), stats, CancellationToken.None);

        Assert.Equal(1, site.Count(r => r == FixtureSite.Home));
        Assert.Equal(1, stats.ListingReads);
        foreach (var g in SlateGames())
        {
            Assert.InRange(stats.PagesPerGame[g.Id], 1, WebExtractor.MaxPagesPerGame);
            Assert.Equal(3, stats.PagesPerGame[g.Id]); // its event page and its two players
        }

        Assert.Equal(1 + 9, site.Count(IsPage));
        Assert.Equal(6, site.Count(r => !IsPage(r))); // each stream's playlist checked once
        Assert.Equal(16, stats.Requests);
        Assert.InRange(site.MaxInFlight, 1, 2);
        Assert.InRange(stats.MaxInFlight, 1, 2);
        Assert.Null(stats.PushedBack);

        // the general crawl's pages are not read
        Assert.DoesNotContain(site.Requests, r => r.Contains("riverton", StringComparison.Ordinal));

        Assert.Equal(6, streams.Count);
        Assert.Equal(2, streams.Count(s => s.Name == "Tampa Bay Rays at Philadelphia Phillies"));
        Assert.Equal(2, streams.Count(s => s.Name == "Cincinnati Reds at Toronto Blue Jays"));
        Assert.Equal(2, streams.Count(s => s.Name == "Cardinals Vs Brewers")); // the page's own name names the game
        Assert.All(streams, s => Assert.False(s.NameFromTitle));
    }

    [Fact]
    public async Task Requests_To_One_Host_Keep_The_Gap()
    {
        var site = Slate();
        var gap = TimeSpan.FromMilliseconds(120);
        await Extractor(site).SearchGamesAsync(FixtureSite.Home, Wanted(SlateGames()), new SearchPacer(gap), new GamePassStats(), CancellationToken.None);

        foreach (var host in site.Starts.GroupBy(x => x.Host))
        {
            var starts = host.Select(x => x.Ticks).OrderBy(x => x).ToList();
            for (var i = 1; i < starts.Count; i++)
            {
                var apart = Stopwatch.GetElapsedTime(starts[i - 1], starts[i]);
                Assert.True(apart >= gap - TimeSpan.FromMilliseconds(15), $"{host.Key}: {apart.TotalMilliseconds} ms apart");
            }
        }
    }

    [Fact]
    public async Task Links_Naming_One_Team_Count_Only_Without_A_Link_Naming_Both_And_At_Most_Two()
    {
        // every other listed game is a Tampa Bay team's: 159 links name the Rays' city; the game's own link names both
        var site = new FixtureSite(fillerTeam: "Tampa Bay Lightning");
        var stats = new GamePassStats();
        var game = WantedGameTests.RaysAtPhillies();
        var streams = await Extractor(site).SearchGamesAsync(FixtureSite.Home, Wanted(new[] { game }), new SearchPacer(TimeSpan.Zero), stats, CancellationToken.None);
        Assert.Equal(2, streams.Count);
        Assert.Equal(3, stats.PagesPerGame[game.Id]);
        Assert.DoesNotContain(site.Requests, r => r.Contains("riverton", StringComparison.Ordinal));

        // no link names both: two of the one-team links are read, no more
        site = new FixtureSite("/event/5000120", "Lightning", fillerTeam: "Tampa Bay Lightning");
        stats = new GamePassStats();
        streams = await Extractor(site).SearchGamesAsync(FixtureSite.Home, Wanted(new[] { game }), new SearchPacer(TimeSpan.Zero), stats, CancellationToken.None);
        Assert.DoesNotContain(streams, s => s.Url.Contains("/wanted/", StringComparison.Ordinal));
        Assert.Equal(WebExtractor.MaxOneTeamPages, stats.PagesPerGame[game.Id]);
        Assert.Equal(1 + 2, site.Count(IsPage));
    }

    [Fact]
    public async Task A_Page_Naming_One_Team_Leads_To_The_Page_Naming_Both()
    {
        var site = new FixtureSite { ThroughTeamPage = true };
        var stats = new GamePassStats();
        var game = WantedGameTests.RaysAtPhillies();
        var streams = await Extractor(site).SearchGamesAsync(FixtureSite.Home, Wanted(new[] { game }), new SearchPacer(TimeSpan.Zero), stats, CancellationToken.None);

        Assert.Equal(2, streams.Count);
        Assert.All(streams, s => Assert.Equal("Tampa Bay Rays at Philadelphia Phillies", s.Name));
        Assert.Equal(4, stats.PagesPerGame[game.Id]); // the team page, the event page, its two players
    }

    [Fact]
    public async Task A_429_Stops_The_Pass()
    {
        var site = Slate();
        site.Fault = url => url.Contains("/embed/7700", StringComparison.Ordinal) ? FixtureSite.Status(HttpStatusCode.TooManyRequests) : null;
        var stats = new GamePassStats();
        var streams = await Extractor(site).SearchGamesAsync(FixtureSite.Home, Wanted(SlateGames()), new SearchPacer(TimeSpan.Zero), stats, CancellationToken.None);

        Assert.Empty(streams);
        Assert.Contains("429", stats.PushedBack, StringComparison.Ordinal);
        Assert.Contains("/embed/7700", site.Requests[^1], StringComparison.Ordinal); // nothing after it
        Assert.DoesNotContain(site.Requests, r => r.Contains("cin-tor", StringComparison.Ordinal));
    }

    [Fact]
    public async Task A_403_Stops_The_Pass_Only_On_A_Page_That_Answered_Before()
    {
        var pacer = new SearchPacer(TimeSpan.Zero);
        var games = Wanted(new[] { WantedGameTests.RaysAtPhillies() });
        var site = new FixtureSite();
        var stats = new GamePassStats();
        await Extractor(site).SearchGamesAsync(FixtureSite.Home, games, pacer, stats, CancellationToken.None);
        Assert.Null(stats.PushedBack);

        site.Fault = url => url == FixtureSite.Home ? FixtureSite.Status(HttpStatusCode.Forbidden) : null;
        stats = new GamePassStats();
        await Extractor(site).SearchGamesAsync(FixtureSite.Home, games, pacer, stats, CancellationToken.None);
        Assert.Contains("403", stats.PushedBack, StringComparison.Ordinal);

        // a page that never answered: not the site pushing back
        stats = new GamePassStats();
        await Extractor(site).SearchGamesAsync(FixtureSite.Home, games, new SearchPacer(TimeSpan.Zero), stats, CancellationToken.None);
        Assert.Null(stats.PushedBack);
    }

    [Fact]
    public async Task Most_Requests_Failing_To_Connect_Stops_The_Pass()
    {
        var site = Slate();
        site.Fault = url => url.Contains("/mlb/", StringComparison.Ordinal) ? throw new HttpRequestException("Connection refused") : null;
        var stats = new GamePassStats();
        await Extractor(site).SearchGamesAsync(FixtureSite.Home, Wanted(SlateGames()), new SearchPacer(TimeSpan.Zero), stats, CancellationToken.None);

        // the listing answered, the first two games' pages did not: 2 of 3 failed, the third game is not tried
        Assert.Equal("2 of 3 requests timed out or could not connect", stats.PushedBack);
        Assert.DoesNotContain(site.Requests, r => r.Contains("cardinals", StringComparison.Ordinal));
    }

    [Fact]
    public async Task Timeouts_Count_As_Failures()
    {
        var site = Slate();
        site.Latency = TimeSpan.FromSeconds(5);
        var ex = Extractor(site);
        ex.PageTimeout = TimeSpan.FromMilliseconds(150);
        var stats = new GamePassStats();
        var clock = Stopwatch.StartNew();
        await ex.SearchGamesAsync(FixtureSite.Home, Wanted(SlateGames()), new SearchPacer(TimeSpan.Zero), stats, CancellationToken.None);

        Assert.Equal("1 of 1 requests timed out or could not connect", stats.PushedBack);
        Assert.True(clock.Elapsed < TimeSpan.FromSeconds(4));
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

    private static (SourceManager Manager, FixtureSite Site, List<GameInfo> Games, FakeClock Clock) Setup(FixtureSite? site = null)
    {
        site ??= new FixtureSite();
        var clock = new FakeClock(DateTimeOffset.UtcNow);
        var games = new List<GameInfo> { WantedGameTests.RaysAtPhillies("in", DateTimeOffset.UtcNow.AddMinutes(-20)) };
        var source = new SourceDefinition { Id = Guid.NewGuid(), Name = "Listing", Kind = SourceKind.Web, PageUrl = FixtureSite.Home, Enabled = true };
        var m = new SourceManager(new Factory(site.Handler()), NullLogger<SourceManager>.Instance)
        {
            DefinitionsOverride = () => new[] { source },
            GamesOverride = () => games,
            Pacer = new SearchPacer(TimeSpan.Zero),
            Clock = clock
        };
        return (m, site, games, clock);
    }

    private static bool HasGame(SourceManager m) => m.GetChannels().Any(c => c.Name == "Tampa Bay Rays at Philadelphia Phillies");

    private static int PlaylistChecks(FixtureSite site) => site.Count(r => r.Contains("/wanted/", StringComparison.Ordinal));

    [Fact]
    public async Task A_Pass_Adds_To_The_Source_And_Its_Streams_Survive_The_Next_Full_Scan_Until_The_Game_Ends()
    {
        var (m, site, games, clock) = Setup();
        var reports = new List<CrawlReport>();
        m.Crawled += reports.Add;

        await m.RefreshAsync(CancellationToken.None);
        var general = m.GetChannels().Select(c => c.Name).ToList();
        Assert.NotEmpty(general);
        Assert.False(HasGame(m));

        var report = await m.SearchAsync(new[] { WantedGame.From(games[0]) }, CancellationToken.None);
        Assert.True(report.GameDriven);
        Assert.Equal(4, report.Pages);
        Assert.Equal(1, report.ListingReads);
        Assert.Equal(3, report.PagesPerGame[games[0].Id]);
        var game = Assert.Single(m.GetChannels(), c => c.Name == "Tampa Bay Rays at Philadelphia Phillies");
        Assert.Equal(2, game.Candidates.Count); // both players, one channel
        Assert.All(general, n => Assert.Contains(m.GetChannels(), c => c.Name == n)); // nothing the full scan found was dropped

        // the front page stops listing the game: the next full scan does not reach it, the streams stay
        site.WantedListed = false;
        await m.RefreshAsync(CancellationToken.None);
        Assert.True(HasGame(m));
        Assert.Equal(game.Id, m.GetChannels().First(c => c.Name == game.Name).Id);

        // their playlists stop answering: let go at the next check
        site.WantedAlive = false;
        clock.Advance(TimeSpan.FromMinutes(16));
        await m.RefreshAsync(CancellationToken.None);
        Assert.False(HasGame(m));

        Assert.Equal(new[] { false, true, false, false }, reports.Select(r => r.GameDriven));
    }

    [Fact]
    public async Task Kept_Streams_Are_Checked_At_Most_Every_15_Minutes()
    {
        var (m, site, games, clock) = Setup();
        await m.RefreshAsync(CancellationToken.None);
        await m.SearchAsync(new[] { WantedGame.From(games[0]) }, CancellationToken.None);
        site.WantedListed = false;
        var checks = PlaylistChecks(site);

        // found by the pass a moment ago: kept without a check, however many scans run
        await m.RefreshAsync(CancellationToken.None);
        clock.Advance(TimeSpan.FromMinutes(10));
        await m.RefreshAsync(CancellationToken.None);
        Assert.True(HasGame(m));
        Assert.Equal(checks, PlaylistChecks(site));

        // 16 minutes on: checked once (its two streams), then not again for 15 minutes
        clock.Advance(TimeSpan.FromMinutes(6));
        await m.RefreshAsync(CancellationToken.None);
        Assert.Equal(checks + 2, PlaylistChecks(site));
        clock.Advance(TimeSpan.FromMinutes(14));
        await m.RefreshAsync(CancellationToken.None);
        Assert.Equal(checks + 2, PlaylistChecks(site));
        Assert.True(HasGame(m));
    }

    [Fact]
    public async Task A_Pass_Before_The_First_Full_Scan_Is_Surgical_Too()
    {
        var (m, _, games, _) = Setup();
        var report = await m.SearchAsync(new[] { WantedGame.From(games[0]) }, CancellationToken.None);

        Assert.Equal(4, report.Pages);
        Assert.True(HasGame(m));
    }

    [Fact]
    public async Task The_Regular_Refresh_Reads_A_Web_Page_Source_Whole_Only_When_Its_Full_Site_Scan_Is_Due()
    {
        var (m, site, _, clock) = Setup();
        var reports = new List<CrawlReport>();
        m.Crawled += reports.Add;

        await m.RefreshScheduledAsync(CancellationToken.None); // never scanned: scans now
        Assert.Equal(1, site.Count(r => r == FixtureSite.Home));
        Assert.Equal(96, reports.Single().Pages);

        await m.RefreshScheduledAsync(CancellationToken.None);
        clock.Advance(TimeSpan.FromMinutes(179));
        await m.RefreshScheduledAsync(CancellationToken.None);
        Assert.Equal(1, site.Count(r => r == FixtureSite.Home));

        clock.Advance(TimeSpan.FromMinutes(1));
        await m.RefreshScheduledAsync(CancellationToken.None);
        Assert.Equal(2, site.Count(r => r == FixtureSite.Home));

        // a configuration change or a Refresh request reads it whole whatever the time
        await m.RefreshAsync(CancellationToken.None);
        Assert.Equal(3, site.Count(r => r == FixtureSite.Home));

        // the full scan does not look for games: the one listed past its 96 pages is left to the game's own searches
        Assert.DoesNotContain(site.Requests, r => r.Contains("tb-phi", StringComparison.Ordinal));
        Assert.All(reports, r => Assert.Empty(r.Wanted));
    }

    [Fact]
    public async Task The_Full_Scan_Reads_Four_Pages_At_A_Time()
    {
        var site = new FixtureSite { Latency = TimeSpan.FromMilliseconds(20) };
        var (m, _, _, _) = Setup(site);
        await m.RefreshAsync(CancellationToken.None);
        Assert.Equal(4, site.MaxInFlight);
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
        var (m, site, games, _) = Setup();
        await m.RefreshAsync(CancellationToken.None);
        await m.SearchAsync(new[] { WantedGame.From(games[0]) }, CancellationToken.None);
        Assert.True(HasGame(m));

        site.WantedListed = false;
        games[0].State = "post";
        await m.RefreshAsync(CancellationToken.None);
        Assert.False(HasGame(m));
    }

    [Fact]
    public async Task One_Crawl_At_A_Time_And_Callers_Coalesce()
    {
        var (m, site, games, _) = Setup();
        site.Gate = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var first = Task.Run(() => m.RefreshAsync(CancellationToken.None));
        await WaitFor(() => site.Count(_ => true) == 1); // the first crawl is reading the front page

        var second = Task.Run(() => m.RefreshAsync(CancellationToken.None)); // waits behind it
        await Task.Delay(200);
        Assert.False(second.IsCompleted);
        var third = m.RefreshAsync(CancellationToken.None); // one is already waiting: joins it
        var fourth = m.RefreshScheduledAsync(CancellationToken.None);
        Assert.True(third.IsCompleted && fourth.IsCompleted);
        var search = m.SearchAsync(new[] { WantedGame.From(games[0]) }, CancellationToken.None);
        await Task.Delay(50);
        Assert.Equal(1, site.Count(r => r == FixtureSite.Home)); // nothing else ran meanwhile

        site.Gate.SetResult();
        await Task.WhenAll(first, second, search);

        Assert.Equal(3, site.Count(r => r == FixtureSite.Home)); // two full scans and the pass
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

public class VisibleFailureTests
{
    [Fact]
    public void An_Upstream_Failure_Is_Logged_At_Most_Once_A_Minute_Per_Host()
    {
        var gate = new Jellyfin.Plugin.Tally.Services.HostLogGate(TimeSpan.FromMinutes(1));
        var t = new DateTimeOffset(2026, 9, 26, 23, 0, 0, TimeSpan.Zero);
        Assert.True(gate.ShouldLog("cdn.example.test", t));
        Assert.False(gate.ShouldLog("cdn.example.test", t.AddSeconds(59)));
        Assert.True(gate.ShouldLog("other.example.test", t.AddSeconds(59)));
        Assert.True(gate.ShouldLog("cdn.example.test", t.AddSeconds(60)));
    }

    [Fact]
    public void An_Upstream_Failure_Names_The_Host_Only()
    {
        var uri = new Uri("https://cdn.example.test/live/secret-token/index.m3u8?sig=abc");
        var ex = new HttpRequestException("Error while copying content to a stream: https://cdn.example.test/live/secret-token/index.m3u8?sig=abc",
            new System.Net.Sockets.SocketException(111));
        var text = Jellyfin.Plugin.Tally.Services.UpstreamFetcher.DescribeFailure(ex, uri);

        Assert.StartsWith("HttpRequestException: ", text, StringComparison.Ordinal);
        Assert.Contains("SocketException", text, StringComparison.Ordinal);
        Assert.Contains("cdn.example.test", text, StringComparison.Ordinal);
        Assert.DoesNotContain("secret-token", text, StringComparison.Ordinal);
        Assert.DoesNotContain("sig=abc", text, StringComparison.Ordinal);
    }

    [Fact]
    public void A_Ladder_Round_Where_Every_Probe_Failed_Is_Reported_Once_With_Its_Reasons()
    {
        var round = new Jellyfin.Plugin.Tally.Live.ProbeRound();
        Assert.Null(round.Done(false, "playlist: HTTP 403"));
        Assert.Null(round.Done(false, "timeout"));
        Assert.Null(round.Done(false, "playlist: HTTP 403"));
        var all = round.Seal(3); // the last probe finished before the round was sealed
        Assert.NotNull(all);
        Assert.Equal(3, all!.Value.Failed);
        Assert.Equal("playlist: HTTP 403 ×2; timeout", all.Value.Reasons);

        var mixed = new Jellyfin.Plugin.Tally.Live.ProbeRound();
        Assert.Null(mixed.Seal(2));
        Assert.Null(mixed.Done(false, "timeout"));
        Assert.Null(mixed.Done(true, null)); // one answered: nothing to warn about

        var urls = new Jellyfin.Plugin.Tally.Live.ProbeRound();
        urls.Seal(1);
        Assert.Equal("fetch <url> failed", urls.Done(false, "fetch https://cdn.example.test/a/b.m3u8?t=1 failed")!.Value.Reasons);
    }
}
