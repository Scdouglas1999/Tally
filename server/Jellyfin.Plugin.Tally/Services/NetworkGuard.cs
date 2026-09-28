using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.Sockets;
using System.Threading;
using System.Threading.Tasks;

namespace Jellyfin.Plugin.Tally.Services;

/// <summary>
/// Where the server may connect on behalf of a stream. Upstream playlists (from IPTV providers, and from web pages
/// Tally scans) name the next addresses the server fetches, and the proxy hands the answers to viewers, so a playlist
/// must not be able to steer the server at its own network: the cloud metadata service, the router's admin page, a
/// database on localhost. Checked on the address actually connected to (after DNS), so a public name that resolves to
/// a private address is caught too.
/// <list type="bullet">
/// <item>Never: unspecified, multicast and broadcast addresses, and the cloud metadata endpoints.</item>
/// <item>Private, loopback, link-local and carrier-grade NAT addresses: only for a host the admin configured (a source's
/// playlist, guide, page or stream address), a stream host of a source that is itself on the local network (an
/// HDHomeRun, a local IPTV proxy), or this server itself.</item>
/// <item>Everything else (the public internet): always.</item>
/// </list>
/// </summary>
public static class NetworkGuard
{
    private static readonly IPAddress[] Metadata =
    {
        IPAddress.Parse("169.254.169.254"),   // AWS, GCP, Azure, DigitalOcean, Oracle...
        IPAddress.Parse("169.254.170.2"),     // AWS ECS task metadata
        IPAddress.Parse("100.100.100.200"),   // Alibaba Cloud
        IPAddress.Parse("fd00:ec2::254"),     // AWS IPv6
    };

    /// <summary>Never connected to, whoever asks.</summary>
    public static bool IsForbidden(IPAddress address)
    {
        var a = Normalize(address);
        if (Metadata.Any(m => m.Equals(a)))
        {
            return true;
        }

        if (a.AddressFamily == AddressFamily.InterNetwork)
        {
            var b = a.GetAddressBytes();
            return b[0] == 0                       // 0.0.0.0/8 "this network" (0.0.0.0 reaches localhost)
                   || b[0] >= 224;                 // multicast, reserved, broadcast
        }

        return a.Equals(IPAddress.IPv6None) || a.Equals(IPAddress.IPv6Any) || a.IsIPv6Multicast;
    }

    /// <summary>Loopback, RFC 1918, carrier-grade NAT, link-local, IPv6 unique-local and site-local.</summary>
    public static bool IsInternal(IPAddress address)
    {
        var a = Normalize(address);
        if (IPAddress.IsLoopback(a))
        {
            return true;
        }

        if (a.AddressFamily == AddressFamily.InterNetwork)
        {
            var b = a.GetAddressBytes();
            return b[0] == 10
                   || (b[0] == 172 && b[1] >= 16 && b[1] <= 31)
                   || (b[0] == 192 && b[1] == 168)
                   || (b[0] == 169 && b[1] == 254)
                   || (b[0] == 100 && b[1] >= 64 && b[1] <= 127);
        }

        var v6 = a.GetAddressBytes();
        return a.IsIPv6LinkLocal || a.IsIPv6SiteLocal || (v6[0] & 0xFE) == 0xFC;
    }

    /// <summary>A host name that can only mean the local network: an IP literal of an internal address, localhost,
    /// a single label ("nas"), or a local suffix (.local, .lan, .home.arpa…).</summary>
    public static bool IsLocalHostName(string? host)
    {
        if (string.IsNullOrEmpty(host))
        {
            return false;
        }

        host = host.Trim('[', ']').TrimEnd('.').ToLowerInvariant();
        if (IPAddress.TryParse(host, out var ip))
        {
            return IsInternal(ip);
        }

        return host == "localhost" || !host.Contains('.')
               || host.EndsWith(".localhost", StringComparison.Ordinal)
               || host.EndsWith(".local", StringComparison.Ordinal)
               || host.EndsWith(".lan", StringComparison.Ordinal)
               || host.EndsWith(".home", StringComparison.Ordinal)
               || host.EndsWith(".home.arpa", StringComparison.Ordinal)
               || host.EndsWith(".internal", StringComparison.Ordinal)
               || host.EndsWith(".localdomain", StringComparison.Ordinal);
    }

