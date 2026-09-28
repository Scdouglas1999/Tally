using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Tally.Services;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Tally.Live;

/// <summary>
/// "No games live · Tally Pulse": what the Pulse channel shows when no live game has a stream, so a player keeps
/// playing instead of erroring out. One short MPEG-TS segment (H.264 still + silent AAC, the layout of nearly every
/// sports stream, so it splices onto a game and back) served over and over, each time continuing the timeline. Made
/// once with Jellyfin's ffmpeg from the Tally title card and kept in the plugin's data folder.
/// </summary>
public sealed class RedZoneSlate
{
    /// <summary>Bump when the picture or the encoding changes: the cached file is made again.</summary>
    public const string FileName = "redzone-slate-v4.ts";

    /// <summary>128 frames at 30 fps and 200 AAC frames at 48 kHz last exactly as long, so the loop joins onto itself
    /// within a frame (the encoder's priming frame starts the audio 21 ms early; with plain seconds it drifted by 65 ms a
    /// lap).</summary>
    public const double Seconds = 128 / 30.0;

    public RedZoneSlate(byte[] bytes)
    {
        Bytes = bytes;
        var info = TsSplicer.Analyze(bytes);
        var video = info.Video?.Pid;
        Duration = video is { } v && info.StartByPid.TryGetValue(v, out var s) && info.EndByPid.TryGetValue(v, out var e) && e > s
            ? Math.Round((e - s) / 90000.0, 3)
            : Seconds;
    }

    public byte[] Bytes { get; }

    /// <summary>Seconds of video in the segment (its EXTINF).</summary>
    public double Duration { get; }

    /// <summary>The cached slate, or a new one made with <paramref name="ffmpeg"/>; null when neither works.</summary>
    public static async Task<RedZoneSlate?> LoadOrCreateAsync(string folder, string? ffmpeg, ILogger logger, CancellationToken ct)
    {
        var path = Path.Combine(folder, FileName);
        try
        {
            if (File.Exists(path))
            {
                var cached = await File.ReadAllBytesAsync(path, ct).ConfigureAwait(false);
                if (TsSplicer.Analyze(cached) is { IsTs: true, Video: not null })
                {
                    return new RedZoneSlate(cached);
                }
            }

            ffmpeg = ToolPath.Resolve(ffmpeg);
            if (ffmpeg == null)
            {
                logger.LogWarning("JellyTV Pulse: Jellyfin's ffmpeg was not found, so there is no \"No games live\" slate");
                return null;
            }

            Directory.CreateDirectory(folder);
            var png = Path.Combine(folder, "redzone-slate.png");
            await File.WriteAllBytesAsync(png, CardArtService.RenderTitle("No games live", RedZoneService.ChannelName), ct).ConfigureAwait(false);
            var tmp = path + ".tmp";
            var error = await RunAsync(ffmpeg, Arguments(png, tmp), ct).ConfigureAwait(false);
            File.Delete(png);
            if (error != null || !File.Exists(tmp))
            {
                logger.LogWarning("JellyTV Pulse: making the slate failed: {Error}", error ?? "no output");
                return null;
            }

            var bytes = await File.ReadAllBytesAsync(tmp, ct).ConfigureAwait(false);
            File.Move(tmp, path, overwrite: true);
            foreach (var old in Directory.GetFiles(folder, "redzone-slate-*.ts").Where(f => Path.GetFileName(f) != FileName))
            {
                File.Delete(old); // an earlier version's
            }

            logger.LogInformation("JellyTV Pulse: made the \"No games live\" slate ({Kb} KB)", bytes.Length / 1024);
            return new RedZoneSlate(bytes);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or InvalidOperationException or System.ComponentModel.Win32Exception)
        {
            logger.LogWarning("JellyTV Pulse: no slate: {Message}", ex.Message);
            return null;
        }
    }

    /// <summary>A 720p30 still with a keyframe at the start and none after (one GOP), and silent stereo AAC; see
    /// <see cref="Seconds"/>.</summary>
    public static IReadOnlyList<string> Arguments(string png, string output) => new[]
    {
        "-hide_banner", "-nostats", "-loglevel", "error", "-y",
        "-loop", "1", "-framerate", "30", "-i", png,
        "-f", "lavfi", "-i", "anullsrc=channel_layout=stereo:sample_rate=48000",
        "-map", "0:v", "-map", "1:a", "-frames:v", "128", "-frames:a", "200",
        "-c:v", "libx264", "-preset", "veryfast", "-tune", "stillimage", "-pix_fmt", "yuv420p", "-profile:v", "high",
        "-g", "300", "-bf", "0", "-vf", "scale=1280:720",
        "-c:a", "aac", "-b:a", "96k", "-ac", "2",
        "-f", "mpegts", output
    };

    private static async Task<string?> RunAsync(string ffmpeg, IReadOnlyList<string> args, CancellationToken ct)
    {
        var psi = new ProcessStartInfo(ffmpeg)
        {
            RedirectStandardError = true,
            RedirectStandardOutput = true,
            UseShellExecute = false,
            CreateNoWindow = true
        };
        foreach (var a in args)
        {
            psi.ArgumentList.Add(a);
        }

        using var process = Process.Start(psi);
        if (process == null)
        {
            return "ffmpeg did not start";
        }

        var stderr = process.StandardError.ReadToEndAsync(ct);
        _ = process.StandardOutput.ReadToEndAsync(ct);
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(TimeSpan.FromSeconds(60));
        try
        {
            await process.WaitForExitAsync(timeout.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            try
            {
                process.Kill(entireProcessTree: true);
            }
            catch (InvalidOperationException)
            {
            }

            return "ffmpeg timed out";
        }

        var text = (await stderr.ConfigureAwait(false)).Trim();
        return process.ExitCode == 0 ? null : $"ffmpeg exit {process.ExitCode}: {(text.Length > 300 ? text[..300] : text)}";
    }
}
