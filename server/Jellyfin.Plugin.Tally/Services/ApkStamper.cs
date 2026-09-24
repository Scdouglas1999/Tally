using System;
using System.Buffers.Binary;
using System.Text;

namespace Jellyfin.Plugin.Tally.Services;

/// <summary>Writes this server's address into an APK so the TV app knows where home is the first time it
/// opens, and nobody has to type an address with a remote.
/// The value goes into the APK Signing Block as one more ID-value pair. The v2/v3 signature covers the zip
/// entries, the central directory and the end-of-central-directory record, but deliberately not the signing
/// block's own pair list (and treats the central-directory offset as unmoved), so the APK still verifies and
/// still updates over, and is updated by, the unstamped release. Same technique as Meituan's Walle.</summary>
public static class ApkStamper
{
    /// <summary>Pair id the app looks for ("JTV\n").</summary>
    public const uint PairId = 0x4A54560A;

    private const int EocdMinSize = 22;
    private static readonly byte[] Magic = Encoding.ASCII.GetBytes("APK Sig Block 42");

    /// <summary>Returns a stamped copy, or null when the file is not an APK with a signing block (never
    /// serve something we could have damaged: the caller falls back to the untouched download).</summary>
    public static byte[]? Stamp(ReadOnlySpan<byte> apk, string value)
    {
        if (!TryLocate(apk, out var eocd, out var blockStart, out var cdOffset))
        {
            return null;
        }

        var payload = Encoding.UTF8.GetBytes(value);
        var oldPairs = apk[(blockStart + 8)..(cdOffset - 24)];

        // keep every existing pair except a previous stamp of ours
        var kept = new byte[oldPairs.Length];
        var keptLength = 0;
        for (var p = 0; p + 12 <= oldPairs.Length;)
        {
            var length = (long)BinaryPrimitives.ReadUInt64LittleEndian(oldPairs[p..]);
            if (length < 4 || p + 8 + length > oldPairs.Length)
            {
                return null;
            }

            var whole = (int)(8 + length);
            if (BinaryPrimitives.ReadUInt32LittleEndian(oldPairs[(p + 8)..]) != PairId)
            {
                oldPairs.Slice(p, whole).CopyTo(kept.AsSpan(keptLength));
                keptLength += whole;
            }

            p += whole;
        }

        var pairsLength = keptLength + 12 + payload.Length;
        var blockSize = (ulong)(pairsLength + 24);          // excludes the leading size field
        var newBlockLength = 8 + pairsLength + 24;
        var delta = newBlockLength - (cdOffset - blockStart);

        var result = new byte[apk.Length + delta];
        var o = 0;
        apk[..blockStart].CopyTo(result);
        o += blockStart;
        BinaryPrimitives.WriteUInt64LittleEndian(result.AsSpan(o), blockSize);
        o += 8;
        kept.AsSpan(0, keptLength).CopyTo(result.AsSpan(o));
        o += keptLength;
        BinaryPrimitives.WriteUInt64LittleEndian(result.AsSpan(o), (ulong)(payload.Length + 4));
        BinaryPrimitives.WriteUInt32LittleEndian(result.AsSpan(o + 8), PairId);
        payload.CopyTo(result.AsSpan(o + 12));
        o += 12 + payload.Length;
        BinaryPrimitives.WriteUInt64LittleEndian(result.AsSpan(o), blockSize);
        Magic.CopyTo(result.AsSpan(o + 8));
        o += 24;
        apk[cdOffset..].CopyTo(result.AsSpan(o));

        BinaryPrimitives.WriteUInt32LittleEndian(result.AsSpan(eocd + delta + 16), (uint)(cdOffset + delta));
        return result;
    }

    /// <summary>Reads a stamp back (used by tests; the app has its own reader).</summary>
    public static string? Read(ReadOnlySpan<byte> apk)
    {
        if (!TryLocate(apk, out _, out var blockStart, out var cdOffset))
        {
            return null;
        }

        var pairs = apk[(blockStart + 8)..(cdOffset - 24)];
        for (var p = 0; p + 12 <= pairs.Length;)
        {
            var length = (long)BinaryPrimitives.ReadUInt64LittleEndian(pairs[p..]);
            if (length < 4 || p + 8 + length > pairs.Length)
            {
                return null;
            }

            if (BinaryPrimitives.ReadUInt32LittleEndian(pairs[(p + 8)..]) == PairId)
            {
                return Encoding.UTF8.GetString(pairs.Slice(p + 12, (int)length - 4));
            }

            p += (int)(8 + length);
        }

        return null;
    }

    private static bool TryLocate(ReadOnlySpan<byte> apk, out int eocd, out int blockStart, out int cdOffset)
    {
        eocd = blockStart = cdOffset = 0;
        var lowest = Math.Max(0, apk.Length - EocdMinSize - ushort.MaxValue);
        for (var i = apk.Length - EocdMinSize; i >= lowest; i--)
        {
            if (BinaryPrimitives.ReadUInt32LittleEndian(apk[i..]) == 0x06054b50
                && i + EocdMinSize + BinaryPrimitives.ReadUInt16LittleEndian(apk[(i + 20)..]) == apk.Length)
            {
                eocd = i;
                break;
            }
        }

        if (eocd == 0)
        {
            return false;
        }

        var cd = BinaryPrimitives.ReadUInt32LittleEndian(apk[(eocd + 16)..]);
        if (cd < 32 || cd > eocd || !apk.Slice((int)cd - 16, 16).SequenceEqual(Magic))
        {
            return false;
        }

        cdOffset = (int)cd;
        var size = BinaryPrimitives.ReadUInt64LittleEndian(apk[(cdOffset - 24)..]);
        if (size < 24 || size > (ulong)(cdOffset - 8))
        {
            return false;
        }

        blockStart = cdOffset - (int)size - 8;
        return BinaryPrimitives.ReadUInt64LittleEndian(apk[blockStart..]) == size;
    }
}
