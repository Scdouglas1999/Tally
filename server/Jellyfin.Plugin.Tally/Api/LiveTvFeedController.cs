using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Xml;
using Jellyfin.Plugin.Tally.Models;
using Jellyfin.Plugin.Tally.Scores;
using Jellyfin.Plugin.Tally.Services;
using Jellyfin.Plugin.Tally.Sources;
using Microsoft.AspNetCore.Mvc;

namespace Jellyfin.Plugin.Tally.Api;

/// <summary>
/// Feeds consumed by Jellyfin's built-in Live TV stack: an M3U tuner playlist and an XMLTV guide. No [Authorize]:
/// Jellyfin's tuner fetches them as an anonymous client. But the playlist hands out every channel's signed stream
/// address (which plays the owner's paid sources through this server), so both answer only Jellyfin itself: a
/// request over loopback that carries the feed key (<see cref="FeedKey"/>), which only the registered tuner and
/// guide addresses have (<see cref="LiveTvRegistrationService"/>). The key keeps out the rest of the internet
/// even behind a reverse proxy on the same machine, where every visitor arrives over loopback.
/// </summary>
[ApiController]
[Route("JellyTV")]
public class LiveTvFeedController : ControllerBase
{
    private readonly SourceManager _sourceManager;
    private readonly StreamSigner _signer;
    private readonly CardArtService _cards;
    private readonly Live.RedZoneService _redZone;

    public LiveTvFeedController(SourceManager sourceManager, StreamSigner signer, CardArtService cards, Live.RedZoneService redZone)
    {
        _redZone = redZone;
        _sourceManager = sourceManager;
        _signer = signer;
        _cards = cards;
    }

    /// <summary>The query key (<c>?k=</c>) the feeds require; derived from the proxy secret.</summary>
    public static string FeedKey(StreamSigner signer) => signer.Sign("feed:livetv", string.Empty);

    /// <summary>Whether a feed request comes from this machine (Jellyfin's own tuner) with the feed key.</summary>
    public static bool IsFeedRequestAllowed(System.Net.IPAddress? remote, string? key, StreamSigner signer)
    {
        if (remote == null)
        {
            return false;
        }

        var ip = remote.IsIPv4MappedToIPv6 ? remote.MapToIPv4() : remote;
        return System.Net.IPAddress.IsLoopback(ip) && signer.Validate("feed:livetv", string.Empty, key);
    }

    private bool Allowed(string? key) => IsFeedRequestAllowed(HttpContext.Connection.RemoteIpAddress, key, _signer);

    /// <summary>M3U playlist fetched by Jellyfin's "m3u" tuner host.</summary>
    [HttpGet("livetv.m3u")]
    [Produces("audio/x-mpegurl")]
    public async Task<IActionResult> Playlist([FromQuery] string? k, CancellationToken cancellationToken)
    {
        if (!Allowed(k))
        {
            return StatusCode(403);
        }

        var games = await _cards.GetChannelGamesAsync(cancellationToken).ConfigureAwait(false);

        var sb = new StringBuilder();
        sb.AppendLine("#EXTM3U");

        // Absolute URLs: Jellyfin resolves the playlist server-side, so the signed
        // proxy URLs must carry scheme + host (loopback when fetched by the server).
        var baseUrl = $"{Request.Scheme}://{Request.Host.ToUriComponent()}";

        // numbered hottest-first, English before Spanish: native apps list channels by number (the RedZone channel
        // is always number 1)
        var all = _sourceManager.GetChannels();
        var channels = all.Where(c => c.IsSynthetic).Concat(LiveTvOrder(all.Where(c => !c.IsSynthetic).ToList(), games, SpanishInLiveTv)).ToList();
        for (var i = 0; i < channels.Count; i++)
        {
            var c = channels[i];
            sb.Append("#EXTINF:-1");
            AppendAttr(sb, "tvg-id", EpgChannelId(c));
            AppendAttr(sb, "tvg-chno", (i + 1).ToString(CultureInfo.InvariantCulture));
            AppendAttr(sb, "tvg-name", c.Name);
            AppendAttr(sb, "tvg-logo", ArtworkUrl(baseUrl, c, games));
            AppendAttr(sb, "group-title", c.Group);
            sb.Append(',').AppendLine(c.Name.Replace('\n', ' ').Replace('\r', ' '));
            sb.Append(baseUrl)
                .AppendLine(ProxyController.BuildLiveUrl(Request, _signer, c.Id));
        }

        return Content(sb.ToString(), "audio/x-mpegurl", Encoding.UTF8);
    }

