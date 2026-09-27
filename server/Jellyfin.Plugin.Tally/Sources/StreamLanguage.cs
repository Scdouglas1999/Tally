using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;
using Jellyfin.Plugin.Tally.Models;

namespace Jellyfin.Plugin.Tally.Sources;

/// <summary>
/// Which language a stream's commentary is in, read from what the adapters see before names are cleaned: an M3U's
/// tvg-language, tvg-country and group-title, a channel's name ("ESPN Deportes", "[ES]"), a web page's link text and
/// title, and the page's or stream's address ("/es/", "-es.m3u8"). The answer is an ISO 639-1 code; nothing said means
/// English, since most sources carry nothing else. Tally keeps English and Spanish and drops the rest: a Spanish stream
/// becomes a channel of its own ("Colts at Texans (Español)", group "American Football · Español"), never a candidate
/// of the English one, so the ladder can never switch a game to another language mid-play.
/// </summary>
public static partial class StreamLanguage
{
    public const string English = "en";

    public const string Spanish = "es";

    /// <summary>Appended to a Spanish channel's name.</summary>
    public const string NameSuffix = " (Español)";

    /// <summary>Appended to a Spanish channel's group.</summary>
    public const string GroupSuffix = " · Español";

    /// <summary>Two- and three-letter tags ("[ES]", "(ENG)", "PT:") → language. Region prefixes such as "US:" or
    /// "UK:" say where a channel is from, not what it speaks ("US: Univision"), so they are not here.</summary>
    private static readonly Dictionary<string, string> Tags = new(StringComparer.Ordinal)
    {
        ["es"] = Spanish, ["esp"] = Spanish, ["spa"] = Spanish, ["mx"] = Spanish, ["lat"] = Spanish, ["latam"] = Spanish,
        ["en"] = English, ["eng"] = English,
        ["pt"] = "pt", ["por"] = "pt", ["br"] = "pt", ["ptbr"] = "pt",
        ["fr"] = "fr", ["fra"] = "fr", ["fre"] = "fr",
        ["de"] = "de", ["ger"] = "de", ["deu"] = "de",
        ["it"] = "it", ["ita"] = "it",
        ["ar"] = "ar", ["ara"] = "ar", ["arab"] = "ar",
        ["ru"] = "ru", ["rus"] = "ru",
        ["tr"] = "tr", ["tur"] = "tr",
        ["nl"] = "nl", ["pl"] = "pl"
    };

    /// <summary>Words that name a language (or a network that only broadcasts in one), accents removed.</summary>
    private static readonly (Regex Pattern, string Language)[] Words =
    {
        (SpanishWords(), Spanish),
        (SpanishAdjective(), Spanish),
        (EnglishAdjective(), English),
        (PortugueseWords(), "pt"),
        (FrenchWords(), "fr"),
        (GermanWords(), "de"),
        (ItalianWords(), "it"),
        (ArabicWords(), "ar"),
        (RussianWords(), "ru"),
        (TurkishWords(), "tr"),
        (DutchWords(), "nl")
    };

    /// <summary>tvg-language values ("Spanish", "es", "spa", "Español", "es-MX", "Spanish;English": the first).</summary>
    private static readonly Dictionary<string, string> LanguageNames = new(StringComparer.Ordinal)
    {
        ["english"] = English, ["ingles"] = English,
        ["spanish"] = Spanish, ["espanol"] = Spanish, ["castellano"] = Spanish, ["latino"] = Spanish,
        ["portuguese"] = "pt", ["portugues"] = "pt", ["french"] = "fr", ["francais"] = "fr", ["german"] = "de", ["deutsch"] = "de",
        ["italian"] = "it", ["italiano"] = "it", ["arabic"] = "ar", ["russian"] = "ru", ["turkish"] = "tr", ["dutch"] = "nl",
        ["polish"] = "pl", ["polski"] = "pl", ["nederlands"] = "nl", ["turkce"] = "tr"
    };

    /// <summary>tvg-country codes of countries whose channels speak one language.</summary>
    private static readonly Dictionary<string, string> Countries = new(StringComparer.Ordinal)
    {
        ["us"] = English, ["usa"] = English, ["uk"] = English, ["gb"] = English, ["ca"] = English, ["au"] = English,
        ["ie"] = English, ["nz"] = English,
        ["es"] = Spanish, ["mx"] = Spanish, ["ar"] = Spanish, ["co"] = Spanish, ["cl"] = Spanish, ["pe"] = Spanish,
        ["ve"] = Spanish, ["ec"] = Spanish, ["uy"] = Spanish, ["py"] = Spanish, ["bo"] = Spanish, ["cr"] = Spanish,
        ["pa"] = Spanish, ["gt"] = Spanish, ["hn"] = Spanish, ["sv"] = Spanish, ["ni"] = Spanish, ["do"] = Spanish,
        ["cu"] = Spanish,
        ["br"] = "pt", ["pt"] = "pt", ["fr"] = "fr", ["de"] = "de", ["at"] = "de", ["it"] = "it",
        ["sa"] = "ar", ["ae"] = "ar", ["qa"] = "ar", ["eg"] = "ar", ["ma"] = "ar", ["dz"] = "ar", ["tn"] = "ar",
        ["jo"] = "ar", ["kw"] = "ar", ["ru"] = "ru", ["tr"] = "tr", ["nl"] = "nl", ["pl"] = "pl"
    };

