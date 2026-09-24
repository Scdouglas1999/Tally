using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;
using Jellyfin.Plugin.Tally.Scores;

namespace Jellyfin.Plugin.Tally.Dvr;

/// <summary>What the scoreboard says about a job's game right now.</summary>
public sealed record GameStatus(string State, DateTimeOffset Start, bool CalledOff)
{
    public static GameStatus From(GameInfo g) => new(g.State, g.Start, g.IsCalledOff);
}

public enum DvrAction
{
    /// <summary>Nothing to do (the state and reason may still change).</summary>
    None,
    StartRecording,
    StopRecording,
    Cancel,
    Fail
}

public sealed record DvrDecision(DvrAction Action, string State, string? Reason, string? StopReason = null);

/// <summary>
/// The job state machine, without any I/O: given the job, what the scoreboard says, whether a channel carries the
/// game and how many recordings are running, what happens next. The DVR service applies the decision.
/// </summary>
public static class DvrPolicy
{
    /// <summary>A recording may start this long before the listed start, once a channel carries the game.</summary>
    public static readonly TimeSpan PreRoll = TimeSpan.FromMinutes(15);

    /// <summary>A job whose game no channel carries this long after the listed start fails.</summary>
    public static readonly TimeSpan NoStreamTimeout = TimeSpan.FromMinutes(60);

    public const string NoStream = "No stream found for this game";

    public static DvrDecision Evaluate(RecordingJob job, GameStatus? game, bool haveChannel, int running, DvrSettings settings, DateTimeOffset now)
    {
        var state = job.State;
        if (JobState.IsFinal(state) || state == JobState.Finishing)
        {
            return new DvrDecision(DvrAction.None, state, job.Reason);
        }

        if (state == JobState.Recording)
        {
            if (game is { CalledOff: true })
            {
                return new DvrDecision(DvrAction.StopRecording, state, null, "postponed");
            }

            if (job.FinalSeenAt is { } final && now >= final + TimeSpan.FromMinutes(settings.PostRollMinutes))
            {
                return new DvrDecision(DvrAction.StopRecording, state, null);
            }

            if (job.StartedAt is { } started && now - started >= TimeSpan.FromHours(settings.MaxHours))
            {
                return new DvrDecision(DvrAction.StopRecording, state, null, "maxLength");
            }

            return new DvrDecision(DvrAction.None, state, job.Reason);
        }

        // scheduled or waiting
        if (game is { CalledOff: true })
        {
            return new DvrDecision(DvrAction.Cancel, JobState.Canceled, "The game was postponed or canceled");
        }

        var start = game?.Start ?? job.Game.Start;
        if (game?.State == "post")
        {
            return new DvrDecision(DvrAction.Fail, JobState.Failed,
                haveChannel ? $"The game ended while {settings.MaxConcurrent} other recordings were running" : NoStream);
        }

        var windowOpen = game?.State == "in" || now >= start - PreRoll;
        if (!windowOpen)
        {
            return new DvrDecision(DvrAction.None, JobState.Scheduled, null);
        }

        if (!haveChannel)
        {
            return now >= start + NoStreamTimeout
                ? new DvrDecision(DvrAction.Fail, JobState.Failed, NoStream)
                : new DvrDecision(DvrAction.None, JobState.Waiting, "Waiting for a channel to carry the game");
        }

        if (running >= settings.MaxConcurrent)
        {
            return new DvrDecision(DvrAction.None, JobState.Waiting,
                $"Waiting for a free slot: {running} recording{(running == 1 ? string.Empty : "s")} already running (limit {settings.MaxConcurrent})");
        }

        return new DvrDecision(DvrAction.StartRecording, JobState.Recording, null);
    }

    /// <summary>What a stop reason says on the job ("Stopped early: disk almost full").</summary>
    public static string? Describe(string? stopReason, DvrSettings settings) => stopReason switch
    {
        null => null,
        "disk" => "Stopped early: disk almost full",
        "canceled" => "Stopped early: canceled",
        "maxLength" => $"Stopped at the maximum length ({FormatHours(settings.MaxHours)})",
        "postponed" => "Stopped early: the game was postponed",
        "restart" => "Stopped early: the server restarted after the game ended",
        _ => "Stopped early: " + stopReason
    };

