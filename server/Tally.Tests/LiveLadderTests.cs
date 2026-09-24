using Jellyfin.Plugin.Tally.Live;
using Jellyfin.Plugin.Tally.Models;
using Jellyfin.Plugin.Tally.Scores;
using Jellyfin.Plugin.Tally.Sources;
using Xunit;

namespace Tally.Tests;

internal static class LiveFixtures
{
    public static byte[] Bytes(string name) => File.ReadAllBytes(Path(name));

    public static string Text(string name) => File.ReadAllText(Path(name));

    private static string Path(string name)
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir != null)
        {
            foreach (var rel in new[] { System.IO.Path.Combine("Fixtures", "live", name), System.IO.Path.Combine("Tally.Tests", "Fixtures", "live", name) })
            {
                var path = System.IO.Path.Combine(dir.FullName, rel);
                if (File.Exists(path))
                {
                    return path;
                }
            }

            dir = dir.Parent;
        }

        throw new FileNotFoundException(name);
    }
}

public class HlsParserTests
{
    [Fact]
    public void Reads_A_Masters_Renditions_And_Resolves_Their_Addresses()
    {
        var master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=5500000,AVERAGE-BANDWIDTH=5200000,RESOLUTION=1920x1080,FRAME-RATE=30.000,CODECS="avc1.640028,mp4a.40.2"
            hi/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1400000,RESOLUTION=960x540,FRAME-RATE=59.940,CODECS="avc1.64001f,mp4a.40.2"
            https://cdn.source.example/lo/index.m3u8?token=abc
            """;
        var v = HlsParser.ParseMaster(master, new Uri("https://source.example/C/master.m3u8"));

        Assert.Equal(2, v.Count);
        Assert.Equal("https://source.example/C/hi/index.m3u8", v[0].Uri.ToString());
        Assert.Equal((1920, 1080, 30.0, 5_200_000L), (v[0].Width!.Value, v[0].Height!.Value, v[0].FrameRate!.Value, v[0].AverageBandwidth!.Value));
        Assert.Equal("avc1.640028,mp4a.40.2", v[0].Codecs);
        Assert.Equal(59.94, v[1].FrameRate);
        Assert.Equal("https://cdn.source.example/lo/index.m3u8?token=abc", v[1].Uri.ToString());
        Assert.True(HlsParser.IsMaster(master));
    }

    [Fact]
    public void Reads_A_Captured_Live_Media_Playlist()
    {
        var pl = HlsParser.ParseMedia(LiveFixtures.Text("a-media.m3u8"), new Uri("http://source.example/A/index.m3u8"));

        Assert.Equal(4, pl.TargetDuration);
        Assert.Equal(13, pl.MediaSequence);
        Assert.Equal(6, pl.Segments.Count);
        Assert.Equal(19, pl.NextSequence);
        Assert.All(pl.Segments, s => Assert.Equal(4.0, s.Duration));
        Assert.Equal("http://source.example/A/seg_18.ts", pl.Segments[^1].Uri.ToString());
        Assert.Equal(18, pl.Segments[^1].Sequence);
        Assert.False(pl.EndList);
    }

    [Fact]
    public void Reads_A_Web_Page_Streams_Master_And_Media_Playlist()
    {
        // captured from a real web-page source (hosts and tokens replaced): one 720p rendition declared at 4 Mbps
        // with no FRAME-RATE — the frame rate has to come from ffprobe — and 5 s segments disguised as .txt files
        var master = HlsParser.ParseMaster(LiveFixtures.Text("web-master.m3u8"), new Uri("https://playlists.source.example/playlist/1000/load-playlist"));
        var v = Assert.Single(master);
        Assert.Equal((1280, 720, 4_000_000L, 3_200_000L), (v.Width!.Value, v.Height!.Value, v.Bandwidth, v.AverageBandwidth!.Value));
        Assert.Null(v.FrameRate);

        var media = HlsParser.ParseMedia(LiveFixtures.Text("web-media.m3u8"), v.Uri);
        Assert.Equal(5, media.TargetDuration);
        Assert.Equal(800, media.MediaSequence);
        Assert.True(media.Segments.Count >= 3);
        Assert.EndsWith(".txt", media.Segments[0].Uri.AbsolutePath, StringComparison.Ordinal);
        Assert.Null(media.Segments[0].KeyMethod);
    }

    [Fact]
    public void Tracks_Keys_Discontinuities_And_Maps()
    {
        var text = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:100
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0x0000000000000000000000000000002A
            #EXTINF:6.006,
            a.ts
            #EXT-X-DISCONTINUITY
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:5.5,
            b.ts
            #EXT-X-ENDLIST
            """;
        var pl = HlsParser.ParseMedia(text, new Uri("https://source.example/x/p.m3u8"));

        Assert.Equal("AES-128", pl.Segments[0].KeyMethod);
        Assert.Equal("https://source.example/x/key.bin", pl.Segments[0].KeyUri!.ToString());
        Assert.False(pl.Segments[0].Discontinuity);
        Assert.True(pl.Segments[1].Discontinuity);
        Assert.Null(pl.Segments[1].KeyMethod);
        Assert.Equal(101, pl.Segments[1].Sequence);
        Assert.True(pl.EndList);
    }
}