    /// <summary>Group titles naming a Spanish-speaking region ("MEXICO", "Latin America").</summary>
    private static readonly string[] SpanishRegions =
    {
        "spain", "espana", "mexico", "latin america", "latinoamerica", "latam", "argentina", "colombia", "chile", "peru"
    };

    /// <summary>"en" when unknown (empty), otherwise the lower-cased code.</summary>
    public static string Normalize(string? language)
        => string.IsNullOrWhiteSpace(language) ? English : language.Trim().ToLowerInvariant();

    public static string Of(SourceChannel c) => Normalize(c.Language);

    public static string Of(StreamCandidate c) => Normalize(c.Language);

    /// <summary>Tally keeps English and Spanish streams; every other language is dropped.</summary>
    public static bool IsKept(string? language) => Normalize(language) is English or Spanish;

    /// <summary>"English" / "Español" (what the apps show for a feed).</summary>
    public static string Label(string? language) => Normalize(language) == Spanish ? "Español" : "English";

    /// <summary>A viewer's <c>streamLanguage</c> setting ("en" default, "es").</summary>
    public static string Preferred(System.Text.Json.Nodes.JsonObject? settings)
    {
        try
        {
            return settings?["streamLanguage"]?.GetValue<string>() is { } v && Normalize(v) == Spanish ? Spanish : English;
        }
        catch (InvalidOperationException)
        {
            return English; // not a string
        }
    }

    /// <summary>The first definite answer, most specific hint first; null when none says.</summary>
    public static string? First(params string?[] answers) => answers.FirstOrDefault(a => a != null);

    /// <summary>
    /// What a name, label or title says: "ESPN Deportes", "Univision", "Spanish", "[ES]", "ES: Fox" → "es";
    /// "English", "(EN)" → "en"; "Portugues", "beIN Arabic", "FR:" → that language; a plain matchup or a text naming
    /// two languages ("English / Español") → null. League names are not languages: "Spanish La Liga", "French Open".
    /// </summary>
    public static string? FromText(string? text)
    {
        if (string.IsNullOrWhiteSpace(text))
        {
            return null;
        }

        var found = new HashSet<string>(StringComparer.Ordinal);
        if (ArabicScript().IsMatch(text))
        {
            found.Add("ar");
        }

        if (CyrillicScript().IsMatch(text))
        {
            found.Add("ru");
        }

        var plain = Fold(text);
        foreach (var (pattern, language) in Words)
        {
            if (pattern.IsMatch(plain))
            {
                found.Add(language);
            }
        }

        foreach (Match m in BracketTag().Matches(plain))
        {
            if (Tags.TryGetValue(m.Groups[1].Value.Replace("-", string.Empty, StringComparison.Ordinal), out var language))
            {
                found.Add(language);
            }
        }

        if (PrefixTag().Match(plain) is { Success: true } p && Tags.TryGetValue(p.Groups[1].Value, out var prefixed))
        {
            found.Add(prefixed);
        }

        return found.Count == 1 ? found.First() : null;
    }

