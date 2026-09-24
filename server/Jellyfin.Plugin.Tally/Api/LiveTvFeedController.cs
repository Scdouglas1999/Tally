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
/// Anonymous feeds consumed by Jellyfin's built-in Live TV stack: an M3U tuner playlist
/// and an XMLTV guide. No [Authorize] — the server itself fetches these as an anonymous
/// client, and the embedded stream URLs are already HMAC-signed by the proxy.
/// </summary>
[ApiController]
[Route("JellyTV")]
public class LiveTvFeedController : ControllerBase
{
    private readonly SourceManager _sourceManager;
    private readonly StreamSigner _signer;
    private readonly CardArtService _cards;

    public LiveTvFeedController(SourceManager sourceManager, StreamSigner signer, CardArtService cards)
    {
        _sourceManager = sourceManager;
        _signer = signer;
        _cards = cards;
    }

    /// <summary>M3U playlist fetched by Jellyfin's "m3u" tuner host.</summary>
    [HttpGet("livetv.m3u")]
    [Produces("audio/x-mpegurl")]
    public async Task<IActionResult> Playlist(CancellationToken cancellationToken)
    {
        var games = await _cards.GetChannelGamesAsync(cancellationToken).ConfigureAwait(false);

        var sb = new StringBuilder();
        sb.AppendLine("#EXTM3U");

        // Absolute URLs: Jellyfin resolves the playlist server-side, so the signed
        // proxy URLs must carry scheme + host (loopback when fetched by the server).
        var baseUrl = $"{Request.Scheme}://{Request.Host.ToUriComponent()}";

        // numbered hottest-first: native apps list channels by number
        var channels = CardArtService.HeatOrder(_sourceManager.GetChannels(), games);
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
    public async Task<IActionResult> Epg(CancellationToken cancellationToken)
    {
        var now = DateTimeOffset.UtcNow;
        var games = await _cards.GetChannelGamesAsync(cancellationToken).ConfigureAwait(false);
        var baseUrl = $"{Request.Scheme}://{Request.Host.ToUriComponent()}";

        // Several source channels can share one tvg-id — emit each guide channel once.
        var channels = _sourceManager.GetChannels()
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
                writer.WriteAttributeString("id", EpgChannelId(c));
                writer.WriteElementString("display-name", c.Name);
                writer.WriteStartElement("icon");
                writer.WriteAttributeString("src", ArtworkUrl(baseUrl, c, games));
                writer.WriteEndElement();

                writer.WriteEndElement();
            }

            foreach (var c in channels)
            {
                var epgId = EpgChannelId(c);
                var programmes = _sourceManager.GetProgrammes(c.Id, now.AddHours(-24), now.AddDays(7));
                if (programmes.Count == 0 && games.TryGetValue(c.Id, out var game))
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

    private static void WriteProgramme(XmlWriter writer, string epgId, Programme p)
    {
        writer.WriteStartElement("programme");
        writer.WriteAttributeString("channel", epgId);
        // XMLTV date format: yyyyMMddHHmmss +zzzz
        writer.WriteAttributeString("start", p.Start.UtcDateTime.ToString("yyyyMMddHHmmss", CultureInfo.InvariantCulture) + " +0000");
        writer.WriteAttributeString("stop", p.End.UtcDateTime.ToString("yyyyMMddHHmmss", CultureInfo.InvariantCulture) + " +0000");

        writer.WriteElementString("title", string.IsNullOrEmpty(p.Title) ? "Live" : p.Title);
        if (!string.IsNullOrEmpty(p.SubTitle))
        {
            writer.WriteElementString("sub-title", p.SubTitle);
        }

        if (!string.IsNullOrEmpty(p.Description))
        {
            writer.WriteElementString("desc", p.Description);
        }

        if (!string.IsNullOrEmpty(p.Category))
        {
            writer.WriteElementString("category", p.Category);
        }

        if (!string.IsNullOrEmpty(p.IconUrl))
        {
            writer.WriteStartElement("icon");
            writer.WriteAttributeString("src", p.IconUrl);
            writer.WriteEndElement();
        }

        if (p.IsLive)
        {
            writer.WriteElementString("live", string.Empty);
        }

        writer.WriteEndElement();
    }
}