public class ChannelGrouperTests
{
    private static SourceChannel Ch(string source, string name, string url, string key = "", string tvg = "")
        => new() { SourceId = source, Name = name, StreamUrl = url, Id = SourceChannel.MakeId(source, url), GroupKey = key, TvgId = tvg };

    private static List<SourceChannel> Assigned(params SourceChannel[] c)
    {
        var list = c.ToList();
        ChannelIdentity.Assign(list);
        return list;
    }

    [Fact]
    public void A_Pages_Several_Links_For_One_Game_Become_One_Channel_That_Keeps_Its_Id()
    {
        // what WebSourceAdapter produces for three links of one event: same key, names numbered by the adapter
        var key = "web:" + ChannelGrouper.KeyFor("Kansas City Chiefs Buffalo Bills");
        var before = Assigned(Ch("w", "Kansas City Chiefs Buffalo Bills", "https://a.example/1.m3u8"));
        var scan = Assigned(
            Ch("w", "Kansas City Chiefs Buffalo Bills", "https://a.example/1.m3u8", key),
            Ch("w", "Kansas City Chiefs Buffalo Bills 2", "https://b.example/2.m3u8", key),
            Ch("w", "Kansas City Chiefs Buffalo Bills 3", "https://c.example/3.m3u8", key),
            Ch("w", "New York Jets Miami Dolphins", "https://a.example/9.m3u8", "web:" + ChannelGrouper.KeyFor("New York Jets Miami Dolphins")));

        var r = ChannelGrouper.Group(scan, games: null);

        Assert.Equal(2, r.Channels.Count);
        var game = r.Channels.Single(c => c.Name.StartsWith("Kansas", StringComparison.Ordinal));
        Assert.Equal(before[0].Id, game.Id);
        Assert.Equal(new[] { "https://a.example/1.m3u8", "https://b.example/2.m3u8", "https://c.example/3.m3u8" }, game.Candidates.Select(c => c.Url));
        Assert.Equal(scan[1].Id, game.MergedIds[0]);
        Assert.Equal(game.Id, r.Aliases[scan[2].Id]);
        Assert.Single(r.Channels.Single(c => c.Name.StartsWith("New York", StringComparison.Ordinal)).Candidates);
        Assert.Empty(scan[0].Candidates); // inputs untouched: the manager re-uses them after a failed scan
    }

