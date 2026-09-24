using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Text.Json;

namespace Jellyfin.Plugin.Tally.Scores;

/// <summary>Parses ESPN's public scoreboard JSON
/// (site.api.espn.com/apis/site/v2/sports/{sport}/{league}/scoreboard) into <see cref="GameInfo"/>.
/// The feed is undocumented, so every field is read defensively.</summary>
public static class EspnScoreboardParser
{
    public static List<GameInfo> Parse(string json, string leaguePath)
    {
        var games = new List<GameInfo>();
        using var doc = JsonDocument.Parse(json);
        var root = doc.RootElement;

        var sport = leaguePath.Split('/')[0];
        var league = leaguePath.Split('/').Last().ToUpperInvariant();
        if (root.TryGetProperty("leagues", out var leagues) && leagues.ValueKind == JsonValueKind.Array && leagues.GetArrayLength() > 0)
        {
            league = Str(leagues[0], "abbreviation") ?? league;
        }

        if (!root.TryGetProperty("events", out var events) || events.ValueKind != JsonValueKind.Array)
        {
            return games;
        }

        foreach (var ev in events.EnumerateArray())
        {
            try
            {
                var game = ParseEvent(ev, sport, league);
                if (game != null)
                {
                    games.Add(game);
                }
            }
            catch (Exception ex) when (ex is InvalidOperationException or KeyNotFoundException or FormatException)
            {
                // one malformed event must not drop the whole league
            }
        }

        return games;
    }

    private static GameInfo? ParseEvent(JsonElement ev, string sport, string league)
    {
        if (!ev.TryGetProperty("competitions", out var comps) || comps.GetArrayLength() == 0)
        {
            return null;
        }

        var comp = comps[0];
        var game = new GameInfo
        {
            Id = Str(ev, "id") ?? string.Empty,
            Sport = sport,
            League = league,
            Name = Str(ev, "shortName") ?? Str(ev, "name") ?? string.Empty
        };

        if (DateTimeOffset.TryParse(Str(ev, "date"), CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out var start))
        {
            game.Start = start;
        }

        if (ev.TryGetProperty("status", out var status))
        {
            game.Period = Int(status, "period") ?? 0;
            game.Clock = Str(status, "displayClock") ?? string.Empty;
            game.ClockSeconds = Dbl(status, "clock") ?? 0;
            if (status.TryGetProperty("type", out var type))
            {
                game.State = Str(type, "state") ?? "pre";
                game.Detail = Str(type, "shortDetail") ?? Str(type, "detail") ?? string.Empty;
            }
        }

        string? possessionId = null;
        if (comp.TryGetProperty("situation", out var sit) && sit.ValueKind == JsonValueKind.Object)
        {
            possessionId = Str(sit, "possession");
            game.DownDistance = Str(sit, "downDistanceText");
            game.RedZone = Bool(sit, "isRedZone");
            game.Balls = Int(sit, "balls");
            game.Strikes = Int(sit, "strikes");
            game.Outs = Int(sit, "outs");
            game.OnFirst = Bool(sit, "onFirst");
            game.OnSecond = Bool(sit, "onSecond");
            game.OnThird = Bool(sit, "onThird");

            if (sit.TryGetProperty("lastPlay", out var lp) && lp.ValueKind == JsonValueKind.Object)
            {
                game.LastPlay = Str(lp, "text")?.Trim();
                game.LastPlayScore = Int(lp, "scoreValue") ?? 0;
                if (lp.TryGetProperty("type", out var lpType))
                {
                    game.LastPlayType = Str(lpType, "alternativeText") ?? Str(lpType, "text");
                }

                if (lp.TryGetProperty("probability", out var prob))
                {
                    game.HomeWinPct = Dbl(prob, "homeWinPercentage");
                }
            }
        }

        // Soccer has no "situation" — the last entry of "details" (goals, cards) is its last play.
        if (game.LastPlay == null && comp.TryGetProperty("details", out var details)
            && details.ValueKind == JsonValueKind.Array && details.GetArrayLength() > 0)
        {
            var d = details[details.GetArrayLength() - 1];
            var what = d.TryGetProperty("type", out var dt) ? Str(dt, "text") : null;
            var when = d.TryGetProperty("clock", out var dc) ? Str(dc, "displayValue") : null;
            string? who = null;
            if (d.TryGetProperty("athletesInvolved", out var ath) && ath.ValueKind == JsonValueKind.Array && ath.GetArrayLength() > 0)
            {
                who = Str(ath[0], "shortName") ?? Str(ath[0], "displayName");
            }

            game.LastPlay = string.Join(" ", new[] { when, what, who == null ? null : "— " + who }.Where(s => !string.IsNullOrEmpty(s)));
            game.LastPlayType = what;
            game.LastPlayScore = Bool(d, "scoringPlay") ? Math.Max(1, Int(d, "scoreValue") ?? 1) : 0;
        }

        if (comp.TryGetProperty("competitors", out var competitors) && competitors.ValueKind == JsonValueKind.Array)
        {
            foreach (var c in competitors.EnumerateArray())
            {
                var team = ParseTeam(c, possessionId);
                if (string.Equals(Str(c, "homeAway"), "home", StringComparison.OrdinalIgnoreCase))
                {
                    game.Home = team;
                }
                else
                {
                    game.Away = team;
                }
            }
        }

        var names = new List<string>();
        if (comp.TryGetProperty("broadcasts", out var bcs) && bcs.ValueKind == JsonValueKind.Array)
        {
            foreach (var b in bcs.EnumerateArray())
            {
                if (b.TryGetProperty("names", out var ns) && ns.ValueKind == JsonValueKind.Array)
                {
                    names.AddRange(ns.EnumerateArray().Select(n => n.GetString() ?? string.Empty));
                }
            }
        }

        if (comp.TryGetProperty("geoBroadcasts", out var geo) && geo.ValueKind == JsonValueKind.Array)
        {
            foreach (var g in geo.EnumerateArray())
            {
                if (g.TryGetProperty("media", out var media))
                {
                    names.Add(Str(media, "shortName") ?? string.Empty);
                }
            }
        }

        game.Broadcasts = names.Where(n => n.Length > 0).Distinct(StringComparer.OrdinalIgnoreCase).ToList();
        return game;
    }

