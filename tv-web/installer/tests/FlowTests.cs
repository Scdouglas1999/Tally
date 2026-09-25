using System.Net;
using System.Net.Sockets;
using System.Text;
using Tally.SamsungInstaller.Certificates;
using Tally.SamsungInstaller.Signing;
using Tally.SamsungInstaller.Tv;
using Xunit;

namespace Tally.SamsungInstaller.Tests;

public class ScannerTests
{
    // what a 2020 Samsung TV answers at :8001/api/v2/ (fields as Samsung TVs report them; values made up)
    internal const string TvInfo = """
        {"device":{"FrameTVSupport":"false","GamePadSupport":"true","ImeSyncedSupport":"true","Language":"en_US","OS":"Tizen",
        "PowerState":"on","TokenAuthSupport":"true","VoiceSupport":"true","WallScreenRatio":"0","WallService":"false",
        "countryCode":"US","description":"Samsung DTV RCR","developerIP":"192.168.1.20","developerMode":"1",
        "duid":"uuid:00000000-0000-0000-0000-000000000000","firmwareVersion":"Unknown","id":"uuid:00000000-0000-0000-0000-000000000000",
        "ip":"192.168.1.40","model":"20_KANTM2_UHD","modelName":"UN55TU8000","name":"[TV] Samsung 8 Series (55)",
        "networkType":"wireless","resolution":"3840x2160","smartHubAgreement":"true","type":"Samsung SmartTV",
        "udn":"uuid:00000000-0000-0000-0000-000000000000","wifiMac":"00:00:00:00:00:00"},
        "id":"uuid:00000000-0000-0000-0000-000000000000","isSupport":"{}","name":"[TV] Samsung 8 Series (55)","remote":"1.0",
        "type":"Samsung SmartTV","uri":"http://192.168.1.40:8001/api/v2/","version":"2.0.25"}
        """;

    [Fact]
    public void ReadsSamsungsTvInformation()
    {
        var tv = TvScanner.ParseInfo(IPAddress.Parse("192.168.1.40"), TvInfo)!;
        Assert.Equal("[TV] Samsung 8 Series (55)", tv.Name);
        Assert.Equal("UN55TU8000", tv.ModelName);
        Assert.Equal(2020, tv.ModelYear);
        Assert.True(tv.DeveloperMode);
        Assert.Equal("192.168.1.20", tv.DeveloperIp);
        Assert.Equal("Samsung 8 Series (55), UN55TU8000, 2020 model", tv.Label);
        Assert.Null(TvScanner.ParseInfo(IPAddress.Loopback, "{\"device\":{\"type\":\"Chromecast\"}}"));
        Assert.Null(TvScanner.ParseInfo(IPAddress.Loopback, "not json"));
    }

    [Fact]
    public async Task FindsTheTvAmongOtherAddresses()
    {
        await using var sdbd = new FakeSdbd();
        var info = new TcpListener(IPAddress.Loopback, 0);
        info.Start();
        var infoPort = ((IPEndPoint)info.LocalEndpoint).Port;
        _ = Task.Run(async () =>
        {
            while (true)
            {
                using var c = await info.AcceptTcpClientAsync();
                var s = c.GetStream();
                var buf = new byte[4096];
                _ = await s.ReadAsync(buf);
                var body = Encoding.UTF8.GetBytes(TvInfo);
                await s.WriteAsync(Encoding.ASCII.GetBytes($"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {body.Length}\r\nConnection: close\r\n\r\n"));
                await s.WriteAsync(body);
            }
        });

        using var http = new HttpClient();
        var scanner = new TvScanner(http) { SdbPort = sdbd.Port, InfoPort = infoPort, ConnectTimeout = TimeSpan.FromMilliseconds(300), InfoTimeout = TimeSpan.FromMilliseconds(600) };
        // 127.0.0.1 answers both; 127.0.0.2-.4 are other loopback addresses where nothing listens on those ports
        var hosts = new[] { "127.0.0.2", "127.0.0.1", "127.0.0.3", "127.0.0.4" }.Select(IPAddress.Parse);
        var found = await scanner.ScanAsync(hosts, CancellationToken.None);
        var tv = Assert.Single(found);
        Assert.Equal(IPAddress.Loopback, tv.Address);
        Assert.True(tv.SdbPortOpen);
        Assert.Equal("UN55TU8000", tv.ModelName);
        info.Stop();
    }

