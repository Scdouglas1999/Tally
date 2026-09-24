using System.Text;
using Jellyfin.Plugin.Tally.Services;
using Xunit;

namespace Tally.Tests;

public class QrEncoderTests
{
    // ================= Independent decoder (test-only) =================

    private readonly record struct Info(int Total, int Ec, int G1, int G1D, int G2, int G2D)
    {
        public int Data => G1 * G1D + G2 * G2D;
        public int Blocks => G1 + G2;
    }

    // Independent copy of the level-M table (index = version).
    private static readonly Info[] T =
    {
        default,
        new(26, 10, 1, 16, 0, 0), new(44, 16, 1, 28, 0, 0), new(70, 26, 1, 44, 0, 0),
        new(100, 18, 2, 32, 0, 0), new(134, 24, 2, 43, 0, 0), new(172, 16, 4, 27, 0, 0),
        new(196, 18, 4, 31, 0, 0), new(242, 22, 2, 38, 2, 39), new(292, 22, 3, 36, 2, 37),
        new(346, 26, 4, 43, 1, 44), new(404, 30, 1, 50, 4, 51), new(466, 22, 6, 36, 2, 37),
        new(532, 22, 8, 37, 1, 38), new(581, 24, 4, 40, 5, 41), new(655, 24, 5, 41, 5, 42),
    };

    private static readonly int[][] Align =
    {
        new int[0], new int[0],
        new[] { 6, 18 }, new[] { 6, 22 }, new[] { 6, 26 }, new[] { 6, 30 }, new[] { 6, 34 },
        new[] { 6, 22, 38 }, new[] { 6, 24, 42 }, new[] { 6, 26, 46 }, new[] { 6, 28, 50 },
        new[] { 6, 30, 54 }, new[] { 6, 32, 58 }, new[] { 6, 34, 62 },
        new[] { 6, 26, 46, 66 }, new[] { 6, 26, 48, 70 },
    };

    private static int VersionOf(bool[,] m) => (m.GetLength(0) - 17) / 4;

