using System;
using System.Buffers.Binary;
using System.IO;
using System.Linq;
using System.Text;
using Jellyfin.Plugin.Tally.Services;
using Xunit;

namespace Tally.Tests;

public class ApkStamperTests
{
    /// <summary>The smallest thing shaped like a signed APK: entries, a signing block with one pair, a central
    /// directory and an end record whose offset points at the central directory.</summary>
    private static byte[] FakeApk(string eocdComment = "")
    {
        var entries = Encoding.ASCII.GetBytes("PK-entries-go-here-0123456789");
        var pairValue = new byte[40];
        new Random(7).NextBytes(pairValue);
        using var block = new MemoryStream();
        using (var w = new BinaryWriter(block, Encoding.ASCII, true))
        {
            ulong size = (ulong)(8 + 4 + pairValue.Length + 24);
            w.Write(size);
            w.Write((ulong)(4 + pairValue.Length));
            w.Write(0x7109871aU);                    // the v2 signature pair id
            w.Write(pairValue);
            w.Write(size);
            w.Write(Encoding.ASCII.GetBytes("APK Sig Block 42"));
        }

        var centralDirectory = Encoding.ASCII.GetBytes("central-directory-records");
        var comment = Encoding.ASCII.GetBytes(eocdComment);
        var eocd = new byte[22 + comment.Length];
        BinaryPrimitives.WriteUInt32LittleEndian(eocd, 0x06054b50);
        BinaryPrimitives.WriteUInt32LittleEndian(eocd.AsSpan(12), (uint)centralDirectory.Length);
        BinaryPrimitives.WriteUInt32LittleEndian(eocd.AsSpan(16), (uint)(entries.Length + block.Length));
        BinaryPrimitives.WriteUInt16LittleEndian(eocd.AsSpan(20), (ushort)comment.Length);
        comment.CopyTo(eocd, 22);
        return entries.Concat(block.ToArray()).Concat(centralDirectory).Concat(eocd).ToArray();
    }

    [Fact]
    public void A_Stamp_Can_Be_Read_Back_And_Nothing_Signed_Moves_Or_Changes()
    {
        var apk = FakeApk();
        var stamped = ApkStamper.Stamp(apk, "http://203.0.113.7:8096")!;

        Assert.Equal("http://203.0.113.7:8096", ApkStamper.Read(stamped));
        Assert.Null(ApkStamper.Read(apk));

        // what the v2 signature covers: the entries, the central directory, the end record minus its offset field
        var grew = stamped.Length - apk.Length;
        Assert.True(apk.AsSpan(0, 29).SequenceEqual(stamped.AsSpan(0, 29)));
        var cdOld = (int)BinaryPrimitives.ReadUInt32LittleEndian(apk.AsSpan(apk.Length - 6));
        var cdNew = (int)BinaryPrimitives.ReadUInt32LittleEndian(stamped.AsSpan(stamped.Length - 6));
        Assert.Equal(cdOld + grew, cdNew);
        Assert.True(apk.AsSpan(cdOld, apk.Length - cdOld - 6).SequenceEqual(stamped.AsSpan(cdNew, stamped.Length - cdNew - 6)));
        // and the original signature pair is still there, byte for byte
        Assert.True(stamped.AsSpan().IndexOf(apk.AsSpan(29 + 8, 52)) > 0);
    }

    [Fact]
    public void Stamping_Twice_Replaces_The_Address_Instead_Of_Stacking()
    {
        var once = ApkStamper.Stamp(FakeApk(), "http://old-address:8096")!;
        var twice = ApkStamper.Stamp(once, "http://new:1")!;

        Assert.Equal("http://new:1", ApkStamper.Read(twice));
        Assert.Equal(-1, Encoding.ASCII.GetString(twice).IndexOf("old-address", StringComparison.Ordinal));
    }

    [Fact]
    public void An_End_Record_With_A_Comment_Is_Still_Found()
        => Assert.Equal("http://h", ApkStamper.Read(ApkStamper.Stamp(FakeApk("built by ci"), "http://h")!));

    [Theory]
    [InlineData("")]
    [InlineData("<html>GitHub is having a bad day</html>")]
    public void Anything_That_Is_Not_A_Signed_Apk_Is_Refused_Rather_Than_Damaged(string body)
        => Assert.Null(ApkStamper.Stamp(Encoding.ASCII.GetBytes(body), "http://h"));

    [Fact]
    public void A_Truncated_Download_Is_Refused()
    {
        var apk = FakeApk();
        Assert.Null(ApkStamper.Stamp(apk.AsSpan(0, apk.Length - 9), "http://h"));
    }
}