    private static string FormatHours(double h)
        => h == Math.Floor(h) ? h.ToString("0", CultureInfo.InvariantCulture) + " h" : h.ToString("0.#", CultureInfo.InvariantCulture) + " h";
}

/// <summary>How big a recording will be, and whether it fits.</summary>
public static class DvrSpace
{
    /// <summary>Assumed when nothing about the stream is known yet (a typical 720p/1080p sports stream).</summary>
    public const double DefaultBitrate = 6_000_000;

    /// <summary>A game running past its typical length is assumed to go on at least this much longer.</summary>
    public static readonly TimeSpan MinimumLeft = TimeSpan.FromMinutes(10);

    /// <summary>Typical game lengths by league path: MLB 3 h, NFL 3.5 h, college football 3.5 h, NBA 2.5 h,
    /// NHL 2.5 h, soccer 2 h, others 3 h.</summary>
    public static TimeSpan TypicalLength(string leaguePath)
    {
        var p = (leaguePath ?? string.Empty).ToLowerInvariant();
        return p switch
        {
            "baseball/mlb" => TimeSpan.FromHours(3),
            "football/nfl" => TimeSpan.FromHours(3.5),
            "football/college-football" => TimeSpan.FromHours(3.5),
            "basketball/nba" => TimeSpan.FromHours(2.5),
            "hockey/nhl" => TimeSpan.FromHours(2.5),
            _ when p.StartsWith("soccer/", StringComparison.Ordinal) => TimeSpan.FromHours(2),
            _ => TimeSpan.FromHours(3)
        };
    }

    /// <summary>How much there is still to record: the rest of a typical game (from the earliest start for an
    /// upcoming one, which includes the pre-roll), never less than <see cref="MinimumLeft"/>, plus the post-roll.</summary>
    public static TimeSpan TimeLeft(string leaguePath, DateTimeOffset start, string state, DateTimeOffset now, TimeSpan postRoll)
    {
        var typical = TypicalLength(leaguePath);
        var from = state == "pre" ? (now > start - DvrPolicy.PreRoll ? now : start - DvrPolicy.PreRoll) : now;
        var end = start + typical;
        var left = end - from;
        if (left < MinimumLeft)
        {
            left = MinimumLeft;
        }

        return left + postRoll;
    }

    public static long Estimate(double bitsPerSecond, TimeSpan left)
        => (long)Math.Ceiling(Math.Max(0, bitsPerSecond) / 8 * Math.Max(0, left.TotalSeconds));

    /// <summary>Only start if free space − estimate ≥ the reserve.</summary>
    public static bool Fits(long freeBytes, long estimateBytes, long reserveBytes) => freeBytes - estimateBytes >= reserveBytes;

    /// <summary>"Not enough space: needs ~9 GB, 4 GB free" (plus the reserve when the space is there but taken by it).</summary>
    public static string NotEnough(long estimateBytes, long freeBytes, long reserveBytes)
    {
        var text = $"Not enough space: needs ~{Size(estimateBytes)}, {Size(freeBytes)} free";
        return freeBytes >= estimateBytes ? text + $" ({Size(reserveBytes)} kept free)" : text;
    }

    /// <summary>While recording: stop once free space falls below the reserve.</summary>
    public static bool BelowReserve(long freeBytes, long reserveBytes) => freeBytes < reserveBytes;

    /// <summary>Remux into a new file only when that leaves at least half the reserve free; otherwise the segments
    /// are joined into one .ts in place (no extra space).</summary>
    public static bool CanRemux(long freeBytes, long recordedBytes, long reserveBytes)
        => freeBytes - (long)(recordedBytes * 1.05) >= reserveBytes / 2;