    /// <summary>Whether a connection to <paramref name="address"/>:<paramref name="port"/> for a request to
    /// <paramref name="host"/> is allowed.</summary>
    /// <param name="trusted">Hosts that may be on the local network (see <see cref="TrustedHosts"/>).</param>
    /// <param name="ownPorts">This server's own ports: loopback on them is the server itself.</param>
    public static bool Allows(IPAddress address, string host, int port, ISet<string> trusted, IReadOnlyCollection<int> ownPorts)
    {
        if (IsForbidden(address))
        {
            return false;
        }

        if (!IsInternal(address))
        {
            return true;
        }

        if (IPAddress.IsLoopback(Normalize(address)) && ownPorts.Contains(port))
        {
            return true;
        }

        return trusted.Contains(NormalizeHost(host));
    }

    /// <summary>
    /// The hosts that may be on the local network: every host an admin typed into a source (playlist, guide, page,
    /// direct streams), and the stream hosts of sources that are themselves local (their playlist is on a local
    /// address), since an HDHomeRun or a local IPTV proxy lists streams on the local network.
    /// </summary>
    public static HashSet<string> TrustedHosts(IEnumerable<SourceDefinition> sources, IEnumerable<Models.SourceChannel> channels)
    {
        var trusted = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var localSources = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var s in sources)
        {
            var hosts = new[] { s.PlaylistUrl, s.EpgUrl, s.PageUrl }.Concat(s.Streams.Select(x => x.Url))
                .Select(HostOf).Where(h => h != null).Select(h => h!).ToList();
            trusted.UnionWith(hosts);
            if (s.Kind == SourceKind.Direct || hosts.Any(IsLocalHostName))
            {
                localSources.Add(s.Id.ToString());
                localSources.Add(s.Id.ToString("N"));
                localSources.Add(s.Name);
            }
        }

        foreach (var c in channels)
        {
            if ((c.SourceId != null && localSources.Contains(c.SourceId)) || (c.SourceName != null && localSources.Contains(c.SourceName)))
            {
                foreach (var url in c.Candidates.Select(x => x.Url).Append(c.StreamUrl))
                {
                    if (HostOf(url) is { } h)
                    {
                        trusted.Add(h);
                    }
                }
            }
        }

        return trusted;
    }

    /// <summary>A URL's host, normalized for <see cref="Allows"/>; null for anything that is not an absolute URL.</summary>
    public static string? HostOf(string? url)
        => Uri.TryCreate((url ?? string.Empty).Trim(), UriKind.Absolute, out var u) && !string.IsNullOrEmpty(u.Host) ? NormalizeHost(u.Host) : null;

    private static string NormalizeHost(string host) => host.Trim('[', ']').TrimEnd('.').ToLowerInvariant();

    private static IPAddress Normalize(IPAddress a) => a.IsIPv4MappedToIPv6 ? a.MapToIPv4() : a;

    /// <summary>
    /// Resolves <paramref name="host"/> and returns the addresses a connection may use (in DNS order), or throws an
    /// <see cref="HttpRequestException"/> naming the host when there are none.
    /// </summary>
    public static async Task<IPAddress[]> ResolveAllowedAsync(string host, int port, Func<IPAddress, bool> allows, CancellationToken ct)
    {
        var bare = host.Trim('[', ']');
        var addresses = IPAddress.TryParse(bare, out var literal)
            ? new[] { literal }
            : await Dns.GetHostAddressesAsync(bare, ct).ConfigureAwait(false);
        var allowed = addresses.Where(allows).ToArray();
        if (allowed.Length == 0)
        {
            throw new HttpRequestException($"Tally does not connect to {host}: it is on this server's own network and no source lists it");
        }

        return allowed;
    }

    /// <summary>A SocketsHttpHandler connect callback that only connects to addresses <paramref name="allows"/>
    /// accepts (host, address, port). A configured HTTP proxy is connected to as it is.</summary>
    public static Func<SocketsHttpConnectionContext, CancellationToken, ValueTask<Stream>> ConnectCallback(Func<string, IPAddress, int, bool> allows)
        => async (context, ct) =>
        {
            var host = context.DnsEndPoint.Host;
            var port = context.DnsEndPoint.Port;
            var target = context.InitialRequestMessage.RequestUri;
            var viaProxy = target != null && IsSystemProxy(target, host, port);
            var addresses = await ResolveAllowedAsync(host, port, a => viaProxy || allows(host, a, port), ct).ConfigureAwait(false);
            var socket = new Socket(SocketType.Stream, ProtocolType.Tcp) { NoDelay = true };
            try
            {
                await socket.ConnectAsync(addresses, port, ct).ConfigureAwait(false);
                return new NetworkStream(socket, ownsSocket: true);
            }
            catch
            {
                socket.Dispose();
                throw;
            }
        };

    private static bool IsSystemProxy(Uri target, string host, int port)
    {
        try
        {
            var proxy = HttpClient.DefaultProxy.GetProxy(target);
            return proxy != null && !proxy.Equals(target) && proxy.Port == port
                   && string.Equals(proxy.Host, host, StringComparison.OrdinalIgnoreCase);
        }
        catch (Exception ex) when (ex is InvalidOperationException or ArgumentException or UriFormatException)
        {
            return false;
        }
    }
}

