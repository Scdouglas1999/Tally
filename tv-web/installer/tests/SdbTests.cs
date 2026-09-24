using System.Text;
using Tally.SamsungInstaller.Sdb;
using Tally.SamsungInstaller.Tv;
using Xunit;

namespace Tally.SamsungInstaller.Tests;

public class SdbTests
{
    [Fact]
    public void EncodesAsTizenStudiosSdb()
    {
        // the first message of the recorded conversation: CNXN a0=0x100000 a1=0x40000 len=7 chk=0x232 "host::\0"
        var bytes = SdbProtocol.Encode(SdbProtocol.Cnxn, SdbProtocol.HostVersion, SdbProtocol.HostMaxPayload, SdbProtocol.CString("host::"));
        Assert.Equal(24 + 7, bytes.Length);
        Assert.Equal("CNXN", Encoding.ASCII.GetString(bytes, 0, 4));
        Assert.Equal(0x232u, BitConverter.ToUInt32(bytes, 16));
        Assert.Equal(SdbProtocol.Cnxn ^ 0xffffffff, BitConverter.ToUInt32(bytes, 20));
        var trace = File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "fixtures", "sdb-trace-emulator.txt"));
        Assert.Contains("H>D CNXN a0=0x100000 a1=0x40000 len=7 chk=0x232 magic_ok=True data=b'host::\\x00'", trace, StringComparison.Ordinal);
    }

    [Fact]
    public async Task ConnectsReadsCapabilityAndDuid()
    {
        await using var tv = new FakeSdbd { PlatformVersion = "6.5", Duid = "ABCDEF1234567" };
        await using var sdb = await SdbClient.ConnectAsync("127.0.0.1", tv.Port);
        Assert.Equal("device::fake-tv::0", sdb.Banner);
        Assert.Equal("fake-tv", sdb.DeviceName);
        Assert.Equal(262044, sdb.MaxPayload); // the smaller of the two offers, as recorded
        var device = await TvDevice.OpenAsync(sdb, CancellationToken.None);
        Assert.Equal("6.5", device.TizenVersion);
        Assert.Equal(6, device.TizenMajor);
        Assert.False(device.NeedsSamsungCertificate);
        Assert.Equal("/home/owner/share/tmp/sdk_tools", device.Capability.SdkToolPath);
        Assert.Equal("ABCDEF1234567", device.Duid);
        Assert.Equal(["capability:", "shell:0 getduid"], tv.Services);
    }

    [Fact]
    public async Task PushesInstallsAndLaunches()
    {
        await using var tv = new FakeSdbd();
        await using var sdb = await SdbClient.ConnectAsync("127.0.0.1", tv.Port);
        var device = await TvDevice.OpenAsync(sdb, CancellationToken.None);
        // bigger than one sync DATA chunk (64 KiB) and than one sdb payload, to cross both boundaries
        var package = new byte[300_000];
        new Random(1).NextBytes(package);
        var lines = new List<string>();
        var result = await device.InstallAsync(package, lines.Add, CancellationToken.None);
        Assert.True(result.Ok, result.Log);
        Assert.Equal(package, tv.Files["/home/owner/share/tmp/sdk_tools/tmp/Tally.wgt"]);
        Assert.Equal("33261", tv.Modes["/home/owner/share/tmp/sdk_tools/tmp/Tally.wgt"]); // 0100755, as sdb sends
        Assert.Contains("shell:0 vd_appinstall TallyTVapp.Tally /home/owner/share/tmp/sdk_tools/tmp/Tally.wgt", tv.Services);
        Assert.Contains("app_id[TallyTVapp.Tally] install completed", lines);
        Assert.True(await device.LaunchAsync(CancellationToken.None));
        Assert.Contains("shell:0 was_execute TallyTVapp.Tally", tv.Services);
        // the connection stays usable after every stream
        Assert.Equal("6.0", (await sdb.GetCapabilityAsync()).PlatformVersion);
    }

    [Fact]
    public async Task ReportsATvThatHangsUp()
    {
        await using var tv = new FakeSdbd(FakeSdbd.Mode.HangUp);
        var ex = await Assert.ThrowsAsync<SdbConnectException>(() => SdbClient.ConnectAsync("127.0.0.1", tv.Port));
        Assert.Equal(SdbConnectFailure.Refused, ex.Failure);
    }

    [Fact]
    public async Task ReportsATvThatSaysNothing()
    {
        await using var tv = new FakeSdbd(FakeSdbd.Mode.Silent);
        var ex = await Assert.ThrowsAsync<SdbConnectException>(() => SdbClient.ConnectAsync("127.0.0.1", tv.Port, TimeSpan.FromSeconds(1)));
        Assert.Equal(SdbConnectFailure.Silent, ex.Failure);
    }

    [Fact]
    public async Task ReportsNothingListening()
    {
        var listener = new System.Net.Sockets.TcpListener(System.Net.IPAddress.Loopback, 0);
        listener.Start();
        var port = ((System.Net.IPEndPoint)listener.LocalEndpoint).Port;
        listener.Stop();
        var ex = await Assert.ThrowsAsync<SdbConnectException>(() => SdbClient.ConnectAsync("127.0.0.1", port, TimeSpan.FromSeconds(2)));
        Assert.Equal(SdbConnectFailure.NoAnswer, ex.Failure);
    }

    [Fact]
    public void ParsesTheCapabilityAsRecorded()
    {
        var text = FakeSdbd.CapabilityText("10.0");
        var raw = new byte[text.Length + 2];
        BitConverter.GetBytes((ushort)text.Length).CopyTo(raw, 0);
        Encoding.ASCII.GetBytes(text).CopyTo(raw, 2);
        var c = SdbCapability.Parse(raw);
        Assert.Equal("10.0", c.PlatformVersion);
        Assert.Equal(10, c.PlatformMajor);
        Assert.True(c.SecureProtocol);
        Assert.Equal("tv", c.ProfileName);
        // without the length, and with the host form's four hex digits
        Assert.Equal("10.0", SdbCapability.Parse(Encoding.ASCII.GetBytes(text)).PlatformVersion);
        Assert.Equal("10.0", SdbCapability.Parse(Encoding.ASCII.GetBytes(text.Length.ToString("x4") + text)).PlatformVersion);
    }

    [Theory]
    [InlineData("XTCJYJZXZBZVK\r\n", "XTCJYJZXZBZVK")]
    [InlineData("\nsome banner\nRZ1234567890AB\n", "RZ1234567890AB")]
    [InlineData("closed", "")]
    [InlineData("", "")]
    public void ParsesDuids(string output, string duid) => Assert.Equal(duid, TvDevice.ParseDuid(output));

    [Theory]
    // the emulator's answer to a package signed with the Tizen certificate (Tizen 10 wants Samsung's)
    [InlineData("app_id[TallyTVapp.Tally] install failed[118, -12], reason: Check certificate error : :Invalid certificate chain with certificate in signature.:<-3>", InstallOutcome.CertificateNotTrusted)]
    // the emulator's answer to a package from another computer's author certificate (the reason ends on its own line)
    [InlineData("app_id[TallyTVapp.Tally] install failed[118, -11], reason: Author certificate not match\n:", InstallOutcome.AuthorMismatch)]
    [InlineData("app_id[TallyTVapp.Tally] install failed[118, -12], reason: Check certificate error : :Author certificate not match :<-12>", InstallOutcome.AuthorMismatch)]
    [InlineData("app_id[TallyTVapp.Tally] install failed[118, -12], reason: Check certificate error : :Certificate in signature is not valid yet.:<-7>", InstallOutcome.NotYetValid)]
    [InlineData("app_id[TallyTVapp.Tally] install failed[118, -12], reason: Check certificate error : :Certificate in signature has expired.:<-6>", InstallOutcome.Expired)]
    [InlineData("app_id[TallyTVapp.Tally] install failed[118, -12], reason: Check certificate error : :Invalid DUID in distributor certificate.:<-11>", InstallOutcome.WrongTv)]
    [InlineData("app_id[TallyTVapp.Tally] install failed[118, -4], reason: Invalid api version", InstallOutcome.TizenTooOld)]
    [InlineData("app_id[TallyTVapp.Tally] install failed[132]", InstallOutcome.Failed)]
    [InlineData("app_id[TallyTVapp.Tally] install completed", InstallOutcome.Installed)]
    [InlineData("nothing useful", InstallOutcome.Failed)]
    public void ClassifiesInstallAnswers(string line, InstallOutcome outcome)
    {
        var log = "\ninstall TallyTVapp.Tally\npackage_path /home/owner/share/tmp/sdk_tools/tmp/Tally.wgt\n" + line + "\nspend time for wascmd is [206]ms\n";
        Assert.Equal(outcome, TvDevice.ParseInstall(log).Outcome);
    }
}
