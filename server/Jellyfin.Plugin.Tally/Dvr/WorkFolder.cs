using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;

namespace Jellyfin.Plugin.Tally.Dvr;

/// <summary>A recorded segment: its file in the work folder and its place in the timeline.</summary>
public sealed record RecordedSegment(int Number, double Duration, bool Discontinuity, long Bytes)
{
    public string FileName => Number.ToString("D6", CultureInfo.InvariantCulture) + ".ts";
}

/// <summary>
/// A recording's work folder: one file per segment as it arrives (<c>000001.ts</c>…) and <c>segments.txt</c>, one
/// line per segment written after the segment itself, so after a crash or restart every listed segment is complete.
/// It lives in <c>&lt;recordings&gt;/.tally-work/&lt;job&gt;/</c>: on the recordings drive (the space checks and the final
/// move cover one volume) but hidden from Jellyfin (a dot folder holding an <c>.ignore</c> file).
/// </summary>
public sealed class WorkFolder
{
    public const string ParentName = ".tally-work";
    private const string IndexName = "segments.txt";

    private readonly object _gate = new();
    private readonly List<RecordedSegment> _segments = new();

    public WorkFolder(string path)
    {
        Path = path;
    }

    public string Path { get; }

    public IReadOnlyList<RecordedSegment> Segments
    {
        get
        {
            lock (_gate)
            {
                return _segments.ToList();
            }
        }
    }

    public long Bytes
    {
        get
        {
            lock (_gate)
            {
                return _segments.Sum(s => s.Bytes);
            }
        }
    }

    public double Seconds
    {
        get
        {
            lock (_gate)
            {
                return _segments.Sum(s => s.Duration);
            }
        }
    }

    public static string ParentFor(string recordingsFolder) => System.IO.Path.Combine(recordingsFolder, ParentName);

    /// <summary>Creates (or reopens) the folder and reads what it already holds.</summary>
    public static WorkFolder Open(string path)
    {
        var parent = System.IO.Path.GetDirectoryName(path)!;
        Directory.CreateDirectory(path);
        var ignore = System.IO.Path.Combine(parent, ".ignore");
        if (!File.Exists(ignore))
        {
            File.WriteAllText(ignore, "Tally's recordings in progress. Jellyfin skips folders that hold an .ignore file.\n");
        }

        var wf = new WorkFolder(path);
        wf.Load();
        return wf;
    }

    private void Load()
    {
        var index = System.IO.Path.Combine(Path, IndexName);
        if (!File.Exists(index))
        {
            return;
        }

        foreach (var line in File.ReadAllLines(index))
        {
            var parts = line.Split('\t');
            if (parts.Length < 4
                || !int.TryParse(parts[0], NumberStyles.Integer, CultureInfo.InvariantCulture, out var n)
                || !double.TryParse(parts[1], NumberStyles.Float, CultureInfo.InvariantCulture, out var d)
                || !long.TryParse(parts[3], NumberStyles.Integer, CultureInfo.InvariantCulture, out var b))
            {
                continue; // a line cut short by a crash
            }

            var seg = new RecordedSegment(n, d, parts[2] == "1", b);
            var file = new FileInfo(System.IO.Path.Combine(Path, seg.FileName));
            if (file.Exists && file.Length == b)
            {
                _segments.Add(seg);
            }
        }
    }

    public int NextNumber
    {
        get
        {
            lock (_gate)
            {
                return _segments.Count == 0 ? 1 : _segments[^1].Number + 1;
            }
        }
    }

    public RecordedSegment Append(byte[] bytes, double duration, bool discontinuity)
    {
        RecordedSegment seg;
        lock (_gate)
        {
            seg = new RecordedSegment(_segments.Count == 0 ? 1 : _segments[^1].Number + 1, duration, discontinuity, bytes.Length);
        }

        var file = System.IO.Path.Combine(Path, seg.FileName);
        using (var fs = new FileStream(file, FileMode.Create, FileAccess.Write, FileShare.Read))
        {
            fs.Write(bytes);
            fs.Flush(true);
        }

        var line = string.Create(CultureInfo.InvariantCulture, $"{seg.Number}\t{seg.Duration:0.000}\t{(seg.Discontinuity ? 1 : 0)}\t{seg.Bytes}\n");
        File.AppendAllText(System.IO.Path.Combine(Path, IndexName), line, Encoding.ASCII);
        lock (_gate)
        {
            _segments.Add(seg);
        }

        return seg;
    }

    public string SegmentPath(RecordedSegment seg) => System.IO.Path.Combine(Path, seg.FileName);

    public byte[]? ReadSegment(RecordedSegment seg)
    {
        try
        {
            return File.ReadAllBytes(SegmentPath(seg));
        }
        catch (IOException)
        {
            return null;
        }
    }

    /// <summary>The start-over playlist: everything recorded so far, growing (EVENT) until the recording ends.</summary>
    public string Playlist(Func<RecordedSegment, string> uri, bool ended)
    {
        var segs = Segments;
        var sb = new StringBuilder(256 + (segs.Count * 90));
        var target = segs.Count == 0 ? 6 : (int)Math.Ceiling(segs.Max(s => s.Duration) - 0.001);
        sb.Append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-PLAYLIST-TYPE:EVENT\n");
        sb.Append("#EXT-X-TARGETDURATION:").Append(Math.Max(1, target).ToString(CultureInfo.InvariantCulture)).Append('\n');
        sb.Append("#EXT-X-MEDIA-SEQUENCE:0\n");
        foreach (var s in segs)
        {
            if (s.Discontinuity)
            {
                sb.Append("#EXT-X-DISCONTINUITY\n");
            }

            sb.Append("#EXTINF:").Append(s.Duration.ToString("0.000", CultureInfo.InvariantCulture)).Append(",\n");
            sb.Append(uri(s)).Append('\n');
        }

        if (ended)
        {
            sb.Append("#EXT-X-ENDLIST\n");
        }

        return sb.ToString();
    }

    public void Delete()
    {
        try
        {
            if (Directory.Exists(Path))
            {
                Directory.Delete(Path, recursive: true);
            }
        }
        catch (IOException)
        {
        }
        catch (UnauthorizedAccessException)
        {
        }
    }
}
