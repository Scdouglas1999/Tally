using System;
using System.Collections.Generic;
using System.Linq;

namespace Jellyfin.Plugin.Tally.Live;

public enum TsStreamKind
{
    Video,
    Audio,
    Other
}

public sealed record TsStream(int Pid, byte StreamType, TsStreamKind Kind);

/// <summary>What one MPEG-TS segment contains: its program layout, continuity counters and time span.
/// Timestamps are 90 kHz, unwrapped relative to the first one seen (a segment may straddle the 33-bit wrap).</summary>
public sealed class TsInfo
{
    public bool IsTs { get; set; }

    /// <summary>Bytes before the first sync byte (some CDNs prepend a fake image header).</summary>
    public int SyncOffset { get; set; }

    public int PmtPid { get; set; } = -1;

    public int PcrPid { get; set; } = -1;

    public List<TsStream> Streams { get; } = new();

    /// <summary>A PAT that fits one packet, verbatim.</summary>
    public byte[]? PatPacket { get; set; }

    /// <summary>A PMT that fits one packet, verbatim.</summary>
    public byte[]? PmtPacket { get; set; }

    public Dictionary<int, int> FirstCc { get; } = new();

    public Dictionary<int, int> LastCc { get; } = new();

    /// <summary>Earliest DTS (PTS when there is none) over all streams.</summary>
    public long? StartDts { get; set; }

    /// <summary>Where the next segment should begin: the latest stream's last DTS plus its last frame duration.</summary>
    public long? EndDts { get; set; }

    public long? FirstVideoPts { get; set; }

    /// <summary>First DTS of each elementary stream.</summary>
    public Dictionary<int, long> StartByPid { get; } = new();

    /// <summary>Last DTS + last frame duration of each elementary stream.</summary>
    public Dictionary<int, long> EndByPid { get; } = new();

    public int Packets { get; set; }

    public TsStream? Video => Streams.FirstOrDefault(s => s.Kind == TsStreamKind.Video);
}

/// <summary>How to turn a segment of a new source into a continuation of the stream already being played:
/// its PIDs mapped onto the first source's, its PAT/PMT replaced by the first source's, its continuity counters
/// continued, and every PTS/DTS/PCR shifted by <see cref="Offset"/> (mod 2^33).</summary>
public sealed class SpliceMap
{
    public static readonly SpliceMap Identity = new() { IsIdentity = true };

    public bool IsIdentity { get; init; }

    public long Offset { get; init; }

    /// <summary>Input PID → output PID. PIDs not listed are dropped (except the null PID).</summary>
    public Dictionary<int, int> PidMap { get; init; } = new();

    /// <summary>Output PID → amount added to the input continuity counter (mod 16).</summary>
    public Dictionary<int, int> CcDelta { get; init; } = new();

    /// <summary>Canonical PAT/PMT (single packets) written in place of the new source's.</summary>
    public byte[]? Pat { get; init; }

    public byte[]? Pmt { get; init; }

    public int InPmtPid { get; init; } = -1;

    public int OutPmtPid { get; init; } = -1;
}

/// <summary>
/// Makes segments from different upstreams play as one continuous transport stream. Jellyfin remuxes a channel
/// with one long-lived ffmpeg (<c>-codec copy -copyts</c>, HLS input), whose HLS demuxer feeds every segment into a
/// single MPEG-TS demuxer: a jump in timestamps stalls or garbles its output and a change of PIDs makes it lose the
/// streams it mapped. So a switch rewrites the new source's packets to look like more of the old one.
/// </summary>
public static class TsSplicer
{
    public const int PacketSize = 188;
    public const long Wrap = 1L << 33;
    private const long Mask = Wrap - 1;
    private const int NullPid = 0x1FFF;

    public static int FindSync(ReadOnlySpan<byte> data)
    {
        var limit = Math.Min(data.Length - PacketSize, 64 * 1024);
        for (var i = 0; i <= limit; i++)
        {
            if (data[i] != 0x47)
            {
                continue;
            }

            var ok = true;
            for (var k = 1; k <= 2 && i + k * PacketSize < data.Length; k++)
            {
                if (data[i + k * PacketSize] != 0x47)
                {
                    ok = false;
                    break;
                }
            }

            if (ok)
            {
                return i;
            }
        }

        return -1;
    }

