using Jellyfin.Plugin.Tally;
using Jellyfin.Plugin.Tally.Live;
using Jellyfin.Plugin.Tally.Models;
using Jellyfin.Plugin.Tally.Scores;
using Jellyfin.Plugin.Tally.Sources;
using Microsoft.Extensions.Logging.Abstractions;
using Xunit;

namespace Tally.Tests;

public class RedZoneDirectorTests
{
    private static readonly DateTimeOffset T0 = new(2026, 9, 27, 18, 0, 0, TimeSpan.Zero);

    private static RedZoneGame G(string id, int heat, string sport = "football", bool redZone = false, bool twoMinute = false,
        bool overtime = false, bool close = false, int points = 0, string? channel = "ch-")
        => new(id, id + " title", sport, heat, redZone, twoMinute, overtime, close, points, channel == "ch-" ? "ch-" + id : channel);

    private static RedZoneDirector Director() => new(TimeSpan.FromSeconds(60));

    [Fact]
    public void Opens_On_The_Hottest_Game_With_A_Stream_Football_Winning_Ties()
    {
        var d = Director();
        var cut = d.Update(T0, new[] { G("mlb", 60, "baseball"), G("nfl", 60), G("hot-no-stream", 90, channel: null) });
        Assert.NotNull(cut);
        Assert.Equal("nfl", d.CurrentGame);
        Assert.Equal("ch-nfl", d.CurrentChannel);
        Assert.Equal("hottest", d.Reason);
        Assert.Equal(T0, d.Since);
        Assert.Equal(new[] { "mlb" }, d.Next.Select(g => g.Id));
    }

    [Fact]
    public void A_Red_Zone_Two_Minute_Drill_Or_Overtime_Outranks_Any_Game_That_Is_None_Of_Those()
    {
        var ranked = RedZoneDirector.Rank(new[] { G("close", 75, close: true), G("drill", 50, twoMinute: true), G("rz", 40, redZone: true), G("ot", 45, "hockey", overtime: true) });
        Assert.Equal(new[] { "drill", "ot", "rz", "close" }, ranked.Select(g => g.Id));
        Assert.Equal("two-minute drill", RedZoneDirector.ReasonOf(ranked[0]));
        Assert.Equal("close", RedZoneDirector.ReasonOf(ranked[3]));
    }

    [Fact]
    public void Holds_A_Game_For_The_Minimum_Dwell_Then_Leaves_Only_For_One_Hotter_By_15()
    {
        var d = Director();
        d.Update(T0, new[] { G("a", 50), G("b", 40) });
        Assert.Equal("a", d.CurrentGame);

        // b heats up: 70 beats 50 by 20, but a is held for 60 s
        Assert.Null(d.Update(T0.AddSeconds(30), new[] { G("a", 50), G("b", 70) }));
        Assert.Equal("a", d.CurrentGame);

        var cut = d.Update(T0.AddSeconds(61), new[] { G("a", 50), G("b", 70) });
        Assert.Equal(("a", "b", "hottest"), (cut!.FromGame, cut.ToGame, cut.Reason));

        // a is now 14 hotter than b: not enough, however long it has been
        Assert.Null(d.Update(T0.AddSeconds(300), new[] { G("a", 84), G("b", 70) }));
        Assert.Equal("b", d.CurrentGame);
    }

    [Fact]
    public void Another_Game_Scoring_Entering_The_Red_Zone_Or_Going_To_Overtime_Cuts_At_Once()
    {
        var d = Director();
        d.Update(T0, new[] { G("a", 60), G("b", 30), G("c", 30), G("d", 30, "hockey") });
        Assert.Equal("a", d.CurrentGame);

        var cut = d.Update(T0.AddSeconds(5), new[] { G("a", 60), G("b", 30, points: 7), G("c", 30), G("d", 30, "hockey") });
        Assert.Equal(("b", "score"), (cut!.ToGame, cut.Reason));

        cut = d.Update(T0.AddSeconds(10), new[] { G("a", 60), G("b", 30, points: 7), G("c", 50, redZone: true), G("d", 30, "hockey") });
        Assert.Equal(("c", "red zone"), (cut!.ToGame, cut.Reason));

        cut = d.Update(T0.AddSeconds(15), new[] { G("a", 60), G("b", 30, points: 7), G("c", 50, redZone: true), G("d", 70, "hockey", overtime: true) });
        Assert.Equal(("d", "overtime"), (cut!.ToGame, cut.Reason));
        Assert.Equal(T0.AddSeconds(15), d.Since);
    }

