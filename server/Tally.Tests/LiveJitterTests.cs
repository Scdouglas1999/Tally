using System.Net;
using System.Net.Http;
using Jellyfin.Plugin.Tally.Live;
using Jellyfin.Plugin.Tally.Models;
using Jellyfin.Plugin.Tally.Scores;
using Jellyfin.Plugin.Tally.Sources;
using Microsoft.Extensions.Logging.Abstractions;
using Xunit;

namespace Tally.Tests;

/// <summary>Builds the playlists a live source lists over time, one fetch at a time.</summary>
internal static class Upstream
{
    public static List<HlsSegment> Window(long last, int count = 3, double duration = 5)
        => Enumerable.Range(0, count).Select(i => new HlsSegment
        {
            Sequence = last - count + 1 + i,
            Duration = duration,
            Uri = new Uri($"https://cdn.source.example/p_{last - count + 1 + i}.ts")
        }).ToList();

    /// <summary>Feeds a meter a fetch every <paramref name="poll"/> seconds from <paramref name="from"/> to
    /// <paramref name="to"/>; <paramref name="newest"/> says which segment is the newest listed at a given second.</summary>
    public static void Watch(CadenceMeter m, DateTimeOffset t0, double from, double to, Func<double, long> newest, double poll = 1.7, double duration = 5)
    {
        for (var s = from; s <= to + 1e-9; s += poll)
        {
            m.OnPlaylist(t0.AddSeconds(s), Window(newest(s), 3, duration));
        }
    }
}

public class CadenceMeterTests
{
    private static readonly DateTimeOffset T0 = new(2026, 9, 24, 21, 32, 0, TimeSpan.Zero);

    [Fact]
    public void A_Steady_Stream_Needs_One_Segment_Of_Cushion_And_Is_Never_Late()
    {
        var m = new CadenceMeter(T0);
        // a new 5 s segment every 5 s, polled every 1.7 s for two minutes
        Upstream.Watch(m, T0, 0, 120, s => 100 + (long)(s / 5));
        var c = m.Summarize(T0.AddSeconds(120), TimeSpan.FromMinutes(2));

        Assert.Equal(0, c.Late);
        Assert.InRange(c.AvgGap, 4.8, 5.2);
        Assert.InRange(c.MaxGap, 5, 7);                    // polling adds up to one poll interval
        Assert.InRange(c.NeededCushion, 5, 7);
        Assert.Equal(5, c.SegmentDuration);
        Assert.False(c.Irregular);
        Assert.True(c.Enough);
    }

    [Fact]
    public void The_Owners_Burst_Pattern_Shows_As_Late_Updates_And_A_Big_Cushion()
    {
        // From the September 24 server log: 11 alone, ~5 s of nothing, 12+13 together, 14, ~11 s of nothing, 15.
        var arrivals = new (double At, long Newest)[] { (0, 11), (10, 13), (15, 14), (26, 15), (31, 16) };
        long Newest(double s) => arrivals.Last(a => a.At <= s + 1e-9).Newest;

        var m = new CadenceMeter(T0.AddSeconds(-5));
        m.OnPlaylist(T0.AddSeconds(-5), Upstream.Window(10));  // baseline: 10 was already listed
        Upstream.Watch(m, T0, 0, 31, Newest, poll: 1);
        var c = m.Summarize(T0.AddSeconds(31), TimeSpan.FromMinutes(1));

        Assert.Equal(2, c.Late);                            // the 10 s and the 11 s gap (> 1.5 × 5 s)
        Assert.Equal(11, c.MaxGap, 1);
        Assert.InRange(c.NeededCushion, 10.9, 11.1);        // a player needed 11 s in hand to never run dry
        Assert.True(c.Irregular);
        Assert.Contains("2 late", c.Describe(), StringComparison.Ordinal);
    }

    [Fact]
    public void A_Wait_Still_Open_Counts_As_Late_Before_The_Segment_Comes()
    {
        var m = new CadenceMeter(T0);
        Upstream.Watch(m, T0, 0, 30, s => 100 + (long)(s / 5));  // steady: 105 arrives at 25.5 s
        Upstream.Watch(m, T0, 31, 34, _ => 105);                 // then nothing more
        var c = m.Summarize(T0.AddSeconds(34), TimeSpan.FromMinutes(1));

        Assert.Equal(1, c.Late);                                 // 8.5 s and counting, more than 7.5 s
        Assert.InRange(c.MaxGap, 8, 9);
    }

