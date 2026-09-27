using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Reflection;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Tally.Models;
using Jellyfin.Plugin.Tally.Scores;
using Jellyfin.Plugin.Tally.Sources;
using Microsoft.Extensions.Logging;
using SkiaSharp;

namespace Jellyfin.Plugin.Tally.Services;

/// <summary>
/// Artwork for native clients. TV apps (Swiftfin, Android TV, Wholphin…) can't run the JellyTV web
/// UI — they draw their own screens from Jellyfin's Live TV data, and fall back to cramped text
/// lists when a channel has no image. Streams scraped from web pages never have one, so this renders
/// a 16:9 card per channel: a matchup card (both team logos, names, league, kickoff) when the channel
/// is carrying a known game, otherwise the channel name set large. Same look as the web UI.
/// </summary>
public sealed class CardArtService
{
    public const int Width = 1280;
    public const int Height = 720;

    private static readonly SKColor Ground = SKColor.Parse("#0e0f0e");
    private static readonly SKColor Rule = SKColor.Parse("#2a2c2a");
    private static readonly SKColor Text = SKColor.Parse("#e3e5de");
    private static readonly SKColor Muted = SKColor.Parse("#8b9084");
    private static readonly SKColor Accent = SKColor.Parse("#ffb000");
    private static readonly SKColor Live = SKColor.Parse("#ff3b30");

    private static readonly Lazy<SKTypeface> Sans = new(() => LoadFont("IBMPlexSans-SemiBold.ttf"));
    private static readonly Lazy<SKTypeface> Mono = new(() => LoadFont("IBMPlexMono-Medium.ttf"));

    private readonly IHttpClientFactory _httpClientFactory;
    private readonly SourceManager _sourceManager;
    private readonly ScoreboardService _scoreboard;
    private readonly TeamDirectory? _teams;
    private readonly ILogger<CardArtService> _logger;

    private readonly ConcurrentDictionary<string, byte[]?> _logos = new(StringComparer.OrdinalIgnoreCase);
    private readonly ConcurrentDictionary<string, byte[]> _cards = new(StringComparer.Ordinal);
    private readonly SemaphoreSlim _indexLock = new(1, 1);
    private Dictionary<string, GameInfo> _index = new(StringComparer.OrdinalIgnoreCase);
    private DateTimeOffset _indexAt = DateTimeOffset.MinValue;

    public CardArtService(IHttpClientFactory httpClientFactory, SourceManager sourceManager, ScoreboardService scoreboard, ILogger<CardArtService> logger, TeamDirectory? teams = null)
    {
        _httpClientFactory = httpClientFactory;
        _sourceManager = sourceManager;
        _scoreboard = scoreboard;
        _logger = logger;
        _teams = teams;
    }

    /// <summary>Channel id → the game it is carrying. Only confident matches (channel or programme
    /// names both teams) — a broadcaster match can be a different regional game.</summary>
    public Task<IReadOnlyDictionary<string, GameInfo>> GetChannelGamesAsync(CancellationToken ct) => GetChannelGamesAsync(ct, fresh: false);