    [Fact]
    public void A_Red_Zone_Elsewhere_Does_Not_Pull_Away_From_A_Game_That_Is_Itself_In_One_But_A_Score_Does()
    {
        var d = Director();
        d.Update(T0, new[] { G("a", 60, redZone: true), G("b", 30) });
        Assert.Null(d.Update(T0.AddSeconds(5), new[] { G("a", 60, redZone: true), G("b", 50, redZone: true) }));
        Assert.Equal("a", d.CurrentGame);

        var cut = d.Update(T0.AddSeconds(10), new[] { G("a", 60, redZone: true), G("b", 50, redZone: true, points: 3) });
        Assert.Equal(("b", "score"), (cut!.ToGame, cut.Reason));
    }

    [Fact]
    public void A_Score_On_The_Game_On_Screen_Holds_It_For_The_Extra_Point_Then_Ranks_Again_Without_The_Dwell()
    {
        var d = Director();
        d.Update(T0, new[] { G("a", 60, redZone: true), G("b", 30), G("c", 40) });

        // a scores; 5 s later b enters the red zone: the try comes first
        Assert.Null(d.Update(T0.AddSeconds(10), new[] { G("a", 60, points: 6), G("b", 30), G("c", 40) }));
        Assert.Equal("score", d.Reason);
        Assert.Null(d.Update(T0.AddSeconds(15), new[] { G("a", 50, points: 6), G("b", 50, redZone: true), G("c", 40) }));
        Assert.Null(d.Update(T0.AddSeconds(29), new[] { G("a", 50, points: 7), G("b", 50, redZone: true), G("c", 40) }));
        Assert.Equal("a", d.CurrentGame);

        var cut = d.Update(T0.AddSeconds(31), new[] { G("a", 50, points: 7), G("b", 50, redZone: true), G("c", 40) });
        Assert.Equal(("b", "red zone"), (cut!.ToGame, cut.Reason));

        // no event: after a score and its hold, a hotter game takes over before the dwell is up
        var e = Director();
        e.Update(T0, new[] { G("a", 50), G("b", 40) });
        e.Update(T0.AddSeconds(5), new[] { G("a", 50, points: 3), G("b", 40) });
        Assert.Null(e.Update(T0.AddSeconds(20), new[] { G("a", 45, points: 3), G("b", 65) }));
        cut = e.Update(T0.AddSeconds(26), new[] { G("a", 45, points: 3), G("b", 65) });
        Assert.Equal(("b", "hottest"), (cut!.ToGame, cut.Reason));
    }

    [Fact]
    public void A_Game_That_Ends_Or_Loses_Its_Stream_Is_Left_At_Once_And_No_Game_Means_The_Slate()
    {
        var d = Director();
        d.Update(T0, new[] { G("a", 60), G("b", 30) });
        var cut = d.Update(T0.AddSeconds(5), new[] { G("a", 60, channel: null), G("b", 30) });
        Assert.Equal(("a", "b"), (cut!.FromGame, cut.ToGame));

        cut = d.Update(T0.AddSeconds(10), Array.Empty<RedZoneGame>());
        Assert.Equal(("b", (string?)null, "no games live"), (cut!.FromGame, cut.ToGame, cut.Reason));
        Assert.Null(d.CurrentGame);
        Assert.Null(d.CurrentChannel);
        Assert.Null(d.Reason);
        Assert.Null(d.Update(T0.AddSeconds(20), Array.Empty<RedZoneGame>())); // stays on the slate quietly
    }

    [Fact]
    public void The_First_Sighting_Of_A_Game_Is_No_Event()
    {
        var d = Director();
        d.Update(T0, new[] { G("a", 60) });
        Assert.Null(d.Update(T0.AddSeconds(5), new[] { G("a", 60), G("b", 30, points: 21, redZone: true) }));
        Assert.Equal("a", d.CurrentGame);
    }

