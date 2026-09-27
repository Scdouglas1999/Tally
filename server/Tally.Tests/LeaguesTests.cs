using System.Net;
using System.Text;
using Jellyfin.Plugin.Tally.Models;
using Jellyfin.Plugin.Tally.Scores;
using Jellyfin.Plugin.Tally.Services;
using Jellyfin.Plugin.Tally.Sources;
using Microsoft.Extensions.Logging.Abstractions;
using SkiaSharp;
using Xunit;

namespace Tally.Tests;

/// <summary>Leagues follow the sources, and channels no game claims still get a designed card.</summary>
public class LeaguesTests
{
    private static GameTeam Team(string abbr, string name, string shortName, string nickname, string location) => new()
    {
        Abbr = abbr, Name = name, ShortName = shortName, Nickname = nickname, Location = location
    };

    private static GameInfo Game(string id, string league, GameTeam away, GameTeam home) => new()
    {
        Id = id, LeaguePath = league, League = "X", State = "pre", Start = DateTimeOffset.UtcNow.AddHours(1), Away = away, Home = home
    };

    private static readonly GameTeam MississippiState = Team("MSST", "Mississippi State Bulldogs", "Mississippi St", "Bulldogs", "Mississippi State");
    private static readonly GameTeam Missouri = Team("MIZ", "Missouri Tigers", "Missouri", "Tigers", "Missouri");

    [Fact]
    public void College_Football_Is_On_By_Default_And_Leagues_From_The_Sources_Join_Unless_Removed()
    {
        Assert.Contains("football/college-football", ScoreboardService.ParseLeagues(null));

        var effective = ScoreboardService.EffectiveLeagues(null, null, new[] { "basketball/nba" });
        Assert.Equal(new[] { "football/nfl", "football/college-football", "baseball/mlb", "basketball/nba" }, effective);

        Assert.DoesNotContain("basketball/nba", ScoreboardService.EffectiveLeagues(null, "basketball/nba", new[] { "basketball/nba" }));

        // the admin's own list replaces the defaults; leagues from the sources still join it
        Assert.Equal(new[] { "baseball/mlb", "hockey/nhl" }, ScoreboardService.EffectiveLeagues("baseball/mlb", "", new[] { "hockey/nhl" }));
        Assert.Equal("College Football", LeagueCatalog.Label("football/college-football"));
        Assert.Equal("soccer/ned.1", LeagueCatalog.Label("soccer/ned.1"));
    }

    [Fact]
    public void A_League_Is_Detected_Only_By_Both_Teams_Full_Names()
    {
        var g = Game("1", "football/college-football", MississippiState, Missouri);
        Assert.True(LeagueDetector.NamesBothByFullName("Mississippi State Bulldogs Missouri Tigers", g));
        Assert.True(LeagueDetector.NamesBothByFullName("MISSISSIPPI STATE BULLDOGS vs Missouri Tigers (HD)", g));
        Assert.False(LeagueDetector.NamesBothByFullName("Bulldogs vs Tigers", g)); // nicknames: could be any league's
        Assert.False(LeagueDetector.NamesBothByFullName("Mississippi State Bulldogs pregame", g));
    }

    private sealed class Espn : HttpMessageHandler
    {
        private readonly Dictionary<string, string> _boards;

        public Espn(Dictionary<string, string> boards) => _boards = boards;

        public List<string> Requests { get; } = new();

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            var url = request.RequestUri!.AbsolutePath;
            lock (Requests)
            {
                Requests.Add(url);
            }

