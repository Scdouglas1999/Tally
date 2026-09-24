using Jellyfin.Plugin.Tally.Services;
using Xunit;

namespace Tally.Tests;

public class HlsPlaylistRewriterTests
{
    private static string Prox(Uri u) => "/JellyTV/Proxy?u=" + Uri.EscapeDataString(u.ToString()) + "&s=x";

    [Fact]
    public void Rewrites_Segment_Lines_And_Uri_Attributes()
    {
        var playlist = """
            #EXTM3U
            #EXT-X-VERSION:6
            #EXT-X-KEY:METHOD=AES-128,URI="https://key.example.com/k.bin",IV=0x1
            #EXT-X-MAP:URI="init.mp4"
            #EXT-X-TARGETDURATION:6
            #EXTINF:6.0,
            seg/001.ts
            #EXTINF:6.0,
            https://cdn.example.com/abs/002.ts
            """;

        var baseUri = new Uri("https://cdn.example.com/live/master.m3u8");
        var outText = HlsPlaylistRewriter.Rewrite(playlist, baseUri, Prox);

        Assert.Contains("u=https%3A%2F%2Fcdn.example.com%2Flive%2Fseg%2F001.ts", outText);
        Assert.Contains("u=https%3A%2F%2Fcdn.example.com%2Fabs%2F002.ts", outText);
        Assert.Contains("URI=\"/JellyTV/Proxy?u=https%3A%2F%2Fkey.example.com%2Fk.bin", outText);
        Assert.Contains("URI=\"/JellyTV/Proxy?u=https%3A%2F%2Fcdn.example.com%2Flive%2Finit.mp4", outText);
        Assert.Contains("#EXT-X-TARGETDURATION:6", outText);
        Assert.DoesNotContain("seg/001.ts\n", outText.Replace("u=https", "").Replace("%2F", "/")); // no raw relative URI left
    }

    [Fact]
    public void Detects_Playlists()
    {
        Assert.True(HlsPlaylistRewriter.LooksLikePlaylist("https://x/master.m3u8", null, ReadOnlySpan<byte>.Empty));
        Assert.True(HlsPlaylistRewriter.LooksLikePlaylist("https://x/pl", "application/vnd.apple.mpegurl", ReadOnlySpan<byte>.Empty));
        Assert.True(HlsPlaylistRewriter.LooksLikePlaylist("https://x/pl", "text/plain", "#EXTM3U\n#EXTINF:1,"u8.ToArray()));
        Assert.False(HlsPlaylistRewriter.LooksLikePlaylist("https://x/seg.ts", "video/mp2t", new byte[] { 0x47, 0x40 }));
    }
}

public class SignerTests
{
    [Fact]
    public void Sign_Validate_RoundTrip()
    {
        var s = new StreamSigner();
        var sig = s.Sign("https://x/a.m3u8", "aGVhZGVycw");
        Assert.True(s.Validate("https://x/a.m3u8", "aGVhZGVycw", sig));
        Assert.False(s.Validate("https://x/other.m3u8", "aGVhZGVycw", sig));
        Assert.False(s.Validate("https://x/a.m3u8", "b3RoZXI", sig));
        Assert.False(s.Validate("https://x/a.m3u8", "aGVhZGVycw", "deadbeef"));
        Assert.False(s.Validate(string.Empty, string.Empty, null));
    }
}

public class HeaderCodecTests
{
    [Fact]
    public void Headers_RoundTrip()
    {
        var headers = new Dictionary<string, string> { ["Referer"] = "https://a/", ["User-Agent"] = "UA 1.0" };
        var encoded = StreamSigner.EncodeHeaders(headers);
        var decoded = StreamSigner.DecodeHeaders(encoded);
        Assert.Equal(headers["Referer"], decoded["Referer"]);
        Assert.Equal(headers["User-Agent"], decoded["User-Agent"]);
    }

    [Fact]
    public void Empty_Headers_RoundTrip()
    {
        Assert.Equal(string.Empty, StreamSigner.EncodeHeaders(null));
        Assert.Empty(StreamSigner.DecodeHeaders(string.Empty));
        Assert.Empty(StreamSigner.DecodeHeaders("not-valid-b64!!!"));
    }
}