    [Fact]
    public void Reads_Heat_Tags_And_Score_From_A_Scoreboard_Game()
    {
        var g = new GameInfo { Id = "1", Sport = "football", League = "NFL", State = "in", Period = 4, ClockSeconds = 90, RedZone = true };
        g.Away = new GameTeam { Name = "Kansas City Chiefs", Score = 20 };
        g.Home = new GameTeam { Name = "Buffalo Bills", Score = 17 };
        GameHeat.Apply(g, T0);
        var r = RedZoneGame.From(g, "ch");
        Assert.True(r.RedZone && r.TwoMinuteDrill && r.Close && !r.Overtime);
        Assert.Equal((37, "Kansas City Chiefs at Buffalo Bills", "ch"), (r.Points, r.Title, r.ChannelId));
        Assert.Equal("red zone", RedZoneDirector.ReasonOf(r));
    }
}

public class RedZoneSessionTests
{
    private static readonly DateTimeOffset T0 = new(2026, 9, 27, 18, 0, 0, TimeSpan.Zero);

    // Heads of real segments from the live bed (see TsSplicerTests): A is ffmpeg's layout, B another encoder's on a
    // far-away clock, C a third.
    private static readonly byte[] SegA = LiveFixtures.Bytes("a-1080p60-head.ts");
    private static readonly byte[] SegB = LiveFixtures.Bytes("b-720p30-head.ts");
    private static readonly byte[] SegC = LiveFixtures.Bytes("c-540p30-head.ts");

    /// <summary>Consecutive segments of one source: the head repeated, each continuing the previous one's clock and counters.</summary>
    private static List<byte[]> Chain(byte[] head, int count)
    {
        var info = TsSplicer.Analyze(head);
        var pids = info.Streams.Select(s => s.Pid).ToDictionary(p => p, p => p);
        var list = new List<byte[]> { head };
        var previous = info;
        for (var i = 1; i < count; i++)
        {
            var map = TsSplicer.WithContinuity(new SpliceMap { Offset = TsSplicer.Mod(previous.EndDts!.Value - info.StartDts!.Value), PidMap = pids, InPmtPid = info.PmtPid, OutPmtPid = info.PmtPid }, info, previous.LastCc);
            var next = TsSplicer.Rewrite(head, map);
            list.Add(next);
            previous = TsSplicer.Analyze(next);
        }

        return list;
    }

    private static double Seconds(byte[] seg)
    {
        var i = TsSplicer.Analyze(seg);
        return (i.EndDts!.Value - i.StartDts!.Value) / 90000.0;
    }

    private static void Feed(OutputWindow w, IEnumerable<byte[]> segs, DateTimeOffset now, bool discontinuity = false)
    {
        foreach (var s in segs)
        {
            w.Publish(new PublishedSegment { Duration = Seconds(s), Upstream = new Uri("http://x.example/s.ts"), Discontinuity = discontinuity, Body = Task.FromResult<byte[]?>(s) }, now);
            discontinuity = false;
        }
    }

    /// <summary>The same segment claiming another audio codec (AC-3 instead of AAC): the splicer cannot continue a
    /// copy remux across that.</summary>
    private static byte[] WithAc3Audio(byte[] seg)
    {
        var bytes = seg.ToArray();
        var info = TsSplicer.Analyze(bytes);
        var audio = info.Streams.First(s => s.Kind == TsStreamKind.Audio).Pid;
        for (var i = info.SyncOffset; i + 188 <= bytes.Length; i += 188)
        {
            var pid = ((bytes[i + 1] & 0x1F) << 8) | bytes[i + 2];
            if (pid != info.PmtPid || (bytes[i + 1] & 0x40) == 0)
            {
                continue;
            }

            var payload = i + 4 + (((bytes[i + 3] >> 4) & 0x3) == 3 ? 1 + bytes[i + 4] : 0);
            var s = payload + 1 + bytes[payload];
            var programInfo = ((bytes[s + 10] & 0x0F) << 8) | bytes[s + 11];
            for (var k = s + 12 + programInfo; k + 5 <= i + 188;)
            {
                if ((((bytes[k + 1] & 0x1F) << 8) | bytes[k + 2]) == audio)
                {
                    bytes[k] = 0x81;
                }

                k += 5 + (((bytes[k + 3] & 0x0F) << 8) | bytes[k + 4]);
            }
        }

        return bytes;
    }

    private static List<TsInfo> Served(RedZoneSession s)
        => s.Window.Listed().Select(p => TsSplicer.Analyze(p.Body.Result!)).ToList();

    /// <summary>Every served segment starts at or after where the one before it ended (never back, at most a frame of gap).</summary>
    private static void AssertContinuous(IReadOnlyList<TsInfo> served)
    {
        for (var i = 1; i < served.Count; i++)
        {
            var gap = TsSplicer.Unwrap(served[i].StartDts!.Value, served[i - 1].EndDts!.Value) - served[i - 1].EndDts!.Value;
            Assert.True(gap >= -3000 && gap < 9000, $"segment {i}: {gap / 90.0:0} ms from the previous one's end");
        }
    }