    /// <summary>XMLTV guide fetched by Jellyfin's "xmltv" listings provider.</summary>
    [HttpGet("epg.xml")]
    [Produces("application/xml")]
    public async Task<IActionResult> Epg([FromQuery] string? k, CancellationToken cancellationToken)
    {
        if (!Allowed(k))
        {
            return StatusCode(403);
        }

        var now = DateTimeOffset.UtcNow;
        var games = await _cards.GetChannelGamesAsync(cancellationToken).ConfigureAwait(false);
        var baseUrl = $"{Request.Scheme}://{Request.Host.ToUriComponent()}";

        // Several source channels can share one tvg-id — emit each guide channel once.
        var channels = LiveTvOrder(_sourceManager.GetChannels(), games, SpanishInLiveTv)
            .GroupBy(EpgChannelId, StringComparer.OrdinalIgnoreCase)
            .Select(g => g.First())
            .ToList();

        using var ms = new MemoryStream();
        var settings = new XmlWriterSettings { Indent = true, Encoding = new UTF8Encoding(false) };
        using (var writer = XmlWriter.Create(ms, settings))
        {
            writer.WriteStartDocument();
            writer.WriteStartElement("tv");
            writer.WriteAttributeString("generator-info-name", "JellyTV");

            foreach (var c in channels)
            {
                writer.WriteStartElement("channel");
                writer.WriteAttributeString("id", X(EpgChannelId(c)));
                writer.WriteElementString("display-name", X(c.Name));
                writer.WriteStartElement("icon");
                writer.WriteAttributeString("src", X(ArtworkUrl(baseUrl, c, games)));
                writer.WriteEndElement();

                writer.WriteEndElement();
            }

            foreach (var c in channels)
            {
                var epgId = EpgChannelId(c);
                var programmes = _sourceManager.GetProgrammes(c.Id, now.AddHours(-24), now.AddDays(7));
                if (c.IsSynthetic)
                {
                    // the game on screen right now; the guide is re-read after a cut changes the live cards
                    var on = await _redZone.StatusAsync(cancellationToken).ConfigureAwait(false);
                    WriteProgramme(writer, epgId, new Programme
                    {
                        Title = on.Title ?? "No games live",
                        SubTitle = on.Reason == null ? c.Name : c.Name + " · " + on.Reason.ToUpperInvariant(),
                        Description = "One stream that cuts to the hottest live game: red zones, scores, two-minute drills and overtime.",
                        Category = "Sports",
                        IconUrl = ArtworkUrl(baseUrl, c, games),
                        Start = on.Since ?? now.AddHours(-1),
                        End = now.AddHours(12),
                        IsLive = true
                    });
                }
                else if (programmes.Count == 0 && games.TryGetValue(c.Id, out var game))
                {
                    // No EPG of its own, but we know the game: give native guides ("On Now", channel
                    // cards) a real title, times and artwork instead of a blank "Live" block.
                    var art = ArtworkUrl(baseUrl, c, games);
                    var about = string.Join(" · ", new[] { game.League, game.Broadcasts.Count > 0 ? "on " + string.Join(", ", game.Broadcasts.Take(3)) : null }.Where(s => !string.IsNullOrEmpty(s)));
                    if (game.Start > now)
                    {
                        WriteProgramme(writer, epgId, new Programme
                        {
                            Title = "Up next: " + GameSchedule.Title(game), Description = about, Category = "Sports",
                            IconUrl = art, Start = game.Start.AddHours(-12), End = game.Start, IsLive = false
                        });
                    }

                    WriteProgramme(writer, epgId, new Programme
                    {
                        Title = GameSchedule.Title(game), SubTitle = game.League, Description = about, Category = "Sports",
                        IconUrl = art, Start = game.Start, End = GameSchedule.ExpectedEnd(game, now), IsLive = true
                    });
                }
                else if (programmes.Count == 0)
                {
                    // No EPG data — emit one long "Live" block so the guide card is
                    // never empty. Covers the ~daily cadence of Jellyfin's guide refresh.
                    WriteProgramme(writer, epgId, new Programme
                    {
                        Title = c.Name,
                        Description = c.Group,
                        IconUrl = ArtworkUrl(baseUrl, c, games),
                        Start = now.AddHours(-1),
                        End = now.AddHours(24),
                        IsLive = true
                    });
                }
                else
                {
                    foreach (var p in programmes)
                    {
                        WriteProgramme(writer, epgId, p);
                    }
                }
            }

            writer.WriteEndElement();
            writer.WriteEndDocument();
        }

        return File(ms.ToArray(), "application/xml; charset=utf-8");
    }