    [Fact]
    public void ScansTheHomeNetworkAroundThisPc()
    {
        var hosts = TvScanner.Slash24(IPAddress.Parse("192.168.1.20")).ToList();
        Assert.Equal(254, hosts.Count);
        Assert.Equal("192.168.1.1", hosts[0].ToString());
        Assert.Equal("192.168.1.254", hosts[^1].ToString());
        Assert.True(TvScanner.IsPrivate(IPAddress.Parse("10.0.0.5")));
        Assert.True(TvScanner.IsPrivate(IPAddress.Parse("172.20.1.5")));
        Assert.False(TvScanner.IsPrivate(IPAddress.Parse("172.32.1.5")));
        Assert.False(TvScanner.IsPrivate(IPAddress.Parse("8.8.8.8")));
        Assert.Equal(IPAddress.Loopback, TvScanner.LocalAddressFor(IPAddress.Loopback));
    }
}

public class ServerTests
{
    [Theory]
    [InlineData("192.168.1.10", new[] { "https://192.168.1.10", "http://192.168.1.10:8096", "http://192.168.1.10" })]
    [InlineData("192.168.1.10:8096", new[] { "http://192.168.1.10:8096", "https://192.168.1.10:8096" })]
    [InlineData("jellyfin.example.com", new[] { "https://jellyfin.example.com", "http://jellyfin.example.com:8096", "http://jellyfin.example.com" })]
    [InlineData("https://jellyfin.example.com/", new[] { "https://jellyfin.example.com" })]
    [InlineData("http://192.168.1.10", new[] { "http://192.168.1.10:8096", "http://192.168.1.10" })]
    [InlineData("http://192.168.1.10:8920", new[] { "http://192.168.1.10:8920" })]
    [InlineData("example.com/jellyfin", new[] { "https://example.com/jellyfin", "http://example.com:8096/jellyfin", "http://example.com/jellyfin" })]
    [InlineData("  ", new string[0])]
    public void TriesTheAddressesTheShellWould(string typed, string[] expected) =>
        Assert.Equal(expected, JellyfinServer.Candidates(typed));

    [Fact]
    public async Task ChecksJellyfinAndTheTvApp()
    {
        var handler = new RecordingHandler(r => r.Url switch
        {
            "http://192.168.1.10:8096/System/Info/Public" => Json("{\"LocalAddress\":\"http://192.168.1.10:8096\",\"ServerName\":\"Den\",\"Version\":\"10.10.6\",\"ProductName\":\"Jellyfin Server\",\"OperatingSystem\":\"\",\"Id\":\"abc\",\"StartupWizardCompleted\":true}"),
            "http://192.168.1.10:8096/JellyTV/TV/manifest.json" => Json("{\"js\":\"app.js\"}"),
            _ => throw new HttpRequestException("no route", null, null),
        });
        var (server, problems) = await new JellyfinServer(new HttpClient(handler)).CheckAsync("192.168.1.10", CancellationToken.None);
        Assert.NotNull(server);
        Assert.Equal("http://192.168.1.10:8096", server!.Address);
        Assert.Equal("Den", server.Name);
        Assert.Equal("10.10.6", server.Version);
        Assert.True(server.HasTvApp);
        Assert.Single(problems); // https:// was tried first
    }

    [Fact]
    public async Task RefusesSomethingThatIsNotJellyfin()
    {
        var handler = new RecordingHandler(_ => Json("<html>router</html>"));
        var (server, problems) = await new JellyfinServer(new HttpClient(handler)).CheckAsync("http://192.168.1.1:8096", CancellationToken.None);
        Assert.Null(server);
        Assert.Contains("not a Jellyfin server", Assert.Single(problems), StringComparison.Ordinal);
    }

    internal static HttpResponseMessage Json(string body) =>
        new(HttpStatusCode.OK) { Content = new StringContent(body, Encoding.UTF8, "application/json") };
}