    /// <param name="fresh">Rebuild the index now instead of using one up to a minute old.</param>
    public async Task<IReadOnlyDictionary<string, GameInfo>> GetChannelGamesAsync(CancellationToken ct, bool fresh)
    {
        if (!(Plugin.Instance?.Configuration.ScoresEnabled ?? true))
        {
            return new Dictionary<string, GameInfo>();
        }

        // Nothing to match yet (server just started, sources still scanning). Don't cache that:
        // an empty answer held for a minute means a minute of blank cards and alphabetical channels.
        if (_sourceManager.GetChannels().Count == 0)
        {
            return new Dictionary<string, GameInfo>();
        }

        if (!fresh && DateTimeOffset.UtcNow - _indexAt < TimeSpan.FromSeconds(60))
        {
            return _index;
        }

        await _indexLock.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            if (DateTimeOffset.UtcNow - _indexAt < (fresh ? TimeSpan.FromSeconds(2) : TimeSpan.FromSeconds(60)))
            {
                return _index;
            }

            var games = await _scoreboard.GetGamesAsync(ct).ConfigureAwait(false);
            var probes = _sourceManager.GetChannels()
                .Select(c => new ChannelProbe(c.Id, c.Name, _sourceManager.GetNowNext(c.Id).Now?.Title))
                .ToList();
            _index = BuildIndex(games, probes);
            _indexAt = DateTimeOffset.UtcNow;
            return _index;
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            _logger.LogWarning("JellyTV cards: could not match channels to games: {Message}", ex.Message);
            return _index;
        }
        finally
        {
            _indexLock.Release();
        }
    }

    public static Dictionary<string, GameInfo> BuildIndex(List<GameInfo> games, IReadOnlyList<ChannelProbe> probes)
    {
        GameChannelMatcher.Match(games, probes);
        var now = DateTimeOffset.UtcNow;
        foreach (var g in games)
        {
            GameHeat.Apply(g, now);
        }

        var map = new Dictionary<string, GameInfo>(StringComparer.OrdinalIgnoreCase);

        // live games claim a channel before upcoming ones; earlier kickoffs before later
        foreach (var g in games.OrderBy(g => g.State == "in" ? 0 : 1).ThenBy(g => g.Start))
        {
            foreach (var gc in g.Channels.Where(x => x.Kind != "network"))
            {
                map.TryAdd(gc.Id, g);
            }
        }

        return map;
    }

    /// <summary>
    /// Card address key. Deliberately NOT the channel id: ids are derived from the stream URL, and scraped
    /// streams carry rotating tokens, so ids change on every source scan — while Jellyfin and the apps keep
    /// the image URLs they were given. The name is what stays put ("Denver Broncos Jacksonville Jaguars").
    /// </summary>
    public static string StableKey(string channelName)
    {
        var hash = System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(channelName.Trim().ToLowerInvariant()));
        return Convert.ToHexString(hash)[..16].ToLowerInvariant();
    }

    /// <summary>Server-relative card URL. Carries the name too, so a card can still be drawn after its
    /// channel has gone (a finished game's guide entry) instead of answering 404.</summary>
    public static string CardPath(SourceChannel c, GameInfo? game, DateTimeOffset now)
        => $"/JellyTV/Card/{StableKey(c.Name)}.png?v={Version(game, now)}&n={Uri.EscapeDataString(c.Name.Length > 80 ? c.Name[..80] : c.Name)}";

    /// <summary>How often a live card is redrawn (its clock and "as of" stamp move even when the score doesn't).</summary>
    public static readonly TimeSpan LiveCardInterval = TimeSpan.FromSeconds(120);

    /// <summary>Changes whenever the card's content would — goes into the image URL so Jellyfin and
    /// the apps refetch. A live game's version rolls over every <see cref="LiveCardInterval"/>.</summary>
    public static string Version(GameInfo? game, DateTimeOffset now)
    {
        if (game == null)
        {
            return "c2"; // the channel's own card (c2: drawn as a matchup when its name reads as one)
        }

        return game.State == "in"
            ? $"g{game.Id}-in-{game.Away.Score ?? 0}-{game.Home.Score ?? 0}-{game.Period}-{now.ToUnixTimeSeconds() / (long)LiveCardInterval.TotalSeconds}"
            : $"g{game.Id}-{game.State}";
    }

    /// <summary>
    /// Channel order for native apps, which list channels by number: live games hottest-first, then
    /// upcoming games by kickoff, then everything else in its original order. The native channel
    /// grid becomes a heat-sorted scoreboard.
    /// </summary>
    public static List<SourceChannel> HeatOrder(IReadOnlyList<SourceChannel> channels, IReadOnlyDictionary<string, GameInfo> games)
        => channels
            .Select((c, i) => (Channel: c, Index: i, Game: games.TryGetValue(c.Id, out var g) ? g : null))
            .OrderBy(x => x.Game == null ? 2 : x.Game.State == "in" ? 0 : 1)
            .ThenByDescending(x => x.Game?.State == "in" ? x.Game.Heat : 0)
            .ThenBy(x => x.Game?.State == "in" ? DateTimeOffset.MinValue : x.Game?.Start ?? DateTimeOffset.MaxValue)
            .ThenBy(x => x.Index)
            .Select(x => x.Channel)
            .ToList();

    /// <summary>The channel's card, <paramref name="width"/> wide (null: full size, see <see cref="ArtRequest.SnapWidth"/>),
    /// with its times in <paramref name="zone"/> (null: the server's zone).</summary>
    public async Task<byte[]> RenderAsync(SourceChannel channel, int? width, TimeZoneInfo? zone, CancellationToken ct, string? wantedVersion = null)
    {
        var games = await GetChannelGamesAsync(ct).ConfigureAwait(false);
        games.TryGetValue(channel.Id, out var game);

        // The board resolves games live while this index is up to a minute old: a card asked for as a game's
        // ("v=g...") that the index does not know yet is looked up afresh, or its address (which clients cache)
        // would get the channel's own card.
        if (wantedVersion != null && wantedVersion.StartsWith('g') && (game == null || !wantedVersion.StartsWith("g" + game.Id + "-", StringComparison.Ordinal)))
        {
            games = await GetChannelGamesAsync(ct, fresh: true).ConfigureAwait(false);
            games.TryGetValue(channel.Id, out game);
        }

        var now = DateTimeOffset.UtcNow;
        zone ??= TimeZoneInfo.Local;
        var key = CacheKey(channel.Id, Version(game, now), channel.Name, game == null ? null : zone, null);
        if (width is { } w)
        {
            var sizedKey = CacheKey(channel.Id, Version(game, now), channel.Name, game == null ? null : zone, w);
            if (_cards.TryGetValue(sizedKey, out var sized))
            {
                return sized;
            }

            var full = await RenderFullAsync(channel, game, zone, now, key, ct).ConfigureAwait(false);
            return Store(sizedKey, ArtRequest.Scale(full, w));
        }

        return await RenderFullAsync(channel, game, zone, now, key, ct).ConfigureAwait(false);
    }

    /// <summary>Cache key of one drawn card: the channel, what is on it, the zone its times are in (matchup cards only:
    /// a title card shows no time) and its width (null: full size).</summary>
    public static string CacheKey(string channelId, string version, string name, TimeZoneInfo? zone, int? width)
        => channelId + "|" + version + "|" + name + "|" + (zone == null ? "-" : ArtRequest.ZoneKey(zone)) + "|" + (width?.ToString(CultureInfo.InvariantCulture) ?? "full");

    private async Task<byte[]> RenderFullAsync(SourceChannel channel, GameInfo? game, TimeZoneInfo zone, DateTimeOffset now, string key, CancellationToken ct)
    {
        if (_cards.TryGetValue(key, out var cached))
        {
            return cached;
        }

        byte[] png;
        if (game != null)
        {
            var away = await GetDarkLogoAsync(game.Away.Logo, ct).ConfigureAwait(false);
            var home = await GetDarkLogoAsync(game.Home.Logo, ct).ConfigureAwait(false);
            png = RenderMatchup(game, away, home, zone, now);
        }
        else
        {
            var (card, complete) = await RenderChannelAsync(channel, ct).ConfigureAwait(false);
            if (!complete)
            {
                return card; // the team lists are still loading: draw it again next time
            }

            png = card;
        }

        return Store(key, png);
    }

    /// <summary>
    /// A channel no game on the scoreboard claims: when its name reads as two teams ("Mississippi State Bulldogs
    /// Missouri Tigers", "Riverton Otters vs Lakeside Herons"), a matchup card without a time (their logos when a team
    /// list knows them, see <see cref="TeamDirectory"/>); otherwise its name set large. Both carry the league or sport.
    /// <c>complete</c> is false when the team lists did not answer in time (the card is then not kept).
    /// </summary>
    private async Task<(byte[] Png, bool Complete)> RenderChannelAsync(SourceChannel channel, CancellationToken ct)
    {
        ChannelMatchup? matchup;
        var complete = true;
        if (_teams == null)
        {
            matchup = TeamDirectory.SplitOnly(channel.Name);
        }
        else
        {
            using var wait = CancellationTokenSource.CreateLinkedTokenSource(ct);
            wait.CancelAfter(TimeSpan.FromSeconds(4));
            try
            {
                matchup = await _teams.ReadAsync(channel.Name, channel.Group, _scoreboard.ActiveLeagues, wait.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (!ct.IsCancellationRequested)
            {
                matchup = TeamDirectory.SplitOnly(channel.Name);
                complete = false;
            }
        }

        if (matchup == null)
        {
            return (RenderTitle(channel.Name, LeagueOrSport(null, channel.Group)), complete);
        }

        var away = matchup.Away is { Logo.Length: > 0 } a ? await GetDarkLogoAsync(a.Logo, ct).ConfigureAwait(false) : null;
        var home = matchup.Home is { Logo.Length: > 0 } h ? await GetDarkLogoAsync(h.Logo, ct).ConfigureAwait(false) : null;
        return (RenderChannelMatchup(matchup, LeagueOrSport(matchup.League, channel.Group), away, home), complete);
    }

    /// <summary>"COLLEGE FOOTBALL" for a league Tally knows, else the channel's sport ("AMERICAN FOOTBALL"); nothing for
    /// the catch-all groups.</summary>
    public static string? LeagueOrSport(string? league, string? group)
        => league != null ? LeagueCatalog.Label(league)
            : string.IsNullOrWhiteSpace(group) || group is "Live" or "Sports" ? null
            : group;

    /// <summary>
    /// The card of a channel whose name reads as two teams but that no game on the scoreboard claims: the pre-game
    /// template (league on top, both sides, "AT" or "VS" between) without a time. Teams a team list knows get their
    /// logo (or a box with their abbreviation) and short name; names only split at "vs" / "at" are set as two lines.
    /// </summary>
    public static byte[] RenderChannelMatchup(ChannelMatchup m, string? label, byte[]? awayLogo, byte[]? homeLogo)
    {
        using var surface = SKSurface.Create(new SKImageInfo(Width, Height, SKColorType.Rgba8888, SKAlphaType.Premul));
        var c = surface.Canvas;
        DrawGround(c);
        if (!string.IsNullOrWhiteSpace(label))
        {
            DrawText(c, label.ToUpperInvariant(), Mono.Value, 34, Accent, 64, 104, SKTextAlign.Left, 1150);
        }

        var joiner = m.At ? "AT" : "VS";
        if (m.Away != null && m.Home != null)
        {
            DrawSide(c, AsTeam(m.Away), awayLogo, 340);
            DrawSide(c, AsTeam(m.Home), homeLogo, 940);
            DrawText(c, joiner, Mono.Value, 40, Muted, Width / 2f, 352, SKTextAlign.Center, 120);
            return Encode(surface);
        }

        // two names, no team list: one per line, as large as both fit
        using var paint = new SKPaint { Typeface = Sans.Value, IsAntialias = true };
        float size = 120;
        for (; size > 52; size -= 4)
        {
            paint.TextSize = size;
            if (paint.MeasureText(m.AwayText) <= Width - 128 && paint.MeasureText(m.HomeText) <= Width - 128)
            {
                break;
            }
        }

        // away name, the joiner on a line of its own, home name: the block (cap height 0.72 of the size) centered
        // below the league line
        var cap = size * 0.72f;
        var block = cap + (size * 0.3f) + 46 + 34 + cap;
        var away = 400f - (block / 2) + cap;
        var join = away + (size * 0.3f) + 46;
        DrawText(c, m.AwayText, Sans.Value, size, Text, 64, away, SKTextAlign.Left, Width - 128);
        DrawText(c, joiner, Mono.Value, 36, Muted, 64, join, SKTextAlign.Left, 200);
        DrawText(c, m.HomeText, Sans.Value, size, Text, 64, join + 34 + cap, SKTextAlign.Left, Width - 128);
        return Encode(surface);
    }

    private static GameTeam AsTeam(DirectoryTeam t) => new()
    {
        Abbr = t.Abbr,
        Name = t.Name,
        ShortName = string.IsNullOrEmpty(t.ShortName) ? t.Name : t.ShortName,
        Nickname = t.Nickname,
        Location = t.Location,
        Logo = t.Logo
    };

    private byte[] Store(string key, byte[] png)
    {
        if (_cards.Count > 400)
        {
            _cards.Clear();
        }

        _cards[key] = png;
        return png;
    }

    public static byte[] RenderMatchup(GameInfo g, byte[]? awayLogo, byte[]? homeLogo, TimeZoneInfo zone, DateTimeOffset now)
    {
        using var surface = SKSurface.Create(new SKImageInfo(Width, Height, SKColorType.Rgba8888, SKAlphaType.Premul));
        var c = surface.Canvas;
        DrawGround(c);

        if (g.State == "in")
        {
            DrawLive(c, g, awayLogo, homeLogo, zone, now);
            return Encode(surface);
        }

        DrawPreGame(c, g, awayLogo, homeLogo, TimeZoneInfo.ConvertTime(g.Start, zone).ToString("ddd h:mm tt", CultureInfo.InvariantCulture));
        return Encode(surface);
    }

    /// <summary>
    /// A recording's 16:9 thumb: the pre-game card with the game's day in the corner where a card shows the start
    /// time. It becomes the library item's image, which every client shows as it is, in whatever zone the viewer is
    /// in, so it carries no time of day (that would be the server's); the day is the one the recording is filed
    /// under (its file name and NFO date, in <paramref name="zone"/>, the server's). The Tally apps show the time
    /// under the picture in the viewer's zone.
    /// </summary>
    public static byte[] RenderRecordingThumb(GameInfo g, byte[]? awayLogo, byte[]? homeLogo, TimeZoneInfo zone)
    {
        using var surface = SKSurface.Create(new SKImageInfo(Width, Height, SKColorType.Rgba8888, SKAlphaType.Premul));
        var c = surface.Canvas;
        DrawGround(c);
        DrawPreGame(c, g, awayLogo, homeLogo, RecordingDay(g, zone));
        return Encode(surface);
    }

    /// <summary>"SAT SEP 26": the day a recording's thumb shows.</summary>
    public static string RecordingDay(GameInfo g, TimeZoneInfo zone)
        => TimeZoneInfo.ConvertTime(g.Start, zone).ToString("ddd MMM d", CultureInfo.InvariantCulture).ToUpperInvariant();

    /// <summary>Pre-game layout: league and <paramref name="corner"/> on top, both teams, the networks below.</summary>
    private static void DrawPreGame(SKCanvas c, GameInfo g, byte[]? awayLogo, byte[]? homeLogo, string corner)
    {
        DrawText(c, g.League.ToUpperInvariant(), Mono.Value, 34, Accent, 64, 104, SKTextAlign.Left, 560);
        DrawText(c, corner.ToUpperInvariant(), Mono.Value, 34, Text, Width - 64, 104, SKTextAlign.Right, 560);

        DrawSide(c, g.Away, awayLogo, 340);
        DrawSide(c, g.Home, homeLogo, 940);
        DrawText(c, "AT", Mono.Value, 40, Muted, Width / 2f, 352, SKTextAlign.Center, 120);

        if (g.Broadcasts.Count > 0)
        {
            DrawText(c, string.Join("  ·  ", g.Broadcasts.Take(3)).ToUpperInvariant(), Mono.Value, 28, Muted, Width / 2f, 668, SKTextAlign.Center, 1100);
        }
    }

    /// <summary>In-progress layout: the score is the headline, with clock, top heat tag and an honest
    /// "as of" stamp — a card is a snapshot, minutes old by the time a TV app shows it.</summary>
    private static void DrawLive(SKCanvas c, GameInfo g, byte[]? awayLogo, byte[]? homeLogo, TimeZoneInfo zone, DateTimeOffset now)
    {
        DrawText(c, g.League.ToUpperInvariant(), Mono.Value, 34, Accent, 64, 104, SKTextAlign.Left, 300);
        DrawText(c, g.Detail.ToUpperInvariant(), Mono.Value, 34, Accent, Width - 64, 104, SKTextAlign.Right, 420);

        if (g.Tags.Count > 0)
        {
            using var tagPaint = new SKPaint { Typeface = Mono.Value, TextSize = 30, IsAntialias = true };
            var label = g.Tags[0];
            var w = tagPaint.MeasureText(label) + 36;
            using var fill = new SKPaint { Color = g.Heat >= 70 ? Live : Rule, Style = SKPaintStyle.Fill };
            c.DrawRect(new SKRect((Width - w) / 2f, 66, (Width + w) / 2f, 116), fill);
            DrawText(c, label, Mono.Value, 30, g.Heat >= 70 ? Ground : Text, Width / 2f, 102, SKTextAlign.Center, w);
        }

        foreach (var (team, logo, cx) in new[] { (g.Away, awayLogo, 340f), (g.Home, homeLogo, 940f) })
        {
            DrawLogo(c, team, logo, cx, 150, 210);
            DrawText(c, (team.Score ?? 0).ToString(CultureInfo.InvariantCulture), Mono.Value, 170, Text, cx, 540, SKTextAlign.Center, 420);
            DrawText(c, string.IsNullOrEmpty(team.ShortName) ? team.Abbr : team.ShortName, Sans.Value, 54, Text, cx, 622, SKTextAlign.Center, 520);
            if (team.HasPossession)
            {
                using var dot = new SKPaint { Color = Accent, Style = SKPaintStyle.Fill };
                c.DrawRect(new SKRect(cx - 9, 646, cx + 9, 664), dot);
            }
        }

        var asOf = "AS OF " + TimeZoneInfo.ConvertTime(now, zone).ToString("h:mm tt", CultureInfo.InvariantCulture).ToUpperInvariant();
        DrawText(c, asOf, Mono.Value, 24, Muted, Width / 2f, 690, SKTextAlign.Center, 400);
    }

    /// <summary>Artwork for a recorded game (the DVR): a 16:9 thumb (the pre-game matchup card: logos, names, league
    /// and the day, see <see cref="RenderRecordingThumb"/>) and a 2:3 poster. Neither ever shows a score: the game is
    /// drawn as not yet started. <paramref name="zone"/> is the server's, the one the recording's date is in.</summary>
    public async Task<(byte[] Thumb, byte[] Poster)> RenderRecordingArtAsync(GameInfo scoreless, TimeZoneInfo zone, CancellationToken ct)
    {
        var away = await GetDarkLogoAsync(scoreless.Away.Logo, ct).ConfigureAwait(false);
        var home = await GetDarkLogoAsync(scoreless.Home.Logo, ct).ConfigureAwait(false);
        var game = Scoreless(scoreless);
        return (RenderRecordingThumb(game, away, home, zone), RenderPoster(game, away, home, zone));
    }

    /// <summary>A recording's thumb drawn again (recordings made before thumbs lost their time of day), or null when a
    /// team's logo cannot be fetched right now, so a picture with logos is not replaced by one without.</summary>
    public async Task<byte[]?> RedrawRecordingThumbAsync(GameInfo scoreless, TimeZoneInfo zone, CancellationToken ct)
    {
        var away = await GetDarkLogoAsync(scoreless.Away.Logo, ct).ConfigureAwait(false);
        var home = await GetDarkLogoAsync(scoreless.Home.Logo, ct).ConfigureAwait(false);
        if ((away == null && IsLogoUrl(scoreless.Away.Logo)) || (home == null && IsLogoUrl(scoreless.Home.Logo)))
        {
            return null;
        }

        return RenderRecordingThumb(Scoreless(scoreless), away, home, zone);
    }

    private static GameInfo Scoreless(GameInfo g)
    {
        var game = g.Clone();
        game.State = "pre";
        game.Away.Score = null;
        game.Home.Score = null;
        return game;
    }

    public const int PosterWidth = 1000;
    public const int PosterHeight = 1500;

    /// <summary>2:3 poster of a matchup: league, both teams stacked with "AT" between them, and the date.</summary>
    public static byte[] RenderPoster(GameInfo g, byte[]? awayLogo, byte[]? homeLogo, TimeZoneInfo zone)
    {
        using var surface = SKSurface.Create(new SKImageInfo(PosterWidth, PosterHeight, SKColorType.Rgba8888, SKAlphaType.Premul));
        var c = surface.Canvas;
        c.Clear(Ground);
        using (var accent = new SKPaint { Color = Accent, Style = SKPaintStyle.Fill })
        {
            c.DrawRect(new SKRect(0, 0, PosterWidth, 10), accent);
        }

        using (var rule = new SKPaint { Color = Rule, Style = SKPaintStyle.Stroke, StrokeWidth = 3 })
        {
            c.DrawRect(new SKRect(1.5f, 1.5f, PosterWidth - 1.5f, PosterHeight - 1.5f), rule);
        }

        const float cx = PosterWidth / 2f;
        DrawText(c, (string.IsNullOrEmpty(g.League) ? "SPORTS" : g.League).ToUpperInvariant(), Mono.Value, 56, Accent, cx, 130, SKTextAlign.Center, 860);

        DrawLogo(c, g.Away, awayLogo, cx, 200, 340);
        DrawText(c, TeamLabel(g.Away), Sans.Value, 76, Text, cx, 640, SKTextAlign.Center, 900);
        DrawText(c, "AT", Mono.Value, 54, Muted, cx, 745, SKTextAlign.Center, 200);
        DrawLogo(c, g.Home, homeLogo, cx, 800, 340);
        DrawText(c, TeamLabel(g.Home), Sans.Value, 76, Text, cx, 1240, SKTextAlign.Center, 900);

        var date = TimeZoneInfo.ConvertTime(g.Start, zone).ToString("MMM d, yyyy", CultureInfo.InvariantCulture).ToUpperInvariant();
        DrawText(c, date, Mono.Value, 48, Text, cx, 1400, SKTextAlign.Center, 860);
        return Encode(surface);
    }

    private static string TeamLabel(GameTeam t) => !string.IsNullOrEmpty(t.ShortName) ? t.ShortName : !string.IsNullOrEmpty(t.Name) ? t.Name : t.Abbr;

    public static byte[] RenderTitle(string name, string? group)
    {
        using var surface = SKSurface.Create(new SKImageInfo(Width, Height, SKColorType.Rgba8888, SKAlphaType.Premul));
        var c = surface.Canvas;
        DrawGround(c);

        if (!string.IsNullOrWhiteSpace(group))
        {
            DrawText(c, group.ToUpperInvariant(), Mono.Value, 34, Accent, 64, 104, SKTextAlign.Left, 1150);
        }

        // largest size at which the name fits in three lines
        using var paint = new SKPaint { Typeface = Sans.Value, IsAntialias = true, Color = Text };
        var lines = new List<string> { name };
        float size = 132;
        for (; size >= 52; size -= 8)
        {
            paint.TextSize = size;
            lines = Wrap(name, paint, Width - 128);
            if (lines.Count <= 3)
            {
                break;
            }
        }

        lines = lines.Take(3).ToList();
        var lineHeight = size * 1.12f;
        var y = (Height / 2f) + 40 - ((lines.Count - 1) * lineHeight / 2f);
        foreach (var line in lines)
        {
            DrawText(c, line, Sans.Value, size, Text, 64, y, SKTextAlign.Left, Width - 128);
            y += lineHeight;
        }

        return Encode(surface);
    }

    private static void DrawSide(SKCanvas c, GameTeam team, byte[]? logo, float cx)
    {
        DrawLogo(c, team, logo, cx, 180, 300);
        var label = string.IsNullOrEmpty(team.ShortName) ? team.Abbr : team.ShortName;
        DrawText(c, label, Sans.Value, 84, Text, cx, 590, SKTextAlign.Center, 540);
    }

    private static void DrawLogo(SKCanvas c, GameTeam team, byte[]? logo, float cx, float top, float box)
    {
        var rect = new SKRect(cx - (box / 2), top, cx + (box / 2), top + box);
        using var bmp = logo == null ? null : SKBitmap.Decode(logo);
        if (bmp != null)
        {
            using var paint = new SKPaint { FilterQuality = SKFilterQuality.High, IsAntialias = true };
            c.DrawBitmap(bmp, rect, paint);
        }
        else
        {
            using var stroke = new SKPaint { Style = SKPaintStyle.Stroke, StrokeWidth = 2, Color = Rule };
            c.DrawRect(rect, stroke);
            DrawText(c, team.Abbr.ToUpperInvariant(), Mono.Value, box * 0.32f, Muted, cx, top + (box * 0.62f), SKTextAlign.Center, box - 20);
        }
    }

    private static void DrawGround(SKCanvas c)
    {
        c.Clear(Ground);
        using var accent = new SKPaint { Color = Accent, Style = SKPaintStyle.Fill };
        c.DrawRect(new SKRect(0, 0, Width, 6), accent);
        using var rule = new SKPaint { Color = Rule, Style = SKPaintStyle.Stroke, StrokeWidth = 2 };
        c.DrawRect(new SKRect(1, 1, Width - 1, Height - 1), rule);
    }

    /// <summary>Draws one line, shrinking the type until it fits <paramref name="maxWidth"/>.</summary>
    private static void DrawText(SKCanvas c, string text, SKTypeface face, float size, SKColor color, float x, float y, SKTextAlign align, float maxWidth)
    {
        using var paint = new SKPaint { Typeface = face, TextSize = size, Color = color, IsAntialias = true, TextAlign = align };
        while (paint.TextSize > 18 && paint.MeasureText(text) > maxWidth)
        {
            paint.TextSize -= 2;
        }

        c.DrawText(text, x, y, paint);
    }

    private static List<string> Wrap(string text, SKPaint paint, float maxWidth)
    {
        var lines = new List<string>();
        var current = string.Empty;
        foreach (var word in text.Split(' ', StringSplitOptions.RemoveEmptyEntries))
        {
            var candidate = current.Length == 0 ? word : current + " " + word;
            if (current.Length > 0 && paint.MeasureText(candidate) > maxWidth)
            {
                lines.Add(current);
                current = word;
            }
            else
            {
                current = candidate;
            }
        }

        if (current.Length > 0)
        {
            lines.Add(current);
        }

        return lines.Count == 0 ? new List<string> { text } : lines;
    }

    private static byte[] Encode(SKSurface surface)
    {
        using var image = surface.Snapshot();
        using var data = image.Encode(SKEncodedImageFormat.Png, 100);
        return data.ToArray();
    }

    private static SKTypeface LoadFont(string file)
    {
        using var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream("Jellyfin.Plugin.Tally.Assets." + file);
        if (stream != null)
        {
            using var ms = new MemoryStream();
            stream.CopyTo(ms);
            ms.Position = 0;
            var face = SKTypeface.FromStream(ms);
            if (face != null)
            {
                return face;
            }
        }

        return SKTypeface.Default;
    }

    /// <summary>The card is dark: prefer the CDN's dark-background variant of a logo, fall back to the standard one.</summary>
    private async Task<byte[]?> GetDarkLogoAsync(string url, CancellationToken ct)
    {
        var dark = url.Replace("/500/", "/500-dark/", StringComparison.Ordinal);
        return (dark != url ? await GetLogoAsync(dark, ct).ConfigureAwait(false) : null)
            ?? await GetLogoAsync(url, ct).ConfigureAwait(false);
    }

    /// <summary>Only the scoreboard's own logo CDN is ever fetched: the URL comes out of third-party JSON.</summary>
    private static bool IsLogoUrl(string? url)
        => Uri.TryCreate(url, UriKind.Absolute, out var uri) && uri.Scheme == Uri.UriSchemeHttps
           && uri.Host.EndsWith(".espncdn.com", StringComparison.OrdinalIgnoreCase);

    private async Task<byte[]?> GetLogoAsync(string url, CancellationToken ct)
    {
        if (!IsLogoUrl(url))
        {
            return null;
        }

        var uri = new Uri(url);

        if (_logos.TryGetValue(url, out var cached))
        {
            return cached;
        }

        try
        {
            var client = _httpClientFactory.CreateClient("jellytv");
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
            timeout.CancelAfter(TimeSpan.FromSeconds(8));
            var bytes = await client.GetByteArrayAsync(uri, timeout.Token).ConfigureAwait(false);
            if (bytes.Length is > 0 and < 2_000_000)
            {
                _logos[url] = bytes;
                return bytes;
            }
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException)
        {
            _logger.LogDebug("JellyTV cards: logo fetch failed for {Url}: {Message}", url, ex.Message);
        }

        return null; // not cached: try again on the next render
    }
}
