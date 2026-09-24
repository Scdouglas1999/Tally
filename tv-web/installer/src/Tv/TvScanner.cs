using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text.Json;
using Tally.SamsungInstaller.Sdb;

namespace Tally.SamsungInstaller.Tv;

/// <summary>A Samsung TV (or an sdb device) found on the network.</summary>
public sealed record TvFound(
    IPAddress Address,
    string? Name,
    string? ModelName,
    string? ModelCode,
    bool? DeveloperMode,
    string? DeveloperIp,
    bool SdbPortOpen)
{
    /// <summary>The model year from the model code ("20_KANTM2_UHD" → 2020), when the TV says.</summary>
    public int? ModelYear =>
        ModelCode is { Length: >= 3 } code && code[2] == '_' && int.TryParse(code[..2], out var yy) ? 2000 + yy : null;

    public string Label
    {
        get
        {
            var parts = new List<string>();
            if (!string.IsNullOrWhiteSpace(Name))
            {
                parts.Add(Name!.Replace("[TV] ", "", StringComparison.Ordinal));
            }

            if (!string.IsNullOrWhiteSpace(ModelName))
            {
                parts.Add(ModelName!);
            }

            if (ModelYear is { } year)
            {
                parts.Add(year + " model");
            }

            return parts.Count == 0 ? "a device with Developer Mode's port open" : string.Join(", ", parts);
        }
    }
}

/// <summary>
/// Finds Samsung TVs on this PC's networks: every address of each local IPv4 network (the /24 around this PC's
/// address) is asked, at the same time, for Samsung's TV information (<c>http://&lt;ip&gt;:8001/api/v2/</c>: name,
/// model, and on TVs that report it, <c>developerMode</c> and <c>developerIP</c>) and whether the sdb port (26101)
/// takes a connection.
/// </summary>
public sealed class TvScanner(HttpClient http)
{
    public int SdbPort { get; init; } = SdbClient.DefaultPort;
    public int InfoPort { get; init; } = 8001;
    public TimeSpan ConnectTimeout { get; init; } = TimeSpan.FromMilliseconds(900);
    public TimeSpan InfoTimeout { get; init; } = TimeSpan.FromSeconds(2);

    /// <summary>This PC's IPv4 networks: (address, the /24 to scan).</summary>
    public static IReadOnlyList<(IPAddress Local, IReadOnlyList<IPAddress> Hosts)> LocalNetworks()
    {
        var result = new List<(IPAddress, IReadOnlyList<IPAddress>)>();
        foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (nic.OperationalStatus != OperationalStatus.Up || nic.NetworkInterfaceType == NetworkInterfaceType.Loopback)
            {
                continue;
            }

            foreach (var unicast in nic.GetIPProperties().UnicastAddresses)
            {
                var ip = unicast.Address;
                if (ip.AddressFamily != AddressFamily.InterNetwork || IPAddress.IsLoopback(ip) || IsLinkLocal(ip)
                    || !IsPrivate(ip) || result.Any(r => r.Item1.Equals(ip)))
                {
                    continue;
                }

                result.Add((ip, Slash24(ip).Where(h => !h.Equals(ip)).ToList()));
            }
        }

