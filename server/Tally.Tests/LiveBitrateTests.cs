using System.IO;
using Jellyfin.Plugin.Tally.Live;
using Jellyfin.Plugin.Tally.Services;
using MediaBrowser.Controller.Library;
using MediaBrowser.Controller.LiveTv;
using MediaBrowser.Model.Dto;
using MediaBrowser.Model.Entities;
using MediaBrowser.Model.LiveTv;
using MediaBrowser.Model.MediaInfo;
using Microsoft.Extensions.Logging.Abstractions;
using Xunit;

namespace Tally.Tests;

public class LiveBitrateTests
{
    private const string TallyPath = "http://127.0.0.1:8096/JellyTV/Live/abc123.m3u8?s=sig";

    [Theory]
    [InlineData(TallyPath, "abc123")]
    [InlineData("http://127.0.0.1:8096/base/JellyTV/Live/redzone.m3u8?s=x", "redzone")]
    [InlineData("http://192.168.1.5:8096/JellyTV/Live/a%20b.m3u8", "a b")]
    [InlineData("http://127.0.0.1:8096/JellyTV/Live/abc/12.ts?s=x", null)]
    [InlineData("http://example.com/live/chan.m3u8", null)]
    [InlineData("udp://239.0.0.1:1234", null)]
    [InlineData(null, null)]
    public void Recognizes_Tally_Channel_Addresses(string? path, string? id)
        => Assert.Equal(id, LiveStreamFacts.ChannelIdOf(path));

    [Fact]
    public void Master_Rate_Is_The_Rendition_Ffmpeg_Plays()
    {
        var u = new Uri("http://x/");
        var variants = new List<HlsVariant>
        {
            new() { Uri = u, Bandwidth = 2_500_000, Width = 1280, Height = 720 },
            new() { Uri = u, Bandwidth = 9_000_000, AverageBandwidth = 6_200_000, Width = 1920, Height = 1080 },
            new() { Uri = u, Bandwidth = 800_000, Width = 640, Height = 360 }
        };
        Assert.Equal(6_200_000, LiveBitrate.FromMaster(variants));

        variants[1] = new HlsVariant { Uri = u, Bandwidth = 9_000_000, Width = 1920, Height = 1080 };
        Assert.Equal(9_000_000, LiveBitrate.FromMaster(variants));
        Assert.Null(LiveBitrate.FromMaster(new List<HlsVariant>()));
        Assert.Null(LiveBitrate.FromMaster(new List<HlsVariant> { new() { Uri = u } }));
    }

    [Fact]
    public void Segments_Give_Their_Peak()
    {
        // 6 s segments of 4.5 MB and 6 MB: 6 and 8 Mbps
        Assert.Equal(8_000_000, LiveBitrate.FromSegments(new[] { (4_500_000L, 6.0), (6_000_000L, 6.0) }));
        // empty, too short and slate-sized segments do not count
        Assert.Null(LiveBitrate.FromSegments(new[] { (0L, 6.0), (1_000_000L, 0.1), (1_000L, 6.0) }));
    }

    [Fact]
    public void Ladder_Ceiling_Is_The_Fastest_Known_Rung()
    {
        var u = new Uri("http://x/");
        var tiers = new[]
        {
            new Tier { MediaUri = u, Bandwidth = 7_000_000 },
            new Tier { MediaUri = u, Bandwidth = 3_000_000, MeasuredBitrate = 7_600_000 },
            new Tier { MediaUri = u } // unknown: its 4 Mbps guess is not a fact
        };
        Assert.Equal(7_600_000, LiveBitrate.Ceiling(tiers));
        Assert.Null(LiveBitrate.Ceiling(new[] { new Tier { MediaUri = u } }));
        Assert.Equal(8_000_000, LiveBitrate.Max(null, 8_000_000, 5_000_000));
        Assert.Null(LiveBitrate.Max(null, 10));
    }

    private static MediaInfo Probe(int? videoBitrate = null) => new()
    {
        Container = "hls",
        MediaStreams = new List<MediaStream>
        {
            new() { Type = MediaStreamType.Video, Index = 0, Codec = "h264", Width = 1280, Height = 720, BitRate = videoBitrate, IsAVC = false, Language = "eng" },
            new() { Type = MediaStreamType.Video, Index = 1, Codec = "h264", Width = 1920, Height = 1080, BitRate = videoBitrate, IsAVC = false, IsInterlaced = false, Profile = "High", Level = 42 },
            new() { Type = MediaStreamType.Audio, Index = 2, Codec = "aac", Channels = 2, BitRate = 192_000, Language = "eng" }
        }
    };