    [Fact]
    public void The_First_Fetch_Is_Backlog_And_A_Restarted_Numbering_Starts_Over()
    {
        var m = new CadenceMeter(T0);
        Assert.Equal(0, m.OnPlaylist(T0, Upstream.Window(500, 6)));
        Assert.Equal(1, m.OnPlaylist(T0.AddSeconds(5), Upstream.Window(501, 6)));
        Assert.Equal(0, m.OnPlaylist(T0.AddSeconds(10), Upstream.Window(3, 3)));   // the encoder restarted
        Assert.Equal(0, m.Summarize(T0.AddSeconds(10), TimeSpan.FromMinutes(1)).Arrivals);
        Assert.Equal(1, m.OnPlaylist(T0.AddSeconds(15), Upstream.Window(4, 3)));
    }

    [Fact]
    public void Steadier_Compares_Late_Updates_Then_Extra_Cushion()
    {
        var steady = new CadenceSummary(60, 12, 12, 5, 6, 0, 6, 5);
        var bursty = new CadenceSummary(60, 6, 12, 10, 15, 3, 15, 5);
        Assert.True(CadenceSummary.CompareSteadiness(steady, bursty) < 0);
        Assert.True(CadenceSummary.CompareSteadiness(bursty, steady) > 0);
        Assert.Equal(10, bursty.Excess);
    }
}

public class JitterPolicyTests
{
    private static readonly DateTimeOffset T0 = new(2026, 9, 24, 21, 32, 0, TimeSpan.Zero);

    private static Tier T(string cand, int h, double fps, long bw) => new()
    {
        Candidate = cand[0] - 'A', CandidateKey = cand, Variant = 0, MediaUri = new Uri($"http://{cand}.example/0"), Height = h, FrameRate = fps, Bandwidth = bw
    };

    // the owner's channel: two 720p30 streams of the same game
    private static readonly Tier A = T("A", 720, 30, 7_000_000);
    private static readonly Tier B = T("B", 720, 30, 6_000_000);
    private static readonly List<Tier> Ladder = LadderRanking.Rank(new[] { A, B });

    private static readonly CadenceSummary Steady = new(60, 12, 12, 5, 6.7, 0, 6.7, 5);
    private static readonly CadenceSummary Bursty = new(60, 6, 12, 9.5, 11, 2, 11, 5);

    private sealed class Probes
    {
        public Dictionary<string, CandidateProbe> Map { get; } = new();

        public void Set(string cand, DateTimeOffset at, CadenceSummary? cadence, double mbps = 90)
            => Map[cand] = new CandidateProbe { At = at, Ok = true, Fresh = true, Throughput = mbps * 1e6, Cadence = cadence };

        public CandidateProbe? Get(string k) => Map.TryGetValue(k, out var p) ? p : null;
    }

    /// <summary>Plays fast downloads (the owner's case: 64-114 Mbps for a 7 Mbps stream) for <paramref name="seconds"/>.</summary>
    private static DateTimeOffset Fast(SwitchPolicy p, DateTimeOffset t, double seconds)
    {
        for (var s = 0.0; s < seconds; s += 5)
        {
            t = t.AddSeconds(5);
            p.OnNewSegmentListed(t);
            p.OnSegment(t, 0.5, 5, 4_400_000);
        }

        return t;
    }

    [Fact]
    public void Ranking_Skips_A_Stream_Seen_Publishing_In_Bursts()
    {
        var probes = new Probes();
        probes.Set("A", T0, Bursty);
        probes.Set("B", T0, Steady);
        Assert.Same(A, Ladder[0]);                         // A is the better stream on paper
        Assert.False(LadderRanking.Healthy(A, probes.Get("A"), T0, TimeSpan.FromMinutes(5)));
        Assert.Same(B, SwitchPolicy.Initial(Ladder, probes.Get, T0, TimeSpan.FromMinutes(45)));

        probes.Set("A", T0, null);                         // never watched (one-fetch probe): judged as before
        Assert.Same(A, SwitchPolicy.Initial(Ladder, probes.Get, T0, TimeSpan.FromMinutes(45)));
    }

