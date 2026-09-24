using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;
using System.Text;
using Tally.SamsungInstaller.Sdb;

namespace Tally.SamsungInstaller.Tests;

/// <summary>
/// A fake TV sdbd on a loopback port that answers as the Tizen TV emulator's sdbd 2.2.31 did when Tizen Studio's sdb
/// talked to it (recorded in fixtures/sdb-trace-emulator.txt): the CNXN banner and payload size, the capability list
/// with its 2-byte length, shell output, the sync protocol (SEND, DATA, DONE → OKAY, QUIT), CLSE pairs, and
/// vd_appinstall's progress lines.
/// </summary>
public sealed class FakeSdbd : IAsyncDisposable
{
    public enum Mode { Normal, HangUp, Silent }

    private readonly TcpListener _listener = new(IPAddress.Loopback, 0);
    private readonly CancellationTokenSource _stop = new();
    private readonly Task _loop;

    public FakeSdbd(Mode mode = Mode.Normal)
    {
        TheMode = mode;
        _listener.Start();
        _loop = Task.Run(AcceptLoopAsync);
    }

    public Mode TheMode { get; }
    public int Port => ((IPEndPoint)_listener.LocalEndpoint).Port;
    public string PlatformVersion { get; set; } = "6.0";
    public string Duid { get; set; } = "XTCJYJZXZBZVK";
    /// <summary>What vd_appinstall prints after "install start" (default: success).</summary>
    public Func<string, string> InstallOutput { get; set; } = id => $"app_id[{id}] install completed\n";
    public List<string> Services { get; } = [];
    public Dictionary<string, byte[]> Files { get; } = [];
    public Dictionary<string, string> Modes { get; } = [];
    public int Installs { get; private set; }

    public static string CapabilityText(string platformVersion) =>
        "secure_protocol:enabled\nintershell_support:disabled\nfilesync_support:pushpull\nusbproto_support:disabled\n" +
        "sockproto_support:enabled\nsyncwinsz_support:enabled\nsdbd_rootperm:disabled\nrootonoff_support:disabled\n" +
        "encryption_support:disabled\nzone_support:disabled\nmultiuser_support:enabled\ncpu_arch:armv7\ncore_abi:armel\n" +
        "sdk_toolpath:/home/owner/share/tmp/sdk_tools\nprofile_name:tv\nvendor_name:Samsung\ncan_launch:tv-samsung\n" +
        $"device_name:Tizen\nplatform_version:{platformVersion}\nproduct_version:4.0\nsdbd_version:2.2.31\n" +
        "sdbd_plugin_version:3.7.0_TV_REL\nsdbd_cap_version:1.0\nlog_enable:disabled\nlog_path:/tmp\nappcmd_support:disabled\n" +
        "appid2pid_support:enabled\npkgcmd_debugmode:enabled\nnetcoredbg_support:enabled\narchitecture:32\n" +
        $"platform_build_version:{platformVersion}\n";

    private async Task AcceptLoopAsync()
    {
        while (!_stop.IsCancellationRequested)
        {
            TcpClient client;
            try
            {
                client = await _listener.AcceptTcpClientAsync(_stop.Token);
            }
            catch (Exception)
            {
                return;
            }

            _ = Task.Run(() => ServeAsync(client));
        }
    }

    private async Task ServeAsync(TcpClient client)
    {
        using (client)
        {
            var s = client.GetStream();
            try
            {
                if (TheMode == Mode.HangUp)
                {
                    return;
                }

                var hello = await SdbProtocol.ReadAsync(s, _stop.Token);
                if (hello.Command != SdbProtocol.Cnxn || TheMode == Mode.Silent)
                {
                    await Task.Delay(Timeout.Infinite, _stop.Token);
                    return;
                }

                await Send(s, SdbProtocol.Cnxn, 0x2000000, 262044, Encoding.ASCII.GetBytes("device::fake-tv::0\0"));
                uint nextRemote = 0xd4;
                while (true)
                {
                    var m = await SdbProtocol.ReadAsync(s, _stop.Token);
                    if (m.Command != SdbProtocol.Open)
                    {
                        continue; // stray OKAY/CLSE of a finished stream
                    }

                    var service = Encoding.UTF8.GetString(m.Data).TrimEnd('\0');
                    lock (Services)
                    {
                        Services.Add(service);
                    }

                    var local = nextRemote++;
                    var host = m.Arg0;
                    await Send(s, SdbProtocol.Okay, local, host, []);
                    if (service == "sync:")
                    {
                        await SyncAsync(s, local, host);
                        continue;
                    }

                    var output = service switch
                    {
                        "capability:" => WithLength(CapabilityText(PlatformVersion)),
                        "shell:0 getduid" => Encoding.ASCII.GetBytes(Duid + "\r\n"),
                        _ when service.StartsWith("shell:0 vd_appinstall ", StringComparison.Ordinal) => InstallLog(service),
                        _ when service.StartsWith("shell:0 was_execute ", StringComparison.Ordinal) =>
                            Encoding.ASCII.GetBytes($"\nlaunch app {service.Split(' ')[2]}\napp_id[{service.Split(' ')[2]}] launch app succeed\nspend time for wascmd is [11]ms\n"),
                        _ when service.StartsWith("shell:0 vd_appuninstall ", StringComparison.Ordinal) =>
                            Encoding.ASCII.GetBytes($"\nuninstall {service.Split(' ')[2]}\napp_id[{service.Split(' ')[2]}] uninstall completed\nspend time for wascmd is [300]ms\n"),
                        _ => [],
                    };

                    if (service.StartsWith("shell:0 vd_app", StringComparison.Ordinal))
                    {
                        // as recorded: progress lines one by one, each waiting for the host's OKAY
                        foreach (var piece in Pieces(output, 40))
                        {
                            await Send(s, SdbProtocol.Wrte, local, host, piece);
                            await Expect(s, SdbProtocol.Okay);
                        }

                        await Send(s, SdbProtocol.Clse, 0, host, []);
                    }
                    else
                    {
                        // as recorded for capability and getduid: the answer and the CLSE at once, before any OKAY
                        if (output.Length > 0)
                        {
                            await Send(s, SdbProtocol.Wrte, local, host, output);
                        }

                        await Send(s, SdbProtocol.Clse, 0, host, []);
                    }

                    await Expect(s, SdbProtocol.Clse);
                }
            }
            catch (Exception)
            {
                // the client went away
            }
        }
    }