        return result;
    }

    public static IEnumerable<IPAddress> Slash24(IPAddress ip)
    {
        var b = ip.GetAddressBytes();
        for (var last = 1; last < 255; last++)
        {
            yield return new IPAddress([b[0], b[1], b[2], (byte)last]);
        }
    }

    private static bool IsLinkLocal(IPAddress ip) => ip.GetAddressBytes() is [169, 254, ..];

    /// <summary>Home networks (10/8, 172.16/12, 192.168/16, 100.64/10): a TV is never on a public address.</summary>
    public static bool IsPrivate(IPAddress ip) => ip.GetAddressBytes() switch
    {
        [10, ..] => true,
        [172, var b, ..] when b is >= 16 and <= 31 => true,
        [192, 168, ..] => true,
        [100, var b, ..] when b is >= 64 and <= 127 => true,
        _ => false,
    };

    /// <summary>Scans the given addresses; returns TVs and open sdb ports, in address order.</summary>
    public async Task<IReadOnlyList<TvFound>> ScanAsync(IEnumerable<IPAddress> hosts, CancellationToken ct,
        IProgress<int>? progress = null)
    {
        var list = hosts.ToList();
        var found = new List<TvFound>();
        var done = 0;
        using var gate = new SemaphoreSlim(96);
        var tasks = list.Select(async ip =>
        {
            await gate.WaitAsync(ct).ConfigureAwait(false);
            try
            {
                var tv = await ProbeAsync(ip, ct).ConfigureAwait(false);
                if (tv is not null)
                {
                    lock (found)
                    {
                        found.Add(tv);
                    }
                }
            }
            finally
            {
                gate.Release();
                progress?.Report(Interlocked.Increment(ref done));
            }
        });
        await Task.WhenAll(tasks).ConfigureAwait(false);
        return found.OrderBy(t => t.Address.GetAddressBytes(), ByteOrder.Instance).ToList();
    }

    /// <summary>One address: Samsung's TV information and the sdb port. Null when neither answers.</summary>
    public async Task<TvFound?> ProbeAsync(IPAddress ip, CancellationToken ct)
    {
        var sdb = PortOpenAsync(ip, SdbPort, ct);
        var info = InfoAsync(ip, ct);
        await Task.WhenAll(sdb, info).ConfigureAwait(false);
        var device = info.Result;
        if (device is null && !sdb.Result)
        {
            return null;
        }

        return device is null
            ? new TvFound(ip, null, null, null, null, null, true)
            : device with { SdbPortOpen = sdb.Result };
    }

    private async Task<bool> PortOpenAsync(IPAddress ip, int port, CancellationToken ct)
    {
        using var limit = CancellationTokenSource.CreateLinkedTokenSource(ct);
        limit.CancelAfter(ConnectTimeout);
        using var tcp = new TcpClient(ip.AddressFamily);
        try
        {
            await tcp.ConnectAsync(ip, port, limit.Token).ConfigureAwait(false);
            return true;
        }
        catch (Exception ex) when (ex is SocketException or OperationCanceledException && !ct.IsCancellationRequested)
        {
            return false;
        }
    }

    private async Task<TvFound?> InfoAsync(IPAddress ip, CancellationToken ct)
    {
        using var limit = CancellationTokenSource.CreateLinkedTokenSource(ct);
        limit.CancelAfter(InfoTimeout);
        try
        {
            using var response = await http.GetAsync($"http://{ip}:{InfoPort}/api/v2/", limit.Token).ConfigureAwait(false);
            if (!response.IsSuccessStatusCode)
            {
                return null;
            }

            var body = await response.Content.ReadAsStringAsync(limit.Token).ConfigureAwait(false);
            return ParseInfo(ip, body);
        }
        catch (Exception ex) when (ex is HttpRequestException or OperationCanceledException && !ct.IsCancellationRequested)
        {
            return null;
        }
    }

    /// <summary>Samsung's <c>/api/v2/</c> answer; null when it is not a Samsung TV.</summary>
    public static TvFound? ParseInfo(IPAddress ip, string body)
    {
        try
        {
            using var doc = JsonDocument.Parse(body);
            var root = doc.RootElement;
            if (!root.TryGetProperty("device", out var device) || device.ValueKind != JsonValueKind.Object)
            {
                return null;
            }

            string? S(string name) => device.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : null;
            var type = S("type") ?? (root.TryGetProperty("type", out var t) ? t.GetString() : null);
            if (type is null || !type.Contains("Samsung", StringComparison.OrdinalIgnoreCase))
            {
                return null;
            }

            bool? devMode = S("developerMode") switch { "1" => true, "0" => false, _ => null };
            var devIp = S("developerIP");
            return new TvFound(ip, S("name") ?? (root.TryGetProperty("name", out var n) ? n.GetString() : null),
                S("modelName"), S("model"), devMode, string.IsNullOrWhiteSpace(devIp) ? null : devIp, false);
        }
        catch (JsonException)
        {
            return null;
        }
    }

    /// <summary>The address this PC uses to reach <paramref name="tv"/> (what Developer Mode's Host PC IP must be).</summary>
    public static IPAddress? LocalAddressFor(IPAddress tv)
    {
        try
        {
            using var socket = new Socket(tv.AddressFamily, SocketType.Dgram, ProtocolType.Udp);
            socket.Connect(tv, 9); // UDP connect sends nothing; it only picks the route
            return (socket.LocalEndPoint as IPEndPoint)?.Address;
        }
        catch (SocketException)
        {
            return null;
        }
    }

    private sealed class ByteOrder : IComparer<byte[]>
    {
        public static readonly ByteOrder Instance = new();

        public int Compare(byte[]? x, byte[]? y)
        {
            for (var i = 0; i < Math.Min(x!.Length, y!.Length); i++)
            {
                var c = x[i].CompareTo(y[i]);
                if (c != 0)
                {
                    return c;
                }
            }

            return x.Length.CompareTo(y.Length);
        }
    }
}