    [Fact]
    public void Cuts_From_One_Game_To_Another_Spliced_Onto_The_First_Games_Pids_Clock_And_Counters()
    {
        var a = new OutputWindow();
        var b = new OutputWindow();
        Feed(a, Chain(SegA, 5), T0);
        Feed(b, Chain(SegB, 4), T0);
        var windows = new Dictionary<string, OutputWindow> { ["a"] = a, ["b"] = b };
        var s = new RedZoneSession();

        s.Target("a", "A", "hottest", T0);
        Assert.Equal(3, s.Pump(T0, id => windows.GetValueOrDefault(id)));          // a fresh start takes the last few
        Feed(a, Chain(SegA, 7).Skip(5), T0.AddSeconds(1));
        Assert.Equal(2, s.Pump(T0.AddSeconds(1), id => windows.GetValueOrDefault(id)));

        s.Target("b", "B", "score", T0.AddSeconds(2));
        Assert.Equal(1, s.Pump(T0.AddSeconds(2), id => windows.GetValueOrDefault(id)));  // caught up with a: b's newest
        Assert.Equal("b", s.Source);

        var served = Served(s);
        Assert.Equal(6, served.Count);
        var join = served[^1];
        var before = served[^2];
        Assert.Equal(0x1000, join.PmtPid);
        Assert.Equal(new[] { 0x100, 0x101 }, join.Streams.Select(x => x.Pid));
        foreach (var pid in new[] { 0, 0x1000, 0x100, 0x101 })
        {
            Assert.Equal((before.LastCc[pid] + 1) & 0xF, join.FirstCc[pid]);
        }

        AssertContinuous(served);
        Assert.DoesNotContain("#EXT-X-DISCONTINUITY", s.Window.Render(n => "/" + n), StringComparison.Ordinal);
        Assert.Equal(("a", "b", "score", "spliced"), (s.Switches[^1].From, s.Switches[^1].To, s.Switches[^1].Reason, s.Switches[^1].How));
    }

    [Fact]
    public void Recent_Lists_The_Cuts_As_The_Playlist_Got_Them_With_Their_Games_Oldest_First()
    {
        var a = new OutputWindow();
        var b = new OutputWindow();
        Feed(a, Chain(SegA, 5), T0);
        var windows = new Dictionary<string, OutputWindow> { ["a"] = a, ["b"] = b };
        var s = new RedZoneSession();
        s.Target("a", "A", "hottest", T0, "game-a");
        s.Pump(T0, id => windows.GetValueOrDefault(id));

        // b is cold when the director cuts to it: the cut reaches the playlist (and recent) only when b has a segment
        s.Target("b", "B", "score", T0.AddSeconds(2), "game-b");
        s.Pump(T0.AddSeconds(2), id => windows.GetValueOrDefault(id));
        Assert.Equal(new[] { "game-a" }, RedZoneService.Recent(s.Switches, T0.AddSeconds(3)).Select(c => c.GameId));
        Feed(b, Chain(SegB, 4), T0.AddSeconds(5));
        s.Pump(T0.AddSeconds(5), id => windows.GetValueOrDefault(id));

        var recent = RedZoneService.Recent(s.Switches, T0.AddSeconds(6));
        Assert.Equal(new[] { ("game-a", "A", "hottest", T0), ("game-b", "B", "score", T0.AddSeconds(5)) },
            recent.Select(c => (c.GameId!, c.Title!, c.Reason!, c.Since)));
        Assert.All(recent, c => Assert.True(c.Active));

        // older than RecentFor drops out, but the cut on now always stays
        Assert.Equal(new[] { "game-b" }, RedZoneService.Recent(s.Switches, T0.AddMinutes(10)).Select(c => c.GameId));
    }