    private byte[] InstallLog(string service)
    {
        var parts = service.Split(' ');
        var id = parts[2];
        Installs++;
        var text = $"\ninstall {id}\npackage_path {parts[3]}\napp_id[{id}] install start\napp_id[{id}] installing[9]\n" +
                   $"app_id[{id}] installing[18]\n" + InstallOutput(id) + "spend time for wascmd is [206]ms\n";
        return Encoding.ASCII.GetBytes(text);
    }

    private async Task SyncAsync(NetworkStream s, uint local, uint host)
    {
        var buffer = new MemoryStream();
        async Task<byte[]> Take(int n)
        {
            while (buffer.Length - buffer.Position < n)
            {
                var m = await SdbProtocol.ReadAsync(s, _stop.Token);
                if (m.Command == SdbProtocol.Clse)
                {
                    throw new EndOfStreamException();
                }

                if (m.Command == SdbProtocol.Wrte)
                {
                    var pos = buffer.Position;
                    buffer.Seek(0, SeekOrigin.End);
                    buffer.Write(m.Data);
                    buffer.Position = pos;
                    await Send(s, SdbProtocol.Okay, local, host, []);
                }
            }

            var result = new byte[n];
            buffer.ReadExactly(result);
            return result;
        }

        try
        {
            while (true)
            {
                var head = await Take(8);
                var id = Encoding.ASCII.GetString(head, 0, 4);
                var length = BinaryPrimitives.ReadUInt32LittleEndian(head.AsSpan(4));
                if (id == "SEND")
                {
                    var target = Encoding.UTF8.GetString(await Take((int)length));
                    var comma = target.LastIndexOf(',');
                    var path = target[..comma];
                    Modes[path] = target[(comma + 1)..];
                    var content = new MemoryStream();
                    while (true)
                    {
                        var h = await Take(8);
                        var kind = Encoding.ASCII.GetString(h, 0, 4);
                        var n = BinaryPrimitives.ReadUInt32LittleEndian(h.AsSpan(4));
                        if (kind == "DATA")
                        {
                            content.Write(await Take((int)n));
                        }
                        else if (kind == "DONE")
                        {
                            break;
                        }
                    }

                    Files[path] = content.ToArray();
                    await Send(s, SdbProtocol.Wrte, local, host, "OKAY\0\0\0\0"u8.ToArray());
                    await Expect(s, SdbProtocol.Okay);
                }
                else if (id == "QUIT")
                {
                    await Expect(s, SdbProtocol.Clse);
                    await Send(s, SdbProtocol.Clse, 0, host, []);
                    return;
                }
            }
        }
        catch (EndOfStreamException)
        {
            await Send(s, SdbProtocol.Clse, 0, host, []);
        }
    }

    private async Task Expect(NetworkStream s, uint command)
    {
        while (true)
        {
            var m = await SdbProtocol.ReadAsync(s, _stop.Token);
            if (m.Command == command)
            {
                return;
            }
        }
    }

    private static byte[] WithLength(string text)
    {
        var body = Encoding.ASCII.GetBytes(text);
        var result = new byte[body.Length + 2];
        BinaryPrimitives.WriteUInt16LittleEndian(result, (ushort)body.Length);
        body.CopyTo(result, 2);
        return result;
    }

    private static IEnumerable<byte[]> Pieces(byte[] data, int size)
    {
        for (var i = 0; i < data.Length; i += size)
        {
            yield return data[i..Math.Min(data.Length, i + size)];
        }
    }

    private static Task Send(NetworkStream s, uint command, uint a0, uint a1, byte[] data) =>
        s.WriteAsync(SdbProtocol.Encode(command, a0, a1, data)).AsTask();

    public async ValueTask DisposeAsync()
    {
        _stop.Cancel();
        _listener.Stop();
        try
        {
            await _loop;
        }
        catch (Exception)
        {
            // stopped
        }
    }
}
