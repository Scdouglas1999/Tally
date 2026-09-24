using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Tally.Dvr;

/// <summary>
/// Turns a work folder of segments into one file. The normal way is a stream-copy remux with Jellyfin's own ffmpeg
/// (no transcode), fed the segments in order on stdin as one transport stream (they are one continuous timeline, see
/// <see cref="RecordingSplicer"/>), into MP4 with the index at the front: MP4 direct-plays in every browser, so
/// Jellyfin web plays the file without the server remuxing it, and the Tally app's players direct-play it too. MKV
/// is the fallback for codecs MP4 cannot hold. When the drive has no room for a second copy, the segments are joined
/// into one <c>.ts</c> in place instead (each segment deleted as soon as it is appended), which Jellyfin also plays.
/// </summary>
public sealed class RecordingFinisher
{
    private const string ConcatMarker = "concat.txt";
    private readonly Func<string?> _ffmpeg;
    private readonly Func<string?> _ffprobe;
    private readonly ILogger _logger;

    public RecordingFinisher(Func<string?> ffmpeg, Func<string?> ffprobe, ILogger logger)
    {
        _ffmpeg = ffmpeg;
        _ffprobe = ffprobe;
        _logger = logger;
    }

    /// <summary>Remuxes to <paramref name="output"/>; null when it worked, else the error.</summary>
    public async Task<string?> RemuxAsync(WorkFolder work, string output, string format, string title, CancellationToken ct)
    {
        var ffmpeg = _ffmpeg();
        if (string.IsNullOrEmpty(ffmpeg) || !File.Exists(ffmpeg))
        {
            return "Jellyfin's ffmpeg was not found";
        }

        var psi = new ProcessStartInfo(ffmpeg)
        {
            RedirectStandardInput = true,
            RedirectStandardError = true,
            RedirectStandardOutput = true,
            UseShellExecute = false,
            CreateNoWindow = true
        };
        foreach (var a in new[] { "-hide_banner", "-nostats", "-loglevel", "error", "-fflags", "+genpts", "-f", "mpegts", "-i", "pipe:0",
                     "-map", "0:v:0?", "-map", "0:a?", "-c", "copy", "-map_metadata", "-1", "-metadata", "title=" + title })
        {
            psi.ArgumentList.Add(a);
        }

        if (format == "mp4")
        {
            psi.ArgumentList.Add("-movflags");
            psi.ArgumentList.Add("+faststart");
        }

        foreach (var a in new[] { "-f", format, "-y", output })
        {
            psi.ArgumentList.Add(a);
        }

        var errors = new List<string>();
        using var process = new Process { StartInfo = psi };
        process.ErrorDataReceived += (_, e) =>
        {
            if (!string.IsNullOrWhiteSpace(e.Data))
            {
                lock (errors)
                {
                    errors.Add(e.Data);
                    if (errors.Count > 20)
                    {
                        errors.RemoveAt(0);
                    }
                }
            }
        };
        process.OutputDataReceived += (_, _) => { };
        if (!process.Start())
        {
            return "ffmpeg did not start";
        }

        process.BeginErrorReadLine();
        process.BeginOutputReadLine();
        try
        {
            var stdin = process.StandardInput.BaseStream;
            foreach (var seg in work.Segments)
            {
                ct.ThrowIfCancellationRequested();
                var bytes = work.ReadSegment(seg);
                if (bytes == null)
                {
                    continue;
                }

                await stdin.WriteAsync(bytes, ct).ConfigureAwait(false);
            }

            await stdin.FlushAsync(ct).ConfigureAwait(false);
            stdin.Close();
        }
        catch (IOException)
        {
            // ffmpeg quit early; its exit code and log say why
        }
        catch (OperationCanceledException)
        {
            TryKill(process);
            throw;
        }

        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(TimeSpan.FromMinutes(30));
        try
        {
            await process.WaitForExitAsync(timeout.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            TryKill(process);
            if (ct.IsCancellationRequested)
            {
                throw;
            }

            return "ffmpeg took longer than 30 minutes";
        }

        if (process.ExitCode != 0)
        {
            lock (errors)
            {
                return $"ffmpeg exited with {process.ExitCode}: {string.Join(" | ", errors.TakeLast(3))}";
            }
        }

        return File.Exists(output) ? null : "ffmpeg wrote no file";
    }

    /// <summary>Duration in seconds and whether there is a video stream, from Jellyfin's ffprobe; null when it
    /// cannot tell.</summary>
    public async Task<(double Duration, bool HasVideo)?> ProbeAsync(string file, CancellationToken ct)
    {
        var ffprobe = _ffprobe();
        if (string.IsNullOrEmpty(ffprobe) || !File.Exists(ffprobe))
        {
            return null;
        }

        var psi = new ProcessStartInfo(ffprobe)
        {
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true
        };
        foreach (var a in new[] { "-v", "error", "-show_entries", "format=duration:stream=codec_type", "-of", "json", file })
        {
            psi.ArgumentList.Add(a);
        }

        try
        {
            using var process = Process.Start(psi);
            if (process == null)
            {
                return null;
            }

            var stdout = process.StandardOutput.ReadToEndAsync(ct);
            _ = process.StandardError.ReadToEndAsync(ct);
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
            timeout.CancelAfter(TimeSpan.FromMinutes(2));
            await process.WaitForExitAsync(timeout.Token).ConfigureAwait(false);
            using var doc = JsonDocument.Parse(await stdout.ConfigureAwait(false));
            var root = doc.RootElement;
            double duration = 0;
            if (root.TryGetProperty("format", out var f) && f.TryGetProperty("duration", out var d))
            {
                double.TryParse(d.GetString(), NumberStyles.Float, CultureInfo.InvariantCulture, out duration);
            }

            var video = root.TryGetProperty("streams", out var streams) && streams.ValueKind == JsonValueKind.Array
                && streams.EnumerateArray().Any(s => s.TryGetProperty("codec_type", out var t) && t.GetString() == "video");
            return (duration, video);
        }
        catch (Exception ex) when (ex is IOException or JsonException or InvalidOperationException or System.ComponentModel.Win32Exception or OperationCanceledException)
        {
            if (ct.IsCancellationRequested)
            {
                throw;
            }

            _logger.LogDebug("JellyTV DVR: ffprobe of {File} failed: {Message}", file, ex.Message);
            return null;
        }
    }

    /// <summary>
    /// Joins the segments into <paramref name="output"/>. In place: each segment is deleted once appended, and
    /// <c>concat.txt</c> in the work folder records how far the join got (last segment, file length), so a join cut
    /// short by a restart continues where it stopped instead of appending a segment twice.
    /// </summary>
    public static void Concat(WorkFolder work, string output, bool inPlace, CancellationToken ct)
    {
        var marker = Path.Combine(work.Path, ConcatMarker);
        var done = 0;
        long length = 0;
        if (inPlace && File.Exists(marker) && File.Exists(output))
        {
            var parts = File.ReadAllText(marker).Trim().Split('\t');
            if (parts.Length == 2 && int.TryParse(parts[0], NumberStyles.Integer, CultureInfo.InvariantCulture, out var n)
                && long.TryParse(parts[1], NumberStyles.Integer, CultureInfo.InvariantCulture, out var len))
            {
                done = n;
                length = len;
            }
        }

        using var fs = new FileStream(output, done > 0 ? FileMode.OpenOrCreate : FileMode.Create, FileAccess.Write, FileShare.Read);
        fs.SetLength(length);
        fs.Position = length;
        foreach (var seg in work.Segments.Where(s => s.Number > done))
        {
            ct.ThrowIfCancellationRequested();
            var bytes = work.ReadSegment(seg);
            if (bytes == null)
            {
                continue;
            }

            fs.Write(bytes);
            if (inPlace)
            {
                fs.Flush(true);
                File.WriteAllText(marker, string.Create(CultureInfo.InvariantCulture, $"{seg.Number}\t{fs.Length}"));
                File.Delete(work.SegmentPath(seg));
            }
        }

        fs.Flush(true);
    }

    public static bool IsInPlaceConcatStarted(WorkFolder work) => File.Exists(Path.Combine(work.Path, ConcatMarker));

    private static void TryKill(Process p)
    {
        try
        {
            if (!p.HasExited)
            {
                p.Kill(entireProcessTree: true);
            }
        }
        catch (Exception ex) when (ex is InvalidOperationException or System.ComponentModel.Win32Exception)
        {
        }
    }
}
