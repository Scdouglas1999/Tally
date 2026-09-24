using Jellyfin.Plugin.Tally.Scores;
using Jellyfin.Plugin.Tally.Services;
using SkiaSharp;
using Xunit;

namespace Tally.Tests;

public class CardArtTests
{
    private static GameInfo JetsPackers(string state = "pre") => new()
    {
        Id = "401", Sport = "football", League = "NFL", State = state,
        Start = new DateTimeOffset(2026, 9, 20, 17, 0, 0, TimeSpan.Zero),
        Broadcasts = new List<string> { "FOX" },
        Home = new GameTeam { Abbr = "GB", Name = "Green Bay Packers", ShortName = "Packers", Nickname = "Packers", Location = "Green Bay" },
        Away = new GameTeam { Abbr = "NYJ", Name = "New York Jets", ShortName = "Jets", Nickname = "Jets", Location = "New York" }
    };

    private static SKBitmap Decode(byte[] png)
    {
        var bmp = SKBitmap.Decode(png);
        Assert.NotNull(bmp);
        return bmp;
    }

    [Fact]
    public void Matchup_Card_Is_A_16x9_Png_With_Something_Drawn_On_It()
    {
        var png = CardArtService.RenderMatchup(JetsPackers(), awayLogo: null, homeLogo: null, TimeZoneInfo.Utc, DateTimeOffset.UtcNow);
        using var bmp = Decode(png);

        Assert.Equal(CardArtService.Width, bmp.Width);
        Assert.Equal(CardArtService.Height, bmp.Height);

        // amber rule along the top, dark ground, and light pixels where the team names are set
        Assert.Equal(SKColor.Parse("#ffb000"), bmp.GetPixel(640, 2));
        Assert.Equal(SKColor.Parse("#0e0f0e"), bmp.GetPixel(640, 140));
        var lit = 0;
        for (var x = 100; x < 1180; x += 2)
        {
            for (var y = 520; y < 600; y += 2)
            {
                if (bmp.GetPixel(x, y).Red > 200) { lit++; }
            }
        }

        Assert.True(lit > 300, $"team names should be drawn large (lit pixels: {lit})");
    }

    [Fact]
    public void Title_Card_Handles_Short_Long_And_Unbreakable_Names()
    {
        foreach (var name in new[] { "ESPN", "New York Jets Green Bay Packers", new string('W', 90), "Ünïcödé Sports ⚽ 日本" })
        {
            using var bmp = Decode(CardArtService.RenderTitle(name, "NFL"));
            Assert.Equal(CardArtService.Width, bmp.Width);
        }
    }

    [Fact]
    public void Only_Confident_Matches_Get_A_Matchup_Card_And_Live_Games_Win_The_Channel()
    {
        var live = JetsPackers("in");
        var later = JetsPackers();
        later.Id = "402";
        later.Start = live.Start.AddDays(7);
        var other = new GameInfo
        {
            Id = "403", State = "in", Broadcasts = new List<string> { "FOX" },
            Home = new GameTeam { Abbr = "CHI", Name = "Chicago Bears", ShortName = "Bears", Nickname = "Bears", Location = "Chicago" },
            Away = new GameTeam { Abbr = "MIN", Name = "Minnesota Vikings", ShortName = "Vikings", Nickname = "Vikings", Location = "Minnesota" }
        };

        var index = CardArtService.BuildIndex(
            new List<GameInfo> { later, other, live },
            new[] { new ChannelProbe("named", "New York Jets Green Bay Packers", null), new ChannelProbe("fox", "FOX HD", null) });

        Assert.Equal("401", index["named"].Id);          // the live game, not next week's rematch
        Assert.False(index.ContainsKey("fox"));           // broadcaster-only: could be either regional game
        Assert.Equal("c", CardArtService.Version(null, DateTimeOffset.UtcNow));
    }

    [Fact]
    public void A_Live_Card_Gets_A_New_Version_When_The_Score_Moves_Or_Two_Minutes_Pass()
    {
        var t0 = new DateTimeOffset(2026, 9, 20, 20, 0, 0, TimeSpan.Zero);
        var pre = JetsPackers();
        Assert.Equal(CardArtService.Version(pre, t0), CardArtService.Version(pre, t0.AddHours(3))); // nothing to redraw before kickoff

        var live = JetsPackers("in");
        live.Home.Score = 7;
        var v = CardArtService.Version(live, t0);
        Assert.Equal(v, CardArtService.Version(live, t0.AddSeconds(30)));
        Assert.NotEqual(v, CardArtService.Version(live, t0.AddSeconds(121)));

        live.Home.Score = 14;
        Assert.NotEqual(v, CardArtService.Version(live, t0));
    }