    public static TsInfo Analyze(ReadOnlySpan<byte> data)
    {
        var info = new TsInfo();
        var sync = FindSync(data);
        if (sync < 0)
        {
            return info;
        }

        info.IsTs = true;
        info.SyncOffset = sync;
        long? reference = null;
        var lastDts = new Dictionary<int, long>();
        var prevDts = new Dictionary<int, long>();
        var firstDts = new Dictionary<int, long>();
        var esPids = new HashSet<int>();
        var adtsPids = new HashSet<int>();
        var lastAudioPes = new Dictionary<int, System.IO.MemoryStream>();

        for (var i = sync; i + PacketSize <= data.Length; i += PacketSize)
        {
            var p = data.Slice(i, PacketSize);
            if (p[0] != 0x47)
            {
                break;
            }

            info.Packets++;
            var pid = ((p[1] & 0x1F) << 8) | p[2];
            var pusi = (p[1] & 0x40) != 0;
            var afc = (p[3] >> 4) & 0x3;
            var cc = p[3] & 0xF;
            if (afc is 1 or 3)
            {
                info.FirstCc.TryAdd(pid, cc);
                info.LastCc[pid] = cc;
            }

            var payload = PayloadStart(p);
            if (payload < 0)
            {
                continue;
            }

            if (pid == 0 && pusi && info.PmtPid < 0)
            {
                ReadPat(p, payload, info);
            }
            else if (pid == info.PmtPid && pusi && info.Streams.Count == 0)
            {
                ReadPmt(p, payload, info);
                foreach (var s in info.Streams)
                {
                    esPids.Add(s.Pid);
                    if (s.StreamType == 0x0F)
                    {
                        adtsPids.Add(s.Pid);
                    }
                }
            }
            else if (!pusi && adtsPids.Contains(pid) && lastAudioPes.TryGetValue(pid, out var cont))
            {
                cont.Write(p[payload..]);
            }
            else if (pusi && esPids.Contains(pid) && TryReadPesTimes(p[payload..], out var pts, out var dts))
            {
                var d = dts ?? pts;
                if (d is null)
                {
                    continue;
                }

                reference ??= d.Value;
                var u = Unwrap(d.Value, reference.Value);
                firstDts.TryAdd(pid, u);
                if (lastDts.TryGetValue(pid, out var last))
                {
                    prevDts[pid] = last;
                }

                lastDts[pid] = u;
                if (adtsPids.Contains(pid))
                {
                    // keep the last PES of an AAC stream: its frame count gives the stream's exact end
                    var ms = new System.IO.MemoryStream();
                    var pes = p[payload..];
                    var header = pes.Length > 9 ? 9 + pes[8] : pes.Length;
                    if (header < pes.Length)
                    {
                        ms.Write(pes[header..]);
                    }

                    lastAudioPes[pid] = ms;
                }
                if (pts is { } vp && info.FirstVideoPts == null && info.Video?.Pid == pid)
                {
                    info.FirstVideoPts = Unwrap(vp, reference.Value);
                }
            }
        }

        if (firstDts.Count > 0)
        {
            info.StartDts = firstDts.Values.Min();
            long end = long.MinValue;
            foreach (var (pid, last) in lastDts)
            {
                var frame = lastAudioPes.TryGetValue(pid, out var pesBytes) && AdtsDuration(pesBytes.ToArray()) is { } exact ? exact
                    : prevDts.TryGetValue(pid, out var prev) && last > prev && last - prev < 90000 ? last - prev : 3000;
                info.EndByPid[pid] = last + frame;
                end = Math.Max(end, last + frame);
            }

            foreach (var (pid, first) in firstDts)
            {
                info.StartByPid[pid] = first;
            }

            info.EndDts = end;
        }

        return info;
    }

