using System.Text;

namespace Jellyfin.Plugin.Tally.Services;

/// <summary>
/// Minimal dependency-free QR Code encoder (ISO/IEC 18004), byte mode, error-correction level M,
/// versions 1 through 15.
/// </summary>
public static class QrEncoder
{
    private readonly record struct EcInfo(int TotalCodewords, int EcPerBlock, int Group1Blocks, int Group1Data, int Group2Blocks, int Group2Data)
    {
        public int DataCodewords => Group1Blocks * Group1Data + Group2Blocks * Group2Data;
        public int NumBlocks => Group1Blocks + Group2Blocks;
    }

    // Level-M block structure for versions 1..15 (index = version):
    //   version: total codewords, ec codewords per block, g1 blocks x data cw, g2 blocks x data cw
    private static readonly EcInfo[] Table =
    {
        default,
        new(26, 10, 1, 16, 0, 0),
        new(44, 16, 1, 28, 0, 0),
        new(70, 26, 1, 44, 0, 0),
        new(100, 18, 2, 32, 0, 0),
        new(134, 24, 2, 43, 0, 0),
        new(172, 16, 4, 27, 0, 0),
        new(196, 18, 4, 31, 0, 0),
        new(242, 22, 2, 38, 2, 39),
        new(292, 22, 3, 36, 2, 37),
        new(346, 26, 4, 43, 1, 44),
        new(404, 30, 1, 50, 4, 51),
        new(466, 22, 6, 36, 2, 37),
        new(532, 22, 8, 37, 1, 38),
        new(581, 24, 4, 40, 5, 41),
        new(655, 24, 5, 41, 5, 42),
    };

    // Index = version. Remainder bits: v1:0, v2-6:7, v7-13:0, v14-15:3.
    private static readonly int[] RemainderBits = { 0, 0, 7, 7, 7, 7, 7, 0, 0, 0, 0, 0, 0, 0, 3, 3 };

    // Alignment pattern center coordinates, index = version (empty for v1).
    private static readonly int[][] AlignmentPositions =
    {
        System.Array.Empty<int>(), System.Array.Empty<int>(),
        new[] { 6, 18 }, new[] { 6, 22 }, new[] { 6, 26 }, new[] { 6, 30 }, new[] { 6, 34 },
        new[] { 6, 22, 38 }, new[] { 6, 24, 42 }, new[] { 6, 26, 46 }, new[] { 6, 28, 50 },
        new[] { 6, 30, 54 }, new[] { 6, 32, 58 }, new[] { 6, 34, 62 },
        new[] { 6, 26, 46, 66 }, new[] { 6, 26, 48, 70 },
    };

    /// <summary>
    /// Encodes <paramref name="text"/> (UTF-8, byte mode) at EC level M, choosing the smallest
    /// version 1..15 that fits and the best of the 8 mask patterns by the standard penalty rules.
    /// Returns the module matrix WITHOUT quiet zone: result[y, x] == true means a dark module.
    /// </summary>
    /// <exception cref="ArgumentException">Thrown when the text does not fit in version 15.</exception>
    public static bool[,] Encode(string text)
    {
        if (text is null) throw new ArgumentNullException(nameof(text));
        byte[] data = Encoding.UTF8.GetBytes(text);

        int version = -1;
        for (int v = 1; v <= 15; v++)
        {
            int ccBits = v <= 9 ? 8 : 16;
            if (4 + ccBits + data.Length * 8 <= Table[v].DataCodewords * 8)
            {
                version = v;
                break;
            }
        }
        if (version < 0)
            throw new ArgumentException($"Text does not fit in a version-15 QR code ({data.Length} bytes).", nameof(text));

        return BuildMatrix(AddErrorCorrection(BuildDataCodewords(data, version), version), version);
    }

