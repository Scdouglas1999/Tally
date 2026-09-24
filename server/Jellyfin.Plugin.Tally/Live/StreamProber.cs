using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Tally.Models;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Tally.Live;

/// <summary>What ffprobe said about a segment.</summary>
public sealed record VideoFacts(int Width, int Height, double? FrameRate, string? Codec);

/// <summary>
/// Looks at one candidate: its master (renditions with BANDWIDTH, RESOLUTION, FRAME-RATE, CODECS), the media
/// playlist of its best rendition (target duration, freshness) and one segment, timed — the throughput this server
/// actually gets. When the master does not say resolution or frame rate, that segment goes through the server's own
/// ffprobe. For a channel with several streams it also watches the media playlist for a while (playlist fetches only)
/// to see how steadily new segments show up in it: a stream that publishes in bursts looks fine in one fetch.
/// </summary>
public sealed class StreamProber
{
    private readonly LiveFetch _fetch;
    private readonly Func<string?> _ffprobePath;
    private readonly ILogger _logger;

    public StreamProber(LiveFetch fetch, Func<string?> ffprobePath, ILogger logger)
    {
        _fetch = fetch;
        _ffprobePath = ffprobePath;
        _logger = logger;
    }

    /// <summary>How long a probe watches the media playlist for its cadence.</summary>
    public static readonly TimeSpan CadenceWatch = TimeSpan.FromSeconds(15);