    /// <summary>"12 GB", "4.2 GB", "350 MB".</summary>
    public static string Size(long bytes)
    {
        var gb = bytes / (double)DvrSettings.Gb;
        if (gb >= 10)
        {
            return Math.Round(gb).ToString("0", CultureInfo.InvariantCulture) + " GB";
        }

        if (gb >= 1)
        {
            return gb.ToString("0.#", CultureInfo.InvariantCulture) + " GB";
        }

        return Math.Max(0, Math.Round(bytes / (1024.0 * 1024))).ToString("0", CultureInfo.InvariantCulture) + " MB";
    }
}

/// <summary>Which finished recordings retention removes.</summary>
public static class DvrRetention
{
    /// <summary>
    /// Finished recordings to delete: beyond a team rule's "keep the last N games" (newest games kept), and older than
    /// the global "delete after N days". Only done jobs with a file; never one <paramref name="isProtected"/> says is
    /// being watched.
    /// </summary>
    public static List<RecordingJob> Select(IEnumerable<RecordingJob> jobs, IEnumerable<RecordingRule> rules, int deleteAfterDays,
        DateTimeOffset now, Func<RecordingJob, bool> isProtected)
    {
        var done = jobs.Where(j => j.State == JobState.Done && !string.IsNullOrEmpty(j.FilePath)).ToList();
        var doomed = new List<RecordingJob>();
        foreach (var rule in rules.Where(r => r.Kind == RecordingRule.TeamKind && r.KeepLast > 0))
        {
            doomed.AddRange(done.Where(j => j.RuleId == rule.Id)
                .OrderByDescending(j => j.Game.Start)
                .Skip(rule.KeepLast));
        }

        if (deleteAfterDays > 0)
        {
            var cutoff = now - TimeSpan.FromDays(deleteAfterDays);
            doomed.AddRange(done.Where(j => (j.EndedAt ?? j.Game.Start) < cutoff));
        }

        return doomed.Distinct().Where(j => !isProtected(j)).ToList();
    }
}

/// <summary>Where a finished recording goes: <c>&lt;folder&gt;/&lt;League&gt;/&lt;Away&gt; at &lt;Home&gt; - yyyy-MM-dd.ext</c>.</summary>
public static class DvrNaming
{
    private static readonly char[] Invalid = "<>:\"/\\|?*".ToCharArray();

    /// <summary>The game's date in <paramref name="zone"/> (the server's own clock, normally).</summary>
    public static DateTime LocalDate(GameSnapshot g, TimeZoneInfo zone) => TimeZoneInfo.ConvertTime(g.Start, zone).Date;

    public static string BaseName(GameSnapshot g, TimeZoneInfo zone)
        => Clean($"{g.Away.DisplayName} at {g.Home.DisplayName} - {LocalDate(g, zone).ToString("yyyy-MM-dd", CultureInfo.InvariantCulture)}");

    public static string LeagueFolder(GameSnapshot g)
        => Clean(string.IsNullOrWhiteSpace(g.League) ? "Sports" : g.League);

    /// <summary>A file or folder name that is valid on Windows and Linux: no reserved characters, no control
    /// characters, no trailing dots or spaces.</summary>
    public static string Clean(string name)
    {
        var sb = new StringBuilder(name.Length);
        foreach (var ch in name)
        {
            sb.Append(char.IsControl(ch) || Array.IndexOf(Invalid, ch) >= 0 ? ' ' : ch);
        }

        var s = string.Join(' ', sb.ToString().Split(' ', StringSplitOptions.RemoveEmptyEntries)).TrimEnd('.', ' ');
        return s.Length == 0 ? "Recording" : s.Length > 150 ? s[..150].TrimEnd('.', ' ') : s;
    }

    /// <summary>The base name, with " (2)", " (3)"… when a recording of that name already exists (a doubleheader).</summary>
    public static string Unique(string directory, string baseName, Func<string, bool> taken)
    {
        var name = baseName;
        for (var n = 2; taken(Path.Combine(directory, name)); n++)
        {
            name = $"{baseName} ({n})";
        }

        return name;
    }
}
