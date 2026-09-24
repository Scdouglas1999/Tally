using System.Buffers.Binary;
using System.Net.Sockets;
using System.Text;

namespace Tally.SamsungInstaller.Sdb;

/// <summary>
/// The part of sdb the installer needs, spoken straight to the TV's sdbd (no sdb server, no Tizen Studio):
/// connect, <c>capability:</c>, <c>shell:</c> (the TV's fixed set of "0 …" commands) and <c>sync:</c> push.
/// One stream at a time: every call opens a stream, runs it to its end and closes it.
/// </summary>
public sealed class SdbClient : IAsyncDisposable
{
    public const int DefaultPort = 26101;

    private readonly TcpClient? _tcp;
    private readonly Stream _stream;
    private readonly SemaphoreSlim _gate = new(1, 1);
    private uint _nextLocalId = 0x10;

    private SdbClient(TcpClient? tcp, Stream stream, string banner, int maxPayload)
    {
        _tcp = tcp;
        _stream = stream;
        Banner = banner;
        MaxPayload = maxPayload;
    }

    /// <summary>The device's CNXN banner, e.g. <c>device::tally-tv::0</c>.</summary>
    public string Banner { get; }

    /// <summary>The largest payload both sides accept.</summary>
    public int MaxPayload { get; }

    /// <summary>The TV's model name or the emulator's VM name, from the banner.</summary>
    public string DeviceName
    {
        get
        {
            var parts = Banner.Split("::");
            return parts.Length > 1 ? parts[1] : "";
        }
    }

    public static async Task<SdbClient> ConnectAsync(string host, int port = DefaultPort, TimeSpan? timeout = null,
        CancellationToken ct = default)
    {
        using var limit = CancellationTokenSource.CreateLinkedTokenSource(ct);
        limit.CancelAfter(timeout ?? TimeSpan.FromSeconds(8));
        var tcp = new TcpClient { NoDelay = true };
        try
        {
            try
            {
                await tcp.ConnectAsync(host, port, limit.Token).ConfigureAwait(false);
            }
            catch (SocketException ex)
            {
                throw new SdbConnectException(SdbConnectFailure.NoAnswer, ex);
            }
            catch (OperationCanceledException) when (!ct.IsCancellationRequested)
            {
                throw new SdbConnectException(SdbConnectFailure.NoAnswer, null);
            }

            return await HandshakeAsync(tcp, tcp.GetStream(), limit.Token, ct).ConfigureAwait(false);
        }
        catch
        {
            tcp.Dispose();
            throw;
        }
    }

    /// <summary>For tests: an sdb conversation over any stream.</summary>
    internal static Task<SdbClient> ConnectAsync(Stream stream, CancellationToken ct = default) =>
        HandshakeAsync(null, stream, ct, ct);

