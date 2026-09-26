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

    // Trimmed from the real football/college-football board of 2026-09-26.
    private const string Cfb = """
        {
          "leagues": [{ "id": "23", "abbreviation": "NCAAF" }],
          "week": { "number": 4 },
          "events": [{
            "id": "401754533", "date": "2026-09-26T16:00Z", "shortName": "TEX @ TENN",
            "status": { "clock": 625.0, "displayClock": "10:25", "period": 4,
                        "type": { "name": "STATUS_IN_PROGRESS", "state": "in", "shortDetail": "10:25 - 4th" } },
            "competitions": [{
              "broadcasts": [{ "market": "national", "names": ["ABC"] }],
              "competitors": [
                { "homeAway": "home", "score": "17", "curatedRank": { "current": 14 },
                  "team": { "id": "2633", "abbreviation": "TENN", "displayName": "Tennessee Volunteers", "shortDisplayName": "Tennessee", "name": "Volunteers", "location": "Tennessee", "logo": "https://a.espncdn.com/i/teamlogos/ncaa/500/2633.png" } },
                { "homeAway": "away", "score": "24", "curatedRank": { "current": 99 },
                  "team": { "id": "251", "abbreviation": "TEX", "displayName": "Texas Longhorns", "shortDisplayName": "Texas", "name": "Longhorns", "location": "Texas" } }
              ],
              "situation": { "downDistanceText": "4th & 24 at TENN 16", "isRedZone": false, "possession": "2633",
                             "lastPlay": { "text": "Punt", "probability": { "homeWinPercentage": 0.189 } } }
            }]
          }]
        }
        """;

    [Fact]
    public void Parses_College_Football_With_Poll_Rankings()
    {
        var g = Assert.Single(EspnScoreboardParser.Parse(Cfb, "football/college-football"));
        Assert.Equal("football", g.Sport);
        Assert.Equal("NCAAF", g.League);
        Assert.Equal("football/college-football", g.LeaguePath);
        Assert.Equal(14, g.Home.Rank);
        Assert.Null(g.Away.Rank); // 99 is ESPN's "unranked"
        Assert.True(g.Home.HasPossession);
        Assert.Equal("4th & 24 at TENN 16", g.DownDistance);
        Assert.Equal(new[] { "ABC" }, g.Broadcasts);
        Assert.Null(ScoreboardService.BoardDay(Cfb)); // a weekly board, like the NFL's
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

public class CollegeFootballMatchTests
{
    private static GameTeam Team(string id, string abbr, string location, string nickname, string? shortName = null) => new()
    {
        Id = id, Abbr = abbr, Location = location, Nickname = nickname,
        ShortName = shortName ?? location, Name = location + " " + nickname
    };

    private static GameInfo Game(string id, GameTeam away, GameTeam home, string league = "NCAAF") => new()
    {
        Id = id, League = league, Sport = "football", State = "pre", Away = away, Home = home
    };

    // A slice of a real Saturday: schools inside other schools' names, and nicknames several schools share.
    private static List<GameInfo> Saturday() => new()
    {
        Game("tex", Team("251", "TEX", "Texas", "Longhorns"), Team("2633", "TENN", "Tennessee", "Volunteers")),
        Game("ttu", Team("2534", "SHSU", "Sam Houston", "Bearkats"), Team("2641", "TTU", "Texas Tech", "Red Raiders")),
        Game("msu", Team("127", "MSU", "Michigan State", "Spartans"), Team("356", "ILL", "Illinois", "Fighting Illini")),
        Game("mich", Team("130", "MICH", "Michigan", "Wolverines"), Team("2294", "IOWA", "Iowa", "Hawkeyes")),
        Game("lsu", Team("99", "LSU", "LSU", "Tigers"), Team("2", "AUB", "Auburn", "Tigers")),
        Game("uga", Team("61", "UGA", "Georgia", "Bulldogs"), Team("344", "MSST", "Mississippi State", "Bulldogs")),
        Game("clem", Team("228", "CLEM", "Clemson", "Tigers"), Team("2579", "SC", "South Carolina", "Gamecocks")),
        Game("nfl", Team("22", "ARI", "Arizona", "Cardinals"), Team("29", "CAR", "Carolina", "Panthers"), "NFL"),
        Game("ariz", Team("12", "ARIZ", "Arizona", "Wildcats"), Team("2439", "UNLV", "UNLV", "Rebels")),
    };

    private static List<string> Matched(string channel, List<GameInfo>? games = null)
    {
        games ??= Saturday();
        GameChannelMatcher.Match(games, new[] { new ChannelProbe("c", channel, null) });
        return games.Where(g => g.Channels.Any(c => c.Kind == "teams")).Select(g => g.Id).ToList();
    }

    [Theory]
    [InlineData("NCAAF: Texas Longhorns vs Tennessee Volunteers", "tex")]
    [InlineData("Texas at Tennessee", "tex")]
    [InlineData("TEX @ TENN", "tex")]
    [InlineData("Sam Houston vs Texas Tech", "ttu")]
    [InlineData("Michigan State vs Illinois", "msu")]
    [InlineData("Michigan Wolverines at Iowa Hawkeyes", "mich")]
    [InlineData("LSU Tigers vs Auburn Tigers", "lsu")]
    [InlineData("Georgia vs Mississippi State", "uga")]
    [InlineData("Clemson vs South Carolina", "clem")]
    [InlineData("Arizona Cardinals vs Carolina Panthers", "nfl")]
    [InlineData("Cardinals @ Panthers", "nfl")]
    [InlineData("Arizona vs UNLV", "ariz")]
    public void Each_Matchup_Names_Its_Own_Game(string channel, string game)
        => Assert.Equal(new[] { game }, Matched(channel));

    [Theory]
    [InlineData("Texas Tech vs Tennessee")]   // "Texas" inside "Texas Tech" is not Texas
    [InlineData("Michigan State vs Iowa")]    // nor "Michigan" inside "Michigan State"
    [InlineData("Tigers vs Tigers")]          // three Tigers play today: shared nicknames alone name none of them
    [InlineData("Bulldogs vs Bulldogs")]
    [InlineData("Tigers vs Bulldogs")]
    [InlineData("Arizona Cardinals vs UNLV")] // the NFL team's name is not the school's
    public void Names_Another_Team_Owns_Do_Not_Match(string channel)
        => Assert.Empty(Matched(channel));

    [Theory]
    [InlineData("Tigers vs Gamecocks", "clem")]            // one of the three Tigers plays the Gamecocks
    [InlineData("Bulldogs vs Mississippi State", "uga")]
    public void A_Shared_Name_Counts_When_The_Other_Team_Pins_The_Game(string channel, string game)
        => Assert.Equal(new[] { game }, Matched(channel));

    [Theory]
    [InlineData("SEC Network", "SEC Network HD")]
    [InlineData("SEC Network", "SECN")]
    [InlineData("BTN", "Big Ten Network")]
    [InlineData("ACC Network", "US: ACC Network")]
    [InlineData("ESPNU", "ESPN U")]
    [InlineData("CW", "The CW")]
    public void College_Networks_Are_Normalized(string broadcast, string channel)
    {
        var g = Saturday()[0];
        g.Broadcasts = new List<string> { broadcast };
        GameChannelMatcher.Match(new[] { g }, new[] { new ChannelProbe("c", channel, null) });
        Assert.Equal("network", Assert.Single(g.Channels).Kind);
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
    public void A_Ranked_Team_Losing_Late_Is_An_Upset_Alert()
    {
        var upset = Live("football", 3, 400, 10, 21);
        upset.Home.Rank = 5;
        var expected = Live("football", 3, 400, 21, 10);
        expected.Home.Rank = 5;
        var close = Live("football", 3, 400, 10, 21);
        (close.Home.Rank, close.Away.Rank) = (5, 12); // a top-5 team losing to #12 is no upset
        var early = Live("football", 1, 400, 0, 7);
        early.Home.Rank = 5;

        Assert.Contains("UPSET ALERT", Heated(upset).Tags);
        Assert.True(upset.Heat > Heated(expected).Heat);
        Assert.DoesNotContain("UPSET ALERT", Heated(close).Tags);
        Assert.DoesNotContain("UPSET ALERT", Heated(early).Tags);
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
        Assert.Equal(new[] { "football/nfl", "football/college-football", "baseball/mlb" }, ScoreboardService.ParseLeagues(null));
        Assert.Contains("football/nfl", ScoreboardService.ParseLeagues("  "));

        var parsed = ScoreboardService.ParseLeagues("football/nfl, soccer/eng.1\nfootball/nfl, ../../etc/passwd, nfl, a/b?x=1");
        Assert.Equal(new[] { "football/nfl", "soccer/eng.1" }, parsed);
    }

    [Fact]
    public void League_Config_Takes_Short_Names()
        => Assert.Equal(
            new[] { "football/nfl", "football/college-football", "baseball/mlb" },
            ScoreboardService.ParseLeagues("NFL, ncaaf, cfb, mlb, football/nfl"));
}
