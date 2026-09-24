using System;

namespace Jellyfin.Plugin.Tally.Scores;

/// <summary>Turns a game into a guide slot for native clients' Live TV guides.</summary>
public static class GameSchedule
{
    public static TimeSpan TypicalLength(string sport) => sport switch
    {
        "football" => TimeSpan.FromMinutes(210),
        "baseball" => TimeSpan.FromMinutes(195),
        "basketball" => TimeSpan.FromMinutes(165),
        "hockey" => TimeSpan.FromMinutes(165),
        "soccer" => TimeSpan.FromMinutes(125),
        _ => TimeSpan.FromMinutes(180)
    };

    /// <summary>Scheduled end — but a game still in progress never "ends" in the guide before it does on the field.</summary>
    public static DateTimeOffset ExpectedEnd(GameInfo g, DateTimeOffset now)
    {
        var end = g.Start + TypicalLength(g.Sport);
        return g.State == "in" && end < now.AddMinutes(30) ? now.AddMinutes(30) : end;
    }

    public static string Title(GameInfo g) => $"{g.Away.Name} at {g.Home.Name}";
}
