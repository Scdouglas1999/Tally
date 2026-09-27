using System;
using System.IO;
using Jellyfin.Plugin.Tally.Services;
using Xunit;

namespace Tally.Tests;

public class ToolPathTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "tally-toolpath-" + Guid.NewGuid().ToString("N"));

    public ToolPathTests() => Directory.CreateDirectory(_dir);

    public void Dispose() => Directory.Delete(_dir, recursive: true);

    [Fact]
    public void A_Full_Path_That_Exists_Is_Kept()
    {
        var ffmpeg = Touch("ffmpeg");
        Assert.Equal(ffmpeg, ToolPath.Resolve(ffmpeg, pathVariable: string.Empty));
        Assert.Equal(ffmpeg, ToolPath.Resolve("\"" + ffmpeg + "\"", pathVariable: string.Empty));
    }

    [Fact]
    public void A_Bare_Name_Is_Found_On_Path()
    {
        // the owner's Windows server: Jellyfin reports its encoder as "ffmpeg"
        var other = Path.Combine(_dir, "other");
        Directory.CreateDirectory(other);
        var ffprobe = Touch("ffprobe");
        Assert.Equal(ffprobe, ToolPath.Resolve("ffprobe", pathVariable: other + Path.PathSeparator + _dir, windows: false));
    }

    [Fact]
    public void On_Windows_A_Bare_Name_Takes_Exe_And_Path_Splits_On_Semicolons()
    {
        var exe = Touch("ffmpeg.exe");
        Assert.Equal(exe, ToolPath.Resolve("ffmpeg", pathVariable: "\"" + Path.Combine(_dir, "none") + "\";" + _dir, windows: true));
    }

    [Fact]
    public void Missing_Tools_Are_Null()
    {
        Assert.Null(ToolPath.Resolve(null));
        Assert.Null(ToolPath.Resolve("  "));
        Assert.Null(ToolPath.Resolve("tally-no-such-tool", pathVariable: _dir, windows: false));
        Assert.Null(ToolPath.Resolve(Path.Combine(_dir, "bin", "ffmpeg"), pathVariable: _dir));
    }

    private string Touch(string name)
    {
        var path = Path.Combine(_dir, name);
        File.WriteAllText(path, string.Empty);
        return path;
    }
}