    private static async Task<SdbClient> HandshakeAsync(TcpClient? tcp, Stream stream, CancellationToken handshakeCt,
        CancellationToken ct)
    {
        try
        {
            var hello = SdbProtocol.Encode(SdbProtocol.Cnxn, SdbProtocol.HostVersion, SdbProtocol.HostMaxPayload,
                SdbProtocol.CString("host::"));
            await stream.WriteAsync(hello, handshakeCt).ConfigureAwait(false);
            while (true)
            {
                var message = await SdbProtocol.ReadAsync(stream, handshakeCt).ConfigureAwait(false);
                switch (message.Command)
                {
                    case SdbProtocol.Cnxn:
                        var banner = Encoding.UTF8.GetString(message.Data).TrimEnd('\0');
                        var max = (int)Math.Min(message.Arg1 == 0 ? 4096 : message.Arg1, SdbProtocol.HostMaxPayload);
                        return new SdbClient(tcp, stream, banner, max);
                    case SdbProtocol.Auth:
                        throw new SdbConnectException(SdbConnectFailure.WantsKey, null);
                    default:
                        // a stray message before the answer (none seen so far): keep waiting
                        continue;
                }
            }
        }
        catch (SdbClosedException ex)
        {
            // what a TV in Developer Mode does when its "Host PC IP" is another computer: it accepts and hangs up
            throw new SdbConnectException(SdbConnectFailure.Refused, ex);
        }
        catch (IOException ex)
        {
            throw new SdbConnectException(SdbConnectFailure.Refused, ex);
        }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested)
        {
            throw new SdbConnectException(SdbConnectFailure.Silent, null);
        }
        catch (SdbProtocolException ex)
        {
            throw new SdbConnectException(SdbConnectFailure.NotSdb, ex);
        }
    }

    /// <summary>The device's <c>capability</c> list (platform_version, sdk_toolpath, …).</summary>
    public async Task<SdbCapability> GetCapabilityAsync(CancellationToken ct = default)
    {
        var raw = await RunServiceAsync("capability:", null, ct).ConfigureAwait(false);
        return SdbCapability.Parse(raw);
    }

    /// <summary>
    /// Runs a shell command and returns everything it printed. On a TV in Developer Mode only Samsung's fixed commands
    /// run ("0 getduid", "0 vd_appinstall", "0 was_execute", …); anything else prints nothing.
    /// </summary>
    public async Task<string> ShellAsync(string command, Action<string>? onOutput = null, CancellationToken ct = default)
    {
        var raw = await RunServiceAsync("shell:" + command, onOutput, ct).ConfigureAwait(false);
        return Encoding.UTF8.GetString(raw);
    }

    /// <summary>Copies bytes to a file on the TV (sync: SEND, DATA…, DONE; the TV answers OKAY or FAIL).</summary>
    public async Task PushAsync(ReadOnlyMemory<byte> content, string remotePath, int mode = 0x81ed /* 0100755 */,
        DateTimeOffset? modified = null, IProgress<double>? progress = null, CancellationToken ct = default)
    {
        await _gate.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            var stream = await OpenStreamAsync("sync:", ct).ConfigureAwait(false)
                ?? throw new SdbException("The TV did not accept a file copy.");
            var target = Encoding.UTF8.GetBytes(remotePath + "," + mode.ToString(System.Globalization.CultureInfo.InvariantCulture));
            var request = new MemoryStream();
            WriteSync(request, "SEND", (uint)target.Length);
            request.Write(target);
            await stream.WriteAsync(request.ToArray(), ct).ConfigureAwait(false);

            const int syncChunk = 64 * 1024; // sdb's SYNC_DATA_MAX
            for (var offset = 0; offset < content.Length; offset += syncChunk)
            {
                var chunk = content.Slice(offset, Math.Min(syncChunk, content.Length - offset));
                var frame = new byte[8 + chunk.Length];
                Encoding.ASCII.GetBytes("DATA", frame);
                BinaryPrimitives.WriteUInt32LittleEndian(frame.AsSpan(4), (uint)chunk.Length);
                chunk.Span.CopyTo(frame.AsSpan(8));
                await stream.WriteAsync(frame, ct).ConfigureAwait(false);
                progress?.Report(Math.Min(1.0, (offset + chunk.Length) / (double)Math.Max(1, content.Length)));
            }

            var done = new MemoryStream();
            WriteSync(done, "DONE", (uint)(modified ?? DateTimeOffset.UtcNow).ToUnixTimeSeconds());
            await stream.WriteAsync(done.ToArray(), ct).ConfigureAwait(false);

            var status = await stream.ReadExactlyAsync(8, ct).ConfigureAwait(false);
            var id = Encoding.ASCII.GetString(status, 0, 4);
            var length = BinaryPrimitives.ReadUInt32LittleEndian(status.AsSpan(4));
            if (id == "FAIL")
            {
                var reason = Encoding.UTF8.GetString(await stream.ReadExactlyAsync((int)Math.Min(length, 4096), ct).ConfigureAwait(false));
                await stream.CloseAsync(ct).ConfigureAwait(false);
                throw new SdbException($"The TV did not accept the file ({reason.Trim()}).");
            }

            if (id != "OKAY")
            {
                throw new SdbProtocolException($"Unexpected answer to a file copy: {id}.");
            }

            var quit = new MemoryStream();
            WriteSync(quit, "QUIT", 0);
            await stream.WriteAsync(quit.ToArray(), ct).ConfigureAwait(false);
            await stream.CloseAsync(ct).ConfigureAwait(false);
        }
        finally
        {
            _gate.Release();
        }
    }

    private static void WriteSync(Stream s, string id, uint value)
    {
        Span<byte> b = stackalloc byte[8];
        Encoding.ASCII.GetBytes(id, b);
        BinaryPrimitives.WriteUInt32LittleEndian(b[4..], value);
        s.Write(b);
    }

    /// <summary>Opens a service, collects everything it writes until it closes.</summary>
    private async Task<byte[]> RunServiceAsync(string service, Action<string>? onOutput, CancellationToken ct)
    {
        await _gate.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            var stream = await OpenStreamAsync(service, ct).ConfigureAwait(false);
            if (stream is null)
            {
                return [];
            }

            var all = new MemoryStream();
            while (true)
            {
                var chunk = await stream.ReadChunkAsync(ct).ConfigureAwait(false);
                if (chunk is null)
                {
                    break;
                }

                all.Write(chunk);
                onOutput?.Invoke(Encoding.UTF8.GetString(chunk));
            }

            return all.ToArray();
        }
        finally
        {
            _gate.Release();
        }
    }

    /// <summary>OPEN → OKAY. Null when the device refuses the service (it answers CLSE).</summary>
    private async Task<SdbStream?> OpenStreamAsync(string service, CancellationToken ct)
    {
        var localId = _nextLocalId;
        _nextLocalId += 2;
        await SendAsync(SdbProtocol.Open, localId, 0, SdbProtocol.CString(service), ct).ConfigureAwait(false);
        while (true)
        {
            var m = await SdbProtocol.ReadAsync(_stream, ct).ConfigureAwait(false);
            if (m.Command == SdbProtocol.Okay && m.Arg1 == localId)
            {
                return new SdbStream(this, localId, m.Arg0);
            }

            if (m.Command == SdbProtocol.Clse && (m.Arg1 == localId || m.Arg1 == 0))
            {
                return null;
            }

            // anything else belongs to an earlier stream that is already over: ignore it
        }
    }

    internal Task SendAsync(uint command, uint arg0, uint arg1, ReadOnlySpan<byte> data, CancellationToken ct)
    {
        var bytes = SdbProtocol.Encode(command, arg0, arg1, data);
        return _stream.WriteAsync(bytes, ct).AsTask();
    }

    internal Task<SdbProtocol.Message> ReceiveAsync(CancellationToken ct) => SdbProtocol.ReadAsync(_stream, ct);

    public async ValueTask DisposeAsync()
    {
        await _stream.DisposeAsync().ConfigureAwait(false);
        _tcp?.Dispose();
        _gate.Dispose();
    }

    /// <summary>One open service: WRTE out (waiting for each OKAY), WRTE in (acknowledged), CLSE.</summary>
    private sealed class SdbStream(SdbClient client, uint localId, uint remoteId)
    {
        private readonly Queue<byte[]> _received = new();
        private byte[] _pending = [];
        private int _pendingOffset;
        private bool _closed;

        /// <summary>The next piece the device wrote, or null once it closed the stream.</summary>
        public async Task<byte[]?> ReadChunkAsync(CancellationToken ct)
        {
            if (_pendingOffset < _pending.Length)
            {
                var rest = _pending[_pendingOffset..];
                _pending = [];
                _pendingOffset = 0;
                return rest;
            }

            if (_received.Count > 0)
            {
                return _received.Dequeue();
            }

            if (_closed)
            {
                return null;
            }

            while (true)
            {
                var m = await client.ReceiveAsync(ct).ConfigureAwait(false);
                if (m.Arg1 != localId)
                {
                    continue;
                }

                switch (m.Command)
                {
                    case SdbProtocol.Wrte:
                        await client.SendAsync(SdbProtocol.Okay, localId, remoteId, [], ct).ConfigureAwait(false);
                        return m.Data;
                    case SdbProtocol.Clse:
                        _closed = true;
                        await client.SendAsync(SdbProtocol.Clse, 0, remoteId, [], ct).ConfigureAwait(false);
                        return null;
                }
            }
        }

        public async Task<byte[]> ReadExactlyAsync(int count, CancellationToken ct)
        {
            var result = new byte[count];
            var filled = 0;
            while (filled < count)
            {
                if (_pendingOffset >= _pending.Length)
                {
                    var chunk = await ReadChunkAsync(ct).ConfigureAwait(false) ?? throw new SdbClosedException();
                    _pending = chunk;
                    _pendingOffset = 0;
                }

                var n = Math.Min(count - filled, _pending.Length - _pendingOffset);
                Array.Copy(_pending, _pendingOffset, result, filled, n);
                filled += n;
                _pendingOffset += n;
            }

            return result;
        }

        public async Task WriteAsync(ReadOnlyMemory<byte> data, CancellationToken ct)
        {
            for (var offset = 0; offset < data.Length; offset += client.MaxPayload)
            {
                var piece = data.Slice(offset, Math.Min(client.MaxPayload, data.Length - offset));
                await client.SendAsync(SdbProtocol.Wrte, localId, remoteId, piece.Span, ct).ConfigureAwait(false);
                // flow control: the next WRTE waits for the device's OKAY; what it writes meanwhile is kept
                while (true)
                {
                    var m = await client.ReceiveAsync(ct).ConfigureAwait(false);
                    if (m.Arg1 != localId)
                    {
                        continue;
                    }

                    if (m.Command == SdbProtocol.Okay)
                    {
                        break;
                    }

                    if (m.Command == SdbProtocol.Wrte)
                    {
                        _received.Enqueue(m.Data);
                        await client.SendAsync(SdbProtocol.Okay, localId, remoteId, [], ct).ConfigureAwait(false);
                        continue;
                    }

                    if (m.Command == SdbProtocol.Clse)
                    {
                        _closed = true;
                        throw new SdbClosedException();
                    }
                }
            }
        }

        public async Task CloseAsync(CancellationToken ct)
        {
            if (_closed)
            {
                return;
            }

            await client.SendAsync(SdbProtocol.Clse, localId, remoteId, [], ct).ConfigureAwait(false);
            // the device confirms with its own CLSE (it may send a last OKAY first)
            using var wait = CancellationTokenSource.CreateLinkedTokenSource(ct);
            wait.CancelAfter(TimeSpan.FromSeconds(5));
            try
            {
                while (true)
                {
                    var m = await client.ReceiveAsync(wait.Token).ConfigureAwait(false);
                    if (m.Command == SdbProtocol.Clse && m.Arg1 == localId)
                    {
                        break;
                    }
                }
            }
            catch (OperationCanceledException) when (!ct.IsCancellationRequested)
            {
                // no confirmation: the stream is over either way
            }

            _closed = true;
        }
    }
}

public enum SdbConnectFailure
{
    /// <summary>Nothing listens on the port (TV off, wrong address, Developer Mode off).</summary>
    NoAnswer,
    /// <summary>The TV took the connection and hung up (Developer Mode's Host PC IP is another computer).</summary>
    Refused,
    /// <summary>Connected, but no sdb answer in time.</summary>
    Silent,
    /// <summary>Something that is not sdb answered.</summary>
    NotSdb,
    /// <summary>The device wants an RSA key (ADB-style authentication; TVs in Developer Mode do not).</summary>
    WantsKey,
}

public sealed class SdbConnectException(SdbConnectFailure failure, Exception? inner)
    : SdbException("Could not connect to the TV over sdb: " + failure, inner)
{
    public SdbConnectFailure Failure { get; } = failure;
}
