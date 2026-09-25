using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using Tally.SamsungInstaller.Certificates;
using Tally.SamsungInstaller.Tv;
using Xunit;

namespace Tally.SamsungInstaller.Tests;

/// <summary>
/// Tizen's public signing material is downloaded (download.tizen.org, pinned SHA-256) instead of carried in the
/// program. Set TALLY_NETWORK_TESTS=1 to also fetch the real package and compare it with the fixtures.
/// </summary>
public class TizenDownloadTests
{
    private static HttpClient Serving(byte[] package, TizenSdkSource source, List<string>? asked = null) => new(new RecordingHandler(r =>
    {
        asked?.Add(r.Url);
        return r.Url == source.Url
            ? new HttpResponseMessage(HttpStatusCode.OK) { Content = new ByteArrayContent(package) }
            : new HttpResponseMessage(HttpStatusCode.NotFound);
    }));

    private sealed class Offline : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) =>
            throw new HttpRequestException("offline", new SocketException((int)SocketError.HostNotFound));
    }

    [Fact]
    public void TheProgramCarriesNoTizenKeys()
    {
        var names = typeof(TizenDefaults).Assembly.GetManifestResourceNames();
        Assert.DoesNotContain(names, n => n.StartsWith("certificates/tizen/", StringComparison.Ordinal));
        foreach (var name in names)
        {
            using var stream = typeof(TizenDefaults).Assembly.GetManifestResourceStream(name)!;
            using var reader = new StreamReader(stream, Encoding.Latin1);
            var text = reader.ReadToEnd();
            Assert.DoesNotContain("PRIVATE KEY", text, StringComparison.Ordinal);
        }
    }

    [Fact]
    public void TheOfficialPackageIsPinned()
    {
        var s = TizenSdkSource.Official;
        Assert.StartsWith("https://download.tizen.org/", s.Url, StringComparison.Ordinal);
        Assert.Equal(64, s.Sha256.Length);
    }

    [Fact]
    public async Task DownloadsOnceThenUsesTheSavedCopy()
    {
        var package = TestTizen.Package();
        var source = TestTizen.SourceFor(package);
        var dir = TestDirs.New();
        var asked = new List<string>();
        var said = new List<string>();
        var store = new CertificateStore(dir) { TizenSource = source };
        var d = await store.LoadTizenDefaultsAsync(Serving(package, source, asked), said.Add, CancellationToken.None);
        Assert.Equal([source.Url], asked);
        Assert.Contains(said, s => s.Contains("download.tizen.org", StringComparison.Ordinal));
        Assert.Equal(TestTizen.Defaults.DeveloperCa.RawData, d.DeveloperCa.RawData);
        Assert.Equal(TestTizen.Defaults.Distributor.Certificate.RawData, d.Distributor.Certificate.RawData);
        Assert.Equal(d.Distributor.Certificate.GetRSAPublicKey()!.ExportSubjectPublicKeyInfo(), d.Distributor.Key.ExportSubjectPublicKeyInfo());
        Assert.True(File.Exists(Path.Combine(dir, source.FileName)));

        // a later run, without a network: the saved copy
        var again = new CertificateStore(dir) { TizenSource = source };
        var d2 = await again.LoadTizenDefaultsAsync(new HttpClient(new Offline()), null, CancellationToken.None);
        Assert.Equal(d.DeveloperCa.RawData, d2.DeveloperCa.RawData);
        Assert.True(again.TizenAuthor().Certificate.Issuer.Contains("Tizen Developers CA", StringComparison.Ordinal));
    }

    [Fact]
    public async Task RefusesAFileWithAnotherHash()
    {
        var source = TestTizen.SourceFor(TestTizen.Package());
        var dir = TestDirs.New();
        var store = new CertificateStore(dir) { TizenSource = source };
        var ex = await Assert.ThrowsAsync<TizenSdkDownloadException>(() =>
            store.LoadTizenDefaultsAsync(Serving(TestTizen.Package(withKey: false), source), null, CancellationToken.None));
        Assert.Contains("SHA-256 differs", ex.Message, StringComparison.Ordinal);
        Assert.False(File.Exists(Path.Combine(dir, source.FileName)));
        Assert.Throws<InvalidOperationException>(() => store.Defaults);
    }

    [Fact]
    public async Task ReplacesADamagedSavedCopy()
    {
        var package = TestTizen.Package();
        var source = TestTizen.SourceFor(package);
        var dir = TestDirs.New();
        await File.WriteAllBytesAsync(Path.Combine(dir, source.FileName), [1, 2, 3]);
        var asked = new List<string>();
        var store = new CertificateStore(dir) { TizenSource = source };
        await store.LoadTizenDefaultsAsync(Serving(package, source, asked), null, CancellationToken.None);
        Assert.Single(asked);
        Assert.Equal(package, await File.ReadAllBytesAsync(Path.Combine(dir, source.FileName)));
    }

    [Fact]
    public async Task OfflineSaysWhatToDo()
    {
        var store = new CertificateStore(TestDirs.New()) { TizenSource = TestTizen.SourceFor(TestTizen.Package()) };
        var ex = await Assert.ThrowsAsync<TizenSdkDownloadException>(() =>
            store.LoadTizenDefaultsAsync(new HttpClient(new Offline()), null, CancellationToken.None));
        Assert.Contains("could not be looked up", ex.Message, StringComparison.Ordinal);
        Assert.Contains("Connect this PC to the internet and run the program again", ex.Message, StringComparison.Ordinal);
    }

    [Fact]
    public async Task TheInstallerStopsWithTheReasonWhenOffline()
    {
        await using var tv = new FakeSdbd { PlatformVersion = "6.0" };
        var output = new StringWriter();
        var ui = new Ui(new StringReader("192.0.2.10:8096\n"), output, color: false);
        var store = new CertificateStore(TestDirs.New()) { TizenSource = TestTizen.SourceFor(TestTizen.Package()) };
        // Jellyfin answers (it is on the LAN); the internet does not
        var handler = new RecordingHandler(r => r.Url switch
        {
            "http://192.0.2.10:8096/System/Info/Public" => ServerTests.Json("{\"ServerName\":\"Den\",\"Version\":\"10.10.6\",\"Id\":\"abc\"}"),
            "http://192.0.2.10:8096/JellyTV/TV/manifest.json" => ServerTests.Json("{}"),
            _ => throw new HttpRequestException("offline", new SocketException((int)SocketError.HostNotFound)),
        });
        var flow = new InstallerFlow(ui, new Options { Tv = "127.0.0.1", SdbPort = tv.Port }, new HttpClient(handler), store)
        {
            Networks = () => [],
        };
        Assert.Equal(1, await flow.RunAsync(CancellationToken.None));
        var text = output.ToString().Replace("\n      ", " ", StringComparison.Ordinal);
        Assert.Contains("could not download Tizen's signing certificates", text, StringComparison.Ordinal);
        Assert.DoesNotContain("shell:0 vd_appinstall", string.Join('\n', tv.Services), StringComparison.Ordinal);
    }

    [Fact]
    public async Task TheInstallerDownloadsOnFirstUse()
    {
        await using var tv = new FakeSdbd { PlatformVersion = "6.5" };
        var package = TestTizen.Package();
        var source = TestTizen.SourceFor(package);
        var output = new StringWriter();
        var ui = new Ui(new StringReader("192.0.2.10:8096\n"), output, color: false);
        var store = new CertificateStore(TestDirs.New()) { TizenSource = source };
        var handler = new RecordingHandler(r => r.Url switch
        {
            "http://192.0.2.10:8096/System/Info/Public" => ServerTests.Json("{\"ServerName\":\"Den\",\"Version\":\"10.10.6\",\"Id\":\"abc\"}"),
            "http://192.0.2.10:8096/JellyTV/TV/manifest.json" => ServerTests.Json("{}"),
            _ when r.Url == source.Url => new HttpResponseMessage(HttpStatusCode.OK) { Content = new ByteArrayContent(package) },
            _ => new HttpResponseMessage(HttpStatusCode.NotFound),
        });
        var flow = new InstallerFlow(ui, new Options { Tv = "127.0.0.1", SdbPort = tv.Port, NoLaunch = true }, new HttpClient(handler), store)
        {
            Networks = () => [],
        };
        Assert.True(await flow.RunAsync(CancellationToken.None) == 0, output.ToString());
        Assert.Contains("Downloading Tizen's public signing certificates", output.ToString(), StringComparison.Ordinal);
        var files = ShellPackage.Unzip(tv.Files["/home/owner/share/tmp/sdk_tools/tmp/Tally.wgt"]);
        Assert.True(files.ContainsKey("signature1.xml"));
        Assert.Contains("Tizen Public Distributor Signer", store.TizenDistributor().Certificate.Subject, StringComparison.Ordinal);
    }

    [Fact]
    public async Task TheRealPackageHoldsTheSameMaterial()
    {
        if (Environment.GetEnvironmentVariable("TALLY_NETWORK_TESTS") != "1")
        {
            return;
        }

        using var http = new HttpClient();
        var d = await TizenSdkDownload.LoadAsync(TestDirs.New(), http, TizenSdkSource.Official, null, CancellationToken.None);
        Assert.Equal(TestTizen.Defaults.DeveloperCa.RawData, d.DeveloperCa.RawData);
        Assert.Equal(TestTizen.Defaults.DeveloperCaKey.ExportRSAPrivateKey(), d.DeveloperCaKey.ExportRSAPrivateKey());
        Assert.Equal(TestTizen.Defaults.Distributor.Certificate.RawData, d.Distributor.Certificate.RawData);
        Assert.Equal(TestTizen.Defaults.Distributor.Key.ExportRSAPrivateKey(), d.Distributor.Key.ExportRSAPrivateKey());
    }
}
