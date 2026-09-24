using System.Text.Json;
using Jellyfin.Plugin.Tally.Dvr;
using Jellyfin.Plugin.Tally.Live;
using Jellyfin.Plugin.Tally.Scores;
using Xunit;

namespace Tally.Tests;

public class DvrPolicyTests
{
    private static readonly DateTimeOffset Start = new(2026, 9, 24, 23, 5, 0, TimeSpan.Zero);
    private static readonly DvrSettings Settings = new();

    private static RecordingJob Job(string state = JobState.Scheduled) => new()
    {
        State = state,
        Game = new GameSnapshot { Id = "1", LeaguePath = "baseball/mlb", Start = Start }
    };

    private static GameStatus Pre => new("pre", Start, false);

    private static GameStatus Live => new("in", Start, false);

    private static GameStatus Final => new("post", Start, false);

    private static GameStatus Postponed => new("post", Start, true);

    [Fact]
    public void An_Upcoming_Game_Stays_Scheduled_Until_Fifteen_Minutes_Before_The_Start()
    {
        var d = DvrPolicy.Evaluate(Job(), Pre, haveChannel: true, running: 0, Settings, Start.AddMinutes(-16));
        Assert.Equal(DvrAction.None, d.Action);
        Assert.Equal(JobState.Scheduled, d.State);

        d = DvrPolicy.Evaluate(Job(), Pre, haveChannel: true, running: 0, Settings, Start.AddMinutes(-15));
        Assert.Equal(DvrAction.StartRecording, d.Action);
    }

    [Fact]
    public void A_Game_That_Goes_Live_Early_Starts_At_Once_When_A_Channel_Carries_It()
    {
        var d = DvrPolicy.Evaluate(Job(), Live, haveChannel: true, running: 0, Settings, Start.AddMinutes(-40));
        Assert.Equal(DvrAction.StartRecording, d.Action);
        Assert.Equal(JobState.Recording, d.State);
    }

    [Fact]
    public void Without_A_Channel_It_Waits_Then_Fails_An_Hour_After_The_Start()
    {
        var d = DvrPolicy.Evaluate(Job(), Live, haveChannel: false, running: 0, Settings, Start.AddMinutes(59));
        Assert.Equal(DvrAction.None, d.Action);
        Assert.Equal(JobState.Waiting, d.State);
        Assert.NotNull(d.Reason);

        d = DvrPolicy.Evaluate(Job(JobState.Waiting), Live, haveChannel: false, running: 0, Settings, Start.AddMinutes(60));
        Assert.Equal(DvrAction.Fail, d.Action);
        Assert.Equal(JobState.Failed, d.State);
        Assert.Equal("No stream found for this game", d.Reason);
    }

    [Fact]
    public void A_Channel_Found_Late_Still_Starts_The_Recording()
    {
        var d = DvrPolicy.Evaluate(Job(JobState.Waiting), Live, haveChannel: true, running: 0, Settings, Start.AddMinutes(45));
        Assert.Equal(DvrAction.StartRecording, d.Action);
    }

    [Fact]
    public void The_Concurrency_Limit_Makes_A_Job_Wait_With_A_Reason()
    {
        var d = DvrPolicy.Evaluate(Job(), Live, haveChannel: true, running: 3, Settings, Start);
        Assert.Equal(DvrAction.None, d.Action);
        Assert.Equal(JobState.Waiting, d.State);
        Assert.Contains("3 recordings already running", d.Reason);

        d = DvrPolicy.Evaluate(Job(JobState.Waiting), Live, haveChannel: true, running: 2, Settings, Start);
        Assert.Equal(DvrAction.StartRecording, d.Action);
    }

    [Fact]
    public void A_Postponed_Game_Cancels_A_Job_That_Has_Not_Started()
    {
        var d = DvrPolicy.Evaluate(Job(), Postponed, haveChannel: true, running: 0, Settings, Start.AddHours(-2));
        Assert.Equal(DvrAction.Cancel, d.Action);
        Assert.Equal(JobState.Canceled, d.State);
    }

