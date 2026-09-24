using Jellyfin.Plugin.Tally.Scores;
using Xunit;

namespace Tally.Tests;

public class EspnScoreboardParserTests
{
    // Trimmed from a real football/nfl scoreboard response.
    private const string Nfl = """
        {
          "leagues": [{ "id": "28", "abbreviation": "NFL" }],
          "events": [{
            "id": "401872933", "date": "2026-09-20T17:00Z", "shortName": "CAR @ ATL",
            "status": { "clock": 478.0, "displayClock": "7:58", "period": 4,
                        "type": { "state": "in", "shortDetail": "7:58 - 4th" } },
            "competitions": [{
              "broadcasts": [{ "market": "national", "names": ["FOX"] }],
              "geoBroadcasts": [{ "media": { "shortName": "FOX" } }, { "media": { "shortName": "NFL+" } }],
              "competitors": [
                { "homeAway": "home", "score": "27", "records": [{ "summary": "0-1" }],
                  "team": { "id": "1", "abbreviation": "ATL", "displayName": "Atlanta Falcons", "shortDisplayName": "Falcons", "name": "Falcons", "location": "Atlanta", "logo": "https://a/atl.png" } },
                { "homeAway": "away", "score": "34",
                  "team": { "id": "29", "abbreviation": "CAR", "displayName": "Carolina Panthers", "shortDisplayName": "Panthers", "name": "Panthers", "location": "Carolina" } }
              ],
              "situation": {
                "downDistanceText": "2nd & 7 at CAR 36", "isRedZone": true, "possession": "29",
                "lastPlay": { "text": " (Shotgun) J.Strand pass short left for 3 yards. ", "scoreValue": 0,
                              "type": { "text": "Pass Reception" },
                              "probability": { "homeWinPercentage": 0.31 } }
              }
            }]
          },
          { "id": "broken" }]
        }
        """;

    [Fact]
    public void Parses_Football_Score_Situation_And_Broadcasts()
    {
        var games = EspnScoreboardParser.Parse(Nfl, "football/nfl");

        var g = Assert.Single(games); // the malformed event is skipped, not fatal
        Assert.Equal("football", g.Sport);
        Assert.Equal("NFL", g.League);
        Assert.Equal("in", g.State);
        Assert.Equal(4, g.Period);
        Assert.Equal(478, g.ClockSeconds);
        Assert.Equal(27, g.Home.Score);
        Assert.Equal(34, g.Away.Score);
        Assert.Equal("ATL", g.Home.Abbr);
        Assert.Equal("0-1", g.Home.Record);
        Assert.True(g.Away.HasPossession);
        Assert.False(g.Home.HasPossession);
        Assert.True(g.RedZone);
        Assert.Equal("2nd & 7 at CAR 36", g.DownDistance);
        Assert.Equal("(Shotgun) J.Strand pass short left for 3 yards.", g.LastPlay);
        Assert.Equal(0.31, g.HomeWinPct);
        Assert.Equal(new[] { "FOX", "NFL+" }, g.Broadcasts);
    }

    [Fact]
    public void Soccer_Last_Play_Comes_From_Details()
    {
        const string json = """
            { "leagues": [{ "abbreviation": "ENG.1" }],
              "events": [{ "id": "1", "date": "2026-09-20T13:00Z", "shortName": "LIV @ BOU",
                "status": { "clock": 4920.0, "displayClock": "82'", "period": 2, "type": { "state": "in", "shortDetail": "82'" } },
                "competitions": [{ "situation": null,
                  "details": [
                    { "type": { "text": "Yellow Card" }, "clock": { "displayValue": "46'" }, "scoringPlay": false },
                    { "type": { "text": "Goal" }, "clock": { "displayValue": "80'" }, "scoringPlay": true, "scoreValue": 1,
                      "athletesInvolved": [{ "shortName": "M. Salah" }] }],
                  "competitors": [
                    { "homeAway": "home", "score": "0", "team": { "id": "349", "abbreviation": "BOU", "displayName": "AFC Bournemouth", "shortDisplayName": "Bournemouth", "name": "AFC Bournemouth", "location": "AFC Bournemouth" } },
                    { "homeAway": "away", "score": "1", "team": { "id": "364", "abbreviation": "LIV", "displayName": "Liverpool" } }] }] }] }
            """;

        var g = Assert.Single(EspnScoreboardParser.Parse(json, "soccer/eng.1"));
        Assert.Equal("80' Goal — M. Salah", g.LastPlay);
        Assert.Equal(1, g.LastPlayScore);
        Assert.Equal("Liverpool", g.Away.ShortName); // falls back to the full name
    }

