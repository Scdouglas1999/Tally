using System.Buffers.Binary;
using System.Text;

namespace Tally.SamsungInstaller.Sdb;

/// <summary>
/// The device side of Samsung's sdb (Smart Development Bridge): the ADB-style framing sdbd on a TV (port 26101)
/// speaks. Every message is a 24-byte little-endian header (command, arg0, arg1, payload length, payload byte sum,
/// command XOR 0xFFFFFFFF) and the payload. Measured against the Tizen TV emulator's sdbd 2.2.31 by recording what
/// Tizen Studio's sdb sends (tv-web/installer/tests/fixtures/sdb-trace-emulator.txt).
/// </summary>
internal static class SdbProtocol
{
    public const uint Cnxn = 0x4e584e43; // "CNXN"
    public const uint Auth = 0x48545541; // "AUTH"
    public const uint Open = 0x4e45504f; // "OPEN"
    public const uint Okay = 0x59414b4f; // "OKAY"
    public const uint Clse = 0x45534c43; // "CLSE"
    public const uint Wrte = 0x45545257; // "WRTE"

    /// <summary>What Tizen Studio's sdb 4.x sends in its CNXN (arg0), and the payload size it offers (arg1).</summary>
    public const uint HostVersion = 0x00100000;
    public const uint HostMaxPayload = 0x40000;
    public const int HeaderSize = 24;
    /// <summary>A payload larger than this is not an sdb message (a different service on the port).</summary>
    public const int MaxAcceptedPayload = 1 << 20;

    public readonly record struct Message(uint Command, uint Arg0, uint Arg1, byte[] Data)
    {
        public override string ToString() => $"{Name(Command)}({Arg0:x}, {Arg1:x}, {Data.Length} bytes)";
    }

    public static string Name(uint command)
    {
        Span<byte> b = stackalloc byte[4];
        BinaryPrimitives.WriteUInt32LittleEndian(b, command);
        foreach (var c in b)
        {
            if (c < 0x20 || c > 0x7e)
            {
                return $"0x{command:x8}";
            }
        }

        return Encoding.ASCII.GetString(b);
    }

    public static byte[] Encode(uint command, uint arg0, uint arg1, ReadOnlySpan<byte> data)
    {
        var buffer = new byte[HeaderSize + data.Length];
        var span = buffer.AsSpan();
        BinaryPrimitives.WriteUInt32LittleEndian(span, command);
        BinaryPrimitives.WriteUInt32LittleEndian(span[4..], arg0);
        BinaryPrimitives.WriteUInt32LittleEndian(span[8..], arg1);
        BinaryPrimitives.WriteUInt32LittleEndian(span[12..], (uint)data.Length);
        BinaryPrimitives.WriteUInt32LittleEndian(span[16..], Checksum(data));
        BinaryPrimitives.WriteUInt32LittleEndian(span[20..], command ^ 0xffffffff);
        data.CopyTo(span[HeaderSize..]);
        return buffer;
    }

    public static uint Checksum(ReadOnlySpan<byte> data)
    {
        uint sum = 0;
        foreach (var b in data)
        {
            sum += b;
        }

        return sum;
    }

    public static async Task<Message> ReadAsync(Stream stream, CancellationToken ct)
    {
        var header = new byte[HeaderSize];
        await ReadExactlyAsync(stream, header, ct).ConfigureAwait(false);
        var command = BinaryPrimitives.ReadUInt32LittleEndian(header);
        var arg0 = BinaryPrimitives.ReadUInt32LittleEndian(header.AsSpan(4));
        var arg1 = BinaryPrimitives.ReadUInt32LittleEndian(header.AsSpan(8));
        var length = BinaryPrimitives.ReadUInt32LittleEndian(header.AsSpan(12));
        var magic = BinaryPrimitives.ReadUInt32LittleEndian(header.AsSpan(20));
        if (magic != (command ^ 0xffffffff) || length > MaxAcceptedPayload)
        {
            throw new SdbProtocolException("The TV answered with something that is not sdb.");
        }

        var data = length == 0 ? [] : new byte[length];
        if (length > 0)
        {
            await ReadExactlyAsync(stream, data, ct).ConfigureAwait(false);
        }

        return new Message(command, arg0, arg1, data);
    }

    private static async Task ReadExactlyAsync(Stream stream, byte[] buffer, CancellationToken ct)
    {
        var read = 0;
        while (read < buffer.Length)
        {
            var n = await stream.ReadAsync(buffer.AsMemory(read), ct).ConfigureAwait(false);
            if (n == 0)
            {
                throw new SdbClosedException();
            }

            read += n;
        }
    }

    /// <summary>A NUL-terminated ASCII string, as services and banners are sent.</summary>
    public static byte[] CString(string text) => Encoding.UTF8.GetBytes(text + "\0");
}

public class SdbException(string message, Exception? inner = null) : Exception(message, inner);

/// <summary>The TV (or what answered on its port) broke the protocol.</summary>
public sealed class SdbProtocolException(string message) : SdbException(message);

/// <summary>The connection closed (the TV refused this PC, went to sleep, or restarted).</summary>
public sealed class SdbClosedException() : SdbException("The TV closed the connection.");