    [Fact]
    public void A_Game_That_Ended_Before_A_Stream_Appeared_Fails_With_No_Stream()
    {
        var d = DvrPolicy.Evaluate(Job(JobState.Waiting), Final, haveChannel: false, running: 0, Settings, Start.AddMinutes(30));
        Assert.Equal(DvrAction.Fail, d.Action);
        Assert.Equal(DvrPolicy.NoStream, d.Reason);
    }

    [Fact]
    public void A_Recording_Stops_After_The_Final_Plus_The_Post_Roll()
    {
        var job = Job(JobState.Recording);
        job.StartedAt = Start;
        job.FinalSeenAt = Start.AddHours(3);

        var d = DvrPolicy.Evaluate(job, Final, true, 1, Settings, Start.AddHours(3).AddMinutes(4));
        Assert.Equal(DvrAction.None, d.Action);

        d = DvrPolicy.Evaluate(job, Final, true, 1, Settings, Start.AddHours(3).AddMinutes(5));
        Assert.Equal(DvrAction.StopRecording, d.Action);
        Assert.Null(d.StopReason); // a normal end
    }

    [Fact]
    public void A_Recording_Stops_At_The_Maximum_Length()
    {
        var job = Job(JobState.Recording);
        job.StartedAt = Start;
        var d = DvrPolicy.Evaluate(job, Live, true, 1, Settings, Start.AddHours(6));
        Assert.Equal(DvrAction.StopRecording, d.Action);
        Assert.Equal("maxLength", d.StopReason);
        Assert.Equal("Stopped at the maximum length (6 h)", DvrPolicy.Describe(d.StopReason, Settings));
    }

    [Fact]
    public void A_Game_Postponed_Mid_Recording_Stops_And_Keeps_What_Was_Recorded()
    {
        var job = Job(JobState.Recording);
        job.StartedAt = Start;
        var d = DvrPolicy.Evaluate(job, Postponed, true, 1, Settings, Start.AddMinutes(30));
        Assert.Equal(DvrAction.StopRecording, d.Action);
        Assert.Equal("postponed", d.StopReason);
    }

    [Fact]
    public void Finished_And_Finishing_Jobs_Are_Left_Alone()
    {
        foreach (var state in new[] { JobState.Finishing, JobState.Done, JobState.Failed, JobState.Canceled })
        {
            var d = DvrPolicy.Evaluate(Job(state), Live, true, 0, Settings, Start);
            Assert.Equal(DvrAction.None, d.Action);
            Assert.Equal(state, d.State);
        }
    }

    [Fact]
    public void A_Game_That_Left_The_Board_Uses_The_Start_It_Was_Given()
    {
        var d = DvrPolicy.Evaluate(Job(), null, haveChannel: false, running: 0, Settings, Start.AddMinutes(61));
        Assert.Equal(DvrAction.Fail, d.Action);
    }
}

public class DvrSpaceTests
{
    private static readonly DateTimeOffset Start = new(2026, 9, 24, 23, 0, 0, TimeSpan.Zero);

    [Theory]
    [InlineData("baseball/mlb", 3)]
    [InlineData("football/nfl", 3.5)]
    [InlineData("football/college-football", 3.5)]
    [InlineData("basketball/nba", 2.5)]
    [InlineData("hockey/nhl", 2.5)]
    [InlineData("soccer/eng.1", 2)]
    [InlineData("soccer/usa.1", 2)]
    [InlineData("basketball/wnba", 3)]
    public void Typical_Game_Lengths_By_League(string league, double hours)
        => Assert.Equal(TimeSpan.FromHours(hours), DvrSpace.TypicalLength(league));