    /// <summary>
    /// What an address says, from its path and its lang/language/audio query value (never its host: a site's country
    /// domain says nothing about a stream): a "/es/" or "/es-mx/" segment, an "es" word in a segment ("colts-texans-es",
    /// "live-es.m3u8"), "espanol", "spanish", "deportes" → "es"; an "/en/" or "/en-us/" segment, "english" → "en";
    /// "portugues", "francais", "arabic"… → that language. Other two-letter segments are not read as languages: CDNs
    /// name edge locations that way ("/de/fra1/").
    /// </summary>
    public static string? FromUrl(string? url)
    {
        if (string.IsNullOrWhiteSpace(url) || !Uri.TryCreate(url, UriKind.Absolute, out var uri))
        {
            return null;
        }

        var found = new HashSet<string>(StringComparer.Ordinal);
        foreach (var raw in uri.AbsolutePath.Split('/', StringSplitOptions.RemoveEmptyEntries))
        {
            var segment = Fold(Uri.UnescapeDataString(raw));
            if (LocaleSegment().Match(segment) is { Success: true } locale && locale.Groups[1].Value is English or Spanish)
            {
                found.Add(locale.Groups[1].Value);
                continue;
            }

            var words = UrlWord().Matches(segment).Select(m => m.Value).ToList();
            var text = string.Join(' ', words);
            // ("en" alone is not English: "ver-partido-en-vivo"; "spanish-la-liga" is a league)
            if (words.Any(w => w is "es" or "espanol" or "deportes" or "castellano" or "latino" or "tudn" or "univision" or "telemundo")
                || SpanishAdjective().IsMatch(text))
            {
                found.Add(Spanish);
            }

            if (EnglishAdjective().IsMatch(text))
            {
                found.Add(English);
            }

            foreach (var w in words)
            {
                if (w is "portugues" or "francais" or "deutsch" or "italiano" or "arabic" or "polski" or "nederlands" or "turkce")
                {
                    found.Add(FromLanguageTag(w)!);
                }
            }
        }

        foreach (Match m in LanguageQuery().Matches(uri.Query))
        {
            if (FromLanguageTag(Uri.UnescapeDataString(m.Groups[1].Value)) is { } q)
            {
                found.Add(q);
            }
        }

        return found.Count == 1 ? found.First() : null;
    }

    /// <summary>An M3U tvg-language (or an HLS LANGUAGE attribute): "Spanish", "es", "spa", "es-MX", "Español";
    /// several separated by ';' or ',' count by the first.</summary>
    public static string? FromLanguageTag(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return null;
        }

        var first = Fold(value.Split(';', ',', '|')[0]).Trim();
        if (LanguageNames.TryGetValue(first, out var named))
        {
            return named;
        }

        var code = first.Split('-', '_')[0];
        if (code is "und" or "mul" or "mis" or "zxx")
        {
            return null; // undetermined, several, none
        }