/// <summary>
/// <see cref="NetworkGuard"/> with this server's configuration: the trusted hosts (recomputed when the sources or the
/// channel list change) and the server's own ports. One instance, shared by the upstream HTTP client's connect callback
/// and the check before the browser relay (which does not go through that client).
/// </summary>
public sealed class UpstreamGuard
{
    private readonly Sources.SourceManager _sources;
    private readonly Func<IReadOnlyCollection<int>> _ownPorts;
    private readonly object _lock = new();
    private (object? Config, DateTimeOffset Loaded, string Data, HashSet<string> Hosts) _cache;

    public UpstreamGuard(Sources.SourceManager sources, MediaBrowser.Controller.IServerApplicationHost appHost)
        : this(sources, () => new[] { appHost.HttpPort, appHost.HttpsPort })
    {
    }

    internal UpstreamGuard(Sources.SourceManager sources, Func<IReadOnlyCollection<int>> ownPorts)
    {
        _sources = sources;
        _ownPorts = ownPorts;
    }

    public ISet<string> Trusted
    {
        get
        {
            var config = Plugin.Instance?.Configuration;
            var loaded = _sources.LoadedAt;
            var data = _sources.DataVersion;
            lock (_lock)
            {
                if (_cache.Hosts == null || !ReferenceEquals(_cache.Config, config) || _cache.Loaded != loaded || _cache.Data != data)
                {
                    IEnumerable<SourceDefinition> defs = _sources.DefinitionsOverride?.Invoke() ?? (IEnumerable<SourceDefinition>?)config?.Sources ?? Array.Empty<SourceDefinition>();
                    _cache = (config, loaded, data, NetworkGuard.TrustedHosts(defs.Where(d => d.Enabled), _sources.GetChannels()));
                }

                return _cache.Hosts;
            }
        }
    }

    public bool Allows(string host, IPAddress address, int port) => NetworkGuard.Allows(address, host, port, Trusted, _ownPorts());

    /// <summary>For fetches that do not go through the guarded HTTP client (the browser relay): false when the host
    /// resolves to nothing it may connect to.</summary>
    public async Task<bool> AllowsUriAsync(Uri uri, CancellationToken ct)
    {
        try
        {
            await NetworkGuard.ResolveAllowedAsync(uri.Host, uri.Port, a => Allows(uri.Host, a, uri.Port), ct).ConfigureAwait(false);
            return true;
        }
        catch (Exception ex) when (ex is HttpRequestException or SocketException or ArgumentException)
        {
            return false;
        }
    }
}
