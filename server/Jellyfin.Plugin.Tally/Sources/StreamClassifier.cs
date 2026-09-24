using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.RegularExpressions;

namespace Jellyfin.Plugin.Tally.Sources;

/// <summary>Heuristic classifier for web-extracted streams: cleans names and
/// assigns a category group from keywords found in link text / page context.</summary>
public static partial class StreamClassifier
{
    // Ordered: first matching category wins. Keep more-specific leagues before
    // generic terms (e.g. "premier league" before "football").
    private static readonly (string Group, string[] Keywords)[] Rules =
    {
        ("Basketball", new[] { "nba", "wnba", "basketball", "ncaa basketball", "march madness" }),
        ("American Football", new[] { "nfl", "ncaa football", "college football", "super bowl", "ncaaf", "cfb" }),
        ("Soccer", new[] { "premier league", "epl", "la liga", "serie a", "bundesliga", "ligue 1", "mls", "champions league", "europa league", "fifa", "uefa", "soccer", "world cup", "copa america", "fa cup" }),
        ("Hockey", new[] { "nhl", "hockey", "stanley cup" }),
        ("Baseball", new[] { "mlb", "baseball", "world series" }),
        ("Fighting", new[] { "ufc", "mma", "boxing", "bellator", "fight night", " ppv" }),
        ("Wrestling", new[] { "wwe", "aew", "wrestling", "wrestlemania", "smackdown", "monday night raw", " nxt" }),
        ("Motorsport", new[] { "formula 1", "formula1", " f1", "nascar", "motogp", "indycar", "racing", "grand prix", "daytona" }),
        ("Golf & Tennis", new[] { "tennis", "golf", "pga", "atp", "wta", "us open", "wimbledon", "masters" }),
        ("Cricket & Rugby", new[] { "cricket", "rugby", "ipl", "ashes" }),
        ("eSports", new[] { "esports", "e-sports", "csgo", "cs:go", "dota", "league of legends", "valorant" }),
        ("News", new[] { "cnn", "bbc", "fox news", "msnbc", "sky news", "al jazeera", "bloomberg", "cnbc", "news" }),
        ("Sports Networks", new[] { "espn", "fox sports", "sky sports", "tnt sports", "nbc sports", "bein", "dazn", "cbs sports", "tsn", "bally sports", "nba tv", "nfl network", "mlb network", "nhl network", "golf channel", "tennis channel" }),
        ("Sports", new[] { "sport", "sports", "game", "match", "live stream", "football" })
    };

    // Team names → group. Sports listings are usually "Team A vs Team B" with no
    // league keyword, so teams do most of the classification work on real pages.
    private static readonly (string Group, string[] Keywords)[] TeamRules =
    {
        ("Basketball", new[] { "lakers", "celtics", "warriors", "bulls", "heat", "knicks", "nets", "76ers", "sixers", "bucks", "cavaliers", "raptors", "suns", "mavericks", "nuggets", "clippers", "rockets", "spurs", "thunder", "timberwolves", "pelicans", "hawks", "hornets", "pacers", "pistons", "magic", "wizards", "grizzlies", "trail blazers", "jazz", "kings" }),
        ("American Football", new[] { "chiefs", "eagles", "cowboys", "49ers", "bills", "ravens", "bengals", "lions", "packers", "steelers", "patriots", "broncos", "raiders", "chargers", "rams", "seahawks", "vikings", "buccaneers", "dolphins", "jets", "giants", "bears", "commanders", "falcons", "saints", "panthers", "cardinals", "colts", "texans", "jaguars", "titans", "browns" }),
        ("Hockey", new[] { "bruins", "rangers", "maple leafs", "canadiens", "oilers", "avalanche", "golden knights", "panthers", "lightning", "hurricanes", "stars", "wild", "capitals", "penguins", "red wings", "blackhawks", "kraken", "canucks", "flames", "jets", "islanders", "devils", "flyers", "senators", "sabres", "blue jackets", "sharks", "ducks", "kings", "blues", "predators", "coyotes", "utah hockey" }),
        ("Baseball", new[] { "yankees", "red sox", "dodgers", "cubs", "mets", "braves", "astros", "phillies", "padres", "giants", "cardinals", "orioles", "guardians", "mariners", "rangers", "diamondbacks", "twins", "brewers", "rays", "tigers", "royals", "angels", "athletics", "white sox", "marlins", "pirates", "reds", "rockies", "nationals", "blue jays" }),
        ("Soccer", new[] { "arsenal", "chelsea", "liverpool", "manchester united", "man united", "man city", "manchester city", "tottenham", "newcastle", "aston villa", "west ham", "everton", "brighton", "fulham", "crystal palace", "real madrid", "barcelona", "atletico", "sevilla", "juventus", "inter milan", "ac milan", "napoli", "roma", "lazio", "bayern", "dortmund", "leverkusen", "psg", "marseille", "lyon", "ajax", "porto", "benfica", "celtic", "rangers fc", "valencia", "real sociedad", "villarreal", "real betis", "athletic club", "seattle sounders", "inter miami", "la galaxy", "atlanta united" })
    };