    [Fact]
    public void Two_Late_Updates_In_A_Minute_Move_To_A_Steadier_Stream_Even_When_Downloads_Are_Fast()
    {
        var probes = new Probes();
        probes.Set("A", T0, null);
        probes.Set("B", T0, Steady);
        var p = new SwitchPolicy(A, T0) { TargetDuration = 5 };
        var t = Fast(p, T0, 30);

        // one late update: nothing yet, but the others' playlists should be watched
        p.OnCadence(t, Bursty with { Late = 1 });
        Assert.Null(p.Evaluate(t, Ladder, probes.Get));
        Assert.True(p.WatchAlternates);

        t = Fast(p, t, 10);
        p.OnCadence(t, Bursty);
        var d = p.Evaluate(t, Ladder, probes.Get);

        Assert.NotNull(d);
        Assert.Same(B, d!.Target);
        Assert.False(d.Hard);
        Assert.Contains("bursts", d.Reason, StringComparison.Ordinal);
        Assert.Contains("2 late updates", d.Reason, StringComparison.Ordinal);
        Assert.True(p.IsBackedOff("A", t.AddMinutes(1)));  // no stepping straight back up to it
        Assert.False(p.WatchAlternates);                   // the new stream's cadence starts over
    }

    [Fact]
    public void No_Move_Without_A_Stream_Known_To_Be_Steadier()
    {
        var probes = new Probes();
        probes.Set("A", T0, null);
        probes.Set("B", T0, null);                         // B's cadence unknown
        var p = new SwitchPolicy(A, T0) { TargetDuration = 5 };
        var t = Fast(p, T0, 40);
        p.OnCadence(t, Bursty);
        Assert.Null(p.Evaluate(t, Ladder, probes.Get));

        probes.Set("B", t, Bursty with { Late = 1 });      // B is late too
        Assert.Null(p.Evaluate(t, Ladder, probes.Get));

        probes.Set("B", t, Steady with { Watched = 6, Arrivals = 1 }); // watched too briefly to tell
        Assert.Null(p.Evaluate(t, Ladder, probes.Get));

        probes.Set("B", t.AddMinutes(-6), Steady);         // probe too old: ask for a new one
        Assert.Null(p.Evaluate(t, Ladder, probes.Get));
        Assert.Contains("B", p.WantProbe);

        probes.Set("B", t, Steady);
        Assert.Same(B, p.Evaluate(t, Ladder, probes.Get)!.Target);
    }

    [Fact]
    public void Late_Updates_Hold_Off_A_Step_Up()
    {
        var probes = new Probes();
        probes.Set("A", T0, Steady);
        probes.Set("B", T0, Steady);
        var p = new SwitchPolicy(B, T0) { TargetDuration = 5 };
        var t = Fast(p, T0, 175);
        p.OnCadence(t, Steady with { Late = 1 });          // one late update on B
        t = Fast(p, t, 10);
        probes.Set("A", t, Steady);
        Assert.Null(p.Evaluate(t, Ladder, probes.Get));    // 3 min on B, but not 3 min without trouble
    }
}

public class CushionTests
{
    private static HlsMediaPlaylist Pl(int count, double duration)
        => new() { TargetDuration = duration, MediaSequence = 100, Segments = Upstream.Window(100 + count - 1, count, duration) };

    [Fact]
    public void A_Steady_Stream_Keeps_One_Segment_In_Hand_When_Its_Window_Has_Room()
    {
        var plan = Cushion.Plan(Pl(6, 4), new CadenceSummary(60, 15, 15, 4, 5.5, 0, 5.5, 4));
        Assert.Equal(new long[] { 102, 103, 104 }, plan.Listed.Select(s => s.Sequence));
        Assert.Equal(105, plan.NextSequence);             // 105 is downloaded and held back
        Assert.True(plan.Paced);
        Assert.Equal(12, plan.LeadTarget);
        Assert.Equal(16, plan.Behind);
        Assert.Equal(16, Cushion.Plan(Pl(6, 4), null).Behind);   // never watched: the same

        // four segments: no room (the oldest may be about to leave the window) — the newest three, as before
        var tight = Cushion.Plan(Pl(4, 4), null);
        Assert.Equal(new long[] { 101, 102, 103 }, tight.Listed.Select(s => s.Sequence));
        Assert.False(tight.Paced);
        Assert.Equal(12, tight.Behind);
        Assert.Null(tight.Why);
    }