    private static MediaSourceInfo M3uSource() => new()
    {
        Path = TallyPath,
        Protocol = MediaProtocol.Http,
        MediaStreams = new[]
        {
            new MediaStream { Type = MediaStreamType.Video, Index = -1, IsInterlaced = true },
            new MediaStream { Type = MediaStreamType.Audio, Index = -1 }
        },
        IsInfiniteStream = true,
        RequiresOpening = true
    };

    [Fact]
    public void Apply_Fills_In_Like_Jellyfins_Probe_With_The_Real_Rate()
    {
        var probe = Probe();
        var source = M3uSource();
        LiveBitrate.Apply(source, probe, 8_000_000, opened: true);

        Assert.Equal("hls", source.Container);
        Assert.Equal(2, source.MediaStreams.Count);
        var v = source.MediaStreams.Single(s => s.Type == MediaStreamType.Video);
        var a = source.MediaStreams.Single(s => s.Type == MediaStreamType.Audio);
        Assert.Equal(1920, v.Width); // the rendition ffmpeg picks without a -map
        Assert.Equal(-1, v.Index);
        Assert.Equal(-1, a.Index);
        Assert.Null(a.Language);
        Assert.Null(v.IsAVC);
        Assert.False(v.IsInterlaced);
        Assert.Equal(8_000_000 - 192_000, v.BitRate);
        Assert.Equal(8_000_000, source.Bitrate);
        Assert.Null(source.RunTimeTicks);
        Assert.Null(source.DefaultAudioStreamIndex);
        Assert.False(source.SupportsProbing); // Jellyfin must not probe again and put its 20 Mbps guess back

        // the probe itself is left alone, so the next channel open starts from it again
        Assert.Equal(0, probe.MediaStreams[0].Index);
        Assert.Equal("eng", probe.MediaStreams[2].Language);
    }

    [Fact]
    public void Apply_Without_A_Rate_Leaves_Jellyfins_Fallback()
    {
        var source = M3uSource();
        LiveBitrate.Apply(source, Probe(), null, opened: false);
        Assert.Null(source.MediaStreams.Single(s => s.Type == MediaStreamType.Video).BitRate);
        Assert.True(source.SupportsProbing);

        source = M3uSource();
        LiveBitrate.Apply(source, Probe(5_000_000), null, opened: true);
        Assert.Equal(5_000_000, source.MediaStreams.Single(s => s.Type == MediaStreamType.Video).BitRate);
    }

    private sealed class FakeFacts : IStreamFactsSource
    {
        public StreamFacts? Facts { get; set; } = new(Probe(), 7_000_000, DateTimeOffset.UtcNow);

        public int Gets { get; private set; }

        public Task<StreamFacts?> GetAsync(MediaSourceInfo source, CancellationToken cancellationToken)
        {
            Gets++;
            return Task.FromResult(Facts);
        }

        public StreamFacts? Peek(string path) => Facts;
    }

    private sealed class FakeStream : ILiveStream
    {
        public int ConsumerCount { get; set; }

        public string OriginalStreamId { get; set; } = string.Empty;

        public string TunerHostId => "t";

        public bool EnableStreamSharing => false;

        public MediaSourceInfo MediaSource { get; set; } = null!;

        public string UniqueId => "u";

        public Task Open(CancellationToken openCancellationToken) => Task.CompletedTask;

        public Task Close() => Task.CompletedTask;

        public Stream GetStream() => Stream.Null;

        public void Dispose()
        {
        }
    }

    /// <summary>Jellyfin's M3U host, as far as the Tally host sees it: one Tally channel and one other.</summary>
    private sealed class FakeM3u : ITunerHost
    {
        public int Opened { get; private set; }

        public string Name => "M3U Tuner";

        public string Type => "m3u";

        public bool IsSupported => true;

        private static string? PathOf(string id) => id switch
        {
            "m3u_tally_1" => TallyPath,
            "m3u_other_1" => "http://iptv.example.com/live/1.m3u8",
            _ => null
        };