    [Fact]
    public void Time_Left_Counts_From_The_Earliest_Start_For_An_Upcoming_Game()
    {
        var postRoll = TimeSpan.FromMinutes(5);
        // two hours out: the recording would begin 15 minutes early and run the typical 3 h, plus the post-roll
        Assert.Equal(TimeSpan.FromMinutes(15 + 180 + 5), DvrSpace.TimeLeft("baseball/mlb", Start, "pre", Start.AddHours(-2), postRoll));
        // an hour into a live game
        Assert.Equal(TimeSpan.FromMinutes(120 + 5), DvrSpace.TimeLeft("baseball/mlb", Start, "in", Start.AddHours(1), postRoll));
        // extra innings: never less than ten minutes
        Assert.Equal(TimeSpan.FromMinutes(10 + 5), DvrSpace.TimeLeft("baseball/mlb", Start, "in", Start.AddHours(4), postRoll));
    }

    [Fact]
    public void The_Estimate_Is_Bitrate_Times_Time_Left()
    {
        // 6 Mbit/s for an hour = 2.7 GB
        Assert.Equal(2_700_000_000L, DvrSpace.Estimate(6_000_000, TimeSpan.FromHours(1)));
    }

    [Fact]
    public void Only_Start_If_Free_Space_Minus_The_Estimate_Leaves_The_Reserve()
    {
        const long gb = DvrSettings.Gb;
        Assert.True(DvrSpace.Fits(20 * gb, 10 * gb, 10 * gb));
        Assert.False(DvrSpace.Fits(20 * gb - 1, 10 * gb, 10 * gb));
        Assert.Equal("Not enough space: needs ~9 GB, 4 GB free", DvrSpace.NotEnough(9 * gb, 4 * gb, 10 * gb));
        Assert.Equal("Not enough space: needs ~9 GB, 12 GB free (10 GB kept free)", DvrSpace.NotEnough(9 * gb, 12 * gb, 10 * gb));
        Assert.Equal("Not enough space: needs ~2.5 GB, 350 MB free", DvrSpace.NotEnough((long)(2.5 * gb), 350L * 1024 * 1024, 0));
    }

    [Fact]
    public void A_Recording_Stops_Below_The_Reserve_And_Remuxes_Only_With_Room_To_Spare()
    {
        const long gb = DvrSettings.Gb;
        Assert.True(DvrSpace.BelowReserve(10 * gb - 1, 10 * gb));
        Assert.False(DvrSpace.BelowReserve(10 * gb, 10 * gb));
        Assert.True(DvrSpace.CanRemux(20 * gb, 8 * gb, 10 * gb));   // 11.6 GB left after a copy: above half the reserve
        Assert.False(DvrSpace.CanRemux(9 * gb, 8 * gb, 10 * gb));   // a disk-full stop: join in place instead
    }
}

public class DvrRetentionTests
{
    private static readonly DateTimeOffset Now = new(2026, 9, 24, 12, 0, 0, TimeSpan.Zero);

    private static RecordingJob Done(Guid rule, int daysAgo, string state = JobState.Done) => new()
    {
        RuleId = rule,
        State = state,
        FilePath = state == JobState.Done ? $"/rec/{daysAgo}.mp4" : null,
        EndedAt = Now.AddDays(-daysAgo),
        Game = new GameSnapshot { Id = daysAgo.ToString(), Start = Now.AddDays(-daysAgo) }
    };

    [Fact]
    public void A_Team_Rule_Keeps_Its_Newest_Games()
    {
        var rule = new RecordingRule { Kind = RecordingRule.TeamKind, TeamId = "5", KeepLast = 2 };
        var jobs = new[] { Done(rule.Id, 1), Done(rule.Id, 3), Done(rule.Id, 2), Done(rule.Id, 7), Done(Guid.NewGuid(), 9) };
        var doomed = DvrRetention.Select(jobs, new[] { rule }, 0, Now, _ => false);
        Assert.Equal(new[] { "3", "7" }, doomed.Select(j => j.Game.Id).OrderBy(x => x));
    }