    /// <summary>
    /// Produces an SVG document for a module matrix. The viewBox includes a 4-module quiet zone;
    /// all dark modules are emitted in a single <path>.
    /// </summary>
    public static string ToSvg(bool[,] modules, string dark = "#0e0f0e", string light = "#e3e5de")
    {
        int n = modules.GetLength(0);
        var sb = new StringBuilder();
        sb.Append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ").Append(n + 8).Append(' ').Append(n + 8)
          .Append("\" shape-rendering=\"crispEdges\">");
        sb.Append("<rect width=\"").Append(n + 8).Append("\" height=\"").Append(n + 8).Append("\" fill=\"").Append(light).Append("\"/>");
        sb.Append("<path fill=\"").Append(dark).Append("\" d=\"");
        for (int y = 0; y < n; y++)
            for (int x = 0; x < n; x++)
                if (modules[y, x])
                    sb.Append("M ").Append(x + 4).Append(' ').Append(y + 4).Append(" h1 v1 h-1 z");
        sb.Append("\"/>");
        sb.Append("</svg>");
        return sb.ToString();
    }

    // ---------- Data encoding ----------

    private static byte[] BuildDataCodewords(byte[] data, int version)
    {
        var info = Table[version];
        int capacityBits = info.DataCodewords * 8;
        var bb = new BitBuffer(capacityBits);
        bb.Append(0b0100, 4);                                   // byte mode
        bb.Append(data.Length, version <= 9 ? 8 : 16);          // char count indicator
        foreach (byte b in data) bb.Append(b, 8);
        bb.Append(0, Math.Min(4, capacityBits - bb.Count));     // terminator
        while (bb.Count % 8 != 0) bb.Append(0, 1);
        for (int pad = 0xEC; bb.Count < capacityBits; pad ^= 0xEC ^ 0x11)
            bb.Append(pad, 8);
        return bb.ToBytes();
    }

    private static byte[] AddErrorCorrection(byte[] dataCodewords, int version)
    {
        var info = Table[version];
        int numBlocks = info.NumBlocks, ecLen = info.EcPerBlock;
        var gen = RsGenerator(ecLen);

        var blocks = new byte[numBlocks][];
        var ecs = new byte[numBlocks][];
        int offset = 0;
        for (int b = 0; b < numBlocks; b++)
        {
            int len = b < info.Group1Blocks ? info.Group1Data : info.Group2Data;
            var blk = new byte[len];
            Array.Copy(dataCodewords, offset, blk, 0, len);
            offset += len;
            blocks[b] = blk;
            ecs[b] = RsRemainder(blk, gen);
        }

        var result = new byte[info.TotalCodewords];
        int k = 0;
        int maxLen = Math.Max(info.Group1Data, info.Group2Data);
        for (int i = 0; i < maxLen; i++)
            for (int b = 0; b < numBlocks; b++)
                if (i < blocks[b].Length) result[k++] = blocks[b][i];
        for (int i = 0; i < ecLen; i++)
            for (int b = 0; b < numBlocks; b++)
                result[k++] = ecs[b][i];
        return result;
    }

    // ---------- Matrix construction ----------

    private static bool[,] BuildMatrix(byte[] codewords, int version)
    {
        int size = 17 + 4 * version;
        var m = new bool[size, size];
        var f = new bool[size, size];   // function modules

        void Set(int x, int y, bool dark) { m[y, x] = dark; f[y, x] = true; }

        void DrawFinder(int cx, int cy)
        {
            for (int dy = -4; dy <= 4; dy++)
                for (int dx = -4; dx <= 4; dx++)
                {
                    int x = cx + dx, y = cy + dy;
                    if (x >= 0 && x < size && y >= 0 && y < size)
                    {
                        int d = Math.Max(Math.Abs(dx), Math.Abs(dy));
                        Set(x, y, d != 2 && d != 4);
                    }
                }
        }

        void DrawFormat(int mask)
        {
            int data = mask;                       // EC level M => format bits 00
            int rem = data;
            for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >> 9) * 0x537);
            int bits = ((data << 10) | rem) ^ 0x5412;