    [Fact]
    public void A_Bursty_Stream_Starts_Further_Back_And_Holds_The_Rest()
    {
        // the live bed's burst source: six 4 s segments listed, pauses up to 15 s
        var bursty = new CadenceSummary(15, 2, 5, 9, 12, 1, 12.5, 4);
        var plan = Cushion.Plan(Pl(6, 4), bursty);

        Assert.Equal(new long[] { 100, 101, 102 }, plan.Listed.Select(s => s.Sequence)); // the oldest three
        Assert.Equal(103, plan.NextSequence);             // 103-105 are downloaded and held back
        Assert.True(plan.Paced);
        Assert.Equal(12, plan.LeadTarget);
        Assert.Equal(24, plan.Behind);                    // 22.5 s wanted; the window's start is as far as it goes
        Assert.Contains("bursts", plan.Why, StringComparison.Ordinal);
    }

    [Fact]
    public void A_Short_Window_Leaves_No_Room_To_Start_Further_Back()
    {
        // the real web source captured on September 23: three 5 s segments
        var pl = HlsParser.ParseMedia(LiveFixtures.Text("web-media.m3u8"), new Uri("https://mirror1.cdn.source.example/scripts/stream/p1000.m3u8"));
        var plan = Cushion.Plan(pl, new CadenceSummary(60, 6, 12, 9.5, 11, 2, 11, 5));
        Assert.Equal(3, plan.Listed.Count);
        Assert.False(plan.Paced);
        Assert.Equal(15, plan.Behind);
    }

    [Fact]
    public void Held_Back_Segments_Are_Due_When_The_Lead_Would_Drop()
    {
        var start = new DateTimeOffset(2026, 9, 24, 22, 0, 0, TimeSpan.Zero);
        // 12 s listed with a 12 s lead: the first held-back segment is due right away; after it (16 s), 4 s later
        Assert.Equal(start, Cushion.NextRelease(start, 12, 12));
        Assert.Equal(start.AddSeconds(4), Cushion.NextRelease(start, 16, 12));
    }
}

public class SessionStatsTests
{
    private static readonly DateTimeOffset T0 = new(2026, 9, 24, 21, 32, 0, TimeSpan.Zero);

    [Fact]
    public void A_Minute_Line_Says_What_The_Upstream_And_The_Player_Did()
    {
        var s = new SessionStats(T0);
        s.OnPlaylistFetch(0.012);
        s.OnPlaylistFetch(0.2);
        for (var i = 0; i < 3; i++)
        {
            s.OnPublished(T0, 5);
        }

        s.OnDownload(0.5, 5, true);
        s.OnDownload(1.0, 5, true);
        s.OnRequest(T0.AddSeconds(1), 0, 5, 0.8);           // the first request waited for its bytes
        s.OnRequest(T0.AddSeconds(2), 1, 5, 0);
        s.OnRequest(T0.AddSeconds(2), 2, 5, 0);
        s.Sample(T0.AddSeconds(2), 0, 0);
        s.OnPlayerPoll(T0.AddSeconds(3), 2);                // has everything: waiting at the live edge
        s.OnPublished(T0.AddSeconds(14), 5);                // 11 s later
        Assert.True(s.Due(T0.AddSeconds(60)));

        var line = s.MinuteLine(T0.AddSeconds(60), new CadenceSummary(60, 6, 12, 9.5, 11, 2, 11, 5));
        Assert.Contains("4 segments (20 s) published", line, StringComparison.Ordinal);
        Assert.Contains("upstream updates every 9.5s avg / 11.0s max apart for 5s segments", line, StringComparison.Ordinal);
        Assert.Contains("playlist fetch 106 ms avg / 200 ms max", line, StringComparison.Ordinal);
        Assert.Contains("segment download 750 ms avg / 1000 ms max (0.15x / 0.20x of its duration)", line, StringComparison.Ordinal);
        Assert.Contains("player waited at the live edge 1x, 11.0 s avg / 11.0 s max", line, StringComparison.Ordinal);
        Assert.Contains("1 of 3 segment requests waited for their bytes, up to 0.8 s", line, StringComparison.Ordinal);
        Assert.False(s.Due(T0.AddSeconds(61)));             // a new minute started
        Assert.Contains("4 segments", s.TotalLine(T0.AddSeconds(61), null), StringComparison.Ordinal);
    }
}

public class ChannelNamingTests
{
    // fictional teams and a fictional site; the tagline is shaped like the one the owner saw
    private const string Tagline = "Example Sports Hub - Sports for Every League, Every Night";