    [Fact]
    public void Keep_All_And_Never_Delete_Are_The_Defaults()
    {
        var rule = new RecordingRule { Kind = RecordingRule.TeamKind, TeamId = "5" };
        var jobs = Enumerable.Range(1, 30).Select(d => Done(rule.Id, d)).ToList();
        Assert.Empty(DvrRetention.Select(jobs, new[] { rule }, 0, Now, _ => false));
    }

    [Fact]
    public void Old_Recordings_Go_After_N_Days_But_Never_One_Being_Watched_Or_Not_Finished()
    {
        var any = Guid.NewGuid();
        var watched = Done(any, 40);
        var jobs = new[] { Done(any, 5), Done(any, 31), watched, Done(any, 50, JobState.Recording), Done(any, 60, JobState.Failed) };
        var doomed = DvrRetention.Select(jobs, Array.Empty<RecordingRule>(), 30, Now, j => ReferenceEquals(j, watched));
        Assert.Equal(new[] { "31" }, doomed.Select(j => j.Game.Id));
    }
}

public class DvrMetadataTests
{
    private static GameInfo PlayedGame() => new()
    {
        Id = "401",
        League = "MLB",
        LeaguePath = "baseball/mlb",
        Sport = "baseball",
        State = "post",
        Detail = "Final",
        Start = new DateTimeOffset(2026, 9, 24, 17, 5, 0, TimeSpan.Zero),
        Away = new GameTeam { Id = "11", Abbr = "ROT", Name = "Riverton Otters", ShortName = "Otters", Score = 71, Winner = true, Periods = { 13, 58 } },
        Home = new GameTeam { Id = "12", Abbr = "LKH", Name = "Lakeside Herons", ShortName = "Herons", Score = 64, Periods = { 20, 44 } },
        LastPlay = "Otters win 71-64",
        Broadcasts = { "FS1" }
    };

    [Fact]
    public void The_Nfo_Has_Title_Date_League_And_Teams_And_No_Score()
    {
        var nfo = DvrNfo.Build(GameSnapshot.From(PlayedGame()), TimeZoneInfo.Utc);
        Assert.Contains("<title>Riverton Otters at Lakeside Herons</title>", nfo);
        Assert.Contains("<premiered>2026-09-24</premiered>", nfo);
        Assert.Contains("<genre>MLB</genre>", nfo);
        Assert.Contains("<tag>Riverton Otters</tag>", nfo);
        Assert.Contains("<tag>Lakeside Herons</tag>", nfo);
        foreach (var leak in new[] { "71", "64", "58", "44", "win", "Final" })
        {
            Assert.DoesNotContain(leak, nfo);
        }
    }