    /// <summary>
    /// The splice that makes <paramref name="next"/> (first segment of the new source) continue right after
    /// <paramref name="previousOut"/> (the last segment published, as served). Each stream is lined up with its
    /// counterpart and the whole segment is shifted by the largest of those offsets, so no stream steps back in time
    /// (a copy remux rejects that) and the other one gets at most a gap of a frame or two. Null when the layouts cannot
    /// be merged (different codecs, no PMT, not TS) — the caller falls back to a discontinuity.
    /// </summary>
    public static SpliceMap? Plan(TsInfo canonical, TsInfo next, TsInfo previousOut)
    {
        if (!canonical.IsTs || !next.IsTs || canonical.PatPacket == null || canonical.PmtPacket == null
            || next.PmtPid < 0 || next.StartDts == null || previousOut.EndDts == null || canonical.Streams.Count == 0)
        {
            return null;
        }

        var map = new Dictionary<int, int>();
        foreach (var kind in new[] { TsStreamKind.Video, TsStreamKind.Audio, TsStreamKind.Other })
        {
            var outs = canonical.Streams.Where(s => s.Kind == kind).ToList();
            var ins = next.Streams.Where(s => s.Kind == kind).ToList();
            for (var k = 0; k < ins.Count && k < outs.Count; k++)
            {
                if (ins[k].StreamType != outs[k].StreamType)
                {
                    if (kind == TsStreamKind.Other)
                    {
                        continue;
                    }

                    return null; // a different codec cannot continue a copy remux
                }

                map[ins[k].Pid] = outs[k].Pid;
            }

            if (kind != TsStreamKind.Other && outs.Count > 0 && ins.Count == 0)
            {
                return null; // the new source lacks a stream the player has mapped
            }
        }

        var canonicalEs = canonical.Streams.Select(s => s.Pid).ToHashSet();
        if (next.PcrPid >= 0 && !map.ContainsKey(next.PcrPid) && canonical.PcrPid >= 0 && !canonicalEs.Contains(canonical.PcrPid))
        {
            map[next.PcrPid] = canonical.PcrPid; // dedicated PCR PID on both sides
        }

        long? offset = null;
        foreach (var (inPid, outPid) in map)
        {
            if (next.StartByPid.TryGetValue(inPid, out var start) && previousOut.EndByPid.TryGetValue(outPid, out var end))
            {
                offset = offset is { } o ? Math.Max(o, end - start) : end - start;
            }
        }

        var result = new SpliceMap
        {
            Offset = Mod(offset ?? (previousOut.EndDts.Value - next.StartDts.Value)),
            PidMap = map,
            Pat = canonical.PatPacket,
            Pmt = canonical.PmtPacket,
            InPmtPid = next.PmtPid,
            OutPmtPid = canonical.PmtPid
        };
        return WithContinuity(result, next, previousOut.LastCc);
    }

    /// <summary>The same splice with continuity counters that carry on from <paramref name="previousLastCcOut"/> —
    /// computed per segment, because some packagers restart their counters in every segment.</summary>
    public static SpliceMap WithContinuity(SpliceMap map, TsInfo next, IReadOnlyDictionary<int, int> previousLastCcOut)
    {
        if (map.IsIdentity)
        {
            return map;
        }

        var delta = new Dictionary<int, int>();
        void Continue(int inPid, int outPid)
        {
            if (next.FirstCc.TryGetValue(inPid, out var first))
            {
                var want = previousLastCcOut.TryGetValue(outPid, out var last) ? (last + 1) & 0xF : first;
                delta[outPid] = (want - first + 16) & 0xF;
            }
        }

        foreach (var (i, o) in map.PidMap)
        {
            Continue(i, o);
        }

        Continue(0, 0);
        Continue(map.InPmtPid, map.OutPmtPid);
        return new SpliceMap
        {
            Offset = map.Offset,
            PidMap = map.PidMap,
            CcDelta = delta,
            Pat = map.Pat,
            Pmt = map.Pmt,
            InPmtPid = map.InPmtPid,
            OutPmtPid = map.OutPmtPid
        };
    }