    private static GameInfo Game() => new()
    {
        Id = "g1",
        Name = "RIV @ LAK",
        State = "in",
        Away = new GameTeam { Abbr = "RIV", Name = "Riverton Otters", ShortName = "Otters", Nickname = "Otters", Location = "Riverton" },
        Home = new GameTeam { Abbr = "LAK", Name = "Lakeside Herons", ShortName = "Herons", Nickname = "Herons", Location = "Lakeside" }
    };

    private static SourceChannel Web(string name, bool fromTitle, string url) => new()
    {
        SourceId = "web", Name = name, StreamUrl = url, NameFromTitle = fromTitle, Group = "Live"
    };

    [Fact]
    public void A_Title_Only_Stands_As_The_Game_It_Names()
    {
        var channels = new List<SourceChannel>
        {
            Web("Otters vs Herons - " + Tagline, true, "https://cdn.x/1.m3u8"),
            Web(Tagline, true, "https://cdn.x/2.m3u8"),
            Web("Riverton Otters Lakeside Herons", false, "https://cdn.x/3.m3u8")   // from an event page's slug
        };

        var (kept, dropped) = ChannelNaming.Resolve(channels, new List<GameInfo> { Game() });

        Assert.Equal(1, dropped);                                         // the tagline names no game: gone
        Assert.Equal(new[] { "Riverton Otters at Lakeside Herons", "Riverton Otters Lakeside Herons" }, kept.Select(c => c.Name));
        Assert.All(kept, c => Assert.False(c.NameFromTitle));
        Assert.Equal("web:riverton otters at lakeside herons", kept[0].GroupKey);
    }

    [Fact]
    public void Without_A_Scoreboard_Only_The_Matchup_Part_Of_A_Title_Is_Kept()
    {
        var channels = new List<SourceChannel>
        {
            Web("Otters vs Herons Live - " + Tagline, true, "https://cdn.x/1.m3u8"),
            Web(Tagline, true, "https://cdn.x/2.m3u8"),
            Web("Stream", true, "https://cdn.x/3.m3u8")
        };

        var (kept, dropped) = ChannelNaming.Resolve(channels, null);

        Assert.Equal(2, dropped);
        Assert.Equal("Otters vs Herons", Assert.Single(kept).Name);
    }

    [Fact]
    public async Task A_Stream_Found_Through_A_Matchup_Link_Is_Named_After_The_Link_Not_The_Page_Title()
    {
        const string home = "<html><head><title>" + Tagline + "</title></head><body>" +
                            "<a href=\"/watch/otters-herons\">Riverton Otters vs Lakeside Herons</a>" +
                            "<script>var featured = 'https://cdn.x/featured.m3u8';</script></body></html>";
        const string watch = "<html><head><title>" + Tagline + "</title></head><body><iframe src=\"https://emb.x/player/77\"></iframe></body></html>";
        var ex = new WebExtractor(new HttpClient(new Stub(url =>
            url.EndsWith(".m3u8", StringComparison.Ordinal) ? Playlist()
            : url.Contains("/player/", StringComparison.Ordinal) ? Html("<html><body><script>file: 'https://cdn.x/game.m3u8'</script></body></html>")
            : url.Contains("/watch/", StringComparison.Ordinal) ? Html(watch)
            : Html(home))), NullLogger.Instance, "TestAgent/1.0");

        var streams = await ex.ExtractAsync("https://site.example/", 1, CancellationToken.None);

        var game = streams.Single(s => s.Url == "https://cdn.x/game.m3u8");
        Assert.Equal("Riverton Otters vs Lakeside Herons", game.Name);
        Assert.False(game.NameFromTitle);
        var featured = streams.Single(s => s.Url == "https://cdn.x/featured.m3u8");
        Assert.True(featured.NameFromTitle);                             // only the site's title to go on
        Assert.Null(WebExtractor.LinkLabel("Watch Live"));
        Assert.Null(WebExtractor.LinkLabel("Link 2 HD"));
    }

    private static HttpResponseMessage Html(string body) =>
        new(HttpStatusCode.OK) { Content = new StringContent(body, System.Text.Encoding.UTF8, "text/html") };

    private static HttpResponseMessage Playlist() =>
        new(HttpStatusCode.OK) { Content = new StringContent("#EXTM3U\n#EXT-X-VERSION:3\n") };

    private sealed class Stub : HttpMessageHandler
    {
        private readonly Func<string, HttpResponseMessage> _router;

        public Stub(Func<string, HttpResponseMessage> router) => _router = router;

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
            => Task.FromResult(_router(request.RequestUri!.ToString()));
    }
}