            for (int i = 0; i <= 5; i++) Set(8, i, ((bits >> i) & 1) != 0);
            Set(8, 7, ((bits >> 6) & 1) != 0);
            Set(8, 8, ((bits >> 7) & 1) != 0);
            Set(7, 8, ((bits >> 8) & 1) != 0);
            for (int i = 9; i < 15; i++) Set(14 - i, 8, ((bits >> i) & 1) != 0);

            for (int i = 0; i < 8; i++) Set(size - 1 - i, 8, ((bits >> i) & 1) != 0);
            for (int i = 8; i < 15; i++) Set(8, size - 15 + i, ((bits >> i) & 1) != 0);
            Set(8, size - 8, true);                // dark module
        }

        void DrawVersion()
        {
            int rem = version;
            for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >> 11) * 0x1F25);
            int bits = (version << 12) | rem;
            for (int i = 0; i < 18; i++)
            {
                bool b = ((bits >> i) & 1) != 0;
                int a = size - 11 + i % 3, c = i / 3;
                Set(a, c, b);
                Set(c, a, b);
            }
        }

        void ApplyMask(int mask)
        {
            for (int y = 0; y < size; y++)
                for (int x = 0; x < size; x++)
                    if (!f[y, x] && MaskBit(mask, x, y))
                        m[y, x] = !m[y, x];
        }

        // Timing patterns
        for (int i = 0; i < size; i++)
        {
            Set(6, i, i % 2 == 0);
            Set(i, 6, i % 2 == 0);
        }
        // Finder patterns + separators (centers)
        DrawFinder(3, 3);
        DrawFinder(size - 4, 3);
        DrawFinder(3, size - 4);
        // Alignment patterns (skip the three finder corners)
        int[] ap = AlignmentPositions[version];
        for (int i = 0; i < ap.Length; i++)
            for (int j = 0; j < ap.Length; j++)
            {
                if ((i == 0 && j == 0) || (i == 0 && j == ap.Length - 1) || (i == ap.Length - 1 && j == 0))
                    continue;
                for (int dy = -2; dy <= 2; dy++)
                    for (int dx = -2; dx <= 2; dx++)
                        Set(ap[i] + dx, ap[j] + dy, Math.Max(Math.Abs(dx), Math.Abs(dy)) != 1);
            }
        // Reserve format areas (drawn for real later, once per candidate mask)
        DrawFormat(0);
        if (version >= 7) DrawVersion();

        // Zig-zag data placement, skipping the vertical timing column
        int bit = 0, totalBits = codewords.Length * 8;
        for (int right = size - 1; right >= 1; right -= 2)
        {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++)
                for (int j = 0; j < 2; j++)
                {
                    int x = right - j;
                    int y = ((right + 1) & 2) == 0 ? size - 1 - vert : vert;
                    if (!f[y, x] && bit < totalBits)
                    {
                        m[y, x] = ((codewords[bit >> 3] >> (7 - (bit & 7))) & 1) != 0;
                        bit++;
                    }
                }
        }

        // Pick the mask with the lowest penalty (ties -> lowest mask index)
        int bestMask = 0, bestScore = int.MaxValue;
        for (int mask = 0; mask < 8; mask++)
        {
            ApplyMask(mask);
            DrawFormat(mask);
            int score = PenaltyScore(m);
            if (score < bestScore) { bestScore = score; bestMask = mask; }
            ApplyMask(mask);
        }
        ApplyMask(bestMask);
        DrawFormat(bestMask);
        return m;
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
        _ => false,
    };

    // ---------- Penalty rules (ISO/IEC 18004) ----------
    // Run-length based N1/N3 with finder-ratio (k:k:3k:k:k) detection, matching libqrencode:
    // the quiet zone beyond each edge counts as a light run for N3.

    private static int PenaltyScore(bool[,] m)
    {
        int n = m.GetLength(0);
        int score = 0;

        // N2: 2x2 same-color blocks
        for (int y = 0; y + 1 < n; y++)
            for (int x = 0; x + 1 < n; x++)
                if (m[y, x] == m[y, x + 1] && m[y, x] == m[y + 1, x] && m[y, x] == m[y + 1, x + 1])
                    score += 3;

        var runs = new int[n + 1];
        for (int pass = 0; pass < 2; pass++)
            for (int a = 0; a < n; a++)
            {
                // Build run lengths: even indices = light runs, odd = dark.
                // runs[0] = -1 when the line starts dark (no leading light run).
                int head = 0;
                runs[0] = 1;
                for (int i = 0; i < n; i++)
                {
                    bool dark = pass == 0 ? m[a, i] : m[i, a];
                    if (i == 0 && dark)
                    {
                        runs[0] = -1;
                        head = 1;
                        runs[head] = 1;
                    }
                    else if (i > 0)
                    {
                        bool prev = pass == 0 ? m[a, i - 1] : m[i - 1, a];
                        if (dark != prev) { head++; runs[head] = 0; }
                        runs[head]++;
                    }
                    else
                    {
                        runs[head]++;
                    }
                }
                int length = head + 1;
                for (int i = 0; i < length; i++)
                {
                    if (runs[i] >= 5) score += 3 + runs[i] - 5;          // N1
                    if ((i & 1) != 0 && i >= 3 && i < length - 2 && runs[i] % 3 == 0)
                    {
                        int fact = runs[i] / 3;
                        if (runs[i - 2] == fact && runs[i - 1] == fact && runs[i + 1] == fact && runs[i + 2] == fact)
                        {
                            if (i == 3 || runs[i - 3] >= 4 * fact) score += 40;          // N3, preceded
                            else if (i + 4 >= length || runs[i + 3] >= 4 * fact) score += 40; // N3, followed
                        }
                    }
                }
            }

        // N4: balance of dark modules (integer percentage)
        int darkCount = 0;
        for (int y = 0; y < n; y++)
            for (int x = 0; x < n; x++)
                if (m[y, x]) darkCount++;
        score += Math.Abs(100 * darkCount / (n * n) - 50) / 5 * 10;
        return score;
    }

    // ---------- Reed-Solomon over GF(256), polynomial 0x11D ----------

    private static readonly int[] GfExp = new int[512];
    private static readonly int[] GfLog = new int[256];

    static QrEncoder()
    {
        int x = 1;
        for (int i = 0; i < 255; i++)
        {
            GfExp[i] = x;
            GfLog[x] = i;
            x <<= 1;
            if (x >= 256) x ^= 0x11D;
        }
        for (int i = 255; i < 512; i++) GfExp[i] = GfExp[i - 255];
    }

    private static int GfMul(int a, int b) => a == 0 || b == 0 ? 0 : GfExp[GfLog[a] + GfLog[b]];

    private static byte[] RsGenerator(int degree)
    {
        var gen = new byte[degree];
        gen[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++)
        {
            for (int j = 0; j < degree; j++)
            {
                gen[j] = (byte)GfMul(gen[j], root);
                if (j + 1 < degree) gen[j] ^= gen[j + 1];
            }
            root = GfMul(root, 2);
        }
        return gen;
    }

    private static byte[] RsRemainder(byte[] data, byte[] gen)
    {
        var rem = new byte[gen.Length];
        foreach (byte b in data)
        {
            int factor = b ^ rem[0];
            Array.Copy(rem, 1, rem, 0, rem.Length - 1);
            rem[rem.Length - 1] = 0;
            for (int i = 0; i < rem.Length; i++)
                rem[i] ^= (byte)GfMul(gen[i], factor);
        }
        return rem;
    }

    private sealed class BitBuffer
    {
        private readonly List<bool> _bits;

        public BitBuffer(int capacity) => _bits = new List<bool>(capacity);
        public int Count => _bits.Count;

        public void Append(int value, int length)
        {
            for (int i = length - 1; i >= 0; i--) _bits.Add(((value >> i) & 1) != 0);
        }

        public byte[] ToBytes()
        {
            var res = new byte[(_bits.Count + 7) / 8];
            for (int i = 0; i < _bits.Count; i++)
                if (_bits[i]) res[i >> 3] |= (byte)(0x80 >> (i & 7));
            return res;
        }
    }
}