    [Fact]
    public void Live_Card_Puts_The_Score_Front_And_Center()
    {
        var live = JetsPackers("in");
        live.Home.Score = 17; live.Away.Score = 17; live.Detail = "1:32 - 4th"; live.Heat = 95; live.Tags = new List<string> { "RED ZONE" };
        using var bmp = Decode(CardArtService.RenderMatchup(live, null, null, TimeZoneInfo.Utc, DateTimeOffset.UtcNow));

        Assert.Equal(SKColor.Parse("#ff3b30"), bmp.GetPixel(640, 70));          // hot tag block, above its text
        var lit = 0;
        for (var x = 200; x < 480; x += 2)
        {
            for (var y = 420; y < 545; y += 2)
            {
                if (bmp.GetPixel(x, y).Red > 200) { lit++; }
            }
        }

        Assert.True(lit > 400, $"away score should be set very large (lit pixels: {lit})");
    }

    [Fact]
    public void Card_Address_Follows_The_Name_Not_The_Volatile_Channel_Id()
    {
        var before = new Jellyfin.Plugin.Tally.Models.SourceChannel { Id = "aaaa1111", Name = "Denver Broncos Jacksonville Jaguars" };
        var rescanned = new Jellyfin.Plugin.Tally.Models.SourceChannel { Id = "bbbb2222", Name = " denver broncos jacksonville jaguars " };
        var t = DateTimeOffset.UtcNow;

        Assert.Equal(CardArtService.StableKey(before.Name), CardArtService.StableKey(rescanned.Name));
        Assert.NotEqual(CardArtService.StableKey(before.Name), CardArtService.StableKey("Cleveland Browns Tampa Bay Buccaneers"));

        var path = CardArtService.CardPath(before, null, t);
        Assert.StartsWith("/JellyTV/Card/" + CardArtService.StableKey(before.Name) + ".png?v=c&n=Denver%20Broncos", path);
        Assert.DoesNotContain("aaaa1111", path);
    }

    [Fact]
    public void Native_Channel_Order_Is_Hottest_Live_Game_First_Then_Upcoming_Then_The_Rest()
    {
        static Jellyfin.Plugin.Tally.Models.SourceChannel Ch(string id) => new() { Id = id, Name = id };
        var channels = new[] { Ch("plain"), Ch("soon"), Ch("warm"), Ch("hot"), Ch("later") };
        var t = new DateTimeOffset(2026, 9, 20, 20, 0, 0, TimeSpan.Zero);
        var games = new Dictionary<string, GameInfo>
        {
            ["warm"] = new() { State = "in", Heat = 40 },
            ["hot"] = new() { State = "in", Heat = 95 },
            ["soon"] = new() { State = "pre", Start = t.AddMinutes(20) },
            ["later"] = new() { State = "pre", Start = t.AddHours(4) }
        };

        Assert.Equal(new[] { "hot", "warm", "soon", "later", "plain" }, CardArtService.HeatOrder(channels, games).Select(c => c.Id));
    }
}

public class GameScheduleTests
{
    [Fact]
    public void Guide_Slot_Covers_The_Game_And_Stretches_While_It_Is_Still_On()
    {
        var start = new DateTimeOffset(2026, 9, 20, 17, 0, 0, TimeSpan.Zero);
        var g = new GameInfo { Sport = "football", State = "pre", Start = start,
            Home = new GameTeam { Name = "Green Bay Packers" }, Away = new GameTeam { Name = "New York Jets" } };

        Assert.Equal("New York Jets at Green Bay Packers", GameSchedule.Title(g));
        Assert.Equal(start.AddMinutes(210), GameSchedule.ExpectedEnd(g, start));

        g.State = "in";                                   // overtime: four hours in and still playing
        var now = start.AddHours(4);
        Assert.Equal(now.AddMinutes(30), GameSchedule.ExpectedEnd(g, now));
    }
}