    public async Task<CandidateProbe> ProbeAsync(int index, StreamCandidate candidate, CandidateProbe? previous, bool withSegment, CancellationToken ct, TimeSpan? watchCadence = null)
    {
        var now = DateTimeOffset.UtcNow;
        if (!Uri.TryCreate(candidate.Url, UriKind.Absolute, out var uri))
        {
            return Failed(now, "bad URL");
        }

        var top = await _fetch.GetAsync(uri, candidate.Headers, TimeSpan.FromSeconds(15), ct).ConfigureAwait(false);
        if (!top.Ok)
        {
            return Failed(now, "playlist: " + top.Error);
        }

        var text = top.Text;
        if (!text.Contains("#EXTM3U", StringComparison.Ordinal))
        {
            return Failed(now, "not an HLS playlist");
        }

        var tiers = new List<Tier>();
        HlsMediaPlaylist media;
        Uri mediaFinal;
        Tier probed;
        if (HlsParser.IsMaster(text))
        {
            var variants = HlsParser.ParseMaster(text, top.FinalUri);
            if (variants.Count == 0)
            {
                return Failed(now, "empty master playlist");
            }

            for (var v = 0; v < variants.Count; v++)
            {
                var x = variants[v];
                tiers.Add(new Tier
                {
                    Candidate = index,
                    CandidateKey = candidate.Url,
                    Variant = v,
                    MediaUri = x.Uri,
                    Bandwidth = x.AverageBandwidth ?? x.Bandwidth,
                    Width = x.Width,
                    Height = x.Height,
                    FrameRate = x.FrameRate,
                    Codecs = x.Codecs
                });
            }

            probed = LadderRanking.Rank(tiers)[0];
            var mp = await _fetch.GetAsync(probed.MediaUri, candidate.Headers, TimeSpan.FromSeconds(15), ct).ConfigureAwait(false);
            if (!mp.Ok)
            {
                return Failed(now, "media playlist: " + mp.Error, tiers);
            }

            media = HlsParser.ParseMedia(mp.Text, mp.FinalUri);
            mediaFinal = mp.FinalUri;
        }
        else
        {
            probed = new Tier { Candidate = index, CandidateKey = candidate.Url, Variant = 0, MediaUri = top.FinalUri };
            tiers.Add(probed);
            media = HlsParser.ParseMedia(text, top.FinalUri);
            mediaFinal = top.FinalUri;
        }

        if (media.Segments.Count == 0)
        {
            return Failed(now, "no segments", tiers);
        }

        var fresh = !media.EndList
            && (previous == null || !previous.Ok || previous.NextSequence < media.NextSequence
                || now - previous.At < TimeSpan.FromSeconds(Math.Max(3, media.TargetDuration * 1.5)));
        var isTs = media.Segments[^1].MapUri == null;
        var encrypted = media.Segments[^1].KeyMethod != null;

        // The cadence watch runs alongside the segment download and ffprobe: the probe takes about as long as the watch.
        using var watchCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        var cadenceTask = watchCadence is { } watch && watch > TimeSpan.Zero && !media.EndList
            ? WatchCadenceAsync(mediaFinal, candidate.Headers, media, watch, watchCts.Token)
            : Task.FromResult<CadenceSummary?>(null);

        double? throughput = previous?.Throughput;
        if (withSegment)
        {
            // Second-newest: the newest may still be propagating through the CDN.
            var seg = media.Segments[Math.Max(0, media.Segments.Count - 2)];
            var r = await _fetch.GetSegmentAsync(seg, candidate.Headers, TimeSpan.FromSeconds(Math.Max(10, seg.Duration * 3)), ct).ConfigureAwait(false);
            if (!r.Ok)
            {
                watchCts.Cancel();
                return Failed(now, "segment: " + r.Error, tiers);
            }

            throughput = r.Body!.Length * 8 / Math.Max(0.05, r.Seconds);
            if (seg.Duration > 0)
            {
                probed.MeasuredBitrate = r.Body!.Length * 8 / seg.Duration;
            }

            isTs = TsSplicer.FindSync(r.Body!) >= 0;
            if (probed.Height == null || probed.FrameRate == null)
            {
                var facts = await FfprobeAsync(r.Body!, ct).ConfigureAwait(false);
                if (facts != null)
                {
                    probed.Width ??= facts.Width;
                    probed.Height ??= facts.Height;
                    probed.FrameRate ??= facts.FrameRate;
                }
            }
        }
        else if (previous != null)
        {
            // keep what an earlier full probe learned about the renditions
            foreach (var t in tiers)
            {
                var old = previous.Tiers.FirstOrDefault(o => o.Variant == t.Variant);
                if (old != null)
                {
                    t.Width ??= old.Width;
                    t.Height ??= old.Height;
                    t.FrameRate ??= old.FrameRate;
                    t.MeasuredBitrate ??= old.MeasuredBitrate;
                }
            }
        }

        var cadence = await cadenceTask.ConfigureAwait(false);
        return new CandidateProbe
        {
            At = now,
            Ok = true,
            Tiers = tiers,
            Throughput = throughput,
            Cadence = cadence ?? previous?.Cadence,
            Fresh = fresh,
            TargetDuration = media.TargetDuration,
            NextSequence = media.NextSequence,
            IsTs = isTs,
            Encrypted = encrypted,
            IsLive = !media.EndList
        };
    }

    /// <summary>Fetches the media playlist every third of a target duration (1-3 s) for <paramref name="watch"/> and
    /// reports how steadily it gained segments. Null when the watch saw nothing usable.</summary>
    private async Task<CadenceSummary?> WatchCadenceAsync(Uri mediaUri, Dictionary<string, string> headers, HlsMediaPlaylist first, TimeSpan watch, CancellationToken ct)
    {
        try
        {
            var start = DateTimeOffset.UtcNow;
            var meter = new CadenceMeter(start);
            meter.OnPlaylist(start, first.Segments);
            var interval = TimeSpan.FromSeconds(Math.Clamp(first.TargetDuration / 3, 1, 3));
            while (DateTimeOffset.UtcNow - start < watch)
            {
                await Task.Delay(interval, ct).ConfigureAwait(false);
                var r = await _fetch.GetAsync(mediaUri, headers, TimeSpan.FromSeconds(10), ct).ConfigureAwait(false);
                if (r.Ok && r.Text.Contains("#EXTINF", StringComparison.Ordinal))
                {
                    meter.OnPlaylist(DateTimeOffset.UtcNow, HlsParser.ParseMedia(r.Text, r.FinalUri).Segments);
                }
            }

            var now = DateTimeOffset.UtcNow;
            return meter.Summarize(now, now - start);
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            _logger.LogDebug(ex, "JellyTV ladder: cadence watch failed");
            return null;
        }
    }

