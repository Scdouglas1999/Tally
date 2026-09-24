using System;

namespace Jellyfin.Plugin.Tally.Scores;

/// <summary>
/// Rates how much a game deserves a screen right now (0–100) and says why.
/// Drives the board's sort order, the switch alerts and multiview auto-fill.
/// Deliberately simple, explainable rules per sport — every point of heat maps to a tag
/// or an obvious fact (late, close, scoring chance), never a black box.
/// </summary>
public static class GameHeat
{
    public static void Apply(GameInfo g, DateTimeOffset now)
    {
        g.Tags.Clear();
        g.Heat = 0;

        if (g.State == "pre")
        {
            var until = g.Start - now;
            if (until <= TimeSpan.FromMinutes(15) && until >= TimeSpan.FromMinutes(-10))
            {
                g.Heat = 10;
                g.Tags.Add("STARTING SOON");
            }

            return;
        }

        if (g.State != "in")
        {
            return;
        }

        var heat = 20;
        var margin = Math.Abs((g.Home.Score ?? 0) - (g.Away.Score ?? 0));

        switch (g.Sport)
        {
            case "football":
                if (g.Period >= 5)
                {
                    heat += 40;
                    g.Tags.Add("OVERTIME");
                }
                else if (g.Period == 4 && margin <= 8)
                {
                    heat += 35;
                    if (g.ClockSeconds <= 120)
                    {
                        heat += 10;
                        g.Tags.Add("TWO-MINUTE DRILL");
                    }

                    g.Tags.Add("ONE-SCORE GAME");
                }
                else if (g.Period >= 3 && margin >= 21)
                {
                    heat -= 15;
                }

                if (g.RedZone)
                {
                    heat += 20;
                    g.Tags.Insert(0, "RED ZONE");
                }

                break;

            case "basketball":
                var regulation = g.League.Contains("NCAA", StringComparison.OrdinalIgnoreCase) ? 2 : 4;
                if (g.Period > regulation)
                {
                    heat += 40;
                    g.Tags.Add("OVERTIME");
                }
                else if (g.Period == regulation && g.ClockSeconds <= 300 && margin <= 6)
                {
                    heat += 40;
                    g.Tags.Add("CLUTCH TIME");
                }
                else if (g.Period >= regulation - 1 && margin >= 20)
                {
                    heat -= 15;
                }

                break;

            case "baseball":
                if (g.Period >= 10)
                {
                    heat += 40;
                    g.Tags.Add("EXTRA INNINGS");
                }
                else if (g.Period >= 7 && margin <= 2)
                {
                    heat += 30;
                    g.Tags.Add("LATE & CLOSE");
                }
                else if (g.Period >= 6 && margin >= 7)
                {
                    heat -= 15;
                }

                if (g.OnFirst && g.OnSecond && g.OnThird)
                {
                    heat += 20;
                    g.Tags.Insert(0, "BASES LOADED");
                }
                else if (g.OnSecond || g.OnThird)
                {
                    heat += 8;
                }

                break;

            case "hockey":
                if (g.Period >= 4)
                {
                    heat += 40;
                    g.Tags.Add("OVERTIME");
                }
                else if (g.Period == 3 && margin <= 1)
                {
                    heat += 30;
                    g.Tags.Add("ONE-GOAL GAME");
                }

                break;

            case "soccer":
                if (g.Period >= 3)
                {
                    heat += 40;
                    g.Tags.Add("EXTRA TIME");
                }
                else if (g.ClockSeconds >= 75 * 60 && margin <= 1)
                {
                    heat += 30;
                    g.Tags.Add("LATE DRAMA");
                }

                break;
        }

        if (g.HomeWinPct is >= 0.25 and <= 0.75)
        {
            heat += 10;
        }

        if (g.LastPlayScore > 0)
        {
            heat += 10;
        }

        g.Heat = Math.Clamp(heat, 0, 100);
    }
}