    /// <summary>Applies a splice. The input is never modified.</summary>
    public static byte[] Rewrite(ReadOnlySpan<byte> data, SpliceMap map)
    {
        if (map.IsIdentity)
        {
            return data.ToArray();
        }

        var sync = FindSync(data);
        if (sync < 0)
        {
            return data.ToArray();
        }

        var output = new byte[data.Length - sync];
        var o = 0;
        for (var i = sync; i + PacketSize <= data.Length; i += PacketSize)
        {
            var src = data.Slice(i, PacketSize);
            if (src[0] != 0x47)
            {
                break;
            }

            var pid = ((src[1] & 0x1F) << 8) | src[2];
            int outPid;
            var replace = (byte[]?)null;
            if (pid == 0)
            {
                outPid = 0;
                replace = map.Pat;
            }
            else if (pid == map.InPmtPid)
            {
                outPid = map.OutPmtPid;
                replace = map.Pmt;
            }
            else if (pid == NullPid)
            {
                outPid = NullPid;
            }
            else if (!map.PidMap.TryGetValue(pid, out outPid))
            {
                continue; // a stream the first source did not have: the player never mapped it
            }

            var p = output.AsSpan(o, PacketSize);
            if (replace != null)
            {
                if ((src[1] & 0x40) == 0)
                {
                    continue; // continuation of a multi-packet section: the canonical table fits one packet
                }

                replace.CopyTo(p);
                p[3] = (byte)((p[3] & 0xF0) | (src[3] & 0x0F)); // take the input counter, adjusted below
            }
            else
            {
                src.CopyTo(p);
                p[1] = (byte)((p[1] & 0xE0) | ((outPid >> 8) & 0x1F));
                p[2] = (byte)(outPid & 0xFF);
            }

            var afc = (p[3] >> 4) & 0x3;
            if (afc is 1 or 3 && map.CcDelta.TryGetValue(outPid, out var d))
            {
                p[3] = (byte)((p[3] & 0xF0) | ((p[3] + d) & 0xF));
            }

            if (replace == null && map.Offset != 0)
            {
                ShiftPcr(p, map.Offset);
                var payload = PayloadStart(p);
                if (payload >= 0 && (p[1] & 0x40) != 0)
                {
                    ShiftPes(p[payload..], map.Offset);
                }
            }

            o += PacketSize;
        }

        return o == output.Length ? output : output.AsSpan(0, o).ToArray();
    }

    public static long Mod(long v) => ((v % Wrap) + Wrap) % Wrap;

    /// <summary>A 33-bit value near <paramref name="reference"/>, as a signed distance from it.</summary>
    public static long Unwrap(long value, long reference)
    {
        var d = Mod(value - reference);
        if (d >= Wrap / 2)
        {
            d -= Wrap;
        }

        return reference + d;
    }

    private static int PayloadStart(ReadOnlySpan<byte> p)
    {
        var afc = (p[3] >> 4) & 0x3;
        if (afc is 0 or 2)
        {
            return -1;
        }

        var start = 4;
        if (afc == 3)
        {
            start += 1 + p[4];
        }

        return start < PacketSize ? start : -1;
    }

    private static void ReadPat(ReadOnlySpan<byte> p, int payload, TsInfo info)
    {
        var s = payload + 1 + p[payload]; // pointer_field
        if (s + 8 > PacketSize || p[s] != 0x00)
        {
            return;
        }

        var sectionLength = ((p[s + 1] & 0x0F) << 8) | p[s + 2];
        var end = Math.Min(s + 3 + sectionLength - 4, PacketSize);
        for (var k = s + 8; k + 4 <= end; k += 4)
        {
            var program = (p[k] << 8) | p[k + 1];
            if (program != 0)
            {
                info.PmtPid = ((p[k + 2] & 0x1F) << 8) | p[k + 3];
                break;
            }
        }

        if (s + 3 + sectionLength <= PacketSize && p[payload] == 0)
        {
            info.PatPacket = p.ToArray();
        }
    }

    private static void ReadPmt(ReadOnlySpan<byte> p, int payload, TsInfo info)
    {
        var s = payload + 1 + p[payload];
        if (s + 12 > PacketSize || p[s] != 0x02)
        {
            return;
        }

        var sectionLength = ((p[s + 1] & 0x0F) << 8) | p[s + 2];
        var fits = s + 3 + sectionLength <= PacketSize && p[payload] == 0;
        var end = Math.Min(s + 3 + sectionLength - 4, PacketSize);
        info.PcrPid = ((p[s + 8] & 0x1F) << 8) | p[s + 9];
        var programInfo = ((p[s + 10] & 0x0F) << 8) | p[s + 11];
        for (var k = s + 12 + programInfo; k + 5 <= end;)
        {
            var type = p[k];
            var pid = ((p[k + 1] & 0x1F) << 8) | p[k + 2];
            var esInfo = ((p[k + 3] & 0x0F) << 8) | p[k + 4];
            info.Streams.Add(new TsStream(pid, type, KindOf(type)));
            k += 5 + esInfo;
        }

        if (fits)
        {
            info.PmtPacket = p.ToArray();
        }
    }

