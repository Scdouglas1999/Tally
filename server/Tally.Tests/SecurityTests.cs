using System.Net;
using System.Net.Http;
using System.Net.Sockets;
using System.Text;
using Jellyfin.Plugin.Tally;
using Jellyfin.Plugin.Tally.Api;
using Jellyfin.Plugin.Tally.Models;
using Jellyfin.Plugin.Tally.Services;
using Xunit;

namespace Tally.Tests;

/// <summary>What keeps a server that faces the internet safe: the Live TV feeds, the proxy's reach into the local
/// network, what the proxy lets a browser run, and what reaches logs and error messages.</summary>
public class SecurityTests
{
    private static readonly int[] NoPorts = Array.Empty<int>();

    // ------------------------------------------------------------------ Live TV feeds

    [Fact]
    public void Feeds_Answer_Only_Loopback_With_The_Key()
    {
        var signer = new StreamSigner();
        var key = LiveTvFeedController.FeedKey(signer);

        Assert.True(LiveTvFeedController.IsFeedRequestAllowed(IPAddress.Loopback, key, signer));
        Assert.True(LiveTvFeedController.IsFeedRequestAllowed(IPAddress.IPv6Loopback, key, signer));
        Assert.True(LiveTvFeedController.IsFeedRequestAllowed(IPAddress.Parse("::ffff:127.0.0.1"), key, signer));

        // the internet, even with the key; loopback without it (a reverse proxy on the same machine)
        Assert.False(LiveTvFeedController.IsFeedRequestAllowed(IPAddress.Parse("203.0.113.7"), key, signer));
        Assert.False(LiveTvFeedController.IsFeedRequestAllowed(IPAddress.Parse("192.168.1.20"), key, signer));
        Assert.False(LiveTvFeedController.IsFeedRequestAllowed(IPAddress.Loopback, null, signer));
        Assert.False(LiveTvFeedController.IsFeedRequestAllowed(IPAddress.Loopback, "", signer));
        Assert.False(LiveTvFeedController.IsFeedRequestAllowed(IPAddress.Loopback, key[..^1] + (key[^1] == 'a' ? 'b' : 'a'), signer));
        Assert.False(LiveTvFeedController.IsFeedRequestAllowed(null, key, signer));
    }

    [Fact]
    public void Feed_Key_Is_Not_A_Stream_Signature()
    {
        var signer = new StreamSigner();
        var key = LiveTvFeedController.FeedKey(signer);
        Assert.NotEqual(signer.Sign("live:redzone", string.Empty), key);
        Assert.False(signer.Validate("live:feed", string.Empty, key));
    }

    [Theory]
    [InlineData("http://127.0.0.1:8096/JellyTV/livetv.m3u", true)]
    [InlineData("http://127.0.0.1:8096/jellyfin/JellyTV/epg.xml", true)]
    [InlineData("http://localhost:8096/JellyTV/livetv.m3u", true)]
    [InlineData("http://[::1]:8096/JellyTV/livetv.m3u", true)]
    [InlineData("http://192.168.1.20:8096/JellyTV/livetv.m3u", false)] // never off this machine
    [InlineData("https://iptv.example.com/JellyTV/livetv.m3u", false)]
    [InlineData("http://127.0.0.1:8096/JellyTV/Live/redzone.m3u8", false)] // channel streams carry no key
    [InlineData("http://127.0.0.1:8096/System/Info", false)]
    public void Feed_Key_Header_Goes_Only_To_Loopback_Feeds(string url, bool expected)
        => Assert.Equal(expected, FeedKeyHandler.IsFeedRequest(new Uri(url)));