    [Fact]
    public void Empty_Or_Eventless_Board_Is_Not_An_Error()
    {
        Assert.Empty(EspnScoreboardParser.Parse("{}", "hockey/nhl"));
        Assert.Empty(EspnScoreboardParser.Parse("""{ "events": [] }""", "hockey/nhl"));
    }
}

public class GameChannelMatcherTests
{
    private static GameInfo ChiefsBills(params string[] broadcasts) => new()
    {
        Id = "g1", State = "in", Broadcasts = broadcasts.ToList(),
        Home = new GameTeam { Abbr = "BUF", Name = "Buffalo Bills", ShortName = "Bills", Nickname = "Bills", Location = "Buffalo" },
        Away = new GameTeam { Abbr = "KC", Name = "Kansas City Chiefs", ShortName = "Chiefs", Nickname = "Chiefs", Location = "Kansas City" }
    };

    private static List<string> Kinds(GameInfo g, params ChannelProbe[] channels)
    {
        GameChannelMatcher.Match(new[] { g }, channels);
        return g.Channels.Select(c => c.Id + ":" + c.Kind).ToList();
    }

    [Theory]
    [InlineData("Chiefs vs Bills")]
    [InlineData("NFL: Kansas City Chiefs at Buffalo Bills (HD)")]
    [InlineData("KC @ BUF")]
    [InlineData("Buffalo - Kansas City")]
    public void Channel_Named_After_The_Matchup_Is_A_Teams_Match(string name)
        => Assert.Equal(new[] { "c:teams" }, Kinds(ChiefsBills(), new ChannelProbe("c", name, null)));

    [Theory]
    [InlineData("Chiefs Kingdom Radio")]          // one team only
    [InlineData("BUF KC")]                        // bare abbreviations, nothing matchup-shaped
    [InlineData("Billions S01E02")]               // "Bills" must match as a whole word
    public void Near_Misses_Do_Not_Match(string name)
        => Assert.Empty(Kinds(ChiefsBills(), new ChannelProbe("c", name, null)));

    [Fact]
    public void Current_Programme_Title_Is_An_Epg_Match()
        => Assert.Equal(new[] { "c:epg" }, Kinds(ChiefsBills(), new ChannelProbe("c", "Sports 4", "NFL Football: Chiefs @ Bills")));

    [Theory]
    [InlineData("ESPN", "ESPN HD")]
    [InlineData("ESPN", "US: ESPN FHD")]
    [InlineData("FS1", "Fox Sports 1")]
    [InlineData("NFL Net", "NFL Network HD")]
    [InlineData("USA Net", "USA Network")]
    public void Broadcaster_Names_Are_Normalized(string broadcast, string channel)
        => Assert.Equal(new[] { "c:network" }, Kinds(ChiefsBills(broadcast), new ChannelProbe("c", channel, null)));

    [Theory]
    [InlineData("ESPN+", "ESPN")]      // the streaming service is not the linear channel
    [InlineData("ESPN", "ESPN2")]
    [InlineData("FOX", "Fox Sports 1")]
    public void Different_Networks_Stay_Different(string broadcast, string channel)
        => Assert.Empty(Kinds(ChiefsBills(broadcast), new ChannelProbe("c", channel, null)));