            var league = _boards.Keys.FirstOrDefault(k => url.Contains("/sports/" + k + "/scoreboard", StringComparison.Ordinal));
            var body = league != null ? _boards[league] : "{\"events\":[]}";
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(body, Encoding.UTF8, "application/json") });
        }
    }

    private sealed class Factory : IHttpClientFactory
    {
        private readonly HttpMessageHandler _handler;

        public Factory(HttpMessageHandler handler) => _handler = handler;

        public HttpClient CreateClient(string name) => new(_handler, disposeHandler: false);
    }

    private static string Board(string away, string awayAbbr, string home, string homeAbbr) => $$"""
        { "events": [{ "id": "77", "date": "{{DateTimeOffset.UtcNow.AddHours(1):yyyy-MM-ddTHH:mmZ}}", "shortName": "{{awayAbbr}} @ {{homeAbbr}}",
          "status": { "type": { "state": "pre", "shortDetail": "Sat" } },
          "competitions": [{ "competitors": [
            { "homeAway": "home", "team": { "id": "1", "abbreviation": "{{homeAbbr}}", "displayName": "{{home}}", "shortDisplayName": "{{home.Split(' ')[^1]}}", "name": "{{home.Split(' ')[^1]}}", "location": "{{string.Join(' ', home.Split(' ')[..^1])}}" } },
            { "homeAway": "away", "team": { "id": "2", "abbreviation": "{{awayAbbr}}", "displayName": "{{away}}", "shortDisplayName": "{{away.Split(' ')[^1]}}", "name": "{{away.Split(' ')[^1]}}", "location": "{{string.Join(' ', away.Split(' ')[..^1])}}" } }
          ] }] }] }
        """;

    private static (LeagueDetector Detector, ScoreboardService Scoreboard, Espn Espn, string State) Detector()
    {
        var espn = new Espn(new Dictionary<string, string>
        {
            ["basketball/nba"] = Board("Boston Celtics", "BOS", "Los Angeles Lakers", "LAL"),
            ["football/college-football"] = Board("Mississippi State Bulldogs", "MSST", "Missouri Tigers", "MIZ")
        });
        var scoreboard = new ScoreboardService(new Factory(espn), NullLogger<ScoreboardService>.Instance);
        var state = Path.Combine(Path.GetTempPath(), "tally-leagues-" + Guid.NewGuid().ToString("N") + ".json");
        var detector = new LeagueDetector(new SourceManager(new Factory(espn), NullLogger<SourceManager>.Instance), scoreboard, NullLogger<LeagueDetector>.Instance) { StatePath = state };
        return (detector, scoreboard, espn, state);
    }

    private static List<SourceChannel> Channels(params string[] names)
        => names.Select((n, i) => new SourceChannel { Id = "c" + i, Name = n, StreamUrl = "https://cdn.example.test/" + i + ".m3u8" }).ToList();

    [Fact]
    public async Task A_League_The_Sources_Carry_Is_Added_And_Remembered()
    {
        var (detector, scoreboard, _, state) = Detector();
        try
        {
            // college football is on by default: its channel is claimed; the NBA one is not, until the NBA is added
            var added = await detector.CheckAsync(Channels("Mississippi State Bulldogs Missouri Tigers", "Boston Celtics Los Angeles Lakers", "Studio Show Tonight"), force: true, CancellationToken.None);

            Assert.Equal(new[] { "basketball/nba" }, added);
            Assert.Contains("basketball/nba", scoreboard.ActiveLeagues);
            var found = Assert.Single(detector.FromSources);
            Assert.Equal(new[] { "Boston Celtics Los Angeles Lakers" }, found.Channels);
            Assert.Contains(await scoreboard.GetGamesAsync(CancellationToken.None), g => g.LeaguePath == "basketball/nba");

            // a new detector (a restart) knows it from disk
            var again = new LeagueDetector(new SourceManager(new Factory(new Espn(new())), NullLogger<SourceManager>.Instance), new ScoreboardService(new Factory(new Espn(new())), NullLogger<ScoreboardService>.Instance), NullLogger<LeagueDetector>.Instance) { StatePath = state };
            await again.StartAsync(CancellationToken.None);
            Assert.Equal("basketball/nba", Assert.Single(again.FromSources).League);
            await again.StopAsync(CancellationToken.None);
        }
        finally
        {
            File.Delete(state);
        }
    }

    [Fact]
    public async Task Nicknames_Alone_Add_No_League()
    {
        var (detector, scoreboard, _, state) = Detector();
        try
        {
            Assert.Empty(await detector.CheckAsync(Channels("Celtics vs Lakers"), force: true, CancellationToken.None));
            Assert.DoesNotContain("basketball/nba", scoreboard.ActiveLeagues);
        }
        finally
        {
            File.Delete(state);
        }
    }

    private static List<DirectoryTeam> CollegeTeams() => new()
    {
        new("football/college-football", "MSST", "Mississippi State Bulldogs", "Mississippi St", "Bulldogs", "Mississippi State", "https://a.espncdn.com/i/teamlogos/ncaa/500/344.png"),
        new("football/college-football", "MIZ", "Missouri Tigers", "Missouri", "Tigers", "Missouri", "https://a.espncdn.com/i/teamlogos/ncaa/500/142.png"),
        new("football/college-football", "MOST", "Missouri State Bears", "Missouri St", "Bears", "Missouri State", ""),
        new("football/college-football", "ORE", "Oregon Ducks", "Oregon", "Ducks", "Oregon", ""),
        new("football/college-football", "USC", "USC Trojans", "USC", "Trojans", "USC", ""),
        new("football/college-football", "UGA", "Georgia Bulldogs", "Georgia", "Bulldogs", "Georgia", ""),
    };

    [Theory]
    [InlineData("Mississippi State Bulldogs Missouri Tigers", "Mississippi State Bulldogs", "Missouri Tigers", false)]
    [InlineData("Oregon Ducks Usc Trojans", "Oregon Ducks", "USC Trojans", false)]
    [InlineData("Georgia at Missouri", "Georgia Bulldogs", "Missouri Tigers", true)]
    [InlineData("Trojans vs Ducks", "USC Trojans", "Oregon Ducks", false)] // nicknames count next to "vs"
    public void A_Channel_Name_Reads_As_Two_Teams(string name, string away, string home, bool at)
    {
        var m = TeamDirectory.Read(name, CollegeTeams());
        Assert.NotNull(m);
        Assert.Equal(away, m!.Away!.Name);
        Assert.Equal(home, m.Home!.Name);
        Assert.Equal(at, m.At);
        Assert.Equal("football/college-football", m.League);
    }

    [Theory]
    [InlineData("Missouri Tigers Pregame Show")] // one team
    [InlineData("Trojans Ducks")] // nicknames without "vs"
    [InlineData("Bulldogs vs Ducks")] // two teams are the Bulldogs: neither is named
    [InlineData("Weekend Sports Desk")]
    public void Other_Names_Do_Not(string name) => Assert.Null(TeamDirectory.Read(name, CollegeTeams()));

    [Fact]
    public void A_Name_Split_At_Vs_Keeps_Its_Two_Sides()
    {
        var m = TeamDirectory.SplitOnly("Riverton Otters vs Lakeside Herons");
        Assert.Equal(("Riverton Otters", "Lakeside Herons", false), (m!.AwayText, m.HomeText, m.At));
        Assert.True(TeamDirectory.SplitOnly("Riverton Otters @ Lakeside Herons")!.At);
        Assert.Null(TeamDirectory.SplitOnly("Weekend Sports Desk"));
    }

    [Fact]
    public async Task The_Sport_Group_Is_Tried_First_Then_Every_League()
    {
        Assert.Equal("football/nfl", TeamDirectory.LeagueOrder("American Football", Array.Empty<string>())[0]);
        Assert.Equal("football/college-football", TeamDirectory.LeagueOrder("American Football", Array.Empty<string>())[1]);
        Assert.Equal("football/college-football", TeamDirectory.LeagueOrder("College Football", Array.Empty<string>())[0]);
        Assert.Equal(LeagueCatalog.Known.Count, TeamDirectory.LeagueOrder("Baseball", new[] { "baseball/mlb" }).Count);

        // classified as baseball ("Tigers"), found in college football all the same
        var directory = new TeamDirectory(new Factory(new Espn(new())), NullLogger<TeamDirectory>.Instance)
        {
            ListsOverride = league => league == "football/college-football" ? CollegeTeams() : new List<DirectoryTeam>()
        };
        var m = await directory.ReadAsync("Mississippi State Bulldogs Missouri Tigers", "Baseball", new[] { "baseball/mlb" }, CancellationToken.None);
        Assert.Equal("football/college-football", m!.League);
    }

    [Fact]
    public void Espn_Team_Lists_Parse()
    {
        const string json = """
            { "sports": [{ "leagues": [{ "teams": [
              { "team": { "abbreviation": "MIZ", "displayName": "Missouri Tigers", "shortDisplayName": "Missouri", "name": "Tigers", "location": "Missouri",
                          "logos": [{ "href": "https://a.espncdn.com/i/teamlogos/ncaa/500/142.png" }] } },
              { "team": { "abbreviation": "X" } }
            ] }] }] }
            """;
        var t = Assert.Single(TeamDirectory.Parse(json, "football/college-football"));
        Assert.Equal(("MIZ", "Missouri Tigers", "Missouri", "Tigers", "Missouri"), (t.Abbr, t.Name, t.ShortName, t.Nickname, t.Location));
        Assert.EndsWith("/142.png", t.Logo, StringComparison.Ordinal);
        Assert.Equal("https://site.web.api.espn.com/apis/site/v2/sports/football/college-football/teams?groups=80&limit=1000", TeamDirectory.TeamsUrl("football/college-football", null));
        Assert.Equal("http://172.17.0.3:8765/apis/site/v2/sports/basketball/nba/teams?limit=1000", TeamDirectory.TeamsUrl("basketball/nba", "http://172.17.0.3:8765/"));
        // a development scoreboard override is asked first, then ESPN (a simulator may have no team lists)
        Assert.Equal(new[] { "http://172.17.0.3:8765/apis/site/v2/sports/hockey/nhl/teams?limit=1000", "https://site.web.api.espn.com/apis/site/v2/sports/hockey/nhl/teams?limit=1000" },
            TeamDirectory.TeamsUrls("hockey/nhl", "http://172.17.0.3:8765"));
        Assert.Single(TeamDirectory.TeamsUrls("hockey/nhl", ""));
    }

    [Fact]
    public void A_Channel_No_Game_Claims_Gets_A_Matchup_Card_Or_Its_Name_With_The_League()
    {
        var teams = TeamDirectory.Read("Mississippi State Bulldogs Missouri Tigers", CollegeTeams())!;
        foreach (var png in new[]
        {
            CardArtService.RenderChannelMatchup(teams, CardArtService.LeagueOrSport(teams.League, "Baseball"), null, null),
            CardArtService.RenderChannelMatchup(TeamDirectory.SplitOnly("Riverton Otters vs Lakeside Herons")!, CardArtService.LeagueOrSport(null, "Sports"), null, null),
            CardArtService.RenderChannelMatchup(TeamDirectory.SplitOnly(new string('W', 60) + " vs " + new string('M', 60))!, "American Football", null, null)
        })
        {
            using var bmp = SKBitmap.Decode(png);
            Assert.Equal((CardArtService.Width, CardArtService.Height), (bmp.Width, bmp.Height));
        }

        Assert.Equal("College Football", CardArtService.LeagueOrSport("football/college-football", "Baseball"));
        Assert.Equal("American Football", CardArtService.LeagueOrSport(null, "American Football"));
        Assert.Null(CardArtService.LeagueOrSport(null, "Sports"));
        Assert.Equal("c2", CardArtService.Version(null, DateTimeOffset.UtcNow)); // new cards replace the old plain ones
    }
}