    [Fact]
    public async Task Feed_Key_Handler_Adds_The_Header_To_Feed_Reads_Only()
    {
        var seen = new List<(string Url, string? Key)>();
        using var client = new HttpClient(new FeedKeyHandler(() => "k1") { InnerHandler = new Recorder(seen) });
        await client.GetAsync("http://127.0.0.1:8096/JellyTV/livetv.m3u");
        await client.GetAsync("http://127.0.0.1:8096/JellyTV/Live/abc.m3u8?s=x");
        await client.GetAsync("https://example.com/JellyTV/epg.xml");
        Assert.Equal("k1", seen[0].Key);
        Assert.Null(seen[1].Key);
        Assert.Null(seen[2].Key);
    }

    private sealed class Recorder(List<(string Url, string? Key)> seen) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            seen.Add((request.RequestUri!.ToString(), request.Headers.TryGetValues(FeedKeyHandler.HeaderName, out var v) ? v.Single() : null));
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK));
        }
    }

    [Fact]
    public void Guide_Text_Drops_Characters_Xml_Cannot_Hold()
    {
        Assert.Equal("Chiefs at Bills", LiveTvFeedController.X("Chiefs\u0001 at\u0000 Bills"));
        Assert.Equal("Goal \U0001F3C8", LiveTvFeedController.X("Goal \U0001F3C8"));
        Assert.Equal("a<b & \"c\"", LiveTvFeedController.X("a<b & \"c\"")); // the writer escapes these
        Assert.Equal("lone", LiveTvFeedController.X("lo\uD800ne"));
        Assert.Equal(string.Empty, LiveTvFeedController.X(null));
    }

    // ------------------------------------------------------------------ network guard

    [Theory]
    [InlineData("169.254.169.254")]
    [InlineData("::ffff:169.254.169.254")]
    [InlineData("169.254.170.2")]
    [InlineData("100.100.100.200")]
    [InlineData("fd00:ec2::254")]
    [InlineData("0.0.0.0")]
    [InlineData("0.1.2.3")]
    [InlineData("224.0.0.1")]
    [InlineData("255.255.255.255")]
    [InlineData("::")]
    [InlineData("ff02::1")]
    public void Never_Connects_To_Metadata_Or_Unroutable(string ip)
    {
        var address = IPAddress.Parse(ip);
        Assert.True(NetworkGuard.IsForbidden(address));
        var trusted = new HashSet<string>(StringComparer.OrdinalIgnoreCase) { ip };
        Assert.False(NetworkGuard.Allows(address, ip, 80, trusted, new[] { 80 }));
    }

    [Theory]
    [InlineData("127.0.0.1")]
    [InlineData("127.8.9.10")]
    [InlineData("::1")]
    [InlineData("10.1.2.3")]
    [InlineData("172.16.0.1")]
    [InlineData("172.31.255.255")]
    [InlineData("192.168.1.1")]
    [InlineData("169.254.1.1")]
    [InlineData("100.64.0.1")]
    [InlineData("fe80::1")]
    [InlineData("fc00::1")]
    [InlineData("fd12:3456::1")]
    [InlineData("::ffff:192.168.1.1")]
    public void Internal_Addresses_Need_A_Trusted_Host(string ip)
    {
        var address = IPAddress.Parse(ip);
        Assert.True(NetworkGuard.IsInternal(address));
        Assert.False(NetworkGuard.IsForbidden(address));
        Assert.False(NetworkGuard.Allows(address, "evil.example.com", 80, new HashSet<string>(), NoPorts));
        Assert.True(NetworkGuard.Allows(address, "tuner.lan", 80, new HashSet<string>(StringComparer.OrdinalIgnoreCase) { "tuner.lan" }, NoPorts));
    }

    [Theory]
    [InlineData("8.8.8.8")]
    [InlineData("172.32.0.1")]
    [InlineData("100.128.0.1")]
    [InlineData("2606:4700::1111")]
    public void Public_Addresses_Are_Allowed(string ip)
    {
        var address = IPAddress.Parse(ip);
        Assert.False(NetworkGuard.IsInternal(address));
        Assert.True(NetworkGuard.Allows(address, "cdn.example.com", 443, new HashSet<string>(), NoPorts));
    }

    [Fact]
    public void Loopback_On_The_Servers_Own_Port_Is_The_Server()
    {
        Assert.True(NetworkGuard.Allows(IPAddress.Loopback, "127.0.0.1", 8096, new HashSet<string>(), new[] { 8096, 8920 }));
        Assert.False(NetworkGuard.Allows(IPAddress.Loopback, "127.0.0.1", 6379, new HashSet<string>(), new[] { 8096, 8920 }));
    }

    [Theory]
    [InlineData("192.168.1.50", true)]
    [InlineData("localhost", true)]
    [InlineData("hdhomerun", true)]
    [InlineData("hdhomerun.local", true)]
    [InlineData("nas.lan", true)]
    [InlineData("box.home.arpa", true)]
    [InlineData("[::1]", true)]
    [InlineData("iptv.example.com", false)]
    [InlineData("8.8.8.8", false)]
    public void Local_Host_Names(string host, bool local)
        => Assert.Equal(local, NetworkGuard.IsLocalHostName(host));

    [Fact]
    public void Trusts_Configured_Hosts_And_Streams_Of_Local_Sources_Only()
    {
        var publicM3u = new SourceDefinition { Name = "IPTV", Kind = SourceKind.M3u, PlaylistUrl = "http://iptv.example.com/get.php?username=u&password=p" };
        var localM3u = new SourceDefinition { Name = "HDHomeRun", Kind = SourceKind.M3u, PlaylistUrl = "http://192.168.1.50/lineup.m3u" };
        var direct = new SourceDefinition
        {
            Name = "Mine", Kind = SourceKind.Direct,
            Streams = { new DirectStream { Name = "Cam", Url = "http://camera.lan:8080/live.m3u8" } }
        };
        var channels = new[]
        {
            // a public playlist naming a local address: not trusted
            new SourceChannel { Id = "a", SourceId = publicM3u.Id.ToString("N"), SourceName = "IPTV", StreamUrl = "http://192.168.1.1/admin" },
            // the local tuner's streams on another local host: trusted
            new SourceChannel { Id = "b", SourceId = localM3u.Id.ToString("N"), SourceName = "HDHomeRun", StreamUrl = "http://192.168.1.51:5004/auto/v2" },
        };

        var trusted = NetworkGuard.TrustedHosts(new[] { publicM3u, localM3u, direct }, channels);

        Assert.Contains("iptv.example.com", trusted);
        Assert.Contains("192.168.1.50", trusted);
        Assert.Contains("192.168.1.51", trusted);
        Assert.Contains("camera.lan", trusted);
        Assert.DoesNotContain("192.168.1.1", trusted);
    }

    [Fact]
    public async Task Guarded_Client_Refuses_Untrusted_Local_Addresses()
    {
        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;
        var serving = Task.Run(async () =>
        {
            while (true)
            {
                using var client = await listener.AcceptTcpClientAsync();
                var stream = client.GetStream();
                var buffer = new byte[4096];
                _ = await stream.ReadAsync(buffer);
                var body = "secret"u8.ToArray();
                await stream.WriteAsync(Encoding.ASCII.GetBytes($"HTTP/1.1 200 OK\r\nContent-Length: {body.Length}\r\nConnection: close\r\n\r\n"));
                await stream.WriteAsync(body);
            }
        });

        var trusted = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        using var http = new HttpClient(new SocketsHttpHandler
        {
            ConnectCallback = NetworkGuard.ConnectCallback((host, address, p) => NetworkGuard.Allows(address, host, p, trusted, NoPorts)),
            UseProxy = false
        });

        var refused = await Assert.ThrowsAsync<HttpRequestException>(() => http.GetStringAsync($"http://127.0.0.1:{port}/"));
        Assert.Contains("127.0.0.1", refused.Message + refused.InnerException?.Message);

        trusted.Add("127.0.0.1");
        Assert.Equal("secret", await http.GetStringAsync($"http://127.0.0.1:{port}/"));
        listener.Stop();
    }

    // ------------------------------------------------------------------ proxy responses

    [Theory]
    [InlineData("text/html", "application/octet-stream")]
    [InlineData("text/html; charset=utf-8", "application/octet-stream")]
    [InlineData("image/svg+xml", "application/octet-stream")]
    [InlineData("application/javascript", "application/octet-stream")]
    [InlineData("application/xhtml+xml", "application/octet-stream")]
    [InlineData("text/xml", "application/octet-stream")]
    [InlineData(null, "application/octet-stream")]
    [InlineData("", "application/octet-stream")]
    [InlineData("video/mp2t", "video/mp2t")]
    [InlineData("Video/MP2T", "video/mp2t")]
    [InlineData("audio/aac", "audio/aac")]
    [InlineData("application/vnd.apple.mpegurl; charset=utf-8", "application/vnd.apple.mpegurl")]
    [InlineData("text/vtt", "text/vtt")]
    [InlineData("image/jpeg", "image/jpeg")]
    [InlineData("binary/octet-stream", "binary/octet-stream")]
    public void Proxy_Never_Serves_Upstream_Pages_As_Pages(string? upstream, string served)
        => Assert.Equal(served, ProxyController.SafeContentType(upstream));

    [Theory]
    [InlineData("Referer", "https://site.example/", true)]
    [InlineData("Cookie", "a=b; c=d", true)]
    [InlineData("X-Evil", "a\r\nHost: internal", false)]
    [InlineData("X-Evil", "a\nb", false)]
    [InlineData("Bad Name", "x", false)]
    [InlineData("Bad:Name", "x", false)]
    [InlineData("Content-Length", "0", false)]
    [InlineData("Transfer-Encoding", "chunked", false)]
    [InlineData("", "x", false)]
    public void Source_Headers_Cannot_Split_Requests(string name, string value, bool ok)
        => Assert.Equal(ok, UpstreamFetcher.IsSafeHeader(name, value));

    // ------------------------------------------------------------------ secrets in logs and messages

    [Theory]
    [InlineData("http://iptv.example.com:8080/get.php?username=me&password=hunter2&type=m3u_plus", "http://iptv.example.com:8080/get.php?…")]
    [InlineData("https://me:hunter2@iptv.example.com/list.m3u", "https://iptv.example.com/list.m3u?…")]
    [InlineData("https://example.com/epg.xml.gz", "https://example.com/epg.xml.gz")]
    [InlineData("not a url", "(address hidden)")]
    [InlineData("", "")]
    public void Addresses_In_Logs_Lose_Their_Credentials(string url, string shown)
    {
        var redacted = Redact.Url(url);
        Assert.Equal(shown, redacted);
        Assert.DoesNotContain("hunter2", redacted);
    }

    // ------------------------------------------------------------------ install page

    [Theory]
    [InlineData("https://tv.example.com", "https://tv.example.com")]
    [InlineData("http://tv.example.com:8096/jellyfin/", "http://tv.example.com:8096/jellyfin")]
    [InlineData("javascript:alert(document.domain)//", null)]
    [InlineData("data:text/html,<script>alert(1)</script>", null)]
    [InlineData("tv.example.com", null)]
    [InlineData("", null)]
    [InlineData(null, null)]
    public void Public_Address_Must_Be_Http(string? configured, string? used)
        => Assert.Equal(used, GetController.PublicAddress(configured));
}

public class TallyUsersTests
{
    [Theory]
    [InlineData(true, false, false, null)]
    [InlineData(true, true, false, null)]
    [InlineData(false, true, false, null)]
    [InlineData(false, false, false, 403)]
    [InlineData(false, false, true, 404)]
    public void Non_Admins_Are_Refused_When_The_Setting_Is_Off(bool allowNonAdmin, bool isAdmin, bool probe, int? status)
        => Assert.Equal(status, Jellyfin.Plugin.Tally.Api.TallyUsersAttribute.Refusal(allowNonAdmin, isAdmin, probe));
}