/// <summary>The whole program, with the fake TV and a fake Jellyfin, answering as the person would.</summary>
public class FlowTests
{
    // failures show everything the person saw
    private static void Has(string text, string part) => Assert.True(Flat(text).Contains(Flat(part), StringComparison.Ordinal), $"missing \"{part}\" in:\n{text}");

    private static string Flat(string text) => System.Text.RegularExpressions.Regex.Replace(text, "\\s+", " ");

    private static void HasNot(string text, string part) => Assert.False(Flat(text).Contains(Flat(part), StringComparison.Ordinal), $"unexpected \"{part}\" in:\n{text}");

    private static RecordingHandler Jellyfin(bool tvApp = true) => new(r => r.Url switch
    {
        "http://192.0.2.10:8096/System/Info/Public" => ServerTests.Json("{\"ServerName\":\"Den\",\"Version\":\"10.10.6\",\"Id\":\"abc\"}"),
        "http://192.0.2.10:8096/JellyTV/TV/manifest.json" when tvApp => ServerTests.Json("{}"),
        _ => new HttpResponseMessage(HttpStatusCode.NotFound),
    });

    private static (InstallerFlow Flow, StringWriter Output, CertificateStore Store) Make(FakeSdbd tv, string input, Options? options = null,
        RecordingHandler? jellyfin = null, CertificateStore? store = null)
    {
        var output = new StringWriter();
        var ui = new Ui(new StringReader(input), output, color: false);
        store ??= new CertificateStore(TestDirs.New(), TestTizen.Defaults);
        var flow = new InstallerFlow(ui, options ?? new Options { Tv = "127.0.0.1", SdbPort = tv.Port }, new HttpClient(jellyfin ?? Jellyfin()), store)
        {
            OpenBrowser = _ => throw new InvalidOperationException("no browser in tests"),
            Networks = () => [],
        };
        return (flow, output, store);
    }

    [Fact]
    public async Task InstallsOnA2021Tv()
    {
        await using var tv = new FakeSdbd { PlatformVersion = "6.0" };
        // the person types the server address and closes nothing else
        var (flow, output, store) = Make(tv, "192.0.2.10:8096\n");
        var code = await flow.RunAsync(CancellationToken.None);
        var text = output.ToString();
        Assert.True(code == 0, text);
        Has(text, "Tizen 6.0, a 2021 TV");
        Has(text, "Found Den (Jellyfin 10.10.6) at http://192.0.2.10:8096");
        Has(text, "Tally is installed");
        Has(text, "Quick Connect");
        Assert.Contains("shell:0 was_execute TallyTVapp.Tally", tv.Services);

        // the package on the TV: signed by this PC's author certificate, the address stamped in
        var files = ShellPackage.Unzip(tv.Files["/home/owner/share/tmp/sdk_tools/tmp/Tally.wgt"]);
        Assert.Contains("\"server\": \"http://192.0.2.10:8096\"", Encoding.UTF8.GetString(files["config.js"]), StringComparison.Ordinal);
        Assert.True(store.HasTizenAuthor);
        var author = Encoding.UTF8.GetString(files[WidgetSigner.AuthorSignatureFile]);
        Assert.Contains(Convert.ToBase64String(store.TizenAuthor().Certificate.RawData)[..60], author.Replace("\n", "", StringComparison.Ordinal), StringComparison.Ordinal);
    }

    [Fact]
    public async Task FindsTheTvByItself()
    {
        await using var tv = new FakeSdbd();
        var output = new StringWriter();
        var ui = new Ui(new StringReader("\n192.0.2.10:8096\n"), output, color: false);
        var store = new CertificateStore(TestDirs.New(), TestTizen.Defaults);
        var flow = new InstallerFlow(ui, new Options { SdbPort = tv.Port, NoLaunch = true }, new HttpClient(Jellyfin()), store)
        {
            Networks = () => [(IPAddress.Parse("127.0.0.1"), [IPAddress.Parse("127.0.0.3"), IPAddress.Loopback])],
            TvInfoPort = 1, // nothing answers Samsung's TV information here
        };
        Assert.True(await flow.RunAsync(CancellationToken.None) == 0, output.ToString());
        var text = output.ToString();
        Has(text, "This PC's address (what Developer Mode's \"Host PC IP\" must be): 127.0.0.1");
        Has(text, "1  a device with Developer Mode's port open, 127.0.0.1");
        Assert.Equal(1, tv.Installs);
        Assert.DoesNotContain("shell:0 was_execute TallyTVapp.Tally", tv.Services); // --no-launch
    }