    private static GameTeam ParseTeam(JsonElement c, string? possessionId)
    {
        var team = new GameTeam { Winner = Bool(c, "winner") };
        if (int.TryParse(Str(c, "score"), NumberStyles.Integer, CultureInfo.InvariantCulture, out var score))
        {
            team.Score = score;
        }

        if (c.TryGetProperty("linescores", out var lines) && lines.ValueKind == JsonValueKind.Array)
        {
            foreach (var l in lines.EnumerateArray())
            {
                team.Periods.Add((int)Math.Round(l.TryGetProperty("value", out var v) && v.ValueKind == JsonValueKind.Number ? v.GetDouble() : 0));
            }
        }

        if (c.TryGetProperty("records", out var recs) && recs.ValueKind == JsonValueKind.Array && recs.GetArrayLength() > 0)
        {
            team.Record = Str(recs[0], "summary");
        }

        if (c.TryGetProperty("team", out var t))
        {
            team.Id = Str(t, "id") ?? string.Empty;
            team.Abbr = Str(t, "abbreviation") ?? string.Empty;
            team.Name = Str(t, "displayName") ?? string.Empty;
            team.ShortName = Str(t, "shortDisplayName") ?? team.Name;
            team.Nickname = Str(t, "name") ?? team.ShortName;
            team.Location = Str(t, "location") ?? string.Empty;
            team.Logo = Str(t, "logo") ?? string.Empty;
            team.Color = Str(t, "color") ?? string.Empty;
            team.AltColor = Str(t, "alternateColor") ?? string.Empty;
        }

        team.HasPossession = possessionId != null && possessionId == team.Id;
        return team;
    }

    private static string? Str(JsonElement e, string name)
        => e.ValueKind == JsonValueKind.Object && e.TryGetProperty(name, out var v)
            ? v.ValueKind switch
            {
                JsonValueKind.String => v.GetString(),
                JsonValueKind.Number => v.GetRawText(),
                _ => null
            }
            : null;

    private static int? Int(JsonElement e, string name)
        => e.ValueKind == JsonValueKind.Object && e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.Number && v.TryGetDouble(out var d)
            ? (int)d
            : null;

    private static double? Dbl(JsonElement e, string name)
        => e.ValueKind == JsonValueKind.Object && e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.Number && v.TryGetDouble(out var d)
            ? d
            : null;

    private static bool Bool(JsonElement e, string name)
        => e.ValueKind == JsonValueKind.Object && e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.True;
}