    [Fact]
    public void Recent_Keeps_The_Last_Few_And_Names_No_Game_For_The_Slate()
    {
        var switches = Enumerable.Range(0, 9)
            .Select(i => new RedZoneSwitch(T0.AddSeconds(i * 10), null, i == 8 ? "slate" : "ch" + i, "G" + i, "hottest", i == 8 ? "slate" : "spliced", "g" + i))
            .Append(new RedZoneSwitch(T0.AddSeconds(95), "slate", "ch9", "G9", "score", null, "g9")) // not in the playlist yet
            .ToList();
        var recent = RedZoneService.Recent(switches, T0.AddSeconds(100));
        Assert.Equal(RedZoneService.RecentMax, recent.Count);
        Assert.Equal(new[] { "g3", "g4", "g5", "g6", "g7", null }, recent.Select(c => c.GameId));
        Assert.False(recent[^1].Active);
        Assert.Null(recent[^1].Title);
        Assert.Empty(RedZoneService.Recent(new List<RedZoneSwitch>(), T0));
    }

    [Fact]
    public void Enters_The_New_Game_As_Far_Behind_Its_Live_Edge_As_The_Old_One_Was_Left_And_Waits_For_A_Cold_One()
    {
        var a = new OutputWindow();
        var b = new OutputWindow();
        Feed(a, Chain(SegA, 4), T0);
        var windows = new Dictionary<string, OutputWindow> { ["a"] = a, ["b"] = b };
        var s = new RedZoneSession();
        s.Target("a", "A", "hottest", T0);
        s.Pump(T0, id => windows.GetValueOrDefault(id));                          // a #1..#3; #0 skipped

        // b has nothing yet: a keeps flowing
        s.Target("b", "B", "red zone", T0.AddSeconds(1));
        Feed(a, Chain(SegA, 6).Skip(4), T0.AddSeconds(1));
        Assert.Equal(2, s.Pump(T0.AddSeconds(1), id => windows.GetValueOrDefault(id)));
        Assert.Equal("a", s.Source);
        Assert.Equal(TimeSpan.FromSeconds(3), s.Starved(T0.AddSeconds(4)));

        // a lists two more before b's first segments arrive: those two were not served, so b is entered two back
        Feed(a, Chain(SegA, 8).Skip(6), T0.AddSeconds(2));
        var bSegs = Chain(SegB, 6);
        Feed(b, bSegs, T0.AddSeconds(2));
        Assert.Equal(2, s.Pump(T0.AddSeconds(2), id => windows.GetValueOrDefault(id)));
        Assert.Equal("b", s.Source);
        Assert.Equal(TsSplicer.Analyze(bSegs[4]).Packets, Served(s)[^2].Packets);
        AssertContinuous(Served(s));
    }

    [Fact]
    public void Cuts_Only_Once_The_New_Games_Entry_Segment_Is_In_Memory()
    {
        var a = new OutputWindow();
        var b = new OutputWindow();
        Feed(a, Chain(SegA, 3), T0);
        var windows = new Dictionary<string, OutputWindow> { ["a"] = a, ["b"] = b };
        var s = new RedZoneSession();
        s.Target("a", "A", "hottest", T0);
        s.Pump(T0, id => windows.GetValueOrDefault(id));

        // b's session just started: its segments are listed while they download
        var body = new TaskCompletionSource<byte[]?>();
        b.Publish(new PublishedSegment { Duration = Seconds(SegB), Upstream = new Uri("http://x.example/b.ts"), Body = body.Task }, T0);
        s.Target("b", "B", "score", T0.AddSeconds(1));
        Feed(a, Chain(SegA, 4).Skip(3), T0.AddSeconds(1));
        Assert.Equal(1, s.Pump(T0.AddSeconds(1), id => windows.GetValueOrDefault(id)));
        Assert.Equal("a", s.Source);

        body.SetResult(SegB);
        Assert.Equal(1, s.Pump(T0.AddSeconds(2), id => windows.GetValueOrDefault(id)));
        Assert.Equal("b", s.Source);
        AssertContinuous(Served(s));
    }