    [Fact]
    public void Strongest_Signal_Is_Listed_First_And_Finished_Games_Match_Nothing()
    {
        var g = ChiefsBills("CBS");
        var channels = new[]
        {
            new ChannelProbe("net", "CBS HD", null),
            new ChannelProbe("epg", "Sports 2", "Chiefs at Bills"),
            new ChannelProbe("teams", "Chiefs v Bills", null)
        };

        Assert.Equal(new[] { "teams:teams", "epg:epg", "net:network" }, Kinds(g, channels));

        g.State = "post";
        Assert.Empty(Kinds(g, channels));
    }
}

public class GameHeatTests
{
    private static readonly DateTimeOffset Now = new(2026, 9, 20, 20, 0, 0, TimeSpan.Zero);

    private static GameInfo Live(string sport, int period, double clock, int home, int away) => new()
    {
        Sport = sport, State = "in", Period = period, ClockSeconds = clock,
        Home = new GameTeam { Score = home }, Away = new GameTeam { Score = away }
    };

    private static GameInfo Heated(GameInfo g)
    {
        GameHeat.Apply(g, Now);
        return g;
    }

    [Fact]
    public void Late_One_Score_Red_Zone_Football_Outranks_A_Blowout()
    {
        var thriller = Live("football", 4, 95, 24, 27);
        thriller.RedZone = true;
        var blowout = Live("football", 4, 95, 3, 34);

        Heated(thriller);
        Heated(blowout);

        Assert.Equal(new[] { "RED ZONE", "TWO-MINUTE DRILL", "ONE-SCORE GAME" }, thriller.Tags);
        Assert.Empty(blowout.Tags);
        Assert.True(thriller.Heat > blowout.Heat + 40);
    }

    [Fact]
    public void Early_Close_Game_Is_Warm_Not_Hot()
    {
        var g = Heated(Live("football", 1, 600, 7, 7));
        Assert.Empty(g.Tags);
        Assert.InRange(g.Heat, 1, 40);
    }

    [Fact]
    public void Sport_Specific_Moments_Are_Tagged()
    {
        var bases = Live("baseball", 8, 0, 3, 4);
        bases.OnFirst = bases.OnSecond = bases.OnThird = true;
        Assert.Equal(new[] { "BASES LOADED", "LATE & CLOSE" }, Heated(bases).Tags);

        Assert.Equal(new[] { "OVERTIME" }, Heated(Live("hockey", 4, 200, 2, 2)).Tags);
        Assert.Equal(new[] { "CLUTCH TIME" }, Heated(Live("basketball", 4, 120, 101, 99)).Tags);
        Assert.Equal(new[] { "LATE DRAMA" }, Heated(Live("soccer", 2, 80 * 60, 1, 1)).Tags);
    }

    [Fact]
    public void Only_Live_Or_Imminent_Games_Carry_Heat()
    {
        var soon = new GameInfo { State = "pre", Start = Now.AddMinutes(10) };
        var later = new GameInfo { State = "pre", Start = Now.AddHours(3) };
        var final = Live("football", 4, 0, 20, 17);
        final.State = "post";

        Assert.Equal(new[] { "STARTING SOON" }, Heated(soon).Tags);
        Assert.Equal(0, Heated(later).Heat);
        Assert.Equal(0, Heated(final).Heat);
    }

    [Fact]
    public void Heat_Is_Recomputed_From_Scratch_And_Clamped()
    {
        var g = Live("football", 5, 30, 30, 30);
        g.RedZone = true;
        g.HomeWinPct = 0.5;
        g.LastPlayScore = 3;

        Heated(g);
        Heated(g); // applying twice must not stack tags

        Assert.Equal(new[] { "RED ZONE", "OVERTIME" }, g.Tags);
        Assert.Equal(100, g.Heat);
    }
}

public class ScoreboardServiceTests
{
    [Fact]
    public void Scoreboard_Url_Is_Host_Plus_League_Path()
        => Assert.Equal("https://site.web.api.espn.com/apis/site/v2/sports/football/nfl/scoreboard",
            ScoreboardService.ScoreboardUrl("site.web.api.espn.com", "football/nfl"));