    [Fact]
    public void M3u_Duplicates_Merge_By_Tvg_Id_Or_By_Name_Without_Quality_Words()
    {
        var m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-id="espn.us",ESPN
            http://iptv.example/1
            #EXTINF:-1,ESPN FHD
            http://iptv.example/2
            #EXTINF:-1,ESPN (Backup) 60FPS
            http://iptv.example/3
            #EXTINF:-1,ESPN 2
            http://iptv.example/4
            #EXTINF:-1 tvg-id="fs1.us",Fox Sports 1
            http://iptv.example/5
            #EXTINF:-1 tvg-id="fs1.us",FS1 | Link 2
            http://iptv.example/6
            """;
        var channels = M3uParser.Parse(m3u, "src", "IPTV", new Dictionary<string, string>());
        ChannelIdentity.Assign(channels);

        var r = ChannelGrouper.Group(channels, games: null);

        Assert.Equal(3, r.Channels.Count);
        var espn = r.Channels.Single(c => c.Name == "ESPN");
        Assert.Equal(3, espn.Candidates.Count);
        Assert.Equal("espn.us", espn.TvgId);
        Assert.Single(r.Channels.Single(c => c.Name == "ESPN 2").Candidates);
        Assert.Equal(2, r.Channels.Single(c => c.Name == "Fox Sports 1").Candidates.Count);
    }

    [Fact]
    public void Channels_Matched_To_One_Game_By_Teams_Merge_Only_Within_A_Source()
    {
        var game = new GameInfo
        {
            Id = "401", State = "in",
            Home = new GameTeam { Name = "Boston Red Sox", ShortName = "Red Sox", Nickname = "Red Sox", Location = "Boston", Abbr = "BOS" },
            Away = new GameTeam { Name = "New York Yankees", ShortName = "Yankees", Nickname = "Yankees", Location = "New York", Abbr = "NYY" }
        };
        var scan = Assigned(
            Ch("w", "New York Yankees Boston Red Sox", "https://a.example/1.m3u8", "web:x"),
            Ch("w", "Yankees vs Red Sox", "https://b.example/2.m3u8", "web:y"),
            Ch("other", "NYY @ BOS", "https://c.example/3.m3u8", "web:z"));
        var matched = new Dictionary<string, string>();

        var r = ChannelGrouper.Group(scan, new List<GameInfo> { game }, teamMatchesOut: matched);

        Assert.Equal(2, r.Channels.Count);
        Assert.Equal(2, r.Channels.Single(c => c.SourceId == "w").Candidates.Count);
        Assert.Equal("401", matched[scan[1].Id]);

        // after the game leaves the scoreboard the remembered match keeps them together
        var later = ChannelGrouper.Group(scan, games: null, stickyTeams: matched);
        Assert.Equal(2, later.Channels.Count);
    }

    [Fact]
    public void Generic_Names_Are_Never_Merged_And_A_Previous_Representative_Keeps_The_Id()
    {
        Assert.Equal(string.Empty, ChannelGrouper.KeyFor("Stream"));
        Assert.Equal(string.Empty, ChannelGrouper.KeyFor("Live HD"));

        var scan = Assigned(
            Ch("m", "Sports Net", "http://iptv.example/1", "name:" + ChannelGrouper.KeyFor("Sports Net")),
            Ch("m", "Sports Net HD", "http://iptv.example/2", "name:" + ChannelGrouper.KeyFor("Sports Net HD")));
        var first = ChannelGrouper.Group(scan, null);
        Assert.Equal(scan[0].Id, first.Channels[0].Id); // the plain name represents the group

        var sticky = ChannelGrouper.Group(scan, null, previousRepresentatives: new HashSet<string> { scan[1].Id });
        Assert.Equal(scan[1].Id, sticky.Channels[0].Id);
    }
}

public class LadderRankingTests
{
    private static Tier T(int cand, int? h, double? fps, long bw) => new()
    {
        Candidate = cand, CandidateKey = "c" + cand, Variant = 0, MediaUri = new Uri("http://x.example/" + cand), Height = h, FrameRate = fps, Bandwidth = bw
    };

    [Fact]
    public void Frame_Rate_Class_Beats_Height_Which_Beats_Bitrate()
    {
        var ranked = LadderRanking.Rank(new[]
        {
            T(0, 1080, 30, 6_000_000), T(1, 720, 60, 4_000_000), T(2, 1080, 60, 8_000_000), T(3, 720, 60, 5_000_000), T(4, 540, 30, 1_200_000)
        });

        Assert.Equal(new[] { 2, 3, 1, 0, 4 }, ranked.Select(t => t.Candidate));
        Assert.Equal("1080p60", ranked[0].Label);
        Assert.Equal("720p60", LadderRanking.Label(720, 59.94));
        Assert.Equal("720p", LadderRanking.Label(720, null));
    }

    [Fact]
    public void Healthy_Means_Fast_Enough_Fresh_And_Recent()
    {
        var now = DateTimeOffset.UtcNow;
        var t = T(0, 1080, 60, 8_000_000);
        CandidateProbe P(double mbps, bool fresh = true, int ageSec = 10, bool ok = true)
            => new() { At = now.AddSeconds(-ageSec), Ok = ok, Fresh = fresh, Throughput = mbps * 1e6, Tiers = { t } };

        Assert.True(LadderRanking.Healthy(t, P(12), now, TimeSpan.FromMinutes(5)));
        Assert.False(LadderRanking.Healthy(t, P(11.9), now, TimeSpan.FromMinutes(5)));
        Assert.False(LadderRanking.Healthy(t, P(50, fresh: false), now, TimeSpan.FromMinutes(5)));
        Assert.False(LadderRanking.Healthy(t, P(50, ageSec: 600), now, TimeSpan.FromMinutes(5)));
        Assert.False(LadderRanking.Healthy(t, P(50, ok: false), now, TimeSpan.FromMinutes(5)));
        t.MeasuredBitrate = 7_000_000; // a real segment beats the declared number
        Assert.True(LadderRanking.Healthy(t, P(10.5), now, TimeSpan.FromMinutes(5)));
    }
}

public class SwitchPolicyTests
{
    private static readonly DateTimeOffset T0 = new(2026, 9, 23, 20, 0, 0, TimeSpan.Zero);

    private static Tier T(string cand, int variant, int h, double fps, long bw) => new()
    {
        Candidate = cand[0] - 'A', CandidateKey = cand, Variant = variant, MediaUri = new Uri($"http://{cand}.example/{variant}"), Height = h, FrameRate = fps, Bandwidth = bw
    };

    private static readonly Tier A = T("A", 0, 1080, 60, 8_000_000);
    private static readonly Tier B = T("B", 0, 720, 30, 3_000_000);
    private static readonly Tier CHi = T("C", 0, 1080, 30, 5_000_000);
    private static readonly Tier CLo = T("C", 1, 540, 30, 1_200_000);
    private static readonly List<Tier> Ladder = LadderRanking.Rank(new[] { B, CLo, A, CHi });

    private sealed class Probes
    {
        public Dictionary<string, CandidateProbe> Map { get; } = new();

        public void Set(string cand, double mbps, DateTimeOffset at, bool ok = true)
            => Map[cand] = new CandidateProbe { At = at, Ok = ok, Fresh = true, Throughput = mbps * 1e6 };

        public CandidateProbe? Get(string k) => Map.TryGetValue(k, out var p) ? p : null;
    }

    private static Probes AllHealthy(DateTimeOffset at)
    {
        var p = new Probes();
        p.Set("A", 40, at);
        p.Set("B", 40, at);
        p.Set("C", 40, at);
        return p;
    }

    /// <summary>Plays <paramref name="n"/> segments of 4 s, each downloaded in <paramref name="seconds"/>.</summary>
    private static DateTimeOffset Play(SwitchPolicy p, DateTimeOffset t, int n, double seconds, long bytes = 4_000_000)
    {
        for (var i = 0; i < n; i++)
        {
            t = t.AddSeconds(4);
            p.OnNewSegmentListed(t);
            p.OnSegment(t, seconds, 4, bytes);
        }

        return t;
    }

    [Fact]
    public void Ladder_Order_Is_60fps_First()
        => Assert.Equal(new[] { A, CHi, B, CLo }, Ladder);

    [Fact]
    public void Starts_On_The_Best_Healthy_Rung()
    {
        var probes = AllHealthy(T0);
        Assert.Same(A, SwitchPolicy.Initial(Ladder, probes.Get, T0, TimeSpan.FromMinutes(45)));
        probes.Set("A", 9, T0); // 9 Mbps for an 8 Mbps stream: not 1.5×
        Assert.Same(CHi, SwitchPolicy.Initial(Ladder, probes.Get, T0, TimeSpan.FromMinutes(45)));
    }

    [Fact]
    public void Two_Slow_Segments_Step_Down_One_Does_Not()
    {
        var probes = AllHealthy(T0);
        var p = new SwitchPolicy(A, T0) { TargetDuration = 4 };
        var t = Play(p, T0, 20, 1.0);                     // 80 s of healthy play
        t = Play(p, t, 1, 3.5);                           // one slow segment (87%)
        t = Play(p, t, 1, 1.0);
        Assert.Null(p.Evaluate(t, Ladder, probes.Get));

        probes.Set("C", 40, t);
        t = Play(p, t, 2, 3.5, bytes: 4_000_000);          // two in a row at ~9 Mbps
        var d = p.Evaluate(t, Ladder, probes.Get);

        Assert.NotNull(d);
        Assert.Same(CHi, d!.Target);                     // the next healthy rung: another stream
        Assert.False(d.Hard);
        Assert.Contains("2 consecutive segments", d.Reason, StringComparison.Ordinal);
    }

    [Fact]
    public void Slowness_Waits_For_A_Minute_Since_The_Last_Switch()
    {
        var probes = AllHealthy(T0);
        var p = new SwitchPolicy(A, T0) { TargetDuration = 4 };
        var t = Play(p, T0, 2, 3.8);                      // struggling right away, 8 s after the switch
        Assert.Null(p.Evaluate(t, Ladder, probes.Get));
        t = Play(p, t, 13, 3.8);                          // 60 s later still struggling
        probes.Set("C", 40, t);
        Assert.NotNull(p.Evaluate(t, Ladder, probes.Get));
    }

    [Fact]
    public void A_Segment_That_Fails_Twice_Moves_On_At_Once_And_Backs_The_Stream_Off()
    {
        var probes = AllHealthy(T0);
        var p = new SwitchPolicy(A, T0) { TargetDuration = 4 };
        var t = Play(p, T0, 3, 1.0);
        p.OnSegmentFailed(t, "HTTP 503");
        var d = p.Evaluate(t, Ladder, probes.Get);

        Assert.True(d!.Hard);
        Assert.Same(CHi, d.Target);
        Assert.True(p.IsBackedOff("A", t.AddMinutes(1)));
        Assert.False(p.IsBackedOff("A", t.AddMinutes(2).AddSeconds(1)));

        // C dies too, 10 s later: A is still backed off, so B
        t = Play(p, t, 2, 1.0);
        p.OnSegmentFailed(t, "timed out");
        Assert.Same(B, p.Evaluate(t, Ladder, probes.Get)!.Target);
    }

    [Fact]
    public void A_Playlist_That_Stops_Advancing_For_Two_Target_Durations_Is_Left()
    {
        var probes = AllHealthy(T0);
        var p = new SwitchPolicy(A, T0) { TargetDuration = 4 };
        var t = Play(p, T0, 5, 1.0);
        Assert.Null(p.Evaluate(t.AddSeconds(8), Ladder, probes.Get));
        var d = p.Evaluate(t.AddSeconds(9.5), Ladder, probes.Get);
        Assert.True(d!.Hard);
        Assert.Contains("no new segment", d.Reason, StringComparison.Ordinal);
    }

    [Fact]
    public void Steps_Back_Up_After_Three_Stable_Minutes_When_The_Better_Stream_Has_Margin()
    {
        var probes = AllHealthy(T0);
        var p = new SwitchPolicy(A, T0) { TargetDuration = 4 };
        var t = Play(p, T0, 20, 1.0);
        p.OnSegmentFailed(t, "HTTP 503");
        Assert.Same(CHi, p.Evaluate(t, Ladder, probes.Get)!.Target);

        t = Play(p, t, 30, 1.0);                          // 2 min on C: not yet
        probes.Set("A", 40, t);
        Assert.Null(p.Evaluate(t, Ladder, probes.Get));

        t = Play(p, t, 16, 1.0);                          // 3 min+, but A was backed off only 2 min: now eligible
        probes.Set("A", 14, t);                           // 1.75× — healthy, not enough margin to go back
        Assert.Null(p.Evaluate(t, Ladder, probes.Get));

        probes.Set("A", 16.5, t.AddSeconds(-100));        // enough, but the probe is stale: ask for a new one
        Assert.Null(p.Evaluate(t, Ladder, probes.Get));
        Assert.Contains("A", p.WantProbe);

        probes.Set("A", 17, t);
        var up = p.Evaluate(t, Ladder, probes.Get);
        Assert.True(up!.Up);
        Assert.Same(A, up.Target);
    }

    [Fact]
    public void Within_One_Stream_The_Measured_Rate_Picks_The_Rendition_That_Fits()
    {
        var probes = new Probes();
        probes.Set("C", 40, T0);
        var ladder = LadderRanking.Rank(new[] { CHi, CLo });
        var p = new SwitchPolicy(CHi, T0) { TargetDuration = 4 };
        var t = Play(p, T0, 20, 1.0);
        t = Play(p, t, 2, 3.4, bytes: 2_600_000);         // ~6 Mbps achieved for a 5 Mbps rendition
        var d = p.Evaluate(t, ladder, probes.Get);
        Assert.Same(CLo, d!.Target);                      // 1.2 Mbps × 1.5 fits in what was measured
    }
}

public class OutputWindowTests
{
    private static PublishedSegment Seg(double d, bool disc = false)
        => new() { Duration = d, Discontinuity = disc, Upstream = new Uri("http://x.example/s.ts") };

    [Fact]
    public void Numbers_Keep_Rising_Across_Sources_And_Restarts()
    {
        var w = new OutputWindow(size: 3);
        var now = DateTimeOffset.UtcNow;
        for (var i = 0; i < 5; i++)
        {
            w.Publish(Seg(4), now);
        }

        w.Publish(Seg(6.006), now); // a switch to a stream with longer segments
        var text = w.Render(n => $"/JellyTV/Live/ch/{n}.ts?s=sig");

        Assert.Contains("#EXT-X-MEDIA-SEQUENCE:3\n", text, StringComparison.Ordinal);
        Assert.Contains("#EXT-X-TARGETDURATION:7\n", text, StringComparison.Ordinal);
        Assert.Contains("#EXTINF:6.006,\n/JellyTV/Live/ch/5.ts?s=sig\n", text, StringComparison.Ordinal);
        Assert.DoesNotContain("DISCONTINUITY", text, StringComparison.Ordinal);
        Assert.Null(w.Get(2));

        w.Clear();
        w.Publish(Seg(4, disc: true), now);
        Assert.Equal(6, w.Last!.Sequence);
    }

    [Fact]
    public void Discontinuity_Sequence_Counts_Tags_That_Left_The_Window()
    {
        var w = new OutputWindow(size: 2);
        var now = DateTimeOffset.UtcNow;
        w.Publish(Seg(4), now);
        w.Publish(Seg(4, disc: true), now);
        Assert.Contains("#EXT-X-DISCONTINUITY-SEQUENCE:0\n#EXTINF:4.000,\n/0\n#EXT-X-DISCONTINUITY\n", w.Render(n => "/" + n), StringComparison.Ordinal);
        w.Publish(Seg(4), now);
        w.Publish(Seg(4), now);
        Assert.Contains("#EXT-X-DISCONTINUITY-SEQUENCE:1\n", w.Render(n => "/" + n), StringComparison.Ordinal);
    }
}

public class TsSplicerTests
{
    // Heads (first 400 packets) of real segments from the live bed: A is ffmpeg's default layout (PMT 0x1000, video
    // 0x100, audio 0x101), B an unrelated encoder's (PMT 480, video 481, audio 482) on a far-away timestamp base.
    private static readonly byte[] SegA = LiveFixtures.Bytes("a-1080p60-head.ts");
    private static readonly byte[] SegB = LiveFixtures.Bytes("b-720p30-head.ts");
    private static readonly byte[] SegC = LiveFixtures.Bytes("c-540p30-head.ts");

    [Fact]
    public void Reads_Layout_Counters_And_Time_Span_Of_A_Real_Segment()
    {
        var a = TsSplicer.Analyze(SegA);
        Assert.True(a.IsTs);
        Assert.Equal(0x1000, a.PmtPid);
        Assert.Equal(new[] { (0x100, (byte)0x1B, TsStreamKind.Video), (0x101, (byte)0x0F, TsStreamKind.Audio) },
            a.Streams.Select(s => (s.Pid, s.StreamType, s.Kind)));
        Assert.NotNull(a.PatPacket);
        Assert.NotNull(a.PmtPacket);
        Assert.True(a.EndDts > a.StartDts);

        var b = TsSplicer.Analyze(SegB);
        Assert.Equal(480, b.PmtPid);
        Assert.Equal(new[] { 481, 482 }, b.Streams.Select(s => s.Pid));
        Assert.True(b.StartDts > 7_000_000_000L);
    }

    [Fact]
    public void A_Spliced_Segment_Continues_The_Old_Streams_Pids_Counters_And_Clock()
    {
        var a = TsSplicer.Analyze(SegA);
        var map = TsSplicer.Plan(a, TsSplicer.Analyze(SegB), a);
        Assert.NotNull(map);

        var output = TsSplicer.Rewrite(SegB, map!);
        var o = TsSplicer.Analyze(output);

        Assert.Equal(0x1000, o.PmtPid);
        Assert.Equal(new[] { 0x100, 0x101 }, o.Streams.Select(s => s.Pid));
        // each stream starts at or after where its counterpart ended, and the tighter one exactly there
        Assert.All(new[] { 0x100, 0x101 }, pid => Assert.True(o.StartByPid[pid] >= a.EndByPid[pid]));
        Assert.Contains(new[] { 0x100, 0x101 }, pid => o.StartByPid[pid] == a.EndByPid[pid]);
        foreach (var pid in new[] { 0, 0x1000, 0x100, 0x101 })
        {
            Assert.Equal((a.LastCc[pid] + 1) & 0xF, o.FirstCc[pid]);        // no continuity error at the join (PAT, PMT, video, audio)
        }

        Assert.Equal(0, output.Length % 188);
        Assert.All(Enumerable.Range(0, output.Length / 188), i => Assert.Equal(0x47, output[i * 188]));
        var bPes = TsSplicer.Analyze(SegB);
        Assert.Equal(bPes.EndDts! - bPes.StartDts!, o.EndDts! - o.StartDts!); // spacing inside the segment is untouched
    }

    [Fact]
    public void Renditions_Of_Another_Stream_Chain_Across_Two_Switches_And_The_33_Bit_Wrap()
    {
        var a = TsSplicer.Analyze(SegA);
        // a previous segment that ends a frame before the 33-bit clock wraps
        var shift = TsSplicer.Mod(TsSplicer.Wrap - 3_000 - a.EndDts!.Value);
        var aLate = TsSplicer.Analyze(TsSplicer.Rewrite(SegA, TsSplicer.WithContinuity(new SpliceMap { Offset = shift, PidMap = { [0x100] = 0x100, [0x101] = 0x101 }, InPmtPid = 0x1000, OutPmtPid = 0x1000 }, a, a.LastCc)));
        Assert.Equal(TsSplicer.Wrap - 3_000, aLate.EndDts);

        var m1 = TsSplicer.Plan(a, TsSplicer.Analyze(SegC), aLate)!;
        var c = TsSplicer.Analyze(TsSplicer.Rewrite(SegC, m1));
        Assert.True(c.StartByPid[0x100] >= aLate.EndByPid[0x100]);
        Assert.True(c.EndDts > TsSplicer.Wrap);                               // wraps inside the segment

        var m2 = TsSplicer.Plan(a, TsSplicer.Analyze(SegB), c)!;
        var b = TsSplicer.Analyze(TsSplicer.Rewrite(SegB, m2));
        Assert.True(b.StartDts < 90_000 * 60);                                // past the wrap: small numbers again
        var gap = TsSplicer.Mod(b.StartByPid[0x100] - c.EndByPid[0x100]);
        Assert.True(gap < 9_000, $"video gap {gap} ticks");                   // under 0.1 s, never negative
        Assert.Equal((c.LastCc[0x100] + 1) & 0xF, b.FirstCc[0x100]);
    }

    [Fact]
    public void Layouts_That_Cannot_Continue_A_Copy_Remux_Are_Refused()
    {
        var a = TsSplicer.Analyze(SegA);
        var audioOnly = TsSplicer.Analyze(SegB);
        audioOnly.Streams.RemoveAll(s => s.Kind == TsStreamKind.Video);
        Assert.Null(TsSplicer.Plan(a, audioOnly, a));

        var hevc = TsSplicer.Analyze(SegB);
        var v = hevc.Streams[0];
        hevc.Streams[0] = v with { StreamType = 0x24 };
        Assert.Null(TsSplicer.Plan(a, hevc, a));

        Assert.Null(TsSplicer.Plan(a, TsSplicer.Analyze(new byte[5000]), a)); // not TS at all
    }

    [Fact]
    public void Identity_Is_Byte_For_Byte_And_A_Junk_Prefix_Is_Found_Past()
    {
        Assert.Equal(SegA, TsSplicer.Rewrite(SegA, SpliceMap.Identity));
        var prefixed = new byte[] { 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A }.Concat(SegA).ToArray();
        var info = TsSplicer.Analyze(prefixed);
        Assert.Equal(8, info.SyncOffset);
        Assert.Equal(TsSplicer.Analyze(SegA).StartDts, info.StartDts);
    }
}

public class SwitchAlignmentTests
{
    private static readonly HlsMediaPlaylist Pl = HlsParser.ParseMedia(LiveFixtures.Text("a-media.m3u8"), new Uri("http://source.example/A/index.m3u8"));

    [Fact]
    public void A_Step_Down_Enters_The_New_Stream_As_Far_Behind_Its_Live_Edge_As_The_Old_One_Was()
    {
        Assert.Equal(18, SwitchAlignment.StartSequence(Pl, behindSeconds: 4.5, urgent: true));  // one segment was missing
        Assert.Equal(16, SwitchAlignment.StartSequence(Pl, behindSeconds: 11, urgent: true));
        Assert.Equal(18, SwitchAlignment.StartSequence(Pl, behindSeconds: 0, urgent: true));    // closest that exists
        Assert.Equal(13, SwitchAlignment.StartSequence(Pl, behindSeconds: 90, urgent: true));   // as far back as it goes
    }

    [Fact]
    public void A_Step_Up_Waits_For_The_Next_Segment_Rather_Than_Repeat_One()
    {
        Assert.Equal(19, SwitchAlignment.StartSequence(Pl, behindSeconds: 1, urgent: false));
        Assert.Equal(18, SwitchAlignment.StartSequence(Pl, behindSeconds: 4, urgent: false));
    }
}