    [Fact]
    public void Nothing_The_Dvr_Keeps_About_A_Game_Can_Hold_A_Score()
    {
        foreach (var type in new[] { typeof(GameSnapshot), typeof(TeamSnapshot), typeof(RecordingJob) })
        {
            Assert.DoesNotContain(type.GetProperties(), p => p.Name.Contains("Score", StringComparison.OrdinalIgnoreCase)
                                                            || p.Name.Contains("Winner", StringComparison.OrdinalIgnoreCase)
                                                            || p.Name.Contains("Period", StringComparison.OrdinalIgnoreCase));
        }

        var json = JsonSerializer.Serialize(new RecordingJob { Id = Guid.Empty, Game = GameSnapshot.From(PlayedGame()) }); // no random ids to trip over
        Assert.DoesNotContain("71", json);
        Assert.DoesNotContain("64", json);
        Assert.DoesNotContain("win", json, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void The_Art_Game_Is_Drawn_As_Not_Started()
    {
        var g = GameSnapshot.From(PlayedGame()).ToArtGame();
        Assert.Equal("pre", g.State);
        Assert.Null(g.Away.Score);
        Assert.Null(g.Home.Score);
        Assert.Empty(g.Away.Periods);
    }

    [Fact]
    public void File_Names_Follow_Away_At_Home_And_The_Date_And_Are_Valid_Everywhere()
    {
        var g = GameSnapshot.From(PlayedGame());
        Assert.Equal("Riverton Otters at Lakeside Herons - 2026-09-24", DvrNaming.BaseName(g, TimeZoneInfo.Utc));
        Assert.Equal("MLB", DvrNaming.LeagueFolder(g));
        Assert.Equal("A B at C D", DvrNaming.Clean("A/B at C:D."));
        var taken = new HashSet<string> { System.IO.Path.Combine("/r", "X"), System.IO.Path.Combine("/r", "X (2)") };
        Assert.Equal("X (3)", DvrNaming.Unique("/r", "X", taken.Contains));
    }
}

public class RecordingSplicerTests
{
    private static readonly byte[] SegA = LiveFixtures.Bytes("a-1080p60-head.ts");
    private static readonly byte[] SegB = LiveFixtures.Bytes("b-720p30-head.ts");

    [Fact]
    public void A_Segment_From_Another_Timeline_Continues_What_Was_Written()
    {
        var splicer = new RecordingSplicer();
        var first = splicer.Process(SegA, false, out var s1);
        Assert.True(s1);
        Assert.Same(SegA, first); // the first segment is written as it came

        var second = splicer.Process(SegB, true, out var s2);
        Assert.True(s2);
        var a = TsSplicer.Analyze(first);
        var b = TsSplicer.Analyze(second);
        Assert.Equal(a.Streams.Select(x => x.Pid), b.Streams.Select(x => x.Pid)); // mapped onto the first layout
        var gap = TsSplicer.Mod(b.StartDts!.Value - a.EndDts!.Value);
        Assert.True(gap < 90000 / 10, $"gap {gap}");
    }

    [Fact]
    public void After_A_Restart_The_Next_Segment_Is_Joined_To_The_Last_One_On_Disk()
    {
        var splicer = new RecordingSplicer();
        splicer.Resume(SegA, SegA);
        var next = splicer.Process(SegA, false, out var spliced); // the same source again, far back in time
        Assert.True(spliced);
        var last = TsSplicer.Analyze(SegA);
        var o = TsSplicer.Analyze(next);
        var gap = TsSplicer.Mod(o.StartDts!.Value - last.EndDts!.Value);
        Assert.True(gap < 90000 / 10, $"gap {gap}");
    }

    [Fact]
    public void A_Hole_Left_By_A_Dropped_Segment_Is_Closed()
    {
        var a = TsSplicer.Analyze(SegA);
        var length = a.EndDts!.Value - a.StartDts!.Value;
        // the same source four seconds later than where it left off: one segment went missing
        var later = TsSplicer.Rewrite(SegA, TsSplicer.WithContinuity(
            new SpliceMap { Offset = TsSplicer.Mod(length + (4 * 90000)), PidMap = { [0x100] = 0x100, [0x101] = 0x101 }, InPmtPid = 0x1000, OutPmtPid = 0x1000 },
            a, a.LastCc));

        var splicer = new RecordingSplicer();
        splicer.Process(SegA, false, out _);
        var next = splicer.Process(later, false, out var spliced);
        Assert.True(spliced);
        var gap = TsSplicer.Mod(TsSplicer.Analyze(next).StartDts!.Value - a.EndDts!.Value);
        Assert.True(gap < 90000 / 10, $"gap {gap}");

        // and the next one, continuous with the source, stays continuous with what was written
        var after = TsSplicer.Rewrite(SegA, TsSplicer.WithContinuity(
            new SpliceMap { Offset = TsSplicer.Mod((2 * length) + (4 * 90000)), PidMap = { [0x100] = 0x100, [0x101] = 0x101 }, InPmtPid = 0x1000, OutPmtPid = 0x1000 },
            a, TsSplicer.Analyze(later).LastCc));
        var third = splicer.Process(after, false, out _);
        var gap2 = TsSplicer.Mod(TsSplicer.Analyze(third).StartDts!.Value - TsSplicer.Analyze(next).EndDts!.Value);
        Assert.True(gap2 < 90000 / 10, $"gap {gap2}");
    }

    [Fact]
    public void A_Jump_Without_A_Flag_Is_Joined_Too()
    {
        var splicer = new RecordingSplicer();
        splicer.Process(SegA, false, out _);
        var next = splicer.Process(SegB, false, out var spliced); // B's clock is nowhere near A's
        Assert.True(spliced);
        var gap = TsSplicer.Mod(TsSplicer.Analyze(next).StartDts!.Value - TsSplicer.Analyze(SegA).EndDts!.Value);
        Assert.True(gap < 90000 / 10, $"gap {gap}");
    }
}

public class WorkFolderTests
{
    [Fact]
    public void Segments_Survive_A_Reopen_And_Make_An_Event_Playlist()
    {
        var dir = Path.Combine(Path.GetTempPath(), "tally-dvr-test-" + Guid.NewGuid().ToString("N"), WorkFolder.ParentName, "job");
        try
        {
            var w = WorkFolder.Open(dir);
            w.Append(new byte[] { 1, 2, 3 }, 4.004, false);
            w.Append(new byte[] { 4, 5 }, 3.5, true);
            File.AppendAllText(Path.Combine(dir, "segments.txt"), "3\t4.0"); // a line cut short by a crash

            var again = WorkFolder.Open(dir);
            Assert.Equal(2, again.Segments.Count);
            Assert.Equal(5, again.Bytes);
            Assert.True(File.Exists(Path.Combine(Path.GetDirectoryName(dir)!, ".ignore")));

            var pl = again.Playlist(s => s.Number + ".ts", ended: false);
            Assert.Contains("#EXT-X-PLAYLIST-TYPE:EVENT", pl);
            Assert.Contains("#EXT-X-TARGETDURATION:5", pl);
            Assert.Contains("#EXTINF:4.004,\n1.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:3.500,\n2.ts", pl);
            Assert.DoesNotContain("ENDLIST", pl);
            Assert.EndsWith("#EXT-X-ENDLIST\n", again.Playlist(s => s.Number + ".ts", ended: true));
        }
        finally
        {
            Directory.Delete(Path.GetDirectoryName(Path.GetDirectoryName(dir)!)!, true);
        }
    }

    [Fact]
    public void An_In_Place_Join_Picks_Up_Where_It_Stopped()
    {
        var root = Path.Combine(Path.GetTempPath(), "tally-dvr-test-" + Guid.NewGuid().ToString("N"));
        var dir = Path.Combine(root, WorkFolder.ParentName, "job");
        try
        {
            var w = WorkFolder.Open(dir);
            for (byte i = 1; i <= 4; i++)
            {
                w.Append(new[] { i, i }, 4, false);
            }

            var output = Path.Combine(root, "out.ts");
            // a join that got through two segments, then wrote a stray byte before a crash
            File.WriteAllBytes(output, new byte[] { 1, 1, 2, 2, 9 });
            File.Delete(Path.Combine(dir, "000001.ts"));
            File.Delete(Path.Combine(dir, "000002.ts"));
            File.WriteAllText(Path.Combine(dir, "concat.txt"), "2\t4");

            RecordingFinisher.Concat(WorkFolder.Open(dir), output, inPlace: true, CancellationToken.None);
            Assert.Equal(new byte[] { 1, 1, 2, 2, 3, 3, 4, 4 }, File.ReadAllBytes(output));
            Assert.False(File.Exists(Path.Combine(dir, "000003.ts")));
        }
        finally
        {
            Directory.Delete(root, true);
        }
    }
}

public class DvrDiskSpaceTests
{
    [Fact]
    public void Free_Space_Is_Read_For_A_Folder_That_Does_Not_Exist_Yet()
    {
        var info = DiskSpace.For(Path.Combine(Path.GetTempPath(), "tally-no-such-folder-" + Guid.NewGuid().ToString("N"), "deeper"));
        Assert.NotNull(info);
        Assert.True(info!.FreeBytes > 0 && info.TotalBytes >= info.FreeBytes);
    }
}