        return Tags.TryGetValue(code, out var tagged) ? tagged
            : code.Length is 2 or 3 && code.All(char.IsAsciiLetterLower) ? code[..2] : null;
    }

    /// <summary>An M3U tvg-country ("MX", "US", "ES;US": the first).</summary>
    public static string? FromCountry(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return null;
        }

        var first = Fold(value.Split(';', ',', '|')[0]).Trim();
        return Countries.TryGetValue(first, out var language) ? language : null;
    }

    /// <summary>An M3U group-title: what its words say ("ES | Deportes", "LATINO", "Spanish"), or a Spanish-speaking
    /// region ("MEXICO", "Latin America").</summary>
    public static string? FromGroup(string? group)
    {
        if (FromText(group) is { } said)
        {
            return said;
        }

        var plain = Fold(group ?? string.Empty);
        return SpanishRegions.Any(r => StreamClassifier.ContainsKeyword(plain, r)) ? Spanish : null;
    }

    /// <summary>An M3U entry: tvg-language first, then its name, its group-title and its tvg-country; English when
    /// none says.</summary>
    public static string ForM3u(string name, IReadOnlyDictionary<string, string> attributes)
    {
        string? Attr(string key) => attributes.TryGetValue(key, out var v) ? v : null;
        return First(FromLanguageTag(Attr("tvg-language")), FromText(name), FromText(Attr("tvg-name")), FromGroup(Attr("group-title")),
            FromCountry(Attr("tvg-country"))) ?? English;
    }

    /// <summary>A master playlist's audio: the language every EXT-X-MEDIA TYPE=AUDIO rendition declares, or null when
    /// there are none, one declares none, or they differ (the player picks one: nothing to conclude).</summary>
    public static string? FromAudio(IReadOnlyCollection<string?> languages)
    {
        if (languages.Count == 0)
        {
            return null;
        }

        var codes = languages.Select(FromLanguageTag).ToList();
        return codes.All(c => c != null) && codes.Distinct(StringComparer.Ordinal).Count() == 1 ? codes[0] : null;
    }

    /// <summary>
    /// A Spanish channel gets " (Español)" after its name and " · Español" after its group, once (names that already
    /// say so, "Español" or "ESPN Deportes", keep theirs), so it never shares a name, an id or a group with its English sibling. English
    /// channels are left exactly as they are: their ids key favorites, DVR rules and Jellyfin's Live TV items.
    /// </summary>
    public static void Decorate(SourceChannel c)
    {
        if (Of(c) != Spanish)
        {
            return;
        }

        c.Language = Spanish;
        if (FromText(c.Name) != Spanish)
        {
            c.Name = c.Name.TrimEnd() + NameSuffix;
        }

        if (!c.Group.EndsWith(GroupSuffix, StringComparison.Ordinal))
        {
            c.Group = c.Group.TrimEnd() + GroupSuffix;
        }
    }

    /// <summary>Drops the channels in languages Tally does not keep and decorates the Spanish ones (see
    /// <see cref="Decorate"/>); runs after page-title names are settled and before ids are assigned.</summary>
    /// <returns>The channels kept and how many were dropped.</returns>
    public static (List<SourceChannel> Kept, int Dropped) Apply(IReadOnlyList<SourceChannel> channels)
    {
        var kept = new List<SourceChannel>(channels.Count);
        foreach (var c in channels)
        {
            if (!IsKept(c.Language))
            {
                continue;
            }

            Decorate(c);
            kept.Add(c);
        }

        return (kept, channels.Count - kept.Count);
    }

    /// <summary>The name without the " (Español)" Tally added ("Colts at Texans (Español)" → "Colts at Texans").</summary>
    public static string BaseName(string name)
        => name.EndsWith(NameSuffix, StringComparison.Ordinal) ? name[..^NameSuffix.Length] : name;

    /// <summary>Lower case, accents removed ("Español" → "espanol", "Galavisión" → "galavision").</summary>
    private static string Fold(string text)
    {
        var decomposed = text.Normalize(NormalizationForm.FormD);
        var sb = new StringBuilder(decomposed.Length);
        foreach (var ch in decomposed)
        {
            if (CharUnicodeInfo.GetUnicodeCategory(ch) != UnicodeCategory.NonSpacingMark)
            {
                sb.Append(char.ToLowerInvariant(ch));
            }
        }

        return sb.ToString().Normalize(NormalizationForm.FormC);
    }

    // A language adjective followed by a competition is the competition's name, not the stream's language.
    private const string NotACompetition = @"(?!\s+(?:la\s*liga|liga|league|primera|segunda|super\s*(?:cup|lig|copa)|supercopa|cup|copa|coppa|open|grand\s+prix|gp|masters|championship|football|soccer|basketball|serie|ligue|bundesliga|eredivisie|premier|top\s*14|national\s+team))";

    [GeneratedRegex(@"\b(?:espanol|castellano|deportes|tudn|univision|unimas|telemundo|galavision|universo|azteca|televisa|latino|latinoamerica|en\s+vivo|en\s+directo)\b")]
    private static partial Regex SpanishWords();

    [GeneratedRegex(@"\bspanish\b" + NotACompetition)]
    private static partial Regex SpanishAdjective();

    [GeneratedRegex(@"\benglish\b" + NotACompetition)]
    private static partial Regex EnglishAdjective();

    [GeneratedRegex(@"\b(?:portugues|brasileiro)\b|\bportuguese\b" + NotACompetition)]
    private static partial Regex PortugueseWords();

    [GeneratedRegex(@"\bfrancais\b|\bfrench\b" + NotACompetition)]
    private static partial Regex FrenchWords();

    [GeneratedRegex(@"\bdeutsch\b|\bgerman\b" + NotACompetition)]
    private static partial Regex GermanWords();

    [GeneratedRegex(@"\bitaliano\b|\bitalian\b" + NotACompetition)]
    private static partial Regex ItalianWords();

    [GeneratedRegex(@"\barabic\b")]
    private static partial Regex ArabicWords();

    [GeneratedRegex(@"\brussian\b" + NotACompetition)]
    private static partial Regex RussianWords();

    [GeneratedRegex(@"\bturkce\b|\bturkish\b" + NotACompetition)]
    private static partial Regex TurkishWords();

    [GeneratedRegex(@"\bnederlands\b|\bdutch\b" + NotACompetition)]
    private static partial Regex DutchWords();

    // "[ES]", "(eng)", "[pt-br]"
    [GeneratedRegex(@"[\(\[]\s*([a-z]{2,5}(?:-[a-z]{2})?)\s*[\)\]]")]
    private static partial Regex BracketTag();

    // "ES: Fox Deportes", "ES | Sports", "PT - Sport TV"
    [GeneratedRegex(@"^\s*([a-z]{2,5})\s*(?::|\||\s-\s)")]
    private static partial Regex PrefixTag();

    [GeneratedRegex(@"[؀-ۿ]")]
    private static partial Regex ArabicScript();

    [GeneratedRegex(@"[Ѐ-ӿ]")]
    private static partial Regex CyrillicScript();

    // "/es/", "/es-mx/", "/pt_br/"
    [GeneratedRegex(@"^([a-z]{2})(?:[-_][a-z]{2})?$")]
    private static partial Regex LocaleSegment();

    [GeneratedRegex(@"[a-z]+")]
    private static partial Regex UrlWord();

    [GeneratedRegex(@"(?i)[?&](?:lang|language|audio|locale|hl)=([^&#]+)")]
    private static partial Regex LanguageQuery();
}
