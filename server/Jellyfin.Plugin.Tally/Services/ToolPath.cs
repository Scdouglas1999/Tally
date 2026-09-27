using System;
using System.IO;
using System.Linq;

namespace Jellyfin.Plugin.Tally.Services;

/// <summary>
/// Where Jellyfin's ffmpeg or ffprobe actually is. Jellyfin reports a full path when one is configured, but a server
/// that finds them on PATH reports the bare name ("ffmpeg"), which <see cref="File.Exists"/> never finds.
/// </summary>
public static class ToolPath
{
    /// <summary>The full path of <paramref name="tool"/> (a path, or a bare name looked up on PATH, with ".exe" on
    /// Windows), or null when there is no such file.</summary>
    public static string? Resolve(string? tool, string? pathVariable = null, bool? windows = null)
    {
        if (string.IsNullOrWhiteSpace(tool))
        {
            return null;
        }

        tool = tool.Trim().Trim('"');
        if (File.Exists(tool))
        {
            return Path.GetFullPath(tool);
        }

        // only a bare name is looked up: "bin/ffmpeg" that isn't there is simply missing
        if (tool.IndexOfAny(new[] { '/', '\\' }) >= 0)
        {
            return null;
        }

        var onWindows = windows ?? OperatingSystem.IsWindows();
        var names = onWindows && !tool.EndsWith(".exe", StringComparison.OrdinalIgnoreCase)
            ? new[] { tool + ".exe", tool }
            : new[] { tool };
        var folders = (pathVariable ?? Environment.GetEnvironmentVariable("PATH") ?? string.Empty)
            .Split(onWindows ? ';' : Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);

        // Jellyfin's Windows installer puts ffmpeg next to jellyfin.exe, which need not be on PATH
        var candidates = folders.Append(AppContext.BaseDirectory);
        return candidates
            .SelectMany(folder => names.Select(name => SafeCombine(folder.Trim('"'), name)))
            .FirstOrDefault(p => p != null && File.Exists(p));
    }

    private static string? SafeCombine(string folder, string name)
    {
        try
        {
            return Path.Combine(folder, name);
        }
        catch (ArgumentException)
        {
            return null;
        }
    }
}