        public Task<List<ChannelInfo>> GetChannels(bool enableCache, CancellationToken cancellationToken)
            => Task.FromResult(new List<ChannelInfo> { new() { Id = "m3u_tally_1" }, new() { Id = "m3u_other_1" } });

        public Task<ILiveStream> GetChannelStream(string channelId, string streamId, IList<ILiveStream> currentLiveStreams, CancellationToken cancellationToken)
        {
            Opened++;
            var source = M3uSource();
            source.Path = PathOf(channelId) ?? throw new FileNotFoundException();
            return Task.FromResult<ILiveStream>(new FakeStream { MediaSource = source });
        }

        public Task<List<MediaSourceInfo>> GetChannelStreamMediaSources(string channelId, CancellationToken cancellationToken)
        {
            var path = PathOf(channelId);
            var list = new List<MediaSourceInfo>();
            if (path != null)
            {
                var s = M3uSource();
                s.Path = path;
                list.Add(s);
            }

            return Task.FromResult(list);
        }

        public Task<List<TunerHostInfo>> DiscoverDevices(int discoveryDurationMs, CancellationToken cancellationToken)
            => Task.FromResult(new List<TunerHostInfo>());
    }

    [Fact]
    public async Task Tuner_Host_Fills_In_Tally_Channels_And_Passes_Others_On()
    {
        var m3u = new FakeM3u();
        var facts = new FakeFacts();
        var host = new TallyTunerHost(() => m3u, () => facts, NullLogger.Instance);

        // lists nothing of its own: the channels (ids, guide, favorites) stay the M3U tuner's
        Assert.Empty(await host.GetChannels(false, CancellationToken.None));
        Assert.Empty(await host.DiscoverDevices(1000, CancellationToken.None));
        await Assert.ThrowsAsync<ArgumentException>(() => host.Validate(new TunerHostInfo { Type = TallyTunerHost.HostType }));

        // a Tally channel: media info filled in, before and after opening
        var sources = await host.GetChannelStreamMediaSources("m3u_tally_1", CancellationToken.None);
        Assert.Equal(7_000_000, Assert.Single(sources).Bitrate);
        var stream = await host.GetChannelStream("m3u_tally_1", "sid", new List<ILiveStream>(), CancellationToken.None);
        Assert.Equal(1, m3u.Opened); // opened by the M3U host
        Assert.Equal(7_000_000, stream.MediaSource.Bitrate);
        Assert.False(stream.MediaSource.SupportsProbing);

        // anyone else's channel: no sources and FileNotFound, so Jellyfin asks the next host, and nothing is opened
        Assert.Empty(await host.GetChannelStreamMediaSources("m3u_other_1", CancellationToken.None));
        await Assert.ThrowsAsync<FileNotFoundException>(() => host.GetChannelStream("m3u_other_1", "sid", new List<ILiveStream>(), CancellationToken.None));
        await Assert.ThrowsAsync<FileNotFoundException>(() => host.GetChannelStream("hdhr_1", "sid", new List<ILiveStream>(), CancellationToken.None));
        Assert.Equal(1, m3u.Opened);
        Assert.Equal(1, facts.Gets);
    }

    [Fact]
    public async Task Tuner_Host_Without_Facts_Leaves_Jellyfins_Stream_As_It_Was()
    {
        var m3u = new FakeM3u();
        var host = new TallyTunerHost(() => m3u, () => new FakeFacts { Facts = null }, NullLogger.Instance);
        var stream = await host.GetChannelStream("m3u_tally_1", "sid", new List<ILiveStream>(), CancellationToken.None);
        Assert.True(stream.MediaSource.SupportsProbing);
        Assert.All(stream.MediaSource.MediaStreams, s => Assert.Equal(-1, s.Index));
        Assert.Null(stream.MediaSource.Bitrate);

        // and without Jellyfin's M3U host, it is never in the way
        host = new TallyTunerHost(() => null, () => new FakeFacts(), NullLogger.Instance);
        Assert.Empty(await host.GetChannelStreamMediaSources("m3u_tally_1", CancellationToken.None));
        await Assert.ThrowsAsync<FileNotFoundException>(() => host.GetChannelStream("m3u_tally_1", "sid", new List<ILiveStream>(), CancellationToken.None));
    }
}