    [Fact]
    public async Task UpdatesWithTheSameAuthorCertificate()
    {
        await using var tv = new FakeSdbd();
        var store = new CertificateStore(TestDirs.New(), TestTizen.Defaults);
        var options = new Options { Tv = "127.0.0.1", SdbPort = tv.Port, Server = "192.0.2.10:8096", Yes = true };
        var (first, _, _) = Make(tv, "", options, store: store);
        Assert.Equal(0, await first.RunAsync(CancellationToken.None));
        var firstCert = SignerCertificate(tv);
        var (second, _, _) = Make(tv, "", options, store: store);
        Assert.Equal(0, await second.RunAsync(CancellationToken.None));
        Assert.Equal(firstCert, SignerCertificate(tv));
        Assert.Equal(2, tv.Installs);
    }

    [Fact]
    public async Task OffersToReplaceTallyFromAnotherComputer()
    {
        await using var tv = new FakeSdbd();
        var attempts = 0;
        tv.InstallOutput = id => ++attempts == 1
            ? $"app_id[{id}] install failed[118, -12], reason: Check certificate error : :Author certificate not match :<-12>\n"
            : $"app_id[{id}] install completed\n";
        var (flow, output, _) = Make(tv, "192.0.2.10:8096\ny\n");
        Assert.Equal(0, await flow.RunAsync(CancellationToken.None));
        Has(output.ToString(), "installed from another computer");
        Assert.Contains("shell:0 vd_appuninstall TallyTVapp.Tally", tv.Services);
        Assert.Equal(2, tv.Installs);
    }

    [Fact]
    public async Task ExplainsATvThatRefusesThisPc()
    {
        await using var tv = new FakeSdbd(FakeSdbd.Mode.HangUp);
        var (flow, output, _) = Make(tv, "q\n");
        Assert.Equal(1, await flow.RunAsync(CancellationToken.None));
        var text = output.ToString();
        Has(text, "Your TV is not in Developer Mode, or the IP in Developer Mode is not this PC's (127.0.0.1)");
        Has(text, "type 1 2 3 4 5");
        Has(text, "Restart the TV fully");
    }

    [Fact]
    public async Task A2024TvOffersSamsungSignInOrAFile()
    {
        await using var tv = new FakeSdbd { PlatformVersion = "8.0", Duid = "NEWTV000000001" };
        // choose "use a file", which was made for another TV
        var other = MakeSamsungWgt("OTHERTV0000001");
        var path = Path.Combine(TestDirs.New(), "Tally.wgt");
        await File.WriteAllBytesAsync(path, other);
        // a stray answer ("y") asks again instead of opening Samsung's sign-in
        var (flow, output, _) = Make(tv, $"192.0.2.10:8096\ny\n2\n{path}\n");
        Assert.Equal(1, await flow.RunAsync(CancellationToken.None));
        var text = output.ToString();
        Has(text, "Type 1 (Samsung account) or 2 (a Tally.wgt file)");
        Has(text, "only installs apps signed with a Samsung certificate");
        Has(text, "NEWTV000000001");
        Has(text, "was made for another TV (OTHERTV0000001)");
        Assert.Equal(0, tv.Installs);
    }

    [Fact]
    public async Task InstallsAFileMadeForThisTv()
    {
        await using var tv = new FakeSdbd { PlatformVersion = "9.0", Duid = "NEWTV000000001" };
        var path = Path.Combine(TestDirs.New(), "Tally.wgt");
        var wgt = MakeSamsungWgt("NEWTV000000001");
        await File.WriteAllBytesAsync(path, wgt);
        var (flow, output, _) = Make(tv, "", new Options { Tv = "127.0.0.1", SdbPort = tv.Port, Wgt = path, Yes = true });
        Assert.True(await flow.RunAsync(CancellationToken.None) == 0, output.ToString());
        Has(output.ToString(), "A Tally package made for this TV, for the server http://192.0.2.10:8096");
        Assert.Equal(wgt, tv.Files["/home/owner/share/tmp/sdk_tools/tmp/Tally.wgt"]);
    }