    [Fact]
    public void Without_An_Override_The_Board_Comes_From_Espn_Hosts_In_Order()
        => Assert.Equal(
            new[]
            {
                "https://site.web.api.espn.com/apis/site/v2/sports/baseball/mlb/scoreboard",
                "https://site.api.espn.com/apis/site/v2/sports/baseball/mlb/scoreboard",
            },
            ScoreboardService.ScoreboardUrls("baseball/mlb", "  "));

    [Fact]
    public void A_Development_Override_Replaces_Espn_Entirely()
        => Assert.Equal(
            new[] { "http://172.17.0.1:8765/apis/site/v2/sports/baseball/mlb/scoreboard" },
            ScoreboardService.ScoreboardUrls("baseball/mlb", "http://172.17.0.1:8765/"));

    [Fact]
    public void College_Boards_Ask_For_Every_Game_Not_Just_Ranked_Teams()
    {
        Assert.EndsWith("/football/college-football/scoreboard?groups=80&limit=300", ScoreboardService.ScoreboardUrl("h", "football/college-football"));
        Assert.EndsWith("/basketball/mens-college-basketball/scoreboard?groups=50&limit=300", ScoreboardService.ScoreboardUrl("h", "basketball/mens-college-basketball"));
    }

    [Fact]
    public void A_Dated_Board_Adds_The_Day_To_The_Query()
    {
        Assert.EndsWith("/baseball/mlb/scoreboard?dates=20260923", ScoreboardService.ScoreboardUrl("h", "baseball/mlb", "20260923"));
        Assert.EndsWith("/football/college-football/scoreboard?groups=80&limit=300&dates=20260926", ScoreboardService.ScoreboardUrl("h", "football/college-football", "20260926"));
    }

    [Fact]
    public void Daily_Boards_Report_Their_Day_And_Weekly_Boards_Do_Not()
    {
        Assert.Equal("2026-09-22", ScoreboardService.BoardDay("{\"day\":{\"date\":\"2026-09-22\"},\"events\":[]}"));
        Assert.Null(ScoreboardService.BoardDay("{\"week\":{\"number\":3},\"events\":[]}"));
    }

    [Fact]
    public void Espn_Days_Follow_Eastern_Time()
    {
        // 03:30 UTC on the 23rd is still the evening of the 22nd in New York
        Assert.Equal("2026-09-22", ScoreboardService.EspnToday(new DateTimeOffset(2026, 9, 23, 3, 30, 0, TimeSpan.Zero)));
        Assert.Equal("2026-09-23", ScoreboardService.EspnToday(new DateTimeOffset(2026, 9, 23, 12, 14, 0, TimeSpan.Zero)));
    }

    [Fact]
    public void A_Stale_Morning_Board_Gains_Todays_Games_Once_Each()
    {
        var last = new List<GameInfo> { new() { Id = "1", State = "post" }, new() { Id = "2", State = "in" } };
        var today = new List<GameInfo> { new() { Id = "2", State = "in" }, new() { Id = "3", State = "pre" } };
        Assert.Equal(new[] { "1", "2", "3" }, ScoreboardService.MergeBoards(last, today).Select(g => g.Id));
    }

    [Fact]
    public void League_Config_Falls_Back_To_Defaults_And_Rejects_Anything_Not_A_Path()
    {
        Assert.Equal(new[] { "football/nfl", "baseball/mlb" }, ScoreboardService.ParseLeagues(null));
        Assert.Contains("football/nfl", ScoreboardService.ParseLeagues("  "));

        var parsed = ScoreboardService.ParseLeagues("football/nfl, soccer/eng.1\nfootball/nfl, ../../etc/passwd, nfl, a/b?x=1");
        Assert.Equal(new[] { "football/nfl", "soccer/eng.1" }, parsed);
    }
}