    private static bool SpanishInLiveTv => Plugin.Instance?.Configuration.SpanishInLiveTv ?? true;

    /// <summary>The channels Jellyfin's Live TV gets, in number order: the English ones hottest-first (see
    /// <see cref="CardArtService.HeatOrder"/>), then the Spanish ones the same way — or none of those when
    /// <paramref name="spanish"/> is off (the Tally board still lists them).</summary>
    public static List<SourceChannel> LiveTvOrder(IReadOnlyList<SourceChannel> channels, IReadOnlyDictionary<string, GameInfo> games, bool spanish)
    {
        var english = CardArtService.HeatOrder(channels.Where(c => StreamLanguage.Of(c) != StreamLanguage.Spanish).ToList(), games);
        if (spanish)
        {
            english.AddRange(CardArtService.HeatOrder(channels.Where(c => StreamLanguage.Of(c) == StreamLanguage.Spanish).ToList(), games));
        }

        return english;
    }

    /// <summary>A provider logo when there is one and nothing better; otherwise a rendered card.
    /// The version suffix changes with the matchup, which is what makes Jellyfin refetch the image.</summary>
    private string ArtworkUrl(string baseUrl, SourceChannel c, IReadOnlyDictionary<string, GameInfo> games)
    {
        games.TryGetValue(c.Id, out var game);
        if (game == null && !string.IsNullOrEmpty(c.LogoUrl))
        {
            return c.LogoUrl;
        }

        return $"{baseUrl}{Request.PathBase.Value}{CardArtService.CardPath(c, game, DateTimeOffset.UtcNow)}";
    }

    /// <summary>ID linking a channel between the M3U (tvg-id) and the XMLTV (channel id).</summary>
    private static string EpgChannelId(SourceChannel c)
        => !string.IsNullOrEmpty(c.TvgId) ? c.TvgId : c.Id;

    private static void AppendAttr(StringBuilder sb, string name, string? value)
    {
        if (!string.IsNullOrEmpty(value))
        {
            // M3U attributes can't contain quotes/newlines.
            var clean = value.Replace('"', '\'').Replace('\n', ' ').Replace('\r', ' ');
            sb.Append(' ').Append(name).Append("=\"").Append(clean).Append('"');
        }
    }

    /// <summary>Text from a source without the characters XML cannot hold (control characters, lone surrogates): one
    /// such character in a provider's guide would otherwise make the writer throw and blank the whole guide.</summary>
    public static string X(string? text)
    {
        if (string.IsNullOrEmpty(text))
        {
            return string.Empty;
        }

        var sb = new StringBuilder(text.Length);
        for (var i = 0; i < text.Length; i++)
        {
            var ch = text[i];
            if (char.IsHighSurrogate(ch) && i + 1 < text.Length && char.IsLowSurrogate(text[i + 1]))
            {
                sb.Append(ch).Append(text[++i]);
            }
            else if (XmlConvert.IsXmlChar(ch))
            {
                sb.Append(ch);
            }
        }

        return sb.ToString();
    }

    private static void WriteProgramme(XmlWriter writer, string epgId, Programme p)
    {
        writer.WriteStartElement("programme");
        writer.WriteAttributeString("channel", X(epgId));
        // XMLTV date format: yyyyMMddHHmmss +zzzz
        writer.WriteAttributeString("start", p.Start.UtcDateTime.ToString("yyyyMMddHHmmss", CultureInfo.InvariantCulture) + " +0000");
        writer.WriteAttributeString("stop", p.End.UtcDateTime.ToString("yyyyMMddHHmmss", CultureInfo.InvariantCulture) + " +0000");

        writer.WriteElementString("title", string.IsNullOrEmpty(p.Title) ? "Live" : X(p.Title));
        if (!string.IsNullOrEmpty(p.SubTitle))
        {
            writer.WriteElementString("sub-title", X(p.SubTitle));
        }

        if (!string.IsNullOrEmpty(p.Description))
        {
            writer.WriteElementString("desc", X(p.Description));
        }

        if (!string.IsNullOrEmpty(p.Category))
        {
            writer.WriteElementString("category", X(p.Category));
        }

        if (!string.IsNullOrEmpty(p.IconUrl))
        {
            writer.WriteStartElement("icon");
            writer.WriteAttributeString("src", X(p.IconUrl));
            writer.WriteEndElement();
        }

        if (p.IsLive)
        {
            writer.WriteElementString("live", string.Empty);
        }

        writer.WriteEndElement();
    }
}