    [Fact]
    public void A_Game_In_Another_Codec_Is_Cut_To_With_A_Discontinuity_And_The_Clock_Still_Never_Steps_Back()
    {
        var a = new OutputWindow();
        var c = new OutputWindow();
        Feed(a, Chain(SegA, 3), T0);
        Feed(c, Chain(WithAc3Audio(SegC), 3), T0);
        var windows = new Dictionary<string, OutputWindow> { ["a"] = a, ["c"] = c };
        var s = new RedZoneSession();
        s.Target("a", "A", "hottest", T0);
        s.Pump(T0, id => windows.GetValueOrDefault(id));
        s.Target("c", "C", "overtime", T0.AddSeconds(1));
        s.Pump(T0.AddSeconds(1), id => windows.GetValueOrDefault(id));

        var listed = s.Window.Listed();
        Assert.True(listed[^1].Discontinuity);
        Assert.Contains("#EXT-X-DISCONTINUITY\n", s.Window.Render(n => "/" + n), StringComparison.Ordinal);
        Assert.Equal("discontinuity", s.Switches[^1].How);
        var served = Served(s);
        Assert.Equal(TsSplicer.Analyze(SegC).PmtPid, served[^1].PmtPid);           // its own layout
        AssertContinuous(served);

        // and back: the layout changes again, so again a discontinuity, and again forward in time
        Feed(a, Chain(SegA, 5).Skip(3), T0.AddSeconds(2));
        s.Target("a", "A", "score", T0.AddSeconds(2));
        s.Pump(T0.AddSeconds(2), id => windows.GetValueOrDefault(id));
        Assert.True(s.Window.Listed()[^1].Discontinuity);
        AssertContinuous(Served(s));
    }

    [Fact]
    public void Continues_Across_A_Source_That_Restarts_Or_Breaks_Its_Own_Timeline()
    {
        var a = new OutputWindow();
        Feed(a, Chain(SegA, 3), T0);
        var windows = new Dictionary<string, OutputWindow> { ["a"] = a };
        var s = new RedZoneSession();
        s.Target("a", "A", "hottest", T0);
        s.Pump(T0, id => windows.GetValueOrDefault(id));

        // the game's session switched to another stream of the game without splicing it (a discontinuity)
        Feed(a, Chain(SegB, 2), T0.AddSeconds(1), discontinuity: true);
        s.Pump(T0.AddSeconds(1), id => windows.GetValueOrDefault(id));
        Assert.All(s.Window.Listed(), p => Assert.False(p.Discontinuity));      // same codecs: joined
        Assert.All(Served(s), i => Assert.Equal(0x1000, i.PmtPid));
        AssertContinuous(Served(s));
    }

    [Fact]
    public void With_No_Game_It_Serves_The_Slate_In_Real_Time_And_Splices_Games_On_And_Off_It()
    {
        var slate = new RedZoneSlate(SegC);
        Assert.InRange(slate.Duration, 0.2, 0.5);
        var a = new OutputWindow();
        var windows = new Dictionary<string, OutputWindow> { ["a"] = a };
        var s = new RedZoneSession { Slate = slate };

        s.Target(null, null, "no games live", T0);
        Assert.Equal(2, s.Pump(T0, id => windows.GetValueOrDefault(id)));         // a segment ahead of real time
        Assert.True(s.OnSlate);
        Assert.Equal(0, s.Pump(T0.AddSeconds(slate.Duration * 0.5), id => windows.GetValueOrDefault(id)));
        Assert.Equal(1, s.Pump(T0.AddSeconds(slate.Duration * 1.01), id => windows.GetValueOrDefault(id)));
        Assert.Equal(4, s.Pump(T0.AddSeconds(slate.Duration * 5.01), id => windows.GetValueOrDefault(id)));
        Assert.Equal("slate", s.Switches[^1].How);

        // a game starts: onto it at its newest segment, spliced (H.264 + AAC like the slate)
        Feed(a, Chain(SegA, 4), T0.AddSeconds(3));
        s.Target("a", "A", "hottest", T0.AddSeconds(3));
        Assert.Equal(1, s.Pump(T0.AddSeconds(3), id => windows.GetValueOrDefault(id)));
        Assert.False(s.OnSlate);
        Assert.Equal("spliced", s.Switches[^1].How);

        // it ends: back to the slate
        s.Target(null, null, "no games live", T0.AddSeconds(4));
        Assert.True(s.Pump(T0.AddSeconds(4), id => windows.GetValueOrDefault(id)) >= 1);
        Assert.True(s.OnSlate);

        var served = Served(s);
        Assert.All(served, i => Assert.Equal(TsSplicer.Analyze(SegC).PmtPid, i.PmtPid)); // the first thing served set the layout
        Assert.All(s.Window.Listed(), p => Assert.False(p.Discontinuity));
        AssertContinuous(served);
    }

    [Fact]
    public void Without_A_Slate_Nothing_Is_Served_Until_A_Game_Is_On()
    {
        var s = new RedZoneSession();
        s.Target(null, null, "no games live", T0);
        Assert.Equal(0, s.Pump(T0, _ => null));
        Assert.Equal(0, s.Window.Count);
        Assert.Equal(TimeSpan.Zero, s.Starved(T0.AddMinutes(5)));
    }
}