    private static readonly Regex Noise = NoiseRegex();
    private static readonly Regex Vs = VsRegex();

    /// <summary>Clean up a raw extracted label (strip "watch/live/HD" noise).</summary>
    public static string CleanName(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw))
        {
            return string.Empty;
        }

        var s = raw.Trim();
        s = Noise.Replace(s, " ");
        s = Regex.Replace(s, @"\s{2,}", " ").Trim(' ', '-', '|', '–', '·');
        return s.Length > 80 ? s[..80].Trim() : s;
    }

    /// <summary>True if the name looks like an event matchup ("A vs B", "A @ B").</summary>
    public static bool LooksLikeEvent(string name) => Vs.IsMatch(name);

    /// <summary>Best-fit group for a stream given its name + page context.</summary>
    public static string GroupFor(string? name, string? context)
    {
        var hay = (name + " " + context).ToLowerInvariant();
        foreach (var (group, keywords) in Rules)
        {
            // "Sports" is the generic catch-all — check specific groups and team
            // names first so "Lakers vs Celtics live stream" lands in Basketball.
            if (group == "Sports")
            {
                continue;
            }

            foreach (var k in keywords)
            {
                if (ContainsKeyword(hay, k))
                {
                    return group;
                }
            }
        }

        foreach (var (group, keywords) in TeamRules)
        {
            foreach (var k in keywords)
            {
                if (ContainsKeyword(hay, k))
                {
                    return group;
                }
            }
        }

        foreach (var k in Rules.First(r => r.Group == "Sports").Keywords)
        {
            if (ContainsKeyword(hay, k))
            {
                return "Sports";
            }
        }

        return LooksLikeEvent(name ?? string.Empty) ? "Sports" : "Live";
    }

    // Word-boundary match so "newcastle" doesn't hit the "news" keyword.
    public static bool ContainsKeyword(string hay, string keyword)
    {
        var idx = hay.IndexOf(keyword, StringComparison.Ordinal);
        while (idx >= 0)
        {
            var beforeOk = idx == 0 || !char.IsLetterOrDigit(hay[idx - 1]);
            var afterIdx = idx + keyword.Length;
            var afterOk = afterIdx >= hay.Length || !char.IsLetterOrDigit(hay[afterIdx]);
            if (beforeOk && afterOk)
            {
                return true;
            }

            idx = hay.IndexOf(keyword, idx + 1, StringComparison.Ordinal);
        }

        return false;
    }

    // Strips common watch-page boilerplate from extracted labels.
    [GeneratedRegex(@"(?i)\b(watch|live|free|online|stream(?:ing)?|hd|4k|720p|1080p|full hd|broadcast|channel|tv)\b|[\[\](){}\u203a\u00bb]")]
    private static partial Regex NoiseRegex();

    [GeneratedRegex(@"(?i)(\b(?:vs\.?|v\.?)\b|\s@\s)")]
    private static partial Regex VsRegex();
}