    [Fact]
    public async Task WritesATizenSignedPackageWithoutATv()
    {
        await using var tv = new FakeSdbd();
        var output = Path.Combine(TestDirs.New(), "Tally.wgt");
        var (flow, text, _) = Make(tv, "", new Options { PackageOnly = true, Server = "192.0.2.10:8096", Out = output, Yes = true });
        Assert.Equal(0, await flow.RunAsync(CancellationToken.None));
        Assert.True(File.Exists(output), text.ToString());
        Assert.Contains(WidgetSigner.DistributorSignatureFile, ShellPackage.Unzip(await File.ReadAllBytesAsync(output)).Keys);
    }

    [Fact]
    public async Task WarnsWhenTheServerHasNoTvApp()
    {
        await using var tv = new FakeSdbd();
        var (flow, output, _) = Make(tv, "192.0.2.10:8096\nn\n", jellyfin: Jellyfin(tvApp: false));
        Assert.Equal(1, await flow.RunAsync(CancellationToken.None));
        Has(output.ToString(), "does not have Tally's TV app yet");
        Assert.Equal(0, tv.Installs);
    }

    [Fact]
    public void KnowsTheModelYears()
    {
        Assert.Equal(2020, InstallerFlow.TizenYear(5, "5.5"));
        Assert.Equal(2022, InstallerFlow.TizenYear(6, "6.5"));
        Assert.Equal(2024, InstallerFlow.TizenYear(8, "8.0"));
        Assert.Null(InstallerFlow.TizenYear(4, "4.0"));
    }

    [Fact]
    public void ParsesOptions()
    {
        var o = Options.Parse(["--tv", "192.168.1.40", "--server", "https://jf.example.com", "--yes", "--no-launch"]);
        Assert.Equal("192.168.1.40", o.Tv);
        Assert.Equal("https://jf.example.com", o.Server);
        Assert.True(o.Yes && o.NoLaunch);
        Assert.Throws<ArgumentException>(() => Options.Parse(["--bogus"]));
        Assert.Throws<ArgumentException>(() => Options.Parse(["--tv"]));
    }

    private static byte[] SignerCertificate(FakeSdbd tv)
    {
        var files = ShellPackage.Unzip(tv.Files["/home/owner/share/tmp/sdk_tools/tmp/Tally.wgt"]);
        var xml = Encoding.UTF8.GetString(files[WidgetSigner.AuthorSignatureFile]);
        var first = System.Text.RegularExpressions.Regex.Match(xml, "<X509Certificate>([^<]*)</X509Certificate>").Groups[1].Value;
        return Convert.FromBase64String(first.Replace("\n", "", StringComparison.Ordinal));
    }

    /// <summary>A package signed as the Samsung path signs (a fake Samsung CA; the TV is not asked here).</summary>
    private static byte[] MakeSamsungWgt(string duid)
    {
        var (ca, caKey) = SamsungTests.FakeCa("VD DEVELOPER Public CA Class");
        using var key = System.Security.Cryptography.RSA.Create(2048);
        var req = new System.Security.Cryptography.X509Certificates.CertificateRequest("CN=TizenSDK", key,
            System.Security.Cryptography.HashAlgorithmName.SHA512, System.Security.Cryptography.RSASignaturePadding.Pkcs1);
        req.CertificateExtensions.Add(CertificateTools.UriSubjectAltName(["URN:tizen:packageid=", "URN:tizen:deviceid=" + duid]));
        using var cert = req.Create(ca.SubjectName,
            System.Security.Cryptography.X509Certificates.X509SignatureGenerator.CreateForRSA(caKey, System.Security.Cryptography.RSASignaturePadding.Pkcs1),
            DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(1), [1, 2, 3, 4]);
        var distributor = new SigningIdentity(key, System.Security.Cryptography.X509Certificates.X509CertificateLoader.LoadCertificate(cert.RawData), [ca]);
        return ShellPackage.BuildSigned("http://192.0.2.10:8096", SignerTests.TestAuthor(), distributor);
    }
}