    // Recompute the function-module map purely from the version.
    private static bool[,] FunctionMap(int version)
    {
        int size = 17 + 4 * version;
        var f = new bool[size, size];
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++)
                // finder+separator blocks plus the format/dark-module strips
                f[y, x] = (x <= 8 && y <= 8) || (x >= size - 8 && y <= 8) || (x <= 8 && y >= size - 8);
        for (int i = 0; i < size; i++) { f[6, i] = true; f[i, 6] = true; }   // timing
        int[] ap = Align[version];
        for (int i = 0; i < ap.Length; i++)
            for (int j = 0; j < ap.Length; j++)
            {
                if ((i == 0 && j == 0) || (i == 0 && j == ap.Length - 1) || (i == ap.Length - 1 && j == 0)) continue;
                for (int dy = -2; dy <= 2; dy++)
                    for (int dx = -2; dx <= 2; dx++)
                        f[ap[j] + dy, ap[i] + dx] = true;
            }
        if (version >= 7)
            for (int i = 0; i < 18; i++)
            {
                int a = size - 11 + i % 3, b = i / 3;
                f[b, a] = true;
                f[a, b] = true;
            }
        return f;
    }

    private static bool MaskBit(int mask, int x, int y) => mask switch
    {
        0 => (x + y) % 2 == 0,
        1 => y % 2 == 0,
        2 => x % 3 == 0,
        3 => (x + y) % 3 == 0,
        4 => (x / 3 + y / 2) % 2 == 0,
        5 => (x * y) % 2 + (x * y) % 3 == 0,
        6 => ((x * y) % 2 + (x * y) % 3) % 2 == 0,
        7 => ((x + y) % 2 + (x * y) % 3) % 2 == 0,
        _ => throw new ArgumentOutOfRangeException(nameof(mask)),
    };

    private static int ReadFormat(bool[,] m, bool secondCopy)
    {
        int size = m.GetLength(0);
        int bits = 0;
        void Bit(int i, bool v) { if (v) bits |= 1 << i; }
        if (!secondCopy)
        {
            for (int i = 0; i <= 5; i++) Bit(i, m[i, 8]);
            Bit(6, m[7, 8]);
            Bit(7, m[8, 8]);
            Bit(8, m[8, 7]);
            for (int i = 9; i < 15; i++) Bit(i, m[8, 14 - i]);
        }
        else
        {
            for (int i = 0; i < 8; i++) Bit(i, m[8, size - 1 - i]);
            for (int i = 8; i < 15; i++) Bit(i, m[size - 15 + i, 8]);
        }
        return bits;
    }

    // Unmask + zig-zag walk -> raw codeword sequence (data and EC interleaved).
    private static byte[] ExtractCodewords(bool[,] m)
    {
        int size = m.GetLength(0);
        int version = VersionOf(m);
        var f = FunctionMap(version);
        int mask = ((ReadFormat(m, false) ^ 0x5412) >> 10) & 7;

        var u = (bool[,])m.Clone();
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++)
                if (!f[y, x] && MaskBit(mask, x, y))
                    u[y, x] = !u[y, x];

        var bits = new List<bool>();
        for (int right = size - 1; right >= 1; right -= 2)
        {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++)
                for (int j = 0; j < 2; j++)
                {
                    int x = right - j;
                    int y = ((right + 1) & 2) == 0 ? size - 1 - vert : vert;
                    if (!f[y, x]) bits.Add(u[y, x]);
                }
        }
        int total = T[version].Total;
        var cw = new byte[total];
        for (int i = 0; i < total * 8; i++)
            if (bits[i]) cw[i >> 3] |= (byte)(0x80 >> (i & 7));
        return cw;
    }

    // De-interleave into per-block data codewords; concatenated in block order.
    private static byte[][] Deinterleave(byte[] cw, int version)
    {
        var info = T[version];
        int nb = info.Blocks;
        var lens = new int[nb];
        var blocks = new byte[nb][];
        for (int b = 0; b < nb; b++)
        {
            lens[b] = b < info.G1 ? info.G1D : info.G2D;
            blocks[b] = new byte[lens[b]];
        }
        int idx = 0;
        int maxLen = Math.Max(info.G1D, info.G2D);
        for (int i = 0; i < maxLen; i++)
            for (int b = 0; b < nb; b++)
                if (i < lens[b]) blocks[b][i] = cw[idx++];
        idx += nb * info.Ec;    // skip interleaved EC codewords
        Assert.Equal(cw.Length, idx);
        return blocks;
    }

    private static string Decode(bool[,] m)
    {
        int version = VersionOf(m);
        var blocks = Deinterleave(ExtractCodewords(m), version);
        var stream = blocks.SelectMany(b => b).ToArray();

        int pos = 0;
        int GetBits(int n)
        {
            int v = 0;
            for (int k = 0; k < n; k++, pos++)
                v = (v << 1) | ((stream[pos >> 3] >> (7 - (pos & 7))) & 1);
            return v;
        }

        Assert.Equal(0b0100, GetBits(4));                    // byte mode
        int count = GetBits(version <= 9 ? 8 : 16);
        var payload = new byte[count];
        for (int i = 0; i < count; i++) payload[i] = (byte)GetBits(8);
        return Encoding.UTF8.GetString(payload);
    }

    // GF(256) helpers for the test (independent of the encoder).
    private static int GfMul(int a, int b)
    {
        int p = 0;
        while (b > 0)
        {
            if ((b & 1) != 0) p ^= a;
            a <<= 1;
            if (a >= 256) a ^= 0x11D;
            b >>= 1;
        }
        return p;
    }

    private static int[] RsGenerator(int degree)
    {
        var gen = new int[degree];
        gen[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++)
        {
            for (int j = 0; j < degree; j++)
            {
                gen[j] = GfMul(gen[j], root);
                if (j + 1 < degree) gen[j] ^= gen[j + 1];
            }
            root = GfMul(root, 2);
        }
        return gen;
    }

    private static string StringOf(int len)
    {
        const string chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789abcdefghijklmnopqrstuvwxyz-._~:/?#[]@!$&'()*+,;=";
        var sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.Append(chars[i % chars.Length]);
        return sb.ToString();
    }

    // ================= Tests =================

    [Fact]
    public void Size_MatchesVersion()
    {
        var m = QrEncoder.Encode("HELLO");
        Assert.Equal(21, m.GetLength(0));
        Assert.Equal(21, m.GetLength(1));

        // 100 ASCII bytes -> version 6 (capacities: v5-M=84 < 100 <= v6-M=106)
        var url = "http://example.com/" + new string('a', 100 - "http://example.com/".Length);
        var m6 = QrEncoder.Encode(url);
        Assert.Equal(41, m6.GetLength(0));
        Assert.Equal(m6.GetLength(0), 17 + 4 * VersionOf(m6));
    }

    [Fact]
    public void FunctionPatterns_AreCorrect()
    {
        var m = QrEncoder.Encode("HELLO");
        int n = m.GetLength(0);
        int v = VersionOf(m);

        // Three 7x7 finder rings
        foreach (var (ox, oy) in new[] { (0, 0), (n - 7, 0), (0, n - 7) })
            for (int y = 0; y < 7; y++)
                for (int x = 0; x < 7; x++)
                {
                    int d = Math.Max(Math.Abs(x - 3), Math.Abs(y - 3));
                    Assert.Equal(d != 2, m[oy + y, ox + x]);
                }

        // Timing patterns alternate, anchored dark at the finder edge
        for (int x = 8; x <= n - 9; x++) Assert.Equal(x % 2 == 0, m[6, x]);
        for (int y = 8; y <= n - 9; y++) Assert.Equal(y % 2 == 0, m[y, 6]);

        // Dark module
        Assert.True(m[4 * v + 9, 8]);
    }

    [Fact]
    public void FormatInfo_IsValidBch_LevelM()
    {
        var m = QrEncoder.Encode("HELLO");
        int fmt1 = ReadFormat(m, false);
        int fmt2 = ReadFormat(m, true);
        Assert.Equal(fmt1, fmt2);

        int unmasked = fmt1 ^ 0x5412;
        // BCH(15,5) remainder must be zero: divide by generator 0x537
        int r = unmasked;
        for (int i = 14; i >= 10; i--)
            if (((r >> i) & 1) != 0) r ^= 0x537 << (i - 10);
        Assert.Equal(0, r);

        Assert.Equal(0, (unmasked >> 13) & 3);      // EC level bits = 00 (M)
        int mask = (unmasked >> 10) & 7;
        Assert.InRange(mask, 0, 7);
    }

    [Theory]
    [InlineData("HELLO")]
    [InlineData("http://192.168.1.50:8096/JellyTV/Get")]
    [InlineData("Zoë’s TV – ¡vamos!")]
    public void RoundTrip_FixedStrings(string text)
    {
        Assert.Equal(text, Decode(QrEncoder.Encode(text)));
    }

    [Fact]
    public void RoundTrip_150CharUrl()
    {
        var url = ("http://192.168.1.50:8096/JellyTV/Get?q=" + new string('x', 200)).Substring(0, 150);
        Assert.Equal(150, url.Length);
        var m = QrEncoder.Encode(url);
        Assert.Equal(49, m.GetLength(0));           // version 8 (v7-M cap 122 < 150 <= v8-M cap 152)
        Assert.Equal(url, Decode(m));
    }

    [Fact]
    public void RoundTrip_EveryVersion()
    {
        // Max byte-mode payload for level M: largest n with 4 + cci + 8n + 4 <= dataCw*8
        for (int v = 1; v <= 15; v++)
        {
            int cci = v <= 9 ? 8 : 16;
            int cap = (T[v].Data * 8 - 8 - cci) / 8;
            string s = StringOf(cap);
            var m = QrEncoder.Encode(s);
            Assert.Equal(17 + 4 * v, m.GetLength(0));
            Assert.Equal(s, Decode(m));
        }
    }

    [Fact]
    public void ReedSolomon_RemainderIsZero()
    {
        // Version 1-M: single block of 16 data + 10 EC codewords.
        var cw = ExtractCodewords(QrEncoder.Encode("HELLO"));
        Assert.Equal(26, cw.Length);

        var gen = RsGenerator(10);                  // degree-10 generator, leading coeff implicit 1
        var work = cw.Select(b => (int)b).ToArray();
        // Polynomial division: x^10 * M(x) must be divisible by G(x), i.e. remainder 0.
        for (int i = 0; i + 10 < work.Length; i++)
        {
            int factor = work[i];
            if (factor == 0) continue;
            work[i] = 0;
            for (int j = 0; j < 10; j++)
                work[i + 1 + j] ^= GfMul(gen[j], factor);
        }
        Assert.All(work, c => Assert.Equal(0, c));
    }

    [Fact]
    public void ToSvg_HasQuietZoneSinglePath()
    {
        var m = QrEncoder.Encode("HELLO");
        int n = m.GetLength(0);
        string svg = QrEncoder.ToSvg(m);

        Assert.Contains($"viewBox=\"0 0 {n + 8} {n + 8}\"", svg);
        Assert.Equal(1, svg.Split("<path").Length - 1);
        Assert.Contains("shape-rendering=\"crispEdges\"", svg);

        int dark = 0;
        for (int y = 0; y < n; y++)
            for (int x = 0; x < n; x++)
                if (m[y, x]) dark++;
        int d0 = svg.IndexOf("d=\"", StringComparison.Ordinal);
        int d1 = svg.IndexOf('"', d0 + 3);
        string d = svg.Substring(d0 + 3, d1 - d0 - 3);
        Assert.Equal(dark, d.Split('M').Length - 1);
    }

    [Fact]
    public void TooLong_Throws()
    {
        Assert.Throws<ArgumentException>(() => QrEncoder.Encode(new string('x', 3000)));
    }
}