public class RedZoneChannelTests
{
    private sealed class NoHttp : IHttpClientFactory
    {
        public HttpClient CreateClient(string name) => throw new InvalidOperationException("no network in this test");
    }

    [Fact]
    public async Task The_RedZone_Channel_Is_Listed_First_After_Every_Refresh_And_Listeners_Never_See_It()
    {
        var source = new SourceDefinition
        {
            Id = Guid.NewGuid(), Name = "Dev", Kind = SourceKind.Direct, Enabled = true,
            Streams = new List<DirectStream>
            {
                new() { Name = "Kansas City Chiefs Miami Dolphins", Url = "http://bed.example/a.m3u8" },
                new() { Name = "Tampa Bay Rays Philadelphia Phillies", Url = "http://bed.example/b.m3u8" }
            }
        };
        var m = new SourceManager(new NoHttp(), NullLogger<SourceManager>.Instance)
        {
            DefinitionsOverride = () => new[] { source },
            GamesOverride = () => new List<GameInfo>()
        };
        var heard = new List<IReadOnlyList<SourceChannel>>();
        m.Refreshed += heard.Add;

        RedZoneService.Install(m, () => "http://127.0.0.1:8096/JellyTV/Live/redzone.m3u8?s=sig");
        Assert.Empty(m.GetChannels());                                             // no sources scanned yet: nothing to cut between

        await m.RefreshAsync(CancellationToken.None);
        await m.RefreshAsync(CancellationToken.None);

        var channels = m.GetChannels();
        Assert.Equal(3, channels.Count);
        Assert.Equal(RedZoneService.ChannelId, channels[0].Id);
        Assert.Single(channels, c => c.IsSynthetic);
        var rz = m.GetChannel("redzone")!;
        Assert.Equal(("Tally Pulse", "Pulse", "redzone"), (rz.Name, rz.Group, rz.Kind));
        Assert.Equal("http://127.0.0.1:8096/JellyTV/Live/redzone.m3u8?s=sig", rz.StreamUrl);
        Assert.Equal("redzone", m.ResolveId("redzone"));
        Assert.Equal(2, heard.Count);
        Assert.All(heard, list => Assert.DoesNotContain(list, c => c.IsSynthetic));

        // switched off: gone at once, and back when switched on again
        m.Synthetic = _ => Array.Empty<SourceChannel>();
        m.Reinject();
        Assert.Null(m.GetChannel("redzone"));
        Assert.Equal(2, m.GetChannels().Count);
        RedZoneService.Install(m, () => "http://127.0.0.1:8096/JellyTV/Live/redzone.m3u8?s=sig");
        Assert.NotNull(m.GetChannel("redzone"));
        Assert.Equal(3, m.GetChannels().Count);
    }

    [Fact]
    public async Task Cards_Drawn_Under_The_Old_RedZone_Name_Draw_The_Pulse_Channel()
    {
        var source = new SourceDefinition
        {
            Id = Guid.NewGuid(), Name = "Dev", Kind = SourceKind.Direct, Enabled = true,
            Streams = new List<DirectStream> { new() { Name = "Kansas City Chiefs Miami Dolphins", Url = "http://bed.example/a.m3u8" } }
        };
        var m = new SourceManager(new NoHttp(), NullLogger<SourceManager>.Instance)
        {
            DefinitionsOverride = () => new[] { source },
            GamesOverride = () => new List<GameInfo>()
        };
        RedZoneService.Install(m, () => "http://127.0.0.1:8096/JellyTV/Live/redzone.m3u8?s=sig");
        await m.RefreshAsync(CancellationToken.None);

        // Jellyfin keeps the card address it was given before the rename (the key of "Tally RedZone")
        var old = Jellyfin.Plugin.Tally.Services.CardArtService.StableKey("Tally RedZone");
        Assert.Equal("redzone", Jellyfin.Plugin.Tally.Api.CardController.FindChannel(m, old)?.Id);
        var now = Jellyfin.Plugin.Tally.Services.CardArtService.StableKey("Tally Pulse");
        Assert.Equal("redzone", Jellyfin.Plugin.Tally.Api.CardController.FindChannel(m, now)?.Id);
        Assert.Null(Jellyfin.Plugin.Tally.Api.CardController.FindChannel(m, "0123456789abcdef"));
    }
}