    /// <summary>Duration (90 kHz) of the ADTS frames in an AAC PES payload; null when it does not parse.</summary>
    public static long? AdtsDuration(ReadOnlySpan<byte> payload)
    {
        ReadOnlySpan<int> rates = stackalloc int[] { 96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350 };
        var frames = 0;
        var rate = 0;
        var i = 0;
        while (i + 7 <= payload.Length && payload[i] == 0xFF && (payload[i + 1] & 0xF0) == 0xF0)
        {
            var sri = (payload[i + 2] >> 2) & 0x0F;
            if (sri >= rates.Length)
            {
                return null;
            }

            rate = rates[sri];
            var len = ((payload[i + 3] & 0x03) << 11) | (payload[i + 4] << 3) | (payload[i + 5] >> 5);
            if (len < 7)
            {
                return null;
            }

            frames += (payload[i + 6] & 0x03) + 1;
            i += len;
        }

        return frames > 0 && rate > 0 ? frames * 1024L * 90000 / rate : null;
    }

    public static TsStreamKind KindOf(byte streamType) => streamType switch
    {
        0x01 or 0x02 or 0x10 or 0x1B or 0x24 or 0x42 or 0xEA => TsStreamKind.Video,
        0x03 or 0x04 or 0x0F or 0x11 or 0x81 or 0x87 => TsStreamKind.Audio,
        _ => TsStreamKind.Other
    };

    private static bool HasPesHeader(byte streamId)
        => streamId is not (0xBC or 0xBE or 0xBF or 0xF0 or 0xF1 or 0xF2 or 0xF8 or 0xFF);

    private static bool TryReadPesTimes(ReadOnlySpan<byte> pes, out long? pts, out long? dts)
    {
        pts = null;
        dts = null;
        if (pes.Length < 14 || pes[0] != 0 || pes[1] != 0 || pes[2] != 1 || !HasPesHeader(pes[3]))
        {
            return false;
        }

        var flags = pes[7] >> 6;
        if (flags is 2 or 3)
        {
            pts = ReadTimestamp(pes[9..]);
        }

        if (flags == 3 && pes.Length >= 19)
        {
            dts = ReadTimestamp(pes[14..]);
        }

        return pts != null;
    }

    private static void ShiftPes(Span<byte> pes, long offset)
    {
        if (pes.Length < 14 || pes[0] != 0 || pes[1] != 0 || pes[2] != 1 || !HasPesHeader(pes[3]))
        {
            return;
        }

        var flags = pes[7] >> 6;
        if (flags is 2 or 3)
        {
            WriteTimestamp(pes[9..], Mod(ReadTimestamp(pes[9..]) + offset));
        }

        if (flags == 3 && pes.Length >= 19)
        {
            WriteTimestamp(pes[14..], Mod(ReadTimestamp(pes[14..]) + offset));
        }
    }

    private static void ShiftPcr(Span<byte> p, long offset)
    {
        var afc = (p[3] >> 4) & 0x3;
        if (afc < 2 || p[4] < 7 || (p[5] & 0x10) == 0)
        {
            return;
        }

        long b = ((long)p[6] << 25) | ((long)p[7] << 17) | ((long)p[8] << 9) | ((long)p[9] << 1) | ((long)p[10] >> 7);
        b = Mod(b + offset);
        p[6] = (byte)(b >> 25);
        p[7] = (byte)(b >> 17);
        p[8] = (byte)(b >> 9);
        p[9] = (byte)(b >> 1);
        p[10] = (byte)((int)((b & 1) << 7) | (p[10] & 0x7F));
    }

    public static long ReadTimestamp(ReadOnlySpan<byte> t)
        => ((long)(t[0] & 0x0E) << 29) | ((long)t[1] << 22) | ((long)(t[2] & 0xFE) << 14) | ((long)t[3] << 7) | ((long)t[4] >> 1);

    private static void WriteTimestamp(Span<byte> t, long v)
    {
        // keep the 4-bit prefix ('0010' PTS only, '0011' PTS of a pair, '0001' DTS) and the marker bits
        t[0] = (byte)((t[0] & 0xF0) | (int)((v >> 29) & 0x0E) | 1);
        t[1] = (byte)(v >> 22);
        t[2] = (byte)(((v >> 14) & 0xFE) | 1);
        t[3] = (byte)(v >> 7);
        t[4] = (byte)(((v << 1) & 0xFE) | 1);
    }
}