    private static CandidateProbe Failed(DateTimeOffset at, string error, List<Tier>? tiers = null)
        => new() { At = at, Ok = false, Error = error, Tiers = tiers ?? new List<Tier>() };

    /// <summary>Width, height and frame rate of a segment, from Jellyfin's configured ffprobe. Null when there is
    /// no ffprobe or it cannot tell.</summary>
    public async Task<VideoFacts?> FfprobeAsync(byte[] segment, CancellationToken ct)
    {
        var exe = _ffprobePath();
        if (string.IsNullOrEmpty(exe) || !File.Exists(exe))
        {
            return null;
        }

        var tmp = Path.Combine(Path.GetTempPath(), "tally-probe-" + Guid.NewGuid().ToString("N") + ".ts");
        try
        {
            await File.WriteAllBytesAsync(tmp, segment, ct).ConfigureAwait(false);
            var psi = new ProcessStartInfo(exe)
            {
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
                CreateNoWindow = true
            };
            foreach (var a in new[] { "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height,avg_frame_rate,r_frame_rate,codec_name", "-of", "json", tmp })
            {
                psi.ArgumentList.Add(a);
            }

            using var p = Process.Start(psi);
            if (p == null)
            {
                return null;
            }

            using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            cts.CancelAfter(TimeSpan.FromSeconds(15));
            var outTask = p.StandardOutput.ReadToEndAsync(cts.Token);
            _ = p.StandardError.ReadToEndAsync(cts.Token);
            try
            {
                await p.WaitForExitAsync(cts.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                try
                {
                    p.Kill(true);
                }
                catch (InvalidOperationException)
                {
                }

                return null;
            }

            return ParseFfprobe(await outTask.ConfigureAwait(false));
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or System.ComponentModel.Win32Exception)
        {
            _logger.LogDebug(ex, "JellyTV ladder: ffprobe failed");
            return null;
        }
        finally
        {
            try
            {
                File.Delete(tmp);
            }
            catch (IOException)
            {
            }
        }
    }

    public static VideoFacts? ParseFfprobe(string json)
    {
        try
        {
            using var doc = JsonDocument.Parse(json);
            if (!doc.RootElement.TryGetProperty("streams", out var streams) || streams.GetArrayLength() == 0)
            {
                return null;
            }

            var s = streams[0];
            var w = s.TryGetProperty("width", out var wp) ? wp.GetInt32() : 0;
            var h = s.TryGetProperty("height", out var hp) ? hp.GetInt32() : 0;
            var fps = Rate(s, "avg_frame_rate") ?? Rate(s, "r_frame_rate");
            return h > 0 ? new VideoFacts(w, h, fps, s.TryGetProperty("codec_name", out var c) ? c.GetString() : null) : null;
        }
        catch (JsonException)
        {
            return null;
        }
    }

    private static double? Rate(JsonElement s, string name)
    {
        if (!s.TryGetProperty(name, out var p) || p.GetString() is not { } v)
        {
            return null;
        }

        var parts = v.Split('/');
        if (parts.Length == 2 && double.TryParse(parts[0], NumberStyles.Float, CultureInfo.InvariantCulture, out var a)
            && double.TryParse(parts[1], NumberStyles.Float, CultureInfo.InvariantCulture, out var b) && a > 0 && b > 0)
        {
            var r = a / b;
            return r is > 1 and < 200 ? Math.Round(r, 2) : null;
        }

        return null;
    }
}
